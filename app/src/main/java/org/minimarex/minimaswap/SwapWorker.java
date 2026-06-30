package org.minimarex.minimaswap;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Periodic fallback: if the OS kills {@link SwapService}, WorkManager re-launches it so in-flight swaps
 * keep progressing (claim the counter-leg, refund on timeout) while the app is closed. The worker does no
 * node work itself — it just ensures the foreground service is alive.
 */
public class SwapWorker extends Worker {

    private static final String UNIQUE = "minimaswap_watch";

    public SwapWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull
    @Override
    public Result doWork() {
        try {
            ContextCompat.startForegroundService(getApplicationContext(),
                    new Intent(getApplicationContext(), SwapService.class));
        } catch (Exception ignored) {}
        return Result.success();
    }

    /** Schedule the ~15-minute fallback (WorkManager's minimum period). */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SwapWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
