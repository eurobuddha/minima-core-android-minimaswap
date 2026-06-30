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
import org.minimarex.comms.CommsTransport;
import org.minimarex.comms.CryptoProvider;
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
    private static final String[] PAIR_TOKENS = {"USDT"};
    private static final long WATCH_INTERVAL_MS = 90_000;

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

    // wallet state shown on the home screen
    private String minimaBal = "…";
    private String ethAddr = null;
    private String ethErr = null;
    private String ethBal = "…";
    private final LinkedHashMap<String, String> tokenBals = new LinkedHashMap<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger notifId = new AtomicInteger(1000);

    private LinearLayout root;
    private ScrollView scroller;
    private View pairingBanner;
    private boolean watching = false;
    private boolean modalOpen = false;   // suppress background render() while a dialog (with inputs) is open
    private boolean serviceStarted = false;
    /** True while the Activity is resumed — the SwapService stands down then so only one polls. */
    public static volatile boolean FOREGROUND = false;

    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            if (engine != null) engine.poll();
            refreshBalances(false);                 // keep balances current without a restart
            ui.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        net = EthNet.MAINNET;
        rpc = new EthRpc(rpcUrl(net));
        db = new SwapDb(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        render();
        node = new NodeApi(this, this::onPaired);
        minima = new MinimaHtlc(node);
        engine = new SwapEngine(node, minima, db, wallet, ui, notifier);
        engine.setNetwork(rpc, net);
        engine.setMyOrder(loadOrder());
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;     // Activity polls while visible; SwapService stands down
        startWatcher();
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;    // hand off to the background SwapService
        stopWatcher();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
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
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

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
            engine.poll();
            startSwapService();
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
                minimaBal = t == null ? "0" : t.optString("sendable", t.optString("confirmed", "0"));
                render();
            }
            @Override public void onError(String m) { minimaBal = "—"; render(); }
        });
    }

    /** Refresh both wallet balances. showLoading=false → silent (periodic) update, no "…" flicker. */
    private void refreshBalances(boolean showLoading) {
        if (!paired) return;
        fetchMinimaBalance();
        if (wallet.ready()) fetchEthBalances(showLoading);
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
                if (ferr != null) ethErr = ferr;
                render();
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
    }

    private void publishOrder() {
        if (identity == null || myMinimaPk == null) { toast("Still connecting to your node…"); return; }
        if (!wallet.ready()) { toast("ETH wallet not ready yet"); return; }
        Order o = loadOrder();
        o.minimaPublicKey = myMinimaPk;
        o.ethAddress = wallet.address();
        o.minimaAvail = parseD(Util.tidyAmount(minimaBal), 0);          // liquidity snapshot (kept current
        o.usdtAvail = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0); // by the ~90s balance auto-refresh)
        boolean anyEnabled = false;
        for (Order.Pair p : o.pairs.values()) if (p.enable) { anyEnabled = true; break; }
        if (!anyEnabled) { toast("Enable at least one pair in your order first"); return; }
        engine.setMyOrder(o);
        orderStatus = "Publishing your order…";
        render();
        SwapOrderBook.publish(node, ls, identity, o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { orderStatus = "✓ Order published — others can now take it"; render(); ui.postDelayed(MainActivity.this::scanOrderBook, 2000); }
            @Override public void onFailed(String message) { orderStatus = "Publish failed: " + message; render(); }
        });
    }

    // ---- order config (persisted; drives both publish AND the responder match guard) ----

    private Order loadOrder() {
        Order o = new Order();
        String raw = prefs.getString("order_config", "");
        JSONObject cfg = null;
        try { if (!raw.isEmpty()) cfg = new JSONObject(raw); } catch (Exception ignore) {}
        for (String sym : PAIR_TOKENS) {
            Order.Pair p = new Order.Pair(false, 1.0, 1.0, 1.0);
            if (cfg != null) {
                JSONObject c = cfg.optJSONObject(sym);
                if (c != null) {
                    p.enable = c.optBoolean("en", false);
                    p.buy = c.optDouble("buy", 1.0);
                    p.sell = c.optDouble("sell", 1.0);
                    p.min = c.optDouble("min", 1.0);
                }
            }
            o.pairs.put(sym, p);
        }
        return o;
    }

    private void saveOrder(Order o) {
        try {
            JSONObject cfg = new JSONObject();
            for (Map.Entry<String, Order.Pair> e : o.pairs.entrySet()) {
                Order.Pair p = e.getValue();
                cfg.put(e.getKey(), new JSONObject().put("en", p.enable).put("buy", p.buy).put("sell", p.sell).put("min", p.min));
            }
            prefs.edit().putString("order_config", cfg.toString()).apply();
        } catch (Exception ignore) {}
        if (engine != null) engine.setMyOrder(o);
    }

    private void editOrderDialog() {
        final Order o = loadOrder();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText("Prices are the price of MINIMA in USDT (USDT per MINIMA). "
                + "buy = price others buy MINIMA from you (the higher / ask). "
                + "sell = price others sell MINIMA to you (the lower / bid). "
                + "min = smallest MINIMA per trade. Keep buy ≥ sell.");
        hint.setTextColor(Design.DIM); hint.setTextSize(12f); hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);

        final Map<String, SwitchCompat> en = new LinkedHashMap<>();
        final Map<String, EditText> buy = new LinkedHashMap<>(), sell = new LinkedHashMap<>(), min = new LinkedHashMap<>();
        for (String sym : PAIR_TOKENS) {
            Order.Pair p = o.pairs.get(sym);
            TextView t = new TextView(this); t.setText("MINIMA / " + sym); t.setTextColor(Design.TEXT); t.setTextSize(15f);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); t.setPadding(0, dp(12), 0, dp(4));
            box.addView(t);
            SwitchCompat sw = new SwitchCompat(this); sw.setText("Enabled"); sw.setTextColor(Design.DIM); sw.setChecked(p.enable);
            box.addView(sw); en.put(sym, sw);
            buy.put(sym, numRow(box, "buy MINIMA price (" + sym + ")", p.buy));
            sell.put(sym, numRow(box, "sell MINIMA price (" + sym + ")", p.sell));
            min.put(sym, numRow(box, "min MINIMA", p.min));
        }

        modalOpen = true;
        new AlertDialog.Builder(this)
                .setTitle("My order / rates")
                .setView(wrapScroll(box))
                .setPositiveButton("Save", (d, w) -> {
                    for (String sym : PAIR_TOKENS) {
                        Order.Pair p = o.pairs.get(sym);
                        p.enable = en.get(sym).isChecked();
                        p.buy = parseD(buy.get(sym).getText().toString(), p.buy);
                        p.sell = parseD(sell.get(sym).getText().toString(), p.sell);
                        p.min = parseD(min.get(sym).getText().toString(), p.min);
                    }
                    saveOrder(o);
                    toast("Order saved");
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> { modalOpen = false; render(); })
                .show();
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
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this); t.setText(label); t.setTextColor(Design.DIM); t.setTextSize(13f);
        EditText e = new EditText(this);
        decimalInput(e);
        e.setText(trim(value)); e.setTextColor(Design.TEXT); e.setTextSize(14f); e.setGravity(Gravity.END);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(r);
        return e;
    }

    // ---- take an order (guided swap start) ----

    /**
     * Take a maker's order. We always trade MINIMA, priced in USDT (USDT per MINIMA). You enter a MINIMA
     * amount; the USDT side is derived from the maker's price.
     *   - Sell MINIMA → you give MINIMA, receive USDT at the maker's SELL price (the lower / bid).
     *   - Buy MINIMA  → you give USDT, receive MINIMA at the maker's BUY price (the higher / ask).
     */
    private void takeOrderDialog(Order maker, String symbol, boolean sellMinima) {
        Order.Pair p = maker.pairs.get(symbol);
        if (p == null) return;
        final double price = sellMinima ? p.sell : p.buy;   // USDT per MINIMA: sell=bid(lower), buy=ask(higher)

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView info = new TextView(this);
        info.setText((sellMinima ? "Sell MINIMA → " + symbol : "Buy MINIMA ← " + symbol)
                + "\nPrice: " + trim(price) + " " + symbol + " per MINIMA"
                + "\nMin: " + trim(p.min) + " MINIMA");
        info.setTextColor(Design.DIM); info.setTextSize(13f); info.setPadding(0, 0, 0, dp(10));
        box.addView(info);

        final EditText amt = new EditText(this);
        amt.setHint("MINIMA to " + (sellMinima ? "sell" : "buy"));
        decimalInput(amt);
        amt.setTextColor(Design.TEXT); amt.setHintTextColor(Design.DIM2);
        box.addView(amt);

        final TextView out = new TextView(this);
        out.setTextColor(Design.ACCENT); out.setTextSize(14f); out.setPadding(0, dp(10), 0, 0);
        box.addView(out);

        amt.addTextChangedListener(new SimpleWatcher(() -> {
            String usdt = computeUsdt(amt.getText().toString(), price);
            out.setText(usdt == null ? "" : (sellMinima ? "You receive ≈ " : "You pay ≈ ") + usdt + " " + symbol);
        }));

        modalOpen = true;
        new AlertDialog.Builder(this)
                .setTitle((sellMinima ? "Sell MINIMA" : "Buy MINIMA") + " · " + Util.shorten(maker.signerPk))
                .setView(box)
                .setPositiveButton("Start swap", (d, w) -> {
                    String minima = amt.getText().toString().trim();
                    String usdt = computeUsdt(minima, price);
                    if (usdt == null) { toast("Enter a valid MINIMA amount"); return; }
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
        SwapEngine.StartCb cb = new SwapEngine.StartCb() {
            @Override public void ok(String hash) {
                orderStatus = "✓ Swap started — leg 1 locked. Watching for the counterparty.";
                render(); engine.poll();
            }
            @Override public void err(String msg) { orderStatus = "Swap failed: " + msg; render(); }
        };
        if (sellMinima) {
            // give MINIMA (minima), receive USDT (usdt) → MINIMA→ERC20
            engine.startMinimaToErc20(maker, minima, symbol, usdt, cb);
        } else {
            // give USDT (usdt), receive MINIMA (minima) → ERC20→MINIMA
            engine.startErc20ToMinima(maker, symbol, usdt, minima, cb);
        }
    }

    // ---- UI ----

    private void render() {
        // Never rebuild the view tree while a dialog with text inputs is open — a background render
        // (watcher / balance callback) restarts the IME and resets the cursor mid-typing. We re-render
        // once when the dialog dismisses.
        if (modalOpen) return;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("minimaSwap");
        brand.setTextColor(Design.TEXT); brand.setTextSize(22f); brand.setTypeface(brand.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chip = Design.pill(this, "Mainnet · real funds", Design.ACCENT, Design.ON_ACCENT);
        header.addView(chip);
        col.addView(header);

        TextView sub = new TextView(this);
        sub.setText("v" + BuildConfig.VERSION_NAME + "  ·  Cross-chain atomic swaps · MINIMA ↔ USDT");
        sub.setTextColor(Design.DIM2); sub.setTextSize(12.5f); sub.setPadding(0, dp(2), 0, dp(14));
        col.addView(sub);

        col.addView(walletCard("Minima", minimaBal + " MINIMA", null, Design.ACCENT));

        String addrLine = ethAddr == null ? (ethErr == null ? "deriving from node seed…" : "—") : shortAddr(ethAddr);
        LinearLayout ethCard = walletCard("Ethereum · " + net.label, ethBal, addrLine, Design.TEXT);
        if (ethAddr != null) ethCard.setOnClickListener(v -> receiveDialog());
        col.addView(ethCard);
        for (Map.Entry<String, String> e : tokenBals.entrySet()) col.addView(kv(e.getKey(), e.getValue()));

        if (ethAddr != null) {
            LinearLayout walletActions = new LinearLayout(this);
            walletActions.setOrientation(LinearLayout.HORIZONTAL);
            walletActions.setPadding(0, dp(8), 0, 0);
            TextView refreshBal = Design.pill(this, "↻  Refresh", Design.SURFACE2, Design.TEXT);
            refreshBal.setOnClickListener(v -> { toast("Refreshing balances…"); refreshBalances(true); });
            TextView fund = Design.pill(this, "⤓  Fund / QR", Design.SURFACE2, Design.TEXT);
            fund.setOnClickListener(v -> receiveDialog());
            TextView exportKey = Design.pill(this, "🔑  Export key", Design.SURFACE2, Design.DIM);
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
            err.setTextColor(Design.RED); err.setTextSize(12f); err.setPadding(dp(2), dp(8), dp(2), 0);
            col.addView(err);
        }

        // ---- Active swaps ----
        renderActiveSwaps(col);

        // ---- Order book ----
        LinearLayout obHeader = new LinearLayout(this);
        obHeader.setOrientation(LinearLayout.HORIZONTAL);
        obHeader.setGravity(Gravity.CENTER_VERTICAL);
        obHeader.setPadding(0, dp(22), 0, dp(2));
        TextView obTitle = new TextView(this);
        obTitle.setText("Order book  ·  " + orderBook.size() + " live");
        obTitle.setTextColor(Design.TEXT); obTitle.setTextSize(16f); obTitle.setTypeface(obTitle.getTypeface(), android.graphics.Typeface.BOLD);
        obHeader.addView(obTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Design.pill(this, "Refresh", Design.SURFACE2, Design.DIM);
        refresh.setOnClickListener(v -> scanOrderBook());
        obHeader.addView(refresh);
        col.addView(obHeader);

        if (orderBook.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(paired ? "No live orders yet. Publish one, or wait for a counterparty." : "Connect your node to see the order book.");
            empty.setTextColor(Design.DIM); empty.setTextSize(12.5f); empty.setPadding(dp(2), dp(6), dp(2), 0);
            col.addView(empty);
        } else {
            orderLadder(col);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView editBtn = button("Edit my order");
        editBtn.setBackground(Design.roundBg(this, Design.SURFACE2, 14));
        editBtn.setTextColor(Design.TEXT);
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
            st.setTextColor(orderStatus.startsWith("✓") ? Design.IN : Design.DIM);
            st.setTextSize(12.5f); st.setPadding(dp(2), dp(10), dp(2), 0);
            col.addView(st);
        }

        scroller.removeAllViews();
        scroller.addView(col);
    }

    private void renderActiveSwaps(LinearLayout col) {
        if (db == null) return;
        List<SwapDb.Swap> swaps = db.allSwaps();
        if (swaps.isEmpty()) return;
        TextView title = new TextView(this);
        title.setText("Your swaps");
        title.setTextColor(Design.TEXT); title.setTextSize(16f); title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(22), 0, dp(2));
        col.addView(title);
        for (SwapDb.Swap s : swaps) col.addView(swapCard(s));
    }

    private LinearLayout swapCard(SwapDb.Swap s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.roundBg(this, Design.SURFACE, 14));
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8); c.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView line = new TextView(this);
        line.setText(s.sellAmount + " " + s.sellToken + "  →  " + s.buyAmount + " " + s.buyToken);
        line.setTextColor(Design.TEXT); line.setTextSize(14f);
        top.addView(line, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Design.pill(this, statusLabel(s), statusBg(s), statusFg(s)));
        c.addView(top);

        TextView detail = new TextView(this);
        detail.setText(statusDetail(s));
        detail.setTextColor(Design.DIM); detail.setTextSize(12.5f); detail.setPadding(0, dp(4), 0, 0);
        c.addView(detail);

        TextView meta = new TextView(this);
        meta.setText(s.role.toLowerCase() + "  ·  " + countdown(s));
        meta.setTextColor(Design.DIM2); meta.setTextSize(11.5f); meta.setPadding(0, dp(3), 0, 0);
        c.addView(meta);

        // a manual "Check now" for immediate feedback (instead of waiting for the ~90s poll)
        if (!SwapDb.ST_COMPLETE.equals(s.status) && !SwapDb.ST_REFUNDED.equals(s.status)) {
            TextView check = Design.pill(this, "Check now", Design.SURFACE2, Design.TEXT);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(8); check.setLayoutParams(clp);
            check.setOnClickListener(v -> { toast("Checking…"); if (engine != null) engine.poll(); refreshBalances(true); });
            c.addView(check);
        }
        return c;
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
        if (SwapDb.ST_COMPLETE.equals(s.status)) return Design.IN;
        if (SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status)) return Design.RED;
        return Design.SURFACE2;
    }
    private int statusFg(SwapDb.Swap s) {
        if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status))
            return Design.ON_ACCENT;
        return Design.DIM;
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

    /** The order book as a two-column bid/ask ladder, best price first, with per-level MINIMA liquidity. */
    private void orderLadder(LinearLayout col) {
        final String sym = "USDT";
        java.util.List<Order> bids = new java.util.ArrayList<>();   // SELL MINIMA (maker's sell/bid price)
        java.util.List<Order> asks = new java.util.ArrayList<>();   // BUY MINIMA (maker's buy/ask price)
        for (Order o : orderBook.values()) {
            Order.Pair p = o.pairs.get(sym);
            if (p == null || !p.enable) continue;
            if (p.sell > 0) bids.add(o);
            if (p.buy > 0) asks.add(o);
        }
        java.util.Collections.sort(bids, (a, b) -> Double.compare(b.pairs.get(sym).sell, a.pairs.get(sym).sell)); // best (highest) first
        java.util.Collections.sort(asks, (a, b) -> Double.compare(a.pairs.get(sym).buy, b.pairs.get(sym).buy));   // best (lowest) first

        // spread = best ask − best bid (best buy price − best sell price)
        if (!bids.isEmpty() && !asks.isEmpty()) {
            double spread = asks.get(0).pairs.get(sym).buy - bids.get(0).pairs.get(sym).sell;
            TextView sp = new TextView(this);
            sp.setText("spread " + trim(spread) + " " + sym + "  ·  prices are " + sym + " per MINIMA (balances at publish)");
            sp.setTextColor(Design.DIM2); sp.setTextSize(11.5f); sp.setPadding(dp(2), dp(4), 0, dp(6));
            col.addView(sp);
        }

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(4); cols.setLayoutParams(clp);
        cols.addView(ladderColumn("SELL MINIMA (bid)", bids, sym, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cols.addView(ladderColumn("BUY MINIMA (ask)", asks, sym, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(cols);
    }

    private LinearLayout ladderColumn(String header, java.util.List<Order> orders, String sym, boolean sellMinima) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.roundBg(this, Design.SURFACE, 12));
        c.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView h = new TextView(this);
        h.setText(header);
        h.setTextColor(sellMinima ? Design.IN : Design.RED); h.setTextSize(12f);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD); h.setPadding(0, 0, 0, dp(4));
        c.addView(h);

        if (orders.isEmpty()) {
            TextView none = new TextView(this); none.setText("—");
            none.setTextColor(Design.DIM2); none.setTextSize(12f);
            c.addView(none);
            return c;
        }
        for (int i = 0; i < orders.size(); i++) {
            final Order o = orders.get(i);
            Order.Pair p = o.pairs.get(sym);
            double price = sellMinima ? p.sell : p.buy;
            double sizeMinima = sellMinima ? (price > 0 ? o.usdtAvail / price : 0) : o.minimaAvail;
            boolean mine = identity != null && o.commsPublicId != null && o.commsPublicId.equals(identity.publicId());

            TextView row = new TextView(this);
            row.setText(trim(price) + "  ·  " + abbrev(sizeMinima) + (mine ? "  (you)" : "  " + shortAddr(o.signerPk)));
            row.setTextColor(i == 0 ? Design.ACCENT : (mine ? Design.DIM2 : Design.DIM));
            row.setTextSize(12.5f);
            if (i == 0) row.setTypeface(row.getTypeface(), android.graphics.Typeface.BOLD);
            row.setPadding(0, dp(3), 0, dp(3));
            if (!mine) row.setOnClickListener(v -> takeOrderDialog(o, sym, sellMinima));
            c.addView(row);
        }
        return c;
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

    private LinearLayout walletCard(String title, String big, String sub, int bigColor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.roundBg(this, Design.SURFACE, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); c.setLayoutParams(lp);
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Design.DIM); t.setTextSize(12.5f);
        TextView v = new TextView(this); v.setText(big); v.setTextColor(bigColor); v.setTextSize(20f);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); v.setPadding(0, dp(3), 0, 0);
        c.addView(t); c.addView(v);
        if (sub != null) {
            TextView s = new TextView(this); s.setText(sub); s.setTextColor(Design.DIM2); s.setTextSize(12.5f); s.setPadding(0, dp(4), 0, 0);
            c.addView(s);
        }
        return c;
    }

    private LinearLayout kv(String k, String val) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(18), dp(6), dp(18), dp(6));
        TextView a = new TextView(this); a.setText(k); a.setTextColor(Design.DIM); a.setTextSize(14f);
        TextView v = new TextView(this); v.setText(val); v.setTextColor(Design.TEXT); v.setTextSize(14f); v.setGravity(Gravity.END);
        r.addView(a, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private TextView button(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Design.ON_ACCENT);
        t.setBackground(Design.roundBg(this, Design.ACCENT, 14));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15f); t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        return t;
    }

    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    private View buildPairingBanner() {
        TextView t = new TextView(this);
        t.setText("Enable minimaSwap in Minima Core → Apps to connect to your node.");
        t.setTextColor(Design.ON_ACCENT); t.setBackgroundColor(Design.ACCENT);
        t.setPadding(dp(16), dp(10), dp(16), dp(10)); t.setTextSize(13f);
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
        addr.setTextColor(Design.TEXT); addr.setTextSize(13f);
        addr.setTypeface(Typeface.MONOSPACE);
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
        note.setTextColor(Design.DIM2); note.setTextSize(12f); note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(14), 0, 0);
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("Receive / Fund · " + net.label)
                .setView(wrapScroll(box))
                .setPositiveButton("Copy address", (d, w) -> copy(ethAddr, "ETH address copied"))
                .setNegativeButton("Close", null)
                .show();
    }

    private void exportKeyDialog() {
        if (!wallet.ready()) { toast("ETH wallet not ready yet"); return; }
        new AlertDialog.Builder(this)
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
        warn.setTextColor(Design.RED); warn.setTextSize(12.5f); warn.setPadding(0, 0, 0, dp(10));
        box.addView(warn);

        TextView key = new TextView(this);
        key.setText(pk);
        key.setTextColor(Design.TEXT); key.setTextSize(13f);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextIsSelectable(true);
        box.addView(key);

        new AlertDialog.Builder(this)
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
    private static double parseD(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }

    private int dp(int v) { return Design.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---- notifications ----

    private final SwapEngine.Notifier notifier = new SwapEngine.Notifier() {
        @Override public void notify(String title, String body) { ui.post(() -> postNotification(title, body)); }
        @Override public void onSwapsChanged() { ui.post(MainActivity.this::render); }
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
