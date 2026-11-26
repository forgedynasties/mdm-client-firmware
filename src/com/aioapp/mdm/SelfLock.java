package com.aioapp.mdm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The device locks itself when it leaves its venue (1.13.0, opt-in per venue from the
 * server's {@code self_lock} config). A T7 off its venue's Wi-Fi cannot be reached, so a
 * dashboard "Mark as lost" never arrives; this puts the lost screen up without the server.
 *
 * <p>"Left" means, for {@code minutes} in a row, on every Wi-Fi scan:
 * <ul>
 * <li>none of the venue's networks is in the scan results — not merely disconnected;</li>
 * <li>the surroundings changed too: fewer than two of the neighbouring access points it last
 *     saw while at the venue are still visible. A venue router going down makes its own network
 *     vanish but leaves the neighbours (the shop next door, the flats upstairs) where they were;
 *     a tablet carried away loses both. A venue with no neighbours to remember falls back to
 *     the first condition alone;</li>
 * <li>not plugged in (on its charger or dock), and no phone on its guest pad.</li>
 * </ul>
 * Then lost mode goes on with {@code self: true}. It lifts itself when a venue network shows up
 * in a scan again — it came back. A lost mode the dashboard set never lifts itself.
 *
 * <p>The venue's networks are learned, not configured: whatever the device is connected to
 * while the server says it is at a venue (guest.restaurant_name), plus the networks pushed with
 * wifi_config. Moving the device to another venue starts the list again.
 */
final class SelfLock {
    private static final String TAG = "SelfLock";
    private static final String PREFS = "mdm_self_lock";
    private static final String K_ENABLED = "enabled";
    private static final String K_MINUTES = "minutes";
    private static final String K_MESSAGE = "message";
    private static final String K_PHONE = "phone";
    private static final String K_VENUE = "venue";
    private static final String K_HOME = "home_ssids";        // JSON array of SSIDs
    private static final String K_NEIGHBOURS = "neighbours";  // JSON array of BSSIDs
    private static final String K_AWAY_SINCE = "away_since";  // wall ms, 0 = not away

    private static final int MAX_HOME = 8;
    private static final int MAX_NEIGHBOURS = 6;
    private static final int NEIGHBOURS_STILL_HERE = 2;

    private SelfLock() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Keeps the server's self_lock config. Absent or enabled=false is off (protocol). */
    static void saveConfig(Context ctx, JSONObject config) {
        JSONObject sl = config.optJSONObject("self_lock");
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (sl == null || !sl.optBoolean("enabled", false)) {
            e.putBoolean(K_ENABLED, false).putLong(K_AWAY_SINCE, 0).apply();
            return;
        }
        int minutes = Math.max(2, Math.min(240, sl.optInt("minutes", 10)));
        e.putBoolean(K_ENABLED, true).putInt(K_MINUTES, minutes)
                .putString(K_MESSAGE, sl.optString("message", ""))
                .putString(K_PHONE, sl.optString("phone", ""))
                .apply();
    }

    /** A venue network pushed with wifi_config add. */
    static void rememberNetwork(Context ctx, String ssid) {
        if (ssid == null || ssid.isEmpty()) return;
        Set<String> home = load(ctx, K_HOME);
        if (home.add(ssid)) save(ctx, K_HOME, home, MAX_HOME);
    }

    /**
     * One Wi-Fi scan's worth of evidence. {@code scan} is the scan as the check-in carries it
     * ([{bssid, ssid, rssi}], strongest first or not), null when the scan failed. Returns true
     * when lost mode went on or off, so the caller sends a frame at once.
     */
    static boolean onScan(Context ctx, DevicePolicyManager dpm, ComponentName admin, JSONArray scan,
                          String connectedSsid, boolean phoneOnPad, String serial) {
        if (ServerSwitches.lostOff(ctx)) return false; // lost device switched off on the server
        SharedPreferences p = prefs(ctx);
        String venue = GuestInfo.load(ctx).restaurantName;
        if (!venue.equals(p.getString(K_VENUE, ""))) {
            // A different venue (or none): what we knew about the old one is no use here.
            p.edit().putString(K_VENUE, venue).remove(K_HOME).remove(K_NEIGHBOURS)
                    .putLong(K_AWAY_SINCE, 0).apply();
        }
        if (scan == null) return false;

        Set<String> home = load(ctx, K_HOME);
        String conn = unquote(connectedSsid);
        if (!venue.isEmpty() && !conn.isEmpty() && home.add(conn)) save(ctx, K_HOME, home, MAX_HOME);

        boolean homeVisible = false;
        List<JSONObject> others = new ArrayList<>();
        for (int i = 0; i < scan.length(); i++) {
            JSONObject ap = scan.optJSONObject(i);
            if (ap == null) continue;
            if (home.contains(ap.optString("ssid", ""))) homeVisible = true;
            else others.add(ap);
        }

        // At the venue: refresh the neighbours it would still see during a router outage.
        if (homeVisible && !others.isEmpty()) {
            Collections.sort(others, (a, b) -> Integer.compare(b.optInt("rssi", -127), a.optInt("rssi", -127)));
            Set<String> n = new HashSet<>();
            for (JSONObject ap : others) {
                String b = ap.optString("bssid", "");
                if (!b.isEmpty()) n.add(b.toLowerCase());
                if (n.size() >= MAX_NEIGHBOURS) break;
            }
            save(ctx, K_NEIGHBOURS, n, MAX_NEIGHBOURS);
        }

        if (LostMode.isSelf(ctx) && homeVisible) {
            Log.i(TAG, "back at the venue — lifting the lost screen it put up itself");
            p.edit().putLong(K_AWAY_SINCE, 0).apply();
            LostMode.lift(ctx, dpm, admin);
            return true;
        }
        if (!p.getBoolean(K_ENABLED, false) || LostMode.isOn(ctx) || venue.isEmpty() || home.isEmpty()) {
            if (p.getLong(K_AWAY_SINCE, 0) != 0) p.edit().putLong(K_AWAY_SINCE, 0).apply();
            return false;
        }

        boolean away = !homeVisible && !neighboursStillHere(ctx, scan) && !plugged(ctx) && !phoneOnPad;
        long now = System.currentTimeMillis();
        long since = p.getLong(K_AWAY_SINCE, 0);
        if (!away) {
            if (since != 0) p.edit().putLong(K_AWAY_SINCE, 0).apply();
            return false;
        }
        if (since == 0 || since > now) {
            p.edit().putLong(K_AWAY_SINCE, now).apply();
            Log.i(TAG, "venue networks gone and surroundings changed, off charge — watching");
            return false;
        }
        int minutes = p.getInt(K_MINUTES, 10);
        if (now - since < minutes * 60_000L) return false;

        String msg = p.getString(K_MESSAGE, "");
        if (msg.isEmpty()) msg = "This device belongs to " + venue + ". Please hand it to a member of staff.";
        Log.w(TAG, "away from " + venue + " for " + minutes + " min — locking itself");
        LostMode.turnOn(ctx, dpm, admin, msg, p.getString(K_PHONE, ""), serial, "", true);
        p.edit().putLong(K_AWAY_SINCE, 0).apply();
        return true;
    }

    /** At least NEIGHBOURS_STILL_HERE of the remembered neighbouring APs are in this scan. */
    private static boolean neighboursStillHere(Context ctx, JSONArray scan) {
        Set<String> n = load(ctx, K_NEIGHBOURS);
        if (n.isEmpty()) return false;
        int seen = 0;
        for (int i = 0; i < scan.length(); i++) {
            JSONObject ap = scan.optJSONObject(i);
            if (ap != null && n.contains(ap.optString("bssid", "").toLowerCase())) seen++;
        }
        return seen >= Math.min(NEIGHBOURS_STILL_HERE, n.size());
    }

    /** On its charger or dock (any power source), from the sticky battery broadcast. */
    private static boolean plugged(Context ctx) {
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (Exception e) {
            return true; // can't tell: never lock on a guess
        }
    }

    private static Set<String> load(Context ctx, String key) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (JSONException ignored) { }
        out.remove("");
        return out;
    }

    private static void save(Context ctx, String key, Set<String> s, int max) {
        JSONArray a = new JSONArray();
        for (String v : s) {
            if (a.length() >= max) break;
            a.put(v);
        }
        prefs(ctx).edit().putString(key, a.toString()).apply();
    }

    private static String unquote(String s) {
        if (s == null || "<unknown ssid>".equals(s)) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
