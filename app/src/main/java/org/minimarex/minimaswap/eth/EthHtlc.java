package org.minimarex.minimaswap.eth;

import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Ethereum leg of the swap — the shared ERC20 HTLC vault. Mirrors the bridge dapp's ethhtlcutil.js:
 * approve → newContract (lock) → withdraw (reveal preimage) → refund (after timelock). The hashlock and
 * preimage are SHA256-based to match the Minima side. All methods BLOCK on RPC — call off the main thread.
 */
public final class EthHtlc {

    /** keccak256("HTLCERC20Withdraw(bytes32,bytes32,bytes32)") — topic0 of the reveal event. */
    public static final String WITHDRAW_TOPIC = "0xae1c384441b246473ee31fdf0bd4cc25284d0cdb2c5258ada6b84b4550b9c058";

    /** topic0 of HTLCERC20New(contractId indexed, owner indexed, receiver indexed, …) — verbatim upstream. */
    public static final String NEW_TOPIC = "0x241f395d4e943ea32c5c6e0b8c523cb6fbf735af15880f21756155e7a5d576eb";

    private final EthRpc rpc;
    private final Credentials creds;
    private final EthNet net;

    public EthHtlc(EthRpc rpc, Credentials creds, EthNet net) {
        this.rpc = rpc; this.creds = creds; this.net = net;
    }

    /** ERC20 approve(htlc, amount) so the vault can pull the tokens. */
    public String approve(String token, BigInteger amount) throws Exception {
        Function f = new Function("approve",
                Arrays.asList(new Address(net.htlc), new Uint256(amount)),
                Collections.emptyList());
        return EthTx.send(rpc, creds, net.chainId, token, FunctionEncoder.encode(f), null, BigInteger.valueOf(100_000));
    }

