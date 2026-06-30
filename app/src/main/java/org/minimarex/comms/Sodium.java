package org.minimarex.comms;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;

/**
 * Single libsodium instance for the app (Android backend). The {@code comms} core classes take a
 * {@link LazySodium} by injection so the exact same logic can be exercised on a desktop JVM
 * (LazySodiumJava) in tests — this just supplies the Android backend at runtime.
 */
public final class Sodium {
    private static LazySodium INSTANCE;

    public static synchronized LazySodium get() {
        if (INSTANCE == null) INSTANCE = new LazySodiumAndroid(new SodiumAndroid());
        return INSTANCE;
    }

    private Sodium() {}
}
