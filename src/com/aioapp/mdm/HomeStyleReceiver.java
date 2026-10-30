package com.aioapp.mdm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Sets the unlocked T7's Home style from the MDM shell (1.28.2):
 * {@code am broadcast -a com.aioapp.mdm.SET_HOME_STYLE -n com.aioapp.mdm/.HomeStyleReceiver --es style board}.
 * The shell can't write it with {@code settings put}: it runs as system with no package, which
 * SettingsProvider refuses. Only senders holding WRITE_SECURE_SETTINGS (system, shell) get in.
 */
public class HomeStyleReceiver extends BroadcastReceiver {
    private static final String TAG = "HomeStyleReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String style = intent.getStringExtra("style");
        if (!"linen".equals(style) && !"signature".equals(style) && !"board".equals(style)
                && !"setup".equals(style)) {
            Log.w(TAG, "unknown Home style: " + style);
            setResultData("unknown style: " + style);
            return;
        }
        Settings.Global.putString(ctx.getContentResolver(), AioHomeWidget.STYLE_KEY, style);
        Log.i(TAG, "Home style " + style);
        setResultData("home style " + style);
    }
}
