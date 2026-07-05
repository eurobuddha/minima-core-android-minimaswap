package org.minimarex.minimaswap.swap;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable OTC negotiation state: one row per deal thread (keyed by {@code ref}) + an append-only message
 * log (keyed by {@code randomid} for dedup). The comms transport is stateless independent coins, so all
 * correlation and turn/status state lives here. Modeled on {@code comms/MerchDb}. The deal's {@code hash}
 * links to the on-chain swap (SwapDb) once execution starts.
 */
public final class OtcDb {

    // deal roles
    public static final String ROLE_INSTIGATOR = "INSTIGATOR";   // I opened the deal → I execute (lock leg 1)
    public static final String ROLE_LP         = "LP";           // my availability was hit → I respond (leg 2)

    // deal statuses
    public static final String ST_PROPOSED  = "PROPOSED";
    public static final String ST_COUNTERED = "COUNTERED";
    public static final String ST_AGREED    = "AGREED";
    public static final String ST_EXECUTING = "EXECUTING";
    public static final String ST_COMPLETE  = "COMPLETE";
    public static final String ST_REJECTED  = "REJECTED";
    public static final String ST_EXPIRED   = "EXPIRED";

    // whoseTurn
    public static final String TURN_ME   = "ME";
    public static final String TURN_PEER = "PEER";

    public static final class Deal {
        public String ref;
        public String role;              // ROLE_INSTIGATOR / ROLE_LP
        public String peerCommsId;       // counterparty commsPublicId
        public String peerMinimaPk;      // counterparty Minima pubkey
        public String peerEthAddr;       // counterparty ETH address
        public String side;              // OtcOffer.LP_SELLS_MINIMA / LP_BUYS_MINIMA (LP perspective)
        public String amount;            // current MINIMA amount, decimal string
        public String price;             // current USDT per MINIMA, decimal string
        public String status;
        public String whoseTurn;         // TURN_ME / TURN_PEER
        public String hash;              // on-chain hashlock once executing (links to SwapDb)
        public long created, updated;
    }

    public static final class Msg {
        public String randomid, ref, type, from, side, amount, price, hash;
        public long date;
    }

    private final Helper helper;
    public OtcDb(Context ctx) { helper = new Helper(ctx.getApplicationContext()); }

    public synchronized void upsertDeal(Deal d) {
        ContentValues v = new ContentValues();
        v.put("ref", d.ref); v.put("role", d.role); v.put("peercid", d.peerCommsId);
        v.put("peermpk", d.peerMinimaPk); v.put("peereth", d.peerEthAddr);
        v.put("side", d.side); v.put("amount", d.amount); v.put("price", d.price);
        v.put("status", d.status); v.put("whoseturn", d.whoseTurn); v.put("hash", d.hash == null ? "" : d.hash);
        if (d.created == 0) d.created = System.currentTimeMillis();
        v.put("created", d.created); v.put("updated", System.currentTimeMillis());
        helper.getWritableDatabase().insertWithOnConflict("otc_deals", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Deal getDeal(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT * FROM otc_deals WHERE ref=? LIMIT 1", new String[]{ref})) {
            return c.moveToFirst() ? readDeal(c) : null;
        }
    }

    /** Find the deal whose execution hashlock matches — the OTC responder's link from an on-chain leg back to terms.
     *  Deterministic (newest-first) so a self-griefer reusing one hash across two of their own deals can't randomise it. */
    public synchronized Deal dealByHash(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT * FROM otc_deals WHERE hash=? ORDER BY updated DESC LIMIT 1", new String[]{hash})) {
            return c.moveToFirst() ? readDeal(c) : null;
        }
    }

    /** Remove a finished deal + its message thread — pruning to bound the table under message spam. */
    public synchronized void deleteDeal(String ref) {
        if (ref == null || ref.isEmpty()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("otc_msgs", "ref=?", new String[]{ref});
        db.delete("otc_deals", "ref=?", new String[]{ref});
    }

    public synchronized List<Deal> allDeals() {
        List<Deal> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT * FROM otc_deals ORDER BY updated DESC", null)) {
            while (c.moveToNext()) out.add(readDeal(c));
        }
        return out;
    }

    /** Record a message (in or out); returns true if newly added (dedup by randomid). */
    public synchronized boolean addMsg(Msg m) {
        if (m.randomid == null || m.randomid.isEmpty()) return false;
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT 1 FROM otc_msgs WHERE randomid=? LIMIT 1", new String[]{m.randomid})) {
            if (c.moveToFirst()) return false;
        }
        ContentValues v = new ContentValues();
        v.put("randomid", m.randomid); v.put("ref", m.ref); v.put("type", m.type); v.put("sender", m.from);
        v.put("side", m.side); v.put("amount", m.amount); v.put("price", m.price); v.put("hash", m.hash == null ? "" : m.hash);
        v.put("date", m.date);
        return helper.getWritableDatabase().insert("otc_msgs", null, v) >= 0;
    }

    public synchronized List<Msg> msgs(String ref) {
        List<Msg> out = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT * FROM otc_msgs WHERE ref=? ORDER BY date ASC", new String[]{ref})) {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.randomid = c.getString(c.getColumnIndexOrThrow("randomid"));
                m.ref = c.getString(c.getColumnIndexOrThrow("ref"));
                m.type = c.getString(c.getColumnIndexOrThrow("type"));
                m.from = c.getString(c.getColumnIndexOrThrow("sender"));
                m.side = c.getString(c.getColumnIndexOrThrow("side"));
                m.amount = c.getString(c.getColumnIndexOrThrow("amount"));
                m.price = c.getString(c.getColumnIndexOrThrow("price"));
                m.hash = c.getString(c.getColumnIndexOrThrow("hash"));
                m.date = c.getLong(c.getColumnIndexOrThrow("date"));
                out.add(m);
            }
        }
        return out;
    }

    private Deal readDeal(Cursor c) {
        Deal d = new Deal();
        d.ref = c.getString(c.getColumnIndexOrThrow("ref"));
        d.role = c.getString(c.getColumnIndexOrThrow("role"));
        d.peerCommsId = c.getString(c.getColumnIndexOrThrow("peercid"));
        d.peerMinimaPk = c.getString(c.getColumnIndexOrThrow("peermpk"));
        d.peerEthAddr = c.getString(c.getColumnIndexOrThrow("peereth"));
        d.side = c.getString(c.getColumnIndexOrThrow("side"));
        d.amount = c.getString(c.getColumnIndexOrThrow("amount"));
        d.price = c.getString(c.getColumnIndexOrThrow("price"));
        d.status = c.getString(c.getColumnIndexOrThrow("status"));
        d.whoseTurn = c.getString(c.getColumnIndexOrThrow("whoseturn"));
        d.hash = c.getString(c.getColumnIndexOrThrow("hash"));
        d.created = c.getLong(c.getColumnIndexOrThrow("created"));
        d.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return d;
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context ctx) { super(ctx, "otc.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE otc_deals (ref TEXT PRIMARY KEY, role TEXT, peercid TEXT, peermpk TEXT, "
                    + "peereth TEXT, side TEXT, amount TEXT, price TEXT, status TEXT, whoseturn TEXT, hash TEXT, "
                    + "created INTEGER, updated INTEGER)");
            db.execSQL("CREATE TABLE otc_msgs (randomid TEXT PRIMARY KEY, ref TEXT, type TEXT, sender TEXT, "
                    + "side TEXT, amount TEXT, price TEXT, hash TEXT, date INTEGER)");
            db.execSQL("CREATE INDEX idx_otcmsgs_ref ON otc_msgs(ref)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}
    }
}
