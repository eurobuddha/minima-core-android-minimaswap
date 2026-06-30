package org.minimarex.comms;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Local store for miniMall (both Shop and Inbox use it). Orders dedup on {@code ref}; chat dedups on
 * {@code randomid}; {@code meta} holds scan bookmarks + identity. Implements {@link CommsScanner.MetaStore}.
 */
public class MerchDb extends SQLiteOpenHelper implements CommsScanner.MetaStore {

    private static final String DB = "minima_merch.db";
    private static final int VERSION = 2;   // v2: + shopid/shopname on orders
    private static final String ORD = "orders", CHAT = "order_chat", META = "meta";

    // statuses
    public static final String PENDING = "PENDING", PAID = "PAID", CONFIRMED = "CONFIRMED",
            SHIPPED = "SHIPPED", DELIVERED = "DELIVERED", UNDERPAID = "UNDERPAID", WRONG_TOKEN = "WRONG_TOKEN",
            INQUIRY = "INQUIRY";

    public MerchDb(Context ctx) { super(ctx, DB, null, VERSION); }

    public static class Order {
        public long id;
        public String ref, role, counterparty, counterpartyname, payaddr, shopId, shopName;
        public String items, product, amount, currency, tokenid, shipping, delivery, message;
        public String status, coinid, paidAmount, paidTokenid;
        public boolean paid, read;
        public long date;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + ORD + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT UNIQUE, role TEXT, counterparty TEXT," +
                "counterpartyname TEXT, payaddr TEXT, shopid TEXT, shopname TEXT, items TEXT, product TEXT, amount TEXT, currency TEXT," +
                "tokenid TEXT, shipping TEXT, delivery TEXT, message TEXT, status TEXT, coinid TEXT," +
                "paidamount TEXT, paidtokenid TEXT, paid INTEGER DEFAULT 0, date INTEGER, read INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + CHAT + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT, incoming INTEGER, message TEXT," +
                "randomid TEXT UNIQUE, date INTEGER, read INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + META + " (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        for (String col : new String[]{"shopid TEXT", "shopname TEXT"}) {
            try { db.execSQL("ALTER TABLE " + ORD + " ADD COLUMN " + col); } catch (Exception ignored) {}
        }
    }

    // ----- orders -----

