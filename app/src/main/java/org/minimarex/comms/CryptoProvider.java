package org.minimarex.comms;

/**
 * The pluggable crypto layer. Everything above it (transport, scanner, DB, UI) is identical regardless
 * of which provider is used.
 *
 * - {@link LocalEcCryptoProvider} — ships now: X25519 sealed box + Ed25519, keys derived in-app from the
 *   Minima seed. Needs NO node changes (a separate, native-only network).
 * - A future {@code MaximaCryptoProvider} — drops in when Minima Core exposes {@code maxmessage} over the
 *   IPC, to interoperate with the existing web-ChainMail network. Only this interface's implementation
 *   changes; the blob in coin state[99] and the identity string differ, nothing else.
 */
public interface CryptoProvider {

    /** My published identity (what others seal to / verify against). */
    String identity();

    /** Seal+sign {@code plaintext} for {@code toPublicId}; returns the hex blob to put in coin state[99]. */
    String seal(String toPublicId, byte[] plaintext);

    /** Try to open a blob: returns null if it isn't for me (or is malformed); otherwise the message and
     *  whether the sender's signature verified. */
    Opened open(String blobHex);
}
