package org.minimarex.comms;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Scans a target address for coins whose state[99] is a sealed message for me. NEWBLOCK-driven.
 *
 * SAFETY (unchanged from the Mail scanner): a large `coins` reply can take the node down (IPC Binder /
 * 256 KB limits). So this NEVER issues an unbounded query — it always bounds with `depth:`, starts TINY
 * (depth 4), grows only while replies come back safely, probes smaller on an over-limit/dropped reply,
 * and is throttled so block bursts can't hammer the node.
 *
 * Generalized for miniMall: the target address + the per-coin handling are injected, so one instance can
 * scan the shared MINIMERCH address (inbox: incoming orders; shop: status/chat) while a {@link Router}
 * decides what each opened message means. Opening with my box key IS the "is this for me?" test.
 */
public final class CommsScanner {

    /** Where scan bookmarks live (so different scanners on different addresses don't collide). */
    public interface MetaStore {
        String getMeta(String k, String def);
        void setMeta(String k, String v);
    }

    /** Handles a message that opened for me (sealed to my box key + signature verified by the scanner). */
    public interface Router {
        /** Return true if this produced a NEW stored item (drives newCount + notifications).
         *  {@code coinid} is the dedup key; {@code coin} is the raw coin (amount/tokenid/created if needed). */
        boolean handle(String coinid, Opened opened, JSONObject coin);
    }

    public interface Listener { void onDone(boolean ok, int newCount); }

    private static final int START_DEPTH = 4;          // every scan's FIRST query is this small + safe
    private static final int MAX_DEPTH = 64;           // grow no deeper than this in one pass
    private static final long MIN_INTERVAL_MS = 4000;  // throttle: don't hammer the node on bursts

    private final NodeApi node;
    private final CryptoProvider crypto;
    private final MetaStore meta;
    private final String targetAddress;
    private final Router router;
    private final Listener listener;
    private final boolean decrypt;              // true: open state[99]; false: hand every coin raw (payments)
    private final String bmBackfilled, bmTip;   // address-namespaced bookmark keys

    private boolean running = false;
    private boolean tracked = false;
    private boolean grew = false;                // got at least one safe (non-over-limit) page this pass
    private int depth, targetDepth, newThisRun;
    private long lastScanEnd = 0;
    private final Set<String> seenCoins = new HashSet<>();   // skip re-decrypting overlap while growing

    /** Sealed-message scanner: opens state[99] with my box key (decrypt = true). */
    public CommsScanner(NodeApi node, CryptoProvider crypto, MetaStore meta,
                        String targetAddress, Router router, Listener listener) {
        this(node, crypto, meta, targetAddress, router, listener, true);
    }

    /** Raw scanner (decrypt = false): hands EVERY coin to the router — e.g. to watch plaintext payments. */
    public CommsScanner(NodeApi node, CryptoProvider crypto, MetaStore meta,
                        String targetAddress, Router router, Listener listener, boolean decrypt) {
        this.node = node; this.crypto = crypto; this.meta = meta;
        this.targetAddress = targetAddress; this.router = router; this.listener = listener;
        this.decrypt = decrypt;
        String tag = targetAddress.length() > 10 ? targetAddress.substring(2, 10) : targetAddress;
        this.bmBackfilled = "backfilled_" + (decrypt ? "" : "pay_") + tag;
        this.bmTip = "scanned_tip_" + (decrypt ? "" : "pay_") + tag;
    }

    public void scan(final int chainBlock) {
        if (running) return;
        if (System.currentTimeMillis() - lastScanEnd < MIN_INTERVAL_MS) return;   // throttle
        running = true; grew = false; newThisRun = 0; seenCoins.clear();
        boolean backfillRun = meta.getMeta(bmBackfilled, "").isEmpty();
        int scannedTip = parseInt(meta.getMeta(bmTip, "0"));
        int gap = (chainBlock > 0) ? Math.max(1, chainBlock - scannedTip + 1) : MAX_DEPTH;
        targetDepth = Math.min(MAX_DEPTH, backfillRun ? MAX_DEPTH : gap);
        depth = Math.min(START_DEPTH, targetDepth);    // ALWAYS start small — never an unbounded query
        if (tracked) { fetch(chainBlock); return; }
        node.cmd("coinnotify action:add address:" + targetAddress, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { tracked = true; fetch(chainBlock); }
            @Override public void onError(String m) { fetch(chainBlock); }
        });
    }

    private void fetch(final int chainBlock) {
        // ALWAYS bounded by depth — never an unbounded `coins` (a huge reply can crash the node).
        String cmd = "coins address:" + targetAddress + " order:desc depth:" + depth;
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray coins = j.optJSONArray("response");
                if (coins == null || !j.optBoolean("status", true)) { overLimit(chainBlock); return; }
                grew = true;
                newThisRun += process(coins);
                if (depth < targetDepth) { depth = Math.min(depth * 2, targetDepth); fetch(chainBlock); }  // grow while safe
                else finish(chainBlock, true);
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) { finishFail(); return; }
                overLimit(chainBlock);
            }
        });
    }

    /** A page came back dropped/refused. Probe smaller if we've not safely read anything yet; else stop. */
    private void overLimit(int chainBlock) {
        if (!grew && depth > 1) { depth = Math.max(1, depth / 2); fetch(chainBlock); }
        else finish(chainBlock, grew);
    }

    private void finish(int chainBlock, boolean ok) {
        if (ok) meta.setMeta(bmBackfilled, "true");
        if (chainBlock > 0) meta.setMeta(bmTip, String.valueOf(chainBlock));
        running = false; lastScanEnd = System.currentTimeMillis();
        listener.onDone(ok, newThisRun);
    }

    private void finishFail() { running = false; lastScanEnd = System.currentTimeMillis(); listener.onDone(false, 0); }

    private int process(JSONArray coins) {
        int newCount = 0;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject coin = coins.optJSONObject(i);
            if (coin == null) continue;
            String cid = coin.optString("coinid", "");
            if (!cid.isEmpty() && !seenCoins.add(cid)) continue;   // already handled this run (grow overlap)
            if (!decrypt) {                                        // raw mode — payments etc.
                if (router.handle(cid, null, coin)) newCount++;
                continue;
            }
            String blob = statePort(coin, 99);
            if (blob == null) continue;
            Opened o = crypto.open(blob);
            if (o == null || !o.valid) continue;                   // not for me / bad signature
            if (router.handle(cid, o, coin)) newCount++;
        }
        return newCount;
    }

    /** Read a coin state port. State may be an array [{port,data}] or an object {"99":...} per node build. */
    public static String statePort(JSONObject coin, int port) {
        Object st = coin.opt("state");
        if (st instanceof JSONObject) {
            String v = ((JSONObject) st).optString(String.valueOf(port), "");
            return v.isEmpty() ? null : v;
        }
        if (st instanceof JSONArray) {
            JSONArray a = (JSONArray) st;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && p.optInt("port", -1) == port) {
                    String v = p.optString("data", "");
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
