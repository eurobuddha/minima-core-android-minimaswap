package org.minimarex.minimaswap.swap;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable swap state — the native equivalent of the bridge MiniDapp's sql.js tables.
 *
 * Two layers:
 *  - The TRUSTLESS idempotency core (verbatim from upstream): {@code secrets} (every known preimage),
 *    an append-only {@code events} log keyed on the hashlock (HTLC_STARTED / CPTXN_SENT / CPTXN_COLLECT /
 *    CPTXN_EXPIRED), and {@code myhtlc} (what I requested when I initiated, so on collect I re-verify the
 *    counterparty locked the right amount/token before revealing the secret). The engine's guards
 *    (haveSent / haveCollect / haveSecret) read these so a restart never double-locks or double-claims.
 *  - A UX projection: {@code swaps}, one row per swap the engine keeps current for resumable cards. Not
 *    trusted for safety — purely what the home screen shows.
 *
 * State on restart is reconstructed from the chains plus this DB exactly like service.js does.
 */
public final class SwapDb {

    // ---- event names (verbatim from sql.js) ----
    public static final String EV_STARTED  = "HTLC_STARTED";   // I initiated + locked leg 1
    public static final String EV_CPSENT   = "CPTXN_SENT";     // I (responder) locked leg 2
    public static final String EV_COLLECT  = "CPTXN_COLLECT";  // I claimed/withdrew a leg (or a terminal error)
    public static final String EV_EXPIRED  = "CPTXN_EXPIRED";  // I refunded an expired leg / saw a secret revealed

    // ---- UX swap status ----
    public static final String ST_STARTED   = "STARTED";    // leg 1 locked, waiting for counterparty
    public static final String ST_LOCKED    = "LOCKED";     // both legs locked
    public static final String ST_CLAIMING  = "CLAIMING";   // claiming a leg
    public static final String ST_COMPLETE  = "COMPLETE";   // I have my funds
    public static final String ST_REFUNDED  = "REFUNDED";   // I refunded after timeout
    public static final String ST_ERROR     = "ERROR";

    private final Helper helper;

    public SwapDb(Context ctx) { helper = new Helper(ctx.getApplicationContext()); }

    // ================= secrets =================

    /** Store a preimage for its hashlock. Returns true if newly added (false if we already had it). */
    public synchronized boolean insertSecret(String hash, String secret) {
        if (hash == null || secret == null || hash.isEmpty() || secret.isEmpty()) return false;
        if (getSecret(hash) != null) return false;
        ContentValues v = new ContentValues();
        v.put("hash", norm(hash));
        v.put("secret", secret);
        v.put("added", System.currentTimeMillis());
        return helper.getWritableDatabase().insert("secrets", null, v) >= 0;
    }

    public synchronized String getSecret(String hash) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT secret FROM secrets WHERE hash=? LIMIT 1", new String[]{norm(hash)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    // ================= event log =================

    public synchronized void logEvent(String hash, String event, String token, String amount, String txnhash) {
        ContentValues v = new ContentValues();
        v.put("hash", norm(hash));
        v.put("event", event);
        v.put("token", token == null ? "" : token);
        v.put("amount", amount == null ? "" : amount);
        v.put("txnhash", txnhash == null ? "" : txnhash);
        v.put("eventdate", System.currentTimeMillis());
        helper.getWritableDatabase().insert("events", null, v);
    }

