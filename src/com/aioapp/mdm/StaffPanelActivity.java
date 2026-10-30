package com.aioapp.mdm;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * The staff controls (A · Control centre in the AIO signature look, picked 30 Oct 2026). A
 * right staff PIN on a T7 opens this instead of the whole tablet: every tool staff need is a
 * tile on one screen, and the tablet never leaves lock task, so staff never see stock Android.
 *
 * Tiles: restart the menu app, Wi-Fi, brightness and sound sliders, the table (set on the
 * dashboard), a 20 s cleaning pause, device details, restart the tablet (tap twice), and
 * Full tablet, which is the old PIN exit for technicians (lock task off, the staff pill up).
 *
 * It goes back to guests on "Back to guest mode", on Back, when the screen goes off, and
 * after {@link #IDLE_MS} without a touch, so a panel left open never faces a guest for long.
 * A tile with a problem (the menu app not running, no Wi-Fi) turns coral-red.
 *
 * Runs in the client's own task inside the lock (the client is on the lock-task allowlist),
 * like UnlockActivity, which starts it. Laid out in code like the other screens here.
 */
public class StaffPanelActivity extends Activity {
    private static final String TAG = "StaffPanel";
    /** Back to guests after this long without a touch. */
    static final long IDLE_MS = 2 * 60_000L;
    private static final int CLEAN_S = 20;
    private static final long CONFIRM_MS = 3_000L;

    /** True while the panel is up, so the corner + Power gesture doesn't stack the PIN pad on it. */
    static volatile boolean showing;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastTouch;
    private TextView idleLine;
    private Tile menuTile, wifiTile, restartTile;
    private FrameLayout root;
    private View cleanOverlay;
    private boolean restartArmed;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long left = IDLE_MS - (android.os.SystemClock.elapsedRealtime() - lastTouch);
            if (cleanOverlay == null && left <= 0) {
                Log.i(TAG, "idle — back to guest mode");
                finish();
                return;
            }
            if (cleanOverlay != null) lastTouch = android.os.SystemClock.elapsedRealtime();
            long s = Math.max(0, left) / 1000;
            idleLine.setText(String.format(java.util.Locale.ROOT, "Back to guests in %d:%02d if nobody touches the screen",
                    s / 60, s % 60));
            ui.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Log.i(TAG, "screen off — back to guest mode");
            finish();
        }
    };

    /** Opens the panel from the PIN pad, in the PIN pad's task (so inside the lock). */
    static void open(Activity from) {
        from.startActivity(new Intent(from, StaffPanelActivity.class));
        from.overridePendingTransition(0, 0);
    }

    /** True on products that get the panel; the others keep the plain PIN exit. */
    static boolean supported() {
        // 1.30.0: off on every product. Staff mode on a T7 is the unlocked tablet's own Home
        // (the Signature style), not a separate panel (user, 30 Oct 2026: "staff mode is just
        // coming back to this not a separate staff mode"), so the PIN leaves guest mode.
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (LostMode.isOn(this)) {
            finish();
            return;
        }
        showing = true;
        setContentView(build());
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        lastTouch = android.os.SystemClock.elapsedRealtime();
        ui.post(tick);
        Log.i(TAG, "staff panel open");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        showing = false;
        ui.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenOff); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastTouch = android.os.SystemClock.elapsedRealtime();
        if (cleanOverlay != null) return true; // cleaning: every touch is ignored
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        lastTouch = android.os.SystemClock.elapsedRealtime();
        if (cleanOverlay != null && e.getKeyCode() != KeyEvent.KEYCODE_POWER) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ── layout ──────────────────────────────────────────────────────────────

    private View build() {
        root = new FrameLayout(this);
        root.addView(new AioLook.Scene(this, false), match());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(16));

        col.addView(AioLook.navPill(this, AioLook.venueChip(this)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        TextView title = AioLook.headline(this, "Staff *controls.*", 32);
        col.addView(title, margins(dp(8), dp(22), 0));
        idleLine = AioLook.text(this, "", AioLook.ON_DARK_60, 12.5f, AioLook.REGULAR);
        col.addView(idleLine, margins(dp(8), dp(8), 0));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        menuTile = new Tile(AioLook.IC_RESTART, "Menu app");
        menuTile.setOnClickListener(v -> restartMenu());
        add(grid, menuTile, 2, dp(96));
        wifiTile = new Tile(AioLook.IC_WIFI, "Wi-Fi");
        wifiTile.setOnClickListener(v -> reconnectWifi());
        add(grid, wifiTile, 1, dp(96));
        add(grid, brightnessTile(), 3, dp(78));
        add(grid, soundTile(), 3, dp(78));
        add(grid, tableTile(), 1, dp(96));
        Tile clean = new Tile(AioLook.IC_CLEAN, "Clean");
        clean.sub.setText(CLEAN_S + " s pause");
        clean.setOnClickListener(v -> startCleaning());
        add(grid, clean, 1, dp(96));
        Tile device = new Tile(AioLook.IC_INFO, "Device");
        device.sub.setText("v" + clientVersion());
        device.setOnClickListener(v -> toast(deviceLine()));
        add(grid, device, 1, dp(96));
        restartTile = new Tile(AioLook.IC_POWER, "Restart");
        restartTile.sub.setText("The tablet");
        restartTile.setOnClickListener(v -> restartTablet());
        add(grid, restartTile, 1, dp(96));
        Tile full = new Tile(AioLook.IC_UNLOCK, "Full tablet");
        full.sub.setText("Leave guest mode for setup. The dashboard sees it.");
        full.setOnClickListener(v -> fullTablet());
        add(grid, full, 2, dp(96));
        col.addView(grid, margins(0, dp(18), 0));

        View pill = AioLook.gradientPill(this, "Back to guest mode", v -> finish());
        col.addView(pill, margins(0, dp(16), 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(col);
        root.addView(scroll, match());

        AioLook.rise(title, 60);
        AioLook.rise(grid, 180);
        AioLook.rise(pill, 300);
        return root;
    }

    private void add(GridLayout grid, View tile, int span, int height) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, span, 1f));
        lp.width = 0;
        lp.height = height;
        int m = dp(4);
        lp.setMargins(m, m, m, m);
        grid.addView(tile, lp);
    }

    private View tableTile() {
        Tile t = new Tile(AioLook.IC_TABLE, "Table");
        GuestInfo g = GuestInfo.load(this);
        t.title.setText(g.hasTable() ? "Table " + g.tableLabel : "Table");
        t.sub.setText(g.hasTable() ? "Change it on the dashboard" : "Not set · set it on the dashboard");
        t.setOnClickListener(v -> toast("The table is set on the AIO dashboard"));
        return t;
    }

    private View brightnessTile() {
        SliderTile s = new SliderTile(AioLook.IC_SUN, "Brightness");
        int min = ScreenBrightness.minLevel(), max = ScreenBrightness.maxLevel();
        int cur = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, max);
        s.slider.set(fraction(cur, min, max));
        if (ScreenBrightness.heldByDashboard(this)) {
            s.value.setText("Set on the dashboard");
            s.slider.setEnabled(false);
            s.slider.setAlpha(0.4f);
        } else {
            s.value.setText(percent(s.slider.value));
            s.slider.listener = f -> {
                int level = Math.round(min + f * (max - min));
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
                } catch (Exception e) {
                    Log.w(TAG, "brightness: " + e.getMessage());
                }
                s.value.setText(percent(f));
            };
        }
        return s;
    }

    private View soundTile() {
        SliderTile s = new SliderTile(AioLook.IC_SOUND, "Sound");
        AudioManager am = getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        s.slider.set(fraction(am.getStreamVolume(AudioManager.STREAM_MUSIC), 0, max));
        s.value.setText(percent(s.slider.value));
        s.slider.listener = f -> {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * max), 0);
            s.value.setText(percent(f));
        };
        return s;
    }

    // ── state ───────────────────────────────────────────────────────────────

    private void refresh() {
        String pkg = hostPackage();
        if (pkg.isEmpty()) {
            menuTile.setState("No menu app is set for this tablet", true);
        } else if (!running(pkg)) {
            menuTile.setState("Not running · tap to start it", true);
        } else {
            menuTile.setState("Running · " + appVersion(pkg) + " · tap to restart", false);
        }
        String ssid = ssid();
        wifiTile.setState(ssid.isEmpty() ? "Not connected · tap to retry" : ssid, ssid.isEmpty());
    }

    private String hostPackage() {
        JSONObject cfg = KioskManager.loadConfig(this);
        String pkg = cfg == null ? "" : cfg.optString("kiosk_package", "");
        return pkg.isEmpty() ? KioskExit.staffPackage(this) : pkg;
    }

    private boolean running(String pkg) {
        try {
            for (ActivityManager.RunningTaskInfo t : getSystemService(ActivityManager.class).getRunningTasks(20)) {
                if (t.baseActivity != null && pkg.equals(t.baseActivity.getPackageName())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String appVersion(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            CharSequence label = pi.applicationInfo.loadLabel(getPackageManager());
            return label + " " + pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private String ssid() {
        try {
            WifiInfo w = getSystemService(WifiManager.class).getConnectionInfo();
            if (w == null || w.getNetworkId() == -1) return "";
            String s = w.getSSID();
            if (s == null || s.equals(WifiManager.UNKNOWN_SSID)) return "";
            return s.replaceAll("^\"|\"$", "");
        } catch (Exception e) {
            return "";
        }
    }

    private String clientVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private String deviceLine() {
        return SystemPropertiesProxy.get("ro.serialno", "") + " · " + SystemPropertiesProxy.get("ro.build.id", "")
                + " · client " + clientVersion();
    }

    // ── actions ─────────────────────────────────────────────────────────────

    /**
     * Restart the menu app: stop it and start it straight back into lock task, so the guest
     * gets a fresh menu. The app's task is the lock's root, so stopping it clears the panel's
     * task too and the lock drops for a moment: the client's re-lock covers that with "Just a
     * moment." (RelockCoverActivity) and finds the app already back.
     */
    private void restartMenu() {
        String pkg = hostPackage();
        if (pkg.isEmpty()) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            toast("The menu app isn't installed");
            return;
        }
        menuTile.setState("Restarting…", false);
        Log.i(TAG, "restarting " + pkg);
        ui.postDelayed(() -> {
            try {
                getSystemService(ActivityManager.class).forceStopPackage(pkg);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLockTaskEnabled(true);
                startActivity(launch, o.toBundle());
            } catch (Exception e) {
                Log.e(TAG, "restart " + pkg + ": " + e.getMessage());
            }
            ui.postDelayed(this::finish, 400);
        }, 500);
    }

    private void reconnectWifi() {
        WifiManager wm = getSystemService(WifiManager.class);
        wifiTile.setState("Reconnecting…", false);
        try {
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
            wm.disconnect();
            wm.reconnect();
        } catch (Exception e) {
            Log.w(TAG, "wifi reconnect: " + e.getMessage());
        }
        ui.postDelayed(this::refresh, 6_000);
    }

    /** Tap once to arm, again within CONFIRM_MS to restart: a reboot is a long wait for the table. */
    private void restartTablet() {
        if (!restartArmed) {
            restartArmed = true;
            restartTile.setState("Tap again to restart", true);
            ui.postDelayed(() -> {
                restartArmed = false;
                restartTile.setState("The tablet", false);
            }, CONFIRM_MS);
            return;
        }
        Log.i(TAG, "staff restart");
        restartTile.setState("Restarting…", true);
        try {
            getSystemService(PowerManager.class).reboot("staff");
        } catch (Exception e) {
            Log.e(TAG, "reboot: " + e.getMessage());
        }
    }

    /** The technicians' way out: the old PIN exit (lock task off, the staff pill up). */
    private void fullTablet() {
        Log.i(TAG, "full tablet");
        UnlockActivity.leaveGuestMode(this);
        finish();
    }

    /**
     * The cleaning pause: the AIO signature cleaning screen over everything for CLEAN_S
     * seconds, every touch ignored, then back to the panel.
     */
    private void startCleaning() {
        FrameLayout o = new FrameLayout(this);
        o.addView(new AioLook.Scene(this, true), match());
        Sweep sweep = new Sweep(this);
        o.addView(sweep, match());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM | Gravity.START);
        col.setPadding(dp(22), dp(14), dp(22), dp(32));
        col.addView(AioLook.navPill(this, AioLook.venueChip(this)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        GradientNumber n = new GradientNumber(this);
        n.setText(String.valueOf(CLEAN_S));
        col.addView(n);
        TextView h = AioLook.headline(this, "Wiping *down.*", 38);
        col.addView(h, margins(0, dp(8), 0));
        TextView sub = AioLook.text(this, "Touches are paused while the table is cleaned.",
                AioLook.ON_DARK_80, 14, AioLook.REGULAR);
        col.addView(sub, margins(0, dp(10), 0));
        o.addView(col, match());
        AioLook.rise(h, 100);
        AioLook.rise(sub, 250);

        cleanOverlay = o;
        root.addView(o, match());
        final int[] left = {CLEAN_S};
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                left[0]--;
                if (left[0] > 0) {
                    n.setText(String.valueOf(left[0]));
                    ui.postDelayed(this, 1000);
                    return;
                }
                root.removeView(o);
                cleanOverlay = null;
                lastTouch = android.os.SystemClock.elapsedRealtime();
                toast("Touches are back on");
            }
        }, 1000);
    }

    private void toast(String s) {
        TextView t = AioLook.text(this, s, Color.WHITE, 13, AioLook.REGULAR);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setBackground(AioLook.rounded(Color.argb(235, 17, 17, 19), dp(99), dp(1), AioLook.HAIRLINE));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(90);
        lp.leftMargin = lp.rightMargin = dp(16);
        root.addView(t, lp);
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(200).start();
        ui.postDelayed(() -> t.animate().alpha(0f).setDuration(300).withEndAction(() -> root.removeView(t)).start(), 2600);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static float fraction(int v, int min, int max) {
        return max <= min ? 0f : Math.max(0f, Math.min(1f, (v - min) / (float) (max - min)));
    }

    private static String percent(float f) {
        return Math.round(f * 100) + "%";
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams margins(int left, int top, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = right;
        return lp;
    }

    private int dp(float v) {
        return AioLook.dp(this, v);
    }

    /** A tile: an icon, a title, a line under it. Coral-red when something needs a look. */
    private final class Tile extends LinearLayout {
        final AioLook.Icon icon;
        final TextView title, sub;

        Tile(String iconPath, String name) {
            super(StaffPanelActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(13), dp(14), dp(12));
            icon = new AioLook.Icon(getContext(), iconPath, AioLook.CORAL);
            addView(icon, new LayoutParams(dp(22), dp(22)));
            addView(new View(getContext()), new LayoutParams(1, 0, 1f));
            title = AioLook.text(getContext(), name, Color.WHITE, 13.5f, AioLook.MEDIUM);
            title.setSingleLine(true);
            addView(title);
            sub = AioLook.text(getContext(), "", AioLook.MUTED, 11, AioLook.REGULAR);
            sub.setMaxLines(2);
            sub.setLineSpacing(0, 1.15f);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            addView(sub, lp);
            setState("", false);
            setClickable(true);
            setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), null,
                    AioLook.rounded(Color.WHITE, dp(24), 0, 0)));
        }

        void setState(String line, boolean bad) {
            if (!line.isEmpty()) sub.setText(line);
            setBackground(bad
                    ? AioLook.rounded(Color.argb(51, 226, 85, 62), dp(24), dp(1.5f), Color.argb(191, 255, 138, 115))
                    : AioLook.rounded(AioLook.CARD, dp(24), dp(1), AioLook.HAIRLINE));
            icon.setColor(bad ? AioLook.ALERT : AioLook.CORAL);
            sub.setTextColor(bad ? AioLook.ALERT : AioLook.MUTED);
        }
    }

    /** A full-width tile with a label, its value and a gradient slider. */
    private final class SliderTile extends LinearLayout {
        final TextView value;
        final Slider slider;

        SliderTile(String iconPath, String name) {
            super(StaffPanelActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(11), dp(14), dp(8));
            setBackground(AioLook.rounded(AioLook.CARD, dp(24), dp(1), AioLook.HAIRLINE));
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(new AioLook.Icon(getContext(), iconPath, AioLook.CORAL), new LayoutParams(dp(20), dp(20)));
            TextView t = AioLook.text(getContext(), name, Color.WHITE, 13.5f, AioLook.MEDIUM);
            LayoutParams tl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            tl.leftMargin = dp(10);
            row.addView(t, tl);
            value = AioLook.text(getContext(), "", AioLook.MUTED, 12, AioLook.REGULAR);
            row.addView(value);
            addView(row);
            slider = new Slider(getContext());
            addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(32)));
        }
    }

    interface OnSlide { void changed(float f); }

    /** A thin track filled coral to violet, with a white thumb that follows the finger. */
    private final class Slider extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        float value;
        OnSlide listener;

        Slider(Context ctx) {
            super(ctx);
            track.setColor(Color.argb(36, 255, 255, 255));
            thumb.setColor(Color.WHITE);
            thumb.setShadowLayer(dp(3), 0, dp(1), Color.argb(90, 0, 0, 0));
        }

        void set(float f) {
            value = f;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            fill.setShader(new LinearGradient(0, 0, w, 0, AioLook.CORAL, AioLook.VIOLET, Shader.TileMode.CLAMP));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            float pad = dp(11);
            float f = Math.max(0f, Math.min(1f, (e.getX() - pad) / (getWidth() - 2 * pad)));
            set(f);
            if (listener != null) listener.changed(f);
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float pad = dp(11), cy = getHeight() / 2f, th = dp(6);
            float x = pad + value * (getWidth() - 2 * pad);
            r.set(pad, cy - th / 2, getWidth() - pad, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, track);
            r.set(pad, cy - th / 2, x, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, fill);
            c.drawCircle(x, cy, dp(10), thumb);
        }
    }

    /** The cleaning countdown: a big light number filled coral to violet. */
    private static final class GradientNumber extends TextView {
        GradientNumber(Context ctx) {
            super(ctx);
            setTypeface(Typeface.create(AioLook.LIGHT, 200, false));
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 120);
            setIncludeFontPadding(false);
            setLetterSpacing(-0.06f);
            setFontFeatureSettings("tnum");
            setTextColor(Color.WHITE);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            getPaint().setShader(new LinearGradient(0, 0, w, 0, AioLook.CORAL, AioLook.VIOLET, Shader.TileMode.CLAMP));
        }
    }

    /** A coral-to-violet glow sweeping down the screen, again and again. */
    private static final class Sweep extends View {
        private final Paint p = new Paint();
        private android.animation.ValueAnimator run;
        private float t;

        Sweep(Context ctx) {
            super(ctx);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            run = android.animation.ValueAnimator.ofFloat(0f, 1f);
            run.setDuration(3000);
            run.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            run.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
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
            float band = getHeight() * 0.22f;
            float y = -band + t * (getHeight() + band);
            p.setShader(new LinearGradient(0, y, 0, y + band,
                    new int[] {Color.TRANSPARENT, Color.argb(110, 249, 103, 78), Color.argb(110, 122, 128, 246), Color.TRANSPARENT},
                    new float[] {0f, 0.45f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, y, getWidth(), y + band, p);
        }
    }
}
