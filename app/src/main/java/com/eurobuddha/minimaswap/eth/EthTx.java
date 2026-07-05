package com.eurobuddha.minimaswap.eth;

import org.json.JSONArray;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs + sends a legacy (EIP-155) Ethereum transaction via our JSON-RPC client. We use legacy
 * gasPrice transactions (accepted on Ethereum mainnet) so we need no Infura gas API / key.
 * Blocking — call off the main thread.
 */
public final class EthTx {

    private static final BigInteger FALLBACK_GAS_PRICE = BigInteger.valueOf(2_000_000_000L); // 2 gwei

    // ── Per-address nonce serializer ──────────────────────────────────────────────────────────────────────
    // A market sweep fires up to 6 ETH locks back-to-back, and the poll loop can issue a claim/refund/withdraw
    // concurrently on the io pool. The RPC "pending" count lags a just-broadcast tx, so two sends would read the
    // SAME nonce and collide — one silently dropped (the 0.10.0 bug that dropped the best leg). We hand out
    // strictly-increasing nonces under a per-address lock, and SELF-HEAL a dropped own-tx by falling back to
    // "pending" once it has sat stuck-behind us for STALE_MS (a plain advancing counter would leave a permanent
    // nonce gap instead — the reason the naive 0.10.x counter was reverted). A false heal is fund-safe: it's an
    // RBF of an in-flight nonce — if the original is fine it wins and the heal-send is rejected ("nonce too low")
    // so send() throws and nothing is committed. Cross-process (Activity vs Service) handoff still relies on a
    // fresh "pending" at cold start, which the FOREGROUND/SWEEP_ACTIVE single-actor guard already guarantees.
    private static final long STALE_MS = 120_000L;
    private static final Map<String, NonceState> NONCE = new ConcurrentHashMap<>();

    private static final class NonceState {
        long next = -1;      // next nonce to hand out; -1 = cold → read "pending"
        long syncAtMs = 0;   // last time "pending" was even-with / ahead-of our counter (in sync)
    }

    /** Forget all cached nonce state — call on a chain/endpoint switch so we cold-start from the new node. */
    public static void resetAll() { NONCE.clear(); }

    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        NonceState st = NONCE.computeIfAbsent(creds.getAddress(), k -> new NonceState());
        synchronized (st) {
            long pending = rpc.getTransactionCount(creds.getAddress()).longValue();   // fresh "pending"
            long now = System.currentTimeMillis();
            long alloc, newNext, newSync;
            if (st.next < 0 || pending >= st.next) {              // cold | in-sync | external tx advanced us
                alloc = pending; newNext = pending + 1; newSync = now;
            } else if (now - st.syncAtMs >= STALE_MS) {           // stuck behind too long → our tx dropped → HEAL
                alloc = pending; newNext = pending + 1; newSync = now;
            } else {                                              // pending just lagging our broadcast → keep counting
                alloc = st.next; newNext = st.next + 1; newSync = st.syncAtMs;
            }

            BigInteger gasPrice = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new JSONArray()));
            if (gasPrice.signum() <= 0) gasPrice = FALLBACK_GAS_PRICE;
            gasPrice = gasPrice.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN); // +20% headroom

            RawTransaction raw = RawTransaction.createTransaction(
                    BigInteger.valueOf(alloc), gasPrice, gasLimit, to,
                    value == null ? BigInteger.ZERO : value, data);
            byte[] signed = TransactionEncoder.signMessage(raw, chainId, creds);
            String txHash = rpc.sendRawTransaction(Numeric.toHexString(signed));
            if (txHash == null || !txHash.startsWith("0x")) throw new Exception("send failed: " + txHash);

            st.next = newNext; st.syncAtMs = newSync;   // commit only AFTER a successful broadcast
            return txHash;
        }
    }
}
