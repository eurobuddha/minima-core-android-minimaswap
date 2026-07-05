package org.minimarex.minimaswap.swap;

import org.json.JSONObject;
import org.minimarex.comms.CommsTransport;
import org.minimarex.comms.CryptoProvider;
import org.minimarex.comms.NodeApi;

import java.nio.charset.StandardCharsets;

/**
 * A single sealed OTC-negotiation message. The comms transport is stateless — each message is an
 * independent 1-nano coin at {@link #ADDRESS} — so a "conversation" is a set of these correlated by
 * {@code ref} (the deal thread id), deduped by {@code randomid}, and ordered by {@code date}. The
 * receiver drives the deal state machine (OtcDb) from the typed messages.
 *
 * Terms are carried on every offer message (PROPOSE/COUNTER/ACCEPT) so both sides always see the
 * standing offer without needing a prior message. Identities travel too, so the recipient can execute
 * against the sender directly. Modeled on {@code comms/MerchMessage} + {@code SwapTake}.
 */
public final class OtcMessage {

    /** "MINIMASWAPOTCC" in hex — the OTC negotiation channel (distinct from the order book / take / OtcBook). */
    public static final String ADDRESS = "0x4D494E494D41535741504F544343";

    // message types — the offer/counter/accept vocabulary
    public static final String PROPOSE = "PROPOSE";   // instigator → LP: open a deal with initial terms
    public static final String COUNTER = "COUNTER";   // either → other: new terms (whoseTurn flips)
    public static final String ACCEPT  = "ACCEPT";    // either → other: accept the other's standing terms → AGREED
    public static final String REJECT  = "REJECT";    // either → other: end the deal
    public static final String EXECUTE = "EXECUTE";   // instigator → LP: leg-1 locked, here's the hashlock

    public String type = PROPOSE;
    public String ref = "";           // deal thread id
    public String randomid = "";      // per-message unique id (dedup)
    public String from = "";          // sender commsPublicId
    public String to = "";            // recipient commsPublicId
    public long   date = 0;

    // negotiated terms (from the LP's perspective for `side`; amount is MINIMA, price is USDT per MINIMA)
    public String side = "";          // OtcOffer.LP_SELLS_MINIMA / OtcOffer.LP_BUYS_MINIMA
    public String amount = "";        // MINIMA for THIS deal, decimal string
    public String price = "";         // USDT per MINIMA, decimal string

    // sender identities, so the recipient can execute/verify against the sender without a separate lookup
    public String minimaPk = "";      // sender's Minima HTLC pubkey
    public String ethAddr = "";       // sender's ETH address

    // EXECUTE only
    public String hash = "";          // on-chain hashlock of the initiator's locked leg

    public byte[] toWire() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type == null ? PROPOSE : type);
            o.put("ref", ref); o.put("randomid", randomid);
            o.put("from", from); o.put("to", to); o.put("date", date);
            o.put("side", side); o.put("amount", amount); o.put("price", price);
            o.put("minimaPk", minimaPk); o.put("ethAddr", ethAddr);
            o.put("hash", hash == null ? "" : hash);
            return o.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException("toWire failed", e); }
    }

    public static OtcMessage fromWire(byte[] wire) {
        try {
            JSONObject o = new JSONObject(new String(wire, StandardCharsets.UTF_8));
            OtcMessage m = new OtcMessage();
            m.type = o.optString("type", PROPOSE);
            m.ref = o.optString("ref", ""); m.randomid = o.optString("randomid", "");
            m.from = o.optString("from", ""); m.to = o.optString("to", ""); m.date = o.optLong("date", 0);
            m.side = o.optString("side", ""); m.amount = o.optString("amount", ""); m.price = o.optString("price", "");
            m.minimaPk = o.optString("minimaPk", ""); m.ethAddr = o.optString("ethAddr", "");
            m.hash = o.optString("hash", "");
            return m;
        } catch (Exception e) { return null; }
    }

    /** Seal this message to {@code to} and post it to the OTC channel (one 1-nano coin at {@link #ADDRESS}). */
    public void send(NodeApi node, CryptoProvider crypto, CommsTransport.SendCb cb) {
        try {
            String blob = crypto.seal(to, toWire());
            CommsTransport.postBlob(node, ADDRESS, CommsTransport.MESSAGE_AMOUNT, CommsTransport.MINIMA, blob, null, cb);
        } catch (Exception e) { cb.onFailed("otc send: " + e.getMessage()); }
    }
}
