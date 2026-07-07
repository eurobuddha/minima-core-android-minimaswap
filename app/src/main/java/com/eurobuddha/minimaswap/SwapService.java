package com.eurobuddha.minimaswap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.comms.Sodium;
import com.eurobuddha.minimaswap.eth.EthNet;
import com.eurobuddha.minimaswap.eth.EthRpc;
import com.eurobuddha.minimaswap.eth.EthWallet;
import com.eurobuddha.minimaswap.swap.MinimaHtlc;
import com.eurobuddha.minimaswap.swap.Order;
import com.eurobuddha.minimaswap.swap.OtcBook;
import com.eurobuddha.minimaswap.swap.OtcController;
import com.eurobuddha.minimaswap.swap.OtcDb;
import com.eurobuddha.minimaswap.swap.OtcMessage;
import com.eurobuddha.minimaswap.swap.OtcOffer;
import com.eurobuddha.minimaswap.swap.PriceOracle;
import com.eurobuddha.minimaswap.swap.PublishGate;
import com.eurobuddha.minimaswap.swap.SwapDb;
import com.eurobuddha.minimaswap.swap.SwapEngine;
import com.eurobuddha.minimaswap.swap.SwapOrderBook;
import com.eurobuddha.minimaswap.swap.SwapTake;

/**
 * Foreground service that drives the swap engine whenever the node is reachable — so in-flight swaps keep
 * progressing (claim the counter-leg, refund on timeout) with the app closed, not just when it's on-screen.
 * Mirrors {@code minima-limit}'s LimitService. It hosts its OWN engine + node IPC; the shared {@link SwapDb}
 * is the source of truth, and a {@code MainActivity.FOREGROUND} guard means only one of {Activity, Service}
 * polls at a time. The node alone can't do this work — we're a separate process — so this is the closest
 * equivalent to "just keep the node online", at the cost of an ongoing silent notification.
 */
public class SwapService extends Service {

    private static final String CH_FG = "swap_fg";       // ongoing foreground notification
    private static final String CH_ALERT = "minimaswap"; // swap event alerts (same channel MainActivity uses)
    private static final String CH_OFFLINE = "swap_offline";   // HIGH-priority "market offline" alert
    private static final int FG_ID = 4101;
    private static final int OFFLINE_ID = 4102;          // fixed id — one offline alert, updated in place
    private static final long INTERVAL_MS = 90_000;
    private static final long DISCOVERY_INTERVAL_MS = 30_000;   // fast, LIGHT handshake-discovery scan (heavy poll stays 90s)
    private static final long REREGISTER_GAP_MS = 30_000;       // pairing retry cadence while the node is unreachable
    private static final long OFFLINE_ALERT_AFTER_MS = 3 * 60_000;   // unpaired this long with a market on → alert
    private static final long OFFLINE_REALERT_MS = 60 * 60_000;      // then re-alert hourly until fixed
    /** Heartbeat intent: one Doze-proof tick pass (the 90s Handler loop stalls when the CPU suspends). */
    public static final String ACTION_HEARTBEAT = "com.eurobuddha.minimaswap.HEARTBEAT";
    private int alertId = 5100;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LazySodium ls;
    private NodeApi node;
    private MinimaHtlc minima;
    private final EthWallet wallet = new EthWallet();
    private SwapDb db;
    private SwapEngine engine;
    private CommsIdentity identity;       // to sign order-book republishes in the background
    private CryptoProvider crypto;        // to open buyers' sealed hashlock handshakes
    private CommsScanner takeScanner;
    private CommsScanner otcScanner;
    private OtcDb otcDb;
    private OtcController otc;
    private String myMinimaPk;

    // Self-heal state (all main-thread). The old one-shot `booted` latch is gone: after a reboot with the
    // node app not yet running, the single constructor REGISTER was lost and the service sat alive-but-inert
    // forever (2026-07-07 outage). Now the tick loop always runs and re-registers until paired.
    private boolean paired = false;
    private long lastRegisterMs = 0;
    private long unpairedSince = 0;
    private long lastOfflineAlertMs = 0;
    private long lastTickPassMs = 0;              // heartbeat/loop overlap guard
    private boolean walletDeriving = false, minimaSettingUp = false, identityDeriving = false;
    private boolean keysLoaded = false, keysLoading = false;
    private int orderMisses = 0, otcMisses = 0;   // consecutive book-presence misses (the belt)
    private String fgText;                        // last foreground-notification text (update on change only)

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        // If the OS refuses the foreground service (e.g. a residual dataSync time-budget on Android 14),
        // bail gracefully instead of crashing — WorkManager/AlarmManager relaunch will retry.
        if (!startForegroundCompat()) { stopSelf(); return; }

