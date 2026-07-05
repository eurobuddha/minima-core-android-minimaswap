package org.minimarex.minimaswap.swap;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * An OTC liquidity provider's standing availability — the OTC analog of {@link Order}, but with NO price
 * ladder (price is negotiated per deal). A single offer quotes BOTH sides independently: {@code sellSize}
 * (max MINIMA the LP will sell) and {@code buySize} (max MINIMA the LP will buy), either 0 to disable that
 * side. Advertised as signed coin-state via {@link OtcBook} (freshest-per-signer); withdrawn by publishing
 * an empty (disabled) offer as a tombstone.
 */
public final class OtcOffer {

    public static final String LP_SELLS_MINIMA = "SELL";   // a DEAL where the LP sells MINIMA (the instigator buys)
    public static final String LP_BUYS_MINIMA  = "BUY";    // a DEAL where the LP buys MINIMA (the instigator sells)

    // sender-authored (signed) fields
    public double sellSize = 0;           // max MINIMA the LP will SELL (0 = not selling)
    public double buySize = 0;            // max MINIMA the LP will BUY  (0 = not buying)
    public boolean enable = false;
    public String minimaPublicKey = "";   // LP's Minima HTLC pubkey
    public String ethAddress = "";        // LP's ETH receiving address
    public String commsPublicId = "";     // 0x + boxPk + signPk — seals negotiation messages to the LP
    public long ts = 0;                   // freshest-per-signer wins

    // set on receive (NOT signed)
    public String signerPk = "";
    public String coinid = "";

    /** False ⇒ tombstone; publishing it withdraws the LP from the board. */
    public boolean hasLiquidity() { return enable && (sellSize > 0 || buySize > 0); }
    public boolean sells() { return enable && sellSize > 0; }
    public boolean buys()  { return enable && buySize > 0; }
    /** The LP's cap (MINIMA) for a deal on {@code dealSide} (LP_SELLS_MINIMA / LP_BUYS_MINIMA). */
    public double capFor(String dealSide) { return LP_SELLS_MINIMA.equals(dealSide) ? sellSize : buySize; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sellsz", sellSize); o.put("buysz", buySize); o.put("en", enable);
        o.put("mpk", minimaPublicKey); o.put("eth", ethAddress); o.put("cid", commsPublicId);
        o.put("ts", ts);
        return o;
    }

    public static OtcOffer fromJson(JSONObject o) {
        OtcOffer f = new OtcOffer();
        f.enable = o.optBoolean("en", false);
        f.minimaPublicKey = o.optString("mpk", "");
        f.ethAddress = o.optString("eth", "");
        f.commsPublicId = o.optString("cid", "");
        f.ts = o.optLong("ts", 0);
        if (o.has("sellsz") || o.has("buysz")) {
            f.sellSize = o.optDouble("sellsz", 0);
            f.buySize = o.optDouble("buysz", 0);
        } else {   // legacy single-sided offer (pre two-sided) — map side+size onto the matching side
            double size = o.optDouble("size", 0);
            if (LP_BUYS_MINIMA.equals(o.optString("side", LP_SELLS_MINIMA))) f.buySize = size; else f.sellSize = size;
        }
        return f;
    }
}