    /** Insert an incoming order/inquiry (dedup on ref; never overwrites an existing one's status). */
    public boolean upsertOrder(MerchMessage m, String role, String coinid) {
        ContentValues v = new ContentValues();
        v.put("ref", m.ref); v.put("role", role);
        // the "other party": for a seller that's the order's sender (buyer); for a buyer it's the recipient (vendor)
        v.put("counterparty", "seller".equals(role) ? m.from : m.to);
        v.put("counterpartyname", m.fromname); v.put("payaddr", m.buyerPayaddr);
        v.put("shopid", m.shopId); v.put("shopname", m.shopName);
        v.put("items", m.items); v.put("product", m.product); v.put("amount", m.amount);
        v.put("currency", m.currency); v.put("tokenid", m.tokenid); v.put("shipping", m.shipping);
        v.put("delivery", m.delivery); v.put("message", m.message);
        v.put("status", MerchMessage.INQUIRY.equals(m.type) ? INQUIRY : PENDING); v.put("coinid", coinid);
        v.put("date", m.date > 0 ? m.date : System.currentTimeMillis()); v.put("read", 0);
        long id = getWritableDatabase().insertWithOnConflict(ORD, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return id != -1;
    }

    public List<Order> orders() {
        return queryOrders("SELECT * FROM " + ORD + " ORDER BY date DESC", null);
    }

    public Order order(String ref) {
        List<Order> l = queryOrders("SELECT * FROM " + ORD + " WHERE ref=?", new String[]{ref});
        return l.isEmpty() ? null : l.get(0);
    }

    public void setStatus(String ref, String status) {
        ContentValues v = new ContentValues(); v.put("status", status);
        getWritableDatabase().update(ORD, v, "ref=?", new String[]{ref});
    }

    public void markRead(String ref) {
        ContentValues v = new ContentValues(); v.put("read", 1);
        getWritableDatabase().update(ORD, v, "ref=?", new String[]{ref});
    }

    public int unreadCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + ORD + " WHERE read=0", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /**
     * Record a payment coin and run the verification gate: PAID only if the coin's amount >= the ordered
     * total AND the tokenid matches; else UNDERPAID / WRONG_TOKEN. Never downgrades a fulfilled order.
     * Returns the resulting status (or null if no such order).
     */
    public String recordPayment(String ref, String paidAmount, String paidTokenid) {
        Order o = order(ref);
        if (o == null) return null;
        String result;
        boolean tokenOk = paidTokenid != null && paidTokenid.equalsIgnoreCase(o.tokenid);
        boolean amountOk;
        try { amountOk = new BigDecimal(paidAmount).compareTo(new BigDecimal(o.amount)) >= 0; }
        catch (Exception e) { amountOk = false; }
        if (!tokenOk) result = WRONG_TOKEN;
        else if (!amountOk) result = UNDERPAID;
        else result = PAID;

        ContentValues v = new ContentValues();
        v.put("paid", 1); v.put("paidamount", paidAmount); v.put("paidtokenid", paidTokenid);
        // only move the lifecycle status forward from PENDING (don't clobber CONFIRMED/SHIPPED/…)
        if (PENDING.equals(o.status) || o.status == null || o.status.isEmpty()) v.put("status", result);
        getWritableDatabase().update(ORD, v, "ref=?", new String[]{ref});
        return result;
    }

    private List<Order> queryOrders(String sql, String[] args) {
        List<Order> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            int iRef = c.getColumnIndex("ref"), iRole = c.getColumnIndex("role"),
                iCp = c.getColumnIndex("counterparty"), iCn = c.getColumnIndex("counterpartyname"),
                iPa = c.getColumnIndex("payaddr"), iSid = c.getColumnIndex("shopid"), iSnm = c.getColumnIndex("shopname"),
                iItems = c.getColumnIndex("items"),
                iProd = c.getColumnIndex("product"), iAmt = c.getColumnIndex("amount"),
                iCur = c.getColumnIndex("currency"), iTok = c.getColumnIndex("tokenid"),
                iShip = c.getColumnIndex("shipping"), iDel = c.getColumnIndex("delivery"),
                iMsg = c.getColumnIndex("message"), iSt = c.getColumnIndex("status"),
                iCoin = c.getColumnIndex("coinid"), iPaid = c.getColumnIndex("paid"),
                iPaidA = c.getColumnIndex("paidamount"), iPaidT = c.getColumnIndex("paidtokenid"),
                iDate = c.getColumnIndex("date"), iRead = c.getColumnIndex("read"), iId = c.getColumnIndex("id");
            while (c.moveToNext()) {
                Order o = new Order();
                o.id = c.getLong(iId); o.ref = c.getString(iRef); o.role = c.getString(iRole);
                o.counterparty = c.getString(iCp); o.counterpartyname = nz(c.getString(iCn));
                o.payaddr = nz(c.getString(iPa));
                o.shopId = iSid >= 0 ? nz(c.getString(iSid)) : ""; o.shopName = iSnm >= 0 ? nz(c.getString(iSnm)) : "";
                o.items = nz(c.getString(iItems)); o.product = nz(c.getString(iProd));
                o.amount = nz(c.getString(iAmt)); o.currency = nz(c.getString(iCur)); o.tokenid = nz(c.getString(iTok));
                o.shipping = nz(c.getString(iShip)); o.delivery = nz(c.getString(iDel)); o.message = nz(c.getString(iMsg));
                o.status = nz(c.getString(iSt)); o.coinid = nz(c.getString(iCoin));
                o.paidAmount = nz(c.getString(iPaidA)); o.paidTokenid = nz(c.getString(iPaidT));
                o.paid = c.getInt(iPaid) == 1; o.date = c.getLong(iDate); o.read = c.getInt(iRead) == 1;
                out.add(o);
            }
        } finally { c.close(); }
        return out;
    }

    // ----- chat -----

    public boolean insertChat(String ref, boolean incoming, String message, String randomid, long date) {
        ContentValues v = new ContentValues();
        v.put("ref", ref); v.put("incoming", incoming ? 1 : 0); v.put("message", message);
        v.put("randomid", randomid); v.put("date", date); v.put("read", incoming ? 0 : 1);
        return getWritableDatabase().insertWithOnConflict(CHAT, null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<String[]> chat(String ref) {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT incoming,message,date FROM " + CHAT + " WHERE ref=? ORDER BY date ASC", new String[]{ref});
        try { while (c.moveToNext()) out.add(new String[]{String.valueOf(c.getInt(0)), c.getString(1), String.valueOf(c.getLong(2))}); }
        finally { c.close(); }
        return out;
    }

    // ----- meta (MetaStore) -----

    @Override public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", v);
        getWritableDatabase().insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override public String getMeta(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM " + META + " WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
