package org.minimarex.minimaswap;

import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.comms.CommsIdentity;
import org.minimarex.comms.CommsScanner;
import org.minimarex.comms.CommsTransport;
import org.minimarex.comms.CryptoProvider;
import org.minimarex.comms.Opened;
import org.minimarex.comms.Hex;
import org.minimarex.comms.LocalEcCryptoProvider;
import org.minimarex.comms.NodeApi;
import org.minimarex.comms.QrUtil;
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * minimaSwap — native cross-chain HTLC atomic swaps (Minima ↔ ERC20) on minimaCore.
 *
 * Home = wallet status (Minima + seed-derived ETH), a live signed on-chain order book, a guided take-order
 * flow, and resumable active-swap cards driven by {@link SwapEngine} (the trustless two-leg state machine)
 * and a background watcher that polls both chains, harvests revealed secrets and fires notifications.
 */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "minimaswap";
    private static final String PREFS = "minimaswap";
    private static final String[] PAIR_TOKENS = Order.PAIR_TOKENS;   // single source of truth in Order
    private static final long WATCH_INTERVAL_MS = 90_000;
    private static final long SWEEP_LEG_TIMEOUT_MS = 300_000;    // start-phase watchdog: lock never broadcasts (covers approve+lock)
    private static final long SWEEP_CONFIRM_WATCHDOG_MS = 300_000; // SELL confirm-phase watchdog: confirmMyLock hangs (> the try-budget below)
    private static final long SWEEP_CONFIRM_INTERVAL_MS = 8_000; // SELL poll cadence while waiting for a leg's MINIMA lock to hit chain
    private static final int  SWEEP_CONFIRM_TRIES = 30;         // ~4 min budget (SELL-only) — a coinage:1 lock lands in ~1 block
    static final long REPUBLISH_INTERVAL_MS = 30 * 60_000;   // keep a published order live + fresh (bridge uses 30m)
    private static final long TERMINAL_GRACE_MS = 10 * 60_000;   // Activity-tab history: finished swaps auto-hide after this
    private static final long SWAP_PANEL_GRACE_MS = 30_000;       // Swap-tab panel: a just-finished swap shows only this long, then clears

    private LazySodium ls;
    private NodeApi node;
    private boolean paired = false;
    private SharedPreferences prefs;

    private final EthWallet wallet = new EthWallet();
    private EthNet net = EthNet.MAINNET;     // mainnet only
    private EthRpc rpc;

    // identity + swap
    private CommsIdentity identity;
    private CryptoProvider crypto;
    private MinimaHtlc minima;
    private String myMinimaPk;
    private SwapDb db;
    private SwapEngine engine;
    private final LinkedHashMap<String, Order> orderBook = new LinkedHashMap<>();
    private String orderStatus = null;
    private CommsScanner takeScanner;   // maker side: receives buyers' hashlock handshakes
    private int historyTab = 0;         // Activity sub-tab: 0 = my swaps, 1 = market
    // ---- tabs ----
    private static final int TAB_SWAP = 0, TAB_WALLET = 1, TAB_ACTIVITY = 2, TAB_MARKET = 3;
    private static final String[] TAB_LABELS = {"Swap", "Wallet", "Activity", "Market"};
    private int currentTab = TAB_SWAP;
    private final TextView[] tabPills = new TextView[4];
    private boolean swapSell = true;    // Swap tab direction: true = Sell MINIMA, false = Buy MINIMA
    private String swapAmount = "";     // Swap tab "you send" amount — persists across tree rebuilds
    private boolean swapInputFocused = false;   // suppress background render() while typing the swap amount
    private boolean swapSyncing = false;        // guard so the send/receive fields don't loop while syncing
    private SweepPlan sweepRun = null;          // non-null while a multi-leg market sweep is executing
    private int sweepIdx = 0, sweepOk = 0;      // current leg index / legs successfully locked
    private Runnable sweepWatchdog = null;      // aborts a sweep whose current leg never calls back
    private static final int USDT_TEAL = 0xFF26A69A;
    private static final int STG_PENDING = 0, STG_ACTIVE = 1, STG_DONE = 2, STG_WARN = 3;
    private static final int STAGE_WARN_COLOR = 0xFFE6A23C;

    // wallet state shown on the home screen
    private String minimaBal = "…";        // sendable (tradeable)
    private String minimaConfirmed = "—", minimaUnconfirmed = "—", minimaCoins = "—";
    private long lastMinimaUpdate = 0;
    private int chainBlock = 0;
    private String ethAddr = null;
    private String ethErr = null;
    private String ethBal = "…";
    private final LinkedHashMap<String, String> tokenBals = new LinkedHashMap<>();

    // Balance-pulse feedback: bounce the headline balances when a swap NEWLY completes (incl. while backgrounded).
    private TextView minimaBalView, ethBalView;
    private final List<TextView> tokenBalViews = new ArrayList<>();
    private boolean pulseMinimaPending = false, pulseEthPending = false;
    private int lastCompleteCount = -1;                 // -1 until first read from prefs
    private static final String TAG_BAL = "balBig";     // marks the big balance TextView in walletCard/kv

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger notifId = new AtomicInteger(1000);

    private LinearLayout root;
    private ScrollView scroller;
    private LinearLayout tabBar;
    private final ImageView[] tabIcons = new ImageView[4];
    private final LinearLayout[] tabCells = new LinearLayout[4];
    private TextView pairingBanner;
    private boolean animateTab = false;   // fade in ONLY on a tab switch, never on a background refresh
    private boolean watching = false;
    private boolean modalOpen = false;   // suppress background render() while a dialog (with inputs) is open
    private boolean serviceStarted = false;
    /** True while the Activity is resumed — the SwapService stands down then so only one polls. */
    public static volatile boolean FOREGROUND = false;
    public static volatile boolean SWEEP_ACTIVE = false;   // a market sweep owns the wire — SwapService must also stand down

    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            if (sweepRun == null || !sweepRun.sell) {  // stand down only mid-SELL-sweep — a poll republish/claim
                if (engine != null) engine.poll();     // could re-select a sell leg's MINIMA coins. A BUY sweep is
                maybeAutoRepublish();                  // nonce-serialized, so the poller may run alongside it.
            }
            refreshBalances(false);                 // keep balances current without a restart (read-only, always safe)
            scanTakeRequests();                      // receive buyers' hashlock handshakes (scan/add only, no tx)
            ui.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Design.load(this);   // persisted theme + bundled Inter/JetBrains Mono, before any view is built
        ls = Sodium.get();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        net = EthNet.MAINNET;
        rpc = new EthRpc(rpcUrl(net));
        db = new SwapDb(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        tabBar = buildTabBar();
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(tabBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        applyChrome();
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        render();
        if (!prefs.getBoolean("seen_welcome", false)) ui.post(this::showWelcome);
        node = new NodeApi(this, this::onPaired);
        minima = new MinimaHtlc(node);
        engine = new SwapEngine(node, minima, db, wallet, ui, notifier);
        engine.setNetwork(rpc, net);
        engine.setMyOrder(loadOrder());
    }

    @Override public void onBackPressed() {
        if (currentTab != TAB_SWAP) { setTab(TAB_SWAP); return; }   // any non-default tab → back to Swap
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;     // Activity polls while visible; SwapService stands down
        startWatcher();
        checkForNewCompletion();   // a swap that completed while we were away → refresh + pulse on return
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;    // hand off to the background SwapService
        stopWatcher();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // Release any in-flight sweep BEFORE tearing down: clear the static SWEEP_ACTIVE (else SwapService would
        // stand down forever and never drive the locked legs), and drop every queued confirm-poll callback so
        // none fires against the shut-down io pool. The background Service resumes the in-flight swaps from the DB.
        SWEEP_ACTIVE = false; sweepRun = null; sweepWatchdog = null;
        ui.removeCallbacksAndMessages(null);
        stopWatcher();
        if (engine != null) engine.shutdown();
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    private void startWatcher() {
        if (watching || engine == null) return;
        watching = true;
        ui.post(watchTick);
    }
    private void stopWatcher() {
        watching = false;
        ui.removeCallbacks(watchTick);
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** Re-colour the persistent chrome (root, tab bar, pairing banner, status bar) for the current theme.
     *  Called once in onCreate and again on a theme toggle — no Activity recreate, so live swaps survive. */
    private void applyChrome() {
        root.setBackgroundColor(Design.BG());
        if (tabBar != null) tabBar.setBackgroundColor(Design.SURFACE());
        if (pairingBanner != null) {
            pairingBanner.setBackgroundColor(Design.ACCENT());
            pairingBanner.setTextColor(Design.ON_ACCENT());
        }
        updateTabBarSelection();
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(!Design.isDark());
    }

    /** Flip Onyx ↔ Daylight and restyle live (re-render, never recreate). */
    private void toggleTheme() {
        Design.toggle(this);
        applyChrome();
        toast(Design.isDark() ? "Dark theme" : "Light theme");
        render();
    }

    /** AlertDialog builder whose chrome follows the in-app theme (dark/light). */
    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, Design.dialogTheme()); }

    // ---- pairing + identity ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            fetchMinimaBalance();
            if (!wallet.ready()) {
                wallet.deriveFromNode(node, ui, new EthWallet.Cb() {
                    @Override public void ok(String address) { ethAddr = address; ethErr = null; render(); fetchEthBalances(true); maybeStartEngine(); }
                    @Override public void err(String msg) { ethErr = msg; render(); }
                });
            } else {
                fetchEthBalances(true);
            }
            if (identity == null) setupIdentity();
            if (!minima.ready()) {
                // Reuse a persisted identity — getaddress rotates through 64 keys, so a fresh one each
                // session would break discovery/resume (the maker's published key must stay constant).
                String savedAddr = prefs.getString("swap_addr", "");
                String savedPk = prefs.getString("swap_pk", "");
                minima.setup(savedAddr, savedPk, new MinimaHtlc.SetupCb() {
                    @Override public void ok(String a, String pk) {
                        myMinimaPk = pk;
                        prefs.edit().putString("swap_addr", a).putString("swap_pk", pk).apply();
                        engine.setMyMinimaPk(pk);
                        minima.loadMyKeys(new MinimaHtlc.KeysCb() {
                            @Override public void ok(java.util.Set<String> keys) { engine.setMyPubkeys(keys); }
                            @Override public void err(String m) { /* refund still works for the publish key */ }
                        });
                        maybeStartEngine(); render();
                    }
                    @Override public void err(String m) { /* surfaced lazily */ }
                });
            }
            scanOrderBook();
            startWatcher();
        }
        render();
    }

    /** Once both the ETH wallet and Minima identity are ready, run a first poll to resume any swaps. */
    private void maybeStartEngine() {
        if (paired && wallet.ready() && minima.ready() && engine != null) {
            loadIncoming();        // re-arm any pending buy handshakes from a prior session
            engine.poll();
            startSwapService();
            scanTakeRequests();
            render();
        }
    }

    /** Start the background watcher so swaps progress with the app closed (once, when ready). */
    private void startSwapService() {
        if (serviceStarted) return;
        serviceStarted = true;
        try { androidx.core.content.ContextCompat.startForegroundService(this, new android.content.Intent(this, SwapService.class)); }
        catch (Exception ignored) {}
        try { SwapWorker.schedule(this); } catch (Exception ignored) {}
        requestBatteryExemption();
    }

    /** Ask once to be exempt from battery optimisation so the watcher keeps running while the app is closed. */
    private void requestBatteryExemption() {
        try {
            if (prefs.getBoolean("asked_batt", false)) return;
            prefs.edit().putBoolean("asked_batt", true).apply();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
    }

    private void setupIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (!ikm.isEmpty()) deriveIdentity(ikm);
            }
            @Override public void onError(String m) { /* not enabled yet, etc. */ }
        });
    }

    private void deriveIdentity(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                ui.post(() -> { identity = id; crypto = new LocalEcCryptoProvider(ls, id); render(); scanOrderBook(); });
            } catch (Exception e) {
                ui.post(() -> toast("Identity error: " + e.getMessage()));
            }
        });
    }

    // ---- balances ----

    private void fetchMinimaBalance() {
        node.cmd("balance tokenid:0x00", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONObject t = null;
                if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) t = ((JSONArray) resp).optJSONObject(0);
                else if (resp instanceof JSONObject) t = (JSONObject) resp;
                if (t != null) {
                    minimaBal = t.optString("sendable", "0");          // tradeable: simple-address coins only
                    minimaConfirmed = t.optString("confirmed", "—");   // all your confirmed coins (incl. contract-locked)
                    minimaUnconfirmed = t.optString("unconfirmed", "—");
                    minimaCoins = t.optString("coins", "—");
                }
                lastMinimaUpdate = System.currentTimeMillis();
                render();   // fires any pending balance pulse via firePendingPulses() when the Wallet tab is showing
            }
            @Override public void onError(String m) { minimaBal = "— (node didn't answer)"; lastMinimaUpdate = System.currentTimeMillis(); render(); }
        });
    }

    /** Diagnostic: list the coins the node counts as relevant to this wallet, by address — so we can see
     *  exactly what inflates the balance (e.g. shared order-book / HTLC / casino coins vs your simple coins). */
    private void minimaCoinDump() {
        toast("Reading coins…");
        node.cmd("coins relevant:true tokenid:0x00 simplestate:false", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                StringBuilder sb = new StringBuilder();
                sb.append("sendable ").append(Util.tidyAmount(minimaBal))
                  .append("   confirmed ").append(Util.tidyAmount(minimaConfirmed)).append("\n")
                  .append(coins.length()).append(" relevant coins:\n\n");
                java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    if (c == null) continue;
                    String amt = c.optString("amount", "0");
                    String addr = c.optString("address", "");
                    try { sum = sum.add(new java.math.BigDecimal(amt)); } catch (Exception ignore) {}
                    String mx = c.optString("miniaddress", "");
                    String tag = "";
                    if (MinimaHtlc.HTLC_ADDRESS.equalsIgnoreCase(mx)) tag = " (swap HTLC)";
                    else if (SwapOrderBook.ADDRESS.equalsIgnoreCase(addr)) tag = " (order book)";
                    sb.append(Util.tidyAmount(amt)).append("   ")
                      .append(addr.length() > 16 ? addr.substring(0, 16) + "…" : addr).append(tag).append("\n");
                }
                sb.append("\nsum of relevant coins ").append(Util.tidyAmount(sum.toPlainString()));
                showText("Coin breakdown", sb.toString());
            }
            @Override public void onError(String m) { showText("Coin breakdown", "Failed: " + m); }
        });
    }

    private void showText(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView t = new TextView(this);
        t.setText(body); t.setTextColor(Design.TEXT()); t.setTextSize(12.5f);
        t.setTypeface(Design.mono()); t.setTextIsSelectable(true);
        box.addView(t);
        modalOpen = true;
        dialog().setTitle(title).setView(wrapScroll(box))
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); }).show();
    }

    /** Full Minima balance breakdown so the displayed numbers are never a black box. */
    private String minimaBreakdown() {
        String locked = "—";
        try {
            locked = Util.tidyAmount(new java.math.BigDecimal(minimaConfirmed).subtract(new java.math.BigDecimal(minimaBal)).toPlainString());
        } catch (Exception ignore) {}
        String ago = lastMinimaUpdate == 0 ? "never" : ((System.currentTimeMillis() - lastMinimaUpdate) / 1000) + "s ago";
        return "confirmed " + Util.tidyAmount(minimaConfirmed) + "  ·  locked ≈ " + locked
                + "  ·  unconfirmed " + Util.tidyAmount(minimaUnconfirmed) + "  ·  " + minimaCoins + " coins  ·  updated " + ago;
    }

    /** Refresh both wallet balances. showLoading=false → silent (periodic) update, no "…" flicker. */
    private void refreshBalances(boolean showLoading) {
        if (!paired) return;
        fetchMinimaBalance();
        fetchBlock();
        if (wallet.ready()) fetchEthBalances(showLoading);
    }

    /** If a swap has NEWLY reached COMPLETE since we last looked (foreground or while backgrounded), arm a
     *  balance pulse and force a visible reload. The count is persisted so a completion that finished while
     *  the app was gone still pulses on return, and pre-existing completions never false-trigger.
     *  Returns true if it kicked off a reload (so callers can skip a redundant silent refresh). */
    private boolean checkForNewCompletion() {
        if (db == null) return false;
        int complete = 0;
        for (SwapDb.Swap s : db.allSwaps()) if (SwapDb.ST_COMPLETE.equals(s.status)) complete++;
        if (lastCompleteCount < 0) {                                       // baseline on first run, then persist it
            lastCompleteCount = prefs.getInt("swap_complete_count", complete);
            prefs.edit().putInt("swap_complete_count", lastCompleteCount).apply();
        }
        if (complete > lastCompleteCount) {
            pulseMinimaPending = true;
            pulseEthPending = true;
            lastCompleteCount = complete;
            prefs.edit().putInt("swap_complete_count", complete).apply();
            refreshBalances(true);
            return true;
        }
        if (complete != lastCompleteCount) {   // terminal count can't really shrink; resync quietly if it did
            lastCompleteCount = complete;
            prefs.edit().putInt("swap_complete_count", complete).apply();
        }
        return false;
    }

    private void fetchBlock() {
        if (!paired || minima == null) return;
        minima.currentBlock(new MinimaHtlc.BlockCb() {
            @Override public void ok(int block) { chainBlock = block; render(); }
            @Override public void err(String m) {}
        });
    }

    private void fetchEthBalances(boolean showLoading) {
        if (!wallet.ready()) return;
        final EthRpc r = rpc;
        final EthNet n = net;
        if (showLoading) { ethBal = "…"; render(); }
        io.execute(() -> {
            String wei, err = null;
            LinkedHashMap<String, String> toks = new LinkedHashMap<>();
            try {
                BigInteger w = wallet.ethBalanceWei(r);
                wei = EthWallet.format(w, 18, 6) + " ETH";
                for (EthNet.Token tk : n.tokens) {
                    try {
                        BigInteger raw = wallet.erc20BalanceRaw(r, tk.address);
                        toks.put(tk.symbol, EthWallet.format(raw, tk.decimals, 6));
                    } catch (Exception e) { toks.put(tk.symbol, "—"); }
                }
            } catch (Exception e) { wei = "—"; err = e.getMessage(); }
            final String fwei = wei, ferr = err;
            ui.post(() -> {
                if (n != net) return;
                ethBal = fwei;
                tokenBals.clear(); tokenBals.putAll(toks);
                String usdt = toks.get("USDT");
                if (usdt != null) prefs.edit().putString("usdt_avail", usdt).apply();   // for the background republish
                if (ferr != null) ethErr = ferr;
                render();   // fires any pending balance pulse via firePendingPulses() when the Wallet tab is showing
            });
        });
    }

    private String rpcUrl(EthNet n) {
        return prefs.getString("rpc_mainnet", n.defaultRpc);
    }

    // ---- order book ----

    private void scanOrderBook() {
        if (!paired) return;
        SwapOrderBook.scan(node, ls,
                book -> { orderBook.clear(); orderBook.putAll(book); render(); },
                err -> { orderStatus = "Book scan: " + err; render(); });
        scanTakeRequests();
    }

    // ---- buy handshake (maker side): receive buyers' hashlocks so we can getContract their USDT lock ----

    private void scanTakeRequests() {
        if (!paired || crypto == null || identity == null || engine == null) return;
        if (takeScanner == null) {
            takeScanner = new CommsScanner(node, crypto, new PrefsMeta(prefs), SwapTake.ADDRESS, this::routeTakeRequest, (ok, n) -> {});
        }
        takeScanner.scan(chainBlock);
    }

    private boolean routeTakeRequest(String coinid, Opened opened, JSONObject coin) {
        try {
            JSONObject j = new JSONObject(new String(opened.plaintext, StandardCharsets.UTF_8));
            String to = j.optString("to", ""), from = j.optString("from", ""), hash = j.optString("hash", "");
            if (hash.isEmpty() || !identity.publicId().equals(to)) return false;       // not for me
            if (opened.fromPublicId == null || !opened.fromPublicId.equals(from)) return false;  // sender mismatch
            return addIncoming(hash);
        } catch (Exception e) { return false; }
    }

    /** Record a buyer's hashlock (engine discovers their USDT lock via getContract) + persist for resume. */
    private boolean addIncoming(String hash) {
        java.util.Set<String> set = incomingHashlocks();
        boolean isNew = set.add(hash);
        if (isNew) prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply();
        if (engine != null) engine.addIncomingHashlock(hash);
        if (isNew) {   // make the handshake visible — the buyer wants to know it landed
            postNotification("Buy request received", "A buyer wants your MINIMA — finding their USDT lock, then locking.");
            orderStatus = "↧ Buy request received — locking MINIMA for a buyer…"; render();
        }
        return isNew;
    }

    private java.util.Set<String> incomingHashlocks() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String h : prefs.getString("incoming_hashlocks", "").split(",")) if (!h.isEmpty()) set.add(h);
        return set;
    }

    /** Re-arm the engine with persisted handshakes after a restart, pruning ones that have fully resolved. */
    private void loadIncoming() {
        if (engine == null || db == null) return;
        java.util.Set<String> set = incomingHashlocks();
        boolean changed = false;
        for (java.util.Iterator<String> it = set.iterator(); it.hasNext(); ) {
            String h = it.next();
            SwapDb.Swap s = db.getSwap(h);
            if (s != null && isTerminal(s)) { it.remove(); changed = true; }   // handshake done — stop persisting it
            else engine.addIncomingHashlock(h);
        }
        if (changed) prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply();
    }

    /** Build the base order (pairs + my identities + USDT avail); returns null if no pair is enabled. */
    private Order baseOrder() {
        Order o = loadOrder();
        o.minimaPublicKey = myMinimaPk;
        o.ethAddress = wallet.address();
        o.usdtAvail = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0);
        for (Order.Pair p : o.pairs.values()) if (p.enable) return o;
        return null;
    }

    private void publishOrder() {
        if (identity == null || myMinimaPk == null) { toast("Still connecting to your node…"); return; }
        if (!wallet.ready()) { toast("ETH wallet not ready yet"); return; }
        final Order o = baseOrder();
        if (o == null) { toast("Enable at least one pair in your order first"); return; }
        prefs.edit().putBoolean("auto_publish", true).apply();   // opt in to keep it live + fresh
        orderStatus = "Publishing your order…";
        render();
        // publishFresh re-reads the live SENDABLE balance so the advertised size is never a stale snapshot.
        SwapOrderBook.publishFresh(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                prefs.edit().putLong("last_publish", System.currentTimeMillis()).apply();
                engine.setMyOrder(o);
                orderStatus = "✓ Order published — auto-refreshes every 30 min";
                render(); ui.postDelayed(MainActivity.this::scanOrderBook, 2000);
            }
            @Override public void onFailed(String message) { orderStatus = "Publish failed: " + message; render(); }
        });
    }

    /** Keep a published order live + its advertised size current: re-publish ~every 30 min (fresh sendable). */
    private void maybeAutoRepublish() {
        if (identity == null || myMinimaPk == null || !wallet.ready()) return;
        if (!prefs.getBoolean("auto_publish", false)) return;
        if (System.currentTimeMillis() - prefs.getLong("last_publish", 0) < REPUBLISH_INTERVAL_MS) return;
        final Order o = baseOrder();
        if (o == null) return;   // all pairs disabled → nothing to keep live
        prefs.edit().putLong("last_publish", System.currentTimeMillis()).apply();   // stamp now to avoid re-entry
        SwapOrderBook.publishFresh(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { engine.setMyOrder(o); }
            @Override public void onFailed(String message) { /* retried next interval */ }
        });
    }

    // ---- order config (persisted; drives both publish AND the responder match guard) ----

    private Order loadOrder() {
        return Order.fromConfigJson(prefs.getString("order_config", ""));   // shared ladder-aware loader
    }

    private void saveOrder(Order o) {
        prefs.edit().putString("order_config", Order.toConfigJson(o)).apply();
        if (engine != null) engine.setMyOrder(o);   // arm the responder match guard immediately
    }

    private void editOrderDialog() {
        final Order o = loadOrder();
        final String sym = PAIR_TOKENS[0];   // single pair today
        final Order.Pair p = o.pairs.get(sym);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText("Your depth ladder for MINIMA / " + sym + "  (price = USDT per MINIMA):\n"
                + "•  ASKS — where YOU SELL MINIMA (higher).   BIDS — where YOU BUY (lower).\n"
                + "•  Each level's amount is a per-take cap. Leave rows blank to skip them.\n"
                + "•  Tip: make your best level your largest — old-app takers only see your best price.");
        hint.setTextColor(Design.DIM()); hint.setTextSize(12f); hint.setTypeface(Design.sans()); hint.setLineSpacing(dp(2), 1f); hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);

        final SwitchCompat sw = new SwitchCompat(this);
        sw.setText("Enabled"); sw.setTextColor(Design.DIM()); sw.setChecked(p.enable);
        box.addView(sw);

        // quick-generate
        box.addView(fieldLabel("QUICK GENERATE (mid · step % · size)"));
        LinearLayout gen = new LinearLayout(this); gen.setOrientation(LinearLayout.HORIZONTAL); gen.setGravity(Gravity.CENTER_VERTICAL); gen.setPadding(0, dp(4), 0, dp(2));
        final EditText midE = genField("mid"), stepE = genField("step %"), sizeE = genField("size");
        LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); g1.rightMargin = dp(6);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); g2.rightMargin = dp(6);
        LinearLayout.LayoutParams g3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gen.addView(midE, g1); gen.addView(stepE, g2); gen.addView(sizeE, g3);
        box.addView(gen);
        TextView genBtn = Design.pill(this, "Generate 6 + 6", Design.SURFACE2(), Design.TEXT());
        LinearLayout.LayoutParams gblp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gblp.topMargin = dp(6); genBtn.setLayoutParams(gblp);
        box.addView(genBtn);

        // Live balances — used to seed a pre-ladder (0.8.x) order's synthetic size so a no-op Save keeps it live.
        final double liveMinima = parseD(minimaBal, 0);
        final double liveUsdt = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0);

        // ASKS (maker sells MINIMA, higher) — laid out ORDER-BOOK style: highest (A6) at the top, best/lowest
        // ask A1 at the BOTTOM adjacent to the bids, so the spread sits in the middle. askRows[] stays indexed
        // A1..A6 (A1 = best); only the visual insertion order is reversed.
        TextView ah = new TextView(this); ah.setText("ASKS — you SELL MINIMA (higher price)");
        ah.setTextColor(Design.RED()); ah.setTextSize(12.5f); ah.setTypeface(Design.sansBold()); ah.setLetterSpacing(0.03f); ah.setPadding(0, dp(12), 0, dp(2));
        box.addView(ah);
        final EditText[][] askRows = new EditText[Order.MAX_LEVELS][];
        for (int i = Order.MAX_LEVELS - 1; i >= 0; i--) {   // A6 first (top) … A1 last (bottom, by the spread)
            boolean legacy0 = i == 0 && p.asks.isEmpty() && p.enable && p.buy > 0;   // seed row from the live single-price ask
            double px = i < p.asks.size() ? p.asks.get(i).price : (legacy0 ? p.buy : 0);
            double am = i < p.asks.size() ? p.asks.get(i).amount : (legacy0 ? liveMinima : 0);
            askRows[i] = numRow2(box, "A" + (i + 1), px, am, Design.RED());
        }

        // BIDS (maker buys MINIMA, lower)
        TextView bh = new TextView(this); bh.setText("BIDS — you BUY MINIMA (lower price)");
        bh.setTextColor(Design.IN()); bh.setTextSize(12.5f); bh.setTypeface(Design.sansBold()); bh.setLetterSpacing(0.03f); bh.setPadding(0, dp(12), 0, dp(2));
        box.addView(bh);
        final EditText[][] bidRows = new EditText[Order.MAX_LEVELS][];
        for (int i = 0; i < Order.MAX_LEVELS; i++) {
            boolean legacy0 = i == 0 && p.bids.isEmpty() && p.enable && p.sell > 0;   // seed row from the live single-price bid
            double px = i < p.bids.size() ? p.bids.get(i).price : (legacy0 ? p.sell : 0);
            double am = i < p.bids.size() ? p.bids.get(i).amount : (legacy0 && p.sell > 0 ? liveUsdt / p.sell : 0);
            bidRows[i] = numRow2(box, "B" + (i + 1), px, am, Design.IN());
        }

        final EditText minE = numRow(box, "min MINIMA / trade", p.min);

        final TextView pv = new TextView(this); pv.setTextSize(12f); pv.setTypeface(Design.sans()); pv.setLineSpacing(dp(2), 1f); pv.setPadding(0, dp(10), 0, dp(2));
        box.addView(pv);

        final Runnable upd = () -> updateLadderPreview(pv, askRows, bidRows);
        TextWatcher w = onChange(upd);
        for (EditText[] row : askRows) { row[0].addTextChangedListener(w); row[1].addTextChangedListener(w); }
        for (EditText[] row : bidRows) { row[0].addTextChangedListener(w); row[1].addTextChangedListener(w); }
        upd.run();

        genBtn.setOnClickListener(v -> {
            double mid = parseD(midE.getText().toString(), 0), step = parseD(stepE.getText().toString(), 0), size = parseD(sizeE.getText().toString(), 0);
            if (mid <= 0 || step <= 0 || size <= 0) { toast("Enter mid price, step % and size to generate"); return; }
            for (int i = 0; i < Order.MAX_LEVELS; i++) {
                askRows[i][0].setText(trimSig(mid * (1 + (i + 1) * step / 100.0))); askRows[i][1].setText(trimSig(size));
                bidRows[i][0].setText(trimSig(mid * (1 - (i + 1) * step / 100.0))); bidRows[i][1].setText(trimSig(size));
            }
            upd.run();
        });
        Design.pressable(genBtn);

        modalOpen = true;
        dialog()
                .setTitle("My market / ladder")
                .setView(wrapScroll(box))
                .setPositiveButton("Save", (d, w2) -> {
                    p.enable = sw.isChecked();
                    p.asks.clear(); p.bids.clear();
                    for (EditText[] row : askRows) p.asks.add(new Order.Level(parseD(row[0].getText().toString(), 0), parseD(row[1].getText().toString(), 0)));
                    for (EditText[] row : bidRows) p.bids.add(new Order.Level(parseD(row[0].getText().toString(), 0), parseD(row[1].getText().toString(), 0)));
                    p.min = parseD(minE.getText().toString(), p.min);
                    // Editor is authoritative: a side the user leaves with no valid levels is OFF. Zero its scalar
                    // first so sanitize (which only DERIVES a scalar when a side HAS levels) can't leave a stale
                    // legacy price live — otherwise the engine's empty-ladder fallback would keep trading it.
                    p.buy = 0; p.sell = 0;
                    Order.sanitize(p);   // drop blanks/invalids, cap 6, sort, derive best-level scalars for non-empty sides
                    saveOrder(o);
                    if (p.enable && p.asks.isEmpty() && p.bids.isEmpty())
                        toast("Enabled, but no levels set — add a bid or ask to publish");
                    else
                        toast(Order.crossed(p) ? "⚠ Saved, but your best bid ≥ best ask (crossed market)" : "Ladder saved");
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    /** Live ladder summary in the editor: level counts, best prices, side totals, crossed warning. */
    private void updateLadderPreview(TextView pv, EditText[][] askRows, EditText[][] bidRows) {
        int nA = 0, nB = 0; double bestAsk = Double.MAX_VALUE, bestBid = 0, sumA = 0, sumB = 0;
        for (EditText[] row : askRows) {
            double px = parseD(row[0].getText().toString(), 0), am = parseD(row[1].getText().toString(), 0);
            if (px > 0 && am > 0) { nA++; sumA += am; if (px < bestAsk) bestAsk = px; }
        }
        for (EditText[] row : bidRows) {
            double px = parseD(row[0].getText().toString(), 0), am = parseD(row[1].getText().toString(), 0);
            if (px > 0 && am > 0) { nB++; sumB += am; if (px > bestBid) bestBid = px; }
        }
        if (nA > 0 && nB > 0 && bestBid >= bestAsk) {
            pv.setText("⚠ Crossed — best bid " + fmtPrice(bestBid) + " ≥ best ask " + fmtPrice(bestAsk) + ": you'd sell cheaper than you buy");
            pv.setTextColor(Design.RED());
            return;
        }
        String bidS = nB > 0 ? (nB + " lvl · best " + fmtPrice(bestBid) + " · " + abbrev(sumB) + " M") : "none";
        String askS = nA > 0 ? (nA + " lvl · best " + fmtPrice(bestAsk) + " · " + abbrev(sumA) + " M") : "none";
        pv.setText("BIDS  " + bidS + "\nASKS  " + askS);
        pv.setTextColor(Design.DIM());
    }

    private static TextWatcher onChange(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { r.run(); }
        };
    }

    /**
     * Configure an EditText for decimal entry that the Samsung keyboard can't scramble.
     *
     * The "0.0054 → 0.0045 / cursor jumps" bug is the IME's composing/predictive region: on a
     * {@code numberDecimal} field, Samsung's keyboard keeps a composing region and re-commits keystrokes
     * out of order. {@code TYPE_TEXT_VARIATION_VISIBLE_PASSWORD} forces immediate-commit with no
     * composing/autocorrect (the reliable cure), and the InputFilter keeps it to one decimal number.
     * (Suppressing the periodic refresh — what the limit app tried — does NOT fix this.)
     */
    private void decimalInput(EditText e) {
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setTransformationMethod(null);   // visible-password must still show the digits, not dots
        e.setFilters(new InputFilter[]{ (source, start, end, dest, dstart, dend) -> {
            String result = dest.toString().substring(0, dstart)
                    + source.subSequence(start, end)
                    + dest.toString().substring(dend);
            return (result.isEmpty() || result.matches("[0-9]*\\.?[0-9]*")) ? null : "";
        }});
    }

    private EditText numRow(LinearLayout parent, String label, double value) {
        return numRow(parent, label, value, Design.DIM());
    }

    private EditText numRow(LinearLayout parent, String label, double value, int labelColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this); t.setText(label); t.setTextColor(labelColor); t.setTextSize(13f); t.setTypeface(Design.sans());
        EditText e = new EditText(this);
        decimalInput(e);
        e.setText(trim(value)); e.setTextColor(Design.TEXT()); e.setTextSize(15f); e.setGravity(Gravity.END); e.setTypeface(Design.mono());
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(r);
        return e;
    }

    /** A ladder tranche row: [label][price][amount MINIMA]. Blank fields (value ≤ 0) render empty = unused. */
    private EditText[] numRow2(LinearLayout parent, String label, double price, double amount, int labelColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(2), 0, dp(2));
        TextView t = new TextView(this); t.setText(label); t.setTextColor(labelColor); t.setTextSize(12.5f); t.setTypeface(Design.mono());
        EditText pe = new EditText(this); decimalInput(pe);
        pe.setHint("price"); pe.setHintTextColor(Design.DIM2());
        pe.setText(price > 0 ? trimSig(price) : ""); pe.setTextColor(Design.TEXT()); pe.setTextSize(14f); pe.setGravity(Gravity.END); pe.setTypeface(Design.mono());
        EditText ae = new EditText(this); decimalInput(ae);
        ae.setHint("MINIMA"); ae.setHintTextColor(Design.DIM2());
        ae.setText(amount > 0 ? trimSig(amount) : ""); ae.setTextColor(Design.TEXT()); ae.setTextSize(14f); ae.setGravity(Gravity.END); ae.setTypeface(Design.mono());
        TextView clr = new TextView(this); clr.setText("✕"); clr.setTextColor(Design.DIM2()); clr.setTextSize(15f);
        clr.setTypeface(Design.sans()); clr.setGravity(Gravity.CENTER); clr.setPadding(dp(8), dp(4), dp(2), dp(4));
        clr.setOnClickListener(v -> { pe.setText(""); ae.setText(""); });   // clear this level (watcher then refreshes the preview)
        Design.pressable(clr);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); plp.leftMargin = dp(6);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); alp.leftMargin = dp(8);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT); clp.leftMargin = dp(2);
        r.addView(t, tlp); r.addView(pe, plp); r.addView(ae, alp); r.addView(clr, clp);
        parent.addView(r);
        return new EditText[]{ pe, ae };
    }

    private EditText genField(String hint) {
        EditText e = new EditText(this); decimalInput(e);
        e.setHint(hint); e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT()); e.setTextSize(14f); e.setTypeface(Design.mono()); e.setGravity(Gravity.CENTER);
        return e;
    }

    // ---- take an order (guided swap start) ----

    /**
     * Take a maker's order. We always trade MINIMA, priced in USDT (USDT per MINIMA). You enter a MINIMA
     * amount; the USDT side is derived from the maker's price.
     *   - Sell MINIMA → you give MINIMA, receive USDT at the maker's SELL price (the lower / bid).
     *   - Buy MINIMA  → you give USDT, receive MINIMA at the maker's BUY price (the higher / ask).
     */
    private void takeOrderDialog(Order maker, String symbol, boolean sellMinima, final double price, final double maxAmount) {
        if (price <= 0) return;
        Order.Pair p = maker.pairs.get(symbol);
        final double min = p != null ? p.min : 0;   // maker's global per-trade minimum

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView info = new TextView(this);
        info.setText((sellMinima ? "Sell MINIMA → " + symbol : "Buy MINIMA ← " + symbol)
                + "\nPrice: " + trim(price) + " " + symbol + " per MINIMA"
                + "\nThis level: up to " + abbrev(maxAmount) + " MINIMA"
                + (min > 0 ? "\nMin: " + trim(min) + " MINIMA" : ""));
        info.setTextColor(Design.DIM()); info.setTextSize(13f); info.setTypeface(Design.sans()); info.setLineSpacing(dp(3), 1f); info.setPadding(0, 0, 0, dp(10));
        box.addView(info);

        final EditText amt = new EditText(this);
        amt.setHint("MINIMA to " + (sellMinima ? "sell" : "buy"));
        decimalInput(amt);
        amt.setTextColor(Design.TEXT()); amt.setHintTextColor(Design.DIM2()); amt.setTextSize(16f); amt.setTypeface(Design.mono());
        box.addView(amt);

        final TextView out = new TextView(this);
        out.setTextColor(Design.ACCENT()); out.setTextSize(14f); out.setTypeface(Design.sans()); out.setPadding(0, dp(10), 0, 0);
        box.addView(out);

        amt.addTextChangedListener(new SimpleWatcher(() -> {
            String usdt = computeUsdt(amt.getText().toString(), price);
            out.setText(usdt == null ? "" : (sellMinima ? "You receive ≈ " : "You pay ≈ ") + usdt + " " + symbol);
        }));

        modalOpen = true;
        dialog()
                .setTitle((sellMinima ? "Sell MINIMA" : "Buy MINIMA") + " · " + Util.shorten(maker.signerPk))
                .setView(box)
                .setPositiveButton("Start swap", (d, w) -> {
                    String minima = amt.getText().toString().trim();
                    String usdt = computeUsdt(minima, price);
                    if (usdt == null) { toast("Enter a valid MINIMA amount"); return; }
                    // Client-side guards are UX-only — the maker's engine guard is authoritative — but catch the
                    // obvious auto-declines before locking any coins on-chain.
                    double m = parseD(minima, 0);
                    if (m > maxAmount + 1e-9) { toast("This level takes up to " + abbrev(maxAmount) + " MINIMA — more will be auto-declined"); return; }
                    if (min > 0 && m < min) { toast("Below the maker's minimum (" + trim(min) + " MINIMA)"); return; }
                    startSwap(maker, symbol, sellMinima, minima, usdt);
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    /** USDT side for a MINIMA amount at a given price (USDT per MINIMA). */
    private String computeUsdt(String minimaAmount, double price) {
        try {
            BigDecimal m = new BigDecimal(minimaAmount.trim());
            if (m.signum() <= 0 || price <= 0) return null;
            BigDecimal usdt = m.multiply(BigDecimal.valueOf(price)).setScale(6, RoundingMode.DOWN);
            return Util.tidyAmount(usdt.stripTrailingZeros().toPlainString());
        } catch (Exception e) { return null; }
    }

    private void startSwap(Order maker, String symbol, boolean sellMinima, String minima, String usdt) {
        orderStatus = "Starting swap…"; render();
        startLeg(maker, symbol, sellMinima, minima, usdt, new SwapEngine.StartCb() {
            @Override public void ok(String hash) {
                orderStatus = "✓ Swap started — leg 1 locked. Watching for the counterparty.";
                render(); engine.poll();
            }
            @Override public void err(String msg) { orderStatus = "Swap failed: " + msg; render(); }
        });
    }

    /**
     * Start ONE atomic swap leg (both directions), invoking {@code legCb} once the first-leg lock is placed
     * ({@code ok(hash)}) or on failure ({@code err}). Shared by the single-swap path and each sweep leg. For
     * BUYs it also seals our hashlock to the maker after the USDT locks (so they discover it via getContract).
     * The handshake's status messages are suppressed during a sweep so they don't clobber the sweep progress.
     */
    private void startLeg(Order maker, String symbol, boolean sellMinima, String minima, String usdt, SwapEngine.StartCb legCb) {
        if (sellMinima) {
            // give MINIMA (minima), receive USDT (usdt) → MINIMA→ERC20
            engine.startMinimaToErc20(maker, minima, symbol, usdt, legCb);
        } else {
            // give USDT (usdt), receive MINIMA (minima) → ERC20→MINIMA, then hand the maker our sealed hashlock.
            engine.startErc20ToMinima(maker, symbol, usdt, minima, new SwapEngine.StartCb() {
                @Override public void ok(String hash) {
                    legCb.ok(hash);
                    if (crypto == null || maker.commsPublicId == null || maker.commsPublicId.isEmpty()) {
                        if (sweepRun == null) { orderStatus = "✓ Buy started, but the maker's contact key is missing — they may not see it."; render(); }
                        return;
                    }
                    SwapTake.send(node, crypto, maker.commsPublicId, identity.publicId(), hash, new CommsTransport.SendCb() {
                        @Override public void onSent(String txpowid) { if (sweepRun == null) { orderStatus = "✓ Buy started — maker notified, watching."; render(); } }
                        @Override public void onFailed(String message) { if (sweepRun == null) { orderStatus = "Buy locked, but maker notify failed: " + message; render(); } }
                    });
                }
                @Override public void err(String msg) { legCb.err(msg); }
            });
        }
    }

    // ---- sweep executor: BUY legs fire back-to-back on broadcast (EthTx nonce serializer keeps them ordered +
    //      collision-free); SELL legs stay gated on each MINIMA lock confirming (no nonce for coin selection) ----

    private void startSweep(SweepPlan plan) {
        if (plan == null || plan.legs.isEmpty()) return;
        sweepRun = plan; sweepIdx = 0; sweepOk = 0;
        // Only a SELL sweep must stand the pollers down — its unpinned MINIMA locks could double-select coins.
        // A BUY sweep is nonce-serialized (EthTx), so the poll loop may keep claiming counter-legs alongside it.
        SWEEP_ACTIVE = plan.sell;
        fireSweepLeg();
    }

    /** Tear a sweep down (done, failed, or stalled): stop the watchdog, release the wire, resume normal polling. */
    private void endSweep() {
        sweepRun = null; SWEEP_ACTIVE = false;
        if (sweepWatchdog != null) { ui.removeCallbacks(sweepWatchdog); sweepWatchdog = null; }
        render(); if (engine != null) engine.poll();
    }

    private void fireSweepLeg() {
        if (sweepRun == null) return;
        final boolean sell = sweepRun.sell;
        final int total = sweepRun.legs.size();
        if (sweepIdx >= total) {
            orderStatus = "✓ Sweep done — " + sweepOk + " of " + total + " parts "
                    + (sell ? "locked on-chain" : "sent") + ". Watching for counterparties.";
            endSweep();
            return;
        }
        final SweepLeg leg = sweepRun.legs.get(sweepIdx);
        final int shown = sweepIdx + 1;
        orderStatus = "Sweeping — locking part " + shown + " of " + total + " (best price first)…"; render();

        // Watchdog: if THIS leg's start neither ok's nor err's (a node hang drops the callback), release the wire so
        // claim/refund polling resumes — a stuck sweepRun would otherwise starve every in-flight swap indefinitely.
        if (sweepWatchdog != null) ui.removeCallbacks(sweepWatchdog);
        final int idxAtStart = sweepIdx;
        sweepWatchdog = () -> {
            if (sweepRun != null && sweepIdx == idxAtStart) {
                orderStatus = "Sweep stalled at part " + shown + " (no response) — stopped. Locked parts continue; unmatched auto-refund.";
                endSweep();
            }
        };
        ui.postDelayed(sweepWatchdog, SWEEP_LEG_TIMEOUT_MS);

        startLeg(leg.maker, "USDT", sell, leg.minima, leg.usdt, new SwapEngine.StartCb() {
            @Override public void ok(String hash) {
                if (sweepRun == null || sweepIdx != idxAtStart) return;   // already torn down — ignore late callback
                if (!sell) {
                    // BUY: the serializer already handed this lock a unique, in-order nonce, so fire the next leg
                    // immediately on broadcast — no wait to mine. Best price still mines first (it got nonce N).
                    sweepOk++; sweepIdx++;
                    orderStatus = "✓ Part " + shown + "/" + total + " lock sent"
                            + (sweepIdx < total ? " — sending part " + (sweepIdx + 1) + "…" : ". Watching for counterparties.");
                    render();
                    fireSweepLeg();
                    return;
                }
                // SELL: MINIMA coin selection has no nonce — the next lock could re-select this leg's coins, so
                // wait until this lock's inputs are spent on-chain (confirmMyLock, coinage:1) before advancing.
                // Re-arm the watchdog for the CONFIRM phase — a hung confirmMyLock must still release SWEEP_ACTIVE.
                if (sweepWatchdog != null) ui.removeCallbacks(sweepWatchdog);
                sweepWatchdog = () -> {
                    if (sweepRun != null && sweepIdx == idxAtStart) {
                        orderStatus = "Sweep stalled confirming part " + shown + " — stopped. Locked parts continue; unmatched auto-refund.";
                        endSweep();
                    }
                };
                ui.postDelayed(sweepWatchdog, SWEEP_CONFIRM_WATCHDOG_MS);
                orderStatus = "✓ Part " + shown + "/" + total + " lock sent — confirming on-chain before the next part…";
                render();
                awaitLegConfirm(hash, sell, idxAtStart, shown, total, 0);   // don't advance until it's actually locked in
            }
            @Override public void err(String msg) {
                if (sweepRun == null || sweepIdx != idxAtStart) return;
                orderStatus = "Sweep stopped — " + sweepOk + " of " + total + " parts done (part " + shown
                        + " failed: " + msg + "). Locked parts continue; unmatched auto-refund.";
                endSweep();
            }
        });
    }

    /** Poll until leg {@code idxAtStart}'s lock is confirmed ON-CHAIN, THEN fire the next leg. If it never
     *  confirms (e.g. the lock tx was dropped), STOP — never execute a worse-priced leg behind an unconfirmed best. */
    private void awaitLegConfirm(String hash, boolean sell, int idxAtStart, int shown, int total, int attempt) {
        if (sweepRun == null || sweepIdx != idxAtStart) return;
        engine.confirmMyLock(hash, sell, onChain -> {
            if (sweepRun == null || sweepIdx != idxAtStart) return;
            if (onChain) {
                sweepOk++; sweepIdx++;
                orderStatus = "✓ Part " + shown + "/" + total + " locked in on-chain"
                        + (sweepIdx < total ? " — starting part " + (sweepIdx + 1) + "…" : ".");
                render();
                fireSweepLeg();   // NEXT leg only after THIS one is confirmed — best price locked in first
            } else if (attempt < SWEEP_CONFIRM_TRIES) {
                ui.postDelayed(() -> awaitLegConfirm(hash, sell, idxAtStart, shown, total, attempt + 1), SWEEP_CONFIRM_INTERVAL_MS);
            } else {
                orderStatus = "Sweep stopped — part " + shown + "'s lock didn't confirm on-chain in time; no further parts started. "
                        + "That leg auto-refunds at its timelock; your other funds are untouched.";
                endSweep();
            }
        });
    }

    // ---- UI ----

    // ---- tab host ----

    private LinearLayout buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Design.SURFACE());
        bar.setPadding(dp(6), dp(7), dp(6), dp(9));
        final int[] icons = {R.drawable.ic_tab_swap, R.drawable.ic_tab_wallet, R.drawable.ic_tab_activity, R.drawable.ic_tab_market};
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;
            boolean sel = idx == currentTab;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(6), 0, dp(5));
            cell.setBackground(Design.roundBg(this, sel ? Design.ACCENT_SOFT() : 0x00000000, 14));

            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(sel ? Design.ACCENT() : Design.DIM());
            ic.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
            tabIcons[i] = ic;

            TextView p = new TextView(this);
            p.setText(TAB_LABELS[i]);
            p.setGravity(Gravity.CENTER);
            p.setTextSize(10.5f);
            p.setTypeface(Design.sans());
            p.setPadding(0, dp(4), 0, 0);
            p.setTextColor(sel ? Design.ACCENT() : Design.DIM());
            tabPills[i] = p;

            cell.addView(ic);
            cell.addView(p);
            cell.setOnClickListener(v -> setTab(idx));
            Design.pressable(cell);
            tabCells[i] = cell;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = i == 0 ? 0 : dp(4);
            bar.addView(cell, lp);
        }
        return bar;
    }

    private void updateTabBarSelection() {
        for (int i = 0; i < tabPills.length; i++) {
            boolean sel = i == currentTab;
            if (tabCells[i] != null) tabCells[i].setBackground(Design.roundBg(this, sel ? Design.ACCENT_SOFT() : 0x00000000, 14));
            if (tabIcons[i] != null) tabIcons[i].setColorFilter(sel ? Design.ACCENT() : Design.DIM());
            if (tabPills[i] != null) tabPills[i].setTextColor(sel ? Design.ACCENT() : Design.DIM());
        }
    }

    private void setTab(int t) {
        if (t == currentTab) return;
        swapInputFocused = false;   // leaving the Swap tab clears the typing guard
        currentTab = t;
        animateTab = true;   // cross-fade the new tab in (navigation only, not background refreshes)
        updateTabBarSelection();
        render();
    }

    // ---- render dispatcher ----

    private void render() {
        // Never rebuild the view tree while a dialog with text inputs is open — a background render
        // (watcher / balance callback) restarts the IME and resets the cursor mid-typing. We re-render
        // once when the dialog dismisses. (All text entry lives in guarded dialogs, never inline in a tab.)
        if (modalOpen) return;
        // While the user is typing the swap amount, don't rebuild the tree under them (would reset the IME/cursor).
        if (swapInputFocused && currentTab == TAB_SWAP) return;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(24));
        buildHeader(col);
        switch (currentTab) {
            case TAB_WALLET:   renderWalletTab(col); break;
            case TAB_ACTIVITY: renderActivityTab(col); break;
            case TAB_MARKET:   renderMarketTab(col); break;
            case TAB_SWAP:
            default:           renderSwapTab(col); break;
        }
        scroller.removeAllViews();
        scroller.addView(col);
        if (animateTab) { col.setAlpha(0f); col.animate().alpha(1f).setDuration(120).start(); animateTab = false; }
        if (currentTab == TAB_WALLET) firePendingPulses();
    }

    private void buildHeader(LinearLayout col) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("minimaSwap");
        brand.setTextColor(Design.TEXT()); brand.setTextSize(21f); brand.setTypeface(Design.sansBold()); brand.setLetterSpacing(-0.01f);
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView theme = Design.pill(this, Design.isDark() ? "☾" : "☀", Design.SURFACE2(), Design.DIM());
        theme.setTextSize(13f);
        theme.setOnClickListener(v -> toggleTheme());
        Design.pressable(theme);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = dp(8);
        header.addView(theme, tlp);

        header.addView(mainnetChip());
        col.addView(header);

        TextView sub = new TextView(this);
        sub.setText("v" + BuildConfig.VERSION_NAME + (chainBlock > 0 ? "  ·  block " + chainBlock : "") + "  ·  real funds");
        sub.setTextColor(Design.DIM2()); sub.setTextSize(12f); sub.setTypeface(Design.sans()); sub.setPadding(0, dp(3), 0, dp(12));
        col.addView(sub);
    }

    /** Small "live" chip: a filled dot + Mainnet, tinted with the soft accent. */
    private View mainnetChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Design.roundBg(this, Design.ACCENT_SOFT(), 14));
        chip.setPadding(dp(10), dp(6), dp(11), dp(6));
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL); dg.setColor(Design.ACCENT());
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6)); dlp.rightMargin = dp(6);
        chip.addView(dot, dlp);
        TextView t = new TextView(this);
        t.setText("Mainnet"); t.setTextColor(Design.ACCENT()); t.setTextSize(11f); t.setTypeface(Design.sansBold());
        chip.addView(t);
        return chip;
    }

    // ---- Swap tab (guided, beginner-first) ----

    private TextView segTab(String label, boolean active, Runnable onTap) {
        TextView t = new TextView(this);
        t.setText(label); t.setGravity(Gravity.CENTER); t.setTextSize(14f);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(Design.roundBg(this, active ? Design.ACCENT() : Design.SURFACE2(), 12));
        t.setTextColor(active ? Design.ON_ACCENT() : Design.DIM());
        t.setTypeface(active ? Design.sansBold() : Design.sans());
        t.setOnClickListener(v -> onTap.run());
        Design.pressable(t);
        return t;
    }

    private void renderSwapTab(LinearLayout col) {
        TextView h = new TextView(this);
        h.setText("Swap MINIMA ⇄ USDT");
        h.setTextColor(Design.TEXT()); h.setTextSize(18f); h.setTypeface(Design.sansBold());
        h.setPadding(0, dp(4), 0, dp(2));
        col.addView(h);
        TextView sh = new TextView(this);
        sh.setText("Enter an amount — see exactly what you'll get at the best price.");
        sh.setTextColor(Design.DIM()); sh.setTextSize(12.5f); sh.setTypeface(Design.sans()); sh.setPadding(0, 0, 0, dp(12));
        col.addView(sh);

        // direction toggle (segmented)
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        TextView sellTab = segTab("Sell MINIMA", swapSell, () -> { swapInputFocused = false; if (!swapSell) { swapSell = true; } render(); });
        TextView buyTab = segTab("Buy MINIMA", !swapSell, () -> { swapInputFocused = false; if (swapSell) { swapSell = false; } render(); });
        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); sp1.rightMargin = dp(5);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); sp2.leftMargin = dp(5);
        seg.addView(sellTab, sp1); seg.addView(buyTab, sp2);
        col.addView(seg);

        if (!paired) {
            col.addView(dimNote("Connect your node in Minima Core → Apps to start swapping."));
            swapStages(col);
            return;
        }

        Best best = bestMakers("USDT");
        final Order maker = swapSell ? best.bidMaker : best.askMaker;
        final double price = swapSell ? best.bestBid : best.bestAsk;
        final boolean have = maker != null && price > 0;

        if (!have) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setBackground(Design.card(this, 18));
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(14); empty.setLayoutParams(elp);
            TextView none = new TextView(this);
            none.setText("No one is quoting a " + (swapSell ? "buy" : "sell") + " price right now.");
            none.setTextColor(Design.TEXT()); none.setTextSize(14.5f); none.setTypeface(Design.sansBold());
            empty.addView(none);
            TextView sub2 = new TextView(this);
            sub2.setText("Check back soon, or open Market to place your own order and wait for a match.");
            sub2.setTextColor(Design.DIM()); sub2.setTextSize(12.5f); sub2.setTypeface(Design.sans()); sub2.setLineSpacing(dp(3), 1f); sub2.setPadding(0, dp(6), 0, dp(2));
            empty.addView(sub2);
            TextView go = Design.pill(this, "Open Market", Design.SURFACE2(), Design.TEXT());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.topMargin = dp(12); go.setLayoutParams(glp); go.setOnClickListener(v -> setTab(TAB_MARKET)); Design.pressable(go);
            empty.addView(go);
            col.addView(empty);
            swapStages(col);
            return;
        }

        // ---- faithful dual-amount card: You send / ⇅ / You receive ----
        final boolean sellMinima = swapSell;   // send=MINIMA,recv=USDT when selling; send=USDT,recv=MINIMA when buying
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Design.card(this, 18));
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setClipChildren(false); card.setClipToPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14); card.setLayoutParams(clp);

        // YOU SEND (editable)
        card.addView(fieldLabel("YOU SEND"));
        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL); sendRow.setGravity(Gravity.CENTER_VERTICAL);
        sendRow.setPadding(0, dp(6), 0, dp(4));
        sendRow.addView(coinChip(sellMinima));
        final EditText amt = new EditText(this);
        decimalInput(amt);
        amt.setText(swapAmount);
        amt.setHint("0.00"); amt.setHintTextColor(Design.DIM2());
        amt.setTextColor(Design.TEXT()); amt.setTextSize(26f); amt.setTypeface(Design.monoBold());
        amt.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        amt.setBackground(null); amt.setPadding(dp(8), 0, 0, 0);
        amt.setOnFocusChangeListener((v, has) -> { swapInputFocused = has; if (!has) ui.postDelayed(this::render, 250); });
        if (swapAmount.length() > 0) amt.setSelection(swapAmount.length());
        sendRow.addView(amt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(sendRow);

        // ⇅ flip divider
        LinearLayout flipRow = new LinearLayout(this);
        flipRow.setOrientation(LinearLayout.HORIZONTAL); flipRow.setGravity(Gravity.CENTER_VERTICAL);
        flipRow.setPadding(0, dp(10), 0, dp(10));
        View l1 = new View(this); l1.setBackgroundColor(Design.BORDER());
        View l2 = new View(this); l2.setBackgroundColor(Design.BORDER());
        TextView flip = new TextView(this);
        flip.setText("⇅"); flip.setGravity(Gravity.CENTER); flip.setTextColor(Design.ACCENT()); flip.setTextSize(16f); flip.setTypeface(Design.sansBold());
        flip.setBackground(Design.stroked(this, Design.SURFACE2(), 19));
        flip.setOnClickListener(v -> { swapInputFocused = false; swapSell = !swapSell; render(); }); Design.pressable(flip);
        int fs = dp(38);
        LinearLayout.LayoutParams llL = new LinearLayout.LayoutParams(0, dp(1), 1f);
        LinearLayout.LayoutParams llF = new LinearLayout.LayoutParams(fs, fs); llF.leftMargin = dp(12); llF.rightMargin = dp(12);
        LinearLayout.LayoutParams llR = new LinearLayout.LayoutParams(0, dp(1), 1f);
        flipRow.addView(l1, llL); flipRow.addView(flip, llF); flipRow.addView(l2, llR);
        card.addView(flipRow);

        // YOU RECEIVE — editable too, so you can enter either side (MINIMA or USDT)
        card.addView(fieldLabel("YOU RECEIVE (estimate)"));
        LinearLayout recvRow = new LinearLayout(this);
        recvRow.setOrientation(LinearLayout.HORIZONTAL); recvRow.setGravity(Gravity.CENTER_VERTICAL);
        recvRow.setPadding(0, dp(6), 0, dp(2));
        recvRow.addView(coinChip(!sellMinima));
        final EditText recv = new EditText(this);
        decimalInput(recv);
        recv.setHint("0.00"); recv.setHintTextColor(Design.DIM2());
        recv.setTextColor(Design.ACCENT()); recv.setTextSize(26f); recv.setTypeface(Design.monoBold());
        recv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        recv.setBackground(null); recv.setPadding(dp(8), 0, 0, 0);
        String r0 = sellMinima ? computeUsdt(swapAmount, price) : computeMinima(swapAmount, price);
        recv.setText(r0 == null ? "" : r0);
        recv.setOnFocusChangeListener((v, has) -> { swapInputFocused = has; if (!has) ui.postDelayed(this::render, 250); });
        recvRow.addView(recv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(recvRow);

        // bidirectional live conversion — type EITHER field, the other computes. swapAmount always = the SEND side.
        amt.addTextChangedListener(new SimpleWatcher(() -> {
            if (swapSyncing) return;
            swapAmount = amt.getText().toString();
            String rr = sellMinima ? computeUsdt(swapAmount, price) : computeMinima(swapAmount, price);
            swapSyncing = true; recv.setText(rr == null ? "" : rr); swapSyncing = false;
        }));
        recv.addTextChangedListener(new SimpleWatcher(() -> {
            if (swapSyncing) return;
            String rstr = recv.getText().toString();
            String ss = sellMinima ? computeMinima(rstr, price) : computeUsdt(rstr, price);   // inverse: receive → send
            swapSyncing = true; amt.setText(ss == null ? "" : ss); swapSyncing = false;
            swapAmount = amt.getText().toString();
        }));

        // best price line — best-level cap, plus total sweepable depth across the book (larger orders sweep)
        double bestCap = sellMinima ? best.bidCap : best.askCap;
        double depth = sweepDepthMinima(sellMinima);
        TextView bp = new TextView(this);
        bp.setText("Best price " + fmtPrice(price) + " USDT/MINIMA  ·  up to ~" + abbrev(bestCap) + " at best"
                + (depth > bestCap + 1e-9 ? ", ~" + abbrev(depth) + " across the book" : "")
                + (sellMinima ? "" : "  ·  ETH gas per part"));
        bp.setTextColor(Design.DIM2()); bp.setTextSize(11.5f); bp.setTypeface(Design.sans()); bp.setPadding(0, dp(12), 0, 0);
        card.addView(bp);

        // BUY slippage tolerance — the most above best you'll pay per MINIMA when filling across deeper levels.
        if (!sellMinima) {
            double curPct = buySlippage() * 100;
            boolean is2 = Math.abs(curPct - 2) < 0.001, is42 = Math.abs(curPct - 4.2) < 0.001;
            LinearLayout slip = new LinearLayout(this);
            slip.setOrientation(LinearLayout.HORIZONTAL); slip.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(10); slip.setLayoutParams(slp);
            TextView lbl = new TextView(this); lbl.setText("Max slippage"); lbl.setTextColor(Design.DIM()); lbl.setTextSize(11.5f); lbl.setTypeface(Design.sans());
            slip.addView(lbl);
            slip.addView(slipPill("2%", is2, () -> setBuySlippage(2)));
            slip.addView(slipPill("4.2%", is42, () -> setBuySlippage(4.2)));
            slip.addView(slipPill((!is2 && !is42) ? pctStr(curPct) + "%" : "Custom", !is2 && !is42, this::customSlippageDialog));
            card.addView(slip);
        }

        TextView cta = button("Review swap");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16); cta.setLayoutParams(blp);
        cta.setOnClickListener(v -> onSwapCta(sellMinima, (sellMinima ? amt : recv).getText().toString().trim(), best));   // MINIMA-side field
        card.addView(cta);

        col.addView(card);

        // live swap-leg stages below the price
        swapStages(col);

        if (orderStatus != null) {
            TextView stt = new TextView(this);
            stt.setText(orderStatus);
            stt.setTextColor(orderStatus.startsWith("✓") ? Design.IN() : Design.DIM());
            stt.setTextSize(12.5f); stt.setTypeface(Design.sans()); stt.setPadding(dp(2), dp(12), dp(2), 0);
            col.addView(stt);
        }
    }

    private TextView fieldLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Design.DIM()); t.setTextSize(11f); t.setTypeface(Design.sans()); t.setLetterSpacing(0.06f);
        return t;
    }

    /** A token chip: coloured coin mark + ticker + available balance. */
    private LinearLayout coinChip(boolean minima) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL); chip.setGravity(Gravity.CENTER_VERTICAL);
        TextView circle = new TextView(this);
        circle.setText(minima ? "M" : "$"); circle.setGravity(Gravity.CENTER);
        circle.setTextColor(minima ? Design.ON_ACCENT() : 0xFFFFFFFF);
        circle.setTypeface(Design.sansBold()); circle.setTextSize(15f);
        android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
        cg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        cg.setColor(minima ? Design.ACCENT() : USDT_TEAL);
        circle.setBackground(cg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(34), dp(34)); clp.rightMargin = dp(10);
        chip.addView(circle, clp);
        LinearLayout colc = new LinearLayout(this); colc.setOrientation(LinearLayout.VERTICAL);
        TextView tk = new TextView(this); tk.setText(minima ? "MINIMA" : "USDT"); tk.setTextColor(Design.TEXT()); tk.setTextSize(15f); tk.setTypeface(Design.sansBold());
        TextView sub = new TextView(this);
        String usdt = tokenBals.get("USDT");
        sub.setText(minima ? ("avail " + abbrev(parseD(minimaBal, 0))) : ("avail " + (usdt != null ? Util.tidyAmount(usdt) : "—")));
        sub.setTextColor(Design.DIM()); sub.setTextSize(11f); sub.setTypeface(Design.sans());
        colc.addView(tk); colc.addView(sub);
        chip.addView(colc);
        return chip;
    }

    /** MINIMA you'd receive for a USDT amount at a given price (USDT per MINIMA). */
    private String computeMinima(String usdtAmount, double price) {
        try {
            BigDecimal u = new BigDecimal(usdtAmount.trim());
            if (u.signum() <= 0 || price <= 0) return null;
            BigDecimal m = u.divide(BigDecimal.valueOf(price), 8, RoundingMode.DOWN);
            return Util.tidyAmount(m.stripTrailingZeros().toPlainString());
        } catch (Exception e) { return null; }
    }

    /** Confirm the inline swap, then start it via the existing engine path. */
    private void reviewSwap(Order maker, boolean sellMinima, String sendStr, double price, double maxAmount) {
        swapInputFocused = false;   // leaving the amount field — let the panel re-render live from here on
        if (sendStr.isEmpty()) { toast("Enter an amount to " + (sellMinima ? "sell" : "spend")); return; }
        final String minima, usdt;
        if (sellMinima) { minima = sendStr; usdt = computeUsdt(sendStr, price); }
        else { usdt = sendStr; minima = computeMinima(sendStr, price); }
        if (minima == null || usdt == null) { toast("Enter a valid amount"); return; }
        // UX-only cap check against the best level (the maker's guard is authoritative) — stop over-sizing before a lock.
        if (maxAmount > 0 && parseD(minima, 0) > maxAmount + 1e-9) {
            toast("Best level takes up to " + abbrev(maxAmount) + " MINIMA — reduce the amount"); return;
        }
        String msg = sellMinima
                ? ("Sell  " + minima + " MINIMA\nReceive  ≈ " + usdt + " USDT\n\nBest price " + fmtPrice(price) + " USDT/MINIMA\nCounterparty  " + Util.shorten(maker.signerPk)
                    + "\n\nThis locks your MINIMA on-chain. Continue?")
                : ("Buy  ≈ " + minima + " MINIMA\nPay  " + usdt + " USDT (+ ETH gas)\n\nBest price " + fmtPrice(price) + " USDT/MINIMA\nCounterparty  " + Util.shorten(maker.signerPk)
                    + "\n\nThis locks your USDT on-chain. Continue?");
        modalOpen = true;
        dialog()
                .setTitle(sellMinima ? "Review — Sell MINIMA" : "Review — Buy MINIMA")
                .setMessage(msg)
                .setPositiveButton("Start swap", (d, w) -> { swapAmount = ""; startSwap(maker, "USDT", sellMinima, minima, usdt); })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    /** Route the Swap-tab CTA: size within the best level → today's single swap; larger → market sweep. */
    /** {@code minimaStr} is the MINIMA amount to sell / to buy (the MINIMA-side field). */
    private void onSwapCta(boolean sell, String minimaStr, Best best) {
        swapInputFocused = false;   // leaving the amount field — panel re-renders live from here (0.8.1 lesson)
        if (minimaStr.isEmpty()) { toast("Enter how much MINIMA to " + (sell ? "sell" : "buy")); return; }
        final Order bestMaker = sell ? best.bidMaker : best.askMaker;
        final double bestPrice = sell ? best.bestBid : best.bestAsk;
        final double bestCap = sell ? best.bidCap : best.askCap;   // MINIMA
        if (bestMaker == null || bestPrice <= 0) { toast("No quote available right now."); return; }
        final double EPS = 1e-9;
        final double want = parseD(minimaStr, 0);
        if (want <= 0) { toast("Enter a valid MINIMA amount."); return; }

        if (sell) {
            if (want > parseD(minimaBal, 0) + EPS) {
                toast("You only have ~" + abbrev(parseD(minimaBal, 0)) + " MINIMA available to sell."); return;
            }
            if (want <= bestCap + EPS) { reviewSwap(bestMaker, true, minimaStr, bestPrice, bestCap); return; }   // single (send = MINIMA)
            SweepPlan plan = buildSweepPlan(true, minimaStr, 0);
            if (plan.legs.isEmpty()) { toast(sweepEmptyMsg(plan)); return; }
            reviewSweep(plan);
            return;
        }

        // BUY: target-driven with slippage — deliver `want` MINIMA across asks priced ≤ best × (1+slippage).
        SweepPlan plan = buildSweepPlan(false, minimaStr, buySlippage());
        if (plan.legs.isEmpty()) { toast(sweepEmptyMsg(plan)); return; }
        double haveUsdt = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0);
        if (plan.totalUsdt > haveUsdt + EPS) {
            toast("Need ≈ " + trimSig(plan.totalUsdt) + " USDT for " + abbrev(plan.filledMinima) + " MINIMA (within "
                    + pctStr(buySlippage() * 100) + "%) — you have " + trimSig(haveUsdt) + "."); return;
        }
        reviewSweep(plan);
    }

    private String sweepEmptyMsg(SweepPlan p) {
        if ("below-min".equals(p.stopReason)) return "That's below the makers' minimum trade size.";
        return "No liquidity available to fill that right now.";
    }

    /** Review a multi-leg market sweep: blended avg/worst price, per-leg breakdown, totals, partial-fill note. */
    private void reviewSweep(SweepPlan plan) {
        if (plan == null || plan.legs.isEmpty()) { toast("Nothing available to fill right now."); return; }
        final boolean sell = plan.sell;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView head = new TextView(this);
        head.setText(sell
                ? ("Sell " + abbrev(plan.filledMinima) + " MINIMA in " + plan.legs.size() + (plan.legs.size() == 1 ? " part" : " parts"))
                : ("Buy ≈ " + abbrev(plan.filledMinima) + " MINIMA for ≈ " + trimSig(plan.totalUsdt) + " USDT in " + plan.legs.size() + (plan.legs.size() == 1 ? " part" : " parts")));
        head.setTextColor(Design.TEXT()); head.setTextSize(15f); head.setTypeface(Design.sansBold()); head.setPadding(0, 0, 0, dp(6));
        box.addView(head);

        TextView metrics = new TextView(this);
        metrics.setText("Avg " + fmtPrice(plan.avgPrice) + "  ·  worst " + fmtPrice(plan.worstPrice) + " USDT/MINIMA"
                + (!sell && plan.slippagePct > 0 ? "  ·  within " + pctStr(plan.slippagePct) + "% slippage" : ""));
        metrics.setTextColor(Design.DIM()); metrics.setTextSize(12.5f); metrics.setTypeface(Design.sans()); metrics.setPadding(0, 0, 0, dp(8));
        box.addView(metrics);

        for (int i = 0; i < plan.legs.size(); i++) {
            SweepLeg leg = plan.legs.get(i);
            TextView r = new TextView(this);
            r.setText("Part " + (i + 1) + " · " + leg.minima + " MINIMA @ " + fmtPrice(leg.price) + " → " + leg.usdt + " USDT · " + shortAddr(leg.maker.signerPk));
            r.setTextColor(Design.TEXT()); r.setTextSize(12f); r.setTypeface(Design.mono()); r.setPadding(0, dp(2), 0, dp(2));
            box.addView(r);
        }

        TextView totals = new TextView(this);
        totals.setText(sell
                ? ("Total: sell " + trimSig(plan.filledMinima) + " MINIMA · receive ≈ " + trimSig(plan.totalUsdt) + " USDT")
                : ("Total: pay ≈ " + trimSig(plan.totalUsdt) + " USDT · receive ≈ " + trimSig(plan.filledMinima) + " MINIMA"));
        totals.setTextColor(Design.ACCENT()); totals.setTextSize(12.5f); totals.setTypeface(Design.sansBold()); totals.setPadding(0, dp(8), 0, dp(2));
        box.addView(totals);

        if (plan.partial) {
            TextView pw = new TextView(this);
            String msg = "Fills " + abbrev(plan.filledMinima) + " of " + abbrev(plan.target) + " MINIMA — ";
            if (!sell && "slippage".equals(plan.stopReason))
                msg += "the rest is priced beyond your " + pctStr(plan.slippagePct) + "% slippage.";
            else
                msg += "the rest isn't available in the book right now.";
            pw.setText(msg);
            pw.setTextColor(Design.RED()); pw.setTextSize(12f); pw.setTypeface(Design.sans()); pw.setLineSpacing(dp(2), 1f); pw.setPadding(0, dp(8), 0, dp(2));
            box.addView(pw);
        }

        if (!sell) {
            TextView gas = new TextView(this);
            gas.setText("Each part is a separate Ethereum transaction — you pay ETH gas " + plan.legs.size() + " times.");
            gas.setTextColor(Design.DIM2()); gas.setTextSize(11.5f); gas.setTypeface(Design.sans()); gas.setPadding(0, dp(8), 0, dp(2));
            box.addView(gas);
        }

        TextView note = new TextView(this);
        note.setText("Parts run one at a time. If one can't start we stop and you keep the rest; unmatched parts auto-refund after the timelock.");
        note.setTextColor(Design.DIM2()); note.setTextSize(11.5f); note.setTypeface(Design.sans()); note.setLineSpacing(dp(2), 1f); note.setPadding(0, dp(8), 0, dp(2));
        box.addView(note);

        modalOpen = true;
        dialog()
                .setTitle(sell ? "Review — Sweep sell" : "Review — Sweep buy")
                .setView(wrapScroll(box))
                .setPositiveButton("Start sweep", (d, w) -> { swapAmount = ""; startSweep(plan); })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    // ---- swap-leg stage pills (readiness → lock → counterparty → claim → complete) ----

    private void swapStages(LinearLayout col) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18); box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText("YOUR SWAP"); t.setTextColor(Design.DIM()); t.setTextSize(11f); t.setLetterSpacing(0.06f); t.setTypeface(Design.sans()); t.setPadding(dp(2), 0, 0, dp(8));
        box.addView(t);

        // pre-flight readiness
        box.addView(stagePill("Node connected", paired ? STG_DONE : STG_WARN));
        if (swapSell) {
            box.addView(stagePill("MINIMA ready to sell", parseD(minimaBal, 0) > 0 ? STG_DONE : STG_WARN));
        } else {
            String usdt = tokenBals.get("USDT");
            box.addView(stagePill("USDT ready to spend", (usdt != null && parseD(usdt, 0) > 0) ? STG_DONE : STG_WARN));
            box.addView(stagePill("ETH for gas", parseD(ethBal.replace(" ETH", ""), 0) > 0 ? STG_DONE : STG_WARN));
        }

        // live swap legs (if a swap is in flight or just finished)
        SwapDb.Swap sw = latestRelevantSwap();
        if (sw != null) {
            // which swap this panel is tracking (disambiguates concurrent swaps)
            TextView which = new TextView(this);
            which.setText(sw.sellAmount + " " + sw.sellToken + "  →  " + sw.buyAmount + " " + sw.buyToken
                    + "   ·   " + (sw.role == null ? "" : sw.role.toLowerCase()));
            which.setTextColor(Design.TEXT()); which.setTextSize(13f); which.setTypeface(Design.mono()); which.setPadding(dp(2), dp(2), 0, dp(6));
            box.addView(which);
            if (SwapDb.ST_REFUNDED.equals(sw.status)) {
                box.addView(stagePill("Timed out — " + sw.sellAmount + " " + sw.sellToken + " refunded", STG_WARN));
            } else {
                int active = stageIndex(sw.status);   // index of the first not-yet-done leg
                box.addView(stagePill("Locked your " + sw.sellAmount + " " + sw.sellToken, legState(1, active)));
                box.addView(stagePill("Counterparty locks their side", legState(2, active)));
                box.addView(stagePill("Claim your " + sw.buyAmount + " " + sw.buyToken, legState(3, active)));
                box.addView(stagePill("Swap complete", legState(4, active)));
            }
            TextView detail = new TextView(this);
            detail.setText(statusDetail(sw));
            detail.setTextColor(Design.DIM()); detail.setTextSize(12f); detail.setTypeface(Design.sans()); detail.setLineSpacing(dp(2), 1f); detail.setPadding(dp(2), dp(8), 0, 0);
            box.addView(detail);
            if (!isTerminal(sw)) {
                TextView chk = Design.pill(this, "Check now", Design.SURFACE2(), Design.TEXT());
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(8); chk.setLayoutParams(clp);
                chk.setOnClickListener(v -> checkNow(sw.hash)); Design.pressable(chk);
                box.addView(chk);
            }
            int more = activeSwapCount() - (isTerminal(sw) ? 0 : 1);
            if (more > 0) {
                TextView moreTv = new TextView(this);
                moreTv.setText("+ " + more + " more swap" + (more == 1 ? "" : "s") + " in flight — see Activity");
                moreTv.setTextColor(Design.DIM2()); moreTv.setTextSize(11.5f); moreTv.setTypeface(Design.sans()); moreTv.setPadding(dp(2), dp(8), 0, 0);
                moreTv.setOnClickListener(v -> openHistory(0));
                Design.pressable(moreTv);
                box.addView(moreTv);
            }
        } else {
            box.addView(stagePill("Enter an amount and tap Review to begin", STG_PENDING));
        }
        col.addView(box);
    }

    /** A stage row: coloured status dot + label. state ∈ {pending, active, done, warn}. */
    private LinearLayout stagePill(String label, int state) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        switch (state) {
            case STG_DONE:   dg.setColor(Design.IN()); break;
            case STG_ACTIVE: dg.setColor(Design.ACCENT()); break;
            case STG_WARN:   dg.setColor(STAGE_WARN_COLOR); break;
            default:         dg.setColor(0x00000000); dg.setStroke(Math.max(1, dp(1)), Design.DIM2()); break;
        }
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9)); dlp.leftMargin = dp(2); dlp.rightMargin = dp(12);
        row.addView(dot, dlp);
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(13f);
        switch (state) {
            case STG_ACTIVE: t.setTextColor(Design.TEXT()); t.setTypeface(Design.sansBold()); break;
            case STG_DONE:   t.setTextColor(Design.TEXT()); t.setTypeface(Design.sans()); break;
            case STG_WARN:   t.setTextColor(STAGE_WARN_COLOR); t.setTypeface(Design.sans()); break;
            default:         t.setTextColor(Design.DIM()); t.setTypeface(Design.sans()); break;
        }
        row.addView(t);
        return row;
    }

    private int legState(int i, int active) {
        if (i < active) return STG_DONE;
        if (i == active) return STG_ACTIVE;
        return STG_PENDING;
    }

    /** Index of the first not-yet-completed leg for a status (1=lock,2=counterparty,3=claim,5=all done). */
    private int stageIndex(String status) {
        switch (status) {
            case SwapDb.ST_STARTED:  return 2;   // your leg locked; waiting on counterparty
            case SwapDb.ST_LOCKED:   return 3;   // counterparty locked; claiming next
            case SwapDb.ST_CLAIMING: return 3;   // claiming your funds
            case SwapDb.ST_COMPLETE: return 5;   // all legs done
            default:                 return 2;
        }
    }

    /** The swap to surface on the Swap tab: most-recently-updated non-terminal swap, or one that finished
     *  within the grace window (so you see it land). */
    /** The swap the front-page "YOUR SWAP" panel tracks: an IN-FLIGHT swap first (so a sweep shows its still-active
     *  leg, not a finished one); else a JUST-finished one, briefly, so it doesn't linger once funds have confirmed. */
    private SwapDb.Swap latestRelevantSwap() {
        if (db == null) return null;
        SwapDb.Swap active = null, recentDone = null;
        long now = System.currentTimeMillis();
        java.util.Set<String> dismissed = dismissedSwaps();
        for (SwapDb.Swap s : db.allSwaps()) {
            if (dismissed.contains(s.hash)) continue;   // user hid it — keep it out of the front-page panel
            if (!isTerminal(s)) {
                if (active == null || s.created > active.created) active = s;
            } else if (now - s.updated <= SWAP_PANEL_GRACE_MS) {
                if (recentDone == null || s.updated > recentDone.updated) recentDone = s;
            }
        }
        return active != null ? active : recentDone;   // in-flight first; else a just-finished one (~30s); else null → fresh prompt
    }

    /** Count of swaps still in flight (non-terminal) — used to hint at concurrent swaps. */
    private int activeSwapCount() {
        if (db == null) return 0;
        int n = 0;
        java.util.Set<String> dismissed = dismissedSwaps();
        for (SwapDb.Swap s : db.allSwaps()) if (!isTerminal(s) && !dismissed.contains(s.hash)) n++;
        return n;
    }

    private static final class Best { Order bidMaker, askMaker; double bestBid = 0, bestAsk = Double.MAX_VALUE; double bidCap = 0, askCap = 0; }

    /** Best non-own bid/ask LEVEL across the live order book (guided Swap tab; never picks my own order).
     *  Walks each maker's ladder (legacy makers contribute one synthetic level) and keeps the single best
     *  price per side plus that level's balance-clamped take cap. */
    private Best bestMakers(String sym) {
        Best r = new Best();
        for (Order o : orderBook.values()) {
            if (isMine(o)) continue;
            for (Order.Level l : o.effectiveBids(sym)) {
                double cap = l.price > 0 ? Math.min(l.amount, o.usdtAvail / l.price) : 0;
                if (l.price > r.bestBid && cap > 0) { r.bestBid = l.price; r.bidMaker = o; r.bidCap = cap; }
            }
            for (Order.Level l : o.effectiveAsks(sym)) {
                double cap = Math.min(l.amount, o.minimaAvail);
                if ((r.askMaker == null || l.price < r.bestAsk) && cap > 0) { r.bestAsk = l.price; r.askMaker = o; r.askCap = cap; }
            }
        }
        if (r.askMaker == null) r.bestAsk = 0;   // normalize "none" to 0 for callers
        return r;
    }

    // ---- market sweep: split a large order across consecutive book levels, each an independent atomic swap ----

    /** One planned leg = one atomic swap against a specific maker+price. */
    private static final class SweepLeg {
        final Order maker; final double price; final String minima, usdt; final double minimaD, usdtD;
        SweepLeg(Order maker, double price, String minima, String usdt) {
            this.maker = maker; this.price = price; this.minima = minima; this.usdt = usdt;
            this.minimaD = parseD(minima, 0); this.usdtD = parseD(usdt, 0);
        }
    }
    private static final class SweepPlan {
        final boolean sell; final java.util.List<SweepLeg> legs = new java.util.ArrayList<>();
        double target, filledMinima, totalUsdt, avgPrice, worstPrice, slippagePct;
        boolean partial; String stopReason = "filled";   // filled | leg-cap | book-exhausted | below-min | slippage
        SweepPlan(boolean sell) { this.sell = sell; }
    }

    /** Round a MINIMA quantity DOWN to 8dp (never over a level's cap) and tidy it for the wire. */
    private static String legMinima(double v) {
        return Util.tidyAmount(BigDecimal.valueOf(v).setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString());
    }

    /** Round a USDT amount DOWN to 6dp (never over budget) and tidy it; null if it collapses to zero. */
    /** USDT for a target-driven BUY leg = ceil6(minima × price), computed EXACTLY in BigDecimal from the minima
     *  STRING (not a double product), so {@code lockedUsdt ≥ minima × price} holds to the last tick against the
     *  maker's own BigDecimal guard while I still receive my target MINIMA. Non-finite/garbage input → null (no throw). */
    private static String ceilUsdt(String minima, double price) {
        if (minima == null || !Double.isFinite(price) || price <= 0) return null;
        try {
            BigDecimal u = new BigDecimal(minima.trim()).multiply(BigDecimal.valueOf(price)).setScale(6, RoundingMode.UP);
            if (u.signum() <= 0) return null;
            String s = Util.tidyAmount(u.stripTrailingZeros().toPlainString());
            return (s.isEmpty() || "0".equals(s)) ? null : s;
        } catch (Exception e) { return null; }
    }

    /** Format a percentage to ≤2dp (kills the float noise from storing e.g. 4.2f: 4.199999809… → "4.2"). */
    private static String pctStr(double v) {
        return Util.tidyAmount(BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
    }

    /**
     * Walk the book best-price-first, splitting a large order into up to {@link Order#MAX_LEVELS} per-level legs.
     * SELL is size-driven (send = MINIMA to sell); BUY is BUDGET-driven (send = USDT to spend) so the total
     * spent never exceeds what the user typed even as deeper legs cost more. Each leg is clamped to the maker's
     * display cap AND a per-maker running balance (so two tranches of one maker can't over-allocate). Never emits
     * a sub-min leg. Excludes my own orders.
     */
    /**
     * Split a large order into up to {@link Order#MAX_LEVELS} per-level legs, best-price-first. BOTH sides are
     * MINIMA-TARGET-driven ({@code targetStr} = MINIMA to sell / to buy). BUY additionally caps at
     * {@code bestAsk × (1+slippage)} — legs above that are not taken (partial). Leg rounding keeps the maker's
     * guard satisfied: SELL locks MINIMA + requests {@code floor6(minima×bid)}; BUY locks {@code ceil6(minima×ask)}
     * + requests exactly {@code minima}. Each leg clamped to the maker's live balance + a per-maker running total.
     */
    private SweepPlan buildSweepPlan(boolean sell, String targetStr, double slippage) {
        final String sym = "USDT";
        final double EPS = 1e-9;
        SweepPlan plan = new SweepPlan(sell);
        plan.slippagePct = slippage * 100;
        double target = parseD(targetStr, 0);   // MINIMA to sell (sell) or MINIMA to buy (buy)
        plan.target = target;
        if (target <= 0) { plan.stopReason = "book-exhausted"; return plan; }
        final double dust = Math.max(EPS, target * 1e-4);   // ignore a sub-0.01% rounding remainder

        java.util.Map<Order, Double> remUsdt = new java.util.HashMap<>();     // maker's remaining USDT balance
        java.util.Map<Order, Double> remMinima = new java.util.HashMap<>();   // maker's remaining MINIMA balance
        double remaining = target;                                           // MINIMA left to fill
        double bestPrice = 0;                                                // first leg actually taken = the slippage anchor

        for (Object[] row : aggSide(sym, sell, true)) {
            if (plan.legs.size() >= Order.MAX_LEVELS) { plan.stopReason = "leg-cap"; plan.partial = true; break; }
            if (remaining <= dust) break;
            Order maker = (Order) row[0];
            Order.Level lvl = (Order.Level) row[1];
            double price = lvl.price;
            if (price <= 0) continue;

            // BUY slippage cap — never pay more than best × (1+slippage) per MINIMA.
            if (!sell && bestPrice > 0 && slippage > 0 && price > bestPrice * (1 + slippage) + EPS) {
                plan.stopReason = "slippage"; plan.partial = true; break;
            }

            Order.Pair pr = maker.pairs.get(sym);
            double mn = pr != null ? pr.min : 0;

            double capMinima;
            if (sell) {   // maker BUYS my MINIMA with USDT → cap by their remaining USDT / price
                double ru = remUsdt.containsKey(maker) ? remUsdt.get(maker) : maker.usdtAvail;
                capMinima = Math.min(lvl.amount, ru / price);
            } else {      // maker SELLS MINIMA → cap by their remaining MINIMA
                double rm = remMinima.containsKey(maker) ? remMinima.get(maker) : maker.minimaAvail;
                capMinima = Math.min(lvl.amount, rm);
            }
            if (capMinima <= EPS) continue;

            double takeMinima = Math.min(capMinima, remaining);
            if (takeMinima <= EPS) continue;

            if (mn > 0 && takeMinima < mn - EPS) {   // never emit a sub-min leg (the maker would auto-decline)
                if (remaining < mn - EPS) { plan.stopReason = "below-min"; plan.partial = true; break; }
                continue;   // this level can't honor its own maker's min → skip it, try deeper
            }

            String minimaStr = legMinima(takeMinima);
            double m = parseD(minimaStr, 0);
            if (m <= EPS) continue;
            String usdtStr = sell ? computeUsdt(minimaStr, price) : ceilUsdt(minimaStr, price);
            if (usdtStr == null) continue;
            SweepLeg leg = new SweepLeg(maker, price, minimaStr, usdtStr);
            if (leg.minimaD <= EPS || leg.usdtD <= EPS) continue;
            if (mn > 0 && leg.minimaD < mn - EPS) continue;   // rounding pushed this sliver below the maker's min — skip

            if (bestPrice <= 0) bestPrice = price;   // anchor slippage on the FIRST leg actually taken (best fillable)
            plan.legs.add(leg);
            plan.filledMinima += leg.minimaD;
            plan.totalUsdt += leg.usdtD;
            plan.worstPrice = price;   // best-first → the last added is the worst price
            if (sell) remUsdt.put(maker, (remUsdt.containsKey(maker) ? remUsdt.get(maker) : maker.usdtAvail) - leg.usdtD);
            else      remMinima.put(maker, (remMinima.containsKey(maker) ? remMinima.get(maker) : maker.minimaAvail) - leg.minimaD);
            remaining -= leg.minimaD;
        }

        if (remaining > dust) plan.partial = true;
        if (plan.partial && "filled".equals(plan.stopReason)) plan.stopReason = "book-exhausted";
        plan.avgPrice = plan.filledMinima > 0 ? plan.totalUsdt / plan.filledMinima : 0;
        return plan;
    }

    /** Total MINIMA takeable across the book (≤6 legs) on the given side — for the "up to ~X" depth hint. */
    private double sweepDepthMinima(boolean sell) {
        return buildSweepPlan(sell, "1000000000", sell ? 0 : buySlippage()).filledMinima;   // huge target → the whole (within-slippage) depth
    }

    // ---- buy slippage tolerance (deliver the MINIMA target across deeper legs, capped at best × (1+slippage)) ----

    private double buySlippage() { return prefs.getFloat("buy_slippage_pct", 4.2f) / 100.0; }   // fraction, default 4.2%

    private TextView slipPill(String label, boolean active, Runnable onTap) {
        TextView p = Design.pill(this, label, active ? Design.ACCENT_SOFT() : Design.SURFACE2(), active ? Design.ACCENT() : Design.DIM());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6); p.setLayoutParams(lp);
        p.setOnClickListener(v -> onTap.run()); Design.pressable(p);
        return p;
    }

    private void setBuySlippage(double pct) {
        prefs.edit().putFloat("buy_slippage_pct", (float) Math.max(0.1, Math.min(50, pct))).apply();
        render();
    }

    private void customSlippageDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView h = new TextView(this);
        h.setText("The most above the best price you'll pay per MINIMA when a buy fills across deeper levels.");
        h.setTextColor(Design.DIM()); h.setTextSize(12.5f); h.setTypeface(Design.sans()); h.setLineSpacing(dp(2), 1f); h.setPadding(0, 0, 0, dp(10));
        box.addView(h);
        final EditText e = new EditText(this); decimalInput(e);
        e.setText(trim(buySlippage() * 100)); e.setHint("%"); e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT()); e.setTextSize(16f); e.setTypeface(Design.mono());
        box.addView(e);
        modalOpen = true;
        dialog()
                .setTitle("Custom max slippage")
                .setView(box)
                .setPositiveButton("Set", (d, w) -> setBuySlippage(parseD(e.getText().toString(), 4.2)))
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    // ---- Wallet tab ----

    private void renderWalletTab(LinearLayout col) {
        LinearLayout minimaCard = walletCard("Minima · available to swap", minimaBal + " MINIMA", minimaBreakdown() + "  ·  long-press for coins", Design.ACCENT());
        minimaCard.setOnLongClickListener(v -> { minimaCoinDump(); return true; });
        col.addView(minimaCard);
        minimaBalView = (TextView) minimaCard.findViewWithTag(TAG_BAL);   // fresh ref each render (tree is rebuilt)

        String addrLine = ethAddr == null ? (ethErr == null ? "deriving from node seed…" : "—") : shortAddr(ethAddr);
        LinearLayout ethCard = walletCard("Ethereum · " + net.label, ethBal, addrLine, Design.TEXT());
        if (ethAddr != null) ethCard.setOnClickListener(v -> receiveDialog());
        col.addView(ethCard);
        ethBalView = (TextView) ethCard.findViewWithTag(TAG_BAL);
        tokenBalViews.clear();
        for (Map.Entry<String, String> e : tokenBals.entrySet()) {
            // token balances get the same big card treatment as Minima/ETH (not a small row)
            LinearLayout tcard = walletCard(e.getKey() + " · Ethereum", e.getValue() + " " + e.getKey(), null, Design.TEXT());
            TextView tv = (TextView) tcard.findViewWithTag(TAG_BAL);
            if (tv != null) tokenBalViews.add(tv);
            col.addView(tcard);
        }

        if (ethAddr != null) {
            LinearLayout walletActions = new LinearLayout(this);
            walletActions.setOrientation(LinearLayout.HORIZONTAL);
            walletActions.setPadding(0, dp(8), 0, 0);
            TextView refreshBal = Design.pill(this, "↻  Refresh", Design.SURFACE2(), Design.TEXT());
            refreshBal.setOnClickListener(v -> { toast("Refreshing balances…"); refreshBalances(true); });
            TextView fund = Design.pill(this, "⤓  Fund / QR", Design.SURFACE2(), Design.TEXT());
            fund.setOnClickListener(v -> receiveDialog());
            TextView exportKey = Design.pill(this, "🔑  Export key", Design.SURFACE2(), Design.DIM());
            exportKey.setOnClickListener(v -> exportKeyDialog());
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gap.rightMargin = dp(8);
            walletActions.addView(refreshBal, gap);
            walletActions.addView(fund, gap);
            walletActions.addView(exportKey);
            col.addView(walletActions);
        }

        if (ethErr != null) {
            TextView err = new TextView(this);
            err.setText("⚠ " + ethErr);
            err.setTextColor(Design.RED()); err.setTextSize(12f); err.setPadding(dp(2), dp(8), dp(2), 0);
            col.addView(err);
        }

        if (!paired) col.addView(dimNote("Connect your node in Minima Core → Apps to see your balances."));
    }

    /** Fire any balance pulse that was armed while the Wallet tab wasn't showing (its balance views didn't exist). */
    private void firePendingPulses() {
        if (modalOpen) return;
        if (pulseMinimaPending && minimaBalView != null) { pulseMinimaPending = false; Design.pulse(minimaBalView, 0xFFFFFFFF); }
        if (pulseEthPending && ethBalView != null) {
            pulseEthPending = false;
            Design.pulse(ethBalView, Design.ACCENT());
            for (TextView t : tokenBalViews) Design.pulse(t, Design.ACCENT());
        }
    }

    // ---- Market tab (advanced: full order book + publish your own) ----

    private void renderMarketTab(LinearLayout col) {
        TextView hint = new TextView(this);
        hint.setText("The live order book. Tap a price to trade, or publish your own offer.");
        hint.setTextColor(Design.DIM()); hint.setTextSize(12.5f); hint.setTypeface(Design.sans()); hint.setPadding(0, dp(2), 0, dp(2));
        col.addView(hint);

        LinearLayout obHeader = new LinearLayout(this);
        obHeader.setOrientation(LinearLayout.HORIZONTAL);
        obHeader.setGravity(Gravity.CENTER_VERTICAL);
        obHeader.setPadding(0, dp(14), 0, dp(2));
        TextView obTitle = new TextView(this);
        obTitle.setText("Order book  ·  " + orderBook.size() + " live");
        obTitle.setTextColor(Design.TEXT()); obTitle.setTextSize(16f); obTitle.setTypeface(Design.sansBold());
        obHeader.addView(obTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Design.pill(this, "Refresh", Design.SURFACE2(), Design.DIM());
        refresh.setOnClickListener(v -> scanOrderBook());
        obHeader.addView(refresh);
        col.addView(obHeader);

        if (orderBook.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(paired ? "No live orders yet. Publish one, or wait for a counterparty." : "Connect your node to see the order book.");
            empty.setTextColor(Design.DIM()); empty.setTextSize(12.5f); empty.setTypeface(Design.sans()); empty.setPadding(dp(2), dp(6), dp(2), 0);
            col.addView(empty);
        } else {
            orderLadder(col);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setClipChildren(false); btns.setClipToPadding(false);   // don't clip the CTA elevation shadow
        TextView editBtn = button("Edit my order");
        editBtn.setBackground(Design.roundBg(this, Design.SURFACE2(), 14));
        editBtn.setTextColor(Design.TEXT());
        editBtn.setOnClickListener(v -> editOrderDialog());
        TextView publish = button("Publish");
        publish.setOnClickListener(v -> publishOrder());
        LinearLayout.LayoutParams e1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        e1.rightMargin = dp(6); e1.topMargin = dp(14);
        LinearLayout.LayoutParams e2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        e2.leftMargin = dp(6); e2.topMargin = dp(14);
        btns.addView(editBtn, e1); btns.addView(publish, e2);
        col.addView(btns);

        if (orderStatus != null) {
            TextView st = new TextView(this);
            st.setText(orderStatus);
            st.setTextColor(orderStatus.startsWith("✓") ? Design.IN() : Design.DIM());
            st.setTextSize(12.5f); st.setTypeface(Design.sans()); st.setPadding(dp(2), dp(10), dp(2), 0);
            col.addView(st);
        }
    }

    private void showInfo(String title, String body) {
        dialog().setTitle(title).setMessage(body).setPositiveButton("Got it", null).show();
    }

    private void showWelcome() {
        prefs.edit().putBoolean("seen_welcome", true).apply();
        dialog()
                .setTitle("Welcome to minimaSwap")
                .setMessage("Swap MINIMA ⇄ USDT trustlessly across chains — no middleman ever holds your funds.\n\n"
                        + "To trade you'll need:\n"
                        + "•  Minima Core running, with minimaSwap enabled in Apps\n"
                        + "•  Some MINIMA to sell — or USDT plus a little ETH (for gas) to buy MINIMA\n\n"
                        + "Your keys are derived from your Minima node seed, so it's the same wallet on any device.\n\n"
                        + "Tabs:  Swap (quick trade)  ·  Wallet (your money)  ·  Activity (your swaps)  ·  Market (full order book).")
                .setPositiveButton("Get started", null)
                .show();
    }

    private void renderActiveSwaps(LinearLayout col) {
        if (db == null) return;
        java.util.Set<String> dismissed = dismissedSwaps();
        long now = System.currentTimeMillis();
        List<SwapDb.Swap> show = new java.util.ArrayList<>();
        int hidden = 0;
        for (SwapDb.Swap s : db.allSwaps()) {
            if (dismissed.contains(s.hash) || (isTerminal(s) && now - s.updated > TERMINAL_GRACE_MS)) { hidden++; continue; }
            show.add(s);
        }
        if (show.isEmpty() && hidden == 0) return;

        TextView title = new TextView(this);
        title.setText("Your swaps");
        title.setTextColor(Design.TEXT()); title.setTextSize(16f); title.setTypeface(Design.sansBold());
        title.setPadding(0, dp(22), 0, dp(2));
        col.addView(title);
        for (SwapDb.Swap s : show) col.addView(swapCard(s));
        if (hidden > 0) {
            TextView h = new TextView(this);
            h.setText(hidden + " finished swap" + (hidden == 1 ? "" : "s") + " hidden — tap for history");
            h.setTextColor(Design.DIM()); h.setTextSize(11.5f); h.setTypeface(Design.sans()); h.setPadding(dp(2), dp(6), 0, dp(2));
            h.setOnClickListener(v -> openHistory(0));
            col.addView(h);
        }
    }

    // ============================================================ Activity tab (live swaps + history)

    /** Jump to the Activity tab and select a sub-tab (0 = my swaps, 1 = market). Always re-renders so the
     *  sub-tab pills switch even when we're already on Activity. */
    private void openHistory(int tab) {
        boolean switching = currentTab != TAB_ACTIVITY;
        historyTab = tab;
        currentTab = TAB_ACTIVITY;
        if (switching) animateTab = true;
        updateTabBarSelection();
        render();
    }

    private void renderActivityTab(LinearLayout col) {
        renderActiveSwaps(col);   // live/resumable swap cards + "N finished hidden" link (empty → adds nothing)

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(0, dp(18), 0, dp(8));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = dp(8);
        TextView t0 = Design.pill(this, "My swaps", historyTab == 0 ? Design.ACCENT() : Design.SURFACE2(), historyTab == 0 ? Design.ON_ACCENT() : Design.DIM());
        t0.setOnClickListener(v -> openHistory(0));
        TextView t1 = Design.pill(this, "Market history", historyTab == 1 ? Design.ACCENT() : Design.SURFACE2(), historyTab == 1 ? Design.ON_ACCENT() : Design.DIM());
        t1.setOnClickListener(v -> openHistory(1));
        tabs.addView(t0, gap); tabs.addView(t1);
        col.addView(tabs);

        if (historyTab == 0) mySwapsList(col); else marketView(col);
    }

    private void mySwapsList(LinearLayout col) {
        if (db == null) return;
        java.util.List<SwapDb.Swap> all = db.allSwaps();
        if (all.isEmpty()) { col.addView(dimNote("No swaps yet — your completed and refunded swaps will appear here.")); return; }
        for (SwapDb.Swap s : all) col.addView(historySwapCard(s));
    }

    private LinearLayout historySwapCard(SwapDb.Swap s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.card(this, 14));
        c.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8); c.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView line = new TextView(this);
        line.setText(s.sellAmount + " " + s.sellToken + "  →  " + s.buyAmount + " " + s.buyToken);
        line.setTextColor(Design.TEXT()); line.setTextSize(14f); line.setTypeface(Design.mono());
        top.addView(line, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Design.pill(this, statusLabel(s), statusBg(s), statusFg(s)));
        c.addView(top);

        TextView meta = new TextView(this);
        String when = android.text.format.DateUtils.getRelativeTimeSpanString(
                s.created, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
        String cp = (s.counterparty == null || s.counterparty.isEmpty()) ? "" : "  ·  " + shortAddr(s.counterparty);
        meta.setText(when + "  ·  " + s.role.toLowerCase() + cp);
        meta.setTextColor(Design.DIM2()); meta.setTextSize(11.5f); meta.setTypeface(Design.sans()); meta.setPadding(0, dp(5), 0, 0);
        c.addView(meta);

        TextView detail = new TextView(this);
        detail.setText(statusDetail(s));
        detail.setTextColor(Design.DIM()); detail.setTextSize(12f); detail.setTypeface(Design.sans()); detail.setPadding(0, dp(3), 0, 0);
        c.addView(detail);

        c.setOnClickListener(v -> checkNow(s.hash));   // tap → live inspect detail
        Design.pressable(c);
        return c;
    }

    private void marketView(LinearLayout col) {
        if (db == null) return;
        java.util.List<SwapDb.MarketTrade> chartData = db.executedTrades(200);
        java.util.List<SwapDb.MarketTrade> recent = db.recentTrades(50);

        MarketChartView chart = new MarketChartView(this);
        chart.setBackground(Design.card(this, 14));
        chart.setData(chartData);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        clp.topMargin = dp(6); chart.setLayoutParams(clp);
        col.addView(chart);

        TextView stats = new TextView(this);
        String last = chartData.isEmpty() ? "—" : fmtPrice(chartData.get(chartData.size() - 1).price);
        stats.setText("last " + last + " USDT/MINIMA  ·  " + countByStatus(recent) + "  ·  price only (buy/sell not shown)");
        stats.setTextColor(Design.DIM2()); stats.setTextSize(11f); stats.setTypeface(Design.sans()); stats.setPadding(dp(2), dp(8), 0, dp(6));
        col.addView(stats);

        if (recent.isEmpty()) {
            col.addView(dimNote("No market trades observed yet. minimaSwap records swaps network-wide (this app + the original miniSwap) as they happen — it can't backfill, so history builds from now on."));
            return;
        }
        for (SwapDb.MarketTrade t : recent) col.addView(tradeRow(t));
    }

    private LinearLayout tradeRow(SwapDb.MarketTrade t) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Design.card(this, 12));
        r.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6); r.setLayoutParams(lp);

        TextView when = new TextView(this);
        when.setText(android.text.format.DateUtils.getRelativeTimeSpanString(
                t.observedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString());
        when.setTextColor(Design.DIM2()); when.setTextSize(11f); when.setTypeface(Design.sans());
        r.addView(when, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView price = new TextView(this);
        price.setText(fmtPrice(t.price)); price.setTextColor(Design.TEXT()); price.setTextSize(14f); price.setTypeface(Design.mono());
        r.addView(price);

        TextView size = new TextView(this);
        size.setText("  " + abbrev(parseD(t.sizeMinima, 0)) + " M"); size.setTextColor(Design.DIM()); size.setTextSize(12f); size.setTypeface(Design.mono());
        size.setPadding(dp(8), 0, dp(8), 0);
        r.addView(size);

        boolean exec = SwapDb.MT_EXECUTED.equals(t.status), ref = SwapDb.MT_REFUNDED.equals(t.status);
        int bg = exec ? Design.IN() : (ref ? Design.RED() : Design.SURFACE2());
        int fg = (exec || ref) ? Design.ON_ACCENT() : Design.DIM();
        r.addView(Design.pill(this, exec ? "filled" : ref ? "cancelled" : "open", bg, fg));
        return r;
    }

    private String countByStatus(java.util.List<SwapDb.MarketTrade> ts) {
        int ex = 0, op = 0;
        for (SwapDb.MarketTrade t : ts) {
            if (SwapDb.MT_EXECUTED.equals(t.status)) ex++;
            else if (SwapDb.MT_OPEN.equals(t.status)) op++;
        }
        return ex + " filled · " + op + " open";
    }

    private TextView dimNote(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Design.DIM()); t.setTextSize(12.5f); t.setTypeface(Design.sans());
        t.setLineSpacing(dp(3), 1f); t.setPadding(dp(2), dp(12), dp(2), 0);
        return t;
    }

    private static boolean isTerminal(SwapDb.Swap s) {
        return SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status);
    }

    private java.util.Set<String> dismissedSwaps() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String h : prefs.getString("dismissed_swaps", "").split(",")) if (!h.isEmpty()) set.add(h);
        return set;
    }

    private void dismissSwap(String hash) {
        java.util.Set<String> set = dismissedSwaps();
        set.add(hash);
        prefs.edit().putString("dismissed_swaps", android.text.TextUtils.join(",", set)).apply();
        render();
    }

    /** Hide a still-open swap card (e.g. one stuck because its lock never landed). UI-ONLY: the engine keeps
     *  driving the underlying swap, so any funds actually locked on-chain still auto-refund at their timelock. */
    private void confirmHideSwap(String hash) {
        modalOpen = true;
        dialog()
                .setTitle("Hide this swap?")
                .setMessage("This only removes the card. If any funds were locked on-chain they still auto-refund at the timelock — hiding doesn't touch them.")
                .setPositiveButton("Hide", (d, w) -> dismissSwap(hash))
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    private LinearLayout swapCard(SwapDb.Swap s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.card(this, 14));
        c.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8); c.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView line = new TextView(this);
        line.setText(s.sellAmount + " " + s.sellToken + "  →  " + s.buyAmount + " " + s.buyToken);
        line.setTextColor(Design.TEXT()); line.setTextSize(14f); line.setTypeface(Design.mono());
        top.addView(line, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Design.pill(this, statusLabel(s), statusBg(s), statusFg(s)));
        {
            TextView x = Design.pill(this, "✕", Design.SURFACE2(), Design.DIM());
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            xlp.leftMargin = dp(6); x.setLayoutParams(xlp);
            // terminal → dismiss immediately; still-open (e.g. a stuck swap whose lock never landed) → confirm first.
            x.setOnClickListener(v -> { if (isTerminal(s)) dismissSwap(s.hash); else confirmHideSwap(s.hash); });
            top.addView(x);
        }
        c.addView(top);

        TextView detail = new TextView(this);
        detail.setText(statusDetail(s));
        detail.setTextColor(Design.DIM()); detail.setTextSize(12.5f); detail.setTypeface(Design.sans()); detail.setPadding(0, dp(5), 0, 0);
        c.addView(detail);

        TextView meta = new TextView(this);
        meta.setText(s.role.toLowerCase() + "  ·  " + countdown(s));
        meta.setTextColor(Design.DIM2()); meta.setTextSize(11.5f); meta.setTypeface(Design.sans()); meta.setPadding(0, dp(3), 0, 0);
        c.addView(meta);

        // surface a logged reject/error reason inline (so a stuck swap isn't a black box)
        String err = swapErrorNote(s.hash);
        if (err != null) {
            TextView e = new TextView(this);
            e.setText("⚠ " + err);
            e.setTextColor(Design.RED()); e.setTextSize(12f); e.setPadding(0, dp(4), 0, 0);
            c.addView(e);
        }

        // a manual "Check now" that runs a live on-chain inspection AND advances the swap
        if (!SwapDb.ST_COMPLETE.equals(s.status) && !SwapDb.ST_REFUNDED.equals(s.status)) {
            TextView check = Design.pill(this, "Check now", Design.SURFACE2(), Design.TEXT());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(8); check.setLayoutParams(clp);
            check.setOnClickListener(v -> checkNow(s.hash));
            c.addView(check);
        }
        return c;
    }

    /** First logged reject/error reason for a swap, or null. */
    private String swapErrorNote(String hash) {
        if (db == null) return null;
        for (SwapDb.Event e : db.getEvents(hash)) {
            String n = e.note == null ? "" : e.note.toLowerCase();
            if (n.contains("mismatch") || n.contains("invalid") || n.contains("incorrect")
                    || n.contains("too close") || n.contains("fail")) return e.note;
        }
        return null;
    }

    /** Force a poll (advance) + run the live inspector and show the report. */
    private void checkNow(String hash) {
        toast("Checking…");
        if (engine == null) return;
        engine.poll();
        refreshBalances(true);
        engine.inspect(hash, lines -> showReport(hash, lines));
    }

    private void showReport(String hash, java.util.List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n\n");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView t = new TextView(this);
        t.setText(sb.toString().trim());
        t.setTextColor(Design.TEXT()); t.setTextSize(13f); t.setTextIsSelectable(true);
        box.addView(t);
        modalOpen = true;
        dialog()
                .setTitle("Swap status")
                .setView(wrapScroll(box))
                .setPositiveButton("Check again", (d, w) -> checkNow(hash))
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
    }

    /** Plain-language description of where a swap is, so "waiting" isn't ambiguous. */
    private String statusDetail(SwapDb.Swap s) {
        boolean initiator = "INITIATOR".equals(s.role);
        switch (s.status) {
            case SwapDb.ST_STARTED:
                return "Locked your " + s.sellAmount + " " + s.sellToken + " — waiting for the counterparty to lock their side.";
            case SwapDb.ST_LOCKED:
                return initiator ? "Counterparty locked — claiming your funds next."
                        : "You locked your side — waiting for the counterparty to reveal the secret.";
            case SwapDb.ST_CLAIMING:
                return "Counterparty locked — claiming your " + s.buyAmount + " " + s.buyToken + " now.";
            case SwapDb.ST_COMPLETE:
                return "Done — received " + s.buyAmount + " " + s.buyToken + ".";
            case SwapDb.ST_REFUNDED:
                return "Timed out — your " + s.sellAmount + " " + s.sellToken + " was refunded.";
            default:
                return "";
        }
    }

    private String statusLabel(SwapDb.Swap s) {
        switch (s.status) {
            case SwapDb.ST_STARTED:  return "waiting";
            case SwapDb.ST_LOCKED:   return "locked";
            case SwapDb.ST_CLAIMING: return "claiming";
            case SwapDb.ST_COMPLETE: return "complete";
            case SwapDb.ST_REFUNDED: return "refunded";
            default: return s.status.toLowerCase();
        }
    }
    private int statusBg(SwapDb.Swap s) {
        if (SwapDb.ST_COMPLETE.equals(s.status)) return Design.IN();
        if (SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status)) return Design.RED();
        return Design.SURFACE2();
    }
    private int statusFg(SwapDb.Swap s) {
        if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status))
            return Design.ON_ACCENT();
        return Design.DIM();
    }

    /** Time left on MY locked leg (the one I'd refund). Minima leg → blocks; ETH leg → wall-clock. */
    private String countdown(SwapDb.Swap s) {
        if (SwapDb.ST_COMPLETE.equals(s.status)) return "done";
        if (SwapDb.ST_REFUNDED.equals(s.status)) return "reclaimed";
        if (s.myTimelock <= 0) return "";
        if (s.myLegIsMinima) {
            return "refundable at block " + s.myTimelock;
        }
        long now = System.currentTimeMillis() / 1000L;
        long left = s.myTimelock - now;
        if (left <= 0) return "refund window open";
        long mins = left / 60;
        return "refundable in ~" + mins + " min";
    }

    /**
     * The order book as centred two-sided rows — one per maker: bidSize · bidPrice │ askPrice · askSize,
     * maker tag above. Tap the bid half to SELL MINIMA, the ask half to BUY MINIMA. Best bid/ask highlighted.
     */
    /** {Order maker, Order.Level} rows for one side across the live book, best-price-first (sell→bids DESC,
     *  buy→asks ASC). Legacy single-price makers contribute one synthetic level via effectiveBids/Asks.
     *  excludeMine drops my own orders (the sweep planner never takes my own liquidity). */
    private java.util.List<Object[]> aggSide(String sym, boolean sell, boolean excludeMine) {
        java.util.List<Object[]> agg = new java.util.ArrayList<>();
        for (Order o : orderBook.values()) {
            if (excludeMine && isMine(o)) continue;
            for (Order.Level l : (sell ? o.effectiveBids(sym) : o.effectiveAsks(sym))) agg.add(new Object[]{o, l});
        }
        java.util.Collections.sort(agg, (a, b) -> sell
                ? Double.compare(((Order.Level) b[1]).price, ((Order.Level) a[1]).price)
                : Double.compare(((Order.Level) a[1]).price, ((Order.Level) b[1]).price));
        return agg;
    }

    private void orderLadder(LinearLayout col) {
        final String sym = "USDT";
        // Display keeps my own orders (tagged "you"); the sweep planner calls aggSide with excludeMine=true.
        java.util.List<Object[]> bidsAgg = aggSide(sym, true, false);
        java.util.List<Object[]> asksAgg = aggSide(sym, false, false);
        if (bidsAgg.isEmpty() && asksAgg.isEmpty()) return;

        double bestBid = bidsAgg.isEmpty() ? 0 : ((Order.Level) bidsAgg.get(0)[1]).price;
        double bestAsk = asksAgg.isEmpty() ? 0 : ((Order.Level) asksAgg.get(0)[1]).price;
        if (bestBid > 0 && bestAsk > 0) {
            TextView sp = new TextView(this);
            sp.setText("spread " + fmtPrice(bestAsk - bestBid) + " " + sym + "  ·  " + sym + " per MINIMA, size in MINIMA");
            sp.setTextColor(Design.DIM2()); sp.setTextSize(11f); sp.setTypeface(Design.sans()); sp.setPadding(dp(2), dp(4), 0, dp(4));
            col.addView(sp);
        }

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setPadding(dp(6), dp(4), dp(6), dp(2));
        TextView lL = new TextView(this); lL.setText("SELL MINIMA (bid)"); lL.setTextColor(Design.IN()); lL.setTextSize(11f); lL.setTypeface(Design.sansBold()); lL.setLetterSpacing(0.04f);
        TextView lR = new TextView(this); lR.setText("BUY MINIMA (ask)"); lR.setTextColor(Design.RED()); lR.setTextSize(11f); lR.setTypeface(Design.sansBold()); lR.setLetterSpacing(0.04f); lR.setGravity(Gravity.END);
        legend.addView(lL, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        legend.addView(lR, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(legend);

        int rows = Math.max(bidsAgg.size(), asksAgg.size());
        int shown = Math.min(rows, 12);
        for (int i = 0; i < shown; i++) {
            Object[] bidRow = i < bidsAgg.size() ? bidsAgg.get(i) : null;
            Object[] askRow = i < asksAgg.size() ? asksAgg.get(i) : null;
            col.addView(depthRow(sym, bidRow, askRow, i == 0));
        }
        if (rows > shown) {
            TextView more = new TextView(this);
            more.setText("+ " + (rows - shown) + " more level" + (rows - shown == 1 ? "" : "s"));
            more.setTextColor(Design.DIM2()); more.setTextSize(11f); more.setTypeface(Design.sans()); more.setPadding(dp(2), dp(6), 0, 0);
            col.addView(more);
        }
    }

    /** One depth row: [bid half]│[ask half] at rank i (the two halves may be from different makers). */
    private LinearLayout depthRow(String sym, Object[] bidMr, Object[] askMr, boolean best) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(best ? Design.stroked(this, Design.SURFACE2(), 12) : Design.card(this, 12));
        box.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6); box.setLayoutParams(blp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = depthHalf(sym, bidMr, true, best);
        LinearLayout right = depthHalf(sym, askMr, false, best);
        TextView div = new TextView(this); div.setText("│"); div.setTextColor(Design.DIM2()); div.setTextSize(14f); div.setPadding(dp(8), 0, dp(8), 0);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(div);
        row.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        return box;
    }

    /** One side of a depth row: the priced quote + a small maker tag; tappable to take THAT level. */
    private LinearLayout depthHalf(String sym, Object[] mr, boolean isBid, boolean best) {
        LinearLayout half = new LinearLayout(this);
        half.setOrientation(LinearLayout.VERTICAL);
        if (mr == null) {
            half.addView(quoteHalf("—", "—", isBid ? Design.IN() : Design.RED(), false, isBid, best));
            return half;
        }
        final Order maker = (Order) mr[0];
        final Order.Level lvl = (Order.Level) mr[1];
        // display clamp: never advertise more than the maker's live balance can back (guard/chain still enforce)
        final double cap = isBid ? (lvl.price > 0 ? Math.min(lvl.amount, maker.usdtAvail / lvl.price) : 0)
                                 : Math.min(lvl.amount, maker.minimaAvail);
        half.addView(isBid
                ? quoteHalf(abbrev(cap), fmtPrice(lvl.price), Design.IN(), best, true, best)
                : quoteHalf(fmtPrice(lvl.price), abbrev(cap), Design.RED(), best, false, best));
        boolean mine = isMine(maker);
        TextView tag = new TextView(this);
        tag.setText(mine ? "you" : shortAddr(maker.signerPk));
        tag.setTextColor(mine ? Design.ACCENT() : Design.DIM2()); tag.setTextSize(9.5f); tag.setTypeface(Design.sans());
        tag.setGravity(isBid ? Gravity.START : Gravity.END);
        half.addView(tag);
        if (!mine && cap > 0) {
            half.setOnClickListener(v -> takeOrderDialog(maker, sym, isBid, lvl.price, cap));
            Design.pressable(half);
        }
        return half;
    }

    private boolean isMine(Order o) {
        return o != null && identity != null && o.commsPublicId != null && o.commsPublicId.equals(identity.publicId());
    }

    /** Pinned top-of-book: the highest bid and lowest ask across all makers (may be two different makers). */
    /** Half of a depth row. leftHalf=true → [size price] right-aligned (price by the centre); else [price size].
     *  big=true bumps the font for the pinned BEST MARKET card. */
    private LinearLayout quoteHalf(String a, String b, int priceColor, boolean best, boolean leftHalf, boolean big) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity((leftHalf ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        String sizeText = leftHalf ? a : b;
        String priceText = leftHalf ? b : a;

        TextView size = new TextView(this);
        size.setText(sizeText); size.setTextColor(Design.DIM()); size.setTextSize(big ? 13f : 12f); size.setTypeface(Design.mono());
        TextView price = new TextView(this);
        price.setText(priceText); price.setTextColor(priceColor); price.setTextSize(big ? 18.5f : 15f);
        price.setTypeface(best ? Design.monoBold() : Design.mono());

        if (leftHalf) { size.setPadding(0, 0, dp(8), 0); h.addView(size); h.addView(price); }
        else { price.setPadding(dp(8), 0, 0, 0); h.addView(price); h.addView(size); }
        return h;
    }

    /** Compact MINIMA size: 300 / 12k / 1.2M. */
    private static String abbrev(double v) {
        if (v <= 0) return "—";
        if (v >= 1_000_000) return trimNum(v / 1_000_000) + "M";
        if (v >= 1_000) return trimNum(v / 1_000) + "k";
        return trimNum(v);
    }
    private static String trimNum(double v) {
        java.math.BigDecimal b = java.math.BigDecimal.valueOf(v).setScale(v < 10 ? 2 : 1, RoundingMode.DOWN).stripTrailingZeros();
        return b.toPlainString();
    }
    /** Price formatting that absorbs float subtraction noise (e.g. a computed spread). */
    private static String fmtPrice(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private LinearLayout walletCard(String title, String big, String sub, int bigColor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.card(this, 16));
        c.setPadding(dp(16), dp(15), dp(16), dp(15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); c.setLayoutParams(lp);
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Design.DIM()); t.setTextSize(12f); t.setTypeface(Design.sans());
        TextView v = new TextView(this); v.setText(big); v.setTextColor(bigColor); v.setTextSize(22f); v.setTag(TAG_BAL);
        v.setTypeface(Design.monoBold()); v.setPadding(0, dp(4), 0, 0);   // balances in tabular mono
        c.addView(t); c.addView(v);
        if (sub != null) {
            TextView s = new TextView(this); s.setText(sub); s.setTextColor(Design.DIM()); s.setTextSize(12.5f); s.setTypeface(Design.sans()); s.setPadding(0, dp(6), 0, 0);
            c.addView(s);
        }
        return c;
    }

    private LinearLayout kv(String k, String val) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(4), dp(9), dp(4), dp(3));
        TextView a = new TextView(this); a.setText(k); a.setTextColor(Design.DIM()); a.setTextSize(14f); a.setTypeface(Design.sans());
        TextView v = new TextView(this); v.setText(val); v.setTextColor(Design.TEXT()); v.setTextSize(14f); v.setGravity(Gravity.END); v.setTag(TAG_BAL); v.setTypeface(Design.mono());
        r.addView(a, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private TextView button(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Design.ON_ACCENT());
        t.setBackground(Design.ripple(Design.gradientCta(this)));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15f); t.setTypeface(Design.sansBold());
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setElevation(dp(3));
        return t;   // ripple background provides the press feedback
    }

    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    private TextView buildPairingBanner() {
        TextView t = new TextView(this);
        t.setText("Enable minimaSwap in Minima Core → Apps to connect to your node.");
        t.setTextColor(Design.ON_ACCENT()); t.setBackgroundColor(Design.ACCENT());
        t.setPadding(dp(16), dp(10), dp(16), dp(10)); t.setTextSize(13f); t.setTypeface(Design.sansBold());
        return t;
    }

    // ---- receive / fund + key export ----

    private void receiveDialog() {
        if (ethAddr == null) { toast("ETH wallet not ready yet"); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView addr = new TextView(this);
        addr.setText(ethAddr);
        addr.setTextColor(Design.TEXT()); addr.setTextSize(13f);
        addr.setTypeface(Design.mono());
        addr.setTextIsSelectable(true);
        addr.setGravity(Gravity.CENTER);
        box.addView(addr);

        Bitmap qr = QrUtil.qr(ethAddr, dp(220));
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            int s = dp(220);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(s, s);
            qlp.topMargin = dp(16);
            iv.setLayoutParams(qlp);
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            iv.setBackgroundColor(0xFFFFFFFF);   // white quiet-zone so the dark theme doesn't break scanning
            box.addView(iv);
        }

        TextView note = new TextView(this);
        note.setText("Same address on all EVM networks — fund it with " + net.label + " ETH and tokens.");
        note.setTextColor(Design.DIM2()); note.setTextSize(12f); note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(14), 0, 0);
        box.addView(note);

        dialog()
                .setTitle("Receive / Fund · " + net.label)
                .setView(wrapScroll(box))
                .setPositiveButton("Copy address", (d, w) -> copy(ethAddr, "ETH address copied"))
                .setNegativeButton("Close", null)
                .show();
    }

    private void exportKeyDialog() {
        if (!wallet.ready()) { toast("ETH wallet not ready yet"); return; }
        dialog()
                .setTitle("Export ETH private key")
                .setMessage("This key controls your ETH funds. Anyone who sees it can take them. It is also "
                        + "derived from your Minima node seed. Never share it or type it into a website.")
                .setPositiveButton("Reveal key", (d, w) -> revealKeyDialog())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void revealKeyDialog() {
        final String pk = wallet.privateKeyHex();
        if (pk == null) { toast("ETH wallet not ready yet"); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView warn = new TextView(this);
        warn.setText("⚠ Keep this secret.");
        warn.setTextColor(Design.RED()); warn.setTextSize(12.5f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);

        TextView key = new TextView(this);
        key.setText(pk);
        key.setTextColor(Design.TEXT()); key.setTextSize(13f);
        key.setTypeface(Design.mono());
        key.setTextIsSelectable(true);
        box.addView(key);

        dialog()
                .setTitle("ETH private key")
                .setView(wrapScroll(box))
                .setPositiveButton("Copy key", (d, w) -> copy(pk, "Private key copied"))
                .setNegativeButton("Close", null)
                .show();
    }

    private String shortAddr(String a) {
        if (a == null || a.length() < 12) return a;
        return a.substring(0, 8) + "…" + a.substring(a.length() - 6);
    }

    private void copy(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("minimaSwap", text)); toast(toast); }
    }

    private static String trim(double v) { return Util.tidyAmount(BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()); }
    /** Like trim() but first rounds away binary float noise (0.00597999999… → 0.00598) — for computed ladder fields. */
    private static String trimSig(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return Util.tidyAmount(new BigDecimal(v, new java.math.MathContext(10)).stripTrailingZeros().toPlainString());
    }
    private static double parseD(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }

    private int dp(int v) { return Design.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---- notifications ----

    private final SwapEngine.Notifier notifier = new SwapEngine.Notifier() {
        @Override public void notify(String title, String body) { ui.post(() -> postNotification(title, body)); }
        @Override public void onSwapsChanged() { ui.post(() -> {
            // Drop the sticky action line ("Buy started — maker notified" / "Sweep done…") once everything has settled.
            if (sweepRun == null && activeSwapCount() == 0) orderStatus = null;
            render();
            if (!checkForNewCompletion()) refreshBalances(false);   // completion → pulse+reload; other moves → silent refresh
        }); }
    };

    private void postNotification(String title, String body) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            toast(title + " — " + body);
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationCompat.Builder n = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify(notifId.incrementAndGet(), n.build());
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "Swaps", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
    }

    /** A no-frills TextWatcher that just runs a callback on every change. */
    private static final class SimpleWatcher implements android.text.TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(android.text.Editable s) { r.run(); }
    }
}
