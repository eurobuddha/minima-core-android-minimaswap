package com.eurobuddha.minimaswap.swap;

import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;

import java.security.SecureRandom;

/**
 * The OTC negotiation state machine + message send. Centralizes PROPOSE/COUNTER/ACCEPT/REJECT/EXECUTE so both
 * the foreground Activity and the background Service drive ONE state machine over a shared {@link OtcDb}. The
 * comms transport is stateless coins; all deal/turn state lives in OtcDb, correlated by {@code ref}.
 *
 * Flow: instigator PROPOSE → LP COUNTER/ACCEPT/REJECT → … → someone ACCEPTs → AGREED. The INSTIGATOR then locks
 * leg 1 (otc=TRUE) via {@link SwapEngine#executeOtcDeal} and sends EXECUTE(hash); the LP records the hash and its
 * OTC responder ({@code otcVerify*}) locks the counter-leg only if the on-chain lock matches the agreed terms.
 */
public final class OtcController {

    public interface Ui { void onDealsChanged(); void note(String title, String body); }
    public interface SendResult { void ok(); void err(String msg); }

    private static final long EXPIRE_MS = 60L * 60 * 1000;        // a deal awaiting the PEER this long is abandoned
    private static final long PRUNE_MS  = 24L * 60 * 60 * 1000;   // drop finished deals after this (bound the table)
    private static final long NOTE_MIN_GAP_MS = 30_000;          // coalesce inbound-PROPOSE notification spam
    private static final long EXEC_RESEND_GAP_MS = 90_000;              // resend a lost EXECUTE at most this often
    private static final long EXEC_RESEND_WINDOW_MS = 90L * 60 * 1000;  // keep retrying EXECUTE well into the ~2h timelock
                                                                        // so a slow LP recovery still completes (not refunds)

    private final NodeApi node;
    private final OtcDb otcDb;
    private final SwapEngine engine;
    private final Ui ui;
    private final SecureRandom rnd = new SecureRandom();

    private volatile CryptoProvider crypto;
    private volatile CommsIdentity identity;
    private volatile String myMinimaPk = "", myEthAddr = "";
    private volatile boolean myOfferEnabled = false;
    private volatile double myOfferSellSize = 0, myOfferBuySize = 0;
    private volatile long lastProposeNote = 0;
    private final java.util.Map<String, Long> lastExecResend = new java.util.concurrent.ConcurrentHashMap<>();

    public OtcController(NodeApi node, OtcDb otcDb, SwapEngine engine, Ui ui) {
        this.node = node; this.otcDb = otcDb; this.engine = engine; this.ui = ui;
    }

    public void setSelf(CryptoProvider crypto, CommsIdentity identity, String myMinimaPk, String myEthAddr) {
        this.crypto = crypto; this.identity = identity;
        this.myMinimaPk = myMinimaPk == null ? "" : myMinimaPk;
        this.myEthAddr = myEthAddr == null ? "" : myEthAddr;
    }

    public boolean ready() { return crypto != null && identity != null && !myMinimaPk.isEmpty() && !myEthAddr.isEmpty(); }

    /** My current published OTC availability — used to validate an inbound PROPOSE (I only accept deals on the side
     *  I advertise and within my advertised size; if I'm not live, I'm not an LP and ignore PROPOSEs). */
    public void setMyOffer(boolean enabled, double sellSize, double buySize) {
        this.myOfferEnabled = enabled; this.myOfferSellSize = sellSize; this.myOfferBuySize = buySize;
    }

    private static double parseD(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }

