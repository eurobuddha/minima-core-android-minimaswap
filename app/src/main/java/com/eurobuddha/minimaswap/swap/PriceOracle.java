package com.eurobuddha.minimaswap.swap;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MEXC MINIMA/USDT price oracle for the maker's auto-market-maker peg.
 *
 * A maker can PEG their depth ladder to the live MEXC market: at every publish the 6+6 ladder is
 * regenerated around the oracle mid (step % spacing, a fixed MINIMA size per level, optional skew),
 * and the 90s watcher force-republishes when the market moves at least the reprice threshold.
 * The published order is exactly what the responder match-guard enforces, so a pegged maker
 * auto-trades at oracle-tracked prices — which makes feed quality FUND-CRITICAL:
 *   - a price only prices an order while FRESH (≤ {@link #FRESH_MS} old) — a stale feed never publishes;
 *   - a >{@link #JUMP_FRACTION} move between reads is suspect until two consecutive reads agree
 *     (one glitched exchange response must not reprice the ladder);
 *   - after {@link #WITHDRAW_MS} without a good read the pegged order is withdrawn (tombstone) and
 *     the responder guard DISARMED, then auto-restored when the feed recovers.
 *
 * Fetches run on a private single thread; readers see the last good snapshot. MainActivity and
 * SwapService live in one process and share this static state plus the "minimaswap" prefs keys below.
 */
public final class PriceOracle {
    private PriceOracle() {}

    public static final String SOURCE = "MEXC";
    private static final String BOOK_URL = "https://api.mexc.com/api/v3/ticker/bookTicker?symbol=MINIMAUSDT";
    private static final String LAST_URL = "https://api.mexc.com/api/v3/ticker/price?symbol=MINIMAUSDT";

    public static final long FRESH_MS = 5 * 60_000;          // max age a price may still price an order
    public static final long WITHDRAW_MS = 10 * 60_000;      // feed down this long → withdraw the pegged order
    private static final long FETCH_GAP_MS = 30_000;         // min gap between fetch attempts
    private static final long MIN_REPRICE_GAP_MS = 3 * 60_000;   // chain-spam floor between peg republishes
    private static final double JUMP_FRACTION = 0.5;         // a >50% move needs two consecutive agreeing reads

    // peg config — shared "minimaswap" prefs so MainActivity + SwapService read one source of truth
    public static final String P_ENABLE = "peg_enable";      // boolean: ladder pegged to the oracle
    public static final String P_STEP = "peg_step_pct";      // string double: level spacing, % of mid (>0)
    public static final String P_SIZE = "peg_size";          // string double: MINIMA per level (>0)
    public static final String P_BIAS = "peg_bias_pct";      // string double: skew, ±% shift of the quoted mid
    public static final String P_REPRICE = "peg_reprice_pct";// string double: republish when moved ≥ this %
    public static final String P_LAST_MID = "peg_last_mid";  // string double: oracle mid at the last pegged publish
    public static final String P_WITHDRAWN = "peg_withdrawn";// boolean: order pulled because the feed went stale

    public static final int PEG_OFF = 0;      // not pegged (or unconfigured) — order left untouched
    public static final int PEG_APPLIED = 1;  // ladder regenerated from a fresh oracle price
    public static final int PEG_STALE = 2;    // pegged but NO fresh price — caller must not publish

    private static final Object LOCK = new Object();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "price-oracle"); t.setDaemon(true); return t;
    });
    private static double price = 0, bid = 0, ask = 0;   // last GOOD read (mid; bid/ask 0 when from last-trade)
    private static long goodAtMs = 0;                     // when the last good read landed
    private static long lastTryMs = 0, firstTryMs = 0;    // fetch pacing / staleness baseline before first success
    private static double suspect = 0;                    // big-jump candidate awaiting a confirming read
    private static boolean fetching = false;
    private static String lastError = null;

    // ---- cached snapshot ----

    public static double mid() { synchronized (LOCK) { return price; } }

    public static long ageMs() {
        synchronized (LOCK) { return goodAtMs > 0 ? System.currentTimeMillis() - goodAtMs : Long.MAX_VALUE; }
    }

    public static boolean fresh() { return mid() > 0 && ageMs() <= FRESH_MS; }

    /** No good read for longer than the safety window (counted from the first attempt if none ever landed). */
    public static boolean feedDownPastLimit() {
        synchronized (LOCK) {
            long ref = goodAtMs > 0 ? goodAtMs : firstTryMs;
            return ref > 0 && System.currentTimeMillis() - ref > WITHDRAW_MS;
        }
    }

    /** One status line for the editor / market tab. */
    public static String describe() {
        synchronized (LOCK) {
            if (price <= 0)
                return fetching ? SOURCE + ": fetching…"
                        : lastError != null ? SOURCE + ": unavailable (" + lastError + ")" : SOURCE + ": no price yet";
            long age = System.currentTimeMillis() - goodAtMs;
            String s = SOURCE + " mid " + fmt(price)
                    + (bid > 0 ? "  (bid " + fmt(bid) + " / ask " + fmt(ask) + ")" : "")
                    + "  ·  " + (age / 1000) + "s ago";
            return age > FRESH_MS ? s + "  — STALE" : s;
        }
    }

    private static String fmt(double v) {
        return new BigDecimal(v, new MathContext(6)).stripTrailingZeros().toPlainString();
    }

    // ---- fetching ----

    /** Rate-limited background refresh — safe to call from any thread / every tick. */
    public static void refreshAsync() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (fetching || now - lastTryMs < FETCH_GAP_MS) return;
            fetching = true; lastTryMs = now;
            if (firstTryMs == 0) firstTryMs = now;
        }
        EXEC.execute(() -> { try { fetchOnce(); } finally { synchronized (LOCK) { fetching = false; } } });
    }

    /** Blocking refresh for a user-initiated publish (call OFF the main thread). Rides an in-flight fetch. */
    public static boolean refreshSync() {
        boolean mine = false;
        synchronized (LOCK) {
            if (!fetching) {
                fetching = true; mine = true; lastTryMs = System.currentTimeMillis();
                if (firstTryMs == 0) firstTryMs = lastTryMs;
            }
        }
        if (mine) { try { fetchOnce(); } finally { synchronized (LOCK) { fetching = false; } } }
        else {
            for (int i = 0; i < 60; i++) {   // ≤12s: wait out the fetch already in flight
                synchronized (LOCK) { if (!fetching) break; }
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return fresh();
    }

    private static void fetchOnce() {
        try {
            double b = 0, a = 0, m;
            try {
                JSONObject book = httpGet(BOOK_URL);
                b = book.optDouble("bidPrice", 0);
                a = book.optDouble("askPrice", 0);
            } catch (IOException ignore) { /* fall through to last-trade */ }
            if (b > 0 && a > 0 && b <= a && (a - b) / a < 0.2) m = (a + b) / 2;   // sane book → mid
            else { m = httpGet(LAST_URL).optDouble("price", 0); b = a = 0; }       // else last trade
            if (!(m > 0) || Double.isInfinite(m)) throw new IOException("bad price");
            synchronized (LOCK) {
                if (price > 0 && Math.abs(m - price) / price > JUMP_FRACTION) {
                    // Suspect glitch: only accept once a SECOND consecutive read lands within 10% of it.
                    if (!(suspect > 0 && Math.abs(m - suspect) / suspect < 0.10)) {
                        suspect = m; lastError = "suspect jump " + fmt(price) + "→" + fmt(m);
                        return;
                    }
                }
                suspect = 0;
                price = m; bid = b; ask = a;
                goodAtMs = System.currentTimeMillis();
                lastError = null;
            }
        } catch (Exception e) {
            synchronized (LOCK) { lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
        }
    }

    private static JSONObject httpGet(String u) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10_000); c.setReadTimeout(10_000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); total += n; if (total > 16384) break; }
            }
            String body = bos.toString("UTF-8");
            if (code < 200 || code >= 300)
                throw new IOException("HTTP " + code + " " + body.substring(0, Math.min(80, body.length())));
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("bad JSON: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- the peg ----

    /**
     * Regenerate the order's ladder around the current oracle mid per the saved peg config, and persist
     * the result to "order_config" (so a restart arms the responder guard with what was last published)
     * plus the mid it was priced from (the reprice baseline). Clears {@link #P_WITHDRAWN} — a successful
     * apply IS the recovery. Returns {@link #PEG_OFF} (order untouched), {@link #PEG_APPLIED}, or
     * {@link #PEG_STALE} — on STALE the caller MUST NOT publish the order.
     */
    public static int applyPeg(Order o, SharedPreferences prefs) {
        if (!prefs.getBoolean(P_ENABLE, false)) return PEG_OFF;
        Order.Pair p = o.pairs.get(Order.PAIR_TOKENS[0]);
        if (p == null || !p.enable) return PEG_OFF;
        double step = prefD(prefs, P_STEP, 0), size = prefD(prefs, P_SIZE, 0);
        double bias = Math.max(-20, Math.min(20, prefD(prefs, P_BIAS, 0)));
        if (!(step > 0) || !(size > 0)) return PEG_OFF;   // unconfigured peg behaves like a manual ladder
        double m;
        synchronized (LOCK) { m = price; }
        if (!(m > 0) || ageMs() > FRESH_MS) return PEG_STALE;
        double quoted = m * (1 + bias / 100.0);
        p.bids.clear(); p.asks.clear();
        for (int i = 1; i <= Order.MAX_LEVELS; i++) {
            p.asks.add(new Order.Level(quoted * (1 + i * step / 100.0), size));
            p.bids.add(new Order.Level(quoted * (1 - i * step / 100.0), size));
        }
        p.buy = 0; p.sell = 0;   // re-derived from the fresh levels (editor-authoritative pattern)
        Order.sanitize(p);       // drops any bid a huge step pushed ≤ 0
        if (p.asks.isEmpty() && p.bids.isEmpty()) return PEG_STALE;   // belt: nothing valid → don't publish
        prefs.edit().putString("order_config", Order.toConfigJson(o))
                .putString(P_LAST_MID, Double.toString(m))
                .putBoolean(P_WITHDRAWN, false).apply();
        return PEG_APPLIED;
    }

    /**
     * Should the maker republish NOW instead of waiting out the 30-min freshness interval? True when the
     * fresh oracle mid has moved ≥ the reprice threshold from the last pegged publish (with a spam floor),
     * or when a withdrawn order can be restored because the feed recovered.
     */
    public static boolean shouldReprice(SharedPreferences prefs, long lastPublishMs) {
        if (!prefs.getBoolean(P_ENABLE, false)) return false;
        double m;
        synchronized (LOCK) { m = price; }
        if (!(m > 0) || ageMs() > FRESH_MS) return false;
        if (prefs.getBoolean(P_WITHDRAWN, false)) return true;   // feed recovered → restore the order now
        if (System.currentTimeMillis() - lastPublishMs < MIN_REPRICE_GAP_MS) return false;
        double last = prefD(prefs, P_LAST_MID, 0);
        if (!(last > 0)) return true;                            // pegged but never stamped → publish
        double thresh = Math.max(0.1, prefD(prefs, P_REPRICE, 1.0));
        return Math.abs(m - last) / last * 100.0 >= thresh;
    }

    /**
     * The order to ARM the responder match-guard with at startup / on save: while a pegged order is
     * withdrawn (stale feed) the guard gets an EMPTY order — it declines every take — instead of a saved
     * ladder whose prices the market may have left behind.
     */
    public static Order armSafe(Order o, SharedPreferences prefs) {
        if (prefs.getBoolean(P_ENABLE, false) && prefs.getBoolean(P_WITHDRAWN, false)) return new Order();
        return o;
    }

    private static double prefD(SharedPreferences p, String k, double def) {
        try {
            String s = p.getString(k, null);
            double d = s == null || s.isEmpty() ? def : Double.parseDouble(s.trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? def : d;
        } catch (Exception e) { return def; }
    }
}
