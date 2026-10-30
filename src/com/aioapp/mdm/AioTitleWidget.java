package com.aioapp.mdm;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Board's title over the Google search bar (1.28.0), from the launcher demo: the time and day,
 * whether the tablet is on a table (coral), the AIO mark. Redrawn with the AIO Home widget.
 */
public class AioTitleWidget extends AppWidgetProvider {
    private static final String TAG = "AioTitleWidget";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        awm.updateAppWidget(ids, views(ctx));
    }

    /** Redraws every title on the device. Safe from any thread. */
    static void updateAll(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            if (awm == null) return;
            int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, AioTitleWidget.class));
            if (ids != null && ids.length > 0) awm.updateAppWidget(ids, views(ctx));
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed: " + e.getMessage());
        }
    }

    private static RemoteViews views(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.home_title_board);
        v.setTextViewText(R.id.home_headline, AioHomeWidget.boardHeadline(GuestInfo.load(ctx)));
        return v;
    }
}