    private String rndHex(int bytes) {
        byte[] b = new byte[bytes]; rnd.nextBytes(b);
        StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static boolean isTerminal(String st) {
        return OtcDb.ST_REJECTED.equals(st) || OtcDb.ST_EXPIRED.equals(st) || OtcDb.ST_COMPLETE.equals(st);
    }

    // ---------- receive (CommsScanner Router) ----------

    /** CommsScanner Router: parse, authenticate, dedup, and drive the deal state machine. */
    public boolean route(String coinid, Opened opened, JSONObject coin) {
        if (opened == null || !opened.valid) return false;
        OtcMessage m = OtcMessage.fromWire(opened.plaintext);
        if (m == null || m.ref.isEmpty() || m.randomid.isEmpty()) return false;
        if (identity == null || !identity.publicId().equals(m.to)) return false;               // not for me
        if (opened.fromPublicId == null || !opened.fromPublicId.equals(m.from)) return false;   // bind envelope sender
        if (OtcMessage.PROPOSE.equals(m.type)) {
            // A PROPOSE I can't act on yet (my offer not loaded/live) must NOT burn its dedup slot — the comms coin is
            // re-scanned, so a later delivery can retry once I'm live. Only record it once it actually creates a deal.
            if (otcDb.getDeal(m.ref) != null) return false;   // already have this deal
            boolean created = applyPropose(m);
            if (created) otcDb.addMsg(toRow(m));
            return created;
        }
        if (!otcDb.addMsg(toRow(m))) return false;   // dedup by randomid
        apply(m);
        return true;
    }

    private OtcDb.Msg toRow(OtcMessage m) {
        OtcDb.Msg r = new OtcDb.Msg();
        r.randomid = m.randomid; r.ref = m.ref; r.type = m.type; r.from = m.from;
        r.side = m.side; r.amount = m.amount; r.price = m.price; r.hash = m.hash; r.date = m.date;
        return r;
    }

    /** Handle an inbound PROPOSE. Returns true iff it created a deal (I'm a live LP on that side, within size). A
     *  false result leaves NO dedup trace so a re-scan can retry once my offer is live (see {@link #route}). */
    private boolean applyPropose(OtcMessage m) {
        if (otcDb.getDeal(m.ref) != null) return false;                // first PROPOSE only (ref is unique)
        double cap = OtcOffer.LP_SELLS_MINIMA.equals(m.side) ? myOfferSellSize : myOfferBuySize;
        if (!myOfferEnabled || cap <= 0) return false;                 // I must be a LIVE LP on THIS side
        if (parseD(m.amount) <= 0 || parseD(m.amount) > cap + 1e-9 || parseD(m.price) <= 0) return false;
        OtcDb.Deal d = new OtcDb.Deal();
        d.ref = m.ref; d.role = OtcDb.ROLE_LP;
        d.peerCommsId = m.from; d.peerMinimaPk = m.minimaPk; d.peerEthAddr = m.ethAddr;
        d.side = m.side; d.amount = m.amount; d.price = m.price;
        d.status = OtcDb.ST_PROPOSED; d.whoseTurn = OtcDb.TURN_ME;
        otcDb.upsertDeal(d);
        noteProposeRateLimited(m.amount + " MINIMA @ " + m.price + " — your call");
        ui.onDealsChanged();
        return true;
    }

    private void apply(OtcMessage m) {
        OtcDb.Deal d = otcDb.getDeal(m.ref);
        // CONSENT + AUTHENTICITY: every non-PROPOSE transition needs an existing deal AND must genuinely come from
        // that deal's counterparty (not just any sealed sender). Without this, a hostile instigator could self-ACCEPT.
        if (d == null || m.from == null || !m.from.equals(d.peerCommsId)) return;
        if (OtcMessage.REJECT.equals(m.type)) {
            if (isTerminal(d.status)) return;
            d.status = OtcDb.ST_REJECTED; otcDb.upsertDeal(d);
            ui.note("OTC rejected", "The counterparty ended the deal");
        } else if (OtcMessage.EXECUTE.equals(m.type)) {
            if (!OtcDb.ROLE_LP.equals(d.role) || m.hash == null || m.hash.isEmpty()) return;
            // Honour EXECUTE if I've AGREED — OR if my last COUNTER is still outstanding (COUNTERED, whoseTurn=PEER):
            // a peer executing my standing offer IS an implicit accept of it. This is ORDER-INDEPENDENT, so it
            // survives the scanner delivering EXECUTE before the matching ACCEPT (coins come newest-first). The
            // on-chain otcVerify* remains the fund gate, and the terms are my own standing counter.
            boolean agreed = OtcDb.ST_AGREED.equals(d.status);
            boolean implicitAccept = OtcDb.ST_COUNTERED.equals(d.status) && OtcDb.TURN_PEER.equals(d.whoseTurn);
            if (!agreed && !implicitAccept) return;
            d.hash = m.hash; d.status = OtcDb.ST_EXECUTING; otcDb.upsertDeal(d);
            if (OtcOffer.LP_SELLS_MINIMA.equals(d.side)) {   // instigator locked USDT → find it via getContract
                engine.addIncomingHashlock(m.hash);
                engine.checkBuyNow(m.hash);
            }   // else instigator locked MINIMA → the Minima scan (addressed to my key) finds it; no eth polling needed
            ui.note("OTC executing", "Counterparty locked — locking your side");
        } else if (!OtcDb.TURN_PEER.equals(d.whoseTurn)) {
            return;   // CONSENT: a COUNTER/ACCEPT is only legal when I'm genuinely waiting on the peer (their turn)
        } else if (OtcMessage.COUNTER.equals(m.type)) {
            if (isTerminal(d.status) || parseD(m.amount) <= 0 || parseD(m.price) <= 0) return;
            d.amount = m.amount; d.price = m.price; d.status = OtcDb.ST_COUNTERED; d.whoseTurn = OtcDb.TURN_ME;
            otcDb.upsertDeal(d);
            ui.note("OTC counter", m.amount + " MINIMA @ " + m.price + " — your call");
        } else if (OtcMessage.ACCEPT.equals(m.type)) {
            if (isTerminal(d.status) || OtcDb.ST_AGREED.equals(d.status) || OtcDb.ST_EXECUTING.equals(d.status)) return;
            d.status = OtcDb.ST_AGREED;
            d.whoseTurn = OtcDb.ROLE_INSTIGATOR.equals(d.role) ? OtcDb.TURN_ME : OtcDb.TURN_PEER;
            otcDb.upsertDeal(d);
            ui.note("OTC agreed", d.amount + " MINIMA @ " + d.price);
            if (OtcDb.ROLE_INSTIGATOR.equals(d.role)) executeDeal(d);   // I opened it → I lock leg 1
        }
        ui.onDealsChanged();
    }

    private void noteProposeRateLimited(String body) {
        long now = System.currentTimeMillis();
        if (now - lastProposeNote < NOTE_MIN_GAP_MS) return;
        lastProposeNote = now;
        ui.note("OTC offer received", body);
    }

    // ---------- send (outbound) ----------

    /** Instigator opens a deal against an LP offer. */
    public void propose(OtcOffer lp, String dealSide, String amount, String price, SendResult res) {
        if (!ready()) { res.err("still connecting"); return; }
        if (parseD(amount) <= 0 || parseD(price) <= 0) { res.err("enter amount + price"); return; }
        OtcDb.Deal d = new OtcDb.Deal();
        d.ref = rndHex(16); d.role = OtcDb.ROLE_INSTIGATOR;
        d.peerCommsId = lp.commsPublicId; d.peerMinimaPk = lp.minimaPublicKey; d.peerEthAddr = lp.ethAddress;
        d.side = dealSide; d.amount = amount; d.price = price;
        d.status = OtcDb.ST_PROPOSED; d.whoseTurn = OtcDb.TURN_PEER;
        otcDb.upsertDeal(d);
        sendMsg(d, OtcMessage.PROPOSE, "", res);
    }

    public void counter(OtcDb.Deal d, String amount, String price, SendResult res) {
        if (!ready() || isTerminal(d.status) || !OtcDb.TURN_ME.equals(d.whoseTurn)) { res.err("not your turn"); return; }
        if (parseD(amount) <= 0 || parseD(price) <= 0) { res.err("enter amount + price"); return; }
        d.amount = amount; d.price = price; d.status = OtcDb.ST_COUNTERED; d.whoseTurn = OtcDb.TURN_PEER;
        otcDb.upsertDeal(d);
        sendMsg(d, OtcMessage.COUNTER, "", res);
    }

    public void accept(OtcDb.Deal d, SendResult res) {
        if (!ready() || isTerminal(d.status) || !OtcDb.TURN_ME.equals(d.whoseTurn)) { res.err("not your turn"); return; }
        d.status = OtcDb.ST_AGREED;
        d.whoseTurn = OtcDb.ROLE_INSTIGATOR.equals(d.role) ? OtcDb.TURN_ME : OtcDb.TURN_PEER;
        otcDb.upsertDeal(d);
        sendMsg(d, OtcMessage.ACCEPT, "", new SendResult() {
            @Override public void ok() { if (OtcDb.ROLE_INSTIGATOR.equals(d.role)) executeDeal(d); res.ok(); }
            @Override public void err(String m) { res.err(m); }
        });
    }

    public void reject(OtcDb.Deal d, SendResult res) {
        if (!ready()) { res.err("still connecting"); return; }
        d.status = OtcDb.ST_REJECTED; otcDb.upsertDeal(d);
        sendMsg(d, OtcMessage.REJECT, "", res);
    }

    private void sendMsg(OtcDb.Deal d, String type, String hash, SendResult res) {
        OtcMessage m = new OtcMessage();
        m.type = type; m.ref = d.ref; m.randomid = rndHex(12);
        m.from = identity.publicId(); m.to = d.peerCommsId; m.date = System.currentTimeMillis();
        m.side = d.side; m.amount = d.amount; m.price = d.price;
        m.minimaPk = myMinimaPk; m.ethAddr = myEthAddr; m.hash = hash;
        otcDb.addMsg(toRow(m));   // record my own outbound message in the thread
        ui.onDealsChanged();
        m.send(node, crypto, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { res.ok(); }
            @Override public void onFailed(String msg) { res.err(msg); }
        });
    }

