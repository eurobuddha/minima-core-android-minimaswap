package org.minimarex.minimaswap.eth;

import org.json.JSONArray;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

/**
 * Signs + sends a legacy (EIP-155) Ethereum transaction via our JSON-RPC client. We use legacy
 * gasPrice transactions (accepted on Ethereum mainnet) so we need no Infura gas API / key.
 * Blocking — call off the main thread.
 */
public final class EthTx {

    private static final BigInteger FALLBACK_GAS_PRICE = BigInteger.valueOf(2_000_000_000L); // 2 gwei

    // Nonce = the RPC's "pending" count. We deliberately do NOT keep a local counter: the two places rapid
    // back-to-back sends occur are both already serialized on-chain — a market sweep waits for each leg's lock
    // to be MINED before the next (see MainActivity.awaitLegConfirm), and ensureAllowanceBlocking waits for the
    // approve to confirm before newContract — so "pending" is always fresh, and pure-pending self-heals if a
    // tx is ever dropped (a local counter would leave a permanent nonce gap instead).
    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        BigInteger nonce = rpc.getTransactionCount(creds.getAddress());
        BigInteger gasPrice = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new JSONArray()));
        if (gasPrice.signum() <= 0) gasPrice = FALLBACK_GAS_PRICE;
        gasPrice = gasPrice.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN); // +20% headroom

        RawTransaction raw = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, to,
                value == null ? BigInteger.ZERO : value, data);
        byte[] signed = TransactionEncoder.signMessage(raw, chainId, creds);
        String txHash = rpc.sendRawTransaction(Numeric.toHexString(signed));
        if (txHash == null || !txHash.startsWith("0x")) throw new Exception("send failed: " + txHash);
        return txHash;
    }
}
