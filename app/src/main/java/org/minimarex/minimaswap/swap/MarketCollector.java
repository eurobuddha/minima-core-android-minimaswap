package org.minimarex.minimaswap.swap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds a NETWORK-WIDE trade history from on-chain HTLC locks (the market data feed). On-chain only shows
 * OPEN locks and the price-bearing coin is spent on completion, so there's no backfill — we poll the shared
 * HTLC address each watcher cycle, persist every observed lock {@code (price = reqUSDT/MINIMA, size, hash,
 * block)}, then reconcile locks that have since been spent to EXECUTED (a notify coin revealed the secret) or
 * REFUNDED (no notify ⇒ refund; the KISS script forces a notify on every claim).
 *
 * Direction (taker buy vs sell) is NOT recoverable from the Minima coin — the counter-leg lives on Ethereum —
 * so this is a price feed, not green/red prints.
 */
public final class MarketCollector {

    private static final int HTLC_DEPTH   = 256;   // bounded — the HTLC address is a shared, potentially heavy sink
    private static final int NOTIFY_DEPTH = 256;

    private MarketCollector() {}

    /** One collection cycle. Safe to call repeatedly from the 90s tick; all writes are idempotent. */
    public static void poll(MinimaHtlc minima, SwapDb db, int tipBlock) {
        if (minima == null || db == null || !minima.ready()) return;
        minima.scanAllHtlcCoins(HTLC_DEPTH, coins -> ingest(minima, db, coins, tipBlock), e -> {});
    }

    private static void ingest(MinimaHtlc minima, SwapDb db, JSONArray coins, int tipBlock) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.optJSONObject(i);
            if (c == null) continue;
            String coinid = c.optString("coinid", "");
            if (coinid.isEmpty()) continue;
            String size = c.optString("amount", "0");          // MINIMA locked = trade size
            String reqAmount = MinimaHtlc.stateAt(c, 1);        // counter-asset (USDT) requested
            double price = price(reqAmount, size);
            if (price <= 0) continue;                           // not a priced lock — skip
            SwapDb.MarketTrade t = new SwapDb.MarketTrade();
            t.coinid = coinid;
            t.hash = MinimaHtlc.stateAt(c, 5);
            t.price = price;
            t.sizeMinima = size;
            t.reqAmount = reqAmount;
            t.reqToken = MinimaHtlc.stateAt(c, 2);
            t.owner = MinimaHtlc.stateAt(c, 0);
            t.receiver = MinimaHtlc.stateAt(c, 4);
            t.createdBlock = c.optLong("created", tipBlock);
            t.timelock = parseLong(MinimaHtlc.stateAt(c, 3));
            db.upsertOpenTrade(t);
            seen.add(coinid);
        }
        // Any lock we had OPEN that's no longer in the scan has been spent → classify it.
        for (SwapDb.MarketTrade open : db.openTrades()) {
            if (!seen.contains(open.coinid)) reconcileSpent(minima, db, open, tipBlock);
        }
    }

    /**
     * A spent lock: EXECUTED if its hashlock has a notify coin (the claim revealed the secret). Otherwise a
     * refund is only possible AFTER the timelock — so if we're past it, REFUNDED; if not, the lock was just
     * claimed and the notify coin isn't visible yet, so leave it OPEN and retry (avoids mislabelling a fill
     * as a cancel during the confirmation window).
     */
    private static void reconcileSpent(MinimaHtlc minima, SwapDb db, SwapDb.MarketTrade t, int tipBlock) {
        minima.scanNotifySecret(t.hash, NOTIFY_DEPTH, notifyCoins -> {
            String secret = secretFor(notifyCoins, t.hash);
            if (secret != null) db.markTradeExecuted(t.coinid, secret);
            else if (t.timelock > 0 && tipBlock > t.timelock) db.markTradeRefunded(t.coinid);
            // else: claimed but notify not yet visible — leave OPEN, retry next cycle
        }, e -> { /* node busy; retry next cycle */ });
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String secretFor(JSONArray notifyCoins, String hash) {
        String want = MinimaHtlc.normKey(hash);
        for (int i = 0; i < notifyCoins.length(); i++) {
            JSONObject c = notifyCoins.optJSONObject(i);
            if (c == null) continue;
            if (MinimaHtlc.normKey(MinimaHtlc.stateAt(c, 101)).equals(want)) {
                String s = MinimaHtlc.stateAt(c, 100);
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static double price(String reqAmount, String sizeMinima) {
        try {
            BigDecimal req = new BigDecimal(reqAmount.trim());
            BigDecimal size = new BigDecimal(sizeMinima.trim());
            if (size.signum() <= 0 || req.signum() <= 0) return 0;
            return req.divide(size, 12, BigDecimal.ROUND_HALF_UP).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }
}
