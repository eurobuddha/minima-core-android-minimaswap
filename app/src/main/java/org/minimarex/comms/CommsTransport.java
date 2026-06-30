package org.minimarex.comms;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * miniMall transport (Maxima-free). Two coin shapes:
 *   1. MESSAGE — a sealed order/status/chat blob in state[99], posted as a 1-nano coin to the shared
 *      MINIMERCH address. Everyone monitors it; only the recipient's box key opens it (privacy by encryption).
 *   2. PAYMENT — a real value send to the vendor's receiving address, with the order ref stamped into
 *      state[1] so the inbox can match a payment to its order deterministically.
 * The node is pure transport — it never sees plaintext.
 */
public final class CommsTransport {

    /** "MINIMERCH" in hex — the one shared address every shop's messages go to + monitor. */
    public static final String MINIMERCH_ADDRESS = "0x4D494E494D45524348";

    /** 1 nano-Minima — effectively free, still a clean valid coin carrying a message. */
    public static final String MESSAGE_AMOUNT = "0.000000001";

    /** Supported pay tokens (same as miniMall). */
    public static final String MINIMA = "0x00";
    public static final String USDT   = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    public interface SendCb {
        void onSent(String txpowid);
        void onFailed(String message);
    }

    /** Seal a wire payload to a recipient's box key and post it (1 nano) to the shared MINIMERCH address. */
    public static void sendMessage(NodeApi node, CryptoProvider crypto, String toPublicId, byte[] wire, SendCb cb) {
        final String blob;
        try {
            blob = crypto.seal(toPublicId, wire);
        } catch (Exception e) {
            cb.onFailed("encrypt failed: " + e.getMessage());
            return;
        }
        postBlob(node, MINIMERCH_ADDRESS, MESSAGE_AMOUNT, MINIMA, blob, null, cb);
    }

    /** Send a real value PAYMENT to a vendor address; stamp the order ref into state[1] for matching. */
    public static void sendPayment(NodeApi node, String vendorAddress, String amount, String tokenid,
                                   String ref, SendCb cb) {
        try {
            JSONObject extra = new JSONObject();
            if (ref != null && !ref.isEmpty()) extra.put("1", "0x" + Hex.to(ref.getBytes(StandardCharsets.UTF_8)));
            String cmd = "send amount:" + amount + " address:" + vendorAddress + " tokenid:" + tokenid
                    + (extra.length() > 0 ? " state:" + extra : "");
            post(node, cmd, cb);
        } catch (Exception e) {
            cb.onFailed(e.getMessage());
        }
    }

    /** Post a (sealed) blob into state[99] at an address with an amount + tokenid (+ optional extra state). */
    public static void postBlob(NodeApi node, String address, String amount, String tokenid,
                                String blobHex, JSONObject extraState, SendCb cb) {
        try {
            JSONObject state = extraState != null ? extraState : new JSONObject();
            state.put("99", "0x" + blobHex);   // hex-typed state value, read back the same way
            post(node, "send amount:" + amount + " address:" + address + " tokenid:" + tokenid + " state:" + state, cb);
        } catch (Exception e) {
            cb.onFailed(e.getMessage());
        }
    }

    private static void post(NodeApi node, String cmd, SendCb cb) {
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                // status:true = accepted; pending:true = queued via the pending app (node locked)
                if (j.optBoolean("status", false) || j.optBoolean("pending", false)) {
                    JSONObject r = j.optJSONObject("response");
                    cb.onSent(r != null ? r.optString("txpowid", "") : "");
                } else {
                    cb.onFailed(j.optString("error", "the node rejected the send"));
                }
            }
            @Override public void onError(String message) { cb.onFailed(message); }
        });
    }

    private CommsTransport() {}
}