    public BigInteger allowance(String token) throws Exception {
        Function f = new Function("allowance",
                Arrays.asList(new Address(creds.getAddress()), new Address(net.htlc)),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        String ret = rpc.ethCall(token, FunctionEncoder.encode(f));
        return EthRpc.hexToBig(ret);
    }

    /** Lock ERC20 into the HTLC. senderMinima = the locker's Minima pubkey (bytes32). */
    public String newContract(String senderMinima, String receiverEth, String hashlock,
                              BigInteger timelockUnix, String token, BigInteger amount,
                              BigInteger requestAmount, boolean otc) throws Exception {
        Function f = new Function("newContract", Arrays.asList(
                new Bytes32(b32(senderMinima)),
                new Address(receiverEth),
                new Bytes32(b32(hashlock)),
                new Uint256(timelockUnix),
                new Address(token),
                new Uint256(amount),
                new Uint256(requestAmount),
                new Bool(otc)),
                Collections.singletonList(new TypeReference<Bytes32>() {}));
        return EthTx.send(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, BigInteger.valueOf(500_000));
    }

    public String withdraw(String contractId, String preimage) throws Exception {
        Function f = new Function("withdraw",
                Arrays.asList(new Bytes32(b32(contractId)), new Bytes32(b32(preimage))),
                Collections.emptyList());
        return EthTx.send(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, BigInteger.valueOf(500_000));
    }

    public String refund(String contractId) throws Exception {
        Function f = new Function("refund",
                Collections.singletonList(new Bytes32(b32(contractId))),
                Collections.emptyList());
        return EthTx.send(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, BigInteger.valueOf(500_000));
    }

    /** contractId is deterministic: sha256(hashlock). Lets us locate a swap without scanning New events. */
    public static String contractId(String hashlock) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b32(hashlock));
            return Numeric.toHexString(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Read revealed preimages from HTLCERC20Withdraw logs between two block heights. */
    public List<Reveal> getReveals(BigInteger fromBlock, BigInteger toBlock) throws Exception {
        JSONObject filter = new JSONObject();
        try {
            filter.put("fromBlock", "0x" + fromBlock.toString(16));
            filter.put("toBlock", "0x" + toBlock.toString(16));
            filter.put("address", net.htlc);
            filter.put("topics", new JSONArray().put(WITHDRAW_TOPIC));
        } catch (Exception e) { throw new Exception("log filter: " + e.getMessage()); }

        JSONArray logs = rpc.getLogs(filter);
        List<Reveal> out = new ArrayList<>();
        for (int i = 0; i < logs.length(); i++) {
            JSONObject log = logs.optJSONObject(i);
            if (log == null) continue;
            JSONArray topics = log.optJSONArray("topics");
            if (topics == null || topics.length() < 4) continue;
            Reveal r = new Reveal();
            r.contractId = topics.optString(1);
            r.secret = topics.optString(2);    // the preimage
            r.hashlock = topics.optString(3);
            out.add(r);
        }
        return out;
    }

    public static final class Reveal {
        public String contractId, secret, hashlock;
    }

    /** Is this HTLC contract still collectable (not yet withdrawn or refunded)? — view call, mirrors upstream. */
    public boolean canCollect(String contractId) throws Exception {
        Function f = new Function("canCollect",
                Collections.singletonList(new Bytes32(b32(contractId))),
                Collections.singletonList(new TypeReference<Bool>() {}));
        String ret = rpc.ethCall(net.htlc, FunctionEncoder.encode(f));
        return EthRpc.hexToBig(ret).signum() != 0;
    }

    /**
     * Read a contract's full state by id via the {@code getContract} VIEW — an {@code eth_call} (current
     * state), NOT {@code eth_getLogs}, so it works on free/keyless RPCs that gate archive log queries.
     * Because {@code contractId = sha256(hashlock)} is deterministic, this lets us find a leg, check
     * withdrawn/refunded, and read the revealed {@code preimage} (secret) without ever scanning logs.
     * Returns null if the contract doesn't exist (zero sender).
     */
    public Contract getContract(String contractId) throws Exception {
        Function f = new Function("getContract",
                Collections.singletonList(new Bytes32(b32(contractId))),
                Arrays.asList(
                        new TypeReference<Address>() {}, new TypeReference<Bytes32>() {}, new TypeReference<Address>() {},
                        new TypeReference<Address>() {}, new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {},
                        new TypeReference<Bytes32>() {}, new TypeReference<Uint256>() {}, new TypeReference<Bool>() {},
                        new TypeReference<Bool>() {}, new TypeReference<Bytes32>() {}, new TypeReference<Bool>() {}));
        String ret = rpc.ethCall(net.htlc, FunctionEncoder.encode(f));
        if (ret == null || ret.length() < 3) return null;
        List<Type> d = FunctionReturnDecoder.decode(ret, f.getOutputParameters());
        if (d.size() < 12) return null;
        String sender = (String) d.get(0).getValue();
        if (sender == null || EthRpc.hexToBig(sender).signum() == 0) return null;   // zero address = no such contract
        Contract c = new Contract();
        c.contractId = contractId.startsWith("0x") ? contractId : "0x" + contractId;
        c.owner = sender;
        c.minimaPublicKey = Numeric.toHexString((byte[]) d.get(1).getValue());
        c.receiver = (String) d.get(2).getValue();
        c.tokenContract = (String) d.get(3).getValue();
        c.amount = (BigInteger) d.get(4).getValue();
        c.requestAmount = (BigInteger) d.get(5).getValue();
        c.hashlock = Numeric.toHexString((byte[]) d.get(6).getValue());
        c.timelock = ((BigInteger) d.get(7).getValue()).longValue();
        c.withdrawn = (Boolean) d.get(8).getValue();
        c.refunded = (Boolean) d.get(9).getValue();
        c.preimage = Numeric.toHexString((byte[]) d.get(10).getValue());
        c.otc = (Boolean) d.get(11).getValue();
        return c;
    }

    /** A decoded HTLCERC20New event — one locked ETH leg, discovered by scanning. Mirrors parseHTLCContractData. */
    public static final class Contract {
        public long block;
        public String txnhash;
        public String contractId;     // 0x… (sha256(hashlock))
        public String owner;          // ETH address that locked (sender)
        public String receiver;       // ETH address that can withdraw
        public String minimaPublicKey;// the locker's Minima pubkey (bytes32)
        public String tokenContract;  // ERC20 address
        public BigInteger amount;     // raw token amount (un-scaled)
        public BigInteger requestAmount; // raw MINIMA requested (18 dp)
        public String hashlock;       // 0x…
        public long timelock;         // unix seconds
        public boolean otc;
        public boolean withdrawn;     // (getContract only) claimed
        public boolean refunded;      // (getContract only) refunded
        public String preimage;       // (getContract only) the revealed secret, once withdrawn
    }

    /** New-contract events where I am the RECEIVER (legs I can claim/respond to). */
    public List<Contract> contractsAsReceiver(BigInteger from, BigInteger to) throws Exception {
        return contracts(from, to, null, padAddr(creds.getAddress()));
    }

    /** New-contract events where I am the OWNER (legs I locked — for refund discovery). */
    public List<Contract> contractsAsOwner(BigInteger from, BigInteger to) throws Exception {
        return contracts(from, to, padAddr(creds.getAddress()), null);
    }

    private List<Contract> contracts(BigInteger from, BigInteger to, String ownerTopic, String receiverTopic) throws Exception {
        JSONObject filter = new JSONObject();
        try {
            filter.put("fromBlock", "0x" + from.toString(16));
            filter.put("toBlock", "0x" + to.toString(16));
            filter.put("address", net.htlc);
            JSONArray topics = new JSONArray();
            topics.put(NEW_TOPIC);
            topics.put(JSONObject.NULL);                                   // contractId — any
            topics.put(ownerTopic == null ? JSONObject.NULL : ownerTopic); // owner
            topics.put(receiverTopic == null ? JSONObject.NULL : receiverTopic); // receiver
            filter.put("topics", topics);
        } catch (Exception e) { throw new Exception("new-log filter: " + e.getMessage()); }

        JSONArray logs = rpc.getLogs(filter);
        List<Contract> out = new ArrayList<>();
        for (int i = 0; i < logs.length(); i++) {
            JSONObject log = logs.optJSONObject(i);
            Contract c = parseNew(log);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** Decode one HTLCERC20New log into a Contract. Returns null on malformed input. */
    static Contract parseNew(JSONObject log) {
        if (log == null) return null;
        try {
            JSONArray topics = log.optJSONArray("topics");
            if (topics == null || topics.length() < 4) return null;
            Contract c = new Contract();
            c.block = EthRpc.hexToBig(log.optString("blockNumber", "0x0")).longValue();
            c.txnhash = log.optString("transactionHash", "");
            c.contractId = topics.optString(1);
            c.owner = "0x" + topics.optString(2).substring(26);     // last 20 bytes of the 32-byte topic
            c.receiver = "0x" + topics.optString(3).substring(26);

            // data = abi.encode(bytes32, address, uint256, uint256, bytes32, uint256, bool)
            List<TypeReference<?>> refs = Arrays.asList(
                    new TypeReference<Bytes32>() {}, new TypeReference<Address>() {},
                    new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {},
                    new TypeReference<Bytes32>() {}, new TypeReference<Uint256>() {},
                    new TypeReference<Bool>() {});
            List<Type> d = FunctionReturnDecoder.decode(log.optString("data", ""), org.web3j.abi.Utils.convert(refs));
            if (d.size() < 7) return null;
            c.minimaPublicKey = Numeric.toHexString((byte[]) d.get(0).getValue());
            c.tokenContract = ((String) d.get(1).getValue());
            c.amount = (BigInteger) d.get(2).getValue();
            c.requestAmount = (BigInteger) d.get(3).getValue();
            c.hashlock = Numeric.toHexString((byte[]) d.get(4).getValue());
            c.timelock = ((BigInteger) d.get(5).getValue()).longValue();
            c.otc = (Boolean) d.get(6).getValue();
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** Left-pad a 20-byte ETH address to a 32-byte topic (0x + 24 zero-nibbles + 40 addr hex). */
    private static String padAddr(String addr) {
        String a = addr.startsWith("0x") ? addr.substring(2) : addr;
        return "0x" + "000000000000000000000000" + a.toLowerCase();
    }

    private static byte[] b32(String hex) {
        byte[] b = Numeric.hexStringToByteArray(hex.startsWith("0x") ? hex : "0x" + hex);
        if (b.length == 32) return b;
        byte[] out = new byte[32];                       // left-pad / truncate to 32 bytes
        System.arraycopy(b, Math.max(0, b.length - 32), out, Math.max(0, 32 - b.length), Math.min(32, b.length));
        return out;
    }
}
