package org.minimarex.minimaswap;

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
import org.minimarex.comms.CommsIdentity;
import org.minimarex.comms.CommsScanner;
import org.minimarex.comms.CommsTransport;
import org.minimarex.comms.CryptoProvider;
import org.minimarex.comms.Hex;
import org.minimarex.comms.LocalEcCryptoProvider;
import org.minimarex.comms.NodeApi;
import org.minimarex.comms.Opened;
import org.minimarex.comms.Sodium;
import org.minimarex.minimaswap.eth.EthNet;
import org.minimarex.minimaswap.eth.EthRpc;
import org.minimarex.minimaswap.eth.EthWallet;
import org.minimarex.minimaswap.swap.MinimaHtlc;
import org.minimarex.minimaswap.swap.Order;
import org.minimarex.minimaswap.swap.SwapDb;
import org.minimarex.minimaswap.swap.SwapEngine;
import org.minimarex.minimaswap.swap.SwapOrderBook;
import org.minimarex.minimaswap.swap.SwapTake;

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
    private static final int FG_ID = 4101;
    private static final long INTERVAL_MS = 90_000;
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
    private String myMinimaPk;
    private boolean booted = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForegroundCompat();

        prefs = getSharedPreferences("minimaswap", MODE_PRIVATE);
        ls = Sodium.get();
        EthNet net = EthNet.MAINNET;
        EthRpc rpc = new EthRpc(prefs.getString("rpc_mainnet", net.defaultRpc));
        db = new SwapDb(this);

        node = new NodeApi(this, this::onPaired);
        minima = new MinimaHtlc(node);
        engine = new SwapEngine(node, minima, db, wallet, h, notifier);
        engine.setNetwork(rpc, net);
        engine.setMyOrder(loadOrder());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    /** The user swiped the app off recents — keep watching: reschedule the worker + AlarmManager relaunch. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        try { SwapWorker.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.app.PendingIntent pi = android.app.PendingIntent.getForegroundService(
                    getApplicationContext(), 11, new Intent(getApplicationContext(), SwapService.class),
                    android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (engine != null) engine.shutdown();
        if (node != null) node.onDestroy();
    }

    // ----- bootstrap once the node enables us -----

    private void onPaired(boolean enabled) {
        if (!enabled || booted) return;
        booted = true;

        wallet.deriveFromNode(node, h, new EthWallet.Cb() {
            @Override public void ok(String address) { /* engine.ready() will pick it up */ }
            @Override public void err(String msg) { /* retry next service launch */ }
        });

        String savedAddr = prefs.getString("swap_addr", "");
        String savedPk = prefs.getString("swap_pk", "");
        minima.setup(savedAddr, savedPk, new MinimaHtlc.SetupCb() {
            @Override public void ok(String a, String pk) {
                myMinimaPk = pk;
                prefs.edit().putString("swap_addr", a).putString("swap_pk", pk).apply();
                engine.setMyMinimaPk(pk);
                minima.loadMyKeys(new MinimaHtlc.KeysCb() {
                    @Override public void ok(java.util.Set<String> keys) { engine.setMyPubkeys(keys); }
                    @Override public void err(String m) {}
                });
            }
            @Override public void err(String m) {}
        });

        deriveIdentity();   // so we can sign order-book republishes in the background

        h.removeCallbacks(tick);
        h.post(tick);
    }

    /** Derive the comms signing identity from the node seed (only needed to republish the maker's order). */
    private void deriveIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                final String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) return;
                new Thread(() -> {
                    try {
                        byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                        h.post(() -> { identity = id; crypto = new LocalEcCryptoProvider(ls, id); });
                    } catch (Exception ignore) {}
                }).start();
            }
            @Override public void onError(String m) {}
        });
    }

    /** 90s poll loop — stands down while the Activity is foreground (it polls then), so we never double-act. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!MainActivity.FOREGROUND && engine != null) { engine.poll(); maybeAutoRepublish(); scanTakeRequests(); }
            h.postDelayed(this, INTERVAL_MS);
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
            if (isNew) alert("Buy request received", "A buyer wants your MINIMA — finding their USDT lock, then locking.");
            return isNew;
        } catch (Exception e) { return false; }
    }

    private java.util.Set<String> incomingHashlocks() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String hh : prefs.getString("incoming_hashlocks", "").split(",")) if (!hh.isEmpty()) set.add(hh);
        return set;
    }

    /** Keep a published order live + its size current while the app is closed (~30 min, fresh sendable). */
    private void maybeAutoRepublish() {
        if (identity == null || myMinimaPk == null || !wallet.ready()) return;
        if (!prefs.getBoolean("auto_publish", false)) return;
        if (System.currentTimeMillis() - prefs.getLong("last_publish", 0) < MainActivity.REPUBLISH_INTERVAL_MS) return;
        Order o = loadOrder();
        o.minimaPublicKey = myMinimaPk;
        o.ethAddress = wallet.address();
        try { o.usdtAvail = Double.parseDouble(prefs.getString("usdt_avail", "0")); } catch (Exception e) { o.usdtAvail = 0; }
        boolean anyEnabled = false;
        for (Order.Pair p : o.pairs.values()) if (p.enable) { anyEnabled = true; break; }
        if (!anyEnabled) return;
        prefs.edit().putLong("last_publish", System.currentTimeMillis()).apply();
        SwapOrderBook.publishFresh(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { engine.setMyOrder(o); }
            @Override public void onFailed(String message) {}
        });
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
        Order o = new Order();
        JSONObject cfg = null;
        try { String raw = prefs.getString("order_config", ""); if (!raw.isEmpty()) cfg = new JSONObject(raw); }
        catch (Exception ignore) {}
        Order.Pair p = new Order.Pair(false, 1, 1, 1);
        if (cfg != null) {
            JSONObject c = cfg.optJSONObject("USDT");
            if (c != null) { p.enable = c.optBoolean("en", false); p.buy = c.optDouble("buy", 1);
                p.sell = c.optDouble("sell", 1); p.min = c.optDouble("min", 1); }
        }
        o.pairs.put("USDT", p);
        return o;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CH_FG, "Swap watcher", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Swaps", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("minimaSwap")
                .setContentText("Watching your swaps")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_ID, n);
        }
    }
}
