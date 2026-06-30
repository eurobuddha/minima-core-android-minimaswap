package org.minimarex.comms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Small text/threading helpers shared by the comms module. */
public final class MailText {

    private static final SecureRandom RNG = new SecureRandom();

    /** 32 random bytes as hex — a per-message id used to de-dupe within a thread. */
    public static String randomId() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Hex.to(b);
    }

    /**
     * Stable thread key for a conversation between two identities about a subject. Order-independent in
     * the two parties (sorted), so both sides compute the same key. SHA-256 of the ordered concat — the
     * thread key is local to this (native-only) network, so it need not match web ChainMail's SHA3.
     */
    public static String threadKey(String pubA, String pubB, String subject) {
        String a = pubA == null ? "" : pubA;
        String b = pubB == null ? "" : pubB;
        String joined = (a.compareTo(b) <= 0) ? (a + b + subject) : (b + a + subject);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return Hex.to(h);
        } catch (Exception e) {
            throw new RuntimeException("threadKey failed", e);
        }
    }

    private MailText() {}
}
