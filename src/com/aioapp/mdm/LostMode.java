package com.aioapp.mdm;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lost mode: the device shows {@link LostActivity} — a message, a phone number, its serial —
 * pinned with lock task, until the server lifts it with {@code lost_mode {"on": false}}.
 * Nothing on the device lifts it: the exit PIN is in every client, so it cannot be what
 * releases a device someone has walked off with.
 *
 * Lost mode sits above lock (kiosk): while it is on, {@link KioskManager#apply} does nothing
 * but re-assert it, so a config push or the kiosk watchdog can never swap the lost screen
 * for the kiosk app. Lifting it re-applies the saved kiosk config, which is how the device
 * gets back exactly the lock state it should have — the server keeps that config current
 * the whole time the device is lost.
 *
 * State lives in device-protected storage, like the kiosk config, so lost mode is back on
 * screen from LOCKED_BOOT_COMPLETED, before anyone unlocks the device.
 */
final class LostMode {
    private static final String TAG = "LostMode";
    private static final String PREFS = "mdm_lost_mode";
    private static final String K_ON = "on";
    private static final String K_SINCE = "since_ms";
    private static final String K_MESSAGE = "message";
    private static final String K_PHONE = "phone";
    private static final String K_SERIAL = "serial";
    private static final String K_BEACON = "beacon"; // BLE token from the server (1.13.0), hex
    private static final String K_SELF = "self";     // turned on by SelfLock, not the server

    static final int MAX_MESSAGE = 280;
    static final int MAX_PHONE = 40;

    /** Sent to LostActivity so an open lost screen closes the moment lost mode is lifted. */
    static final String ACTION_LIFTED = "com.aioapp.mdm.action.LOST_MODE_LIFTED";
    /** Sent to LostActivity when the message or phone changes while it is up. */
    static final String ACTION_CHANGED = "com.aioapp.mdm.action.LOST_MODE_CHANGED";

    private LostMode() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context ctx) { return prefs(ctx).getBoolean(K_ON, false); }
    static String message(Context ctx) { return prefs(ctx).getString(K_MESSAGE, ""); }
    static String phone(Context ctx) { return prefs(ctx).getString(K_PHONE, ""); }
    static String serial(Context ctx) { return prefs(ctx).getString(K_SERIAL, ""); }
    /** The BLE beacon token the server gave this lost session ("" when none). */
    static String beacon(Context ctx) { return prefs(ctx).getString(K_BEACON, ""); }
    /** Lost mode was put up by the device itself when it left its venue (SelfLock). */
    static boolean isSelf(Context ctx) { return isOn(ctx) && prefs(ctx).getBoolean(K_SELF, false); }

    /** extra.lost_mode: {"on": true, "since_ms", "message", "self"?} or {"on": false}. */
    static JSONObject report(Context ctx) throws JSONException {
        JSONObject o = new JSONObject();
        boolean on = isOn(ctx);
        o.put("on", on);
        if (on) {
            o.put("since_ms", prefs(ctx).getLong(K_SINCE, 0));
            o.put("message", message(ctx));
            if (isSelf(ctx)) o.put("self", true);
        }
        return o;
    }

    /** Turn lost mode on (or update its text while on) and put the lost screen up. */
    static void turnOn(Context ctx, DevicePolicyManager dpm, ComponentName admin,
                       String message, String phone, String serial) {
        turnOn(ctx, dpm, admin, message, phone, serial, "", false);
    }

    /**
     * As above, with the server's BLE beacon token (1.13.0) and whether the device is doing
     * this itself (SelfLock). A server command always clears "self": once the dashboard has
     * marked it lost, only the dashboard lifts it.
     */
    static void turnOn(Context ctx, DevicePolicyManager dpm, ComponentName admin,
                       String message, String phone, String serial, String beacon, boolean self) {
        if (ServerSwitches.lostOff(ctx)) {
            Log.i(TAG, "lost mode is switched off on the server: not turning it on");
            return;
        }
        SharedPreferences p = prefs(ctx);
        String token = beacon == null ? "" : beacon.trim().toLowerCase(java.util.Locale.ROOT);
        if (!token.matches("[0-9a-f]{16}")) token = "";
        SharedPreferences.Editor e = p.edit()
                .putBoolean(K_ON, true)
                .putString(K_MESSAGE, clip(message, MAX_MESSAGE))
                .putString(K_PHONE, clip(phone, MAX_PHONE))
                .putString(K_SERIAL, serial == null ? "" : serial)
                .putString(K_BEACON, token)
                .putBoolean(K_SELF, self);
        if (!p.getBoolean(K_ON, false)) e.putLong(K_SINCE, System.currentTimeMillis());
        e.commit(); // committed before the screen goes up, so a crash right after keeps it
        try {
            ctx.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(ctx.getPackageName()));
        } catch (Exception ignored) {
        }
        enforce(ctx, dpm, admin);
        Log.i(TAG, "lost mode on");
    }

    /**
     * Lift lost mode: close the lost screen, leave its lock task, and re-apply the saved kiosk
     * config so the device is locked to its app again if it should be.
     */
    static void lift(Context ctx, DevicePolicyManager dpm, ComponentName admin) {
        prefs(ctx).edit().clear().commit();
        try {
            ctx.sendBroadcast(new Intent(ACTION_LIFTED).setPackage(ctx.getPackageName()));
        } catch (Exception ignored) {
        }
        KioskManager.stopSystemLockTask();
        KioskManager.clearLockTaskAllowlist(dpm, admin);
        JSONObject cfg = KioskManager.loadConfig(ctx);
        boolean relock = cfg != null && cfg.optBoolean("kiosk_enabled", false)
                && !KioskExit.isSuspended(ctx);
        if (cfg != null) {
            KioskManager.apply(ctx, dpm, admin, cfg);
        } else {
            KioskManager.releaseAdminIfUnused(ctx, dpm, admin);
        }
        if (!relock) KioskManager.goHome(ctx);
        Log.i(TAG, "lost mode lifted (relock=" + relock + ")");
    }

    /**
     * Put the lost screen up if lost mode is on and it isn't already LOCKED in front. Called
     * from turnOn, boot, service start, the kiosk watchdog and every KioskManager.apply.
     */
    static void enforce(Context ctx, DevicePolicyManager dpm, ComponentName admin) {
        if (!isOn(ctx) || KioskManager.isFactoryTestBoot()) return;
        try {
            if (KioskManager.isProperlyLocked(ctx)
                    && ctx.getPackageName().equals(KioskManager.foregroundPackage(ctx))) {
                return;
            }
            if (!KioskManager.ensureAdmin(dpm, admin)) return;
            // Only this package: the kiosk app must not be reachable from the lost screen.
            dpm.setLockTaskPackages(admin, new String[]{ctx.getPackageName()});
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            Settings.Global.putString(ctx.getContentResolver(), Settings.Global.POLICY_CONTROL, "");
            if (KioskManager.lockState(ctx) == ActivityManager.LOCK_TASK_MODE_PINNED) {
                KioskManager.stopSystemLockTask();
            }
            // Same entry as kiosk: a freshly created activity with setLockTaskEnabled is the
            // only way into LOCK_TASK_MODE_LOCKED (see KioskManager.apply).
            Intent i = new Intent(ctx, LostActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLockTaskEnabled(true);
            ctx.startActivity(i, options.toBundle());
        } catch (Exception e) {
            Log.e(TAG, "enforce error: " + e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
