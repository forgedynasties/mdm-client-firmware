package com.aioapp.mdm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.PathParser;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The AIO signature look (picked 30 Oct 2026, from aioapp.com) that the client's own screens
 * share: a dim restaurant scene that drifts slowly, a glass nav pill with the AIO mark,
 * Poppins Light headlines with one word lit in coral, and the coral-to-violet pill.
 *
 * Everything is drawn in code like the other screens here: the mark and the icons are path
 * strings (the mark is hero_mark.xml from the AIO settings, the icons are 24-unit line icons),
 * so nothing depends on a resource, a theme or a font the image may not carry. Poppins
 * (aio-sans) falls back to the default face where it is missing.
 */
final class AioLook {
    static final int INK = Color.parseColor("#111113");
    static final int CARD = Color.parseColor("#1C1C1F");
    static final int CORAL = Color.parseColor("#F9674E");
    static final int VIOLET = Color.parseColor("#7A80F6");
    static final int MUTED = Color.parseColor("#B9B5AE");
    static final int ON_DARK_80 = Color.argb(204, 255, 255, 255);
    static final int ON_DARK_60 = Color.argb(153, 255, 255, 255);
    static final int HAIRLINE = Color.argb(26, 255, 255, 255);
    static final int GLASS = Color.argb(140, 17, 17, 19);
    static final int ALERT = Color.parseColor("#FF8A73");

    static final Typeface LIGHT = Typeface.create("aio-sans-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("aio-sans", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("aio-sans-medium", Typeface.NORMAL);

    /** The AIO mark: one stroke for the A, baseline and o, the i stem; then the ring over the i. */
    private static final String MARK = "M2.4,10.1L6.6,4.3L10.6,9.5Q11.1,10.1 12,10.1H20.8A1.1,1.1 0,0 0,21.9 9V5.6"
            + "A1.3,1.3 0,0 0,20.6 4.3H16.4A1.35,1.35 0,0 0,15.05 5.65V7.9M12.25,4.3V7.95";
    private static final String MARK_RING = "M12.25,1.18A0.72,0.72 0,1 1,12.25 2.62A0.72,0.72 0,1 1,12.25 1.18Z";

    // 24-unit line icons, stroked.
    static final String IC_RESTART = "M3,12a9,9 0,1 0,3 -6.7L3,8M3,3v5h5";
    static final String IC_WIFI = "M5,12.6a10,10 0,0 1,14 0M8.5,16.4a5,5 0,0 1,7 0M2,8.8a15,15 0,0 1,20 0M12,20h0.01";
    static final String IC_SUN = "M16,12A4,4 0,1 1,8 12A4,4 0,1 1,16 12ZM12,2v2M12,20v2M4.9,4.9l1.4,1.4M17.7,17.7l1.4,1.4"
            + "M2,12h2M20,12h2M4.9,19.1l1.4,-1.4M17.7,6.3l1.4,-1.4";
    static final String IC_SOUND = "M11,5L6,9H3v6h3l5,4ZM15.5,8.5a5,5 0,0 1,0 7M18.5,5.5a9,9 0,0 1,0 13";
    static final String IC_TABLE = "M3,9h18M5,9v11M19,9v11M8,5h8l3,4H5Z";
    static final String IC_CLEAN = "M12,3l1.8,4.2L18,9l-4.2,1.8L12,15l-1.8,-4.2L6,9l4.2,-1.8ZM19,15l0.8,1.7l1.7,0.8l-1.7,0.8"
            + "L19,20l-0.8,-1.7l-1.7,-0.8l1.7,-0.8Z";
    static final String IC_INFO = "M21,12A9,9 0,1 1,3 12A9,9 0,1 1,21 12ZM12,11v6M12,7.5h0.01";
    static final String IC_POWER = "M12,3v9M6.3,6.8a8,8 0,1 0,11.4 0";
    static final String IC_UNLOCK = "M6,11h12a1,1 0,0 1,1 1v8a1,1 0,0 1,-1 1H6a1,1 0,0 1,-1 -1v-8a1,1 0,0 1,1 -1Z"
            + "M8,11V7a4,4 0,0 1,7.8 -1.2";
    static final String IC_LOCK = "M6,11h12a1,1 0,0 1,1 1v8a1,1 0,0 1,-1 1H6a1,1 0,0 1,-1 -1v-8a1,1 0,0 1,1 -1Z"
            + "M8,11V7a4,4 0,0 1,8 0v4";
    static final String IC_ARROW = "M7,17L17,7M8,7h9v9";

    private AioLook() {}

    static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static Path path(String d) {
        return PathParser.createPathFromPathData(d);
    }

    /**
     * A headline in Poppins Light. Words between asterisks are lit in coral, as on
     * aioapp.com: "Your table is *ready.*"
     */
    static TextView headline(Context ctx, String text, float sp) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        boolean lit = false;
        for (String part : text.split("\\*", -1)) {
            int start = b.length();
            b.append(part);
            if (lit && b.length() > start) {
                b.setSpan(new ForegroundColorSpan(CORAL), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            lit = !lit;
        }
        TextView t = text(ctx, "", Color.WHITE, sp, LIGHT);
        t.setText(b);
        t.setLetterSpacing(-0.03f);
        t.setLineSpacing(0, 1.05f);
        return t;
    }

    static TextView text(Context ctx, String s, int color, float sp, Typeface face) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable rounded(int fill, float radiusPx, int strokePx, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    /** Rises into place out of a fade, like the headlines on aioapp.com. */
    static void rise(View v, long delayMs) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 18));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(700)
                .setInterpolator(new DecelerateInterpolator(2.2f)).start();
    }

