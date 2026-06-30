package org.minimarex.minimaswap.swap;

import org.json.JSONObject;
import org.minimarex.comms.NodeApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Minima leg of the atomic swap — the native equivalent of the bridge MiniDapp's apiminima.js.
 *
 * The HTLC is a KISS script coin: a Minima coin locked with 7 PREVSTATE fields. It can be spent two
 * ways — CLAIM (the counterparty reveals the secret whose SHA2 equals the hashlock, and must pay a
 * 0.0001 "notify" coin to {@link #NOTIFY} so the reveal is observable on-chain) or REFUND (the owner
 * reclaims after the block timelock passes). Script + address + notify address are verbatim from the
 * upstream so coins are spend-compatible with the bridge MiniDapp.
 *
 * NodeApi delivers one JSONObject per command, so the multi-step claim/refund transactions are issued
 * as a *sequence* of single commands tied together by a shared txn id (Minima keeps the half-built txn
 * in memory until txnpost/txndelete).
 */
public final class MinimaHtlc {

    /** Verbatim from bridge dapp/js/scripts.js — changing a byte changes the address. */
    public static final String HTLC_SCRIPT =
            "LET version=1.2 LET owner=PREVSTATE(0) LET requestamount=PREVSTATE(1) LET requesttoken=PREVSTATE(2) "
          + "LET timelock=PREVSTATE(3) LET counterparty=PREVSTATE(4) LET hash=PREVSTATE(5) LET ownerethkey=PREVSTATE(6) "
          + "IF SIGNEDBY(owner) AND (@BLOCK GT timelock) THEN RETURN TRUE ENDIF LET secret=STATE(100) "
          + "ASSERT SIGNEDBY(counterparty) AND (SHA2(secret) EQ hash) ASSERT STATE(101) EQ hash "
          + "ASSERT STATE(102) EQ STRING(owner) ASSERT STATE(103) EQ STRING(counterparty) "
          + "RETURN VERIFYOUT(@INPUT 0xFFEEDD9999 0.0001 @TOKENID TRUE)";

    public static final String HTLC_ADDRESS = "MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G";
    public static final String NOTIFY = "0xFFEEDD9999";
    public static final int MINIMA_BLOCK_TIME = 50;                 // seconds/block (upstream htlcvars.js)
    public static final int TIMELOCK_BLOCKS = (60 * 60 * 2) / MINIMA_BLOCK_TIME;   // 2h ≈ 144 blocks

    private final NodeApi node;
    private String myAddress;   // Mx… address (fromaddress + change recipient)
    private String myPubkey;    // 0x… public key (signkey + state owner)

    public MinimaHtlc(NodeApi node) { this.node = node; }

    public String myAddress() { return myAddress; }
    public String myPubkey() { return myPubkey; }
    public boolean ready() { return myAddress != null && myPubkey != null; }

    public interface SetupCb { void ok(String address, String pubkey); void err(String msg); }
    public interface SecretCb { void ok(String secret, String hash); void err(String msg); }
    public interface BlockCb { void ok(int block); void err(String msg); }
    public interface PostCb { void ok(String txpowid); void err(String msg); }
    public interface KeysCb { void ok(java.util.Set<String> pubkeys); void err(String msg); }

    // ---- setup: register the HTLC script + resolve a STABLE address/pubkey ----

    /**
     * Register the HTLC script and establish my swap identity. A Minima node has 64 permanent default
     * keys and {@code getaddress} returns a *different* one each call — so the identity MUST be persisted
     * once and reused, or discovery/resume/refund break across restarts. Pass the previously-saved
     * {@code savedAddress}/{@code savedPubkey} to reuse them; pass null on first run to pick one (returned
     * via {@code cb.ok} for the caller to persist). The chosen key is one of the 64 the node controls
     * forever, so it survives restarts.
     */
    public void setup(final String savedAddress, final String savedPubkey, final SetupCb cb) {
        cmd("newscript script:\"" + HTLC_SCRIPT + "\" trackall:false", r1 -> {
            if (savedAddress != null && !savedAddress.isEmpty() && savedPubkey != null && !savedPubkey.isEmpty()) {
                myAddress = savedAddress; myPubkey = savedPubkey;
                cb.ok(myAddress, myPubkey);
                return;
            }
            cmd("getaddress", r2 -> {
                JSONObject resp = r2.optJSONObject("response");
                if (resp == null) { cb.err("getaddress returned nothing"); return; }
                myAddress = resp.optString("miniaddress", resp.optString("address", ""));
                myPubkey  = resp.optString("publickey", "");
                if (myAddress.isEmpty() || myPubkey.isEmpty()) { cb.err("Could not resolve my Minima address/key"); return; }
                cb.ok(myAddress, myPubkey);
            }, cb::err);
        }, cb::err);
    }

    /** All 64 of my node's public keys (normalised), so refund/owner matching works for a coin locked
     *  under any default key — not just the one persisted swap identity. */
    public void loadMyKeys(KeysCb cb) {
        cmd("keys", r -> {
            java.util.Set<String> out = new java.util.HashSet<>();
            JSONObject resp = r.optJSONObject("response");
            org.json.JSONArray arr = resp == null ? null : resp.optJSONArray("keys");
            if (arr == null && r.opt("response") instanceof org.json.JSONArray) arr = (org.json.JSONArray) r.opt("response");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject k = arr.optJSONObject(i);
                if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) out.add(normKey(pk)); }
            }
            cb.ok(out);
        }, cb::err);
    }

    /** Canonical form of a Minima public key for set membership: no 0x, upper-case. */
    public static String normKey(String pk) {
        if (pk == null) return "";
        String s = pk.trim().toUpperCase();
        return s.startsWith("0X") ? s.substring(2) : s;
    }

    // ---- helpers ----

    public void currentBlock(BlockCb cb) {
        cmd("block", r -> {
            JSONObject resp = r.optJSONObject("response");
            cb.ok(resp == null ? 0 : resp.optInt("block", 0));
        }, cb::err);
    }

    /** Generate a fresh 32-byte secret + its SHA2 (SHA256) hashlock, the same way the bridge does. */
    public void generateSecret(SecretCb cb) {
        cmd("random type:sha2", r -> {
            JSONObject resp = r.optJSONObject("response");
            if (resp == null) { cb.err("random returned nothing"); return; }
            String secret = resp.optString("random", "");
            String hash = resp.optString("hashed", "");
            if (secret.isEmpty() || hash.isEmpty()) { cb.err("random missing secret/hash"); return; }
            cb.ok(secret, hash);
        }, cb::err);
    }

    // ---- LOCK: send a coin to the HTLC with the 7 PREVSTATE fields ----

    /**
     * @param amount        MINIMA amount to lock (the owner's side)
     * @param requestAmount the ERC20 amount requested in return (string)
     * @param reqToken      the ERC20 token contract address (the script stores "[reqToken]")
     * @param receiverPubkey counterparty's Minima public key (who can claim with the secret)
     * @param ownerEthKey   the owner's ETH address
     * @param hashlock      SHA2(secret)
     * @param timelockBlock absolute Minima block after which the owner can refund
     * @param otc           "TRUE"/"FALSE"
     */
    public void lock(String amount, String requestAmount, String reqToken, String receiverPubkey,
                     String ownerEthKey, String hashlock, int timelockBlock, String otc, PostCb cb) {
        if (!ready()) { cb.err("Minima wallet not ready"); return; }
        JSONObject state = new JSONObject();
        try {
            state.put("0", myPubkey);
            state.put("1", requestAmount);
            state.put("2", "[" + reqToken + "]");
            state.put("3", timelockBlock);
            state.put("4", receiverPubkey);
            state.put("5", hashlock);
            state.put("6", ownerEthKey);
            state.put("7", otc);
        } catch (Exception e) { cb.err("state build: " + e.getMessage()); return; }

        // Fund from ANY of my 64 default addresses (no fromaddress/signkey constraint) — the node has no
        // dedicated "bridge wallet" here, so pinning to one address would fail when funds sit elsewhere.
        // The refund owner is set explicitly via state[0]=myPubkey, so the coin stays mine to reclaim.
        String send = "send amount:" + amount + " mine:true address:" + HTLC_ADDRESS
                + " state:" + state.toString() + " tokenid:0x00";
        cmd(send, r -> {
            JSONObject resp = r.optJSONObject("response");
            cb.ok(resp == null ? "" : resp.optString("txpowid", ""));
        }, cb::err);
    }

    // ---- CLAIM: counterparty reveals the secret + pays the notify coin ----

    /** coin = the HTLC coin JSON (needs coinid, tokenid, amount, state[]). I am the receiver (state[4]). */
    public void claim(JSONObject coin, String hash, String secret, PostCb cb) {
        if (!ready()) { cb.err("Minima wallet not ready"); return; }
        String coinid = coin.optString("coinid", "");
        String tokenid = coin.optString("tokenid", "0x00");
        String amount = coin.optString("amount", "0");
        String owner = stateAt(coin, 0);
        String receiver = stateAt(coin, 4);
        String change = subtract(amount, "0.0001");
        String id = txnId();

        List<String> seq = new ArrayList<>();
        seq.add("txncreate id:" + id);
        seq.add("txninput id:" + id + " coinid:" + coinid);
        // notify coin MUST be output 0 (the script's VERIFYOUT(@INPUT 0xFFEEDD9999 ...))
        seq.add("txnoutput id:" + id + " tokenid:" + tokenid + " amount:0.0001 address:" + NOTIFY);
        // skip the change output for a dust-only lock (amount == 0.0001 → change == 0)
        if (positive(change)) seq.add("txnoutput id:" + id + " tokenid:" + tokenid + " amount:" + change + " address:" + myAddress);
        seq.add("txnstate id:" + id + " port:100 value:" + secret);
        seq.add("txnstate id:" + id + " port:101 value:" + hash);
        seq.add("txnstate id:" + id + " port:102 value:[" + owner + "]");
        seq.add("txnstate id:" + id + " port:103 value:[" + receiver + "]");
        // sign with the coin's receiver key (the counterparty the script requires) — one of my 64 defaults.
        seq.add("txnsign id:" + id + " publickey:" + receiver);
        seq.add("txnpost id:" + id + " mine:true auto:true txndelete:true");
        runSeq(seq, last -> cb.ok(txpowOf(last)), e -> { deleteTxn(id); cb.err(e); });
    }

    // ---- REFUND: owner reclaims after the timelock ----

    public void refund(JSONObject coin, PostCb cb) {
        if (!ready()) { cb.err("Minima wallet not ready"); return; }
        String coinid = coin.optString("coinid", "");
        String tokenid = coin.optString("tokenid", "0x00");
        String amount = coin.optString("amount", "0");
        String owner = stateAt(coin, 0);                    // the script's refund signer = state[0]
        String id = txnId();

        List<String> seq = new ArrayList<>();
        seq.add("txncreate id:" + id);
        seq.add("txninput id:" + id + " coinid:" + coinid);
        seq.add("txnoutput id:" + id + " tokenid:" + tokenid + " amount:" + amount + " address:" + myAddress);
        // sign with the coin's owner key (SIGNEDBY(owner)) — one of my 64 defaults, whichever locked it.
        seq.add("txnsign id:" + id + " publickey:" + owner);
        seq.add("txnpost id:" + id + " auto:true txndelete:true");
        runSeq(seq, last -> cb.ok(txpowOf(last)), e -> { deleteTxn(id); cb.err(e); });
    }

    /** Scan the shared HTLC address for coins (claimable orders / my locked coins). */
    public void scanHtlcCoins(int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coins depth:" + depth + " relevant:false address:" + HTLC_ADDRESS, r -> {
            Object resp = r.opt("response");
            ok.accept(resp instanceof org.json.JSONArray ? (org.json.JSONArray) resp : new org.json.JSONArray());
        }, err);
    }

    /**
     * Scan the HTLC address for coins relevant to ME — verbatim the upstream query
     * ({@code coins coinage:2 tokenid:0x00 simplestate:true relevant:true address:HTLC_ADDRESS}).
     * {@code relevant:true} bounds the reply to coins my keys appear in (owner state[0] or receiver
     * state[4]), which is both correct AND the safety bound — the shared address is global, so an
     * unbounded scan could return a node-crashing reply.
     */
    public void scanMyHtlcCoins(Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coins coinage:2 tokenid:0x00 simplestate:true relevant:true address:" + HTLC_ADDRESS, r -> {
            Object resp = r.opt("response");
            ok.accept(resp instanceof org.json.JSONArray ? (org.json.JSONArray) resp : new org.json.JSONArray());
        }, err);
    }

    /**
     * Scan the notify address for revealed-secret coins (the 0.0001 coins a claim forces to {@link #NOTIFY}).
     * The ERC20→MINIMA responder reads the secret here. Bounded by {@code depth} — the address is global,
     * so we never query it unbounded; the caller filters by the hashlocks it actually cares about.
     */
    public void scanNotifyCoins(int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        // The notify address is SHARED (nobody owns it), so `relevant:false` returns 0 until it's tracked.
        // coinnotify-add it first (idempotent), then query BARE — the same fix the order-book scan uses.
        cmd("coinnotify action:add address:" + NOTIFY, r -> doScanNotify(depth, ok, err), e -> doScanNotify(depth, ok, err));
    }

    private void doScanNotify(int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coins simplestate:true depth:" + depth + " address:" + NOTIFY, r -> {
            Object resp = r.opt("response");
            ok.accept(resp instanceof org.json.JSONArray ? (org.json.JSONArray) resp : new org.json.JSONArray());
        }, err);
    }

    // ---- command plumbing ----

    private void cmd(String command, Consumer<JSONObject> ok, Consumer<String> err) {
        node.cmd(command, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", true)) { err.accept(shortCmd(command) + ": " + j.optString("error", "command failed")); return; }
                ok.accept(j);
            }
            @Override public void onError(String m) { err.accept(m); }
        });
    }

    private void runSeq(List<String> cmds, Consumer<JSONObject> finalOk, Consumer<String> err) {
        runSeqAt(cmds, 0, finalOk, err);
    }
    private void runSeqAt(List<String> cmds, int i, Consumer<JSONObject> finalOk, Consumer<String> err) {
        cmd(cmds.get(i), resp -> {
            if (i == cmds.size() - 1) finalOk.accept(resp);
            else runSeqAt(cmds, i + 1, finalOk, err);
        }, err);
    }

    private void deleteTxn(String id) { node.cmd("txndelete id:" + id, new NodeApi.Cb() {
        @Override public void onResult(JSONObject j) {} @Override public void onError(String m) {} }); }

    /** Read a coin state port. State may be a simplestate object {"4":…} or an array [{port,data}]. */
    static String stateAt(JSONObject coin, int port) {
        Object st = coin.opt("state");
        if (st instanceof JSONObject) {
            return ((JSONObject) st).optString(String.valueOf(port), "");
        }
        if (st instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) st;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optInt("port", -1) == port) return o.optString("data", "");
            }
        }
        return "";
    }

    private static String txpowOf(JSONObject postResp) {
        JSONObject resp = postResp.optJSONObject("response");
        return resp == null ? "" : resp.optString("txpowid", "");
    }

    private static String subtract(String a, String b) {
        try { return new java.math.BigDecimal(a).subtract(new java.math.BigDecimal(b)).stripTrailingZeros().toPlainString(); }
        catch (Exception e) { return a; }
    }

    private static boolean positive(String a) {
        try { return new java.math.BigDecimal(a).signum() > 0; } catch (Exception e) { return false; }
    }

    private static String txnId() { return "swap_" + Long.toHexString(System.nanoTime()); }
    private static String shortCmd(String c) { int sp = c.indexOf(' '); return sp < 0 ? c : c.substring(0, sp); }
}
