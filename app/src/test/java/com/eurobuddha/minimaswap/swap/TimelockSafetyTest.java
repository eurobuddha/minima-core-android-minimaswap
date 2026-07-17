package com.eurobuddha.minimaswap.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * STEP: timelock ordering — the property that makes the swap ATOMIC rather than a way to lose money, and the
 * invariant the F2 fix (chain-time lock timelocks) protects. The initiator locks the first leg (long
 * timelock) and knows the secret; the responder locks a counter-leg (short timelock) and does not. For nobody
 * to be able to BOTH refund their own leg AND claim the other's, the counter-leg must always expire strictly
 * before the first leg, with margin. These raw-value pins fail if an edit ever weakens the ordering or drifts
 * a window; all values are compile-time constants so this reads them without loading the Android-coupled
 * SwapEngine.
 *
 * Wall-clock (MINIMA_BLOCK_TIME = 50 s/block): first leg 144 blk / 7200 s = 2 h; half-window gate 72 blk /
 * 3600 s = 1 h; counter-leg 36 blk / 1800 s = 30 min.
 */
public class TimelockSafetyTest {

    @Test public void counterLegShorterThanFirstLeg() {
        assertTrue(SwapEngine.CP_SECS < SwapEngine.TIMELOCK_SECS);
        assertTrue(SwapEngine.CP_BLOCKS < SwapEngine.TIMELOCK_BLOCKS);
    }

    @Test public void counterLegExpiresBeforeGuaranteedRemainingWindow() {
        assertTrue(SwapEngine.CP_SECS < SwapEngine.CP_SECS_CHECK);
        assertTrue(SwapEngine.CP_BLOCKS < SwapEngine.CP_BLOCKS_CHECK);
    }

    @Test public void safetyMarginIsAtLeastThirtyMinutes() {
        long ethMarginSecs = SwapEngine.CP_SECS_CHECK - SwapEngine.CP_SECS;
        long minimaMarginSecs = (long) (SwapEngine.CP_BLOCKS_CHECK - SwapEngine.CP_BLOCKS)
                * MinimaHtlc.MINIMA_BLOCK_TIME;
        assertTrue("ETH claim margin ≥ 30 min", ethMarginSecs >= 30 * 60);
        assertTrue("Minima claim margin ≥ 30 min", minimaMarginSecs >= 30 * 60);
    }

    @Test public void firstLegWindowsArePinned() {
        assertEquals(144, SwapEngine.TIMELOCK_BLOCKS);
        assertEquals(7200, SwapEngine.TIMELOCK_SECS);
    }

    @Test public void halfWindowGatesArePinned() {
        assertEquals(72, SwapEngine.CP_BLOCKS_CHECK);
        assertEquals(3600, SwapEngine.CP_SECS_CHECK);
    }

    @Test public void counterLegWindowsArePinned() {
        assertEquals(36, SwapEngine.CP_BLOCKS);
        assertEquals(1800, SwapEngine.CP_SECS);
    }

    @Test public void blockAndSecondWindowsAgreeInWallClock() {
        int bt = MinimaHtlc.MINIMA_BLOCK_TIME;
        assertEquals(SwapEngine.TIMELOCK_SECS, (long) SwapEngine.TIMELOCK_BLOCKS * bt);
        assertEquals(SwapEngine.CP_SECS, (long) SwapEngine.CP_BLOCKS * bt);
        assertEquals(SwapEngine.CP_SECS_CHECK, (long) SwapEngine.CP_BLOCKS_CHECK * bt);
    }
}
