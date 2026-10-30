package com.aioapp.mdm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * The AIO Home widget (1.24.0; laid out on the 4x5 grid since 1.26.0): the block by the Google search bar on an unlocked T7's
 * home screen, in the Home style the launcher is set to ({@code Settings.Global}
 * {@value #STYLE_KEY}, written by the launcher's Home settings or the MDM shell):
 * <ul>
 * <li>{@code linen} (C, the default): a big light clock, the date and where the tablet is, and
 *     a white card that opens the menu app.</li>
 * <li>{@code signature} (A): the glass nav pill with the AIO mark, a greeting with the venue in
 *     coral, and the gradient "Open the menu" pill.</li>
 * <li>{@code board} (B): the menu app, Wi-Fi, battery, AIO connection, firmware and table as
 *     tiles; one with a problem turns coral-red.</li>
 * <li>{@code setup} (D): until the tablet is on a venue, five steps to ready, each ticking
 *     itself; then the venue, a big card that opens the menu, and the checks as chips.</li>
 * </ul>
 * Redrawn on every change that shows in it: the style, the guest info, the network and the
 * AIO connection, installed apps, the kiosk config and (board only) the battery. Laid out in
 * resources because RemoteViews needs them; the menu app opens through the welcome card's
 * broadcast ({@link GuestWelcomeWidget#ACTION_OPEN_GUEST_APP}).
 */
public class AioHomeWidget extends AppWidgetProvider {
    private static final String TAG = "AioHomeWidget";
    static final String STYLE_KEY = "aio_home_style";
    private static final int CORAL = Color.parseColor("#F9674E");
    private static final int OK_GREEN = Color.parseColor("#6EE0A6");
    private static final int ALERT = Color.parseColor("#FF8A73");

    /** The WebSocket to the AIO server is up (set by MdmService). */
    private static volatile boolean online;
    /** Last battery reading drawn (board), so a broadcast that changes nothing redraws nothing. */
    private static volatile int lastBattery = -1;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        update(ctx, awm, ids);
    }

    /** The AIO connection went up or down. */
    static void setOnline(Context ctx, boolean up) {
        if (online == up) return;
        online = up;
        updateAll(ctx);
    }

    static boolean isOnline() {
        return online;
    }

    /** A battery broadcast: only the board shows the battery, and only a new reading redraws. */
    static void onBattery(Context ctx, Intent b) {
        if (b == null || !"board".equals(style(ctx))) return;
        int key = percent(b) * 2 + (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ? 1 : 0);
        if (key == lastBattery) return;
        lastBattery = key;
        updateAll(ctx);
    }

    /** Redraws every AIO Home widget on the device. Safe from any thread. */
    static void updateAll(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            if (awm == null) return;
            int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, AioHomeWidget.class));
            if (ids != null && ids.length > 0) update(ctx, awm, ids);
            AioHeadingWidget.updateAll(ctx);
            AioTitleWidget.updateAll(ctx);
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed: " + e.getMessage());
        }
    }

    static String style(Context ctx) {
        String s = Settings.Global.getString(ctx.getContentResolver(), STYLE_KEY);
        if ("signature".equals(s) || "board".equals(s) || "setup".equals(s)) return s;
        return "linen";
    }

    private static void update(Context ctx, AppWidgetManager awm, int[] ids) {
        State st = new State(ctx);
        RemoteViews v;
        switch (style(ctx)) {
            case "signature": v = signature(ctx, st); break;
            case "board": v = board(ctx, st); break;
            case "setup": v = st.deployed ? setupReady(ctx, st) : setup(ctx, st); break;
            default: v = linen(ctx, st); break;
        }
        awm.updateAppWidget(ids, v);
    }

    // ── the four styles ─────────────────────────────────────────────────────

    private static RemoteViews linen(Context ctx, State st) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.home_widget_linen);
        SpannableStringBuilder line = new SpannableStringBuilder(
                new java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault())
                        .format(new java.util.Date()) + " · ");
        if (st.deployed) {
            line.append(st.g.restaurantName);
            if (st.g.hasTable()) lit(line, ", Table " + st.g.tableLabel);
        } else {
            lit(line, "not on a table yet");
        }
        v.setTextViewText(R.id.home_line, line);
        v.setTextViewText(R.id.home_card_title, st.deployed ? st.g.restaurantName + " menu" : "Menu app");
        v.setTextViewText(R.id.home_card_sub, st.menuLine());
        v.setOnClickPendingIntent(R.id.home_card, openMenu(ctx));
        return v;
    }

    private static RemoteViews signature(Context ctx, State st) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.home_widget_signature);
        v.setTextViewText(R.id.home_chip, st.deployed ? st.where() : "Not on a table yet");
        SpannableStringBuilder h = new SpannableStringBuilder(greeting() + ".\n");
        lit(h, st.deployed ? st.g.restaurantName + "." : "Welcome.");
        v.setTextViewText(R.id.home_headline, h);
        v.setTextViewText(R.id.home_sub, st.deployed ? "The menu app is ready for the next guests."
                : "Assign this tablet to a table on the AIO dashboard.");
        v.setOnClickPendingIntent(R.id.home_pill, openMenu(ctx));
        return v;
    }

    private static RemoteViews board(Context ctx, State st) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.home_widget_board);
        tile(v, 1, "Menu app", st.menuApp == null ? "Not installed" : st.menuApp + " · open it", false);
        v.setOnClickPendingIntent(R.id.home_t1, openMenu(ctx));
        tile(v, 2, "Wi-Fi", st.ssid.isEmpty() ? "Not connected" : st.ssid, st.ssid.isEmpty());
        v.setOnClickPendingIntent(R.id.home_t2, activity(ctx, 2, new Intent(Settings.ACTION_WIFI_SETTINGS)));
        boolean low = st.battery >= 0 && st.battery <= 20 && !st.plugged;
        tile(v, 3, "Battery " + Math.max(0, st.battery) + "%",
                st.plugged ? "On its stand" : low ? "Not on its stand" : "On battery", low);
        v.setOnClickPendingIntent(R.id.home_t3, activity(ctx, 3, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        tile(v, 4, "AIO", online ? "Connected" : "Not connected", !online);
        tile(v, 5, "Firmware", st.build, false);
        tile(v, 6, st.deployed ? st.where() : "No table",
                st.deployed ? (st.kiosk ? "Guest mode is on" : "Guest mode turns on from the dashboard")
                        : "Assign it on the AIO dashboard", false);
        return v;
    }

    private static RemoteViews setup(Context ctx, State st) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.home_widget_setup);
        boolean[] done = {!st.ssid.isEmpty(), online, st.menuApp != null, st.deployed && st.g.hasTable(), st.kiosk};
        String[][] text = {
                {"Wi-Fi", st.ssid.isEmpty() ? "Connect it in Settings" : st.ssid},
                {"Connected to AIO", online ? st.serial : "Needs the internet"},
                {"Menu app", st.menuApp == null ? "Installs from the AIO dashboard" : st.menuApp + " installed"},
                {"Venue and table", st.deployed ? st.where() : "Assign it on the AIO dashboard"},
                {"Guest mode", "Locks it to the menu for guests"}};
        int count = 0, next = -1;
        for (int i = 0; i < 5; i++) {
            if (done[i]) count++;
            else if (next < 0) next = i;
        }
        SpannableStringBuilder h = new SpannableStringBuilder("Let's get this T7 ");
        lit(h, "onto a table.");
        v.setTextViewText(R.id.home_headline, h);
        v.setProgressBar(R.id.home_progress, 5, count, false);
        v.setTextViewText(R.id.home_ring_text, count + "/5");
        int[] rows = {R.id.home_s1, R.id.home_s2, R.id.home_s3, R.id.home_s4, R.id.home_s5};
        int[] dots = {R.id.home_s1_dot, R.id.home_s2_dot, R.id.home_s3_dot, R.id.home_s4_dot, R.id.home_s5_dot};
        int[] titles = {R.id.home_s1_title, R.id.home_s2_title, R.id.home_s3_title, R.id.home_s4_title, R.id.home_s5_title};
        int[] subs = {R.id.home_s1_sub, R.id.home_s2_sub, R.id.home_s3_sub, R.id.home_s4_sub, R.id.home_s5_sub};
        for (int i = 0; i < 5; i++) {
            v.setTextViewText(titles[i], text[i][0]);
            v.setTextViewText(subs[i], text[i][1]);
            v.setTextViewText(dots[i], done[i] ? "✓" : String.valueOf(i + 1));
            v.setTextColor(dots[i], done[i] ? OK_GREEN : Color.WHITE);
            v.setInt(dots[i], "setBackgroundResource",
                    done[i] ? R.drawable.home_dot_ok : i == next ? R.drawable.home_dot_now : R.drawable.home_dot_todo);
            v.setInt(rows[i], "setBackgroundResource", i == next ? R.drawable.home_step_now : R.drawable.home_step);
        }
        v.setOnClickPendingIntent(R.id.home_s1, activity(ctx, 11, new Intent(Settings.ACTION_WIFI_SETTINGS)));
        return v;
    }

    private static RemoteViews setupReady(Context ctx, State st) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.home_widget_setup_ready);
        Bitmap logo = VenueIcon.icon(ctx);
        if (logo != null) v.setImageViewBitmap(R.id.home_logo, logo);
        else v.setImageViewResource(R.id.home_logo, R.drawable.ic_home_menu);
        if (logo == null) v.setInt(R.id.home_logo, "setColorFilter", CORAL);
        v.setTextViewText(R.id.home_venue, st.g.restaurantName);
        v.setTextViewText(R.id.home_sub, st.g.hasTable() ? "Table " + st.g.tableLabel : "No table set yet");
        v.setTextViewText(R.id.home_big_sub, st.menuLine() + (st.kiosk ? "" : " · guest mode turns on from the dashboard"));
        v.setOnClickPendingIntent(R.id.home_big, openMenu(ctx));
        chip(v, R.id.home_c1, "Wi-Fi", !st.ssid.isEmpty());
        chip(v, R.id.home_c2, "AIO", online);
        chip(v, R.id.home_c3, "Menu app", st.menuApp != null);
        chip(v, R.id.home_c4, st.g.hasTable() ? "Table " + st.g.tableLabel : "Table", st.g.hasTable());
        return v;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** What the widgets (and AIO Device) show, read once per redraw. */
    static final class State {
        final GuestInfo g;
        final boolean deployed, kiosk, plugged;
        final String ssid, menuApp, build, serial;
        final int battery;

        State(Context ctx) {
            g = GuestInfo.load(ctx);
            deployed = g.hasRestaurant();
            JSONObject cfg = KioskManager.loadConfig(ctx);
            kiosk = cfg != null && cfg.optBoolean("kiosk_enabled", false);
            ssid = ssid(ctx);
            menuApp = menuApp(ctx, g);
            build = SystemPropertiesProxy.get("ro.build.id", "");
            serial = deviceSerial();
            Intent b = ctx.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            battery = b == null ? -1 : percent(b);
            plugged = b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }

        String where() {
            return g.hasTable() ? g.restaurantName + " · Table " + g.tableLabel : g.restaurantName;
        }

        String menuLine() {
            return menuApp == null ? "Not installed yet" : menuApp + " · ready";
        }
    }

    private static void tile(RemoteViews v, int i, String title, String sub, boolean bad) {
        int[][] ids = {{}, {R.id.home_t1, R.id.home_t1_icon, R.id.home_t1_title, R.id.home_t1_sub},
                {R.id.home_t2, R.id.home_t2_icon, R.id.home_t2_title, R.id.home_t2_sub},
                {R.id.home_t3, R.id.home_t3_icon, R.id.home_t3_title, R.id.home_t3_sub},
                {R.id.home_t4, R.id.home_t4_icon, R.id.home_t4_title, R.id.home_t4_sub},
                {R.id.home_t5, R.id.home_t5_icon, R.id.home_t5_title, R.id.home_t5_sub},
                {R.id.home_t6, R.id.home_t6_icon, R.id.home_t6_title, R.id.home_t6_sub}};
        v.setTextViewText(ids[i][2], title);
        v.setTextViewText(ids[i][3], sub);
        if (i == 1) return; // the menu app tile keeps its gradient
        v.setInt(ids[i][0], "setBackgroundResource", bad ? R.drawable.home_tile_bad : R.drawable.home_tile);
        v.setInt(ids[i][1], "setColorFilter", bad ? ALERT : CORAL);
        v.setTextColor(ids[i][3], bad ? ALERT : Color.parseColor("#B9B5AE"));
    }

    private static void chip(RemoteViews v, int id, String label, boolean ok) {
        SpannableStringBuilder s = new SpannableStringBuilder("● ");
        s.setSpan(new ForegroundColorSpan(ok ? OK_GREEN : ALERT), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append(label);
        v.setTextViewText(id, s);
        v.setViewVisibility(id, View.VISIBLE);
    }

    /** Board's title (AioTitleWidget): "Not on a *table yet.*" or "Ready for *Table 12.*" */
    static CharSequence boardHeadline(GuestInfo g) {
        SpannableStringBuilder h = new SpannableStringBuilder();
        if (g.hasRestaurant() && g.hasTable()) {
            h.append("Ready for ");
            lit(h, "Table " + g.tableLabel + ".");
        } else {
            h.append("Not on a ");
            lit(h, "table yet.");
        }
        return h;
    }

    /** Appends text in coral. */
    private static void lit(SpannableStringBuilder b, String s) {
        int start = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(CORAL), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String greeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    /** The serial: Build.getSerial() (the client may read it), not ro.serialno (it may not). */
    private static String deviceSerial() {
        try {
            String s = android.os.Build.getSerial();
            if (s != null && !s.equals(android.os.Build.UNKNOWN)) return s;
        } catch (SecurityException ignored) {
        }
        return SystemPropertiesProxy.get("ro.serialno", "");
    }

    private static int percent(Intent b) {
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }

    private static String ssid(Context ctx) {
        try {
            WifiInfo w = ctx.getSystemService(WifiManager.class).getConnectionInfo();
            if (w == null || w.getNetworkId() == -1) return "";
            String s = w.getSSID();
            if (s == null || s.equals(WifiManager.UNKNOWN_SSID)) return "";
            return s.replaceAll("^\"|\"$", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** "Nugget 17.0.0" for the installed menu app, or null when there is none. */
    private static String menuApp(Context ctx, GuestInfo g) {
        Intent i = g.guestAppIntent(ctx);
        if (i == null || i.getPackage() == null && i.getComponent() == null) return null;
        String pkg = i.getPackage() != null ? i.getPackage() : i.getComponent().getPackageName();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.applicationInfo.loadLabel(ctx.getPackageManager()) + " " + pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static PendingIntent openMenu(Context ctx) {
        Intent open = new Intent(GuestWelcomeWidget.ACTION_OPEN_GUEST_APP).setClass(ctx, GuestWelcomeWidget.class);
        return PendingIntent.getBroadcast(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent activity(Context ctx, int code, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
