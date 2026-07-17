package com.eurobuddha.minimaswap.eth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * STEP: the Ethereum leg's deterministic addressing + amount formatting — the pure functions the F1/F2 fixes
 * rely on to locate a contract (via getContract) and read back a counterparty's amount.
 *
 *  - {@link EthHtlc#contractId} MUST be sha256(hashlock) (NOT keccak) so it matches the Minima side, where
 *    the hashlock is SHA2(secret). A wrong hash family strands the counterparty's leg forever.
 *  - {@link EthWallet#format} turns a raw integer balance into the human amount the UI + amount checks use.
 */
public class EthEncodingTest {

    @Test public void contractIdIsSha256OfAllZeroHashlock() {
        // b32("0x") = 32 zero bytes; sha256(32 zero bytes) is a stable, independently-known vector. This pins
        // both the hash family (sha256, not keccak) and the zero-padding of the bytes32 coercion.
        assertEquals("0x66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925",
                EthHtlc.contractId("0x"));
    }

    @Test public void contractIdUsesSha256NotKeccak() throws Exception {
        // Cross-check a 32-byte hashlock against the JDK's real SHA-256; keccak256 would differ.
        String hashlock = "0x2b6f0cc904f1f0d1e2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809";
        byte[] raw = org.web3j.utils.Numeric.hexStringToByteArray(hashlock);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(raw);
        assertEquals(org.web3j.utils.Numeric.toHexString(expected), EthHtlc.contractId(hashlock));
    }

    @Test public void contractIdIsDeterministicAndCollisionSensitive() {
        String a = "0x1111111111111111111111111111111111111111111111111111111111111111";
        String b = "0x1111111111111111111111111111111111111111111111111111111111111112";
        assertEquals(EthHtlc.contractId(a), EthHtlc.contractId(a));
        assertNotEquals(EthHtlc.contractId(a), EthHtlc.contractId(b));
    }

    @Test public void formatScalesAndTrims() {
        assertEquals("0.3465", EthWallet.format(BigInteger.valueOf(346500), 6, 6));
        assertEquals("1", EthWallet.format(BigInteger.valueOf(1_000_000), 6, 6));
        assertEquals("1.5", EthWallet.format(BigInteger.valueOf(1_500_000), 6, 6));
    }

    @Test public void formatTruncatesToMaxFractionDown() {
        assertEquals("1.23", EthWallet.format(BigInteger.valueOf(1_239_999), 6, 2));
    }

    @Test public void formatZeroAndNull() {
        assertEquals("0", EthWallet.format(null, 6, 6));
        assertEquals(0, new java.math.BigDecimal(EthWallet.format(BigInteger.ZERO, 6, 6)).signum());
    }
}