    public synchronized boolean hasEvent(String hash, String event) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT 1 FROM events WHERE hash=? AND event=? LIMIT 1", new String[]{norm(hash), event})) {
            return c.moveToFirst();
        }
    }

    public boolean haveSentCounterParty(String hash) { return hasEvent(hash, EV_CPSENT); }
    public boolean haveCollect(String hash)          { return hasEvent(hash, EV_COLLECT); }
    public boolean haveCollectExpired(String hash)   { return hasEvent(hash, EV_EXPIRED); }

    // ================= myhtlc (what I requested when initiating) =================

    public synchronized void insertMyHtlc(String hash, String reqAmount, String reqToken) {
        ContentValues v = new ContentValues();
        v.put("hash", norm(hash));
        v.put("reqamount", reqAmount);
        v.put("token", reqToken);
        v.put("eventdate", System.currentTimeMillis());
        helper.getWritableDatabase().insertWithOnConflict("myhtlc", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** {reqAmount, reqToken} for an initiated swap, or null if I did not start this HTLC. */
    public synchronized String[] getRequest(String hash) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT reqamount, token FROM myhtlc WHERE hash=? LIMIT 1", new String[]{norm(hash)})) {
            return c.moveToFirst() ? new String[]{c.getString(0), c.getString(1)} : null;
        }
    }

    // ================= swaps (UX projection) =================

    public static final class Swap {
        public String hash;
        public String role;        // INITIATOR / RESPONDER
        public String direction;   // MINIMA_TO_ERC20 / ERC20_TO_MINIMA
        public String sellToken, sellAmount, buyToken, buyAmount;
        public String counterparty;  // a short label (eth or minima key)
        public String status;
        public String contractId;    // ETH leg, if known
        public long myTimelock;      // absolute: minima block OR unix secs for my locked leg
        public boolean myLegIsMinima;// true if my locked leg is the Minima coin (governs timelock units)
        public long created;
        public long updated;
    }

    public synchronized void upsertSwap(Swap s) {
        ContentValues v = new ContentValues();
        v.put("hash", norm(s.hash));
        v.put("role", s.role);
        v.put("direction", s.direction);
        v.put("selltoken", s.sellToken);
        v.put("sellamount", s.sellAmount);
        v.put("buytoken", s.buyToken);
        v.put("buyamount", s.buyAmount);
        v.put("counterparty", s.counterparty);
        v.put("status", s.status);
        v.put("contractid", s.contractId == null ? "" : s.contractId);
        v.put("mytimelock", s.myTimelock);
        v.put("mylegminima", s.myLegIsMinima ? 1 : 0);
        if (s.created == 0) s.created = System.currentTimeMillis();
        v.put("created", s.created);
        v.put("updated", System.currentTimeMillis());
        helper.getWritableDatabase().insertWithOnConflict("swaps", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void setSwapStatus(String hash, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated", System.currentTimeMillis());
        helper.getWritableDatabase().update("swaps", v, "hash=?", new String[]{norm(hash)});
    }

    public synchronized void setSwapContractId(String hash, String contractId) {
        ContentValues v = new ContentValues();
        v.put("contractid", contractId);
        v.put("updated", System.currentTimeMillis());
        helper.getWritableDatabase().update("swaps", v, "hash=?", new String[]{norm(hash)});
    }

    public synchronized Swap getSwap(String hash) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM swaps WHERE hash=? LIMIT 1", new String[]{norm(hash)})) {
            return c.moveToFirst() ? readSwap(c) : null;
        }
    }

    /** All swaps, newest first. */
    public synchronized List<Swap> allSwaps() {
        List<Swap> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT * FROM swaps ORDER BY created DESC", null)) {
            while (c.moveToNext()) out.add(readSwap(c));
        }
        return out;
    }

    /** Hashes of swaps not yet finished — the set the watcher is still interested in (for secret matching). */
    public synchronized Set<String> activeHashes() {
        Set<String> out = new HashSet<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT hash FROM swaps WHERE status NOT IN (?,?,?)",
                new String[]{ST_COMPLETE, ST_REFUNDED, ST_ERROR})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    private static Swap readSwap(Cursor c) {
        Swap s = new Swap();
        s.hash = c.getString(c.getColumnIndexOrThrow("hash"));
        s.role = c.getString(c.getColumnIndexOrThrow("role"));
        s.direction = c.getString(c.getColumnIndexOrThrow("direction"));
        s.sellToken = c.getString(c.getColumnIndexOrThrow("selltoken"));
        s.sellAmount = c.getString(c.getColumnIndexOrThrow("sellamount"));
        s.buyToken = c.getString(c.getColumnIndexOrThrow("buytoken"));
        s.buyAmount = c.getString(c.getColumnIndexOrThrow("buyamount"));
        s.counterparty = c.getString(c.getColumnIndexOrThrow("counterparty"));
        s.status = c.getString(c.getColumnIndexOrThrow("status"));
        s.contractId = c.getString(c.getColumnIndexOrThrow("contractid"));
        s.myTimelock = c.getLong(c.getColumnIndexOrThrow("mytimelock"));
        s.myLegIsMinima = c.getInt(c.getColumnIndexOrThrow("mylegminima")) != 0;
        s.created = c.getLong(c.getColumnIndexOrThrow("created"));
        s.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return s;
    }

    /** Normalise a hashlock for keys: lower-case, no 0x prefix, so 0x-prefixed and bare forms match. */
    private static String norm(String h) {
        if (h == null) return "";
        String s = h.trim().toLowerCase();
        return s.startsWith("0x") ? s.substring(2) : s;
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context ctx) { super(ctx, "minimaswap.db", null, 1); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE secrets (hash TEXT PRIMARY KEY, secret TEXT, added INTEGER)");
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT, event TEXT, "
                    + "token TEXT, amount TEXT, txnhash TEXT, eventdate INTEGER)");
            db.execSQL("CREATE INDEX idx_events_hash ON events(hash)");
            db.execSQL("CREATE TABLE myhtlc (hash TEXT PRIMARY KEY, reqamount TEXT, token TEXT, eventdate INTEGER)");
            db.execSQL("CREATE TABLE swaps (hash TEXT PRIMARY KEY, role TEXT, direction TEXT, "
                    + "selltoken TEXT, sellamount TEXT, buytoken TEXT, buyamount TEXT, counterparty TEXT, "
                    + "status TEXT, contractid TEXT, mytimelock INTEGER, mylegminima INTEGER, "
                    + "created INTEGER, updated INTEGER)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { /* v1 only */ }
    }
}
