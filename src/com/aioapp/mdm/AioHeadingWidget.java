package com.aioapp.mdm;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * The "More" heading on page 2 of an unlocked T7's home screen (1.26.0), from the launcher
 * demos: ink on Linen, white on the dark Home styles. Redrawn with the AIO Home widget.
 */
public class AioHeadingWidget extends AppWidgetProvider {
    private static final String TAG = "AioHeadingWidget";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        awm.updateAppWidget(ids, views(ctx));
    }

    /** Redraws every heading on the device. Safe from any thread. */
    static void updateAll(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            if (awm == null) return;
            int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, AioHeadingWidget.class));
            if (ids != null && ids.length > 0) awm.updateAppWidget(ids, views(ctx));
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed: " + e.getMessage());
        }
    }

    private static RemoteViews views(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.home_heading);
        v.setTextColor(R.id.home_heading, "linen".equals(AioHomeWidget.style(ctx)) ? 0xFF111113 : 0xFFFFFFFF);
        return v;
    }
}