    /** Instigator: on AGREED, lock leg 1 (otc=TRUE) then EXECUTE-notify the LP with the hashlock. Idempotent: the
     *  status is flipped to EXECUTING synchronously BEFORE the async lock, so a second call is a no-op. */
    public synchronized void executeDeal(OtcDb.Deal d) {
        if (!ready() || !OtcDb.ROLE_INSTIGATOR.equals(d.role)) return;
        OtcDb.Deal cur = otcDb.getDeal(d.ref);
        if (cur == null || !OtcDb.ST_AGREED.equals(cur.status)) return;   // already executing / not agreed / terminal
        cur.status = OtcDb.ST_EXECUTING; otcDb.upsertDeal(cur); ui.onDealsChanged();
        engine.executeOtcDeal(cur, new SwapEngine.StartCb() {
            @Override public void ok(String hash) {
                OtcDb.Deal c2 = otcDb.getDeal(cur.ref); if (c2 == null) return;
                c2.hash = hash; c2.status = OtcDb.ST_EXECUTING; otcDb.upsertDeal(c2);
                sendMsg(c2, OtcMessage.EXECUTE, hash, new SendResult() { public void ok() {} public void err(String m) {} });
                ui.note("OTC executing", "Locked your side — waiting for the counterparty");
                ui.onDealsChanged();
            }
            @Override public void err(String m) {
                OtcDb.Deal c2 = otcDb.getDeal(cur.ref);
                if (c2 != null && OtcDb.ST_EXECUTING.equals(c2.status) && (c2.hash == null || c2.hash.isEmpty())) {
                    c2.status = OtcDb.ST_AGREED; otcDb.upsertDeal(c2); ui.onDealsChanged();   // reset so it can retry
                }
                ui.note("OTC execute failed", m);
            }
        });
    }

