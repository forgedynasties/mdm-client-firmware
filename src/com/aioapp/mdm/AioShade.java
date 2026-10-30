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
 * turns it on for Linen and off otherwise. A firmware without the overlay is left alone.
 */
final class AioShade {
    private static final String TAG = "AioShade";
    static final String LIGHT_OVERLAY = "com.aioapp.overlay.shadelight";

    private AioShade() {}

    static void apply(Context ctx) {
        boolean light = "linen".equals(AioHomeWidget.style(ctx));
        try {
            OverlayManager om = ctx.getSystemService(OverlayManager.class);
            OverlayInfo info = om == null ? null : om.getOverlayInfo(LIGHT_OVERLAY, UserHandle.SYSTEM);
            if (info == null || info.isEnabled() == light) return;
            om.setEnabled(LIGHT_OVERLAY, light, UserHandle.SYSTEM);
            Log.i(TAG, "shade: " + (light ? "light (Linen)" : "dark"));
        } catch (RuntimeException e) {
            Log.w(TAG, "can't switch the shade: " + e.getMessage());
        }
    }
}
