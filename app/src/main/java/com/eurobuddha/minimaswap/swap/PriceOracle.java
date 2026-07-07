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
    private static final String DEPTH_URL = "https://api.mexc.com/api/v3/depth?symbol=MINIMAUSDT&limit=20";
    private static final String BOOK_URL = "https://api.mexc.com/api/v3/ticker/bookTicker?symbol=MINIMAUSDT";

    public static final long FRESH_MS = 5 * 60_000;          // ≤ this old → peg TIGHT at the configured step
    public static final long WITHDRAW_MS = 10 * 60_000;      // feed down this long AND no usable price → withdraw
    private static final long WITHDRAW_GRACE_MS = 60_000;    // must have been TRYING this long (this process) first
    private static final long FETCH_GAP_MS = 30_000;         // min gap between fetch attempts
    private static final long MIN_REPRICE_GAP_MS = 3 * 60_000;   // chain-spam floor between peg republishes
    private static final double JUMP_FRACTION = 0.5;         // a >50% move needs two consecutive agreeing reads
    private static final double DEPTH_MIN_USDT = 25;         // effective bid/ask = book level where cumulative notional ≥ this

    // Stay-live-with-defensive-widening: when the feed is STALE but we still have a last-good mid, keep the
    // market up (so a phone that sleeps overnight keeps supplying liquidity) but widen the spread the longer
    // the feed's been down, so any stale-price fill is at a price that favours the maker. Snaps back to tight
    // the moment MEXC is reachable again. Only when there is NO usable price at all — OR the feed's been down
    // past the HARD CEILING (truly abandoned) — do we withdraw, so a multi-day outage can't drain the balance.
    private static final long STALE_WIDEN_FULL_MS = 90 * 60_000;      // spread reaches MAX_WIDEN after this long stale
    private static final double MAX_WIDEN = 10.0;                     // max spread multiplier when long-stale
    private static final long HARD_STALE_MS = 12 * 60 * 60_000;       // feed down THIS long → withdraw even with a last price

    // peg config — shared "minimaswap" prefs so MainActivity + SwapService read one source of truth
    public static final String P_ENABLE = "peg_enable";      // boolean: ladder pegged to the oracle
    public static final String P_STEP = "peg_step_pct";      // string double: level spacing, % of mid (>0)
    public static final String P_SIZE = "peg_size";          // string double: MINIMA per level (>0)
    public static final String P_BIAS = "peg_bias_pct";      // string double: skew, ±% shift of the quoted mid
    public static final String P_REPRICE = "peg_reprice_pct";// string double: republish when moved ≥ this %
    public static final String P_LAST_MID = "peg_last_mid";  // string double: oracle mid at the last pegged publish
    public static final String P_WITHDRAWN = "peg_withdrawn";// boolean: order pulled because there was NO usable price
    public static final String P_TOMBSTONED = "peg_tombstoned"; // boolean: the withdraw tombstone CONFIRMED on the wire
    public static final String P_LAST_OK = "peg_last_ok";    // long: when the last good read landed (survives restarts)
    public static final String P_LAST_PRICE = "peg_last_price"; // string double: last-good oracle price (survives restart → stay-live)
    public static final String P_WIDE = "peg_wide";          // boolean: last publish used a widened (stale) spread

    public static final int PEG_OFF = 0;      // not pegged (or unconfigured) — order left untouched
    public static final int PEG_APPLIED = 1;  // ladder regenerated from a FRESH oracle price (tight)
    public static final int PEG_STALE = 2;    // pegged but NO usable price at all — caller must not publish (withdraw)
    public static final int PEG_WIDE = 3;     // stale but priced from the last-good mid with a WIDENED defensive spread

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
    private static SharedPreferences sPrefs;              // set by init(); only P_LAST_OK is written from here
    private static double appliedMid = 0;                 // mid the last applyPeg priced from (for commitPeg)

    /** Hand the oracle the shared "minimaswap" prefs and restore the persisted last-good stamp, so the
     *  feed-down withdraw clock SURVIVES process restarts — OEM kills / AlarmManager relaunches must not
     *  reset the safety window while a stale-priced order is still live on-chain. Call from both hosts'
     *  onCreate, before the first tick. */
    public static void init(SharedPreferences prefs) {
        synchronized (LOCK) {
            sPrefs = prefs;
            long t = prefs.getLong(P_LAST_OK, 0), now = System.currentTimeMillis();
            if (goodAtMs == 0 && t > 0 && t <= now) goodAtMs = t;   // restore the staleness clock
            // Restore the last-good PRICE too so a cold start (overnight process kill) can keep the market
            // LIVE around it — widened by the staleness clock above rather than pulled. A brand-new fetch
            // replaces it as soon as the network is back.
            if (price <= 0) {
                try { double lp = Double.parseDouble(prefs.getString(P_LAST_PRICE, "0"));
                      if (lp > 0 && !Double.isInfinite(lp)) price = lp; } catch (Exception ignore) {}
            }
        }
    }

    // ---- cached snapshot ----

    public static double mid() { synchronized (LOCK) { return price; } }

    public static long ageMs() {
        synchronized (LOCK) {
            if (goodAtMs <= 0) return Long.MAX_VALUE;
            long a = System.currentTimeMillis() - goodAtMs;
            return a < 0 ? Long.MAX_VALUE : a;   // backward clock jump (NTP/manual) must read as STALE, never fresh
        }
    }

    public static boolean fresh() { return mid() > 0 && ageMs() <= FRESH_MS; }

    /** Feed down past the HARD ceiling: withdraw even with a last-good price (bounds a multi-day-outage
     *  pick-off). Distinct from {@link #feedDownPastLimit} (the no-price tombstone window). */
    public static boolean feedDownPastCeiling() { return ageMs() > HARD_STALE_MS; }

    /** No good read for longer than the safety window. The baseline is the persisted last-good stamp (via
     *  init), so process restarts don't reset the clock; and we require ≥ one grace period of actual TRYING
     *  in this process first, so a cold start with an old stamp doesn't tombstone before the first fetch
     *  has had a chance to land. */
    public static boolean feedDownPastLimit() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (firstTryMs == 0 || now - firstTryMs < WITHDRAW_GRACE_MS) return false;
            long ref = goodAtMs > 0 ? goodAtMs : firstTryMs;
            return now - ref > WITHDRAW_MS;
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
            if (age > FRESH_MS) {
                double w = wideningFactor(age);
                return s + "  — STALE · defensive spread ×" + new BigDecimal(w, new MathContext(2)).stripTrailingZeros().toPlainString();
            }
            return s;
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
            double b = 0, a = 0;
            try {
                // PRIMARY: order-book depth. On a thin market the top-of-book is dust and trivially movable,
                // so the effective bid/ask is the level where CUMULATIVE notional reaches DEPTH_MIN_USDT per
                // side — dragging that price requires committing real size, not a dust order.
                JSONObject depth = httpGet(DEPTH_URL);
                b = effectiveLevel(depth.optJSONArray("bids"));
                a = effectiveLevel(depth.optJSONArray("asks"));
            } catch (IOException ignore) {
                // FALLBACK: top-of-book only (depth endpoint unreachable). Weaker but still spread-checked.
                JSONObject book = httpGet(BOOK_URL);
                b = book.optDouble("bidPrice", 0);
                a = book.optDouble("askPrice", 0);
            }
            // No last-trade tier: on a thin market a dust self-trade can print any price inside the book —
            // an empty/absent book means there is no market, and the freshness/withdraw machinery handles
            // "no price" safely. Better no quote than a fake one.
            if (!(b > 0) || !(a > 0) || b > a) throw new IOException("thin/empty book");
            if ((a - b) / a >= 0.2) throw new IOException("spread too wide — book too thin to quote");
            double m = (a + b) / 2;
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
                // Persist BOTH the clock and the price so a restart can keep the market live around the last mid.
                if (sPrefs != null) sPrefs.edit().putLong(P_LAST_OK, goodAtMs)
                        .putString(P_LAST_PRICE, Double.toString(m)).apply();
            }
        } catch (Exception e) {
            synchronized (LOCK) { lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
        }
    }

    /** Price at which cumulative notional (price × qty) reaches DEPTH_MIN_USDT, or 0 if the side can't absorb
     *  it (too thin to quote). Rows are ["price","qty"] pairs, best level first. */
    private static double effectiveLevel(org.json.JSONArray side) {
        try {
            if (side == null) return 0;
            double cum = 0;
            for (int i = 0; i < side.length(); i++) {
                org.json.JSONArray row = side.getJSONArray(i);
                double px = Double.parseDouble(row.getString(0)), qty = Double.parseDouble(row.getString(1));
                if (!(px > 0) || !(qty > 0)) return 0;
                cum += px * qty;
                if (cum >= DEPTH_MIN_USDT) return px;
            }
            return 0;
        } catch (Exception e) { return 0; }
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
     * the result to "order_config" (so a restart arms the responder guard with what is being published).
     * The reprice BASELINE and the withdrawn-clear are NOT committed here — capture {@link #appliedMid()}
     * right after a PEG_APPLIED return and call {@link #commitPeg} from the publish onSent, so a FAILED
     * publish keeps the old baseline (the reprice retries) and stays withdrawn (the restore retries).
     * Returns {@link #PEG_OFF} (order untouched), {@link #PEG_APPLIED}, or {@link #PEG_STALE} — on STALE
     * the caller MUST NOT publish the order.
     */
    public static int applyPeg(Order o, SharedPreferences prefs) {
        if (!prefs.getBoolean(P_ENABLE, false)) return PEG_OFF;
        Order.Pair p = o.pairs.get(Order.PAIR_TOKENS[0]);
        if (p == null || !p.enable) return PEG_OFF;
        double step = prefD(prefs, P_STEP, 0), size = prefD(prefs, P_SIZE, 0);
        double bias = Math.max(-20, Math.min(20, prefD(prefs, P_BIAS, 0)));
        if (!(step > 0) || !(size > 0)) return PEG_OFF;   // unconfigured peg behaves like a manual ladder
        double m; long age;
        synchronized (LOCK) { m = price; age = ageMs(); }
        if (!(m > 0)) return PEG_STALE;   // NO usable price at all (never fetched, none persisted) → don't publish
        if (age > HARD_STALE_MS) return PEG_STALE;   // feed down past the hard ceiling → stop publishing, let withdraw stand
        // STAY LIVE: fresh → tight; stale → keep the market up but widen the spread the longer the feed's down,
        // so an overnight stale-price fill is defensive. wide = MAX_WIDEN once feed's been down ≥ STALE_WIDEN_FULL_MS.
        boolean stale = age > FRESH_MS;
        double effStep = step * wideningFactor(age);
        double quoted = m * (1 + bias / 100.0);
        p.bids.clear(); p.asks.clear();
        for (int i = 1; i <= Order.MAX_LEVELS; i++) {
            p.asks.add(new Order.Level(quoted * (1 + i * effStep / 100.0), size));
            p.bids.add(new Order.Level(quoted * (1 - i * effStep / 100.0), size));
        }
        p.buy = 0; p.sell = 0;   // re-derived from the fresh levels (editor-authoritative pattern)
        Order.sanitize(p);       // drops any bid a huge step pushed ≤ 0
        if (p.asks.isEmpty() && p.bids.isEmpty()) return PEG_STALE;   // belt: nothing valid → don't publish
        prefs.edit().putString("order_config", Order.toConfigJson(o)).apply();
        synchronized (LOCK) { appliedMid = m; }
        return stale ? PEG_WIDE : PEG_APPLIED;
    }

    /** Spread multiplier by feed staleness: 1× while fresh, ramping linearly to {@link #MAX_WIDEN} once the
     *  feed's been down {@link #STALE_WIDEN_FULL_MS}. Keeps a sleeping phone's market live but defensive. */
    private static double wideningFactor(long ageMs) {
        if (ageMs <= FRESH_MS) return 1.0;
        double t = (double) (ageMs - FRESH_MS) / (STALE_WIDEN_FULL_MS - FRESH_MS);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return 1.0 + (MAX_WIDEN - 1.0) * t;
    }

    /** The mid the LAST {@link #applyPeg} priced from. Capture into a final local immediately after a
     *  PEG_APPLIED return (all callers run on the main thread) and hand it to {@link #commitPeg}. */
    public static double appliedMid() { synchronized (LOCK) { return appliedMid; } }

    /** Commit a pegged publish that CONFIRMED on the wire: advance the reprice baseline, clear the
     *  withdrawn/tombstoned state, and record whether the published spread was WIDENED (stale) so the next
     *  fresh read tightens immediately. Call ONLY from the publish onSent — never before. */
    public static void commitPeg(SharedPreferences prefs, double mid, boolean wide) {
        prefs.edit().putString(P_LAST_MID, Double.toString(mid))
                .putBoolean(P_WITHDRAWN, false)
                .putBoolean(P_TOMBSTONED, false)
                .putBoolean(P_WIDE, wide).apply();
    }

    /**
     * Should the maker republish NOW instead of waiting out the 30-min freshness interval? True when the
     * fresh oracle mid has moved ≥ the reprice threshold from the last pegged publish (with a spam floor),
     * or when a withdrawn order can be restored because the feed recovered.
     */
    public static boolean shouldReprice(SharedPreferences prefs, long lastPublishMs) {
        if (!prefs.getBoolean(P_ENABLE, false)) return false;
        double m; long age;
        synchronized (LOCK) { m = price; age = ageMs(); }
        if (!(m > 0)) return false;                             // no price → keep-alive/withdraw handles it
        boolean fresh = age <= FRESH_MS;
        if (prefs.getBoolean(P_WITHDRAWN, false)) return fresh;  // a no-price withdrawal restores once fresh
        if (fresh && prefs.getBoolean(P_WIDE, false)) return true;  // was WIDE, now fresh → tighten NOW
        if (!fresh) return false;                               // stale: stay-live via the 30-min keep-alive, no urgent reprice
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
