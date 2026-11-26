package com.aioapp.mdm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * The server's Lock and Lost switches (client 1.22.5, 26 Nov 2026): config keys
 * {@code lock_allowed} and {@code lost_allowed}, sent by servers from 1.74.0.
 *
 * <p>Lock off: the device is never locked, whatever a cached config, a boot, the watchdog or
 * the staff pill says. Staff mode, a pending re-lock and a local exit are dropped, and there
 * is no pill and no PIN pad. Before this, "Back to guest mode" wrote a lock into the cached
 * config and the server's unchanged "unlocked" was never applied again, so the tablet stayed
 * locked until the service restarted.
 *
 * <p>Lost off: lost mode is left if it is on, and self-lock never starts it.
 *
 * <p>Kept in device-protected storage so it holds offline, before unlock and across reboots.
 * A server that doesn't send the keys (older) leaves the stored values as they are; the
 * default is "allowed", which is how every client before this behaved.
 */
final class ServerSwitches {
    private static final String PREFS = "mdm_server_switches";
    private static final String K_LOCK_OFF = "lock_off";
    private static final String K_LOST_OFF = "lost_off";

    private ServerSwitches() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Stores lock_allowed / lost_allowed from a config when present. */
    static void save(Context ctx, JSONObject config) {
        if (config == null) return;
        SharedPreferences.Editor e = prefs(ctx).edit();
        boolean any = false;
        if (config.has("lock_allowed")) { e.putBoolean(K_LOCK_OFF, !config.optBoolean("lock_allowed", true)); any = true; }
        if (config.has("lost_allowed")) { e.putBoolean(K_LOST_OFF, !config.optBoolean("lost_allowed", true)); any = true; }
        if (any) e.commit();
    }

    static boolean lockOff(Context ctx) { return prefs(ctx).getBoolean(K_LOCK_OFF, false); }
    static boolean lostOff(Context ctx) { return prefs(ctx).getBoolean(K_LOST_OFF, false); }
}
