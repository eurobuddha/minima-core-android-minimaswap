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
 * The native order book — the Maxima-free replacement for the bridge's maxsign coin-state book.
 * Orders are broadcast as coin-state at the shared {@link #ADDRESS} sentinel: the order JSON in
 * state[99], the maker's Ed25519 public key in state[1], and an Ed25519 signature over the JSON in
 * state[2]. Any peer scans the address, verifies the signature with libsodium (not Maxima maxverify),
 * and builds a local book — freshest order per signer wins.
 */
public final class SwapOrderBook {

    /** "MINIMASWAP" in hex — the shared order-book address. */
    public static final String ADDRESS = "0x4D494E494D4153574150";

    /** ~1 hour of Minima blocks — orders older than this are treated as stale. */
    public static final int SCAN_DEPTH = (60 * 60) / MinimaHtlc.MINIMA_BLOCK_TIME;

    private SwapOrderBook() {}

    /** Re-read the live SENDABLE Minima balance, stamp it onto {@code base}, then publish — so the advertised
     *  liquidity is never stale (contract-locked coins, e.g. casino, are excluded by the node's `sendable`). */
    public static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, CommsTransport.SendCb cb) {
        node.cmd("balance tokenid:0x00", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONObject t = null;
                if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) t = ((JSONArray) resp).optJSONObject(0);
                else if (resp instanceof JSONObject) t = (JSONObject) resp;
                base.minimaAvail = parseD(t == null ? "0" : t.optString("sendable", "0"));
                publish(node, ls, id, base, cb);
            }
            @Override public void onError(String m) { publish(node, ls, id, base, cb); }   // publish with what we have
        });
    }

    private static double parseD(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }

    /** Sign {@code order} with my comms key and broadcast it to the book. */
    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, CommsTransport.SendCb cb) {
        try {
            order.ts = System.currentTimeMillis();
            order.commsPublicId = id.publicId();
            byte[] msg = order.toJson().toString().getBytes(StandardCharsets.UTF_8);
            byte[] sig = new byte[Sign.BYTES];
            if (!ls.cryptoSignDetached(sig, msg, msg.length, id.signSk)) { cb.onFailed("sign failed"); return; }
            JSONObject extra = new JSONObject();
            extra.put("1", "0x" + Hex.to(id.signPk));
            extra.put("2", "0x" + Hex.to(sig));
            CommsTransport.postBlob(node, ADDRESS, CommsTransport.MESSAGE_AMOUNT, CommsTransport.MINIMA,
                    Hex.to(msg), extra, cb);
        } catch (Exception e) {
            cb.onFailed("publish: " + e.getMessage());
        }
    }

    /**
     * Bounded one-shot scan of the order book; verifies signatures and returns freshest-per-signer.
     *
     * The book lives at a SHARED address nobody owns, so Minima returns 0 coins for {@code relevant:false}
     * there until the address is tracked. We therefore {@code coinnotify}-add it first (idempotent), then
     * query BARE (no {@code relevant} flag) — the same pattern the comms scanner uses for the Mail address.
     * The query stays bounded by {@code depth:} (a shared address can be heavy; an unbounded read can crash
     * the node).
     */
    public static void scan(NodeApi node, LazySodium ls, Consumer<Map<String, Order>> ok, Consumer<String> err) {
        node.cmd("coinnotify action:add address:" + ADDRESS, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { doScan(node, ls, ok, err); }
            @Override public void onError(String m) { doScan(node, ls, ok, err); }   // proceed regardless
        });
    }

    private static void doScan(NodeApi node, LazySodium ls, Consumer<Map<String, Order>> ok, Consumer<String> err) {
        node.cmd("coins simplestate:true order:desc depth:" + SCAN_DEPTH + " address:" + ADDRESS, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                Map<String, Order> book = new LinkedHashMap<>();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject coin = coins.optJSONObject(i);
                    Order o = verifyCoin(ls, coin);
                    if (o == null) continue;
                    Order prev = book.get(o.signerPk);
                    if (prev == null || o.ts > prev.ts) book.put(o.signerPk, o);
                }
                ok.accept(book);
            }
            @Override public void onError(String m) { err.accept(m); }
        });
    }

    /** Parse + verify a single order coin. Returns null if missing fields or the signature is invalid. */
    public static Order verifyCoin(LazySodium ls, JSONObject coin) {
        if (coin == null) return null;
        try {
            String orderHex = stateVal(coin, 99);
            String pkHex = stateVal(coin, 1);
            String sigHex = stateVal(coin, 2);
            if (orderHex == null || pkHex == null || sigHex == null) return null;
            byte[] msg = Hex.from(orderHex);
            byte[] pk = Hex.from(pkHex);
            byte[] sig = Hex.from(sigHex);
            if (sig.length != Sign.BYTES || pk.length != Sign.PUBLICKEYBYTES) return null;
            if (!ls.cryptoSignVerifyDetached(sig, msg, msg.length, pk)) return null;
            Order o = Order.fromJson(new JSONObject(new String(msg, StandardCharsets.UTF_8)));
            o.signerPk = "0x" + Hex.to(pk);
            o.coinid = coin.optString("coinid", "");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read a coin state value by port, handling both [{port,data}] arrays and simplestate maps. */
    private static String stateVal(JSONObject coin, int port) {
        // simplestate:true → "state" is a JSON object keyed by port string
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) {
            String v = sm.optString(String.valueOf(port), null);
            return v == null || v.isEmpty() ? null : v;
        }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject e = sa.optJSONObject(i);
                if (e != null && e.optInt("port", -1) == port) {
                    String d = e.optString("data", "");
                    return d.isEmpty() ? null : d;
                }
            }
        }
        return null;
    }
}
