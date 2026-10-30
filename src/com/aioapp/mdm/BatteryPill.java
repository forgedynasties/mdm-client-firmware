package com.aioapp.mdm;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The low-battery moment in the AIO signature look (1.23.0): a glass pill with a coral-to-violet
 * edge drops in at the top of a locked tablet, "Battery low. Please put me on my stand.", stays
 * {@link #SHOW_MS} and goes. It speaks at 15, 10 and 5 % on the way down and again every
 * {@link #REPEAT_MS} while still low, and never while the tablet is plugged in, so a charger that
 * flaps doesn't make it blink. A tap puts it away early.
 *
 * A system window like {@link StaffPill}, over the menu app (which runs full screen), and it
 * takes no focus: the guest's typing and the menu are untouched under it.
 */
final class BatteryPill {
    private static final String TAG = "BatteryPill";
    private static final int[] STEPS = {15, 10, 5};
    /** Back above this, the steps count again. */
    private static final int RESET_ABOVE = 20;
    private static final long SHOW_MS = 8_000L;
    private static final long REPEAT_MS = 10 * 60_000L;
    private static final String BOLT = "M13,2L4,14h7l-1,8l9,-12h-7Z";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideLater = this::hide;
    private View view;
    private int lowestStep = Integer.MAX_VALUE;
    private long lastShownMs;

    BatteryPill(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * A battery broadcast. {@code guests} is true while the tablet is locked for guests (lock
     * task on, not lost, not in staff mode): the only time the pill is the tablet's to show.
     */
    void onBattery(Intent b, boolean guests) {
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = Math.round(level * 100f / scale);
        boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (pct > RESET_ABOVE) lowestStep = Integer.MAX_VALUE;
        if (plugged || !guests) {
            hide();
            return;
        }
        int step = Integer.MAX_VALUE;
        for (int s : STEPS) if (pct <= s) step = s;
        if (step == Integer.MAX_VALUE) return;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean newStep = step < lowestStep;
        boolean repeat = lastShownMs != 0 && now - lastShownMs >= REPEAT_MS;
        if (!newStep && !repeat) return;
        lowestStep = Math.min(lowestStep, step);
        lastShownMs = now;
        show(pct);
    }

    private void show(int pct) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> show(pct));
            return;
        }
        hideNow();
        View v = build(pct);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.setFitInsetsTypes(0);
        lp.setTitle("AioBatteryPill");
        try {
            ctx.getSystemService(WindowManager.class).addView(v, lp);
            view = v;
            View pill = ((FrameLayout) v).getChildAt(0);
            pill.setAlpha(0f);
            pill.setTranslationY(-AioLook.dp(ctx, 24));
            pill.animate().alpha(1f).translationY(0f).setDuration(800)
                    .setInterpolator(new DecelerateInterpolator(2.2f)).start();
            main.removeCallbacks(hideLater);
            main.postDelayed(hideLater, SHOW_MS);
            Log.i(TAG, "battery low (" + pct + "%): pill up");
        } catch (RuntimeException e) {
            Log.w(TAG, "can't add the battery pill: " + e.getMessage());
        }
    }

    /** Fades the pill out, if it is up. Safe from any thread. */
    void hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::hide);
            return;
        }
        main.removeCallbacks(hideLater);
        if (view == null) return;
        View v = view;
        View pill = ((FrameLayout) v).getChildAt(0);
        pill.animate().alpha(0f).translationY(-AioLook.dp(ctx, 24)).setDuration(400)
                .withEndAction(() -> remove(v)).start();
        view = null;
    }

    private void hideNow() {
        main.removeCallbacks(hideLater);
        if (view != null) remove(view);
        view = null;
    }

    private void remove(View v) {
        try {
            ctx.getSystemService(WindowManager.class).removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private View build(int pct) {
        int dp14 = AioLook.dp(ctx, 14);
        FrameLayout frame = new FrameLayout(ctx);
        frame.setPadding(dp14, dp14, dp14, 0);

        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int r = AioLook.dp(ctx, 99), edge = AioLook.dp(ctx, 1);
        GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {AioLook.CORAL, AioLook.VIOLET});
        ring.setCornerRadius(r);
        LayerDrawable bg = new LayerDrawable(new android.graphics.drawable.Drawable[] {ring,
                AioLook.rounded(Color.argb(235, 17, 17, 19), r, 0, 0)});
        bg.setLayerInset(1, edge, edge, edge, edge);
        pill.setBackground(bg);
        pill.setElevation(AioLook.dp(ctx, 8));
        pill.setPadding(AioLook.dp(ctx, 7), AioLook.dp(ctx, 7), AioLook.dp(ctx, 16), AioLook.dp(ctx, 7));

        FrameLayout dot = new FrameLayout(ctx);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {AioLook.CORAL, AioLook.VIOLET});
        d.setShape(GradientDrawable.OVAL);
        dot.setBackground(d);
        dot.addView(new AioLook.Icon(ctx, BOLT, Color.WHITE),
                new FrameLayout.LayoutParams(AioLook.dp(ctx, 16), AioLook.dp(ctx, 16), Gravity.CENTER));
        pill.addView(dot, new LinearLayout.LayoutParams(AioLook.dp(ctx, 34), AioLook.dp(ctx, 34)));

        SpannableStringBuilder s = new SpannableStringBuilder("Battery low. ");
        s.setSpan(new TypefaceSpan(AioLook.MEDIUM), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append("Please put me on my stand.");
        TextView t = AioLook.text(ctx, "", Color.WHITE, 13, AioLook.REGULAR);
        t.setText(s);
        t.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = AioLook.dp(ctx, 12);
        tl.rightMargin = AioLook.dp(ctx, 8);
        pill.addView(t, tl);
        pill.addView(AioLook.text(ctx, pct + "%", AioLook.CORAL, 12, AioLook.REGULAR));

        pill.setContentDescription("Battery low, " + pct + " percent. Please put me on my stand.");
        pill.setOnClickListener(x -> hide());
        frame.addView(pill, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        return frame;
    }
}
