package com.aioapp.mdm;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.SplashScreen;

/**
 * "Just a moment" over the screen while a locked tablet's host app is put back. When lock
 * task drops (the app crashed or was killed) the device is unlocked for the second or two
 * before MdmService re-locks it, and the guest would otherwise see the launcher. This covers
 * that gap in the AIO signature look (1.23.0): the dim scene, the AIO nav pill with the venue,
 * "Just a moment." with a shimmer line, and nothing to touch.
 *
 * It goes away by itself: ACTION_DONE when the device is locked again, or after
 * {@link #MAX_MS} whatever happens, so a host app that never comes back can't leave the
 * tablet stuck under it. Laid out in code like LostActivity.
 */
public class RelockCoverActivity extends Activity {
    static final String ACTION_DONE = "com.aioapp.mdm.action.RELOCK_COVER_DONE";
    private static final long MAX_MS = 8_000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::finish;

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            finish();
        }
    };

    /** Puts the cover up. Safe to call again while it is showing (singleInstance). */
    static void show(Context ctx) {
        try {
            // No transition and a plain-colour splash: the system's icon splash would show
            // first otherwise.
            ActivityOptions o = ActivityOptions.makeCustomAnimation(ctx, 0, 0);
            o.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);
            ctx.startActivity(new Intent(ctx, RelockCoverActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    o.toBundle());
        } catch (Exception ignored) {
        }
    }

    /** Takes the cover down, if it is up. */
    static void hide(Context ctx) {
        try {
            ctx.sendBroadcast(new Intent(ACTION_DONE).setPackage(ctx.getPackageName()));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        registerReceiver(done, new IntentFilter(ACTION_DONE), Context.RECEIVER_NOT_EXPORTED);
        setContentView(buildView());
        ui.postDelayed(timeout, MAX_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ui.removeCallbacks(timeout);
        ui.postDelayed(timeout, MAX_MS);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(timeout);
        try { unregisterReceiver(done); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* nothing behind it for a guest */ }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) { return true; }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return e.getKeyCode() != KeyEvent.KEYCODE_POWER || super.dispatchKeyEvent(e);
    }

    /**
     * The AIO signature moment: the dim scene drifting behind, the nav pill with the AIO mark
     * and the venue, and low on the left "Just a moment." with a shimmer line under it.
     */
    private View buildView() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new AioLook.Scene(this, true), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(32));
        col.addView(AioLook.navPill(this, AioLook.venueChip(this)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        TextView title = AioLook.headline(this, "Just a *moment.*", 38);
        col.addView(title, inset(0));
        TextView sub = AioLook.text(this, "Bringing the menu back.", AioLook.ON_DARK_80, 14, AioLook.REGULAR);
        col.addView(sub, inset(dp(10)));
        AioLook.Shimmer line = new AioLook.Shimmer(this);
        LinearLayout.LayoutParams lp = inset(dp(18));
        lp.height = dp(3);
        col.addView(line, lp);
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        AioLook.rise(title, 80);
        AioLook.rise(sub, 220);
        AioLook.rise(line, 320);
        return root;
    }

    private LinearLayout.LayoutParams inset(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = dp(8);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