    /** The glass nav pill: the AIO mark on the left, a coral-edged chip (venue, table) on the right. */
    static View navPill(Context ctx, String chip) {
        FrameLayout pill = new FrameLayout(ctx);
        pill.setBackground(rounded(GLASS, dp(ctx, 23), dp(ctx, 1), HAIRLINE));
        pill.setPadding(dp(ctx, 18), 0, dp(ctx, 8), 0);
        pill.addView(new Mark(ctx), new FrameLayout.LayoutParams(dp(ctx, 44), dp(ctx, 22),
                Gravity.START | Gravity.CENTER_VERTICAL));
        if (chip != null && !chip.isEmpty()) {
            TextView c = text(ctx, chip, Color.WHITE, 12, REGULAR);
            c.setSingleLine(true);
            c.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
            c.setBackground(rounded(Color.TRANSPARENT, dp(ctx, 99), dp(ctx, 1), Color.argb(153, 249, 103, 78)));
            pill.addView(c, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        }
        pill.setMinimumHeight(dp(ctx, 46));
        return pill;
    }

    /** "Venue · Table 12" for the nav chip, or what there is of it. */
    static String venueChip(Context ctx) {
        GuestInfo g = GuestInfo.load(ctx);
        if (g.hasRestaurant() && g.hasTable()) return g.restaurantName + " · Table " + g.tableLabel;
        if (g.hasRestaurant()) return g.restaurantName;
        return g.hasTable() ? "Table " + g.tableLabel : "";
    }

    /** The coral-to-violet pill with a white circle and an arrow, the one thing to tap. */
    static View gradientPill(Context ctx, String label, View.OnClickListener onClick) {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(ctx, 22), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {CORAL, VIOLET});
        bg.setCornerRadius(dp(ctx, 99));
        pill.setBackground(bg);
        pill.setForeground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), null,
                rounded(Color.WHITE, dp(ctx, 99), 0, 0)));
        TextView t = text(ctx, label, Color.WHITE, 16, REGULAR);
        pill.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout dot = new FrameLayout(ctx);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        dot.setBackground(circle);
        Icon arrow = new Icon(ctx, IC_ARROW, VIOLET);
        dot.addView(arrow, new FrameLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18), Gravity.CENTER));
        pill.addView(dot, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));
        pill.setClickable(true);
        pill.setOnClickListener(onClick);
        pill.setContentDescription(label);
        return pill;
    }

    /** The AIO mark, stroked in white, fitted to the view. */
    static final class Mark extends View {
        private final Path mark = path(MARK), ring = path(MARK_RING);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mark(Context ctx) {
            super(ctx);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.WHITE);
            setContentDescription("AIO");
        }

        @Override protected void onDraw(Canvas c) {
            // With its stroke the mark spans x 1.7..22.6 and y 1.0..10.8; drawn left-aligned.
            float s = Math.min(getWidth() / 20.9f, getHeight() / 9.8f);
            c.save();
            c.translate(0f, (getHeight() - 9.8f * s) / 2f);
            c.scale(s, s);
            c.translate(-1.7f, -1.0f);
            paint.setStrokeWidth(1.35f);
            c.drawPath(mark, paint);
            paint.setStrokeWidth(0.32f);
            c.drawPath(ring, paint);
            c.restore();
        }
    }

    /** A 24-unit line icon. */
    static final class Icon extends View {
        private final Path p;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Context ctx, String d, int color) {
            super(ctx);
            p = path(d);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2f);
            paint.setColor(color);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.scale(s, s);
            c.drawPath(p, paint);
            c.restore();
        }
    }

    /**
     * The background: near-black with warm, coral and violet light. With bokeh it is the
     * guest scene, soft lights drifting slowly like the video behind aioapp.com; without, the
     * calmer staff backdrop.
     */
    static final class Scene extends View {
        // x, y (fractions of the view), radius (fraction of the longer side), colour
        private static final float[][] GLOWS = {{0.74f, 0.70f, 0.45f}, {0.20f, 0.28f, 0.50f}, {0.85f, 0.12f, 0.55f}};
        private static final int[] GLOW_C = {Color.argb(82, 247, 103, 78), Color.argb(51, 255, 190, 120), Color.argb(56, 122, 128, 246)};
        private static final int[] STAFF_GLOW_C = {Color.argb(41, 249, 103, 78), Color.TRANSPARENT, Color.argb(56, 122, 128, 246)};
        private static final float[][] BOKEH = {{0.30f, 0.40f, 0.07f}, {0.62f, 0.30f, 0.05f}, {0.78f, 0.58f, 0.08f},
                {0.18f, 0.66f, 0.06f}, {0.50f, 0.18f, 0.09f}};
        private static final int[] BOKEH_C = {Color.argb(128, 255, 214, 150), Color.argb(90, 255, 240, 220),
                Color.argb(102, 249, 103, 78), Color.argb(90, 122, 128, 246), Color.argb(56, 255, 200, 140)};

        private final boolean bokeh;
        /** Off for a screen that may stay lit on battery for a long time (lost mode). */
        boolean moving = true;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private Shader base, shade;
        private Shader[] glows, lights;
        private ValueAnimator drift;
        private float t;

        Scene(Context ctx, boolean bokeh) {
            super(ctx);
            this.bokeh = bokeh;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float big = Math.max(w, h);
            base = bokeh
                    ? new LinearGradient(0, 0, 0, h, new int[] {Color.parseColor("#1A1411"), Color.parseColor("#0B0A0C"), INK},
                            new float[] {0f, 0.62f, 1f}, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, h, INK, INK, Shader.TileMode.CLAMP);
            shade = new LinearGradient(0, h * 0.3f, 0, h, Color.argb(26, 17, 17, 19), Color.argb(184, 17, 17, 19),
                    Shader.TileMode.CLAMP);
            int[] gc = bokeh ? GLOW_C : STAFF_GLOW_C;
            glows = new Shader[GLOWS.length];
            for (int i = 0; i < GLOWS.length; i++) {
                glows[i] = new RadialGradient(GLOWS[i][0] * w, GLOWS[i][1] * h, GLOWS[i][2] * big,
                        gc[i], Color.TRANSPARENT, Shader.TileMode.CLAMP);
            }
            lights = new Shader[BOKEH.length];
            for (int i = 0; i < BOKEH.length; i++) {
                lights[i] = new RadialGradient(0, 0, BOKEH[i][2] * big, BOKEH_C[i], Color.TRANSPARENT,
                        Shader.TileMode.CLAMP);
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!bokeh || !moving) return;
            drift = ValueAnimator.ofFloat(0f, 1f);
            drift.setDuration(14_000);
            drift.setRepeatMode(ValueAnimator.REVERSE);
            drift.setRepeatCount(ValueAnimator.INFINITE);
            drift.setInterpolator(new LinearInterpolator());
            drift.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            drift.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (drift != null) drift.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas c) {
            if (base == null) return;
            int w = getWidth(), h = getHeight();
            fill.setShader(base);
            c.drawRect(0, 0, w, h, fill);
            for (Shader g : glows) {
                fill.setShader(g);
                c.drawRect(0, 0, w, h, fill);
            }
            if (bokeh) {
                // Each light drifts on its own small ellipse; t eases back and forth.
                float e = (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
                for (int i = 0; i < BOKEH.length; i++) {
                    float dx = (float) Math.sin(e * Math.PI + i) * 0.035f * w;
                    float dy = (float) Math.cos(e * Math.PI + i * 1.7) * 0.025f * h;
                    paint.setShader(lights[i]);
                    c.save();
                    c.translate(BOKEH[i][0] * w + dx, BOKEH[i][1] * h + dy);
                    float r = BOKEH[i][2] * Math.max(w, h);
                    c.drawCircle(0, 0, r, paint);
                    c.restore();
                }
                fill.setShader(shade);
                c.drawRect(0, 0, w, h, fill);
            }
        }
    }

    /** A thin track with a coral-to-violet segment sliding through it: something is happening. */
    static final class Shimmer extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), seg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private ValueAnimator run;
        private float t;

        Shimmer(Context ctx) {
            super(ctx);
            track.setColor(Color.argb(36, 255, 255, 255));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            run = ValueAnimator.ofFloat(0f, 1f);
            run.setDuration(1400);
            run.setRepeatCount(ValueAnimator.INFINITE);
            run.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            run.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (run != null) run.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, track);
            float segW = w * 0.45f, x = -segW + t * (w + segW);
            seg.setShader(new LinearGradient(x, 0, x + segW, 0,
                    new int[] {Color.TRANSPARENT, CORAL, VIOLET, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
            c.save();
            c.clipRect(0, 0, w, h);
            r.set(x, 0, x + segW, h);
            c.drawRoundRect(r, h / 2, h / 2, seg);
            c.restore();
        }
    }
}
