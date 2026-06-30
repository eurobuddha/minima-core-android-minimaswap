package org.minimarex.minimaswap.swap;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A standing swap offer broadcast to the order book. Carries the maker's identities (Minima HTLC key,
 * ETH receiving address, comms publicId for the sealed handshake) and their rates per token pair.
 * The maker signs the canonical JSON with their Ed25519 comms key; takers verify before trusting it.
 */
public final class Order {

    public String minimaPublicKey;   // maker's Minima public key (the HTLC counterparty field)
    public String ethAddress;        // maker's ETH receiving address
    public String commsPublicId;     // 0x + boxPk + signPk — used to seal the handshake to the maker
    public long ts;                  // maker's timestamp (ms) — freshest order per signer wins
    public double minimaAvail;       // maker's available MINIMA at publish time (liquidity, ask side)
    public double usdtAvail;         // maker's available USDT at publish time (liquidity, bid side)
    public final Map<String, Pair> pairs = new LinkedHashMap<>();   // "USDT" -> rate

    // set on receive (not part of the signed payload)
    public String signerPk;          // 0x + Ed25519 pk recovered from the coin (verified signer)
    public String coinid;            // source coin id (dedup / expiry)

    public static final class Pair {
        public boolean enable;
        public double buy;     // MINIMA the maker pays per 1 token (maker buys token? see UI)
        public double sell;
        public double min;     // minimum MINIMA per trade
        public Pair() {}
        public Pair(boolean enable, double buy, double sell, double min) {
            this.enable = enable; this.buy = buy; this.sell = sell; this.min = min;
        }
    }

    /** Canonical JSON that gets signed (the order without receive-side fields). */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("mpk", minimaPublicKey);
        o.put("eth", ethAddress);
        o.put("cid", commsPublicId);
        o.put("ts", ts);
        o.put("bal", new JSONObject().put("m", minimaAvail).put("u", usdtAvail));
        JSONObject p = new JSONObject();
        for (Map.Entry<String, Pair> e : pairs.entrySet()) {
            Pair v = e.getValue();
            p.put(e.getKey(), new JSONObject()
                    .put("en", v.enable).put("buy", v.buy).put("sell", v.sell).put("min", v.min));
        }
        o.put("pairs", p);
        return o;
    }

    public static Order fromJson(JSONObject o) {
        Order r = new Order();
        r.minimaPublicKey = o.optString("mpk", "");
        r.ethAddress = o.optString("eth", "");
        r.commsPublicId = o.optString("cid", "");
        r.ts = o.optLong("ts", 0);
        JSONObject bal = o.optJSONObject("bal");
        if (bal != null) { r.minimaAvail = bal.optDouble("m", 0); r.usdtAvail = bal.optDouble("u", 0); }
        JSONObject p = o.optJSONObject("pairs");
        if (p != null) {
            for (Iterator<String> it = p.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject v = p.optJSONObject(k);
                if (v != null) r.pairs.put(k, new Pair(
                        v.optBoolean("en"), v.optDouble("buy"), v.optDouble("sell"), v.optDouble("min")));
            }
        }
        return r;
    }
}
