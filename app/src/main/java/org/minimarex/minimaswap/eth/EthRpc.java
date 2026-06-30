package org.minimarex.minimaswap.eth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Ethereum JSON-RPC client over HttpURLConnection — no okhttp, no web3j HTTP service.
 * All methods are SYNCHRONOUS and BLOCKING: call them off the main thread (the UI uses an executor).
 */
public final class EthRpc {

    private volatile String url;

    public EthRpc(String url) { this.url = url; }
    public void setUrl(String url) { this.url = url; }
    public String url() { return url; }

    /** Raw JSON-RPC call. Returns the {@code result} node (String for scalars, JSONArray/JSONObject otherwise). */
    public Object call(String method, JSONArray params) throws IOException {
        HttpURLConnection c = null;
        try {
            JSONObject payload = new JSONObject()
                    .put("jsonrpc", "2.0").put("id", 1).put("method", method)
                    .put("params", params == null ? new JSONArray() : params);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            JSONObject resp = new JSONObject(body);
            if (resp.has("error")) {
                JSONObject err = resp.optJSONObject("error");
                throw new IOException("ETH RPC " + method + ": " + (err == null ? body : err.optString("message", body)));
            }
            return resp.opt("result");
        } catch (JSONException e) {
            throw new IOException("ETH RPC " + method + " parse error: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public String callStr(String method, JSONArray params) throws IOException {
        Object r = call(method, params);
        return r == null ? null : r.toString();
    }

    public JSONArray getLogs(JSONObject filter) throws IOException {
        Object r = call("eth_getLogs", new JSONArray().put(filter));
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    public BigInteger getBalance(String address) throws IOException {
        return hexToBig(callStr("eth_getBalance", new JSONArray().put(address).put("latest")));
    }

    public BigInteger getTransactionCount(String address) throws IOException {
        return hexToBig(callStr("eth_getTransactionCount", new JSONArray().put(address).put("pending")));
    }

    public BigInteger blockNumber() throws IOException {
        return hexToBig(callStr("eth_blockNumber", new JSONArray()));
    }

    /** eth_call to a contract; returns the raw hex return data ("0x...."). */
    public String ethCall(String to, String data) throws IOException {
        try {
            JSONObject tx = new JSONObject().put("to", to).put("data", data);
            return callStr("eth_call", new JSONArray().put(tx).put("latest"));
        } catch (JSONException e) {
            throw new IOException("ETH eth_call build error: " + e.getMessage());
        }
    }

    public String sendRawTransaction(String signedHex) throws IOException {
        return callStr("eth_sendRawTransaction", new JSONArray().put(signedHex));
    }

    public static BigInteger hexToBig(String hex) {
        if (hex == null) return BigInteger.ZERO;
        hex = hex.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.isEmpty()) return BigInteger.ZERO;
        return new BigInteger(hex, 16);
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
