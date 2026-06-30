package org.minimarex.comms;

import org.json.JSONObject;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Passphrase-encrypts the identity backup (which contains the private key) — PBKDF2-HMAC-SHA256 +
 * AES-256-GCM, all via the platform JCE (no third-party crypto). The exported file is useless without
 * the passphrase.
 */
public final class BackupCrypto {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 120000;

    public static String encrypt(String passphrase, byte[] plaintext) throws Exception {
        byte[] salt = new byte[16]; RNG.nextBytes(salt);
        byte[] iv = new byte[12]; RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plaintext);
        JSONObject o = new JSONObject();
        o.put("v", 1); o.put("salt", Hex.to(salt)); o.put("iv", Hex.to(iv)); o.put("ct", Hex.to(ct));
        return o.toString();
    }

    public static byte[] decrypt(String passphrase, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        byte[] salt = Hex.from(o.getString("salt"));
        byte[] iv = Hex.from(o.getString("iv"));
        byte[] ct = Hex.from(o.getString("ct"));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }

    private static SecretKey deriveKey(String pass, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(pass.toCharArray(), salt, ITERATIONS, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    private BackupCrypto() {}
}
