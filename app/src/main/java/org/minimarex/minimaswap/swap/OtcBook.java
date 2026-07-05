package org.minimarex.minimaswap.swap;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.comms.CommsIdentity;
import org.minimarex.comms.CommsTransport;
import org.minimarex.comms.Hex;
import org.minimarex.comms.NodeApi;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The OTC availability board — the {@link SwapOrderBook} pattern applied to OTC LP offers. Each LP
 * broadcasts availability (side + size + enable) as signed coin-state at the shared {@link #ADDRESS}: the
 * offer JSON in state[99], the LP's Ed25519 public key in state[1], a signature over the JSON in state[2].
 * Peers scan, verify the signature with libsodium, and keep the freshest offer per signer. A disabled
 * (empty) offer is a tombstone that withdraws the LP from the board.
 */
public final class OtcBook {

    /** "MINIMASWAPOTCB" in hex — the OTC availability board (distinct from order book / take / OTC chat). */
    public static final String ADDRESS = "0x4D494E494D41535741504F544342";

    /** ~1 hour of Minima blocks — offers older than this are treated as stale. */
    public static final int SCAN_DEPTH = (60 * 60) / MinimaHtlc.MINIMA_BLOCK_TIME;

    private OtcBook() {}

    /** Sign {@code offer} with my comms key and broadcast it to the board. */
    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, OtcOffer offer, CommsTransport.SendCb cb) {
        try {
            offer.ts = System.currentTimeMillis();
            offer.commsPublicId = id.publicId();
            byte[] msg = offer.toJson().toString().getBytes(StandardCharsets.UTF_8);
            byte[] sig = new byte[Sign.BYTES];
            if (!ls.cryptoSignDetached(sig, msg, msg.length, id.signSk)) { cb.onFailed("sign failed"); return; }
            JSONObject extra = new JSONObject();
            extra.put("1", "0x" + Hex.to(id.signPk));
            extra.put("2", "0x" + Hex.to(sig));
            CommsTransport.postBlob(node, ADDRESS, CommsTransport.MESSAGE_AMOUNT, CommsTransport.MINIMA,
                    Hex.to(msg), extra, cb);
        } catch (Exception e) {
            cb.onFailed("otc publish: " + e.getMessage());
        }
    }

    /** Bounded one-shot scan of the board; verifies signatures and returns freshest-per-signer. */
    public static void scan(NodeApi node, LazySodium ls, Consumer<Map<String, OtcOffer>> ok, Consumer<String> err) {
        node.cmd("coinnotify action:add address:" + ADDRESS, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { doScan(node, ls, ok, err); }
            @Override public void onError(String m) { doScan(node, ls, ok, err); }   // proceed regardless
        });
    }

    private static void doScan(NodeApi node, LazySodium ls, Consumer<Map<String, OtcOffer>> ok, Consumer<String> err) {
        node.cmd("coins simplestate:true order:desc depth:" + SCAN_DEPTH + " address:" + ADDRESS, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                Map<String, OtcOffer> book = new LinkedHashMap<>();
                for (int i = 0; i < coins.length(); i++) {
                    OtcOffer o = verifyCoin(ls, coins.optJSONObject(i));
                    if (o == null) continue;
                    OtcOffer prev = book.get(o.signerPk);
                    if (prev == null || o.ts > prev.ts) book.put(o.signerPk, o);
                }
                ok.accept(book);
            }
            @Override public void onError(String m) { err.accept(m); }
        });
    }

    /** Parse + verify a single offer coin. Returns null if missing fields or the signature is invalid. */
    public static OtcOffer verifyCoin(LazySodium ls, JSONObject coin) {
        if (coin == null) return null;
        try {
            String offerHex = stateVal(coin, 99), pkHex = stateVal(coin, 1), sigHex = stateVal(coin, 2);
            if (offerHex == null || pkHex == null || sigHex == null) return null;
            byte[] msg = Hex.from(offerHex), pk = Hex.from(pkHex), sig = Hex.from(sigHex);
            if (sig.length != Sign.BYTES || pk.length != Sign.PUBLICKEYBYTES) return null;
            if (!ls.cryptoSignVerifyDetached(sig, msg, msg.length, pk)) return null;
            OtcOffer o = OtcOffer.fromJson(new JSONObject(new String(msg, StandardCharsets.UTF_8)));
            o.signerPk = "0x" + Hex.to(pk);
            o.coinid = coin.optString("coinid", "");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read a coin state value by port, handling both [{port,data}] arrays and simplestate maps. */
    private static String stateVal(JSONObject coin, int port) {
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) { String v = sm.optString(String.valueOf(port), null); return v == null || v.isEmpty() ? null : v; }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject e = sa.optJSONObject(i);
            if (e != null && e.optInt("port", -1) == port) { String d = e.optString("data", ""); return d.isEmpty() ? null : d; }
        }
        return null;
    }
}
