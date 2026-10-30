package com.aioapp.mdm;

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
            + "\"android.theme.customization.system_palette\":\"F9674E\","
            + "\"android.theme.customization.accent_color\":\"F9674E\","
            + "\"android.theme.customization.theme_style\":\"TONAL_SPOT\"}";

    /**
     * The system's colours from AIO coral (1.31.0), not from the wallpaper (which gave Settings
     * and system dialogs a pink cast on Signature). Once: a different choice made later on the
     * tablet is kept.
     */
    static void accent(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String now = android.provider.Settings.Secure.getString(cr, THEME_KEY);
            if (now != null && now.contains("F9674E")) return;
            if (ctx.getSharedPreferences("aio_shade", Context.MODE_PRIVATE).getBoolean("accent_set", false)) return;
            android.provider.Settings.Secure.putString(cr, THEME_KEY, AIO_THEME);
            ctx.getSharedPreferences("aio_shade", Context.MODE_PRIVATE).edit().putBoolean("accent_set", true).apply();
            Log.i(TAG, "system colours: AIO coral");
        } catch (RuntimeException e) {
            Log.w(TAG, "can't set the system colours: " + e.getMessage());
        }
    }

    static void apply(Context ctx) {
        boolean light = "linen".equals(AioHomeWidget.style(ctx));
        try {
            OverlayManager om = ctx.getSystemService(OverlayManager.class);
            OverlayInfo info = om == null ? null : om.getOverlayInfo(LIGHT_OVERLAY, UserHandle.SYSTEM);
            if (info == null || info.isEnabled() == light) return;
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
}
