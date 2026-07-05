package com.eurobuddha.minimaswap.swap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A standing swap offer broadcast to the order book. Carries the maker's identities (Minima HTLC key,
 * ETH receiving address, comms publicId for the sealed handshake) and their rates per token pair.
 * The maker signs the canonical JSON with their Ed25519 comms key; takers verify before trusting it.
 *
 * Since 0.9.0 a pair can carry a DEPTH LADDER — up to {@link #MAX_LEVELS} bid tranches and ask tranches,
 * each with its own price (USDT per MINIMA) and amount (MINIMA, the per-take cap). The legacy scalars
 * stay populated with the ladder's best prices so 0.8.x clients keep seeing a valid single-level quote
 * (their fromJson reads buy/sell/min via no-default optDouble — a missing key would be NaN and poison them).
 *
 * NAMING INVERSION (historical, wire-compatible — carry it everywhere): the scalar {@code buy} is the
 * ASK (maker SELLS MINIMA, the higher price); {@code sell} is the BID (maker BUYS MINIMA, the lower price).
 * The ladder lists use honest names: {@code asks} pairs with {@code buy}, {@code bids} pairs with {@code sell}.
 */
public final class Order {

    /** The single trading pair today. Shared by MainActivity + SwapService so config parsing has one home. */
    public static final String[] PAIR_TOKENS = {"USDT"};

    public static final int MAX_LEVELS = 6;   // max ladder tranches per side

    public String minimaPublicKey;   // maker's Minima public key (the HTLC counterparty field)
    public String ethAddress;        // maker's ETH receiving address
    public String commsPublicId;     // 0x + boxPk + signPk — used to seal the handshake to the maker
    public long ts;                  // maker's timestamp (ms) — freshest order per signer wins
    public double minimaAvail;       // maker's available MINIMA at publish time (liquidity, ask side)
    public double usdtAvail;         // maker's available USDT at publish time (liquidity, bid side)
    public final Map<String, Pair> pairs = new LinkedHashMap<>();   // "USDT" -> rate

    // set on receive (not part of the signed payload)
    public String signerPk;          // 0x + Ed25519 pk recovered from the coin (verified signer)
    public String coinid;            // source coin id (dedup / expiry)

    /** One ladder tranche: a price and the maximum MINIMA a single take may fill at it. */
    public static final class Level {
        public double price;    // USDT per MINIMA
        public double amount;   // MINIMA at this level (per-take cap)
        public Level() {}
        public Level(double price, double amount) { this.price = price; this.amount = amount; }
    }

    public static final class Pair {
        public boolean enable;
        public double buy;     // legacy scalar = ASK: price where the maker SELLS MINIMA (higher)
        public double sell;    // legacy scalar = BID: price where the maker BUYS MINIMA (lower)
        public double min;     // minimum MINIMA per trade (global, applies to every level)
        public final List<Level> bids = new ArrayList<>();   // maker BUYS MINIMA — sorted price DESC (best first)
        public final List<Level> asks = new ArrayList<>();   // maker SELLS MINIMA — sorted price ASC (best first)
        public Pair() {}
        public Pair(boolean enable, double buy, double sell, double min) {
            this.enable = enable; this.buy = buy; this.sell = sell; this.min = min;
        }
    }

    private static boolean validPos(double d) { return !Double.isNaN(d) && !Double.isInfinite(d) && d > 0; }

    /**
     * THE single normalizer — called after every parse and before every serialize so the ladder is
     * always: valid levels only (finite, >0 price and amount), at most MAX_LEVELS per side, bids sorted
     * price-DESC / asks price-ASC (best first), and the legacy scalars derived from the best levels.
     * A side with NO ladder levels leaves its scalar UNTOUCHED (legacy single-price behaviour; 0 = side off),
     * so a no-op Save on a pre-ladder config changes nothing.
     */
    public static void sanitize(Pair p) {
        if (p == null) return;
        norm(p.bids, true);
        norm(p.asks, false);
        if (!p.bids.isEmpty()) p.sell = p.bids.get(0).price;   // best (highest) bid
        if (!p.asks.isEmpty()) p.buy = p.asks.get(0).price;    // best (lowest) ask
        if (Double.isNaN(p.buy) || Double.isInfinite(p.buy)) p.buy = 0;
        if (Double.isNaN(p.sell) || Double.isInfinite(p.sell)) p.sell = 0;
        if (Double.isNaN(p.min) || Double.isInfinite(p.min)) p.min = 0;
    }

    private static void norm(List<Level> levels, boolean desc) {
        for (Iterator<Level> it = levels.iterator(); it.hasNext(); ) {
            Level l = it.next();
            if (l == null || !validPos(l.price) || !validPos(l.amount)) it.remove();
        }
        Collections.sort(levels, (a, b) -> desc ? Double.compare(b.price, a.price) : Double.compare(a.price, b.price));
        while (levels.size() > MAX_LEVELS) levels.remove(levels.size() - 1);
    }

    /** Crossed market: best bid at-or-above best ask (maker would sell cheaper than they buy). Warn-only. */
    public static boolean crossed(Pair p) {
        return p != null && !p.bids.isEmpty() && !p.asks.isEmpty()
                && p.bids.get(0).price >= p.asks.get(0).price;
    }

    /**
     * The maker's bid levels (maker BUYS MINIMA), or a single synthetic level from the legacy scalar for
     * pre-ladder orders — so display/take code has ONE uniform per-level path. Synthetic size = the
     * balance-derived formula the old UI used (usdtAvail / price).
     */
    public List<Level> effectiveBids(String sym) {
        Pair p = pairs.get(sym);
        if (p == null || !p.enable) return Collections.emptyList();
        if (!p.bids.isEmpty()) return p.bids;
        if (validPos(p.sell)) {
            List<Level> one = new ArrayList<>(1);
            one.add(new Level(p.sell, usdtAvail > 0 ? usdtAvail / p.sell : 0));
            return one;
        }
        return Collections.emptyList();
    }

    /** The maker's ask levels (maker SELLS MINIMA), or a synthetic single level (size = minimaAvail). */
    public List<Level> effectiveAsks(String sym) {
        Pair p = pairs.get(sym);
        if (p == null || !p.enable) return Collections.emptyList();
        if (!p.asks.isEmpty()) return p.asks;
        if (validPos(p.buy)) {
            List<Level> one = new ArrayList<>(1);
            one.add(new Level(p.buy, minimaAvail));
            return one;
        }
        return Collections.emptyList();
    }

    /** True if any enabled pair currently advertises at least one bid or ask level (real or synthetic). A saved
     *  order with this false is effectively withdrawn — publishing it acts as a tombstone in the freshest-per-
     *  signer book (peers drop my liquidity on their next scan). */
    public boolean hasLiquidity() {
        for (String sym : pairs.keySet())
            if (!effectiveBids(sym).isEmpty() || !effectiveAsks(sym).isEmpty()) return true;
        return false;
    }

    /** Largest ask per-take cap across a symbol's enabled tranches (0 if none) — one coin ≥ this backs any tranche. */
    public double maxAskAmount(String sym) {
        double m = 0;
        for (Level l : effectiveAsks(sym)) if (l.amount > m) m = l.amount;
        return m;
    }

    /** Trim a symbol's advertised ask ladder to its first {@code k} (best-price) tranches — so a maker never
     *  advertises a tranche it has too few spendable coins to lock. No-op for a synthetic single-level order. */
    public void trimAsks(String sym, int k) {
        Pair p = pairs.get(sym);
        if (p == null || p.asks.isEmpty() || k < 0) return;
        while (p.asks.size() > k) p.asks.remove(p.asks.size() - 1);
        // If we trimmed the ladder to nothing, also clear the legacy best-ask scalar — else effectiveAsks would
        // resurrect a SYNTHETIC single ask for the whole balance (exactly the unbacked tranche we're avoiding).
        if (p.asks.isEmpty()) p.buy = 0;
    }

    // ---- (de)serialization — ONE pair codec shared by the signed wire format AND the local config ----

    /** Pair → JSON. Always writes en/buy/sell/min (0.8.x compat) + bids/asks when present. */
    static JSONObject pairToJson(Pair v) throws JSONException {
        sanitize(v);
        JSONObject j = new JSONObject()
                .put("en", v.enable).put("buy", v.buy).put("sell", v.sell).put("min", v.min);
        if (!v.bids.isEmpty()) j.put("bids", levelsToJson(v.bids));
        if (!v.asks.isEmpty()) j.put("asks", levelsToJson(v.asks));
        return j;
    }

    private static JSONArray levelsToJson(List<Level> levels) throws JSONException {
        JSONArray a = new JSONArray();
        for (Level l : levels) a.put(new JSONObject().put("p", l.price).put("a", l.amount));
        return a;
    }

    /** JSON → Pair. {@code scalarDefault} = 0 for wire orders (0 = side off), 1.0 for local config (legacy). */
    static Pair pairFromJson(JSONObject v, double scalarDefault) {
        Pair p = new Pair(v.optBoolean("en", false),
                v.optDouble("buy", scalarDefault), v.optDouble("sell", scalarDefault), v.optDouble("min", scalarDefault));
        readLevels(v.optJSONArray("bids"), p.bids);
        readLevels(v.optJSONArray("asks"), p.asks);
        sanitize(p);
        return p;
    }

    private static void readLevels(JSONArray a, List<Level> out) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject l = a.optJSONObject(i);
            if (l != null) out.add(new Level(l.optDouble("p", 0), l.optDouble("a", 0)));
        }
    }

    /** Canonical JSON that gets signed (the order without receive-side fields). */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("mpk", minimaPublicKey);
        o.put("eth", ethAddress);
        o.put("cid", commsPublicId);
        o.put("ts", ts);
        o.put("bal", new JSONObject().put("m", minimaAvail).put("u", usdtAvail));
        JSONObject p = new JSONObject();
        for (Map.Entry<String, Pair> e : pairs.entrySet()) p.put(e.getKey(), pairToJson(e.getValue()));
        o.put("pairs", p);
        return o;
    }

    public static Order fromJson(JSONObject o) {
        Order r = new Order();
        r.minimaPublicKey = o.optString("mpk", "");
        r.ethAddress = o.optString("eth", "");
        r.commsPublicId = o.optString("cid", "");
        r.ts = o.optLong("ts", 0);
        JSONObject bal = o.optJSONObject("bal");
        if (bal != null) { r.minimaAvail = bal.optDouble("m", 0); r.usdtAvail = bal.optDouble("u", 0); }
        JSONObject p = o.optJSONObject("pairs");
        if (p != null) {
            for (Iterator<String> it = p.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject v = p.optJSONObject(k);
                if (v != null) r.pairs.put(k, pairFromJson(v, 0));
            }
        }
        return r;
    }

    // ---- local persisted config ("order_config" prefs) — same pair schema as the wire ----

    /** Serialize an order's pairs for the local prefs config. */
    public static String toConfigJson(Order o) {
        try {
            JSONObject cfg = new JSONObject();
            for (Map.Entry<String, Pair> e : o.pairs.entrySet()) cfg.put(e.getKey(), pairToJson(e.getValue()));
            return cfg.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Load an order from the local prefs config — always yields a Pair per PAIR_TOKENS symbol. */
    public static Order fromConfigJson(String raw) {
        Order o = new Order();
        JSONObject cfg = null;
        try { if (raw != null && !raw.isEmpty()) cfg = new JSONObject(raw); } catch (Exception ignore) {}
        for (String sym : PAIR_TOKENS) {
            JSONObject v = cfg != null ? cfg.optJSONObject(sym) : null;
            o.pairs.put(sym, v != null ? pairFromJson(v, 1.0) : new Pair(false, 1.0, 1.0, 1.0));
        }
        return o;
    }
}
