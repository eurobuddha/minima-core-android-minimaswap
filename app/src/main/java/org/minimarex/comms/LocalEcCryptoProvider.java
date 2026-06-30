package org.minimarex.comms;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Client-side end-to-end crypto — the ship-now provider. No node crypto involved.
 *
 * Envelope (mirrors ChainMail's "decrypt-then-verify"):
 *   payload = { f: senderPublicId, b: bodyHex, s: Ed25519_sign(senderSignKey, senderPublicId || body) }
 *   blob    = crypto_box_seal(payload, recipientBoxKey)        // anonymous; only the recipient can open
 *
 * On open, the recipient is the only one whose box key opens the seal ("anyone tries, only the recipient
 * succeeds"), then the embedded Ed25519 signature authenticates the sender — the same privacy + auth
 * model as Maxima's maxmessage, but computed in-app instead of by the node.
 */
public final class LocalEcCryptoProvider implements CryptoProvider {

    private final LazySodium ls;
    private final CommsIdentity me;

    public LocalEcCryptoProvider(LazySodium ls, CommsIdentity me) {
        this.ls = ls;
        this.me = me;
    }

    @Override public String identity() { return me.publicId(); }

    @Override public String seal(String toPublicId, byte[] plaintext) {
        try {
            // Sign (myPublicId || plaintext) so the signature binds sender identity to content.
            byte[] from = Hex.from(me.publicId());
            byte[] signed = concat(from, plaintext);
            byte[] sig = new byte[Sign.BYTES];
            if (!ls.cryptoSignDetached(sig, signed, signed.length, me.signSk)) throw new RuntimeException("sign failed");

            JSONObject payload = new JSONObject();
            payload.put("f", me.publicId());
            payload.put("b", Hex.to(plaintext));
            payload.put("s", Hex.to(sig));
            byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            byte[] recipientBoxPk = CommsIdentity.boxPkOf(toPublicId);
            byte[] cipher = new byte[payloadBytes.length + Box.SEALBYTES];
            if (!ls.cryptoBoxSeal(cipher, payloadBytes, payloadBytes.length, recipientBoxPk)) throw new RuntimeException("seal failed");
            return Hex.to(cipher);
        } catch (Exception e) {
            throw new RuntimeException("seal error: " + e.getMessage(), e);
        }
    }

    @Override public Opened open(String blobHex) {
        try {
            byte[] cipher = Hex.from(blobHex);
            if (cipher.length <= Box.SEALBYTES) return null;            // too short to be a sealed box
            byte[] payloadBytes = new byte[cipher.length - Box.SEALBYTES];
            // Only succeeds if this seal was made for MY box key — i.e. the message is for me.
            if (!ls.cryptoBoxSealOpen(payloadBytes, cipher, cipher.length, me.boxPk, me.boxSk)) return null;

            JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            String from = payload.optString("f", "");
            byte[] body = Hex.from(payload.optString("b", ""));
            byte[] sig  = Hex.from(payload.optString("s", ""));
            if (!CommsIdentity.isValidPublicId(from) || sig.length != Sign.BYTES) return new Opened(false, from, body);

            byte[] signed = concat(Hex.from(from), body);
            byte[] signPk = CommsIdentity.signPkOf(from);
            boolean valid = ls.cryptoSignVerifyDetached(sig, signed, signed.length, signPk);
            return new Opened(valid, from, body);
        } catch (Exception e) {
            return null;   // not for us / malformed — skip silently
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