    /** Expire deals stuck awaiting the PEER past {@link #EXPIRE_MS}; prune long-finished deals so the table can't
     *  grow unboundedly under message spam. Also settle EXECUTING deals whose on-chain swap has completed/refunded. */
    public void expireStale(long now) {
        for (OtcDb.Deal d : otcDb.allDeals()) {
            boolean stale = now - d.updated > EXPIRE_MS;
            if (OtcDb.ST_EXECUTING.equals(d.status) && d.hash != null && !d.hash.isEmpty()) {
                String ss = engine.swapStatus(d.hash);
                if (SwapDb.ST_COMPLETE.equals(ss)) { d.status = OtcDb.ST_COMPLETE; otcDb.upsertDeal(d); ui.onDealsChanged(); }
                else if (SwapDb.ST_REFUNDED.equals(ss) || SwapDb.ST_ERROR.equals(ss)) { d.status = OtcDb.ST_REJECTED; otcDb.upsertDeal(d); ui.onDealsChanged(); }
                else if (ss == null && stale) { d.status = OtcDb.ST_EXPIRED; otcDb.upsertDeal(d); ui.onDealsChanged(); }   // a mismatched EXECUTE never produced a swap
                else if (OtcDb.ROLE_INSTIGATOR.equals(d.role) && SwapDb.ST_STARTED.equals(ss)
                        && now - d.created < EXEC_RESEND_WINDOW_MS) resendExecute(d, now);   // leg 1 locked but LP silent → my EXECUTE may have been lost
            } else if (stale && (OtcDb.ST_AGREED.equals(d.status)
                    || (OtcDb.ST_EXECUTING.equals(d.status) && (d.hash == null || d.hash.isEmpty()))   // execute never produced a hash
                    || ((OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status)) && OtcDb.TURN_PEER.equals(d.whoseTurn)))) {
                d.status = OtcDb.ST_EXPIRED; otcDb.upsertDeal(d); ui.onDealsChanged();   // no progress → abandon (any locked leg refunds at timelock)
            }
            if (isTerminal(d.status) && now - d.updated > PRUNE_MS) { otcDb.deleteDeal(d.ref); lastExecResend.remove(d.ref); }
        }
    }

    /** Leg 1 is locked but the counterparty still hasn't responded — my one-shot EXECUTE may have failed to send.
     *  Resend it (throttled per deal) so a lost notification doesn't strand the deal until refund. Each resend is a
     *  fresh message (new randomid); the LP's apply(EXECUTE) is idempotent, and re-recording the same hash is harmless. */
    private void resendExecute(OtcDb.Deal d, long now) {
        if (!ready()) return;
        Long last = lastExecResend.get(d.ref);
        if (last != null && now - last < EXEC_RESEND_GAP_MS) return;
        lastExecResend.put(d.ref, now);
        sendMsg(d, OtcMessage.EXECUTE, d.hash, new SendResult() { public void ok() {} public void err(String m) {} });
    }
}
