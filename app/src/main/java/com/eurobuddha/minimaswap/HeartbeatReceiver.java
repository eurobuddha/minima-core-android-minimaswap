package com.eurobuddha.minimaswap;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Doze-proof publish heartbeat. The service's 90s Handler loop stalls when the CPU suspends (a foreground
 * service keeps the process resident but does NOT exempt postDelayed from Doze), so overnight the market
 * keep-alive collapsed to Doze maintenance windows. An exact allow-while-idle alarm is the only wake
 * mechanism that fires reliably — each firing chains the next one and drives a single service tick pass.
 *
 * FGS-start-from-receiver is legal here: the app holds the battery-optimization exemption (the same path
 * WorkManager already uses to relaunch the service).
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final long INTERVAL_MS = 15 * 60_000;
    private static final int REQUEST_CODE = 12;   // fixed + FLAG_UPDATE_CURRENT → rescheduling is idempotent

    @Override public void onReceive(Context ctx, Intent intent) {
        SwapLog.d("heartbeat alarm");
        schedule(ctx);   // chain the next one FIRST — a crash below must not end the heartbeat
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, SwapService.class).setAction(SwapService.ACTION_HEARTBEAT));
        } catch (Exception e) {
            SwapLog.w("heartbeat FGS start: " + e.getClass().getSimpleName());   // worker/alarm relaunch retries
        }
    }

    /** (Re)schedule the next heartbeat ~15 min out. Safe to call from anywhere, any number of times. */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE,
                    new Intent(ctx, HeartbeatReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + INTERVAL_MS;
            // USE_EXACT_ALARM (API 33+) is auto-granted, so canScheduleExactAlarms() is normally true;
            // fall back to inexact allow-while-idle (still fires under Doze, just batched) if not.
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            SwapLog.w("heartbeat schedule: " + e.getClass().getSimpleName());
        }
    }
}
