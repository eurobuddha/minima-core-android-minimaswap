package org.minimarex.minimaswap.swap;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.comms.NodeApi;
import org.minimarex.minimaswap.eth.EthHtlc;
import org.minimarex.minimaswap.eth.EthNet;
import org.minimarex.minimaswap.eth.EthRpc;
import org.minimarex.minimaswap.eth.EthWallet;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The end-to-end atomic-swap engine — a faithful native port of the bridge MiniDapp's service.js loop
 * plus its apiminima.js / apieth.js collect / expired / discovery routines.
 *
 * <p><b>Trustless model (verbatim upstream).</b> There is no server and no direct messaging. The party
 * who locks FIRST (the <i>initiator</i>) generates the secret and the hashlock and gets the LONGER
 * timelock; the counterparty (the <i>responder</i>, whose published order was taken) discovers that leg by
 * scanning the chain, validates it against their <b>own published order</b>, and locks the SECOND leg with
 * a SHORTER timelock. The initiator then claims the second leg, revealing the secret on-chain; the
 * responder reads the secret from that chain and claims the first leg. {@code contractId = sha256(hashlock)}
 * is deterministic, so nothing needs to be exchanged off-chain.
 *
 * <p><b>Idempotency / resume.</b> Every action is guarded by {@link SwapDb} (secrets / event log) exactly
 * like upstream, so the watcher can fire the same checks every cycle and a kill-and-restart simply
 * re-discovers in-flight swaps from the chains + DB. An in-memory {@link #inflight} set additionally stops
 * a duplicate post within overlapping cycles before the DB guard lands.
 *
 * <p><b>Timelocks (htlcvars.js).</b> first Minima leg = block+144; first ETH leg = now+7200s; second ETH
 * leg = now+1800s; second Minima leg = block+36. A responder refuses to lock the second leg unless the
 * first leg still has ≥ half its window left (72 Minima blocks / 3600 ETH secs).
 */
public final class SwapEngine {

    // timelock constants — verbatim from htlcvars.js (MINIMA_BLOCK_TIME 50, main 2h)
    public static final int TIMELOCK_BLOCKS              = MinimaHtlc.TIMELOCK_BLOCKS;       // 144
    public static final long TIMELOCK_SECS              = 60 * 60 * 2;                       // 7200
    public static final int  CP_BLOCKS_CHECK            = TIMELOCK_BLOCKS / 2;               // 72
    public static final long CP_SECS_CHECK              = TIMELOCK_SECS / 2;                 // 3600
    public static final int  CP_BLOCKS                  = (TIMELOCK_BLOCKS / 2) / 2;         // 36
    public static final long CP_SECS                    = (TIMELOCK_SECS / 2) / 2;           // 1800
    private static final long SECRETS_BACKLOG           = 50 + (TIMELOCK_SECS / 15);         // ETH blocks
    private static final int  NOTIFY_SCAN_DEPTH         = 256;                               // bounded notify scan
    private static final BigInteger MAX_UINT = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    public interface Notifier {
        void notify(String title, String body);   // OS notification for a meaningful transition
        void onSwapsChanged();                     // ask the UI to re-render swap cards
    }
    public interface StartCb { void ok(String hash); void err(String msg); }
    public interface InspectCb { void report(java.util.List<String> lines); }

    private final NodeApi node;
    private final MinimaHtlc minima;
    private final SwapDb db;
    private final EthWallet wallet;
    private final Handler ui;
    private final Notifier notifier;
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private static final long APPROVE_TTL_MS = 5 * 60 * 1000;   // re-fire a stuck approve after this
    private static final long ETH_SCAN_CAP = 5000;             // max getLogs span (catch-up after downtime)

    private volatile EthRpc rpc;
    private volatile EthNet net;
    private volatile String myMinimaPk;                         // my persisted swap identity (one of 64)
    private volatile Set<String> myPubkeys = Collections.emptySet();  // all 64 default keys (owner/refund match)
    private volatile Order myOrder;                 // my published order — the responder-side match guard

    private volatile long lastSecretBlock = -1;     // ETH block bookmark (checkETHNewSecrets)
    private volatile long lastEthScanned = -1;      // ETH block bookmark (New-contract discovery)
    private final Set<String> inflight = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> approvePending = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> incoming = Collections.synchronizedSet(new HashSet<>());   // hashlocks announced by a buyer's handshake
    private final Set<String> declined = Collections.synchronizedSet(new HashSet<>());   // handshake buys we've already notified as declined

    public SwapEngine(NodeApi node, MinimaHtlc minima, SwapDb db, EthWallet wallet,
                      Handler ui, Notifier notifier) {
        this.node = node; this.minima = minima; this.db = db; this.wallet = wallet;
        this.ui = ui; this.notifier = notifier;
    }

    public void setNetwork(EthRpc rpc, EthNet net) {
        this.rpc = rpc; this.net = net;
        lastSecretBlock = -1;             // re-scan secrets from the backlog on the new chain
        lastEthScanned = -1;
        approvePending.clear();
    }
    public void setMyMinimaPk(String pk) { this.myMinimaPk = pk; }
    /** The node's full 64-key set, so refunds work for a coin locked under any default key. */
    public void setMyPubkeys(Set<String> keys) { this.myPubkeys = keys == null ? Collections.emptySet() : keys; }
    public void setMyOrder(Order o) { this.myOrder = o; }

    /** A taker told us (via the sealed handshake) the hashlock of a USDT lock addressed to us. We discover it
     *  by deterministic contractId via getContract (free-RPC-safe) instead of eth_getLogs, then respond. */
    public void addIncomingHashlock(String hash) {
        if (hash != null && !hash.isEmpty()) incoming.add(MinimaHtlc.normKey(hash));
    }
    public SwapDb db() { return db; }
    public void shutdown() { io.shutdownNow(); }

    private boolean ready() { return rpc != null && net != null && wallet.ready() && myMinimaPk != null && minima.ready(); }
    private String myEth() { return wallet.address(); }

    // ============================================================ initiate (user-driven)

    /** I give MINIMA, want an ERC20 token. I lock MINIMA FIRST (block+144) and generate the secret. */
    public void startMinimaToErc20(Order maker, String sellMinima, String tokenSymbol, String buyTokenAmount, StartCb cb) {
        if (!ready()) { cb.err("Not ready"); return; }
        final EthNet.Token token = net.token(tokenSymbol);
        if (token == null) { cb.err("Unknown token " + tokenSymbol); return; }
        final String reqToken = "ETH:" + token.address;
        minima.generateSecret(new MinimaHtlc.SecretCb() {
            @Override public void ok(String secret, String hash) {
                minima.currentBlock(new MinimaHtlc.BlockCb() {
                    @Override public void ok(int block) {
                        final int timelock = block + TIMELOCK_BLOCKS;
                        minima.lock(sellMinima, buyTokenAmount, token.address, maker.minimaPublicKey,
                                myEth(), hash, timelock, "FALSE", new MinimaHtlc.PostCb() {
                            @Override public void ok(String txpowid) {
                                db.insertSecret(hash, secret);
                                db.logEvent(hash, SwapDb.EV_STARTED, "minima", sellMinima, txpowid);
                                db.insertMyHtlc(hash, buyTokenAmount, reqToken);
                                SwapDb.Swap s = baseSwap(hash, "INITIATOR", "MINIMA_TO_ERC20",
                                        "MINIMA", sellMinima, tokenSymbol, buyTokenAmount, maker.ethAddress);
                                s.myTimelock = timelock; s.myLegIsMinima = true; s.status = SwapDb.ST_STARTED;
                                db.upsertSwap(s);
                                notifier.onSwapsChanged();
                                cb.ok(hash);
                            }
                            @Override public void err(String m) { cb.err(m); }
                        });
                    }
                    @Override public void err(String m) { cb.err(m); }
                });
            }
            @Override public void err(String m) { cb.err(m); }
        });
    }

    /** I give an ERC20 token, want MINIMA. I lock the ERC20 FIRST (now+7200s) and generate the secret. */
    public void startErc20ToMinima(Order maker, String tokenSymbol, String sellTokenAmount, String buyMinima, StartCb cb) {
        if (!ready()) { cb.err("Not ready"); return; }
        final EthNet.Token token = net.token(tokenSymbol);
        if (token == null) { cb.err("Unknown token " + tokenSymbol); return; }
        minima.generateSecret(new MinimaHtlc.SecretCb() {
            @Override public void ok(String secret, String hash) {
                io.execute(() -> {
                    try {
                        Credentials creds = wallet.creds();
                        EthHtlc eth = new EthHtlc(rpc, creds, net);
                        BigInteger sellRaw = parseUnits(sellTokenAmount, token.decimals);
                        BigInteger reqRaw = parseUnits(buyMinima, 18);
                        ensureAllowanceBlocking(eth, token.address, sellRaw);
                        long timelock = nowUnix() + TIMELOCK_SECS;
                        String txhash = eth.newContract(myMinimaPk, maker.ethAddress, hash,
                                BigInteger.valueOf(timelock), token.address, sellRaw, reqRaw, false);
                        ui.post(() -> {
                            db.insertSecret(hash, secret);
                            db.logEvent(hash, SwapDb.EV_STARTED, "ETH:" + token.address, sellTokenAmount, txhash);
                            db.insertMyHtlc(hash, buyMinima, "minima");
                            SwapDb.Swap s = baseSwap(hash, "INITIATOR", "ERC20_TO_MINIMA",
                                    tokenSymbol, sellTokenAmount, "MINIMA", buyMinima, maker.minimaPublicKey);
                            s.myTimelock = timelock; s.myLegIsMinima = false; s.status = SwapDb.ST_STARTED;
                            s.contractId = EthHtlc.contractId(hash);
                            db.upsertSwap(s);
                            notifier.onSwapsChanged();
                            cb.ok(hash);
                        });
                    } catch (Exception e) {
                        ui.post(() -> cb.err(e.getMessage()));
                    }
                });
            }
            @Override public void err(String m) { cb.err(m); }
        });
    }

    // ============================================================ inspect (live diagnostic for one swap)

    /** Probe both legs on-chain for one swap and report a plain-language status (why it's stuck / where it is). */
    public void inspect(String hash, InspectCb cb) {
        if (!ready()) { ui.post(() -> cb.report(java.util.Collections.singletonList("Wallet/node not ready yet — open the app and wait a moment."))); return; }
        final SwapDb.Swap s = db.getSwap(hash);
        if (s == null) { ui.post(() -> cb.report(java.util.Collections.singletonList("No record of this swap."))); return; }
        // Read the Minima side first (async node.cmd), then the ETH side (blocking, on io), then report.
        minima.currentBlock(new MinimaHtlc.BlockCb() {
            @Override public void ok(int block) {
                minima.scanMyHtlcCoins(coins -> {
                    JSONObject myMinimaCoin = null, counterMinimaCoin = null;
                    for (int i = 0; i < coins.length(); i++) {
                        JSONObject c = coins.optJSONObject(i);
                        if (c == null || !MinimaHtlc.normKey(MinimaHtlc.stateAt(c, 5)).equals(MinimaHtlc.normKey(hash))) continue;
                        if (isMyOwnedKey(MinimaHtlc.stateAt(c, 0))) myMinimaCoin = c;        // a coin I locked
                        if (isMyPublishKey(MinimaHtlc.stateAt(c, 4))) counterMinimaCoin = c; // a coin locked to me
                    }
                    final JSONObject myMin = myMinimaCoin, cpMin = counterMinimaCoin;
                    io.execute(() -> reportInspection(s, hash, block, myMin, cpMin, cb));
                }, err -> io.execute(() -> reportInspection(s, hash, block, null, null, cb)));
            }
            @Override public void err(String m) { io.execute(() -> reportInspection(s, hash, -1, null, null, cb)); }
        });
    }

    /** [io] compose the inspection report from the Minima coins (already scanned) + a live ETH getContract read. */
    private void reportInspection(SwapDb.Swap s, String hash, int block, JSONObject myMin, JSONObject cpMin, InspectCb cb) {
        java.util.List<String> L = new java.util.ArrayList<>();
        try {
            boolean sell = "MINIMA_TO_ERC20".equals(s.direction);   // I sold MINIMA → counter leg is ETH USDT
            boolean secretKnown = db.getSecret(hash) != null;
            L.add((sell ? "Sell " : "Buy ") + s.sellAmount + " " + s.sellToken + " → " + s.buyAmount + " " + s.buyToken
                    + "  ·  " + s.status.toLowerCase());
            EthHtlc eth = new EthHtlc(rpc, wallet.creds(), net);

            // ---- my leg ----
            if (s.myLegIsMinima) {
                if (myMin != null) {
                    int tl = parseInt(MinimaHtlc.stateAt(myMin, 3));
                    L.add("• Your " + s.sellAmount + " MINIMA: LOCKED — refundable at block " + tl
                            + (block > 0 ? " (~" + Math.max(0, (tl - block)) * 50 / 60 + " min)" : ""));
                } else {
                    L.add("• Your " + s.sellAmount + " MINIMA: not locked on-chain now — "
                            + (SwapDb.ST_REFUNDED.equals(s.status) ? "refunded" : SwapDb.ST_COMPLETE.equals(s.status) ? "claimed by the counterparty (complete)" : "spent/claimed"));
                }
            } else {
                boolean stillLocked = eth.canCollect(s.contractId);
                L.add("• Your " + s.sellAmount + " " + s.sellToken + ": " + (stillLocked ? "LOCKED on Ethereum" : "claimed or refunded"));
            }

            // ---- counterparty leg ----
            if (sell) {
                // counter = ETH USDT to me — read by deterministic contractId (no eth_getLogs)
                EthHtlc.Contract gc = eth.getContract(EthHtlc.contractId(hash));
                if (gc == null) {
                    L.add("• Counterparty " + s.buyToken + " leg: NOT FOUND yet — the maker hasn't locked it.");
                } else {
                    boolean claimable = !gc.withdrawn && !gc.refunded;
                    L.add("• Counterparty " + s.buyToken + " leg: FOUND " + EthWallet.format(gc.amount, decimalsOf(gc.tokenContract), 6)
                            + " " + s.buyToken + (claimable ? " — claimable now" : (gc.withdrawn ? " — withdrawn (complete)" : " — refunded")));
                    if (claimable) L.add("→ Claiming on the next poll — your " + s.buyToken + " arrives shortly.");
                    else if (gc.refunded) L.add("→ Maker's leg timed out & refunded; your MINIMA auto-refunds at block " + s.myTimelock + ".");
                }
            } else {
                // counter = MINIMA to me — real on-chain check of the maker's lock
                if (cpMin != null) {
                    L.add("• Counterparty MINIMA leg: FOUND " + cpMin.optString("amount", "?") + " MINIMA — "
                            + (secretKnown ? "claimable now (claiming on the next poll)" : "waiting for the secret"));
                } else {
                    L.add("• Counterparty MINIMA leg: NOT FOUND yet — the maker hasn't locked MINIMA (or it's <2 confirmations old).");
                }
            }

            L.add("• Secret: " + (secretKnown ? "known (you can claim)" : "not revealed yet"));
            for (SwapDb.Event e : db.getEvents(hash)) {
                String n = e.note == null ? "" : e.note.toLowerCase();
                if (n.contains("mismatch") || n.contains("invalid") || n.contains("incorrect")
                        || n.contains("too close") || n.contains("fail")) L.add("⚠ " + e.note);
            }
            if (!SwapDb.ST_COMPLETE.equals(s.status) && !SwapDb.ST_REFUNDED.equals(s.status))
                L.add("(swaps take a few minutes — ~90s polls + 2 confirmations + on-phone PoW per step)");
        } catch (Exception e) {
            L.add("Check failed (RPC/node): " + e.getMessage());
        }
        final java.util.List<String> out = L;
        ui.post(() -> cb.report(out));
    }

    // ============================================================ watcher poll

    /** One watcher cycle: drive both chains. Safe to call repeatedly (every action is idempotency-guarded). */
    public void poll() {
        if (!ready()) return;
        minima.currentBlock(new MinimaHtlc.BlockCb() {
            @Override public void ok(int block) {
                runMinimaChecks(block);
                io.execute(() -> runEthChecks(block));
            }
            @Override public void err(String m) { /* node busy; next cycle */ }
        });
    }

    // ---- Minima side (node.cmd; main thread) ----

    private void runMinimaChecks(final int block) {
        // Harvest the revealed secret for each leg I locked that's still waiting — one hashlock-FILTERED query
        // per pending swap, so we never pull the whole (global, unbounded) notify address.
        for (String h : pendingSecretHashes()) {
            minima.scanNotifySecret(h, NOTIFY_SCAN_DEPTH, coins -> harvestNotifySecrets(coins), e -> {});
        }
        minima.scanMyHtlcCoins(coins -> {
            for (int i = 0; i < coins.length(); i++) {
                JSONObject coin = coins.optJSONObject(i);
                if (coin == null) continue;
                try {
                    String owner = MinimaHtlc.stateAt(coin, 0);
                    String receiver = MinimaHtlc.stateAt(coin, 4);
                    if (isMyPublishKey(receiver)) {
                        checkCanSwapCoin(coin, block);      // a swap addressed to my published identity
                    } else if (isMyOwnedKey(owner)) {
                        checkExpiredMinima(coin, block);    // a coin I locked under any of my 64 keys
                    }
                } catch (Exception ignore) {}
            }
        }, e -> {});
    }

    /** Port of _checkCanSwapCoin: I am the receiver(state[4]) of a Minima HTLC coin. */
    private void checkCanSwapCoin(JSONObject coin, int block) {
        String hash = MinimaHtlc.stateAt(coin, 5);
        if (hash.isEmpty()) return;
        int timelock = parseInt(MinimaHtlc.stateAt(coin, 3));
        String reqTokenAddr = stripReqToken(MinimaHtlc.stateAt(coin, 2));   // ERC20 the maker wants back
        String secret = db.getSecret(hash);

        if (secret != null) {
            // I know the secret → claim this coin (reveals it via the notify coin).
            String[] req = db.getRequest(hash);
            if (req != null && !amountTokenOk(req, coin.optString("amount", "0"), reqTokenAddr, false)) {
                db.logEvent(hash, SwapDb.EV_COLLECT, "minima", "0", "counterparty amount/token mismatch");
                return;
            }
            if (db.haveCollect(hash) || !inflight.add("claimM:" + hash)) return;
            db.setSwapStatus(hash, SwapDb.ST_CLAIMING);   // counterparty leg found — claiming now
            notifier.onSwapsChanged();
            minima.claim(coin, hash, secret, new MinimaHtlc.PostCb() {
                @Override public void ok(String txpowid) {
                    db.logEvent(hash, SwapDb.EV_COLLECT, "minima", coin.optString("amount", ""), txpowid);
                    db.setSwapStatus(hash, SwapDb.ST_COMPLETE);
                    notifier.notify("Swap complete", "Claimed " + coin.optString("amount", "") + " MINIMA");
                    notifier.onSwapsChanged();
                    inflight.remove("claimM:" + hash);
                }
                @Override public void err(String m) { inflight.remove("claimM:" + hash); }
            });
            return;
        }

        // I don't know the secret → I'm a MINIMA→ERC20 responder; lock the ETH counter-leg.
        if ("TRUE".equals(MinimaHtlc.stateAt(coin, 7))) return;            // OTC: manual only
        if (db.haveSentCounterParty(hash)) return;
        if (timelock - block < CP_BLOCKS_CHECK) return;                    // first leg too close to expiry
        if (!acceptTakerSellMinima(coin, reqTokenAddr)) return;          // must match my published order
        if (!inflight.add("cpEth:" + hash)) return;
        io.execute(() -> lockEthCounterLeg(coin, hash, reqTokenAddr));
    }

    private void checkExpiredMinima(JSONObject coin, int block) {
        int timelock = parseInt(MinimaHtlc.stateAt(coin, 3));
        if (block <= timelock) return;
        String hash = MinimaHtlc.stateAt(coin, 5);
        if (db.haveCollectExpired(hash) || !inflight.add("refundM:" + hash)) return;
        minima.refund(coin, new MinimaHtlc.PostCb() {
            @Override public void ok(String txpowid) {
                db.logEvent(hash, SwapDb.EV_EXPIRED, "minima", coin.optString("amount", ""), txpowid);
                db.setSwapStatus(hash, SwapDb.ST_REFUNDED);
                notifier.notify("Swap refunded", "Timelock passed — reclaimed your MINIMA");
                notifier.onSwapsChanged();
                inflight.remove("refundM:" + hash);
            }
            @Override public void err(String m) { inflight.remove("refundM:" + hash); }
        });
    }

    /** [io] lock the ETH counter-leg for a MINIMA→ERC20 swap I'm responding to (now+1800s). */
    private void lockEthCounterLeg(JSONObject coin, String hash, String reqTokenAddr) {
        try {
            EthNet.Token token = net.tokenByAddress(reqTokenAddr);
            if (token == null) { inflight.remove("cpEth:" + hash); return; }
            Credentials creds = wallet.creds();
            EthHtlc eth = new EthHtlc(rpc, creds, net);
            String tokenHuman = MinimaHtlc.stateAt(coin, 1);               // ERC20 amount the maker requested
            String reqMinimaHuman = coin.optString("amount", "0");        // MINIMA they locked
            String receiverEth = MinimaHtlc.stateAt(coin, 6);             // maker's ETH address (ownereth)
            BigInteger sellRaw = parseUnits(tokenHuman, token.decimals);
            BigInteger reqRaw = parseUnits(reqMinimaHuman, 18);
            if (!approveIfReady(eth, token.address, sellRaw)) { inflight.remove("cpEth:" + hash); return; }
            long timelock = nowUnix() + CP_SECS;
            String txhash = eth.newContract(myMinimaPk, receiverEth, hash,
                    BigInteger.valueOf(timelock), token.address, sellRaw, reqRaw, false);
            ui.post(() -> {
                db.logEvent(hash, SwapDb.EV_CPSENT, "ETH:" + token.address, tokenHuman, txhash);
                SwapDb.Swap s = baseSwap(hash, "RESPONDER", "MINIMA_TO_ERC20",
                        token.symbol, tokenHuman, "MINIMA", reqMinimaHuman, receiverEth);
                s.myTimelock = timelock; s.myLegIsMinima = false; s.contractId = EthHtlc.contractId(hash);
                s.status = SwapDb.ST_LOCKED;
                db.upsertSwap(s);
                notifier.notify("Locked your " + token.symbol, "Waiting for the counterparty to reveal the secret");
                notifier.onSwapsChanged();
                inflight.remove("cpEth:" + hash);
            });
        } catch (Exception e) {
            inflight.remove("cpEth:" + hash);
        }
    }

    // ---- Ethereum side ([io]; blocking RPC) ----

    private void runEthChecks(final int minimaBlock) {
        EthHtlc eth;
        try { eth = new EthHtlc(rpc, wallet.creds(), net); } catch (Exception e) { return; }
        final String myEth = wallet.address();

        // PRIMARY (works on free/keyless RPCs): for every known swap, read its ETH leg by deterministic
        // contractId = sha256(hashlock) via getContract (eth_call) — claim, harvest the revealed preimage,
        // or refund. No eth_getLogs, which free nodes gate as an "archive" request.
        for (SwapDb.Swap s : db.allSwaps()) {
            if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status)) continue;
            try { checkEthContractFor(eth, s, myEth); } catch (Exception ignore) {}
        }

        // BUY handshake (free-RPC-safe): a buyer told us the hashlock of a USDT lock addressed to us. Find it by
        // deterministic contractId via getContract (no eth_getLogs) and run the normal responder path (lock the
        // MINIMA counter-leg). Once it becomes a known swap, the loop above + the secondary path take over.
        for (String hash : new java.util.ArrayList<>(incoming)) {
            if (db.getSwap(hash) != null || db.haveSentCounterParty(hash)) { incoming.remove(hash); continue; }
            try {
                EthHtlc.Contract c = eth.getContract(EthHtlc.contractId(hash));
                if (c == null) continue;   // the buyer's USDT leg isn't visible yet — retry next cycle
                if (c.receiver != null && c.receiver.equalsIgnoreCase(myEth) && !c.withdrawn && !c.refunded) {
                    checkCanCollectEth(eth, c, minimaBlock);
                }
            } catch (Exception ignore) {}
        }

        // SECONDARY (best-effort): eth_getLogs to discover a brand-new incoming ERC20→MINIMA lock whose
        // hashlock we don't know yet (the only case getContract can't cover). Needs an archive RPC; silently
        // skipped if the endpoint rejects eth_getLogs, so the sell-MINIMA path keeps working on free nodes.
        try {
            long ethBlock = rpc.blockNumber().longValue();
            long cap = Math.max(0, ethBlock - ETH_SCAN_CAP);
            long recvFrom = scanFrom(ethBlock, 500, cap);
            for (EthHtlc.Contract c : eth.contractsAsReceiver(BigInteger.valueOf(recvFrom), BigInteger.valueOf(ethBlock))) {
                if (db.getSwap(c.hashlock) != null || db.haveCollect(c.hashlock)) continue;   // known swaps handled above
                try { checkCanCollectEth(eth, c, minimaBlock); } catch (Exception ignore) {}
            }
            lastEthScanned = ethBlock;
        } catch (Exception ignore) { /* free RPC without eth_getLogs — getContract path above still works */ }
    }

    /** Read one swap's ETH leg by deterministic contractId and drive it: claim (receiver), harvest the
     *  revealed secret / refund (sender). All via getContract/withdraw/refund — no eth_getLogs. */
    private void checkEthContractFor(EthHtlc eth, SwapDb.Swap s, String myEth) throws Exception {
        final String hash = s.hash;
        final String contractId = EthHtlc.contractId(hash);
        EthHtlc.Contract gc = eth.getContract(contractId);
        if (gc == null) return;   // the ETH leg isn't locked yet

        boolean iAmReceiver = gc.receiver != null && gc.receiver.equalsIgnoreCase(myEth);
        boolean iAmSender = gc.owner != null && gc.owner.equalsIgnoreCase(myEth);

        if (iAmReceiver) {
            if (gc.withdrawn || gc.refunded) return;
            String secret = db.getSecret(hash);
            if (secret == null) return;   // (responder before harvesting the secret — nothing to do yet)
            String[] req = db.getRequest(hash);
            if (req != null) {
                String tokenHuman = EthWallet.format(gc.amount, decimalsOf(gc.tokenContract), 18);
                if (!amountTokenOk(req, tokenHuman, gc.tokenContract, true)) {
                    db.logEvent(hash, SwapDb.EV_COLLECT, "ETH:" + gc.tokenContract, "0", "counterparty amount/token mismatch");
                    return;
                }
            }
            if (db.haveCollect(hash) || !inflight.add("wdEth:" + hash)) return;
            db.setSwapStatus(hash, SwapDb.ST_CLAIMING);
            ui.post(notifier::onSwapsChanged);
            try {
                String tx = eth.withdraw(contractId, secret);
                db.logEvent(hash, SwapDb.EV_COLLECT, "ETH:" + gc.tokenContract, "", tx);
                db.setSwapStatus(hash, SwapDb.ST_COMPLETE);
                ui.post(() -> { notifier.notify("Swap complete", "Withdrew your " + s.buyToken); notifier.onSwapsChanged(); });
            } finally { inflight.remove("wdEth:" + hash); }
        } else if (iAmSender) {
            if (gc.withdrawn) {
                // The counterparty revealed the preimage IN the contract — read it directly (no eth_getLogs).
                if (gc.preimage != null && EthRpc.hexToBig(gc.preimage).signum() != 0 && db.insertSecret(hash, gc.preimage)) {
                    ui.post(() -> { notifier.notify("Secret revealed", "Claiming your side of the swap"); notifier.onSwapsChanged(); });
                }
            } else if (!gc.refunded && nowUnix() > gc.timelock) {
                if (db.haveCollectExpired(hash) || !inflight.add("refundE:" + hash)) return;
                try {
                    String tx = eth.refund(contractId);
                    db.logEvent(hash, SwapDb.EV_EXPIRED, "ETH:" + gc.tokenContract, "", tx);
                    db.setSwapStatus(hash, SwapDb.ST_REFUNDED);
                    ui.post(() -> { notifier.notify("Swap refunded", "Reclaimed your tokens"); notifier.onSwapsChanged(); });
                } finally { inflight.remove("refundE:" + hash); }
            }
        }
    }

    private void checkEthNewSecrets(EthHtlc eth, long ethBlock) {
        try {
            if (lastSecretBlock == ethBlock) return;
            long start = (lastSecretBlock == -1) ? Math.max(0, ethBlock - SECRETS_BACKLOG) : lastSecretBlock + 1;
            long end = ethBlock;
            if (start > end) start = end;
            List<EthHtlc.Reveal> reveals = eth.getReveals(BigInteger.valueOf(start), BigInteger.valueOf(end));
            lastSecretBlock = end;
            for (EthHtlc.Reveal r : reveals) {
                if (db.getSwap(r.hashlock) == null) continue;             // only secrets for swaps I'm in
                if (db.insertSecret(r.hashlock, r.secret)) {
                    ui.post(() -> { notifier.notify("Secret revealed", "Claiming your side of the swap");
                        notifier.onSwapsChanged(); });
                }
            }
        } catch (Exception e) {
            /* leave the bookmark untouched — just retry the same range next cycle */
        }
    }

    /** Port of _checkCanCollectETHCoin: I am the receiver of an ETH HTLC contract. */
    private void checkCanCollectEth(EthHtlc eth, EthHtlc.Contract c, int minimaBlock) throws Exception {
        String hash = c.hashlock;
        String secret = db.getSecret(hash);

        if (secret != null) {
            String[] req = db.getRequest(hash);
            if (req != null) {
                String tokenHuman = EthWallet.format(c.amount, decimalsOf(c.tokenContract), 18);
                if (!amountTokenOk(req, tokenHuman, c.tokenContract, true)) {
                    db.logEvent(hash, SwapDb.EV_COLLECT, "ETH:" + c.tokenContract, "0", "counterparty amount/token mismatch");
                    return;
                }
            }
            if (db.haveCollect(hash) || !eth.canCollect(c.contractId) || !inflight.add("wdEth:" + hash)) return;
            db.setSwapStatus(hash, SwapDb.ST_CLAIMING);   // counterparty leg found — claiming now
            ui.post(notifier::onSwapsChanged);
            try {
                String txhash = eth.withdraw(c.contractId, secret);
                db.logEvent(hash, SwapDb.EV_COLLECT, "ETH:" + c.tokenContract, "", txhash);
                db.setSwapStatus(hash, SwapDb.ST_COMPLETE);
                ui.post(() -> { notifier.notify("Swap complete", "Withdrew your tokens"); notifier.onSwapsChanged(); });
            } finally { inflight.remove("wdEth:" + hash); }
            return;
        }

        // I don't know the secret → I'm an ERC20→MINIMA responder; lock the MINIMA counter-leg.
        if (c.otc) return;
        if (db.haveSentCounterParty(hash)) return;
        if (c.timelock - nowUnix() < CP_SECS_CHECK) { declineNote(hash, "their USDT lock is too close to its timeout"); return; }
        if (myOrder == null) return;                                      // order not loaded yet — retry next cycle
        if (!acceptTakerBuyMinima(c)) { declineNote(hash, "it didn't match your published price/limits"); return; }
        if (!inflight.add("cpMin:" + hash)) return;
        lockMinimaCounterLeg(c, minimaBlock);
    }

    /** Tell the maker (once) why a handshake-announced buy was declined, so a reject isn't silent. */
    private void declineNote(String hash, String reason) {
        if (!incoming.contains(MinimaHtlc.normKey(hash)) || !declined.add(MinimaHtlc.normKey(hash))) return;
        ui.post(() -> notifier.notify("Buy request declined", reason));
    }

    private void checkExpiredEth(EthHtlc eth, EthHtlc.Contract c) {
        try {
            if (nowUnix() <= c.timelock) return;
            if (db.haveCollectExpired(c.hashlock) || !eth.canCollect(c.contractId)) return;
            if (!inflight.add("refundE:" + c.hashlock)) return;
            try {
                String txhash = eth.refund(c.contractId);
                db.logEvent(c.hashlock, SwapDb.EV_EXPIRED, "ETH:" + c.tokenContract, "", txhash);
                db.setSwapStatus(c.hashlock, SwapDb.ST_REFUNDED);
                ui.post(() -> { notifier.notify("Swap refunded", "Timelock passed — reclaimed your tokens");
                    notifier.onSwapsChanged(); });
            } finally { inflight.remove("refundE:" + c.hashlock); }
        } catch (Exception ignore) {}
    }

    /** Lock the MINIMA counter-leg for an ERC20→MINIMA swap I'm responding to (block+36). */
    private void lockMinimaCounterLeg(EthHtlc.Contract c, int minimaBlock) {
        final String hash = c.hashlock;
        final int timelock = minimaBlock + CP_BLOCKS;
        final String reqMinimaHuman = EthWallet.format(c.requestAmount, 18, 18);   // MINIMA they want from me
        final String receiverPubkey = c.minimaPublicKey;                           // initiator's Minima pubkey
        ui.post(() -> minima.lock(reqMinimaHuman, reqMinimaHuman, "minima", receiverPubkey,
                myEth(), hash, timelock, "FALSE", new MinimaHtlc.PostCb() {
            @Override public void ok(String txpowid) {
                db.logEvent(hash, SwapDb.EV_CPSENT, "minima", reqMinimaHuman, txpowid);
                EthNet.Token tk = net.tokenByAddress(c.tokenContract);
                String sym = tk == null ? "token" : tk.symbol;
                SwapDb.Swap s = baseSwap(hash, "RESPONDER", "ERC20_TO_MINIMA",
                        "MINIMA", reqMinimaHuman, sym, EthWallet.format(c.amount, decimalsOf(c.tokenContract), 18),
                        receiverPubkey);
                s.myTimelock = timelock; s.myLegIsMinima = true; s.contractId = c.contractId;
                s.status = SwapDb.ST_LOCKED;
                db.upsertSwap(s);
                notifier.notify("Locked your MINIMA", "Waiting for the counterparty to reveal the secret");
                notifier.onSwapsChanged();
                inflight.remove("cpMin:" + hash);
            }
            @Override public void err(String m) { inflight.remove("cpMin:" + hash); }
        }));
    }

    // ============================================================ order-match guards (fund safety)

    /**
     * Responder guard when the taker is SELLING MINIMA to me (they locked MINIMA wanting USDT). I am BUYING
     * MINIMA, so I pay my SELL price (the lower / bid, in USDT per MINIMA). I only auto-lock if the pair is
     * enabled, the MINIMA I'd receive meets my minimum, and the USDT I'd pay is ≤ (MINIMA received × sell).
     */
    private boolean acceptTakerSellMinima(JSONObject coin, String reqTokenAddr) {
        Order.Pair p = pairFor(reqTokenAddr);
        if (p == null || !p.enable) return false;
        BigDecimal recvMinima = dec(coin.optString("amount", "0"));   // MINIMA the taker locked, I receive
        BigDecimal giveUsdt = dec(MinimaHtlc.stateAt(coin, 1));       // USDT they requested, I'd pay
        if (recvMinima.compareTo(BigDecimal.valueOf(p.min)) < 0) return false;
        return giveUsdt.compareTo(recvMinima.multiply(BigDecimal.valueOf(p.sell))) <= 0;
    }

    /**
     * Responder guard when the taker is BUYING MINIMA from me (they locked USDT wanting MINIMA). I am SELLING
     * MINIMA, so I get my BUY price (the higher / ask, in USDT per MINIMA). I only auto-lock if the pair is
     * enabled, the MINIMA I'd give meets my minimum, and the USDT I'd receive is ≥ (MINIMA given × buy).
     */
    private boolean acceptTakerBuyMinima(EthHtlc.Contract c) {
        Order.Pair p = pairFor(c.tokenContract);
        if (p == null || !p.enable) return false;
        BigDecimal giveMinima = dec(EthWallet.format(c.requestAmount, 18, 18));            // MINIMA I'd give
        BigDecimal recvUsdt = dec(EthWallet.format(c.amount, decimalsOf(c.tokenContract), 18)); // USDT I'd receive
        if (giveMinima.compareTo(BigDecimal.valueOf(p.min)) < 0) return false;
        return recvUsdt.compareTo(giveMinima.multiply(BigDecimal.valueOf(p.buy))) >= 0;
    }

    private Order.Pair pairFor(String tokenAddr) {
        Order o = myOrder;
        EthNet.Token tk = net.tokenByAddress(tokenAddr);
        if (o == null || tk == null) return null;
        return o.pairs.get(tk.symbol);
    }

    /** Initiator's check that the counterparty locked at least what I asked, in the right token. */
    private boolean amountTokenOk(String[] req, String counterpartyAmountHuman, String tokenAddr, boolean ethLeg) {
        try {
            BigDecimal want = dec(req[0]);
            BigDecimal got = dec(counterpartyAmountHuman);
            if (want.compareTo(got) > 0) return false;                    // they locked less than I asked
            String reqToken = req[1] == null ? "" : req[1];
            if (reqToken.startsWith("ETH:")) reqToken = reqToken.substring(4);
            if (ethLeg) return reqToken.equalsIgnoreCase(tokenAddr);
            return reqToken.equalsIgnoreCase("minima") || reqToken.equalsIgnoreCase(tokenAddr);
        } catch (Exception e) { return false; }
    }

    // ============================================================ secret harvesting (Minima notify)

    /** Hashlocks of legs I locked as an ERC20→MINIMA responder that are still waiting for the revealed secret. */
    private java.util.List<String> pendingSecretHashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SwapDb.Swap s : db.allSwaps()) {
            if ("RESPONDER".equals(s.role) && "ERC20_TO_MINIMA".equals(s.direction)
                    && SwapDb.ST_LOCKED.equals(s.status) && db.getSecret(s.hash) == null) out.add(s.hash);
        }
        return out;
    }

    private void harvestNotifySecrets(JSONArray coins) {
        boolean changed = false;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject coin = coins.optJSONObject(i);
            if (coin == null) continue;
            String hash = MinimaHtlc.stateAt(coin, 101);
            String secret = MinimaHtlc.stateAt(coin, 100);
            if (hash.isEmpty() || secret.isEmpty()) continue;
            if (db.getSwap(hash) == null) continue;                       // only my swaps
            if (db.insertSecret(hash, secret)) changed = true;
        }
        if (changed) { notifier.notify("Secret revealed", "Claiming your side of the swap"); notifier.onSwapsChanged(); }
    }

    // ============================================================ ETH allowance helpers

    /** Blocking (user-initiated start): approve MAX if needed and wait until the allowance lands. */
    private void ensureAllowanceBlocking(EthHtlc eth, String token, BigInteger needed) throws Exception {
        if (eth.allowance(token).compareTo(needed) >= 0) return;
        eth.approve(token, MAX_UINT);
        for (int i = 0; i < 40; i++) {                                    // up to ~2 min
            Thread.sleep(3000);
            if (eth.allowance(token).compareTo(needed) >= 0) return;
        }
        throw new Exception("Token approval not confirmed in time — try again");
    }

    /** Non-blocking (watcher auto-lock): if allowance is short, fire an approve and defer to a later cycle.
     *  Re-fires after a TTL so a dropped/failed approve doesn't strand the swap forever. */
    private boolean approveIfReady(EthHtlc eth, String token, BigInteger needed) throws Exception {
        if (eth.allowance(token).compareTo(needed) >= 0) { approvePending.remove(token); return true; }
        Long sent = approvePending.get(token);
        long now = System.currentTimeMillis();
        if (sent == null || now - sent > APPROVE_TTL_MS) { approvePending.put(token, now); eth.approve(token, MAX_UINT); }
        return false;
    }

    // ============================================================ small helpers

    /** A coin addressed to my published swap identity (the key I post in orders + lock under). */
    private boolean isMyPublishKey(String pk) {
        return myMinimaPk != null && MinimaHtlc.normKey(pk).equals(MinimaHtlc.normKey(myMinimaPk));
    }
    /** A coin I own / can refund — owner is any of my 64 default keys (or my publish key before they load). */
    private boolean isMyOwnedKey(String pk) {
        String n = MinimaHtlc.normKey(pk);
        return (!n.isEmpty() && myPubkeys.contains(n)) || isMyPublishKey(pk);
    }

    /** Discovery look-back start: a {@code window} back normally, extended to lastEthScanned+1 after
     *  downtime, never older than {@code cap}, never below 0. */
    private long scanFrom(long ethBlock, long window, long cap) {
        long base = ethBlock - window;
        if (lastEthScanned >= 0) base = Math.min(base, lastEthScanned + 1);
        return Math.max(0, Math.max(cap, base));
    }

    private SwapDb.Swap baseSwap(String hash, String role, String dir,
                                 String sellTok, String sellAmt, String buyTok, String buyAmt, String cp) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = hash; s.role = role; s.direction = dir;
        s.sellToken = sellTok; s.sellAmount = sellAmt; s.buyToken = buyTok; s.buyAmount = buyAmt;
        s.counterparty = cp;
        return s;
    }

    private int decimalsOf(String tokenAddr) {
        EthNet.Token t = net.tokenByAddress(tokenAddr);
        return t == null ? 18 : t.decimals;
    }

    /** Strip the "[ETH:…]" / "[…]" wrapper off a Minima requesttoken state value. */
    private static String stripReqToken(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        if (s.startsWith("ETH:")) s = s.substring(4);
        return s;
    }

    private static BigInteger parseUnits(String human, int decimals) {
        return new BigDecimal(human).movePointRight(decimals).toBigInteger();
    }
    private static BigDecimal dec(String s) {
        try { return (s == null || s.isEmpty()) ? BigDecimal.ZERO : new BigDecimal(s); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }
    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private static long nowUnix() { return System.currentTimeMillis() / 1000L; }
}
