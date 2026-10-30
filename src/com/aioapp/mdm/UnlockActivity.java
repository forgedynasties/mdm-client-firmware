package com.aioapp.mdm;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.content.res.Configuration;
import android.graphics.drawable.StateListDrawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The staff PIN pad that takes a tablet out of lock mode, with no server connection needed.
 * U1 · Split (picked 28 Oct 2026, the same design as the Standard client's): on a landscape
 * screen the venue, "Staff unlock", what happens and the device on the left, the keypad on the
 * right; on a portrait screen the two stack. Dots fill as digits go in, round keys sized to the
 * screen, a coral unlock key next to 0, a white ring on the key a D-pad is on. On a T7 a right
 * PIN opens the staff controls ({@link StaffPanelActivity}) inside the lock (1.23.0); on the
 * other products it shows "Staff mode on" for a moment, then the tablet leaves lock task and
 * {@link StaffPill} stays up until someone goes back to guest mode.
 *
 * Opened by the firmware's corner + Power gesture (MdmService.onKioskExitRequested). Never
 * opens in lost mode — only the dashboard lifts that (see LostMode).
 *
 * Entry is through a keypad this activity draws, not the system IME: lock task can be run
 * with the keyboard suppressed, a menu board is driven by a remote with no IME at all, and
 * the keys are focusable so a D-pad reaches them. Nothing here depends on a theme, a layout
 * resource, or a font the image may not carry (Poppins falls back to the default face).
 */
public class UnlockActivity extends Activity {
    private static final String TAG = "UnlockActivity";

    private static final int C_BG_TOP = Color.parseColor("#3A2B5E");
    private static final int C_BG = Color.parseColor("#1B1530");
    private static final int C_TEXT = Color.WHITE;
    private static final int C_KICKER = Color.parseColor("#FFB199");
    private static final int C_KEY = Color.argb(20, 255, 255, 255);
    private static final int C_KEY_LINE = Color.argb(36, 255, 255, 255);
    private static final int C_CORAL = Color.parseColor("#F9674E");
    private static final int C_CORAL_OFF = Color.argb(72, 249, 103, 78);
    private static final int C_ERR = Color.parseColor("#FFC1B4");
    private static final int C_OK = Color.parseColor("#5BE38F");
    private static final Typeface FONT = Typeface.create("aio-sans", Typeface.NORMAL);
    private static final Typeface FONT_MEDIUM = Typeface.create("aio-sans-medium", Typeface.NORMAL);

    /** Longest accepted entry: a server-pushed PIN may be up to 8. */
    private static final int MAX_LEN = 8;
    /** Below this there is nothing worth checking, so Unlock stays disabled. */
    private static final int MIN_LEN = 4;
    /** "Staff mode on" stays this long before the tablet leaves lock task. */
    private static final long SUCCESS_MS = 900;

    private final StringBuilder entry = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Dots dots;
    private TextView status;
    private View unlockBtn;
    private Runnable lockoutTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(""); // suppress the app label in the window title
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (LostMode.isOn(this) || !KioskExit.isEnabled(this)) {
            Log.w(TAG, "offline exit not enabled/provisioned; dismissing");
            finish();
            return;
        }
        setContentView(buildView());
        hideSystemBars();
        refreshLockout();
    }

    /**
     * Full screen over the system bars (lock task keeps the nav bar's Back): Cancel and the
     * serial sat under it. Whatever bar still shows pads the content instead (see root()).
     */
    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected void onDestroy() {
        if (lockoutTick != null) ui.removeCallbacks(lockoutTick);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View root(LinearLayout column) {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setColors(new int[] {Color.parseColor("#2C2350"), Color.parseColor("#161227"), Color.parseColor("#0F0C1C")});
        bg.setGradientCenter(0f, 0f);
        bg.setGradientRadius(Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 1.15f);
        root.setBackground(bg);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final int top = column.getPaddingTop(), bottom = column.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            column.setPadding(column.getPaddingLeft(), top + bars.top,
                    column.getPaddingRight(), bottom + bars.bottom);
            return insets;
        });
        return root;
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(28), dp(24), dp(16));
        col.addView(venueRow());
        return col;
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private View buildView() {
        boolean land = landscape();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout info = infoPanel(land), keys = keypadPanel(land);
        if (land) {
            row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            View rule = new View(this);
            rule.setBackgroundColor(Color.argb(18, 255, 255, 255));
            row.addView(rule, new LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT));
            row.addView(keys, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        } else {
            row.addView(info, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(keys, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        updateEntry();
        return root(row);
    }

    /** The venue, what this screen is, what happens, and (landscape) which device. */
    private LinearLayout infoPanel(boolean land) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(land ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(land ? 48 : 28), dp(land ? 40 : 30), dp(land ? 40 : 28), dp(land ? 32 : 4));
        LinearLayout venue = (LinearLayout) venueRow();
        if (!land) venue.setGravity(Gravity.CENTER); // centred over the stacked text
        col.addView(venue);
        if (land) col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        TextView kicker = label("STAFF UNLOCK", C_KICKER, 12, FONT_MEDIUM);
        kicker.setLetterSpacing(0.14f);
        col.addView(kicker, startMargin(land, dp(land ? 0 : 26)));
        TextView title = label(land ? "Enter the\nvenue PIN" : "Enter the venue PIN", C_TEXT, land ? 34 : 27, FONT_MEDIUM);
        if (land) title.setGravity(Gravity.START);
        title.setLineSpacing(0, 1.05f);
        col.addView(title, startMargin(land, dp(6)));
        boolean panel = StaffPanelActivity.supported();
        TextView sub = label(panel
                ? (land ? "Opens the staff controls. Guests get the tablet back by itself. Your manager has the PIN."
                        : "Opens the staff controls.")
                : (land ? "The device leaves guest mode until you switch it back. Your manager has the PIN."
                        : "Leaves guest mode until you switch it back."), C_TEXT, 15, FONT);
        sub.setAlpha(0.62f);
        sub.setMaxWidth(dp(340));
        sub.setLineSpacing(0, 1.2f);
        if (land) sub.setGravity(Gravity.START);
        col.addView(sub, startMargin(land, dp(10)));
        String serial = SystemPropertiesProxy.get("ro.serialno", "");
        if (land && !serial.isEmpty()) {
            TextView s = label(serial, C_TEXT, 11, FONT);
            s.setAlpha(0.4f);
            s.setLetterSpacing(0.04f);
            col.addView(s, startMargin(true, dp(20)));
        }
        return col;
    }

    private LinearLayout keypadPanel(boolean land) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        dots = new Dots(this);
        col.addView(dots, sized(LinearLayout.LayoutParams.MATCH_PARENT, dp(18), 0));
        status = label("", C_ERR, 13, FONT);
        status.setMinHeight(dp(18));
        col.addView(status, topMargin(dp(10)));
        col.addView(buildKeypad(land), topMargin(dp(8)));

        TextView cancel = label("Cancel", C_TEXT, 16, FONT_MEDIUM);
        cancel.setAlpha(0.75f);
        cancel.setPadding(dp(20), dp(8), dp(20), dp(8));
        cancel.setBackground(focusRing(Color.TRANSPARENT, true));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> finish());
        col.addView(cancel, topMargin(dp(12)));
        String serial = SystemPropertiesProxy.get("ro.serialno", "");
        if (!land && !serial.isEmpty()) {
            TextView s = label(serial, C_TEXT, 10, FONT);
            s.setAlpha(0.45f);
            s.setLetterSpacing(0.04f);
            col.addView(s, topMargin(dp(4)));
        }
        return col;
    }

    private LinearLayout.LayoutParams startMargin(boolean land, int top) {
        LinearLayout.LayoutParams lp = topMargin(top);
        lp.gravity = land ? Gravity.START : Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    /** A key's background: its fill, and a white ring while a D-pad is on it. */
    private StateListDrawable focusRing(int fill, boolean pill) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable ring = new GradientDrawable();
        GradientDrawable plain = new GradientDrawable();
        for (GradientDrawable g : new GradientDrawable[] {ring, plain}) {
            if (pill) g.setCornerRadius(dp(20)); else g.setShape(GradientDrawable.OVAL);
            g.setColor(fill);
        }
        ring.setStroke(dp(3), Color.WHITE);
        GradientDrawable down = new GradientDrawable();
        if (pill) down.setCornerRadius(dp(20)); else down.setShape(GradientDrawable.OVAL);
        down.setColor(Color.argb(48, 255, 255, 255));
        d.addState(new int[] {android.R.attr.state_pressed}, down);
        d.addState(new int[] {android.R.attr.state_focused}, ring);
        d.addState(new int[] {}, plain);
        return d;
    }

    /** The venue's logo (or the AIO tile) and its name: whose PIN this wants. */
    private View venueRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GuestInfo g = GuestInfo.load(this);
        Bitmap logo = g.hasRestaurant() ? VenueIcon.icon(this) : null;

        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        if (logo != null) {
            bg.setColor(Color.WHITE);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logo);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(4), dp(4), dp(4), dp(4));
            tile.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            bg.setColors(new int[] {C_CORAL, Color.parseColor("#7A80F6")});
            TextView mark = label("AIO", C_TEXT, 10, FONT_MEDIUM);
            tile.addView(mark, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        tile.setBackground(bg);
        tile.setClipToOutline(true);
        row.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView name = label(g.hasRestaurant() ? g.restaurantName : "AIO", C_TEXT, 13, FONT_MEDIUM);
        name.setAlpha(0.9f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nLp.leftMargin = dp(10);
        row.addView(name, nLp);
        return row;
    }

    /** 3x4 grid of round keys: 1-9, then backspace / 0 / unlock. */
    private View buildKeypad(boolean land) {
        // Four rows, the dots, the message and Cancel must fit the panel's height.
        int hDp = getResources().getConfiguration().screenHeightDp;
        int fit = (land ? hDp - 230 : (hDp - 330) * 2 / 3) / 4 - 14;
        int size = dp(Math.max(56, Math.min(fit, land ? 80 : 96)));
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"del", "0", "ok"}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final String k = rows[r][c];
                View key;
                if ("del".equals(k)) {
                    TextView t = label("Del", C_TEXT, 17, FONT);
                    t.setAlpha(0.8f);
                    t.setBackground(focusRing(Color.TRANSPARENT, false));
                    t.setContentDescription("Delete");
                    key = t;
                    key.setOnClickListener(v -> {
                        if (entry.length() > 0) entry.setLength(entry.length() - 1);
                        updateEntry();
                    });
                    key.setOnLongClickListener(v -> {
                        entry.setLength(0);
                        updateEntry();
                        return true;
                    });
                } else if ("ok".equals(k)) {
                    key = new UnlockKey(this);
                    key.setContentDescription("Unlock");
                    key.setOnClickListener(v -> attempt());
                    unlockBtn = key;
                } else {
                    TextView t = label(k, C_TEXT, 30, FONT);
                    t.setBackground(focusRing(C_KEY, false));
                    key = t;
                    key.setOnClickListener(v -> {
                        if (entry.length() < MAX_LEN) entry.append(k);
                        status.setText("");
                        updateEntry();
                    });
                }
                key.setClickable(true);
                key.setFocusable(true); // so a remote / D-pad can drive the keypad
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                if (c > 0) lp.leftMargin = dp(26);
                line.addView(key, lp);
            }
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r > 0) lineLp.topMargin = dp(14);
            lineLp.gravity = Gravity.CENTER_HORIZONTAL;
            pad.addView(line, lineLp);
        }
        return pad;
    }

    private GradientDrawable round(int fill, int line) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (line != 0) d.setStroke(dp(1), line);
        return d;
    }

    private TextView label(String text, int color, float sp, Typeface face) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams topMargin(int top) {
        return sized(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, top);
    }

    private LinearLayout.LayoutParams sized(int w, int h, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = top;
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    /** Redraws the dots and enables Unlock once the entry is long enough. */
    private void updateEntry() {
        dots.setCount(entry.length());
        boolean ready = entry.length() >= MIN_LEN && !KioskExit.isLockedOut(this);
        unlockBtn.setEnabled(ready);
        unlockBtn.invalidate();
    }

    /** While locked out, count the remaining seconds down in place rather than once. */
    private void refreshLockout() {
        if (lockoutTick != null) ui.removeCallbacks(lockoutTick);
        lockoutTick = new Runnable() {
            @Override public void run() {
                if (KioskExit.isLockedOut(UnlockActivity.this)) {
                    long s = KioskExit.lockoutRemainingMs(UnlockActivity.this) / 1000 + 1;
                    status.setText(String.format(java.util.Locale.ROOT,
                            "Too many tries. Try again in %d:%02d", s / 60, s % 60));
                    updateEntry();
                    ui.postDelayed(this, 1000);
                } else if (status.getText().toString().startsWith("Too many")) {
                    status.setText("");
                    updateEntry();
                }
            }
        };
        ui.post(lockoutTick);
    }

    private void attempt() {
        if (KioskExit.isLockedOut(this)) {
            refreshLockout();
            return;
        }
        String code = entry.toString();
        entry.setLength(0);
        if (!KioskExit.verify(this, code)) {
            updateEntry();
            dots.shake();
            if (KioskExit.isLockedOut(this)) {
                refreshLockout();
            } else {
                status.setText("Wrong PIN. Try again.");
            }
            return;
        }
        if (StaffPanelActivity.supported()) {
            // A T7 stays in lock task: the staff controls open over the menu app.
            StaffPanelActivity.open(this);
            finish();
            return;
        }
        showStaffModeOn();
        ui.postDelayed(this::exitLockMode, SUCCESS_MS);
    }

    /** A green tick and "Staff mode on" while the tablet gets ready to leave lock task. */
    private void showStaffModeOn() {
        LinearLayout col = column();
        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView tick = label("✓", C_OK, 46, FONT_MEDIUM);
        tick.setBackground(round(Color.argb(46, 91, 227, 143), 0));
        col.addView(tick, sized(dp(96), dp(96), 0));
        col.addView(label("Staff mode on", C_TEXT, 27, FONT_MEDIUM), topMargin(dp(20)));
        TextView sub = label("Guest mode is off until you switch it back.", C_TEXT, 15, FONT);
        sub.setAlpha(0.8f);
        col.addView(sub, topMargin(dp(8)));
        View bottom = new View(this);
        col.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1.3f));
        setContentView(root(col));
    }

    private void exitLockMode() {
        leaveGuestMode(this);
        finish();
    }

    /**
     * Out of lock task for staff: the tablet's whole UI, with the staff pill up until someone
     * goes back to guest mode. The PIN exit on products without the staff panel, and the
     * panel's Full tablet tile on a T7.
     */
    static void leaveGuestMode(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(ctx, MdmAdminReceiver.class);
            KioskManager.suspendLocally(ctx, dpm, admin);
        } catch (Exception e) {
            Log.e(TAG, "suspendLocally error: " + e.getMessage());
        }
        // Tell the service to report the exit now (and put the staff pill up). Without this
        // the suspension only rode the next scheduled check-in, so the dashboard showed the
        // device as locked long after the technician had unlocked it in front of the customer.
        try {
            Intent report = new Intent(ctx, MdmService.class);
            report.setAction(MdmService.ACTION_KIOSK_EXITED);
            ctx.startService(report);
        } catch (Exception e) {
            Log.e(TAG, "exit report error: " + e.getMessage());
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float dpf(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** One outlined dot per digit (at least 4), filled for each digit entered. */
    private final class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;

        Dots(Context ctx) { super(ctx); }

        void setCount(int n) {
            count = n;
            invalidate();
        }

        void shake() {
            animate().cancel();
            setTranslationX(0);
            float d = dpf(10);
            animate().translationX(d).setDuration(50).withEndAction(() ->
                    animate().translationX(-d).setDuration(80).withEndAction(() ->
                            animate().translationX(d / 2).setDuration(70).withEndAction(() ->
                                    animate().translationX(0).setDuration(60).start()).start()).start()).start();
        }

        @Override protected void onDraw(Canvas c) {
            int n = Math.max(MIN_LEN, count);
            float r = dpf(7.5f), gap = dpf(16);
            float total = n * 2 * r + (n - 1) * gap;
            float x = (getWidth() - total) / 2f + r, y = getHeight() / 2f;
            for (int i = 0; i < n; i++) {
                if (i < count) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(C_TEXT);
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dpf(1.5f));
                    paint.setColor(Color.argb(140, 255, 255, 255));
                }
                c.drawCircle(x, y, i < count ? r : r - dpf(0.75f), paint);
                x += 2 * r + gap;
            }
        }
    }

    /** The coral round key with an open padlock; pale and inert until 4 digits are in. */
    private final class UnlockKey extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        UnlockKey(Context ctx) { super(ctx); }

        @Override protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            invalidate(); // draw or drop the D-pad ring
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            boolean on = isEnabled();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? C_CORAL : C_CORAL_OFF);
            c.drawCircle(cx, cy, Math.min(w, h) / 2f, paint);

            int ink = on ? Color.WHITE : Color.argb(115, 255, 255, 255);
            float bw = dpf(18), bh = dpf(13), bt = cy - dpf(1);
            // Shackle, swung open to the left.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(2.2f));
            paint.setColor(ink);
            float sw = dpf(11);
            float sl = cx - bw / 2f - dpf(3);
            c.drawArc(new RectF(sl, bt - sw, sl + sw, bt), 180f, 180f, false, paint);
            c.drawLine(sl + sw, bt - sw / 2f, sl + sw, bt, paint);
            // Body with a keyhole.
            paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(cx - bw / 2f, bt, cx + bw / 2f, bt + bh), dpf(3), dpf(3), paint);
            paint.setColor(on ? C_CORAL : C_BG);
            c.drawCircle(cx, bt + bh * 0.45f, dpf(2), paint);
            if (isFocused()) { // the D-pad is on it
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(3));
                paint.setColor(Color.WHITE);
                c.drawCircle(cx, cy, Math.min(w, h) / 2f - dpf(1.5f), paint);
            }
        }
    }
}
