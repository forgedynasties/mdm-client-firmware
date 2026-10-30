package com.aioapp.mdm;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The lost-mode screen (see {@link LostMode}): whose device this is and what to do with it.
 * Nothing on it can be tapped — touches are swallowed and Back does nothing; lock task keeps
 * home, recents, notifications and the status bar away. The five-Power-press exit prompt does
 * not open while lost; only the dashboard lifts it.
 *
 * In the AIO signature look since 1.23.0 (it was L1, picked 19 Oct 2026): the same facts, the
 * venue (see {@link VenueIcon}), the request, the phone when there is one, the serial and how
 * staff get it back, set like the rest of the guest screens (see {@link AioLook}).
 *
 * Laid out in code like UnlockActivity, with no theme, layout or font the image may lack.
 * The block is centred and width-capped, so it reads the same on the T7 in landscape and on
 * a portrait kiosk panel. The screen is allowed to sleep: a lost T7 is on battery, and
 * keeping the panel lit would run it flat long before anyone finds it.
 */
public class LostActivity extends Activity {

    private static final String DEFAULT_MESSAGE = "Please hand it to a member of staff.";

    private final BroadcastReceiver lifted = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (LostMode.ACTION_CHANGED.equals(i.getAction())) setContentView(buildView());
            else finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        IntentFilter f = new IntentFilter(LostMode.ACTION_LIFTED);
        f.addAction(LostMode.ACTION_CHANGED);
        registerReceiver(lifted, f, Context.RECEIVER_NOT_EXPORTED);
        setContentView(buildView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!LostMode.isOn(this)) finish(); // lifted while we were in the background
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(lifted); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* nothing to go back to */ }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) { return true; }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // D-pad and remote keys go nowhere; Power is handled by the system, not here.
        return true;
    }

    /**
     * The AIO signature lost screen: the dim scene (still, to save the battery), the nav pill
     * with the AIO mark and where it lives, low on the left "This tablet lives at <venue>."
     * with the venue in coral, the request, and a glass card with the phone (or how staff get
     * it back) and the serial. Width-capped, so a landscape panel reads the same.
     */
    private View buildView() {
        boolean t7 = MdmProduct.KEY_T7.equals(MdmProduct.detect().key());
        String venue = VenueIcon.venue(this);
        String phone = LostMode.phone(this);
        String serial = LostMode.serial(this);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();

        FrameLayout root = new FrameLayout(this);
        AioLook.Scene scene = new AioLook.Scene(this, true);
        scene.moving = false;
        root.addView(scene, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        String chip = AioLook.venueChip(this);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP);
        nlp.setMargins(dp(14), dp(14), dp(14), 0);
        root.addView(AioLook.navPill(this, chip.isEmpty() ? venue : chip), nlp);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        int width = Math.min(dp(520), dm.widthPixels - dp(44));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        blp.setMargins(dp(22), 0, dp(22), dp(28));
        root.addView(block, blp);

        String thing = t7 ? "This tablet" : "This device";
        block.addView(AioLook.headline(this, venue.isEmpty() ? thing + " is *lost.*"
                : thing + " lives at *" + venue + ".*", 36));
        TextView ask = AioLook.text(this, request(LostMode.message(this), venue), AioLook.ON_DARK_80, 15,
                AioLook.REGULAR);
        ask.setLineSpacing(0, 1.25f);
        block.addView(ask, top(dp(12)));

        // The card: who to call (or how staff get it back), and the serial.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(14), dp(16), dp(14));
        card.setBackground(AioLook.rounded(AioLook.GLASS, dp(22), dp(1), AioLook.HAIRLINE));
        FrameLayout avatar = new FrameLayout(this);
        GradientDrawable av = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {AioLook.CORAL, AioLook.VIOLET});
        av.setShape(GradientDrawable.OVAL);
        avatar.setBackground(av);
        avatar.addView(new AioLook.Icon(this, AioLook.IC_LOCK, Color.WHITE),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        card.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView main = AioLook.text(this, phone.isEmpty() ? "Hand it to any member of staff" : phone,
                Color.WHITE, phone.isEmpty() ? 15 : 22, AioLook.MEDIUM);
        lines.addView(main);
        String small = (phone.isEmpty() ? "It unlocks from the dashboard once it is back"
                : "or call AIO") + (serial.isEmpty() ? "" : " · " + serial);
        lines.addView(AioLook.text(this, small, AioLook.ON_DARK_60, 12, AioLook.REGULAR), top(dp(4)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = dp(12);
        card.addView(lines, llp);
        block.addView(card, top(dp(18)));
        return root;
    }

    private static LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        return lp;
    }

    /**
     * The request, without what the screen already says: "It belongs to <venue>." when the
     * venue is drawn above it (servers keep that sentence for clients before 1.13.4), and a
     * trailing "or call:" — the number has its own box, and with no number it led nowhere.
     */
    static String request(String msg, String venue) {
        String m = msg == null ? "" : msg.trim();
        if (!venue.isEmpty()) {
            for (String lead : new String[]{"It belongs to " + venue + ".", "This device belongs to " + venue + "."}) {
                if (m.startsWith(lead)) m = m.substring(lead.length()).trim();
            }
        }
        if (m.endsWith("or call:")) {
            m = m.substring(0, m.length() - "or call:".length()).trim();
            if (m.endsWith(",")) m = m.substring(0, m.length() - 1).trim();
            if (!m.isEmpty() && !m.endsWith(".")) m += ".";
        }
        return m.isEmpty() ? DEFAULT_MESSAGE : m;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
