package com.eurobuddha.minimaswap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Restart the market keeper the moment the device (or, in Secure Folder, the folder's user) comes up
 * after a reboot — and after an APK update. Before this receiver, post-reboot recovery rode only the
 * 15-min WorkManager job, and the 2026-07-07 outage showed the full failure chain: reboot → Secure
 * Folder locked → hours later WorkManager relaunched a service that then sat inert because the node app
 * wasn't running (the pairing self-heal in SwapService fixes that half).
 *
 * Starting a specialUse FGS from BOOT_COMPLETED is legal — Android 15's boot-time FGS restriction list
 * covers dataSync, camera, media playback/projection, microphone and phoneCall, not specialUse (which
 * SwapService uses on API 34+).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context ctx, Intent intent) {
        SwapLog.d("boot/update: " + (intent == null ? "?" : intent.getAction()));
        try { SwapWorker.schedule(ctx); } catch (Exception ignored) {}
        HeartbeatReceiver.schedule(ctx);
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, SwapService.class));
        } catch (Exception e) {
            SwapLog.w("boot FGS start: " + e.getClass().getSimpleName());   // worker/heartbeat retry shortly
        }
    }
}
