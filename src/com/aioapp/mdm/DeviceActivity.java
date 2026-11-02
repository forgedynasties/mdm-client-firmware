package com.aioapp.mdm;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * AIO Device (1.25.0): the app with the AIO mark in an unlocked T7's dock, from the Linen home
 * demo. One light screen of what the tablet is and how it is doing: the venue and table, the
 * AIO connection, Wi-Fi, the menu app, the battery, the firmware, this client and the serial.
 *
 * Dark on the dark Home styles (1.32.0), as the Settings demo's AIO Device page; light on Linen.
 *
 * Read-only on purpose: everything here is changed on the AIO dashboard, and the staff controls
 * stay inside the lock. Redrawn on every resume. Laid out in code like the other screens here.
 */
public class DeviceActivity extends Activity {
    private static final int LINEN = Color.parseColor("#F6F4EF");
    private static final int EDGE = Color.parseColor("#E6E3DC");
    private static final int GREY = Color.parseColor("#6B6B70");
    private static final int OK = Color.parseColor("#2E9E6A");

    private ScrollView root;
    private boolean dark;
    private int ink, grey, edge, rule, surface, page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = !"linen".equals(AioHomeWidget.style(this));
        ink = dark ? Color.WHITE : AioLook.INK;
        grey = dark ? AioLook.ON_DARK_60 : GREY;
        edge = dark ? AioLook.HAIRLINE : EDGE;
        rule = dark ? Color.argb(15, 255, 255, 255) : Color.parseColor("#ECE9E3");
        surface = dark ? AioLook.CARD : Color.WHITE;
        page = dark ? AioLook.INK : LINEN;
        if (dark) {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(page));
            getWindow().setStatusBarColor(page);
            getWindow().setNavigationBarColor(page);
        }
        root = new ScrollView(this);
        root.setBackgroundColor(page);
        root.setFitsSystemWindows(true);
        setContentView(root);
        if (dark) {
            // Only after setContentView: before it the window has no decor and this throws.
            getWindow().getInsetsController().setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        root.removeAllViews();
        root.addView(build());
    }

    private View build() {
        AioHomeWidget.State st = new AioHomeWidget.State(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(20), dp(18), dp(24));

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.aio_mark);
        mark.setImageTintList(ColorStateList.valueOf(ink));
        col.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(21)));

        TextView title = AioLook.headline(this, st.deployed ? "This tablet, at *" + st.g.restaurantName + ".*"
                : "This tablet is *not on a table yet.*", 30);
        title.setTextColor(ink);
        col.addView(title, margins(dp(18), 0));
        TextView sub = AioLook.text(this, st.deployed ? "Managed from the AIO dashboard."
                : "Assign it to a table on the AIO dashboard.", grey, 13.5f, AioLook.REGULAR);
        col.addView(sub, margins(dp(8), 0));

        LinearLayout card = card();
        row(card, "Venue", st.deployed ? st.where() : "Not on a table yet", st.deployed ? 0 : AioLook.CORAL);
        row(card, "AIO", AioHomeWidget.isOnline() ? "Connected" : "Offline", AioHomeWidget.isOnline() ? OK : AioLook.CORAL);
        row(card, "Wi-Fi", st.ssid.isEmpty() ? "Not connected" : st.ssid, st.ssid.isEmpty() ? AioLook.CORAL : 0);
        row(card, "Menu app", st.menuApp == null ? "Not installed yet" : st.menuApp, st.menuApp == null ? AioLook.CORAL : 0);
        row(card, "Battery", st.battery < 0 ? "Unknown" : st.battery + "%" + (st.plugged ? " · charging" : ""), 0);
        col.addView(card, margins(dp(22), 0));

        LinearLayout about = card();
        row(about, "Firmware", st.build.isEmpty() ? "Unknown" : st.build, 0);
        row(about, "AIO client", clientVersion(), 0);
        row(about, "Serial", st.serial.isEmpty() ? "Unknown" : st.serial, 0);
        col.addView(about, margins(dp(12), 0));

        TextView foot = AioLook.text(this, "Settings for this tablet (venue, table, Wi-Fi, updates) are on the AIO dashboard.",
                grey, 12, AioLook.REGULAR);
        foot.setLineSpacing(0, 1.2f);
        col.addView(foot, margins(dp(16), dp(4)));
        return col;
    }

    private String clientVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "Unknown";
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(AioLook.rounded(surface, dp(22), dp(1), edge));
        c.setPadding(dp(16), dp(4), dp(16), dp(4));
        return c;
    }

    /** One label and value line; a non-zero colour lights the value. */
    private void row(LinearLayout card, String label, String value, int color) {
        if (card.getChildCount() > 0) {
            View rule = new View(this);
            rule.setBackgroundColor(this.rule);
            card.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout r = new LinearLayout(this);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(13), 0, dp(13));
        r.addView(AioLook.text(this, label, ink, 14, AioLook.REGULAR),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = AioLook.text(this, value, color != 0 ? color : grey, 13, AioLook.REGULAR);
        v.setGravity(android.view.Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        card.addView(r);
    }

    private LinearLayout.LayoutParams margins(int top, int start) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.setMarginStart(start);
        return lp;
    }

    private int dp(float v) {
        return AioLook.dp(this, v);
    }
}
