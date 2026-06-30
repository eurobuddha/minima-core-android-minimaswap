package org.minimarex.minimaswap.swap;

import org.json.JSONObject;
import org.minimarex.comms.CommsTransport;
import org.minimarex.comms.CryptoProvider;
import org.minimarex.comms.NodeApi;

import java.nio.charset.StandardCharsets;

/**
 * The buy-MINIMA handshake channel. When a taker buys MINIMA it locks USDT first (generating the
 * hashlock), then seals {@code {to, from, hash}} to the maker's comms identity and posts it to {@link
 * #ADDRESS}. The maker scans this address, opens the sealed message, and uses the hashlock to find the
 * USDT lock by deterministic contractId = sha256(hash) via getContract — no eth_getLogs, so it works on
 * free RPCs. The hashlock is NOT secret (it's already on-chain in the HTLC); the preimage is never sent.
 */
public final class SwapTake {

    /** "MINIMASWAPTAKE" in hex — a sentinel address distinct from the order book + Mail channels. */
    public static final String ADDRESS = "0x4D494E494D415357415054414B45";

    private SwapTake() {}

    /** Taker → maker: seal the hashlock to the maker's publicId and post it to the take channel. */
    public static void send(NodeApi node, CryptoProvider crypto, String makerPublicId, String myPublicId,
                            String hash, CommsTransport.SendCb cb) {
        try {
            JSONObject j = new JSONObject().put("to", makerPublicId).put("from", myPublicId).put("hash", hash);
            String blob = crypto.seal(makerPublicId, j.toString().getBytes(StandardCharsets.UTF_8));
            CommsTransport.postBlob(node, ADDRESS, CommsTransport.MESSAGE_AMOUNT, CommsTransport.MINIMA, blob, null, cb);
        } catch (Exception e) {
            cb.onFailed("take send: " + e.getMessage());
        }
    }
}
