package org.minimarex.comms;

/** Minimal, dependency-free hex codec. Accepts an optional 0x/0X prefix on decode. */
public final class Hex {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String to(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] from(String h) {
        if (h == null) return new byte[0];
        if (h.length() >= 2 && (h.charAt(0) == '0') && (h.charAt(1) == 'x' || h.charAt(1) == 'X')) h = h.substring(2);
        int n = h.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private Hex() {}
}
