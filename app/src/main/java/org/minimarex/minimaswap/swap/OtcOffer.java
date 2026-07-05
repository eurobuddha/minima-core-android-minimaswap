package org.minimarex.minimaswap.swap;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * An OTC liquidity provider's standing availability — the OTC analog of {@link Order}, but with NO price
 * ladder (price is negotiated per deal). Advertised as signed coin-state via {@link OtcBook}
 * (freshest-per-signer); withdrawn by publishing an empty (disabled) offer as a tombstone.
 */
public final class OtcOffer {

    public static final String LP_SELLS_MINIMA = "SELL";   // LP sells MINIMA (the instigator buys)
    public static final String LP_BUYS_MINIMA  = "BUY";    // LP buys MINIMA (the instigator sells)

    // sender-authored (signed) fields
    public String side = LP_SELLS_MINIMA;
    public double size = 0;               // max MINIMA the LP will deal
    public boolean enable = false;
    public String minimaPublicKey = "";   // LP's Minima HTLC pubkey
    public String ethAddress = "";        // LP's ETH receiving address
    public String commsPublicId = "";     // 0x + boxPk + signPk — seals negotiation messages to the LP
    public long ts = 0;                   // freshest-per-signer wins

    // set on receive (NOT signed)
    public String signerPk = "";
    public String coinid = "";

    /** False ⇒ this is a tombstone; publishing it withdraws the LP from the board. */
    public boolean hasLiquidity() { return enable && size > 0; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("side", side); o.put("size", size); o.put("en", enable);
        o.put("mpk", minimaPublicKey); o.put("eth", ethAddress); o.put("cid", commsPublicId);
        o.put("ts", ts);
        return o;
    }

    public static OtcOffer fromJson(JSONObject o) {
        OtcOffer f = new OtcOffer();
        f.side = o.optString("side", LP_SELLS_MINIMA);
        f.size = o.optDouble("size", 0);
        f.enable = o.optBoolean("en", false);
        f.minimaPublicKey = o.optString("mpk", "");
        f.ethAddress = o.optString("eth", "");
        f.commsPublicId = o.optString("cid", "");
        f.ts = o.optLong("ts", 0);
        return f;
    }
}