        prefs = getSharedPreferences("minimaswap", MODE_PRIVATE);
        PriceOracle.init(prefs);   // restore the persisted last-good stamp — the withdraw clock survives restarts
        ls = Sodium.get();
        EthNet net = EthNet.MAINNET;
        EthRpc rpc = new EthRpc(prefs.getString("rpc_mainnet", net.defaultRpc));
        db = new SwapDb(this);

        node = new NodeApi(this, this::onPaired);
        minima = new MinimaHtlc(node);
        engine = new SwapEngine(node, minima, db, wallet, h, notifier);
        otcDb = new OtcDb(this);
        engine.setOtcDb(otcDb);
        otc = new OtcController(node, otcDb, engine, new OtcController.Ui() {
            @Override public void onDealsChanged() {}
            @Override public void note(String title, String body) { alert(title, body); }
        });
        engine.setNetwork(rpc, net);
        // armSafe: while a pegged order is withdrawn (stale MEXC feed) arm an EMPTY order — decline every take.
        engine.setMyOrder(PriceOracle.armSafe(loadOrder(), prefs));

        // Start the loops UNCONDITIONALLY — pairing/derivations/publish all self-heal inside tickPass.
        // (The old code only started them from onPaired, which never fired if the node was down at launch.)
        unpairedSince = System.currentTimeMillis();
        lastRegisterMs = unpairedSince;   // the constructor's REGISTER counts as attempt #1 — don't instantly redo it
        SwapLog.d("svc onCreate — loops starting");
        h.post(tick);
        h.postDelayed(discoveryTick, DISCOVERY_INTERVAL_MS);
        HeartbeatReceiver.schedule(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HEARTBEAT.equals(intent.getAction()) && node != null) {
            SwapLog.d("heartbeat");
            acquireTimedWakelock();
            if (prefs.getBoolean(PriceOracle.P_ENABLE, false)) PriceOracle.refreshAsync();
            tickPass();                  // respects the FOREGROUND/SWEEP standdown + the 30s overlap guard
            reconcileBookPresence();     // the belt — see below
        }
        return START_STICKY;
    }

    /** Timed partial wakelock so a Doze-woken heartbeat can finish one publish pipeline before the CPU re-suspends. */
    private void acquireTimedWakelock() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "minimaswap:heartbeat");
            wl.setReferenceCounted(false);
            wl.acquire(3 * 60_000);   // timed — auto-releases, can never leak
        } catch (Exception ignored) {}
    }

    /** The user swiped the app off recents — keep watching: reschedule the worker + AlarmManager relaunch. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        try { SwapWorker.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try { HeartbeatReceiver.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.app.PendingIntent pi = android.app.PendingIntent.getForegroundService(
                    getApplicationContext(), 11, new Intent(getApplicationContext(), SwapService.class),
                    android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            // RTC_WAKEUP + AllowWhileIdle so the relaunch fires even under Doze (the old inexact RTC alarm
            // was deferred to the maintenance window, leaving the market dark for hours).
            if (am != null) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (engine != null) engine.shutdown();
        if (node != null) node.onDestroy();
    }

    // ----- pairing (state-change handler, NOT a boot latch — the loops already run) -----

    private void onPaired(boolean enabled) {
        boolean was = paired;
        paired = enabled;
        SwapLog.d("svc paired=" + enabled);
        if (enabled) {
            unpairedSince = 0;
            lastOfflineAlertMs = 0;
            cancelOfflineAlert();
            ensureDerivations();
        } else if (was || unpairedSince == 0) {
            unpairedSince = System.currentTimeMillis();
        }
        updateFg(fgStateText());
    }

    /**
     * Idempotent, retrying bootstrap. Each derivation is a read-only node command that reuses the
     * persisted swap_addr/swap_pk (so the published maker key never changes); a transient failure clears
     * its in-flight flag and retries on the next tick. The old one-shot version had empty error callbacks
     * — a single failure left the service alive-but-inert (no identity → every publish path early-returns).
     */
    private void ensureDerivations() {
        if (!wallet.ready() && !walletDeriving) {
            walletDeriving = true;
            wallet.deriveFromNode(node, h, new EthWallet.Cb() {
                @Override public void ok(String address) { walletDeriving = false; SwapLog.d("svc eth wallet ready"); }
                @Override public void err(String msg) { walletDeriving = false; SwapLog.w("svc eth wallet derive failed"); }   // msg may echo key material — never log it
            });
        }
        if (!minima.ready() && !minimaSettingUp) {
            minimaSettingUp = true;
            String savedAddr = prefs.getString("swap_addr", "");
            String savedPk = prefs.getString("swap_pk", "");
            minima.setup(savedAddr, savedPk, new MinimaHtlc.SetupCb() {
                @Override public void ok(String a, String pk) {
                    minimaSettingUp = false;
                    myMinimaPk = pk;
                    prefs.edit().putString("swap_addr", a).putString("swap_pk", pk).apply();
                    engine.setMyMinimaPk(pk);
                    SwapLog.d("svc minima identity ready");
                }
                @Override public void err(String m) { minimaSettingUp = false; SwapLog.w("svc minima setup: " + m); }
            });
        }
        if (minima.ready() && !keysLoaded && !keysLoading) {
            keysLoading = true;
            minima.loadMyKeys(new MinimaHtlc.KeysCb() {
                @Override public void ok(java.util.Set<String> keys) { keysLoading = false; keysLoaded = true; engine.setMyPubkeys(keys); }
                @Override public void err(String m) { keysLoading = false; SwapLog.w("svc loadMyKeys: " + m); }
            });
        }
        if (identity == null && !identityDeriving) deriveIdentity();
    }

    /** Derive the comms signing identity from the node seed (only needed to republish the maker's order). */
    private void deriveIdentity() {
        identityDeriving = true;
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                final String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) { identityDeriving = false; SwapLog.w("svc identity: empty seed reply (vault locked?)"); return; }
                new Thread(() -> {
                    try {
                        byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                        h.post(() -> { identity = id; crypto = new LocalEcCryptoProvider(ls, id); identityDeriving = false; SwapLog.d("svc comms identity ready"); });
                    } catch (Exception e) {
                        h.post(() -> { identityDeriving = false; SwapLog.w("svc identity derive failed: " + e.getClass().getSimpleName()); });
                    }
                }).start();
            }
            @Override public void onError(String m) { identityDeriving = false; SwapLog.w("svc identity seed read: " + m); }
        });
    }

    /** 90s poll loop — the body lives in {@link #tickPass()} so the Doze heartbeat can drive the same pass. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickPass();
            h.postDelayed(this, INTERVAL_MS);
        }
    };

    /** One self-healing pass: re-register while unpaired, retry derivations, then poll/publish. */
    private void tickPass() {
        if (node == null || prefs == null) return;   // onCreate bailed (FGS refused) — nothing is wired
        long now = System.currentTimeMillis();
        if (now - lastTickPassMs < 30_000) return;   // heartbeat + loop overlap guard
        lastTickPassMs = now;
        if (!paired) {
            maybeReRegister();
            maybeOfflineAlert();
            updateFg(fgStateText());
            return;
        }
        ensureDerivations();
        // Stand down while the Activity is foreground (it polls then) or mid-sweep — its sequential legs
        // own the ETH "pending" nonce / MINIMA coin selection; a Service poll could submit a colliding tx.
        if (!MainActivity.FOREGROUND && !MainActivity.SWEEP_ACTIVE && engine != null) {
            // Re-arm the responder guard from the PERSISTED config on EVERY pass, BEFORE poll() — a
            // cancel/edit made in the other host must reach this engine's guard (a cancelled order's
            // pairs are disabled → declines every take), and after a fg/bg handoff the Activity may have
            // withdrawn/restored while this engine still held the pre-handoff order.
            engine.setMyOrder(PriceOracle.armSafe(loadOrder(), prefs));
            if (prefs.getBoolean(PriceOracle.P_ENABLE, false) && prefs.getBoolean("auto_publish", false)) {
                PriceOracle.refreshAsync();   // keep the peg's price fresh in the background (rate-limited)
                maybePegWithdraw();           // feed down too long → pull the order + disarm the guard
            }
            engine.poll();
            maybeAutoRepublish();
            maybeRepublishOtc();              // OTC availability parity — it ages out of the board too
            if (prefs.getBoolean("auto_publish", false)) engine.maintainLadderCoins(true);
        }
    }

    /** While unpaired, re-send the pairing REGISTER every ~30s — the constructor's one-shot is lost if the
     *  node app isn't running yet (post-reboot, force-stopped, Secure Folder just unlocked). */
    private void maybeReRegister() {
        long now = System.currentTimeMillis();
        if (now - lastRegisterMs < REREGISTER_GAP_MS) return;
        lastRegisterMs = now;
        node.reRegister();
    }

    /** Fast, LIGHT discovery tick (~30s): only the bounded handshake scan (kicks checkBuyNow on a new buy) so a
     *  buyer is seen in ~1 block, not up to a 90s poll. Stands down when the Activity is foreground/sweeping. */
    private final Runnable discoveryTick = new Runnable() {
        @Override public void run() {
            if (!MainActivity.FOREGROUND && !MainActivity.SWEEP_ACTIVE && engine != null) { scanTakeRequests(); scanOtc(); }
            h.postDelayed(this, DISCOVERY_INTERVAL_MS);
        }
    };

    // ---- buy handshake (maker side, background) ----

    private void scanTakeRequests() {
        if (crypto == null || identity == null || engine == null) return;
        if (takeScanner == null) {
            takeScanner = new CommsScanner(node, crypto, new PrefsMeta(prefs), SwapTake.ADDRESS, this::routeTakeRequest, (ok, n) -> {});
            for (String hh : incomingHashlocks()) engine.addIncomingHashlock(hh);   // re-arm persisted handshakes
        }
        takeScanner.scan(0);   // bounded depth-grow scan; block number only affects bookmarking
    }

    /** Background OTC negotiation receiver: drive the shared state machine so deals progress with the app closed. */
    private void scanOtc() {
        if (crypto == null || identity == null || engine == null || otc == null) return;
        if (myMinimaPk != null && wallet.ready()) {
            otc.setSelf(crypto, identity, myMinimaPk, wallet.address());
            double sell = 0, buy = 0;
            try { sell = Double.parseDouble(prefs.getString("otc_sell_size", "0")); } catch (Exception ignore) {}
            try { buy = Double.parseDouble(prefs.getString("otc_buy_size", "0")); } catch (Exception ignore) {}
            otc.setMyOffer(prefs.getBoolean("otc_enable", false), sell, buy);
        }
        if (otcScanner == null)
            otcScanner = new CommsScanner(node, crypto, new PrefsMeta(prefs), OtcMessage.ADDRESS, otc::route, (ok, n) -> {});
        otcScanner.scan(0);
        otc.expireStale(System.currentTimeMillis());
    }

    private boolean routeTakeRequest(String coinid, Opened opened, JSONObject coin) {
        try {
            JSONObject j = new JSONObject(new String(opened.plaintext, java.nio.charset.StandardCharsets.UTF_8));
            String to = j.optString("to", ""), from = j.optString("from", ""), hash = j.optString("hash", "");
            if (hash.isEmpty() || !identity.publicId().equals(to)) return false;
            if (opened.fromPublicId == null || !opened.fromPublicId.equals(from)) return false;
            java.util.Set<String> set = incomingHashlocks();
            boolean isNew = set.add(hash);
            if (isNew) prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply();
            engine.addIncomingHashlock(hash);
            if (isNew && !MainActivity.SWEEP_ACTIVE) engine.checkBuyNow(hash);   // act now — don't wait for the next 90s poll
            if (isNew) alert("Buy request received", "A buyer wants your MINIMA — finding their USDT lock, then locking.");
            return isNew;
        } catch (Exception e) { return false; }
    }

    private java.util.Set<String> incomingHashlocks() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String hh : prefs.getString("incoming_hashlocks", "").split(",")) if (!hh.isEmpty()) set.add(hh);
        return set;
    }

    /** Keep a published order live + its size current while the app is closed (~30 min, fresh sendable) —
     *  or NOW when the pegged oracle price moved past the reprice threshold / the feed just recovered.
     *  The interval runs off {@code last_publish_ok} which is stamped ONLY on a confirmed publish, so a
     *  FAILED attempt is retried on the next 90s tick instead of silently suppressed for 30 min — and the
     *  moment pairing returns after an outage the stale stamp forces an immediate reinstatement. */
    private void maybeAutoRepublish() {
        if (identity == null || myMinimaPk == null || !wallet.ready()) { SwapLog.skip("bgpub", "waiting: identity/wallet not ready"); return; }
        if (!prefs.getBoolean("auto_publish", false)) return;
        long okStamp = prefs.getLong("last_publish_ok", 0);
        boolean reprice = PriceOracle.shouldReprice(prefs, okStamp);
        // Belt throttle: while publishes "succeed" but never land on-chain (vault locked → pending),
        // hourly instead of every 30 min — keep trying, don't flood the node's pending list.
        long interval = orderMisses >= 2 ? 60 * 60_000 : MainActivity.REPUBLISH_INTERVAL_MS;
        if (!reprice && System.currentTimeMillis() - okStamp < interval) return;
        Order o = loadOrder();
        o.minimaPublicKey = myMinimaPk;
        o.ethAddress = wallet.address();
        try { o.usdtAvail = Double.parseDouble(prefs.getString("usdt_avail", "0")); } catch (Exception e) { o.usdtAvail = 0; }
        boolean anyEnabled = false;
        for (Order.Pair p : o.pairs.values()) if (p.enable) { anyEnabled = true; break; }
        if (!anyEnabled) return;
        if (!PublishGate.tryAcquire(PublishGate.ORDER)) { SwapLog.skip("bgpub", "gate busy"); return; }
        final boolean restored = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
        final int peg = PriceOracle.applyPeg(o, prefs);
        if (peg == PriceOracle.PEG_STALE) {   // NO usable price at all → don't publish (withdraw runs on the tick)
            PublishGate.release(PublishGate.ORDER);
            SwapLog.skip("bgpub", "peg stale — no usable price");
            return;
        }
        final boolean wide = peg == PriceOracle.PEG_WIDE;
        final double pegMid = PriceOracle.appliedMid();
        final long gen = PublishGate.gen();
        SwapLog.d("bg publish attempt reason=" + (reprice ? "reprice" : "interval"));
        updateFg("Republishing your market…");
        engine.ensureLadderCoins(o, !MainActivity.SWEEP_ACTIVE, () -> {
            // Re-check BEFORE the send: a user withdraw/edit or peg tombstone during the coin/balance reads
            // must not be out-freshened on-chain by this stale ladder (its ts is stamped at publish time).
            if (gen != PublishGate.gen()) {
                PublishGate.release(PublishGate.ORDER);
                SwapLog.d("bg publish superseded pre-send — dropped");
                updateFg(fgStateText());
                return;
            }
            SwapOrderBook.publishFresh(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                if (gen != PublishGate.gen()) {   // a user edit/withdraw or peg-tombstone superseded this attempt
                    PublishGate.release(PublishGate.ORDER);
                    SwapLog.d("bg publish superseded — result dropped");
                    updateFg(fgStateText());
                    return;
                }
                prefs.edit().putLong("last_publish_ok", System.currentTimeMillis()).apply();
                if (peg == PriceOracle.PEG_APPLIED || wide) PriceOracle.commitPeg(prefs, pegMid, wide);   // baseline moves only on a CONFIRMED publish
                engine.setMyOrder(o);
                PublishGate.release(PublishGate.ORDER);
                SwapLog.d("bg publish OK " + txpowid);
                updateFg(fgStateText());
                if (restored && peg == PriceOracle.PEG_APPLIED)
                    alert(PriceOracle.SOURCE + " feed recovered", "Your pegged order is live again.");
            }
            @Override public void onFailed(String message) {
                PublishGate.release(PublishGate.ORDER);
                SwapLog.w("bg publish FAIL: " + message);   // retried on the next 90s tick (ok-stamp untouched)
                updateFg(fgStateText());
            }
            });
        });
    }

    /** OTC availability parity: the offer ages out of the board (~1h) exactly like the order, but until now
     *  ONLY the foreground Activity republished it — an LP's OTC presence died ~1h after backgrounding. */
    private void maybeRepublishOtc() {
        if (identity == null || myMinimaPk == null || !wallet.ready()) return;
        if (!prefs.getBoolean("otc_auto", false)) return;
        long okStamp = prefs.getLong("otc_last_publish_ok", 0);
        long interval = otcMisses >= 2 ? 60 * 60_000 : MainActivity.REPUBLISH_INTERVAL_MS;
        if (System.currentTimeMillis() - okStamp < interval) return;
        if (!PublishGate.tryAcquire(PublishGate.OTC)) return;
        final long gen = PublishGate.gen();
        OtcOffer o = new OtcOffer();
        double sell = 0, buy = 0;
        try { sell = Double.parseDouble(prefs.getString("otc_sell_size", "0")); } catch (Exception ignore) {}
        try { buy = Double.parseDouble(prefs.getString("otc_buy_size", "0")); } catch (Exception ignore) {}
        o.sellSize = Math.max(0, sell); o.buySize = Math.max(0, buy); o.enable = true;
        o.minimaPublicKey = myMinimaPk; o.ethAddress = wallet.address(); o.commsPublicId = identity.publicId();
        OtcBook.publish(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                PublishGate.release(PublishGate.OTC);
                // Gen-checked so a user's new-sizes tap can't be over-stamped by this old-sizes keep-alive.
                if (gen != PublishGate.gen()) { SwapLog.d("bg OTC publish superseded — stamp skipped"); return; }
                prefs.edit().putLong("otc_last_publish_ok", System.currentTimeMillis()).apply();
                SwapLog.d("bg OTC publish OK " + txpowid);
            }
            @Override public void onFailed(String message) {
                PublishGate.release(PublishGate.OTC);
                SwapLog.w("bg OTC publish FAIL: " + message);
            }
        });
    }

    /** FUND-SAFETY, last resort (mirrors MainActivity): only when there is NO usable price AT ALL and the feed's
     *  been down past the window → tombstone + disarm. With a last-good mid the peg STAYS LIVE at a widened
     *  defensive spread (PEG_WIDE) so a sleeping phone keeps supplying liquidity. auto_publish stays ON. */
    private void maybePegWithdraw() {
        if (identity == null || myMinimaPk == null || !wallet.ready() || engine == null) return;
        final boolean withdrawn = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
        if (withdrawn && prefs.getBoolean(PriceOracle.P_TOMBSTONED, false)) return;   // tombstone confirmed — done
        if (PriceOracle.mid() > 0 && !PriceOracle.feedDownPastCeiling()) return;   // have a usable price & within the ceiling → stay live
        if (!withdrawn && !PriceOracle.feedDownPastLimit()) return;   // no price yet, but not down long enough
        final boolean first = !withdrawn;                // else: retrying a tombstone whose broadcast failed
        if (first) prefs.edit().putBoolean(PriceOracle.P_WITHDRAWN, true)
                .putBoolean(PriceOracle.P_TOMBSTONED, false).apply();   // withdrawn state FIRST — kill-safe
        PublishGate.bumpGen();            // any in-flight AUTO publish result is now stale — it must not re-arm the ladder
        engine.setMyOrder(new Order());   // disarm NOW — an empty order declines every take — even if the publish fails
        SwapLog.w("svc peg withdraw — tombstoning (feed unusable)");
        Order o = loadOrder();            // tombstone: the saved order with no liquidity (freshest-per-signer wins)
        for (Order.Pair p : o.pairs.values()) p.enable = false;
        o.minimaPublicKey = myMinimaPk;
        o.ethAddress = wallet.address();
        SwapOrderBook.publish(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {   // confirmed on the wire — stop retrying
                prefs.edit().putBoolean(PriceOracle.P_TOMBSTONED, true).apply();
                SwapLog.d("svc tombstone confirmed " + txpowid);
                updateFg(fgStateText());
            }
            @Override public void onFailed(String message) { SwapLog.w("svc tombstone publish FAIL: " + message); /* retried next tick */ }
        });
        if (first) alert(PriceOracle.SOURCE + " price feed lost",
                "Your pegged order was withdrawn for safety. It re-publishes automatically when the feed recovers.");
    }

    // ----- book-presence belt (heartbeat cadence, ~15 min) -----

    /**
     * The ok-stamp can lie in two ways: a vault-locked node answers {@code pending:true} (we log "OK") but
     * the coin never mines, and an accepted txn can be orphaned. Scan the book (bounded depth-72 — the
     * established node-safe pattern) and if my freshest signed coin is missing/stale while my stamp claims
     * a recent success, ZERO the stamp so the next tick republishes (either host — the stamp is shared, so
     * this runs even while the Activity is foreground-parked). Only ever FORCES a publish, never suppresses.
     *
     * The INVERSE also holds ("cancelled means cancelled"): while the market is user-cancelled or
     * peg-withdrawn, a stale in-flight publish may have out-freshened the tombstone on-chain (the gen
     * check drops its local result but can't unsend the coin) — if my freshest book coin still shows
     * liquidity, re-assert the tombstone so peers drop it, instead of leaving a zombie order for ~1h.
     */
    private void reconcileBookPresence() {
        if (!paired || identity == null) return;
        final String me = "0x" + Hex.to(identity.signPk);
        final long genAtScan = PublishGate.gen();
        SwapOrderBook.scan(node, ls, book -> {
            Order mine = book.get(me);
            // Read the flags INSIDE the callback (and gen-guard the re-assert): a user re-publish between
            // scan start and this reply must not get tombstoned by a stale view of auto_publish.
            boolean autoPub = prefs.getBoolean("auto_publish", false);
            boolean withdrawn = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
            if (autoPub && !withdrawn) checkPresence(mine == null ? 0 : mine.ts, "last_publish_ok", true);
            else if (mine != null && mine.hasLiquidity() && genAtScan == PublishGate.gen()) reassertTombstone();
        }, err -> {});
        if (prefs.getBoolean("otc_auto", false)) {
            OtcBook.scan(node, ls, book -> {
                OtcOffer mine = book.get(me);
                checkPresence(mine == null ? 0 : mine.ts, "otc_last_publish_ok", false);
            }, err -> {});
        }
    }

    private long lastReassertMs = 0;

    /** A live order of mine is on the book but the market is cancelled/withdrawn — publish a fresh tombstone. */
    private void reassertTombstone() {
        if (identity == null || myMinimaPk == null || !wallet.ready()) return;
        if (MainActivity.SWEEP_ACTIVE) return;   // a 1-nano send could still collide with a sweep leg's coin selection
        long now = System.currentTimeMillis();
        if (now - lastReassertMs < 60 * 60_000) return;   // hourly cap — a vault-locked node would otherwise queue one per heartbeat
        lastReassertMs = now;
        if (!PublishGate.tryAcquire(PublishGate.ORDER)) return;   // an in-flight publish settles first; retry next heartbeat
        SwapLog.w("zombie live order on book after cancel/withdraw — re-asserting tombstone");
        Order t = loadOrder();
        for (Order.Pair p : t.pairs.values()) p.enable = false;
        t.minimaPublicKey = myMinimaPk;
        t.ethAddress = wallet.address();
        SwapOrderBook.publish(node, ls, identity, t, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { PublishGate.release(PublishGate.ORDER); SwapLog.d("tombstone re-assert OK " + txpowid); }
            @Override public void onFailed(String message) { PublishGate.release(PublishGate.ORDER); SwapLog.w("tombstone re-assert FAIL: " + message); }
        });
    }

    private void checkPresence(long coinTs, String stampKey, boolean isOrder) {
        long now = System.currentTimeMillis();
        long stamp = prefs.getLong(stampKey, 0);
        if (stamp == 0 || now - stamp >= MainActivity.REPUBLISH_INTERVAL_MS) return;   // republish already due
        if (now - stamp < 5 * 60_000) return;   // a just-confirmed publish takes ~1 block to appear — not a miss
        boolean present = coinTs > 0 && now - coinTs < MainActivity.REPUBLISH_INTERVAL_MS + 10 * 60_000;
        if (present) { if (isOrder) orderMisses = 0; else otcMisses = 0; return; }
        int misses = isOrder ? ++orderMisses : ++otcMisses;
        prefs.edit().putLong(stampKey, 0).apply();   // force a republish on the next tick
        SwapLog.w((isOrder ? "order" : "otc") + " book-miss #" + misses + " — publish claimed OK but isn't on the book; forcing republish");
        if (misses == 2) alert("Publishes aren't landing on-chain",
                "Your market updates may be stuck in the node's Pending list — is the vault locked? Open Minima Core and check Pending.");
    }

    // ----- market-offline alert + live foreground-notification text -----

    /** Unpaired > 3 min with a market enabled → HIGH-priority alert, re-posted hourly until the node returns. */
    private void maybeOfflineAlert() {
        if (paired) return;
        if (!prefs.getBoolean("auto_publish", false) && !prefs.getBoolean("otc_auto", false)) return;
        long now = System.currentTimeMillis();
        if (unpairedSince == 0 || now - unpairedSince < OFFLINE_ALERT_AFTER_MS) return;
        if (lastOfflineAlertMs != 0 && now - lastOfflineAlertMs < OFFLINE_REALERT_MS) return;
        lastOfflineAlertMs = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        // A background service can't launch another app's Activity on Android 10+ — the notification tap
        // IS the legal cross-app launch path (user-initiated PendingIntent).
        Intent launch = getPackageManager().getLaunchIntentForPackage("org.minimarex.minimacore");
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 13,
                launch != null ? launch : new Intent(this, MainActivity.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        String body = "Minima Core isn't running — your published market will drop off the book. Tap to open it.";
        nm.notify(OFFLINE_ID, new NotificationCompat.Builder(this, CH_OFFLINE)
                .setContentTitle("Market offline")
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
        SwapLog.w("offline alert posted (unpaired " + ((now - unpairedSince) / 60_000) + " min)");
    }

    private void cancelOfflineAlert() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(OFFLINE_ID);
    }

    /** Re-render the persistent FGS notification only when its text actually changes. */
    private void updateFg(String text) {
        if (text == null || text.equals(fgText)) return;
        fgText = text;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(FG_ID, fgNotification(text));
    }

    /** What the persistent notification should say right now — the user's at-a-glance market health. */
    private String fgStateText() {
        if (!paired) return unpairedSince > 0 && System.currentTimeMillis() - unpairedSince > OFFLINE_ALERT_AFTER_MS
                ? "Market offline — waiting for Minima Core" : "Waiting for Minima Core…";
        if (prefs.getBoolean(PriceOracle.P_ENABLE, false) && prefs.getBoolean(PriceOracle.P_WITHDRAWN, false))
            return "Market withdrawn — waiting for the price feed";
        if (prefs.getBoolean("auto_publish", false) || prefs.getBoolean("otc_auto", false))
            return "Market live — keeping it published";
        return "Watching swaps";
    }

    // ----- engine notifier: OS notifications only (no UI here); SwapDb carries state to the Activity -----

    private final SwapEngine.Notifier notifier = new SwapEngine.Notifier() {
        @Override public void notify(String title, String body) { h.post(() -> alert(title, body)); }
        @Override public void onSwapsChanged() { /* no UI in the service */ }
    };

    private void alert(String title, String body) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(alertId++, new NotificationCompat.Builder(this, CH_ALERT)
                .setContentTitle(title).setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true).build());
    }

    // ----- helpers -----

    /** Load my published rates from prefs (drives the responder match-guard for background auto-fills). */
    private Order loadOrder() {
        return Order.fromConfigJson(prefs.getString("order_config", ""));   // shared loader — keeps the ladder intact on background republish
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CH_FG, "Swap watcher", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Swaps", NotificationManager.IMPORTANCE_DEFAULT));
            nm.createNotificationChannel(new NotificationChannel(CH_OFFLINE, "Market offline", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    /** Build the persistent FGS notification with the given status text (tap → open the app). */
    private Notification fgNotification(String text) {
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 14,
                new Intent(this, MainActivity.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("minimaSwap")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private boolean startForegroundCompat() {
        fgText = "Waiting for Minima Core…";
        Notification n = fgNotification(fgText);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // specialUse is uncapped, unlike dataSync's ~6h/day Android-14 budget that was killing us overnight.
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;   // ForegroundServiceStartNotAllowedException etc. — don't crash; relaunch retries
        }
    }

    /** Android 14+: the OS tells a time-limited FGS to stop. Stop gracefully instead of crashing.
     *  (specialUse isn't time-limited, but this is belt-and-braces for the dataSync fallback path.) */
    @Override public void onTimeout(int startId) { stopGracefully(); }
    @Override public void onTimeout(int startId, int fgsType) { stopGracefully(); }
    private void stopGracefully() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }
}
