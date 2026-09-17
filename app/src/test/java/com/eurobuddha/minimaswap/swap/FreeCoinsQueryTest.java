package com.eurobuddha.minimaswap.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.eurobuddha.comms.NodeApi;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * myFreeCoins must ask the node ONLY for coins a send/lock will actually accept. `coins` defaults
 * checkmempool:false and still lists a coin committed to an unconfirmed transaction, which the node's
 * spend path then refuses; counting it as "free" skipped the split and the later lock failed funding.
 * Same flag the AtomiX APK carries; same defect class as minimaCore Desktop 0.17.2.
 */
public class FreeCoinsQueryTest {

    @Test public void freeCoinsQueryExcludesMempoolCommittedCoins() {
        NodeApi node = mock(NodeApi.class);
        final List<String> commands = new ArrayList<>();
        doAnswer(inv -> {
            commands.add(inv.getArgument(0));
            NodeApi.Cb cb = inv.getArgument(1);
            cb.onResult(new JSONObject().put("status", true).put("response", new JSONArray()));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));

        final boolean[] done = {false};
        new MinimaHtlc(node).myFreeCoins(arr -> done[0] = true, err -> { throw new AssertionError(err); });

        assertTrue("callback ran", done[0]);
        assertEquals("exactly one coins read", 1, commands.size());
        String q = commands.get(0);
        assertTrue("reads coins: " + q, q.startsWith("coins "));
        assertTrue("relevant only: " + q, q.contains("relevant:true"));
        assertTrue("sendable only: " + q, q.contains("sendable:true"));
        assertTrue("MUST exclude coins already committed in the mempool: " + q, q.contains("checkmempool:true"));
    }
}
