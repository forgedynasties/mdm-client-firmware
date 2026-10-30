package com.aioapp.mdm;

import android.app.UiModeManager;
import android.content.Context;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.os.UserHandle;
import android.util.Log;

/**
 * The swipe-down shade follows the Home style (1.27.0), as in the launcher demos: Linen's is
 * light, the other styles share the dark AIO shade. The firmware's SystemUI is the dark shade;
 * the AioShadeLight overlay (com.aioapp.overlay.shadelight) swaps in Linen's colours, so this
 * turns it on for Linen and off otherwise, then restarts SystemUI: its header and the colour
 * behind the shade are only read when it starts. Only when the shade changes, so a style change
 * (rare, at setup) costs one SystemUI restart. A firmware without the overlay is left alone.
 */
final class AioShade {
    private static final String TAG = "AioShade";
    static final String LIGHT_OVERLAY = "com.aioapp.overlay.shadelight";

    private AioShade() {}

    /** Secure setting ThemeOverlayController reads the system palette from. */
    private static final String THEME_KEY = "theme_customization_overlay_packages";
    private static final String AIO_THEME = "{\"android.theme.customization.color_source\":\"preset\","
            + "\"android.theme.customization.system_palette\":\"7A80F6\","
            + "\"android.theme.customization.accent_color\":\"7A80F6\","
            + "\"android.theme.customization.theme_style\":\"TONAL_SPOT\"}";

    /**
     * The system's colours from AIO violet (1.32.0), not from the wallpaper (which gave Settings
     * and system dialogs a pink cast on Signature). One seed makes all the palettes, so 1.31.0's
     * coral also tinted the dark backgrounds brown; violet's are the demos' cool ink, and is
     * the end of the coral-to-violet gradient. Once, and over 1.31.0's coral: a different choice
     * made later on the tablet is kept.
     */
    static void accent(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String now = android.provider.Settings.Secure.getString(cr, THEME_KEY);
            if (now != null && now.contains("7A80F6")) return;
            boolean coral = now != null && now.contains("F9674E");
            if (!coral && ctx.getSharedPreferences("aio_shade", Context.MODE_PRIVATE).getBoolean("accent_set", false)) return;
            android.provider.Settings.Secure.putString(cr, THEME_KEY, AIO_THEME);
            ctx.getSharedPreferences("aio_shade", Context.MODE_PRIVATE).edit().putBoolean("accent_set", true).apply();
            Log.i(TAG, "system colours: AIO violet");
        } catch (RuntimeException e) {
            Log.w(TAG, "can't set the system colours: " + e.getMessage());
        }
    }

    static void apply(Context ctx) {
        boolean light = "linen".equals(AioHomeWidget.style(ctx));
        try {
            OverlayManager om = ctx.getSystemService(OverlayManager.class);
            OverlayInfo info = om == null ? null : om.getOverlayInfo(LIGHT_OVERLAY, UserHandle.SYSTEM);
            if (info == null) return;
            nightMode(ctx, !light);
            if (info.isEnabled() == light) return;
            om.setEnabled(LIGHT_OVERLAY, light, UserHandle.SYSTEM);
            Log.i(TAG, "shade: " + (light ? "light (Linen)" : "dark") + "; restarting SystemUI");
            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                    Runtime.getRuntime().exec(new String[] {"am", "crash", "com.android.systemui"}).waitFor();
                } catch (Exception e) {
                    Log.w(TAG, "SystemUI restart: " + e.getMessage());
                }
            }, "AioShade").start();
        } catch (RuntimeException e) {
            Log.w(TAG, "can't switch the shade: " + e.getMessage());
        }
    }

    /**
     * The dark styles run the tablet in night mode (1.32.0): notifications in the dark shade,
     * Settings and system dialogs dark, as in the demos. Linen stays in day mode.
     *
     * Only when the style changes (or the first time), never on a plain service start: CTS
     * (UiModeManagerTest, RemoteViewsTest, BatterySaverTest, SplashscreenTests...) sets night
     * mode itself, and a client restart in the middle must not switch it back.
     */
    private static void nightMode(Context ctx, boolean dark) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences("aio_shade", Context.MODE_PRIVATE);
            String style = dark ? "dark" : "light";
            if (style.equals(p.getString("night_for", null))) return;
            UiModeManager ui = ctx.getSystemService(UiModeManager.class);
            if (ui == null) return;
            p.edit().putString("night_for", style).apply();
            int want = dark ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO;
            if (ui.getNightMode() == want) return;
            ui.setNightMode(want);
            Log.i(TAG, "night mode: " + (dark ? "on" : "off"));
        } catch (RuntimeException e) {
            Log.w(TAG, "can't set night mode: " + e.getMessage());
        }
    }
}
