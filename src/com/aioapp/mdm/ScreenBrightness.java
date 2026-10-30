package com.aioapp.mdm;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Screen brightness set from the dashboard (1.14.0), optionally locked.
 *
 * <p>The server sends {@code screen_brightness} (1-255, null when not managed) and
 * {@code screen_brightness_locked} in every config. A level is applied when the dashboard
 * value changes, not on every check-in, so an unlocked tablet keeps whatever staff set
 * afterwards. Locked, the level is held:
 * <ul>
 * <li>{@code DISALLOW_CONFIG_BRIGHTNESS} goes on as a base user restriction. With
 *     SystemUI's enforce_brightness_base_user_restriction flag (on in A15) that greys out
 *     the Quick Settings slider without a device owner, and SettingsProvider refuses
 *     brightness writes from every app but the system uid — which this client is.</li>
 * <li>Auto-brightness goes off and an observer puts the level back if anything moves it.</li>
 * </ul>
 * The level is clamped to the platform's slider range first (the T7 caps at 122): asking
 * for more would be clamped by the display stack, read back lower and re-asserted forever.
 * The restriction is only lifted if this class put it on.
 */
final class ScreenBrightness {
    private static final String TAG = "ScreenBrightness";
    private static final String PREFS = "screen_brightness";
    private static final String KEY_LEVEL = "level";            // -1 = not managed
    private static final String KEY_LOCKED = "locked";
    private static final String KEY_RESTRICTED = "restricted";  // we set the user restriction

    /** Re-asserting more often than this means something keeps fighting us; let it settle. */
    private static final long REASSERT_MIN_MS = 2000;

    private static ContentObserver observer;
    private static long lastReassertMs;

    private ScreenBrightness() {}

    /** Takes the brightness keys from a config; a config without them leaves things alone. */
    static void saveConfig(Context ctx, JSONObject config) {
        if (!config.has("screen_brightness")) return;
        int level = config.isNull("screen_brightness") ? -1 : config.optInt("screen_brightness", -1);
        if (level < 1 || level > 255) level = -1;
        boolean locked = level > 0 && config.optBoolean("screen_brightness_locked", false);
        SharedPreferences p = prefs(ctx);
        if (p.getInt(KEY_LEVEL, -1) == level && p.getBoolean(KEY_LOCKED, false) == locked) return;
        p.edit().putInt(KEY_LEVEL, level).putBoolean(KEY_LOCKED, locked).apply();
        Log.i(TAG, "dashboard brightness " + (level < 0 ? "not managed" : level)
                + (locked ? " (locked)" : ""));
        apply(ctx, level, locked, true);
    }

    /** Service start: a locked level is held again (the observer does not survive a restart). */
    static void restore(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int level = p.getInt(KEY_LEVEL, -1);
        boolean locked = p.getBoolean(KEY_LOCKED, false);
        if (locked || p.getBoolean(KEY_RESTRICTED, false)) apply(ctx, level, locked, locked);
    }

    /** What the device shows now, for telemetry. */
    static void report(Context ctx, JSONObject extra) throws JSONException {
        ContentResolver cr = ctx.getContentResolver();
        int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
        if (cur >= 0) extra.put("screen_brightness", cur);
        extra.put("screen_brightness_auto", Settings.System.getInt(cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        extra.put("screen_brightness_locked", restricted(ctx));
    }

    private static synchronized void apply(Context ctx, int level, boolean locked, boolean setLevel) {
        ContentResolver cr = ctx.getContentResolver();
        int target = clamp(level);
        if (setLevel && target > 0) {
            try {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target);
                if (target != level) Log.i(TAG, "level " + level + " clamped to " + target);
            } catch (Exception e) {
                Log.e(TAG, "set brightness failed: " + e);
            }
        }
        setRestriction(ctx, locked);
        if (locked && target > 0) watch(ctx, target);
        else unwatch(ctx);
    }

    private static void setRestriction(Context ctx, boolean on) {
        SharedPreferences p = prefs(ctx);
        if (!on && !p.getBoolean(KEY_RESTRICTED, false)) return; // not ours to lift
        try {
            UserManager um = ctx.getSystemService(UserManager.class);
            um.setUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS, on);
            p.edit().putBoolean(KEY_RESTRICTED, on).apply();
        } catch (Exception e) {
            Log.e(TAG, "brightness restriction " + (on ? "on" : "off") + " failed: " + e);
        }
    }

    private static boolean restricted(Context ctx) {
        try {
            return ctx.getSystemService(UserManager.class)
                    .hasUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS);
        } catch (Exception e) {
            return false;
        }
    }

    private static void watch(Context ctx, int target) {
        Context app = ctx.getApplicationContext();
        ContentResolver cr = app.getContentResolver();
        unwatch(app);
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
                int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                if (cur == target && mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastReassertMs < REASSERT_MIN_MS) return;
                lastReassertMs = now;
                Log.i(TAG, "brightness moved to " + cur + (mode != 0 ? " (auto)" : "")
                        + ", holding " + target);
                try {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target);
                } catch (Exception e) {
                    Log.e(TAG, "re-assert failed: " + e);
                }
            }
        };
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false, observer);
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                false, observer);
        observer.onChange(false); // catch a move made while nothing was watching
    }

    private static void unwatch(Context ctx) {
        if (observer == null) return;
        ctx.getApplicationContext().getContentResolver().unregisterContentObserver(observer);
        observer = null;
    }

    /** The dashboard holds the level: staff can't change it on the tablet. */
    static boolean heldByDashboard(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LOCKED, false);
    }

    /** The platform slider range, for the staff panel's slider. */
    static int minLevel() { return sysInt("config_screenBrightnessSettingMinimum", 1); }
    static int maxLevel() { return sysInt("config_screenBrightnessSettingMaximum", 255); }

    /** Into the platform slider range (config_screenBrightnessSettingMinimum..Maximum). */
    private static int clamp(int level) {
        if (level <= 0) return level;
        return Math.max(sysInt("config_screenBrightnessSettingMinimum", 1),
                Math.min(level, sysInt("config_screenBrightnessSettingMaximum", 255)));
    }

    private static int sysInt(String name, int def) {
        try {
            Resources r = Resources.getSystem();
            int id = r.getIdentifier(name, "integer", "android");
            return id != 0 ? r.getInteger(id) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
