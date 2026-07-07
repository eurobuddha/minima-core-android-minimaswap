package com.eurobuddha.minimaswap.swap;

/**
 * Cross-host publish coordination. The Activity and the Service share one process (same convention as
 * {@code MainActivity.FOREGROUND}), so statics are the correct scope — and deliberately in-memory: no
 * publish survives a dead process, so neither should the guard.
 *
 * IN-FLIGHT GUARD — one publish per slot (order book / OTC board) at a time, with a timeout so a lost
 * node callback can never wedge the keep-alive.
 *
 * SUPERSEDE GENERATION — user actions (manual publish, edit, withdraw) and the peg-tombstone safety path
 * bump {@link #bumpGen()}. An AUTO publish captures {@link #gen()} before sending; if the generation has
 * moved by the time its onSent fires, the result is stale and must be dropped (no ok-stamp, no
 * {@code engine.setMyOrder}) — a stale auto result can never overwrite fresher user or safety intent.
 */
public final class PublishGate {

    public static final int ORDER = 0, OTC = 1;

    /** Longer than the worst publish pipeline: ladder coin read + balance read + the 180s txnpost write timeout. */
    private static final long TIMEOUT_MS = 5 * 60_000;

    private static final long[] since = new long[2];
    private static long gen = 0;

    private PublishGate() {}

    /** Claim the slot; false while another publish is in flight (and hasn't timed out). */
    public static synchronized boolean tryAcquire(int slot) {
        long now = System.currentTimeMillis();
        if (since[slot] != 0 && now - since[slot] < TIMEOUT_MS) return false;
        since[slot] = now;
        return true;
    }

    public static synchronized void release(int slot) { since[slot] = 0; }

    /** Capture before an AUTO publish; compare in its onSent — drop the result if it moved. */
    public static synchronized long gen() { return gen; }

    /** User/safety intent changed — every in-flight AUTO publish result is now stale. */
    public static synchronized void bumpGen() { gen++; }
}
