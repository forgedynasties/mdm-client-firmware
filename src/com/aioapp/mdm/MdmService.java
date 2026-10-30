package com.aioapp.mdm;

import android.app.*;
import android.content.*;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MdmService extends Service {
    private static final String TAG = "MdmService";
    private static final String LOCATION_TAG = "MdmLocation";
    private static final String CHANNEL_ID = "MDM_SERVICE";
    // Separate, gently-alerting channel for update / app-install completions, so the
    // persistent status channel can stay silent.
    private static final String UPDATES_CHANNEL_ID = "AIO_MDM_UPDATES";
    private static final int NOTIFICATION_ID = 1001;

    // Staff exit: the firmware (PhoneWindowManager's AioStaffExit) writes the time to this
    // Settings.Secure key when a finger rests in the top-left corner and Power is pressed while
    // the tablet is locked; that Power press never reaches the screen. The answer here is only
    // ever the PIN pad, so a request from anything else that can write secure settings still
    // needs the PIN.
    public static final String KIOSK_EXIT_REQUEST_SETTING = "aioapp_kiosk_exit_request";
    private ContentObserver kioskExitRequestObserver;
    private StaffPill staffPill;
    /** The low-battery pill over a locked T7 (products with a battery only). */
    private BatteryPill batteryPill;
    private BroadcastReceiver screenOnReceiver;
    // Re-lock the moment lock task drops (the host app crashed or was killed), instead of
    // leaving the guest on the launcher until the next poll. RELOCK_MAX re-locks inside
    // RELOCK_WINDOW_MS means the app is crash-looping: stop chasing it and let the poll retry.
    private static final long RELOCK_DELAY_MS = 1_500L;
    private static final int RELOCK_MAX = 3;
    private static final long RELOCK_WINDOW_MS = 60_000L;
    private final long[] relockTimes = new long[RELOCK_MAX];
    private int relockIdx = 0;
    private final Handler relockHandler = new Handler(Looper.getMainLooper());
    private final Runnable relockRunnable = this::relockAfterDrop;
    private final Runnable lockDroppedRunnable = this::onLockTaskDropped;
    private TaskStackListener lockTaskListener;
    // Separate notification for app download/install progress, so it doesn't disturb the
    // persistent foreground-service notification (1001).
    private static final int INSTALL_NOTIFICATION_ID = 1002;

    // Play Protect's install-time verification asks the user to confirm a scan before it
    // returns a verdict. On a kiosk nobody ever taps that window, so PackageManagerService
    // waits for a verdict that never comes and the PackageInstaller session hangs — every
    // self-update stalled at "installing" until the client's 180s timeout. The client cannot
    // exempt its own session (PackageInstallerService honours INSTALL_DISABLE_VERIFICATION
    // only for adb), so the verifier is switched off around our own install and put back
    // immediately after. The previous value is written down first, so an install that kills
    // this process — or a reboot mid-install — cannot leave it off: the next startup
    // restores it. A framework fix (skip verification for installs initiated by this uid)
    // is the intended end state; this toggle goes away when that ships in the image.
    private static final String VERIFIER_SETTING = "package_verifier_enable";
    // Wireless adb port, applied by the firmware's aio-adb-tcp.rc (see "adb_tcp"), and the
    // wall-clock time it switches itself off again (0 = stays on until told otherwise).
    private static final String ADB_TCP_PROP = "persist.sys.aio.adb_tcp_port";
    private static final String ADB_TCP_UNTIL_PROP = "persist.sys.aio.adb_tcp_until";
    // Published only when getDeviceSerial() has a VALID_SERIAL; firmware's aio-serial.rc copies
    // it to sys.serialnumber, which apps (Nugget) can read but ro.serialno (serialno_prop) not.
    private static final String AIO_SERIAL_PROP = "persist.sys.aio.serial";
    private static final String PREFS_VERIFIER = "mdm_verifier";
    private static final String KEY_VERIFIER_SAVED = "saved_value";

    // Also declared on WatchdogReceiver in the manifest, so a poll alarm that fires after the
    // process died (e.g. a self-update whose restart failed) starts the service again.
    private static final String POLL_ACTION = "com.aioapp.mdm.POLL";
    // Repeating backstop for the same thing, armed at startup and never cancelled: alarms
    // survive a self-update's stop, so this keeps retrying even if the poll alarm's one try
    // fails too. Inexact, so it batches with other wakeups.
    private static final String WATCHDOG_ACTION = "com.aioapp.mdm.WATCHDOG";
    private static final long WATCHDOG_INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
    // Fired as the status notification's deleteIntent — i.e. when the user swipes it away.
    private static final String NOTIF_DISMISSED_ACTION = "com.aioapp.mdm.NOTIF_DISMISSED";

    private static volatile boolean running;

    /** True while this process has a live MdmService; read by WatchdogReceiver. */
    static boolean isRunning() {
        return running;
    }

    private AlarmManager alarmManager;
    private PendingIntent pollIntent;
    private BroadcastReceiver pollReceiver;
    private MdmApiService apiService;
    // This device's own server key (device-key plan, phase 2); registered after a check-in.
    private DeviceKey deviceKey;
    private volatile long nextKeyAttemptAt = 0;
    // Readings kept while the MDM can't be reached (1.7.0), sent when it can again.
    private OfflineQueue offlineQueue;
    private volatile boolean serverReachable = true;
    private volatile long lastQueuedAt = 0;
    private long lastQueuedCrashMs = 0; // newest crash already put in a kept reading
    private final AtomicBoolean flushingOffline = new AtomicBoolean(false);
    private static final long OFFLINE_SAMPLE_MS = 5 * 60_000L;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private volatile boolean networkAvailable = false;
    private volatile boolean polling = false;
    private volatile boolean remoteConfigLoaded = false;
    private OtaUpdateManager otaUpdateManager;
    private volatile String otaCommandId;  // set while an OTA is in progress
    private volatile boolean isCapturing = false;
    // Live logcat streams keyed by request_id; the Process is killed to stop a stream.
    private final java.util.Map<String, java.lang.Process> logcatStreams = new java.util.concurrent.ConcurrentHashMap<>();
    // Command ids the server told us to cancel (operator deleted the action). An
    // in-flight APK download polls this and aborts, so we stop pulling bytes for a
    // command that no longer exists. Populated by the WS "cancel_command" frame.
    private final java.util.Set<String> cancelledCommands = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ── Command idempotency + reliable terminal acks ─────────────────────────────
    // Ids currently executing, so a re-delivered command is not run a second time.
    private final java.util.Set<String> inFlightCommands = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Recently-completed command ids (bounded, persisted) so a re-delivery AFTER completion
    // is not re-run. Guarded by its own monitor.
    private final java.util.LinkedHashSet<String> recentDoneCommands = new java.util.LinkedHashSet<>();
    // Terminal acks not yet CONFIRMED by the server, persisted so a 'completed'/'failed'
    // survives a half-open socket or a process restart. cmdId → ack JSON. Guarded by itself.
    private final java.util.LinkedHashMap<String, JSONObject> pendingAcks = new java.util.LinkedHashMap<>();
    // Terminal OTA status reports ("installed"/"error") not yet confirmed by the server —
    // same durability problem as pendingAcks, but postOtaStatus() was fire-and-forget with
    // no retry at all until this was added. cmdId → status JSON. Guarded by itself.
    private final java.util.LinkedHashMap<String, JSONObject> pendingOtaAcks = new java.util.LinkedHashMap<>();
    private static final String PREFS_CMD = "mdm_cmd";
    private static final String KEY_DONE_CMDS = "recent_done_commands";
    private static final String KEY_PENDING_ACKS = "pending_acks";
    private static final String KEY_PENDING_OTA_ACKS = "pending_ota_acks";
    private static final int MAX_DONE_CMDS = 200;
    private volatile boolean cmdStateLoaded = false;

    private static final int MAX_LOGCAT_STREAMS = 3;
    /** Interactive shell sessions by session id (see runShellSession). */
    private final java.util.Map<String, java.lang.Process> shellSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> shellLastInput = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_SHELL_SESSIONS = 3;
    /** A session nobody has typed into for this long is closed. */
    private static final long SHELL_IDLE_MS = 30L * 60_000L;
    private android.os.PowerManager.WakeLock remoteWakeLock;  // keeps the screen on during a remote session
    private static final long REMOTE_WAKE_TIMEOUT_MS = 30 * 60 * 1000L;  // safety cap so a dropped session can't pin the screen
    private volatile MdmWebSocketClient wsClient;      // published from startWebSocket, read on many threads
    private volatile JSONArray cachedInstalledApps = null;  // invalidated from a package-change receiver thread
    private BroadcastReceiver packageChangeReceiver;
    // Status line (notifications N2, 23 Oct 2026): "Connected to AIO" / "Offline · trying
    // again", or what a firmware update is doing while one runs. See statusLine().
    private volatile String otaTitle = null;
    private volatile String otaText = null;
    private volatile long otaErrorAt = 0;
    private String lastStatusKey = "";
    // Shown as the notification's subtext. Resolved once: the PackageManager lookup is a
    // binder call and the version cannot change while this process lives.
    private String notificationVersion = "";
    private BroadcastReceiver notifDismissReceiver;
    // Control-plane pool: short tasks (check-ins, telemetry, acks, config, input, pings).
    private ExecutorService executor;
    // Heavy pool: long-running ops (commands, logcat, OTA download, screen capture) so they
    // never occupy the control-plane pool and starve/drop a telemetry or ack task.
    private ExecutorService heavyExecutor;
    private String deviceSerial;  // cached; Build.getSerial() is a binder call and never changes
    private String serialRaw;             // what Build.getSerial() actually returned
    private boolean serialCorrupt = false; // true when deviceSerial is msm-<ANDROID_ID> instead
    // A device serial is letters and digits (AT070AABU00429). Anything else — '=', '.',
    // spaces — is a fragment of the boot command line, not a serial.
    private static final java.util.regex.Pattern VALID_SERIAL =
            java.util.regex.Pattern.compile("^[A-Za-z0-9]{4,32}$");
    // Cached reflected SurfaceControl.screenshot — looked up once, not per capture frame.
    private volatile java.lang.reflect.Method screenshotMethod;
    private volatile boolean screenshotMethodResolved;
    private ScheduledExecutorService wifiScanExecutor;
    // Per-app usage report every AppUsage.INTERVAL_MS (1.17.0).
    private ScheduledExecutorService appUsageExecutor;
    // Lost-device reporting over Bluetooth LE (BleLost): the beacon while lost, and listening
    // for lost devices nearby while the server asks for it. Its own thread, because switching
    // Bluetooth on and scanning block for seconds.
    private ScheduledExecutorService bleExecutor;
    private int bleTicks = 0;

    // Battery: registered once in onCreate, updated via sticky broadcast
    private volatile Intent cachedBatteryIntent = null;
    private BroadcastReceiver batteryReceiver;
    // Edge-detect charging state so we only push telemetry on plug/unplug
    // transitions (ACTION_BATTERY_CHANGED fires on every level change too).
    private volatile int lastChargingState = -1; // -1 unknown, 0 not charging, 1 charging
    private volatile String lastChargerType = null;
    // A charger toggling every second or two is debounced into one flap (see ChargerFlap):
    // no frame per toggle, one when the flap starts and one when it settles.
    private final ChargerFlap chargerFlap = new ChargerFlap();
    private final Handler flapHandler = new Handler(Looper.getMainLooper());
    private final Runnable flapSettleCheck = new Runnable() {
        @Override public void run() {
            if (chargerFlap.settle(SystemClock.elapsedRealtime())) {
                Log.i(TAG, "Charger flap settled — pushing the settled state");
                synchronized (wlcLock) { wlcLastMs = 0; }
                sendTelemetryOverWs();
            } else if (chargerFlap.isFlapping()) {
                flapHandler.postDelayed(this, ChargerFlap.SETTLE_MS);
            }
        }
    };

    // Battery wear (1.10.1): times the constant-current climb across a voltage ladder while
    // charging (see BatteryWear) and reports the last finished climb as battery_cc, and keeps
    // the raw battery curve on the device (see BatteryLog) for designing the health model.
    // Both null on products without a battery and a charger. The last climb is kept across
    // restarts so a keyframe after a reboot still carries it.
    private BatteryWear batteryWear;
    private BatteryLog batteryLog;
    private volatile String lastClimbJson;
    private static final String PREFS_BATTERY_WEAR = "mdm_battery_wear";
    private static final String KEY_LAST_CLIMB = "last_climb";

    // Wi-Fi disconnect tracking: edge-detect connected→disconnected and keep the last
    // hour of event timestamps (elapsedRealtime millis) so getWifiDisconnects1h() is O(n).
    private BroadcastReceiver wifiReceiver;
    // Retries AutoSetup's bootstrap Wi-Fi on unlock and Wi-Fi state changes (a no-op once done).
    private final BroadcastReceiver bootstrapReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            executor.submit(() -> AutoSetup.bootstrapWifi(MdmService.this));
        }
    };
    private volatile boolean wifiWasConnected = false;
    private final java.util.ArrayDeque<Long> wifiDisconnects = new java.util.ArrayDeque<>();

    // Per-field system query caches
    private double cachedStorageFreeGb = 0;
    private long storageLastMs = 0;
    private static final long STORAGE_CACHE_MS = 120_000;

    private JSONObject cachedWifiExtra = null;
    private volatile long wifiLastMs = 0;
    // One minute (1.17.0): the device page's Network tab draws the signal, and a 5-minute
    // cache made it a staircase. Reading the connection info is cheap.
    private static final long WIFI_CACHE_MS = 60_000;

    private JSONArray cachedWifiScan = null;
    private static final long WIFI_SCAN_INTERVAL_SEC = 60;

    private JSONObject cachedRam = null;
    private long ramLastMs = 0;
    private static final long RAM_CACHE_MS = 30_000;

    // Reported (not raw) charger voltage. It jitters by a few mV, and every wobble used to
    // travel as a fresh value and land as a stored history row. This holds what was last
    // reported and only moves once the reading has genuinely moved by the step below, so a
    // value parked on a boundary cannot flap. Deliberately a deadband against the last
    // REPORTED value rather than rounding to a grid: rounding still flips every time the
    // reading crosses a grid line.
    //
    // Battery temperature is NOT held or smoothed (1.4.8): it is reported exactly as healthd
    // gives it, the same value `dumpsys battery` shows, so the MDM and adb agree. The noise
    // is fixed at its source, the battery-NTC ADC (hardware averaging in the device tree).
    private int reportedChargerMv = -1;
    private static final float SENTINEL_TEMP_C = -999f;

    // Fast temperature (1.5.0): while the server's config carries temp_fast_sec > 0, a
    // light tick every temp_fast_sec reads the cached battery broadcast and sends a frame
    // when the temperature moved TEMP_FAST_STEP_C since the last one, or TEMP_FAST_HEARTBEAT_MS
    // passed. Only then is the full extra built, so the tick itself adds almost no load
    // (load shifts the NTC reading). Off below firmware v2.1.023: before healthd smoothed
    // the reading, a 0.2 C step would have fired on its noise every tick.
    private static final double TEMP_FAST_STEP_C = 0.2;
    private static final long TEMP_FAST_HEARTBEAT_MS = 60_000L;
    private volatile int tempFastSec = 0;
    private volatile long lastTempSentMs = 0;          // elapsedRealtime of the last frame sent
    private final Handler tempFastHandler = new Handler(Looper.getMainLooper());
    private final Runnable tempFastTick = new Runnable() {
        @Override public void run() {
            int sec = tempFastSec;
            if (sec <= 0) return;
            double cur = extractBatteryTemperature(getBatteryIntent());
            double prev;
            synchronized (baselineLock) {
                prev = lastSentExtra == null ? SENTINEL_TEMP_C
                        : lastSentExtra.optDouble("battery_temp_c", SENTINEL_TEMP_C);
            }
            if (cur > SENTINEL_TEMP_C && (prev <= SENTINEL_TEMP_C
                    || Math.abs(cur - prev) >= TEMP_FAST_STEP_C
                    || SystemClock.elapsedRealtime() - lastTempSentMs >= TEMP_FAST_HEARTBEAT_MS)) {
                sendTelemetryOverWs();
            }
            tempFastHandler.postDelayed(this, sec * 1000L);
        }
    };
    private static final int CHARGER_STEP_MV = 50;      // the pack's own range spans ~45 mV
    private final Object quantLock = new Object();      // guards the reported value above

    private int cachedWlcStatus = -1;
    private long wlcLastMs = 0;
    private static final long WLC_CACHE_MS = 120_000;
    private final Object wlcLock = new Object();          // guards cachedWlcStatus / wlcLastMs
    // The Qi charging pad exposes no system broadcast (unlike charging, which rides
    // ACTION_BATTERY_CHANGED), so a guest device being placed on / removed from the pad is
    // invisible until the next telemetry read. A short GPIO poll edge-detects that change and
    // pushes immediately, making wlc_status as responsive as the charging state.
    private ScheduledExecutorService wlcWatcher;
    private static final long WLC_WATCH_MS = 1_000;
    private volatile int lastWatchedWlc = Integer.MIN_VALUE; // last value the watcher observed

    // Cross-tick flapping detector. readSettledWlc()'s own disagreement check only catches a
    // pad that won't settle WITHIN one ~160ms sample window — some hardware flaps on a slower
    // cadence instead, where each individual watcher tick settles cleanly but consecutive
    // ticks (WLC_WATCH_MS apart) keep disagreeing (observed in the field: settled=0, then
    // settled=1, then settled=0, one tick apart, agree=3 every time). Tracks the last
    // WLC_FLAP_WINDOW_TICKS raw 0/1 readings the watcher saw; WLC_FLAP_MIN_FLIPS+ transitions
    // in that window means the pad is genuinely bouncing tick-to-tick, not settling on a real
    // placement/removal, so it's reported as flapping instead of whichever value this tick
    // happened to land on.
    private static final int WLC_FLAP_WINDOW_TICKS = 5;
    private static final int WLC_FLAP_MIN_FLIPS = 3;
    private final java.util.ArrayDeque<Integer> wlcRecentTicks = new java.util.ArrayDeque<>();

    // gpio27 only carries guest state while charging is ON (gpio127=1): STABLE 1 = guest
    // on the pad, STABLE 0 = vacant. With charging OFF the line free-runs (toggles rapidly)
    // and carries NO placement info — so to read placement while charging is disabled we
    // briefly pulse gpio127=1, take a settled read, then restore. Characterized on-device
    // with tools/wlc-gpio-matrix.sh and validated with tools/wlc-pulse-test.sh.
    //
    // While charging IS on, a genuinely faulty/loose pad connection makes gpio27 toggle
    // continuously too — field capture (tools/wlc-check.sh --watch, and a raw per-sample
    // adb loop) on real hardware showed edges roughly every 65-200ms, non-stop, for as
    // long as it was watched. That's faster than a "read 3 times, 20ms apart, wait for
    // agreement" scheme reliably catches: reads can land within one half-cycle by pure
    // luck and falsely report "settled" (to whichever value the phase happened to be on).
    // So this burst-samples over a window comfortably longer than the worst edge gap seen
    // and classifies by counting edges in that raw sequence instead — the same method
    // tools/wlc-check.sh already uses for its own live classification.
    private static final int  WLC_SETTLE_READS    = 24;     // burst-sample count
    private static final long WLC_SETTLE_STEP_MS  = 10L;    // gap between samples (~240ms total window)
    private static final int  WLC_SETTLE_MIN_EDGES = 2;     // this many transitions in the burst = flapping

    // Hardware product category (T7 vs Kiosk 18/22/27) + its capabilities, resolved once
    // in onCreate. Gates which telemetry we sample: a wall-powered kiosk has no battery,
    // charger, or Qi pad, so those fields are omitted rather than reported as bogus values.
    private final MdmProduct product = MdmProduct.detect();

    // App list delta
    private String lastAppsHash = null;
    private volatile boolean sendFullAppList = false;

    // Telemetry delta baseline: what the server was last told. A WS frame carries only the
    // gated fields that changed (others are merged server-side from this baseline); a send is
    // skipped entirely when nothing changed. forceKeyframe makes the next send a full snapshot
    // (on start, WS (re)connect, and after a failed send) to (re)establish the baseline.
    private final Object baselineLock = new Object();
    private JSONObject lastSentExtra = null;          // guarded by baselineLock
    private int lastSentBattery = Integer.MIN_VALUE;  // guarded by baselineLock
    private volatile boolean forceKeyframe = true;

    /** Sent by UnlockActivity after a verified PIN exit, to report the new state at once. */
    public static final String ACTION_KIOSK_EXITED = "com.aioapp.mdm.action.KIOSK_EXITED";

    // Connection health
    private static final long HTTP_SAFETY_NET_MS = 5 * 60_000L;
    private static final long STALE_WS_THRESHOLD_SECS = 120;
    private volatile long lastHttpCheckinAt = 0;

    // Shell command gating lives server-side only now (the MDM server decides what it will
    // ever queue as a "shell" command) — the client no longer keeps its own allowlist, so a
    // legitimate command like `update_engine_client` isn't blocked here just because it wasn't
    // anticipated on-device. Restricting who can reach the server's shell endpoint in the first
    // place is what actually matters for safety.

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // CallerRunsPolicy applies backpressure instead of silently discarding a queued
        // ack/telemetry task if the pool ever saturates. Long ops live on heavyExecutor.
        executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32), new ThreadPoolExecutor.CallerRunsPolicy());
        // Bounded (was an unbounded cached pool). A flood of long commands — installs each
        // block up to 180s, shells up to 30s — could otherwise spawn threads without limit and
        // OOM / exhaust fds on a system-UID process. 16 threads + a 32-deep queue is far more
        // than any real command load; genuine overflow is logged and the task dropped (the
        // server re-sends unacked commands).
        heavyExecutor = new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "mdm-heavy");
                    t.setDaemon(true);
                    return t;
                },
                (r, ex) -> Log.w(TAG, "heavy task rejected — pool saturated (possible command flood)"));
        wifiScanExecutor = Executors.newSingleThreadScheduledExecutor();
        wifiScanExecutor.scheduleWithFixedDelay(this::runWifiScan, 0, WIFI_SCAN_INTERVAL_SEC, TimeUnit.SECONDS);
        apiService = new MdmApiService();
        appUsageExecutor = Executors.newSingleThreadScheduledExecutor();
        appUsageExecutor.scheduleWithFixedDelay(() -> {
            try { AppUsage.report(MdmService.this, apiService, getDeviceSerial()); }
            catch (Throwable t) { Log.w(TAG, "app usage report: " + t); }
        }, 120, AppUsage.INTERVAL_MS / 1000, TimeUnit.SECONDS);
        try {
            offlineQueue = new OfflineQueue(this);
            deviceKey = new DeviceKey(this);
            apiService.setDeviceKey(deviceKey, () -> executor.submit(this::restartWebSocket));
            Log.i(TAG, deviceKey.active() != null ? "Using this device's own key" : "No device key yet; using the shared key");
        } catch (Exception e) {
            Log.w(TAG, "Device key storage unavailable, staying on the shared key: " + e.getMessage());
        }
        // Restore command dedup + any terminal acks that weren't confirmed before a restart,
        // and try to flush the acks now (also retried on every reconnect).
        loadCommandState();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MdmAdminReceiver.class);
        // startForeground MUST be called within ~5s of startForegroundService or the OS crashes
        // the process (ForegroundServiceDidNotStartInTimeAllowedException). releaseDeviceOwnerIfAny()
        // makes synchronous DPM binder calls that can stall past 5s on a cold fleet boot, so
        // promote to foreground FIRST, then do provisioning.
        createNotificationChannel();
        notificationVersion = clientVersionName();
        startForeground(NOTIFICATION_ID, buildStatus());
        Notices.afterStart(this, UPDATES_CHANNEL_ID, notificationVersion);
        // Kiosk needs the active admin, never Device Owner — KioskManager claims and releases the
        // admin with the kiosk config itself.
        releaseDeviceOwnerIfAny();
        KioskManager.releaseAdminIfUnused(this, dpm, adminComponent);
        LostMode.enforce(this, dpm, adminComponent); // back up after a crash or a kill
        bleExecutor = Executors.newSingleThreadScheduledExecutor();
        bleExecutor.scheduleWithFixedDelay(this::bleTick, 5, 10, TimeUnit.SECONDS);

        // A self-update the previous build started: this one reports how it went. Queued on
        // the executor because the ack may have to go over HTTP before the socket is up.
        // An install that replaced the previous process, or a reboot that interrupted one,
        // left the verifier off with its old value saved. Put it back before anything else.
        restorePackageVerifier();
        executor.execute(() -> ClientUpdater.settlePending(this,
                (cmdId, status, output) -> reportTerminal(cmdId, getDeviceSerial(), status, output)));

        // Product (resolved once at field init) gates the receiver registrations below and
        // the telemetry collectors via its capabilities.
        // The client's own version leads the line: the first question about a device is
        // which client it runs, and since 1.0.0 that can differ from the firmware it
        // shipped with (ClientUpdater installs newer builds over it).
        Log.i(TAG, "MdmClient v" + clientVersionName() + " (" + clientVersionCode() + ")"
                + " product=" + product.key()
                + " battery=" + product.hasBattery()
                + " charging=" + product.hasCharging()
                + " wlc=" + product.hasWlc());

        if (product.hasBattery() && product.hasCharging()) {
            batteryWear = new BatteryWear(SystemClock.elapsedRealtime(), isScreenOn());
            batteryLog = new BatteryLog(new File(getFilesDir(), "battery"));
            lastClimbJson = getSharedPreferences(PREFS_BATTERY_WEAR, MODE_PRIVATE)
                    .getString(KEY_LAST_CLIMB, null);
        }
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                cachedBatteryIntent = intent;
                int charging = extractCharging(intent) ? 1 : 0;
                String chargerType = extractChargerType(intent);
                if (batteryWear != null) {
                    int ichgUa = readChargeCurrentUa(intent);
                    logBattery("b", intent, ichgUa);
                    // On a healthy socket HTTP check-ins are rare, so the broadcast is what
                    // keeps the upload going; flushBatteryLog paces itself.
                    flushBatteryLog();
                    // A climb that ends here on a charging edge rides the edge's push below;
                    // one that ends without an edge (top rung reached, or a charge stop while
                    // still plugged in) pushes on its own.
                    if (feedBatteryWear(intent, ichgUa)
                            && (lastChargingState == -1 || charging == lastChargingState)) {
                        sendTelemetryOverWs();
                    }
                }
                if (lastChargingState != -1 && charging != lastChargingState) {
                    boolean started = chargerFlap.onFlip(SystemClock.elapsedRealtime(),
                            lastChargingState == 1, lastChargerType);
                    if (chargerFlap.isFlapping()) {
                        // Flapping: the reported state is held, so a push per toggle would
                        // carry nothing new. Say it once, and again when it settles.
                        if (started) {
                            Log.w(TAG, "Charger flapping (" + ChargerFlap.ENTER_FLIPS + "+ toggles in "
                                    + (ChargerFlap.WINDOW_MS / 1000) + "s) — holding charging state");
                            sendTelemetryOverWs();
                        }
                        flapHandler.removeCallbacks(flapSettleCheck);
                        flapHandler.postDelayed(flapSettleCheck, ChargerFlap.SETTLE_MS);
                    } else {
                        Log.i(TAG, "Charging state changed: " + lastChargingState + " -> " + charging
                                + " — pushing immediate telemetry");
                        // A power transition can change what the pad reports; drop the wlc cache
                        // so the imminent push carries a fresh reading rather than a stale one.
                        synchronized (wlcLock) { wlcLastMs = 0; }
                        sendTelemetryOverWs();
                    }
                }
                lastChargingState = charging;
                lastChargerType = chargerType;
                if (batteryPill != null) {
                    batteryPill.onBattery(intent, isInKioskLock() && !LostMode.isOn(MdmService.this)
                            && !KioskExit.isStaffMode(MdmService.this) && !StaffPanelActivity.showing);
                }
            }
        };
        if (product.hasBattery()) batteryPill = new BatteryPill(this);
        cachedBatteryIntent = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        // Wi-Fi stability: count connected→disconnected transitions so the server can flag
        // "≥N disconnects in an hour". SUPPLICANT/NETWORK state changes fire often, so we
        // only record an edge from a previously-connected state to a disconnected one.
        wifiReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                NetworkInfo ni = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                boolean connected = ni != null && ni.isConnected();
                // The network changed: the next report reads it fresh rather than from the
                // one-minute cache, so the dashboard never shows the old network (1.18.1).
                wifiLastMs = 0;
                if (wifiWasConnected && !connected) {
                    recordWifiDisconnect();
                }
                wifiWasConnected = connected;
            }
        };
        registerReceiver(wifiReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));

        // Both edges count as one press: Power with the screen on gives SCREEN_OFF, Power
        // with it off gives SCREEN_ON.
        screenOnReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                BatteryWear wear = batteryWear;
                if (wear != null) {
                    wear.onScreen(SystemClock.elapsedRealtime(),
                            Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
                    logBattery("s", getBatteryIntent(), Integer.MIN_VALUE);
                }
            }
        };
        IntentFilter powerFilter = new IntentFilter();
        powerFilter.addAction(Intent.ACTION_SCREEN_ON);
        powerFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOnReceiver, powerFilter);

        kioskExitRequestObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                onKioskExitRequested();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KIOSK_EXIT_REQUEST_SETTING), false,
                kioskExitRequestObserver);
        staffPill = new StaffPill(this, this::backToGuestMode);

        lockTaskListener = new TaskStackListener() {
            @Override public void onLockTaskModeChanged(int mode) {
                if (mode == ActivityManager.LOCK_TASK_MODE_LOCKED) {
                    relockHandler.post(() -> RelockCoverActivity.hide(MdmService.this));
                    return;
                }
                if (mode != ActivityManager.LOCK_TASK_MODE_NONE) return;
                relockHandler.removeCallbacks(lockDroppedRunnable);
                relockHandler.post(lockDroppedRunnable);
            }
        };
        try {
            ActivityTaskManager.getService().registerTaskStackListener(lockTaskListener);
        } catch (Exception e) {
            Log.e(TAG, "lock-task listener: " + e.getMessage());
        }

        alarmManager = getSystemService(AlarmManager.class);
        pollReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                enforceKioskLock();
                updateStaffPill();
                if (networkAvailable && !polling) performCheckin();
                else maybeQueueOffline();
                scheduleNextPoll();
            }
        };
        registerReceiver(pollReceiver, new IntentFilter(POLL_ACTION), Context.RECEIVER_NOT_EXPORTED);
        // A14 decides dismissibility itself: NotificationManagerService.fixNotification() keeps
        // FLAG_NO_DISMISS only for media/CallStyle notifications or an "enterprise exempted" app
        // (Device Owner/Profile Owner, or the OP_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS
        // appop — which it refuses outright for uid SYSTEM, which is us). We hold the active
        // admin only while kiosked and never Device Owner, so FLAG_NO_CLEAR is stripped and the
        // status line can be swiped away. Re-post it the moment that happens; the periodic
        // ensureStatusNotification() on check-in is only the slow backstop.
        notifDismissReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "status notification dismissed — re-posting");
                getSystemService(NotificationManager.class)
                        .notify(NOTIFICATION_ID, buildStatus());
            }
        };
        registerReceiver(notifDismissReceiver, new IntentFilter(NOTIF_DISMISSED_ACTION),
                Context.RECEIVER_NOT_EXPORTED);
        pollIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(POLL_ACTION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        armWatchdog();

        registerNetworkCallback();
        registerPackageChangeReceiver();
        // Only run the WLC watcher on products that actually have a wireless-charging
        // pad. Kiosks have none, so there is nothing to sample — skip it entirely.
        if (product.hasWlc()) {
            startWlcWatcher();
            applySavedWlcCharging(); // sysfs resets to on across reboot — restore the saved choice
        }
        startCommandPoller();
        // Mic gain enforcement target (debug prop) resets on boot — restore the saved one.
        executor.submit(() -> MicGain.applySavedTarget(MdmService.this));
        // A locked brightness is held again (restriction, auto off, observer).
        ScreenBrightness.restore(this);
        // A wiped device saves the firmware's bootstrap Wi-Fi so it can reach us (AutoSetup):
        // now, and again on unlock and whenever Wi-Fi changes state, since the first tries
        // can come before unlock or before Wi-Fi is up.
        IntentFilter bootstrapFilter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        bootstrapFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(bootstrapReceiver, bootstrapFilter);
        executor.submit(() -> AutoSetup.bootstrapWifi(MdmService.this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Cancel any existing alarm before rescheduling — prevents duplicates when
        // LOCKED_BOOT_COMPLETED + BOOT_COMPLETED both fire on a fresh boot.
        alarmManager.cancel(pollIntent);
        // A technician who exits kiosk with the static PIN does it from UnlockActivity,
        // which is a separate process-less activity with no handle on this service — so
        // the exit used to reach the server only on the next scheduled poll, and the
        // dashboard kept saying "Kiosk on" over an unlocked device for minutes. The
        // activity starts us with this action instead, and it reports at once, exactly
        // like the Back+Power gesture path does.
        if (intent != null && ACTION_KIOSK_EXITED.equals(intent.getAction())) {
            Log.i(TAG, "kiosk exited via unlock PIN — pushing immediate telemetry");
            forceKeyframe = true;
            sendTelemetryOverWs();
        }
        // Again on every start: the first one can come before unlock or with Wi-Fi still off.
        executor.submit(() -> AutoSetup.bootstrapWifi(MdmService.this));
        if (networkAvailable && !polling) performCheckin();
        enforceKioskLock(); // self-heal: re-assert lock-task if it has dropped
        updateStaffPill();
        scheduleNextPoll();
        return START_STICKY;
    }

    /**
     * Kiosk watchdog. Kiosk policy is otherwise applied only when the server pushes a
     * CHANGED config, so any drop of lock-task -- a warm apply that never entered it, the
     * kiosk app crashing/relaunching, an errant stopLockTask, a reboot race -- would leave
     * the device unlocked until someone toggles the config. This runs every poll and
     * re-asserts the saved kiosk policy whenever the device should be locked but isn't
     * (not LOCKED, or the wrong app is foreground). Honours the offline-exit suspension so
     * a technician's local exit is not fought.
     */
    private void enforceKioskLock() {
        try {
            if (LostMode.isOn(this)) {  // the lost screen outranks the kiosk app
                LostMode.enforce(this, dpm, adminComponent);
                return;
            }
            JSONObject cfg = KioskManager.loadConfig(this);
            if (cfg == null || !cfg.optBoolean("kiosk_enabled", false)) return;
            String pkg = cfg.optString("kiosk_package", "");
            if (pkg.isEmpty()) return;
            if (KioskExit.isSuspended(this)) return; // respect a verified offline exit
            // Use isProperlyLocked (LOCKED only): PINNED leaves the nav bar visible, so it
            // must be treated as unlocked and re-asserted into a real device-owner lock.
            boolean locked = KioskManager.isProperlyLocked(this);
            String fg = KioskManager.foregroundPackage(this);
            // The client's own screens (the staff PIN pad, the re-lock cover) are allowed
            // over the host app in lock task; one of them in front is not a dropped lock.
            if (!locked || !(pkg.equals(fg) || getPackageName().equals(fg))) {
                Log.w(TAG, "kiosk watchdog: reasserting lock (state=" + KioskManager.lockState(this) + " fg=" + fg + " want=" + pkg + ")");
                KioskManager.apply(this, dpm, adminComponent, cfg);
            }
        } catch (Exception e) {
            Log.e(TAG, "kiosk watchdog error: " + e.getMessage());
        }
    }

    /**
     * Lock task just ended (runs on the main thread, after whatever ended it, such as an
     * offline exit, has saved its state). On a tablet that should be locked, cover the
     * launcher at once and re-lock shortly; otherwise leave it be.
     */
    private void onLockTaskDropped() {
        relockHandler.removeCallbacks(relockRunnable);
        if (!kioskWanted() || relockBudgetSpent()) return;
        RelockCoverActivity.show(this);
        relockHandler.postDelayed(relockRunnable, RELOCK_DELAY_MS);
    }

    /** The saved config wants this tablet locked, and nothing local says otherwise. */
    private boolean kioskWanted() {
        if (LostMode.isOn(this) || KioskExit.isSuspended(this)) return false;
        JSONObject cfg = KioskManager.loadConfig(this);
        return cfg != null && cfg.optBoolean("kiosk_enabled", false)
                && !cfg.optString("kiosk_package", "").isEmpty();
    }

    /** RELOCK_MAX re-locks already inside RELOCK_WINDOW_MS: the app is crash-looping. */
    private boolean relockBudgetSpent() {
        long oldest = relockTimes[relockIdx];
        return oldest != 0 && SystemClock.elapsedRealtime() - oldest < RELOCK_WINDOW_MS;
    }

    /**
     * Lock task just ended. When the saved config still wants the device locked (and no
     * offline exit or lost mode says otherwise, which enforceKioskLock checks), put the host
     * app back at once. The delay lets a relaunch with CLEAR_TASK, which passes through
     * LOCK_TASK_MODE_NONE on its way back to LOCKED, settle first.
     */
    private void relockAfterDrop() {
        long now = SystemClock.elapsedRealtime();
        if (relockBudgetSpent()) {
            Log.w(TAG, "kiosk relock: " + RELOCK_MAX + " drops in " + RELOCK_WINDOW_MS / 1000
                    + " s, leaving it to the poll");
            RelockCoverActivity.hide(this);
            return;
        }
        if (KioskManager.isProperlyLocked(this)) {
            RelockCoverActivity.hide(this);
            return;
        }
        relockTimes[relockIdx] = now;
        relockIdx = (relockIdx + 1) % RELOCK_MAX;
        enforceKioskLock();
    }

    /** True when the device is currently in lock-task (kiosk) mode. */
    private boolean isInKioskLock() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Staff mode: the PIN was right, so the tablet is out of lock task. The top pill says so
     * over every app until someone taps "Back to guest mode" or the dashboard locks it again.
     * It outlasts the server's answer to the exit, which turns the lock off server-side.
     */
    private void updateStaffPill() {
        if (staffPill == null) return;
        if (KioskExit.isStaffMode(this) && !LostMode.isOn(this)) staffPill.show();
        else staffPill.hide();
    }

    /** "Back to guest mode" on the staff pill: lock the host app again now. */
    private void backToGuestMode() {
        String pkg = KioskExit.staffPackage(this);
        Log.i(TAG, "staff pill: back to guest mode on " + pkg);
        KioskExit.setSuspended(this, false);
        KioskExit.endStaffMode(this);
        KioskExit.markRelocked(this);
        staffPill.hide();
        if (!pkg.isEmpty()) {
            // Lock the app staff mode left, whatever the server's config says by now (its
            // answer to the exit turned the lock off); kiosk_relocked_at tells it.
            JSONObject cfg = KioskManager.loadConfig(this);
            try {
                if (cfg == null) cfg = new JSONObject();
                cfg.put("kiosk_enabled", true);
                cfg.put("kiosk_package", pkg);
                KioskManager.applyAndSave(this, dpm, adminComponent, cfg);
            } catch (JSONException e) {
                Log.e(TAG, "back to guest mode: " + e.getMessage());
            }
        }
        forceKeyframe = true;
        sendTelemetryOverWs();
        if (networkAvailable && !polling) performCheckin();
    }

    /** The firmware asked for the staff PIN pad (corner + Power while locked). */
    private void onKioskExitRequested() {
        if (!isInKioskLock() || LostMode.isOn(this) || !KioskExit.isEnabled(this)) return;
        if (StaffPanelActivity.showing) return; // staff are already in
        Log.i(TAG, "kiosk-exit: corner + Power — opening the staff PIN pad");
        showUnlockPrompt();
    }

    /** Wakes the screen (the last press may have turned it off) and shows UnlockActivity. */
    private void showUnlockPrompt() {
        try {
            wakeScreen(null);
            Intent i = new Intent(this, UnlockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "showUnlockPrompt failed: " + e.getMessage());
        }
    }

    /** True when the device is on external power (wired or wireless pad). */
    private boolean isOnExternalPower() {
        Intent b = getBatteryIntent();
        return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** Arm (or re-arm — same PendingIntent replaces it) the repeating WatchdogReceiver alarm. */
    private void armWatchdog() {
        PendingIntent watchdog = PendingIntent.getBroadcast(this, 0,
                new Intent(WATCHDOG_ACTION).setClass(this, WatchdogReceiver.class),
                PendingIntent.FLAG_IMMUTABLE);
        alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, watchdog);
    }

    private void scheduleNextPoll() {
        long intervalMs = getAdaptivePollInterval() * apiService.getBackoffMultiplier();

        // If the server asked us to slow down (429/503 Retry-After), never poll sooner than that.
        intervalMs = Math.max(intervalMs, apiService.consumeRetryAfterMs());

        boolean powered = isOnExternalPower();
        boolean wsHealthy = wsClient != null && wsClient.isConnected()
                && wsClient.getSecsSinceLastData() <= STALE_WS_THRESHOLD_SECS;

        // On battery with a healthy WebSocket, the WS keepalive thread (which uses no alarm
        // of its own) already carries presence + pushed commands and self-reconnects, so the
        // wakeup alarm only needs to fire at the HTTP safety-net cadence — this drops the
        // ~9/10 Doze wakeups that previously only re-sent a WS telemetry frame.
        if (!powered && wsHealthy) {
            intervalMs = Math.max(intervalMs, HTTP_SAFETY_NET_MS);
        }

        // Add up to +20% jitter so ~900 devices that boot / restore power / reconnect together
        // don't fire their poll alarm in lockstep and stampede the server every cycle. Only
        // lengthens the interval (never below the intended cadence).
        long jitter = ThreadLocalRandom.current().nextLong((intervalMs / 5) + 1);
        long triggerAt = SystemClock.elapsedRealtime() + intervalMs + jitter;
        if (powered) {
            // Powered (kiosk on a charger): keep exact, responsive wakeups.
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pollIntent);
        } else {
            // On battery: inexact so the OS can batch this wakeup with other Doze maintenance.
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pollIntent);
        }
    }

    /**
     * Give Device Owner back. The client has no use for it — kiosk lock-task is authorized by
     * the package allowlist the active admin writes (LockTaskController.getLockTaskAuth), not by
     * ownership — and holding it makes the device un-certifiable: GTS removes non-test admins
     * before its device-policy tests and the multiuser modules abort on device-owner removal,
     * while a non-test admin cannot be removed by anything but its own package
     * (DevicePolicyManagerService.forceRemoveActiveAdmin — what `dpm remove-active-admin` calls —
     * throws for it, root and shell included).
     *
     * Only the device-owner package itself may clear the owner, so this has to happen in here.
     * Clearing it also drops the DISALLOW_ADD_USER / DISALLOW_ADD_*_PROFILE restrictions the
     * framework attached to the owner (clearDeviceOwnerLocked -> clearDeviceOwnerUserRestriction),
     * which is what lets a GTS multiuser run add users on a device provisioned earlier.
     *
     * The active admin is a separate thing: KioskManager claims it only while a device is kiosked
     * and releases it when kiosk goes off, so a device that is never kiosked holds neither.
     */
    private void releaseDeviceOwnerIfAny() {
        try {
            if (!dpm.isDeviceOwnerApp(getPackageName())) return;
            dpm.clearDeviceOwnerApp(getPackageName());
            Log.i(TAG, "released Device Owner — kiosk runs on the active admin alone");
        } catch (Exception e) {
            Log.e(TAG, "clearDeviceOwnerApp failed: " + e.getMessage());
        }
    }

    private void registerNetworkCallback() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                networkAvailable = true;
                refreshStatus();
                Log.i(TAG, "Network available");
                if (!remoteConfigLoaded) {
                    executor.submit(() -> {
                        apiService.loadRemoteConfig();
                        remoteConfigLoaded = true;
                        startWebSocket();
                    });
                }
            }

            @Override
            public void onLost(Network network) {
                networkAvailable = false;
                Log.i(TAG, "Network lost");
                refreshStatus();
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);

        Network active = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
        networkAvailable = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        refreshStatus();
    }

    private void performCheckin() {
        long now = System.currentTimeMillis();
        ensureStatusNotification();
        // A self-update that never installed: report it rather than leaving the command at
        // "installing" until this service happens to restart.
        ClientUpdater.sweepStalePending(this,
                (cmdId, status, output) -> reportTerminal(cmdId, getDeviceSerial(), status, output));
        maybeQueueOffline();
        if (wsClient != null && wsClient.isConnected()) {
            // Liveness is gauged by data *received* (server keepalive pings every ~45s), not by
            // how often we send — with change-gated telemetry a healthy link can be quiet.
            long staleSecs = wsClient.getSecsSinceLastData();
            if (staleSecs > STALE_WS_THRESHOLD_SECS) {
                Log.w(TAG, "WS stale (no data " + staleSecs + "s), forcing reconnect");
                wsClient.forceReconnect();
            } else {
                sendTelemetryOverWs();
            }
        } else if (wsClient != null && wsClient.isStuckDialing()) {
            // Alive but stuck inside a dial (a TLS handshake that lost the network used to
            // block with no timeout): the loop never gets to retry, so start a fresh client.
            Log.w(TAG, "WS dial hung — restarting the WebSocket client");
            executor.submit(this::restartWebSocket);
        } else if (wsClient != null && !wsClient.isLoopAlive()) {
            // The reconnect loop is gone but this object is still here, so startWebSocket()
            // would return early and the device would hold no socket for the rest of its
            // uptime: no commands, no OTA, Offline on the dashboard, telemetry still flowing
            // over the HTTP safety net so nothing looks wrong. Found on AT070AABU00077
            // (2026-10-09): 15h of uptime, not one WS dial reaching the server.
            Log.w(TAG, "WS loop thread is dead — restarting the WebSocket client");
            executor.submit(this::restartWebSocket);
        } else if (wsClient == null && remoteConfigLoaded && networkAvailable) {
            // onAvailable fires once per network and is the only thing that ever starts the
            // socket; if it was missed (or its task died before startWebSocket), nothing else
            // tries again. This tick is that second chance.
            Log.w(TAG, "No WebSocket client but the network is up — starting it");
            executor.submit(this::startWebSocket);
        }
        // HTTP safety net: always checkin via HTTP every 5 minutes regardless of WS state.
        // Command delivery is NOT this schedule's job — telemetry cadence and command
        // latency are different problems, and making check-ins minutely to carry commands
        // would multiply the fleet's telemetry writes by five to fix a delivery bug. The
        // response still carries any pending command it happens to find (runHttpCommands),
        // which is a free backup; the minute-by-minute command poll is its own loop.
        if ((now - lastHttpCheckinAt) >= HTTP_SAFETY_NET_MS) {
            lastHttpCheckinAt = now;
            executor.submit(() -> {
                polling = true;
                try {
                    JSONObject payload = buildCheckinPayload();
                    JSONObject response = apiService.checkin(payload);
                    if (response != null) {
                        // Keyframe landed — reset the delta baseline to this full snapshot.
                        // Battery-less products (kiosks) omit battery_pct from the payload — use
                        // optInt(-1) to match extractBatteryPct's no-battery value and avoid a
                        // JSONException ("No value for battery_pct") after an otherwise-OK check-in.
                        rememberSent(payload.getJSONObject("extra"), payload.optInt("battery_pct", -1));
                        if (response.optBoolean("send_apps", false)) sendFullAppList = true;
                        JSONObject config = response.optJSONObject("config");
                        if (config != null) applyConfig(config);
                        runHttpCommands(response);
                        maybeRegisterDeviceKey();
                        serverReachable = true;
                        refreshStatus();
                        AutoSetup.maybeFinish(MdmService.this); // we reach it: setup can go
                        flushOfflineQueue();
                        flushBatteryLog();
                    } else {
                        serverReachable = false;
                        refreshStatus();
                        maybeQueueOffline();
                        Log.w(TAG, "Checkin failed, reloading remote config");
                        apiService.loadRemoteConfig();
                        remoteConfigLoaded = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Checkin error: " + e.getMessage());
                } finally {
                    polling = false;
                }
            });
        }
    }

    private void ackCommand(String cmdId, String serial, String status, String output) {
        ackCommand(cmdId, serial, status, output, null);
    }

    /** A NetScout frame (sighting / scan-done / enrol progress) back to the server over the
     *  command WS. Dropped if the socket is down: a scan is retried from the server, and a
     *  half-reported scan is harmless — the next one supersedes it. */
    private void sendScoutFrame(JSONObject frame) {
        MdmWebSocketClient ws = wsClient;
        if (ws == null || !ws.isConnected()) { Log.w(TAG, "scout frame dropped (WS down)"); return; }
        try { ws.send(frame.toString()); }
        catch (Exception e) { Log.w(TAG, "scout frame send failed: " + e.getMessage()); }
    }

    /** pkg (nullable) is the package an install produced — reported on 'installed' so
     *  the server can reconcile a stuck install against the device's package list. */
    private void ackCommand(String cmdId, String serial, String status, String output, String pkg) {
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "command_ack");
                m.put("command_id", cmdId);
                m.put("serial_number", serial);
                m.put("status", status);
                if (!output.isEmpty()) m.put("output", output);
                if (pkg != null && !pkg.isEmpty()) m.put("package", pkg);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS ack failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.ackCommand(cmdId, serial, status, output, pkg));
    }

    // ── Command idempotency + reliable terminal acks ─────────────────────────────

    /** Load persisted dedup + pending-ack state once, on startup. */
    private synchronized void loadCommandState() {
        if (cmdStateLoaded) return;
        cmdStateLoaded = true;
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_CMD, MODE_PRIVATE);
        String done = sp.getString(KEY_DONE_CMDS, "");
        if (!done.isEmpty()) {
            synchronized (recentDoneCommands) {
                for (String id : done.split(",")) if (!id.isEmpty()) recentDoneCommands.add(id);
            }
        }
        String acks = sp.getString(KEY_PENDING_ACKS, "");
        if (!acks.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(acks);
                synchronized (pendingAcks) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.getJSONObject(i);
                        pendingAcks.put(a.getString("command_id"), a);
                    }
                }
            } catch (Exception e) { Log.e(TAG, "loadCommandState acks: " + e.getMessage()); }
        }
        String otaAcks = sp.getString(KEY_PENDING_OTA_ACKS, "");
        if (!otaAcks.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(otaAcks);
                synchronized (pendingOtaAcks) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.getJSONObject(i);
                        pendingOtaAcks.put(a.getString("command_id"), a);
                    }
                }
            } catch (Exception e) { Log.e(TAG, "loadCommandState otaAcks: " + e.getMessage()); }
        }
        drainPendingAcks();
        drainPendingOtaAcks();
    }

    /** Entry point for an incoming WS command: confirm receipt, dedup, then run. */
    private void handleIncomingCommand(JSONObject msg) {
        final String cmdId = msg.optString("id", "");
        if (cmdId.isEmpty()) { Log.w(TAG, "WS command with no id"); return; }
        // 1. Always confirm receipt so the server stops re-delivering — even a duplicate.
        sendReceived(cmdId);
        // 2. Idempotency: never execute a command already finished or still running.
        boolean alreadyDone;
        synchronized (recentDoneCommands) { alreadyDone = recentDoneCommands.contains(cmdId); }
        if (alreadyDone || !inFlightCommands.add(cmdId)) {
            Log.i(TAG, "Duplicate command " + cmdId + " ignored (already handled/in-flight)");
            return;
        }
        heavyExecutor.submit(() -> {
            try {
                processWsCommand(msg);
            } catch (Exception e) {
                Log.e(TAG, "WS command error: " + e.getMessage());
            } finally {
                markCommandDone(cmdId);
            }
        });
    }

    /** Command poll cadence while there is no usable socket. See startCommandPoller. */
    private static final long COMMAND_POLL_MS = 60_000L;
    /** Poll at least this often even with a healthy socket — see the floor check below. */
    private static final long COMMAND_POLL_FLOOR_MS = 5 * 60_000L;
    private volatile long lastCommandPollAt = 0L;
    private ScheduledExecutorService commandPoller;
    /** Set by a server "wake" frame (and after a terminal ack) to poll on the next tick. */
    private final AtomicBoolean pollCommandsNow = new AtomicBoolean(false);

    /**
     * Ask the server for queued commands, on its own cadence, independent of telemetry.
     *
     * Commands used to arrive only over the WebSocket, so a client whose socket died could
     * not be reached at all — it kept checking in, looked healthy on every page, and ran
     * nothing sent to it (AT070AABU00077, 9 Oct 2026: 15 hours, a shell command stuck at
     * 'delivered', fixed only by a reboot). Asking is the half a device can always do.
     *
     * Deliberately NOT the check-in schedule. Command latency and telemetry volume are
     * different problems: making check-ins minutely to carry commands would multiply the
     * fleet's telemetry writes by five to fix a delivery bug. So telemetry keeps its
     * five-minute keyframe and this loop owns command latency.
     *
     * While the socket is healthy this does nothing — the server sends a "wake" frame when
     * there is work, which is instant and cheaper than polling. The loop is what covers the
     * case where that frame can never arrive.
     */
    private void startCommandPoller() {
        commandPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mdm-cmd-poll");
            t.setDaemon(true);
            return t;
        });
        commandPoller.scheduleWithFixedDelay(() -> {
            try {
                if (!running || !networkAvailable || !remoteConfigLoaded) return;
                boolean wsUsable = wsClient != null && wsClient.isConnected()
                        && wsClient.getSecsSinceLastData() <= STALE_WS_THRESHOLD_SECS;
                // A healthy socket is not proof that frames are arriving: a half-open one
                // reads as connected and swallows every wake, which is precisely how a
                // device ends up queued-but-untouched. So even a healthy socket is polled
                // at a floor cadence, and that floor is the worst case for a swallowed
                // wake — minutes, not forever.
                boolean floorDue = SystemClock.elapsedRealtime() - lastCommandPollAt >= COMMAND_POLL_FLOOR_MS;
                // A wake frame (or a command that just finished, which may have been
                // holding its lane) overrides the socket check: poll once, now.
                if (wsUsable && !floorDue && !pollCommandsNow.getAndSet(false)) return;
                lastCommandPollAt = SystemClock.elapsedRealtime();
                pollCommandsNow.set(false);
                JSONObject resp = apiService.fetchPendingCommands(getDeviceSerial());
                if (resp != null) runHttpCommands(resp);
            } catch (Exception e) {
                Log.w(TAG, "command poll tick: " + e.getMessage());
            }
        }, 15_000L, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** Poll on the next tick rather than waiting out the interval. */
    private void pollCommandsSoon() {
        pollCommandsNow.set(true);
    }

    /**
     * Fetch now, off the socket thread. A wake frame means the server already has work
     * queued, so waiting out the poll interval would throw away the whole point of being
     * told — the first version of this only set the flag, and a command to a connected
     * device still took up to a minute (AT070AA2600024: 83s, measured on stage).
     */
    private void pollCommandsNow() {
        pollCommandsNow.set(true);
        executor.submit(() -> {
            try {
                if (!running || !networkAvailable) return;
                pollCommandsNow.set(false);
                JSONObject resp = apiService.fetchPendingCommands(getDeviceSerial());
                if (resp != null) runHttpCommands(resp);
            } catch (Exception e) {
                Log.w(TAG, "immediate command poll: " + e.getMessage());
            }
        });
    }

    /**
     * Commands handed back in a check-in response — the backup command channel.
     *
     * This client took commands over the WebSocket only (the HTTP command parse was
     * dropped in 56b160e), so a device whose socket was gone could not be reached at all:
     * it kept checking in, looked alive on every page, and ran nothing that was sent to it
     * (AT070AABU00077, 9 Oct 2026 — 15h of check-ins, a shell command stuck at
     * 'delivered'). The server now puts pending commands in the response whenever this
     * device holds no socket and said it reads them (extra.http_commands).
     *
     * Everything downstream is shared with the WS path: handleIncomingCommand acks receipt
     * over HTTP, dedups by id against in-flight and recently-done commands, and runs on
     * heavyExecutor. Results, progress and OTA status already go over HTTP. So a command
     * delivered this way behaves exactly like a pushed one, only later.
     */
    private void runHttpCommands(JSONObject response) {
        JSONArray cmds = response.optJSONArray("commands");
        if (cmds == null || cmds.length() == 0) return;
        Log.i(TAG, "Check-in carried " + cmds.length() + " command(s) over HTTP (no socket)");
        for (int i = 0; i < cmds.length(); i++) {
            JSONObject cmd = cmds.optJSONObject(i);
            if (cmd == null) continue;
            try {
                // The HTTP shape names the type "type" (what MDM-lite reads) as well as
                // "command_type" (the WS field); normalise so processWsCommand — the same
                // code for both transports — sees the shape it expects.
                if (!cmd.has("command_type")) {
                    cmd.put("command_type", cmd.optString("type", "install_apk"));
                }
                handleIncomingCommand(cmd);
            } catch (Exception e) {
                Log.e(TAG, "HTTP command " + cmd.optString("id", "?") + " error: " + e.getMessage());
            }
        }
    }

    /** Confirm receipt of a command (before/independent of executing it). */
    private void sendReceived(String cmdId) {
        ackCommand(cmdId, getDeviceSerial(), "received", "");
    }

    private void markCommandDone(String cmdId) {
        inFlightCommands.remove(cmdId);
        // This command may have been holding its lane, so the next one only becomes
        // deliverable now. Ask again rather than idling for a minute.
        pollCommandsSoon();
        synchronized (recentDoneCommands) {
            recentDoneCommands.remove(cmdId);
            recentDoneCommands.add(cmdId);
            while (recentDoneCommands.size() > MAX_DONE_CMDS) {
                java.util.Iterator<String> it = recentDoneCommands.iterator();
                it.next(); it.remove();
            }
            getSharedPreferences(PREFS_CMD, MODE_PRIVATE).edit()
                    .putString(KEY_DONE_CMDS, String.join(",", recentDoneCommands)).apply();
        }
    }

    /** Terminal command result. Persisted and retried over HTTP until the server confirms,
     *  so a 'completed'/'failed' is never lost to a half-open socket or a process restart.
     *  Replaces the old fire-and-forget WS/HTTP terminal acks. */
    /**
     * Erase the device the way Settings → Reset does: the FACTORY_RESET broadcast to the
     * framework's MasterClearReceiver, which needs MASTER_CLEAR (the system uid passes every
     * permission check). The force extra gets past DISALLOW_FACTORY_RESET, which a lock policy
     * may have set to keep a guest from resetting the tablet — it is not meant to stop us.
     * External storage is kept: on our hardware it is the SD card, not user data.
     */
    private void factoryReset(String reason) {
        Log.w(TAG, "Factory reset: " + reason);
        Intent i = new Intent(Intent.ACTION_FACTORY_RESET);
        i.setPackage("android");
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.putExtra(Intent.EXTRA_REASON, reason);
        i.putExtra("android.intent.extra.FORCE_FACTORY_RESET", true);
        sendBroadcast(i);
    }

    private void reportTerminal(String cmdId, String serial, String status, String output) {
        reportTerminal(cmdId, serial, status, output, null);
    }
    private void reportTerminal(String cmdId, String serial, String status, String output, String pkg) {
        try {
            JSONObject a = new JSONObject();
            a.put("command_id", cmdId);
            a.put("serial_number", serial);
            a.put("status", status);
            if (output != null && !output.isEmpty()) a.put("output", output);
            if (pkg != null && !pkg.isEmpty()) a.put("package", pkg);
            synchronized (pendingAcks) { pendingAcks.put(cmdId, a); persistPendingAcks(); }
        } catch (Exception e) { Log.e(TAG, "reportTerminal: " + e.getMessage()); }
        drainPendingAcks();
    }

    /** persist pendingAcks; caller holds the monitor. */
    private void persistPendingAcks() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (JSONObject a : pendingAcks.values()) arr.put(a);
        getSharedPreferences(PREFS_CMD, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_ACKS, arr.toString()).apply();
    }

    /** Deliver every not-yet-confirmed terminal ack over HTTP; drop each on a 2xx. Safe to
     *  call repeatedly (on reconnect, on report, on startup) — server acks are idempotent. */
    private void drainPendingAcks() {
        executor.submit(() -> {
            java.util.List<JSONObject> snapshot;
            synchronized (pendingAcks) {
                if (pendingAcks.isEmpty()) return;
                snapshot = new java.util.ArrayList<>(pendingAcks.values());
            }
            for (JSONObject a : snapshot) {
                boolean ok = apiService.ackCommandOk(
                        a.optString("command_id"), a.optString("serial_number"),
                        a.optString("status"), a.optString("output", ""),
                        a.has("package") ? a.optString("package") : null);
                if (ok) {
                    synchronized (pendingAcks) { pendingAcks.remove(a.optString("command_id")); persistPendingAcks(); }
                }
            }
        });
    }

    /** persist pendingOtaAcks; caller holds the monitor. */
    private void persistPendingOtaAcks() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (JSONObject a : pendingOtaAcks.values()) arr.put(a);
        getSharedPreferences(PREFS_CMD, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_OTA_ACKS, arr.toString()).apply();
    }

    /** Deliver every not-yet-confirmed terminal OTA status over HTTP; drop each on a 2xx.
     *  Mirrors drainPendingAcks — safe to call repeatedly (on reconnect, on report, on
     *  startup); server acks are idempotent. */
    private void drainPendingOtaAcks() {
        executor.submit(() -> {
            java.util.List<JSONObject> snapshot;
            synchronized (pendingOtaAcks) {
                if (pendingOtaAcks.isEmpty()) return;
                snapshot = new java.util.ArrayList<>(pendingOtaAcks.values());
            }
            for (JSONObject a : snapshot) {
                boolean ok = apiService.postOtaStatusOk(
                        a.optString("serial_number"), a.optString("command_id"),
                        a.optString("status"), a.has("error_code") ? a.optString("error_code") : null);
                if (ok) {
                    synchronized (pendingOtaAcks) { pendingOtaAcks.remove(a.optString("command_id")); persistPendingOtaAcks(); }
                }
            }
        });
    }

    private void reportLogcat(String requestId, String content) {
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "logcat_result");
                m.put("request_id", requestId);
                m.put("content", content);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS logcat failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.postLogcat(getDeviceSerial(), requestId, content));
    }

    /**
     * Runs a continuous `logcat` for a live-tail session and streams batched chunks
     * over the WebSocket as {type:logcat_stream, request_id, chunk} frames, ending
     * with a {type:logcat_stream_end} frame. The process is killed when the matching
     * stop_logcat_stream arrives (it removes the entry from logcatStreams) or the WS
     * drops. Filters: buffer (main/system/crash/radio/events/all), level (V..E),
     * tag (exact), grep (logcat -e regex), tail (initial backlog lines).
     */
    private void runLogcatStream(String reqId, JSONObject opts) throws Exception {
        if (reqId == null || reqId.isEmpty()) return;
        if (logcatStreams.size() >= MAX_LOGCAT_STREAMS) {
            sendLogcatStreamEnd(reqId, "error", "too many active logcat streams");
            return;
        }

        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("logcat");
        args.add("-v"); args.add("threadtime");
        String buffer = opts.optString("buffer", "").trim();
        if (!buffer.isEmpty() && !buffer.equals("default") && !buffer.equals("main")) {
            args.add("-b"); args.add(buffer);  // "all" and named buffers; "main" is the default
        }
        int tail = opts.optInt("tail", 200);
        if (tail < 0) tail = 0;
        if (tail > 5000) tail = 5000;
        args.add("-T"); args.add(String.valueOf(tail));
        String grep = opts.optString("grep", "").trim();
        if (!grep.isEmpty()) { args.add("-e"); args.add(grep); }

        String level = opts.optString("level", "V").trim();
        if (level.isEmpty()) level = "V";
        String tag = opts.optString("tag", "").trim();
        if (!tag.isEmpty()) { args.add(tag + ":" + level); args.add("*:S"); }
        else { args.add("*:" + level); }

        java.lang.Process p;
        try {
            p = new ProcessBuilder(args).redirectErrorStream(true).start();
        } catch (Exception e) {
            sendLogcatStreamEnd(reqId, "error", "failed to start logcat: " + e.getMessage());
            return;
        }
        logcatStreams.put(reqId, p);
        Log.i(TAG, "logcat stream " + reqId + " started: " + args);

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 16384)) {
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder();
            long lastFlush = System.currentTimeMillis();
            int n;
            // Batch output and flush every ~250ms (or when the buffer grows) so a chatty
            // log doesn't flood the WebSocket with a frame per line.
            while (logcatStreams.containsKey(reqId)
                    && wsClient != null && wsClient.isConnected()
                    && (n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
                long now = System.currentTimeMillis();
                if (sb.length() >= 12000 || now - lastFlush >= 250) {
                    sendLogcatChunk(reqId, sb.toString());
                    sb.setLength(0);
                    lastFlush = now;
                }
            }
            if (sb.length() > 0) sendLogcatChunk(reqId, sb.toString());
        } finally {
            logcatStreams.remove(reqId);
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            sendLogcatStreamEnd(reqId, "stopped", "");
            Log.i(TAG, "logcat stream " + reqId + " ended");
        }
    }

    private void sendLogcatChunk(String reqId, String chunk) {
        if (wsClient == null || !wsClient.isConnected() || chunk == null || chunk.isEmpty()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "logcat_stream");
            m.put("request_id", reqId);
            m.put("chunk", chunk);
            wsClient.send(m.toString());
        } catch (Exception e) {
            Log.e(TAG, "logcat chunk send failed: " + e.getMessage());
        }
    }

    private void sendLogcatStreamEnd(String reqId, String reason, String error) {
        if (wsClient == null || !wsClient.isConnected()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "logcat_stream_end");
            m.put("request_id", reqId);
            m.put("reason", reason);
            if (error != null && !error.isEmpty()) m.put("error", error);
            wsClient.send(m.toString());
        } catch (Exception ignored) {}
    }

    /**
     * One interactive shell for as long as the dashboard console stays open: a single
     * `sh -i` whose stdin is fed by shell_input frames and whose output (stdout and
     * stderr together) goes back as {type:shell_data, request_id, chunk} frames, ending
     * with {type:shell_end}. State — the working directory, variables, a program waiting
     * for input — carries from one line to the next, unlike the per-command `sh -c`.
     * It runs with this app's privileges, the same as the shell command.
     */
    private void runShellSession(String sid) {
        if (sid == null || sid.isEmpty() || shellSessions.containsKey(sid)) return;
        if (shellSessions.size() >= MAX_SHELL_SESSIONS) {
            sendShellEnd(sid, "error", "too many open shell sessions on this device");
            return;
        }
        java.lang.Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-i").redirectErrorStream(true);
            pb.environment().put("TERM", "dumb");
            pb.environment().put("PS1", "$ ");
            pb.directory(new java.io.File("/"));
            p = pb.start();
        } catch (Exception e) {
            sendShellEnd(sid, "error", "could not start a shell: " + e.getMessage());
            return;
        }
        shellSessions.put(sid, p);
        shellLastInput.put(sid, System.currentTimeMillis());
        Log.i(TAG, "shell session " + sid + " opened");
        final java.lang.Process proc = p;
        // Watchdog: a read blocks until the shell prints, so idleness and a dropped
        // socket are noticed here instead.
        Thread dog = new Thread(() -> {
            while (shellSessions.get(sid) == proc) {
                try { Thread.sleep(30_000); } catch (InterruptedException ie) { return; }
                Long last = shellLastInput.get(sid);
                boolean idle = last != null && System.currentTimeMillis() - last > SHELL_IDLE_MS;
                boolean gone = wsClient == null || !wsClient.isConnected();
                if (idle || gone) {
                    if (idle) sendShellChunk(sid, "\n[closed after 30 minutes without input]\n");
                    closeShellSession(sid);
                    return;
                }
            }
        }, "shell-dog-" + sid);
        dog.setDaemon(true);
        dog.start();
        String reason = "exited";
        try (java.io.InputStreamReader rd = new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder();
            long lastFlush = System.currentTimeMillis();
            int n;
            while ((n = rd.read(buf)) != -1) {
                sb.append(buf, 0, n);
                long now = System.currentTimeMillis();
                // Flush small bursts quickly (a prompt, an echo) but batch a flood.
                if (sb.length() >= 12000 || now - lastFlush >= 60 || !rd.ready()) {
                    sendShellChunk(sid, sb.toString());
                    sb.setLength(0);
                    lastFlush = now;
                }
            }
            if (sb.length() > 0) sendShellChunk(sid, sb.toString());
        } catch (Exception e) {
            reason = shellSessions.containsKey(sid) ? "error" : "closed";
        } finally {
            boolean wasOpen = shellSessions.remove(sid) != null;
            shellLastInput.remove(sid);
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            int code = -1;
            try { code = p.exitValue(); } catch (Exception ignored) {}
            if (!wasOpen) reason = "closed";
            sendShellEnd(sid, reason, "exited".equals(reason) ? "exit " + code : "");
            Log.i(TAG, "shell session " + sid + " ended (" + reason + ")");
        }
    }

    private void writeShellInput(String sid, String data) {
        java.lang.Process p = shellSessions.get(sid);
        if (p == null || data == null) return;
        shellLastInput.put(sid, System.currentTimeMillis());
        try {
            java.io.OutputStream os = p.getOutputStream();
            os.write(data.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            sendShellChunk(sid, "\n[input failed: " + e.getMessage() + "]\n");
        }
    }

    private void closeShellSession(String sid) {
        java.lang.Process p = shellSessions.remove(sid);
        shellLastInput.remove(sid);
        if (p != null) {
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        }
    }

    private void sendShellChunk(String sid, String chunk) {
        if (wsClient == null || !wsClient.isConnected() || chunk == null || chunk.isEmpty()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "shell_data");
            m.put("request_id", sid);
            m.put("chunk", chunk);
            wsClient.send(m.toString());
        } catch (Exception e) {
            Log.e(TAG, "shell chunk send failed: " + e.getMessage());
        }
    }

    private void sendShellEnd(String sid, String reason, String error) {
        if (wsClient == null || !wsClient.isConnected()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "shell_end");
            m.put("request_id", sid);
            m.put("reason", reason);
            if (error != null && !error.isEmpty()) m.put("error", error);
            wsClient.send(m.toString());
        } catch (Exception ignored) {}
    }

    /** Closes every shell session — called when the service stops. */
    private void stopAllShellSessions() {
        for (String sid : new java.util.ArrayList<>(shellSessions.keySet())) closeShellSession(sid);
    }

    /** Kills every active logcat stream — called when the device WS drops or the service stops. */
    private void stopAllLogcatStreams() {
        for (java.util.Map.Entry<String, java.lang.Process> e : logcatStreams.entrySet()) {
            try { e.getValue().destroyForcibly(); } catch (Exception ignored) {}
        }
        logcatStreams.clear();
    }

    /** Reports a terminal OTA status ("downloaded"/"installed"/"error"). Tries WS first for
     *  speed, but — unlike the old fire-and-forget version — always also queues a
     *  persisted, retried HTTP ack (drainPendingOtaAcks), so a report that lands on a dead
     *  server (WS send "succeeds" into a half-open socket, or the HTTP fallback's single
     *  attempt fails) isn't lost forever. Confirmed live: a stage outage stranded
     *  AT070AABU00226/122/387 mid-deployment because the old version had no retry. */
    private void reportOtaStatus(String cmdId, String status, String errorCode) {
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "ota_status");
                m.put("command_id", cmdId);
                m.put("status", status);
                if (errorCode != null && !errorCode.isEmpty()) m.put("error_code", errorCode);
                wsClient.send(m.toString());
            } catch (Exception e) {
                Log.e(TAG, "WS ota_status failed: " + e.getMessage());
            }
        }
        try {
            JSONObject a = new JSONObject();
            a.put("command_id", cmdId);
            a.put("serial_number", getDeviceSerial());
            a.put("status", status);
            if (errorCode != null && !errorCode.isEmpty()) a.put("error_code", errorCode);
            synchronized (pendingOtaAcks) { pendingOtaAcks.put(cmdId, a); persistPendingOtaAcks(); }
        } catch (Exception e) { Log.e(TAG, "reportOtaStatus: " + e.getMessage()); }
        drainPendingOtaAcks();
    }

    /** Relay interim progress to the server so the dashboard shows it live: 'downloading'
     *  with a percent or 'installing' for an install, 'running' for a command that has
     *  started and reports nothing until it finishes. WS-first, HTTP fallback; percent
     *  < 0 omits the number. Non-terminal — the terminal ack still follows. */
    private void reportProgress(String cmdId, String serial, String status, int percent) {
        if (cmdId == null || cmdId.isEmpty()) return;
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "command_ack");
                m.put("command_id", cmdId);
                m.put("status", status);
                if (percent >= 0) m.put("progress", percent);
                wsClient.send(m.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "WS install progress failed, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.reportCommandProgress(cmdId, serial, status, percent));
    }

    /** Sends a real-time OTA progress frame over the WebSocket, falling back to HTTP
     *  when the socket is down or the send fails — WS-only used to mean a flappy
     *  connection silently dropped every progress report, leaving the dashboard
     *  percent stuck even though the device was actively downloading the whole time. */
    private void sendOtaProgressFrame(String cmdId, String phase, int percent) {
        sendOtaProgressFrame(cmdId, phase, percent, 0L, 0L);
    }

    private void sendOtaProgressFrame(String cmdId, String phase, int percent, long done, long total) {
        if (cmdId == null) return;
        if (wsClient != null && wsClient.isConnected()) {
            try {
                JSONObject frame = new JSONObject();
                frame.put("type", "ota_progress");
                frame.put("command_id", cmdId);
                frame.put("phase", phase);
                frame.put("percent", percent);
                if (done > 0) frame.put("bytes", done);
                if (total > 0) frame.put("bytes_total", total);
                wsClient.send(frame.toString());
                return;
            } catch (Exception e) {
                Log.e(TAG, "Failed to send OTA progress frame, falling back to HTTP: " + e.getMessage());
            }
        }
        executor.submit(() -> apiService.postOtaProgress(getDeviceSerial(), cmdId, phase, percent, done, total));
    }

    /** "Updating your device… 412 MB of 1.6 GB" where the figures are known, else a
     *  percent. The same reasoning as the dashboard's: a percent that moves once per
     *  twenty megabytes looks like nothing is happening. */
    private static String humanMB(long n) {
        if (n < (1L << 30)) return ((n + (1L << 19)) >> 20) + " MB";
        return String.format(java.util.Locale.US, "%.1f GB", n / (double) (1L << 30));
    }

    /** OTA listener that reports against the current otaCommandId, so a duplicate command
     *  can re-point status to the new id without restarting the in-flight update. */
    private OtaUpdateManager.Listener buildOtaListener() {
        return new OtaUpdateManager.Listener() {
            @Override public void onDownloadProgress(String phase, int percent) {
                setOtaStatus("Updating · " + percent + " %", null);
                sendOtaProgressFrame(otaCommandId, phase, percent);
            }
            @Override public void onDownloadProgress(String phase, int percent, long done, long total) {
                setOtaStatus("Updating · " + percent + " %",
                        done > 0 && total > 0 ? humanMB(done) + " of " + humanMB(total) : null);
                sendOtaProgressFrame(otaCommandId, phase, percent, done, total);
            }
            @Override public void onDownloadComplete() {
                setOtaStatus("Installing the update", "Takes a few minutes");
                reportOtaStatus(otaCommandId, "downloaded", null);
            }
            @Override public void onInstallComplete() {
                setOtaStatus("Update ready", "Restarts to finish");
                reportOtaStatus(otaCommandId, "installed", null);
                otaCommandId = null;
            }
            @Override public void onErrorFor(String commandId, String errorCode) {
                // An attempt that ended after its successor started: report it against
                // its own command, and leave the running one alone.
                String id = (commandId == null || commandId.isEmpty()) ? otaCommandId : commandId;
                Log.w(TAG, "OTA " + errorCode + " on command " + id);
                reportOtaStatus(id, "error", errorCode);
                if (id != null && id.equals(otaCommandId)) {
                    setOtaError();
                    otaCommandId = null;
                }
            }

            @Override public void onError(String errorCode) {
                // Keep the user-facing text reassuring; the raw code goes to the log + server.
                // Failed updates wait for an operator's Retry on the dashboard; they
                // are not re-offered on the next check-in any more.
                setOtaError();
                Log.w(TAG, "OTA error: " + errorCode);
                reportOtaStatus(otaCommandId, "error", errorCode);
                otaCommandId = null;
            }
        };
    }

    private void sendTelemetryOverWs() {
        if (wsClient == null || !wsClient.isConnected()) return;
        executor.submit(() -> {
            try {
                JSONObject curExtra = buildExtra();
                int curBattery = extractBatteryPct(getBatteryIntent());
                boolean keyframe = forceKeyframe;
                if (!keyframe) {
                    synchronized (baselineLock) {
                        if (lastSentBattery == curBattery && !gatedChanged(curExtra) && !tempTrigger(curExtra)) {
                            return; // nothing changed — skip the send entirely
                        }
                    }
                }
                JSONObject payload = keyframe
                        ? buildCheckinPayload()
                        : buildDeltaPayload(curExtra, curBattery);
                payload.put("type", "telemetry");
                wsClient.send(payload.toString());
                rememberSent(curExtra, curBattery);
            } catch (Exception e) {
                forceKeyframe = true; // next send re-establishes the full baseline
                Log.e(TAG, "WS telemetry failed, falling back to HTTP: " + e.getMessage());
                try {
                    JSONObject payload = buildCheckinPayload();
                    JSONObject response = apiService.checkin(payload);
                    if (response != null) {
                        // Battery-less products (kiosks) omit battery_pct from the payload — use
                        // optInt(-1) to match extractBatteryPct's no-battery value and avoid a
                        // JSONException ("No value for battery_pct") after an otherwise-OK check-in.
                        rememberSent(payload.getJSONObject("extra"), payload.optInt("battery_pct", -1));
                        JSONObject config = response.optJSONObject("config");
                        if (config != null) applyConfig(config);
                        runHttpCommands(response);
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "HTTP telemetry fallback error: " + e2.getMessage());
                }
            }
        });
    }

    private void applyConfig(JSONObject config) {
        // Apply the check-in interval first and independently: a failure applying
        // kiosk policy (DPM/lock-task can throw) must never stop the device from
        // adopting the configured poll interval, or it stays on the 30s default.
        long secs = config.optLong("checkin_interval_seconds", 0);
        if (secs >= 10) {
            long ms = secs * 1000L;
            if (apiService.getPollInterval() != ms) {
                apiService.setPollInterval(ms);
                Log.i(TAG, "Applied checkin interval: " + secs + "s");
                scheduleNextPoll(); // re-arm the alarm immediately at the new cadence
            }
        }
        applyTempFast(config.optInt("temp_fast_sec", 0));
        // Which app to report on (HostApp). Only the check-in/telemetry answer carries it, so a
        // config without the key (a dashboard push) leaves the stored pattern alone.
        if (config.has("host_app_pattern")) {
            HostApp.savePattern(MdmService.this, config.optString("host_app_pattern", ""));
        }
        // Lost-device reporting (1.13.0): which lost beacons to listen for nearby, and whether
        // this device locks itself when it leaves its venue. Absent means off for both.
        try {
            BleLost.saveListen(MdmService.this, config);
            SelfLock.saveConfig(MdmService.this, config);
            // The lost screen's venue and icon (1.13.4) ride in self_lock.
            VenueIcon.note(MdmService.this, config.optJSONObject("self_lock"));
            syncVenueIcon();
        } catch (Exception e) {
            Log.e(TAG, "lost reporting config error: " + e.getMessage());
        }
        // Guest info for the home and lock screens (HTTP check-ins carry it; over the socket
        // it comes as its own "guest" frame). A config without it leaves what we have.
        try {
            GuestInfo.save(MdmService.this, config.optJSONObject("guest"));
        } catch (Exception e) {
            Log.e(TAG, "guest info save error: " + e.getMessage());
        }
        // Offline kiosk-exit policy (the exit PIN) is provisioned via config;
        // store it before applying kiosk policy so a suspended device is respected.
        try {
            KioskExit.savePolicy(MdmService.this, config.optJSONObject("offline_exit"));
            // Server acknowledged our reported exit → it has flipped kiosk off on its side.
            // Stop resending and drop the local "exited" guard; the config in this same
            // response now says kiosk-off, so the device settles cleanly.
            long ack = config.optLong("offline_exit_ack", 0);
            if (ack > 0 && ack == KioskExit.pendingEventAt(MdmService.this)) {
                KioskExit.clearPendingEvent(MdmService.this);
                KioskExit.setSuspended(MdmService.this, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "offline-exit savePolicy error: " + e.getMessage());
        }
        // WLC charging enable/disable. Persist so it survives a reboot (the sysfs line
        // resets to on), and only touch the hardware on products that have a pad. Config
        // arrives on every check-in and telemetry frame, and several frames can interleave
        // around a toggle — writing the gpio every time made it visibly flap. Write only
        // when the target actually changes from what we last applied.
        if (config.has("wlc_charging_enabled")) {
            boolean wlc = config.optBoolean("wlc_charging_enabled", true);
            getSharedPreferences(PREFS_WLC, MODE_PRIVATE).edit()
                    .putBoolean(KEY_WLC_CHARGING, wlc).apply();
            if (product.hasWlc() && (wlcLastApplied == null || wlcLastApplied.booleanValue() != wlc)) {
                if (setWlcCharging(wlc)) wlcLastApplied = Boolean.valueOf(wlc);
            }
        }
        // Screen brightness from the dashboard (1.14.0): applied when the value changes, held
        // while locked. A config without the keys (older server) leaves it alone.
        try {
            ScreenBrightness.saveConfig(MdmService.this, config);
        } catch (Exception e) {
            Log.e(TAG, "brightness config error: " + e.getMessage());
        }
        // Apply kiosk policy only when it actually changed. Config rides every check-in
        // and telemetry frame (many per second when the charger flaps), and re-applying an
        // unchanged policy churned lock-task/DPM state and forced HOME each time. The kiosk
        // watchdog (enforceKioskLock) still re-asserts a dropped lock independently.
        try {
            String kioskSig = config.optBoolean("kiosk_enabled", false) + "|" + config.optString("kiosk_package", "");
            if (!kioskSig.equals(kioskLastSig)) {
                KioskManager.applyAndSave(MdmService.this, dpm, adminComponent, config);
                // The lock is on again server-side (the dashboard re-locked it, or took up a
                // "Back to guest mode" from here): staff mode is over.
                if (config.optBoolean("kiosk_enabled", false) && !KioskExit.isSuspended(MdmService.this)) {
                    KioskExit.endStaffMode(MdmService.this);
                    KioskExit.clearPendingRelock(MdmService.this);
                }
                updateStaffPill();
                kioskLastSig = kioskSig;
            }
        } catch (Exception e) {
            Log.e(TAG, "kiosk applyAndSave error: " + e.getMessage());
        }
    }

    /** Starts, retimes or stops the fast-temperature tick (see tempFastTick). */
    private void applyTempFast(int sec) {
        if (sec > 0 && !firmwareSmoothsTemp()) sec = 0;
        if (sec > 0 && sec < 2) sec = 2;
        if (sec == tempFastSec) return;
        tempFastSec = sec;
        tempFastHandler.removeCallbacks(tempFastTick);
        if (sec > 0) tempFastHandler.postDelayed(tempFastTick, sec * 1000L);
        Log.i(TAG, sec > 0 ? "Fast temperature on: every " + sec + "s" : "Fast temperature off");
    }

    /** Firmware v2.1.023+ (qcom line), whose healthd keeps a 30 s mean of the battery NTC. */
    static boolean firmwareSmoothsTemp(String buildId) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^v(\\d+)\\.(\\d+)\\.(\\d+)").matcher(buildId == null ? "" : buildId);
        if (!m.find()) return false;
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
        int patch = Integer.parseInt(m.group(3));
        if (major != 2) return major > 2;
        if (minor != 1) return minor > 1;
        return patch >= 23;
    }

    private boolean firmwareSmoothsTemp() {
        return firmwareSmoothsTemp(currentBuildId());
    }

    /**
     * Register this device's own key, once, right after a check-in: the server accepts it
     * only from the address it just heard from, with the boot id and build it was just
     * told. 409 means "not yet" (retry after the next check-in); 403/404 mean this server
     * doesn't take keys from this device, so try again much later.
     */
    private void maybeRegisterDeviceKey() {
        DeviceKey k = deviceKey;
        if (k == null || k.active() != null) return;
        long now = System.currentTimeMillis();
        if (now < nextKeyAttemptAt) return;
        String key = k.pendingOrCreate();
        int code = apiService.registerDeviceKey(getDeviceSerial(), DeviceKey.sha256Hex(key), getBootId(), currentBuildId());
        if (code == 200) {
            k.promote();
            Log.i(TAG, "Registered this device's own key; the shared key is no longer accepted for it");
            apiService.keyChanged();
            return;
        }
        nextKeyAttemptAt = now + ((code == 403 || code == 404) ? 6 * 3600_000L : 10 * 60_000L);
    }

    /**
     * Out of contact (no socket, and no network or the last check-in failed): keep a
     * reading every few minutes, to send when the MDM can be reached again.
     */
    private void maybeQueueOffline() {
        if (offlineQueue == null) return;
        if (wsClient != null && wsClient.isConnected()) return;
        if (networkAvailable && serverReachable) return;
        long now = System.currentTimeMillis();
        if (now - lastQueuedAt < OFFLINE_SAMPLE_MS) return;
        lastQueuedAt = now;
        executor.submit(() -> {
            try {
                offlineQueue.add(offlineReading());
            } catch (Exception e) {
                Log.w(TAG, "could not keep an offline reading: " + e.getMessage());
            }
        });
    }

    /** The 8 strongest APs of the last kept offline reading, by BSSID (Wi-Fi diary, 1.13.0). */
    private java.util.Set<String> lastQueuedBssids = new java.util.HashSet<>();

    /**
     * The 8 strongest APs of the latest scan, when they are not the set the previous kept
     * reading carried — a device moving while out of reach leaves a trail the server can place
     * on a map later; one sitting offline on its dock adds nothing. Null when unchanged.
     */
    private JSONArray offlineWifiDiary() {
        JSONArray scan = cachedWifiScan;
        if (scan == null || scan.length() == 0) return null;
        List<JSONObject> aps = new ArrayList<>();
        for (int i = 0; i < scan.length(); i++) {
            JSONObject ap = scan.optJSONObject(i);
            if (ap != null && !ap.optString("bssid", "").isEmpty()) aps.add(ap);
        }
        Collections.sort(aps, (a, b) -> Integer.compare(b.optInt("rssi", -127), a.optInt("rssi", -127)));
        JSONArray top = new JSONArray();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JSONObject ap : aps) {
            if (top.length() >= 8) break;
            top.put(ap);
            ids.add(ap.optString("bssid"));
        }
        if (ids.equals(lastQueuedBssids)) return null;
        lastQueuedBssids = ids;
        return top;
    }

    /** The fields the server keeps as history, with the times it needs to place them. */
    private JSONObject offlineReading() throws JSONException {
        JSONObject p = buildCheckinPayload();
        JSONObject extra = p.optJSONObject("extra");
        JSONObject r = new JSONObject();
        r.put("at_ms", System.currentTimeMillis());
        JSONArray diary = offlineWifiDiary();
        if (diary != null) r.put("wifi_scan", diary);
        r.put("elapsed_ms", android.os.SystemClock.elapsedRealtime());
        r.put("boot_id", getBootId());
        int bat = p.optInt("battery_pct", -1);
        if (bat >= 0) r.put("battery_pct", bat);
        if (extra != null) {
            if (extra.has("battery_temp_c") && !extra.isNull("battery_temp_c")) r.put("temp_c", extra.optDouble("battery_temp_c"));
            if (extra.has("wifi_rssi") && !extra.isNull("wifi_rssi")) r.put("wifi_rssi", extra.optInt("wifi_rssi"));
            if (extra.has("storage_free_gb") && !extra.isNull("storage_free_gb")) r.put("storage_free_gb", extra.optDouble("storage_free_gb"));
            JSONObject ram = extra.optJSONObject("ram_usage_mb");
            if (ram != null && ram.has("used") && ram.has("total")) {
                r.put("ram_used_mb", ram.optInt("used"));
                r.put("ram_total_mb", ram.optInt("total"));
            }
            // 1.7.1: the states the charge strip draws, exactly as a check-in reports them.
            if (extra.has("charging")) r.put("charging", extra.opt("charging"));
            if (extra.has("wlc_status")) r.put("wlc_status", extra.opt("wlc_status"));
        }
        // 1.7.1: crashes since the previous kept reading. A live check-in only looks back
        // an hour, so a longer outage lost everything before that; each kept reading takes
        // what happened since the last one. Traces are cut to 8 KB so a crashing device
        // can't fill the queue; the server dedupes a crash also reported live.
        JSONArray crashes = new JSONArray();
        long newest = lastQueuedCrashMs;
        JSONArray recent = getRecentCrashEvents();
        for (int i = 0; i < recent.length(); i++) {
            JSONObject c = recent.optJSONObject(i);
            if (c == null) continue;
            long t = c.optLong("time_ms", 0);
            if (t <= lastQueuedCrashMs) continue;
            String trace = c.optString("trace", "");
            if (trace.length() > 8192) c.put("trace", trace.substring(0, 8192));
            crashes.put(c);
            if (t > newest) newest = t;
        }
        lastQueuedCrashMs = newest;
        if (crashes.length() > 0) r.put("crashes", crashes);
        return r;
    }

    /** Send the kept readings, oldest first, deleting each batch the server answers for. */
    private void flushOfflineQueue() {
        if (offlineQueue == null || !flushingOffline.compareAndSet(false, true)) return;
        executor.submit(() -> {
            try {
                while (true) {
                    List<String> batch = offlineQueue.peek(200);
                    if (batch.isEmpty()) break;
                    JSONObject body = new JSONObject();
                    body.put("serial", getDeviceSerial());
                    body.put("now_ms", System.currentTimeMillis());
                    body.put("now_elapsed_ms", android.os.SystemClock.elapsedRealtime());
                    body.put("boot_id", getBootId());
                    body.put("dropped", offlineQueue.dropped());
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (String line : batch) arr.put(new JSONObject(line));
                    body.put("readings", arr);
                    int code = apiService.postBackfill(body);
                    if (code != 200) {
                        Log.w(TAG, "offline readings not sent (" + code + "); " + offlineQueue.size() + " kept");
                        break;
                    }
                    offlineQueue.remove(batch.size());
                    offlineQueue.clearDropped();
                    Log.i(TAG, "sent " + batch.size() + " reading(s) kept while offline");
                }
            } catch (Exception e) {
                Log.w(TAG, "sending offline readings failed: " + e.getMessage());
            } finally {
                flushingOffline.set(false);
            }
        });
    }

    // Battery log upload: at most one attempt per BATTERY_UPLOAD_EVERY_MS, and a server that
    // does not have the endpoint yet (404/405/501) is asked again only after
    // BATTERY_UPLOAD_MISSING_MS. Rows stay on the device meanwhile (BatteryLog keeps 21 days).
    // No answer at all (-1) is retried after BATTERY_UPLOAD_NO_NET_MS (1.10.2): the first
    // attempt runs on the boot-time battery broadcast, before Wi-Fi has DNS, and used to
    // wait the full 15 minutes for nothing.
    private static final long BATTERY_UPLOAD_EVERY_MS = 15 * 60_000L;
    private static final long BATTERY_UPLOAD_MISSING_MS = 6 * 3_600_000L;
    private static final long BATTERY_UPLOAD_NO_NET_MS = 60_000L;
    private static final int BATTERY_UPLOAD_ROWS = 2000;
    private final java.util.concurrent.atomic.AtomicBoolean uploadingBatteryLog =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long batteryUploadNotBefore = 0;   // elapsedRealtime

    /** Send the battery rows not yet accepted, oldest first (see BatteryLog). */
    private void flushBatteryLog() {
        BatteryLog log = batteryLog;
        if (log == null || SystemClock.elapsedRealtime() < batteryUploadNotBefore
                || !uploadingBatteryLog.compareAndSet(false, true)) return;
        executor.submit(() -> {
            long wait = BATTERY_UPLOAD_EVERY_MS;
            try {
                BatteryLog.Batch batch;
                while ((batch = log.peek(BATTERY_UPLOAD_ROWS)) != null) {
                    JSONObject body = new JSONObject();
                    body.put("serial", getDeviceSerial());
                    body.put("schema", BatteryLog.SCHEMA);
                    body.put("columns", BatteryLog.HEADER);
                    body.put("now_ms", System.currentTimeMillis());
                    body.put("now_elapsed_ms", SystemClock.elapsedRealtime());
                    body.put("boot_id", getBootId());
                    body.put("rows", batch.rows);
                    int code = apiService.postBatteryLog(body);
                    if (code == 404 || code == 405 || code == 501) {
                        Log.w(TAG, "battery log not taken (" + code + "); kept, next try in "
                                + (BATTERY_UPLOAD_MISSING_MS / 3_600_000L) + " h");
                        wait = BATTERY_UPLOAD_MISSING_MS;
                        break;
                    }
                    if (code != 200 && code != 204) {
                        Log.w(TAG, "battery log not sent (" + code + ")");
                        if (code == -1) wait = BATTERY_UPLOAD_NO_NET_MS;
                        break;
                    }
                    log.ack(batch);
                    Log.i(TAG, "sent " + batch.count + " battery row(s)");
                    if (batch.count < BATTERY_UPLOAD_ROWS) break;
                }
            } catch (Exception e) {
                Log.w(TAG, "sending battery log failed: " + e.getMessage());
            } finally {
                batteryUploadNotBefore = SystemClock.elapsedRealtime() + wait;
                uploadingBatteryLog.set(false);
            }
        });
    }

    /** The key in use changed: reconnect the socket with it. */
    private synchronized void restartWebSocket() {
        if (wsClient != null) {
            wsClient.stop();
            wsClient = null;
        }
        startWebSocket();
    }

    private synchronized void startWebSocket() {
        if (wsClient != null) return;
        String serial = getDeviceSerial();
        wsClient = new MdmWebSocketClient(apiService.getApiBaseUrl(), serial, apiService.getApiKey());
        wsClient.setListener(this::handleWsMessage);
        // On every (re)connect the server has no baseline for us — send a full keyframe.
        wsClient.setConnectedCallback(() -> { forceKeyframe = true; sendTelemetryOverWs(); drainPendingAcks(); serverReachable = true; refreshStatus(); flushOfflineQueue(); flushBatteryLog(); });
        wsClient.start();
        Log.i(TAG, "WebSocket client started");
    }

    private void handleWsMessage(JSONObject msg) {
        String type = msg.optString("type", "");
        switch (type) {
            case "command":
                handleIncomingCommand(msg);
                break;
            case "logcat_request":
                heavyExecutor.submit(() -> {
                    try { processWsLogcatRequest(msg); } catch (Exception e) {
                        Log.e(TAG, "WS logcat error: " + e.getMessage());
                    }
                });
                break;
            case "telemetry_request":
                sendTelemetryOverWs();
                break;
            case "ping_request":
                executor.submit(() -> {
                    try {
                        JSONObject pong = new JSONObject();
                        pong.put("type", "pong_response");
                        pong.put("nonce", msg.optString("nonce", ""));
                        wsClient.send(pong.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "ping_request handler error: " + e.getMessage());
                    }
                });
                break;
            case "config":
                executor.submit(() -> {
                    try { applyConfig(msg); } catch (Exception e) {
                        Log.e(TAG, "WS config error: " + e.getMessage());
                    }
                });
                break;
            case "guest":
                // Venue, table and guest Wi-Fi: sent on connect and whenever they change.
                // Its own frame, not a "config" — a config frame is applied whole.
                executor.submit(() -> {
                    try { GuestInfo.save(MdmService.this, msg.optJSONObject("guest")); } catch (Exception e) {
                        Log.e(TAG, "WS guest error: " + e.getMessage());
                    }
                });
                break;
            case "input_event":
                executor.submit(() -> {
                    try { handleInputEvent(msg); } catch (Exception e) {
                        Log.e(TAG, "input_event error: " + e.getMessage());
                    }
                });
                break;
            case "clipboard_get":
                // The remote page's "Copy from device" (client 1.16.0, RemoteInput).
                executor.submit(() -> {
                    try {
                        JSONObject out = new JSONObject();
                        out.put("type", "remote_clipboard");
                        out.put("text", RemoteInput.clipboardText(MdmService.this));
                        wsClient.send(out.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "clipboard_get error: " + e.getMessage());
                    }
                });
                break;
            case "start_capture":
                heavyExecutor.submit(() -> {
                    // Clamp server-supplied capture params so an out-of-range value can't peg CPU
                    // or flood the WS (a bad/hostile server shouldn't be able to melt the device).
                    String codec = "h264".equals(msg.optString("codec", "jpeg")) ? "h264" : "jpeg";
                    int quality = Math.max(1, Math.min(100, msg.optInt("quality", 60)));
                    double scale = Math.max(0.1, Math.min(1.0, msg.optDouble("scale", 0.5)));
                    int maxFps = Math.max(1, Math.min(30, msg.optInt("max_fps", 10)));
                    int bitrate = Math.max(250000, Math.min(20000000, msg.optInt("bitrate", 4000000)));
                    if (!isCapturing) {
                        isCapturing = true;
                        acquireRemoteWakeLock();
                        runCaptureLoop(codec, quality, scale, maxFps, bitrate);
                    }
                });
                break;
            case "stop_capture":
                isCapturing = false;
                releaseRemoteWakeLock();
                break;
            case "adb_tunnel_open": {
                // An admin's `adb connect` reached the server: carry adbd (127.0.0.1:port)
                // to it over a second WebSocket. See AdbTunnel and internal/adbtunnel.
                AdbTunnel.open(apiService.getApiBaseUrl(), getDeviceSerial(), apiService.getApiKey(),
                        msg.optString("session", ""), msg.optString("stream", ""), msg.optInt("port", 5555));
                break;
            }
            case "adb_tunnel_close":
                AdbTunnel.closeSession(msg.optString("session", ""));
                break;
            case "net_scan": {
                // Scout: sweep this device's Wi-Fi for devices that trust the fleet adb key
                // and report what each one is. See NetScout and the sightings table.
                final JSONObject scanMsg = msg;
                heavyExecutor.submit(() -> {
                    try { NetScout.scan(MdmService.this, scanMsg, this::sendScoutFrame); }
                    catch (Exception e) { Log.e(TAG, "net_scan error: " + e.getMessage()); }
                });
                break;
            }
            case "net_scan_cancel":
                NetScout.cancelScan(msg.optString("session", ""));
                break;
            case "net_enroll": {
                // Scout: install the standard client on one device on the Wi-Fi and make it
                // Device Owner, with the one-shot token the server minted.
                final JSONObject enrollMsg = msg;
                heavyExecutor.submit(() -> {
                    try { NetScout.enroll(MdmService.this, enrollMsg, this::sendScoutFrame); }
                    catch (Exception e) { Log.e(TAG, "net_enroll error: " + e.getMessage()); }
                });
                break;
            }
            case "start_logcat_stream": {
                final JSONObject lcOpts = msg;
                final String lcReq = msg.optString("request_id", "");
                heavyExecutor.submit(() -> {
                    try {
                        runLogcatStream(lcReq, lcOpts);
                    } catch (Exception e) {
                        Log.e(TAG, "logcat stream error: " + e.getMessage());
                        sendLogcatStreamEnd(lcReq, "error", e.getMessage());
                    }
                });
                break;
            }
            case "shell_open": {
                final String shSid = msg.optString("session_id", "");
                // Its own thread: a session lives as long as the console is open, and must
                // not hold one of heavyExecutor's core threads away from installs and logcat.
                Thread shT = new Thread(() -> {
                    try { runShellSession(shSid); } catch (Exception e) {
                        Log.e(TAG, "shell session error: " + e.getMessage());
                        sendShellEnd(shSid, "error", e.getMessage());
                    }
                }, "shell-" + shSid);
                shT.setDaemon(true);
                shT.start();
                break;
            }
            case "shell_input": {
                final String inSid = msg.optString("session_id", "");
                final String inData = msg.optString("data", "");
                executor.submit(() -> writeShellInput(inSid, inData));
                break;
            }
            case "shell_close":
                closeShellSession(msg.optString("session_id", ""));
                break;
            case "stop_logcat_stream": {
                java.lang.Process lp = logcatStreams.remove(msg.optString("request_id", ""));
                if (lp != null) lp.destroyForcibly();
                break;
            }
            case "cancel_command": {
                // Operator deleted/cancelled the action. Flag the id so an in-flight
                // download aborts (checked every read chunk in installApkInner).
                String cid = msg.optString("id", "");
                if (!cid.isEmpty()) {
                    cancelledCommands.add(cid);
                    Log.i(TAG, "Cancel requested for command " + cid);
                    // Bound the set so broadcast cancels for commands we never ran
                    // can't accumulate forever.
                    if (cancelledCommands.size() > 128) cancelledCommands.clear();
                }
                // OTA: only abort while still downloading — the download's own byte-loop
                // doesn't consult cancelledCommands (unlike installApkInner), so it needs
                // an explicit cancel() here. Never touch an install already in progress:
                // update_engine is mid-write to the inactive slot at that point, and
                // aborting it risks an unbootable slot rather than just a wasted download.
                if (!cid.isEmpty() && cid.equals(otaCommandId) && otaUpdateManager != null
                        && otaUpdateManager.isActive()
                        && "downloading".equals(otaUpdateManager.getCurrentPhase())) {
                    Log.i(TAG, "Cancelling in-progress OTA download for command " + cid);
                    otaUpdateManager.cancel();
                    reportOtaStatus(cid, "error", "CANCELLED");
                    otaCommandId = null;
                    // The status notification was last set by onDownloadProgress
                    // ("Updating · N %"); nothing else resets it after a
                    // cancel, so the device kept showing a stale percent.
                    clearOtaStatus();
                }
                break;
            }
            case "wake":
                // "There is work, come and get it" — no payload, so losing this frame costs
                // at most one poll interval instead of losing a command. The server sends
                // it instead of the command itself to clients that can fetch their own
                // work, which is what makes a half-open socket harmless.
                Log.i(TAG, "Wake from server (" + msg.optString("reason", "") + ") — polling for commands");
                pollCommandsNow();
                break;
            case "checkin_now":
                // Server nudge (e.g. an OTA was just assigned): do a full HTTP check-in
                // immediately so the update resolves now instead of on the next periodic
                // poll. performCheckin() self-guards against a concurrent poll.
                executor.submit(() -> {
                    try { performCheckin(); } catch (Exception e) {
                        Log.e(TAG, "checkin_now error: " + e.getMessage());
                    }
                });
                break;
            case "wake_screen":
                wakeScreen(null);
                break;
            default:
                Log.w(TAG, "Unknown WS message type: " + type);
        }
    }

    private void processWsCommand(JSONObject cmd) throws Exception {
        String cmdId = cmd.getString("id");
        String cmdType = cmd.optString("command_type", "install_apk");
        JSONObject payload = cmd.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        String serialNumber = getDeviceSerial();

        Log.i(TAG, "Processing WS command " + cmdId + " type=" + cmdType);
        switch (cmdType) {
            case "install_apk": {
                String[] pkgHolder = new String[1];
                // apk_size / apk_etag are populated server-side (best-effort HEAD of the
                // APK URL) so the download can verify completeness and resume safely.
                long apkSize = payload.optLong("apk_size", -1);
                String apkEtag = payload.optString("apk_etag", null);
                try {
                    String err = installApk(cmd.getString("apk_url"), cmdId, serialNumber, pkgHolder,
                            apkSize, apkEtag);
                    if (cancelledCommands.contains(cmdId)) {
                        reportTerminal(cmdId, serialNumber, "cancelled", "cancelled by operator", pkgHolder[0]);
                    } else {
                        // HTTP (not WS): a long install can leave the WS half-open, silently
                        // dropping a WS ack and making the install show 'failed' though it
                        // succeeded.
                        reportTerminal(cmdId, serialNumber, err.isEmpty() ? "installed" : "failed", err, pkgHolder[0]);
                    }
                } finally {
                    cancelledCommands.remove(cmdId);
                }
                break;
            }
            case "app_update": {
                // Agent OTA: a newer build of this client. The server names the APK and its
                // digest; everything else is checked on the device. On success this process
                // is replaced mid-install, so the only ack from here is a failure — the new
                // version settles the command at startup.
                String url = cmd.optString("apk_url", payload.optString("apk_url", ""));
                if (url.isEmpty()) {
                    reportTerminal(cmdId, serialNumber, "failed", "missing apk_url");
                    break;
                }
                String err = installApk(url, cmdId, serialNumber, new String[1],
                        payload.optLong("apk_size", -1), payload.optString("apk_etag", null),
                        true, payload.optString("sha256", ""));
                if (!err.isEmpty()) {
                    ClientUpdater.clearPending(this);
                    // Already on this build (or newer): the update has nothing to do. That is
                    // the operator aiming an update at a device that does not need it, not a
                    // device failing — settle it as completed so the command list says so.
                    reportTerminal(cmdId, serialNumber,
                            ClientUpdater.notNewer(err) ? "completed" : "failed", err);
                }
                break;
            }
            case "uninstall": {
                String pkg = payload.optString("package", "");
                if (pkg.isEmpty()) {
                    reportTerminal(cmdId, serialNumber, "failed", "missing package");
                    break;
                }
                String err = uninstallPackage(pkg);
                reportTerminal(cmdId, serialNumber, err == null ? "completed" : "failed",
                        err == null ? ("uninstalled " + pkg) : err);
                break;
            }
            case "shell": {
                String shellCmd = payload.optString("cmd", "");
                if (shellCmd.isEmpty()) {
                    reportTerminal(cmdId, serialNumber, "failed", "empty cmd");
                    break;
                }
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});
                // A shell reports nothing until it exits, and it may take up to the 30s cap
                // below — say it started, so the command list shows "running" instead of
                // leaving an operator looking at a command that appears not to have landed.
                // Terminal ack still follows; this is interim only (see reportProgress).
                reportProgress(cmdId, serialNumber, "running", -1);
                // Drain stderr in a side thread; cap at 10 MB to prevent OOM
                ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
                Thread stderrThread = new Thread(() -> {
                    try (InputStream es = p.getErrorStream()) {
                        byte[] buf = new byte[8192];
                        int n, total = 0;
                        while ((n = es.read(buf)) != -1) {
                            if (total < 10 * 1024 * 1024) { stderrBuf.write(buf, 0, n); total += n; }
                            // keep draining even past cap so process doesn't stall on full pipe
                        }
                    } catch (Exception ignored) {}
                });
                stderrThread.start();
                // Stream stdout chunks via WebSocket so the browser sees them immediately.
                // `collected` only backs the HTTP ack, so cap it (the WS stream is unbounded).
                final int MAX_COLLECTED = 1024 * 1024;
                StringBuilder collected = new StringBuilder();
                try (InputStream is = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        if (collected.length() < MAX_COLLECTED) collected.append(chunk);
                        JSONObject outFrame = new JSONObject();
                        outFrame.put("type", "command_output");
                        outFrame.put("command_id", cmdId);
                        outFrame.put("chunk", chunk);
                        wsClient.send(outFrame.toString());
                    }
                }
                boolean finished = p.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                }
                stderrThread.join(5_000);
                int exitCode = finished ? p.exitValue() : -1;
                // Signal stream end so the browser SSE closes
                JSONObject doneFrame = new JSONObject();
                doneFrame.put("type", "command_done");
                doneFrame.put("command_id", cmdId);
                doneFrame.put("exit_code", exitCode);
                wsClient.send(doneFrame.toString());
                // HTTP-ack to persist status in DB
                String output = collected.length() > 0 ? collected.toString()
                        : stderrBuf.toString(StandardCharsets.UTF_8);
                reportTerminal(cmdId, serialNumber, exitCode == 0 ? "completed" : "failed", output);
                break;
            }
            case "screenshot": {
                File tmp = new File(getCacheDir(), "mdm_screen_" + System.currentTimeMillis() + ".png");
                try {
                    java.lang.Process p = Runtime.getRuntime().exec(
                            new String[]{"screencap", "-p", tmp.getAbsolutePath()});
                    p.waitFor();
                    // Stream-encode to base64 in 8 KB chunks — raw PNG bytes never fully in heap
                    java.io.ByteArrayOutputStream b64Buf = new java.io.ByteArrayOutputStream();
                    try (FileInputStream fis = new FileInputStream(tmp);
                         OutputStream enc = java.util.Base64.getEncoder().wrap(b64Buf)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) != -1) enc.write(buf, 0, n);
                    }
                    reportTerminal(cmdId, serialNumber, "completed",
                            b64Buf.toString(StandardCharsets.UTF_8.name()));
                } finally {
                    tmp.delete();
                }
                break;
            }
            // get_app_inventory was removed: nothing has created one for a long time (no
            // server-side creator anywhere), and the inventory it returned now rides every
            // check-in as installed_apps. A command type with no sender is worse than
            // absent — it reads as a feature and hides that the check-in path superseded it.
            case "mic_gain_set": {
                // Admin sets (or clears with value=null/"") the TX_DEC enforcement target.
                // Only works on firmware with the vendor daemon; reports why otherwise.
                Integer target = null;
                if (payload.has("value") && !payload.isNull("value")) {
                    String v = String.valueOf(payload.opt("value")).trim();
                    if (!v.isEmpty()) {
                        try { target = Integer.valueOf(v); }
                        catch (NumberFormatException e) { reportTerminal(cmdId, serialNumber, "failed", "invalid value: " + v); break; }
                    }
                }
                String err = MicGain.setTarget(MdmService.this, target, 3000);
                if (err != null) { reportTerminal(cmdId, serialNumber, "failed", err); break; }
                JSONObject mg = MicGain.snapshot();
                reportTerminal(cmdId, serialNumber, "completed", mg != null ? mg.toString() : "");
                break;
            }
            case "mic_gain_read": {
                // Fresh TX_DEC gain snapshot on demand (same shape as the checkin field).
                // On firmware with the vendor probe daemon, re-probe the mixer first so the
                // reading is current rather than up to one daemon period old.
                MicGain.refreshLive(3000);
                JSONObject mg = MicGain.snapshot();
                if (mg == null) reportTerminal(cmdId, serialNumber, "failed", "mic gain unavailable: no mixer_paths xml on this device");
                else reportTerminal(cmdId, serialNumber, "completed", mg.toString());
                break;
            }
            case "adb_tcp": {
                // Wireless adb on (port) or off (0). We may only set persist.sys.*; the
                // firmware's aio-adb-tcp.rc copies it to service.adb.tcp.port and restarts
                // adbd, the same thing `adb tcpip` does. Persistent, so it survives the
                // reboot an OTA test needs. Read back to report whether it took.
                int port = payload.optInt("port", 5555);
                if (port != 0 && (port < 1024 || port > 65535)) {
                    reportTerminal(cmdId, serialNumber, "failed", "invalid port: " + port);
                    break;
                }
                // Switches itself off after `hours` (default 24) unless hours=0 keeps it on.
                int hours = Math.max(0, Math.min(24 * 30, payload.optInt("hours", 24)));
                long until = port > 0 && hours > 0 ? System.currentTimeMillis() / 1000 + hours * 3600L : 0;
                SystemPropertiesProxy.set(ADB_TCP_UNTIL_PROP, String.valueOf(until));
                if (port > 0) {
                    // adbd is only kept running while USB debugging is on.
                    Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                }
                if (!SystemPropertiesProxy.set(ADB_TCP_PROP, String.valueOf(port))) {
                    reportTerminal(cmdId, serialNumber, "failed", "could not set " + ADB_TCP_PROP);
                    break;
                }
                String applied = "";
                for (int i = 0; i < 20; i++) {  // init applies it within a few hundred ms
                    applied = SystemPropertiesProxy.get("service.adb.tcp.port", "");
                    if (String.valueOf(port).equals(applied)) break;
                    SystemClock.sleep(100);
                }
                if (!String.valueOf(port).equals(applied)) {
                    reportTerminal(cmdId, serialNumber, "failed",
                            "firmware did not apply it (service.adb.tcp.port=" + applied
                            + "): this image has no aio-adb-tcp.rc — needs v2.1.022+");
                    break;
                }
                String ip = cachedWifiExtra != null ? cachedWifiExtra.optString("ip_address", "") : "";
                reportTerminal(cmdId, serialNumber, "completed", port == 0
                        ? "wireless adb off"
                        : "wireless adb on " + (ip.isEmpty() || "null".equals(ip) ? "" : ip) + ":" + port
                          + (until > 0 ? ", off after " + hours + " h" : ", stays on"));
                break;
            }
            case "lost_mode": {
                // Lost screen on or off (LostMode). Off restores the lock the device should
                // have, from the saved kiosk config the server keeps current.
                boolean on = payload.optBoolean("on", false);
                if (KioskManager.isFactoryTestBoot()) {
                    reportTerminal(cmdId, serialNumber, "failed", "factory test boot");
                    break;
                }
                if (on) {
                    VenueIcon.note(this, payload);
                    LostMode.turnOn(this, dpm, adminComponent, payload.optString("message", ""),
                            payload.optString("phone", ""), serialNumber,
                            payload.optString("beacon", ""), false);
                    syncVenueIcon();
                } else {
                    LostMode.lift(this, dpm, adminComponent);
                }
                // The beacon follows lost mode at once rather than at the next tick.
                if (bleExecutor != null) bleExecutor.execute(() -> BleLost.syncBeacon(this));
                forceKeyframe = true;
                reportTerminal(cmdId, serialNumber, "completed",
                        new JSONObject().put("lost_mode", on).toString());
                sendTelemetryOverWs();
                break;
            }
            case "pull_apk": {
                // Save to library (1.21.0): upload an installed app's APK (ApkPuller). Runs
                // on this heavy thread for as long as the upload takes; repeats of the same
                // command are already dropped by handleIncomingCommand. A progress ack every
                // 10 s whatever the upload does, so the server sees it is alive.
                final String pkg = payload.optString("package", "");
                final java.util.concurrent.atomic.AtomicInteger pct = new java.util.concurrent.atomic.AtomicInteger(0);
                final String cid = cmdId, sn = serialNumber;
                reportProgress(cid, sn, "running", 0);
                java.util.concurrent.ScheduledExecutorService beat =
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                beat.scheduleAtFixedRate(() -> reportProgress(cid, sn, "running", pct.get()),
                        10, 10, TimeUnit.SECONDS);
                ApkPuller.Result res;
                try {
                    final int[] last = {-1};
                    res = ApkPuller.pull(this, apiService, sn, cid, pkg, p -> {
                        pct.set(p);
                        // Every 5 %: enough to move the dashboard's bar without flooding the socket.
                        if (p - last[0] >= 5 || p == 100) { last[0] = p; reportProgress(cid, sn, "running", p); }
                    });
                } finally {
                    beat.shutdownNow();
                }
                Log.i(TAG, "pull_apk " + pkg + ": " + (res.ok ? "ok" : "failed") + " — " + res.message);
                reportTerminal(cid, sn, res.ok ? "completed" : "failed", res.message);
                break;
            }
            case "wifi_config": {
                if ("export".equals(payload.optString("action", ""))) {
                    // Saved networks with passwords, sealed to a key off the server (1.20.0).
                    try {
                        JSONObject res = WifiConfig.export(this, serialNumber);
                        Log.i(TAG, "wifi export: " + res.optInt("count") + " network(s)");
                        reportTerminal(cmdId, serialNumber, "completed", res.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "wifi export: " + e);
                        reportTerminal(cmdId, serialNumber, "failed",
                                new JSONObject().put("error", String.valueOf(e.getMessage())).toString());
                    }
                    break;
                }
                if ("connect".equals(payload.optString("action", ""))) {
                    // Switch networks, reversibly (WifiConfig.connect, 1.18.0): up to 90 s on
                    // its own thread; the result is acked once the device is back online.
                    final String cid = cmdId, sn = serialNumber, target = payload.optString("ssid", "");
                    heavyExecutor.submit(() -> {
                        try {
                            JSONObject res = WifiConfig.connect(MdmService.this, target, () -> {
                                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                                return apiService.fetchBytes("/health", b) == 200;
                            });
                            forceKeyframe = true;
                            reportTerminal(cid, sn, res.optBoolean("ok", false) ? "completed" : "failed", res.toString());
                            sendTelemetryOverWs();
                        } catch (Exception e) {
                            Log.e(TAG, "wifi switch: " + e.getMessage());
                        }
                    });
                    break;
                }
                // Add/update or forget a saved Wi-Fi network (WifiConfig). Never touches the
                // network the device is on, so a venue can add its new network first.
                String err = WifiConfig.apply(this, payload);
                String ssid = payload.optString("ssid", "");
                String action = payload.optString("action", "");
                if (err == null) {
                    forceKeyframe = true;
                    reportTerminal(cmdId, serialNumber, "completed", new JSONObject()
                            .put("ok", true).put("ssid", ssid).put("action", action).toString());
                    sendTelemetryOverWs();
                } else {
                    reportTerminal(cmdId, serialNumber, "failed",
                            new JSONObject().put("error", err).put("ssid", ssid).toString());
                }
                break;
            }
            case "reboot": {
                // Server marks reboot commands completed at delivery — no ack needed
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                pm.reboot(null);
                break;
            }
            case "wipe": {
                // Factory reset. Ack first: nothing survives the wipe to report afterwards, and
                // the server takes "completed" as its cue to forget this device's own key so the
                // wiped device can register a fresh one. The wipe follows a few seconds later
                // whether or not the ack got through.
                reportTerminal(cmdId, serialNumber, "completed", "factory reset starting");
                final String wipeReason = "MDM wipe (command " + cmdId + ")";
                Thread t = new Thread(() -> {
                    try { Thread.sleep(5_000); } catch (InterruptedException ignored) { }
                    factoryReset(wipeReason);
                }, "mdm-wipe");
                t.setDaemon(true);
                t.start();
                break;
            }
            case "ota": {
                final String updateUrl = payload.optString("update_url", "");
                final String otaCmdId = cmdId;
                // Route subsequent OTA status/progress to the latest command id.
                otaCommandId = otaCmdId;
                if (otaUpdateManager == null) {
                    otaUpdateManager = new OtaUpdateManager(this, heavyExecutor);
                    otaUpdateManager.setListener(buildOtaListener());
                }
                // Idempotent delivery: a duplicate "ota" command (e.g. a redeploy) must NOT
                // restart an update that's already applied or in flight — restarting aborts
                // update_engine and re-downloads from 0%. Only start fresh for a new package.
                if (otaUpdateManager.isUpdatePendingReboot()) {
                    // Already applied; the device just needs to reboot. Leave update_engine alone.
                    Log.i(TAG, "OTA already applied, pending reboot — acking cmd " + otaCmdId);
                    reportOtaStatus(otaCmdId, "installed", null);
                    break;
                }
                if (otaUpdateManager.isActive() && updateUrl.equals(otaUpdateManager.getCurrentUrl())) {
                    // Same package still downloading/installing: keep going, just surface the
                    // current progress against the new command id.
                    Log.i(TAG, "OTA already in progress for same URL — not restarting (cmd " + otaCmdId + ")");
                    sendOtaProgressFrame(otaCmdId, otaUpdateManager.getCurrentPhase(),
                            otaUpdateManager.getCurrentPercent());
                    break;
                }
                // New or different package: cancel anything stale and start fresh.
                otaUpdateManager.cancel();
                otaUpdateManager.startUpdate(updateUrl, otaCmdId);
                break;
            }
            case "update_splash": {
                // Replace the boot logo. The image is staged + flashed by an
                // init broker (the app can't write the block device); we only
                // transport the file and trigger. Runs on the worker thread.
                String splashUrl = payload.optString("url", "");
                if (splashUrl.isEmpty()) {
                    reportTerminal(cmdId, serialNumber, "failed", "missing url");
                    break;
                }
                long partitionSize = payload.optLong("partition_size", 0);
                String status = downloadAndUpdateSplash(splashUrl, partitionSize);
                boolean ok = "ok".equals(status);
                reportTerminal(cmdId, serialNumber, ok ? "completed" : "failed", status);
                break;
            }
            case "start_capture": {
                // Clamp server-supplied capture params (see the WS start_capture handler).
                String codec = "h264".equals(payload.optString("codec", "jpeg")) ? "h264" : "jpeg";
                int quality = Math.max(1, Math.min(100, payload.optInt("quality", 60)));
                double scale = Math.max(0.1, Math.min(1.0, payload.optDouble("scale", 0.5)));
                int maxFps = Math.max(1, Math.min(30, payload.optInt("max_fps", 10)));
                int bitrate = Math.max(250000, Math.min(20000000, payload.optInt("bitrate", 4000000)));
                if (!isCapturing) {
                    isCapturing = true;
                    acquireRemoteWakeLock();
                    heavyExecutor.submit(() -> runCaptureLoop(codec, quality, scale, maxFps, bitrate));
                }
                reportTerminal(cmdId, serialNumber, "completed", "");
                break;
            }
            case "stop_capture": {
                isCapturing = false;
                releaseRemoteWakeLock();
                reportTerminal(cmdId, serialNumber, "completed", "");
                break;
            }
            default:
                Log.w(TAG, "Unknown command type: " + cmdType);
                reportTerminal(cmdId, serialNumber, "failed", "unknown type: " + cmdType);
        }
    }


    private void runCaptureLoop(String codec, int quality, double scale, int maxFps, int bitrate) {
        // getDisplay() throws/returns null on a Service context on some Android
        // versions; fall back to the DisplayManager's default display.
        android.view.Display display = null;
        try {
            display = getDisplay();
        } catch (Throwable t) {
            Log.w(TAG, "getDisplay() failed on service context: " + t);
        }
        if (display == null) {
            try {
                android.hardware.display.DisplayManager dm =
                        (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            } catch (Throwable t) {
                Log.w(TAG, "DisplayManager fallback failed: " + t);
            }
        }
        if (display == null) {
            Log.e(TAG, "Cannot get display for capture — remote control will show no frames");
            isCapturing = false;
            return;
        }
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        int scaledW = (int) (size.x * scale);
        int scaledH = (int) (size.y * scale);
        int rotation = display.getRotation();

        // Tier 3: hardware H.264 when the operator opted in (?codec=h264). Runs until the
        // session stops; falls back to the JPEG still path if the encoder or auto-mirror
        // display can't be set up on this device.
        if ("h264".equals(codec)) {
            boolean ran = runH264Loop(scaledW, scaledH, maxFps, bitrate);
            if (ran) {
                isCapturing = false;
                releaseRemoteWakeLock();
                Log.i(TAG, "Capture loop stopped");
                return;
            }
            Log.w(TAG, "H.264 unavailable — falling back to JPEG stills");
        }

        long targetMs = 1000 / maxFps;
        Log.i(TAG, "Capture loop started — scaled=" + scaledW + "x" + scaledH
                + " quality=" + quality + " fps=" + maxFps + " codec=jpeg");

        // Decouple the blocking WS send from capture+encode: a dedicated sender drains this
        // queue so the next frame is captured while the previous one uploads. Bounded and
        // drop-oldest so a slow link sheds frames instead of piling up latency.
        final java.util.concurrent.ArrayBlockingQueue<byte[]> sendQ =
                new java.util.concurrent.ArrayBlockingQueue<>(2);
        Thread sender = new Thread(() -> {
            try {
                while (isCapturing || !sendQ.isEmpty()) {
                    byte[] f = sendQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (f != null && wsClient != null && wsClient.isConnected()) {
                        try {
                            wsClient.sendBinary(f);
                        } catch (java.io.IOException io) {
                            Log.w(TAG, "Frame send failed: " + io.getMessage());
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "mdm-frame-sender");
        sender.start();

        long sent = 0, nullFrames = 0, lastLog = System.currentTimeMillis();
        while (isCapturing && wsClient != null && wsClient.isConnected()) {
            long start = System.currentTimeMillis();
            try {
                android.graphics.Bitmap screen = captureScreen(scaledW, scaledH, rotation);
                if (screen == null) {
                    nullFrames++;
                    if (start - lastLog >= 5000) {
                        Log.w(TAG, "Capture: 0 frames sent, " + nullFrames + " null captures in last 5s — screen capture is failing");
                        nullFrames = 0; lastLog = start;
                    }
                    Thread.sleep(targetMs);
                    continue;
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(32768);
                // JPEG software-encodes several times faster than WEBP at similar size/quality —
                // the main lever moving the still path from ~3 fps toward the target.
                screen.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos);
                screen.recycle();

                byte[] frame = bos.toByteArray();
                if (!sendQ.offer(frame)) { sendQ.poll(); sendQ.offer(frame); } // drop oldest under back-pressure
                sent++;
                if (start - lastLog >= 5000) {
                    Log.i(TAG, "Capture: " + sent + " frames sent in last 5s (" + frame.length + "B last)");
                    sent = 0; nullFrames = 0; lastLog = start;
                }

                long elapsed = System.currentTimeMillis() - start;
                if (elapsed < targetMs) {
                    Thread.sleep(targetMs - elapsed);
                }
            } catch (Exception e) {
                Log.e(TAG, "Capture loop error: " + e.getMessage());
                if (wsClient == null || !wsClient.isConnected()) break;
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
        isCapturing = false;
        sender.interrupt();
        releaseRemoteWakeLock();
        Log.i(TAG, "Capture loop stopped");
    }

    /**
     * Tier 3 hardware path: mirror the screen into a MediaCodec H.264 encoder via an
     * auto-mirror VirtualDisplay and stream Annex-B NAL units. Each WS message is
     * [1-byte type][payload]: type 1 = key frame (SPS/PPS prepended), 0 = delta — the
     * browser decodes this with WebCodecs. Returns true if it ran (setup succeeded),
     * false if the encoder/display couldn't be set up so the caller can fall back to
     * JPEG stills. Blocks until the session stops. Needs CAPTURE_VIDEO_OUTPUT (held as
     * a privileged platform app).
     */
    private boolean runH264Loop(int w, int h, int fps, int bitrate) {
        w = w & ~1; h = h & ~1; // H.264 wants even dimensions
        android.media.MediaCodec mc = null;
        android.hardware.display.VirtualDisplay vd = null;
        android.view.Surface surface = null;
        Thread sender = null;
        final java.util.concurrent.ArrayBlockingQueue<byte[]> sendQ =
                new java.util.concurrent.ArrayBlockingQueue<>(3);
        try {
            android.media.MediaFormat fmt = android.media.MediaFormat.createVideoFormat("video/avc", w, h);
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1s key frames
            // Baseline profile matches the browser's avc1.42E01E decoder config.
            fmt.setInteger(android.media.MediaFormat.KEY_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            // Low-latency tuning — minimise tap-to-screen delay for remote control:
            //  - realtime priority so the encoder is scheduled ahead of background work;
            //  - CBR so a static screen doesn't build a VBR buffer that delays motion;
            //  - no B-frames (baseline has none, but be explicit) so no reorder delay;
            //  - encode latency of 1 frame so the encoder never holds a frame back;
            //  - cap the input rate to the target fps (fixed fps) so we don't burn CPU
            //    encoding redundant frames on a busy screen;
            //  - repeat the last frame after one frame-interval so the stream keeps a
            //    steady cadence and the decoder always holds the freshest content even
            //    when the screen is idle. Unsupported keys are ignored by the encoder.
            fmt.setInteger(android.media.MediaFormat.KEY_BITRATE_MODE,
                    android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            fmt.setInteger(android.media.MediaFormat.KEY_PRIORITY, 0); // 0 = realtime
            fmt.setInteger(android.media.MediaFormat.KEY_MAX_B_FRAMES, 0);
            fmt.setInteger(android.media.MediaFormat.KEY_LATENCY, 1);
            fmt.setFloat(android.media.MediaFormat.KEY_MAX_FPS_TO_ENCODER, (float) fps);
            fmt.setLong(android.media.MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000L / fps);
            mc = android.media.MediaCodec.createEncoderByType("video/avc");
            mc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = mc.createInputSurface();
            mc.start();

            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            // SECURE (CAPTURE_SECURE_VIDEO_OUTPUT): windows marked secure, like Google's sign-in
            // pages during setup, are mirrored too instead of showing black.
            int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
            vd = dm.createVirtualDisplay("mdm-remote", w, h, dpi, surface, flags);
            if (vd == null) throw new IllegalStateException("createVirtualDisplay returned null");

            final android.media.MediaCodec fmc = mc;
            sender = new Thread(() -> {
                try {
                    while (isCapturing || !sendQ.isEmpty()) {
                        byte[] f = sendQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (f != null && wsClient != null && wsClient.isConnected()) {
                            try {
                                wsClient.sendBinary(f);
                            } catch (java.io.IOException io) {
                                Log.w(TAG, "Frame send failed: " + io.getMessage());
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }, "mdm-frame-sender-h264");
            sender.start();

            Log.i(TAG, "H.264 capture started — " + w + "x" + h + " fps=" + fps + " bitrate=" + bitrate);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            byte[] csd = null; // SPS/PPS, prepended to each key frame
            long sent = 0, lastLog = System.currentTimeMillis();
            while (isCapturing && wsClient != null && wsClient.isConnected()) {
                int idx = mc.dequeueOutputBuffer(info, 100000); // 100ms
                if (idx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat of = mc.getOutputFormat();
                    java.nio.ByteBuffer sps = of.getByteBuffer("csd-0");
                    java.nio.ByteBuffer pps = of.getByteBuffer("csd-1");
                    if (sps != null && pps != null) {
                        byte[] s = new byte[sps.remaining()]; sps.get(s);
                        byte[] p = new byte[pps.remaining()]; pps.get(p);
                        csd = new byte[s.length + p.length];
                        System.arraycopy(s, 0, csd, 0, s.length);
                        System.arraycopy(p, 0, csd, s.length, p.length);
                    }
                    continue;
                }
                if (idx < 0) continue; // INFO_TRY_AGAIN_LATER etc.
                java.nio.ByteBuffer buf = mc.getOutputBuffer(idx);
                if (buf == null) { mc.releaseOutputBuffer(idx, false); continue; }
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                buf.get(data);
                mc.releaseOutputBuffer(idx, false);

                boolean isConfig = (info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKey = (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (isConfig) { csd = data; continue; } // SPS/PPS as a config buffer
                byte[] payload = data;
                if (isKey && csd != null) {
                    payload = new byte[csd.length + data.length];
                    System.arraycopy(csd, 0, payload, 0, csd.length);
                    System.arraycopy(data, 0, payload, csd.length, data.length);
                }
                byte[] framed = new byte[payload.length + 1];
                framed[0] = (byte) (isKey ? 1 : 0);
                System.arraycopy(payload, 0, framed, 1, payload.length);
                if (!sendQ.offer(framed)) { sendQ.poll(); sendQ.offer(framed); } // drop oldest
                sent++;
                if (System.currentTimeMillis() - lastLog >= 5000) {
                    Log.i(TAG, "H.264: " + sent + " frames in last 5s (" + framed.length + "B last)");
                    sent = 0; lastLog = System.currentTimeMillis();
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "H.264 capture unavailable: " + t);
            return false;
        } finally {
            try { if (vd != null) vd.release(); } catch (Exception ignored) {}
            try { if (mc != null) { mc.stop(); mc.release(); } } catch (Exception ignored) {}
            try { if (surface != null) surface.release(); } catch (Exception ignored) {}
            if (sender != null) sender.interrupt();
        }
    }

    /**
     * Turns the display on and holds it bright for the duration of a remote-control
     * session. Called at session start so the operator never connects to a dark or
     * sleeping screen. Safe to call repeatedly — re-arms the safety timeout.
     */
    private void acquireRemoteWakeLock() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeScreen(pm);
            if (remoteWakeLock == null) {
                remoteWakeLock = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "MDM:RemoteControl");
                remoteWakeLock.setReferenceCounted(false);
            }
            remoteWakeLock.acquire(REMOTE_WAKE_TIMEOUT_MS);
            Log.i(TAG, "Remote wake lock acquired (" + REMOTE_WAKE_TIMEOUT_MS + "ms)");
        } catch (Exception e) {
            Log.e(TAG, "acquireRemoteWakeLock failed: " + e.getMessage());
        }
    }

    private void releaseRemoteWakeLock() {
        try {
            if (remoteWakeLock != null && remoteWakeLock.isHeld()) {
                remoteWakeLock.release();
                Log.i(TAG, "Remote wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "releaseRemoteWakeLock failed: " + e.getMessage());
        }
    }

    /** Forces the display on (used at session start and on operator right-click). */
    private void wakeScreen(android.os.PowerManager pm) {
        try {
            if (pm == null) {
                pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            }
            java.lang.reflect.Method wakeUp = android.os.PowerManager.class.getMethod(
                    "wakeUp", long.class);
            wakeUp.invoke(pm, android.os.SystemClock.uptimeMillis());
        } catch (Exception e) {
            // wakeUp() is a hidden API; the ACQUIRE_CAUSES_WAKEUP wake-lock flag is the fallback.
            Log.w(TAG, "PowerManager.wakeUp() unavailable: " + e.getMessage());
        }
    }

    private android.graphics.Bitmap captureScreen(int w, int h, int rotation) {
        java.lang.reflect.Method method = screenshotMethod;
        if (method == null && !screenshotMethodResolved) {
            synchronized (this) {
                if (!screenshotMethodResolved) {
                    try {
                        screenshotMethod = android.view.SurfaceControl.class.getMethod(
                                "screenshot", android.graphics.Rect.class, int.class, int.class, int.class);
                    } catch (Exception e) {
                        Log.w(TAG, "SurfaceControl.screenshot() unavailable: " + e.getMessage());
                    }
                    screenshotMethodResolved = true;
                }
            }
            method = screenshotMethod;
        }
        if (method == null) return captureScreenFallback(w, h);
        try {
            return (android.graphics.Bitmap) method.invoke(null,
                    new android.graphics.Rect(), w, h, rotation);
        } catch (Exception e) {
            Log.w(TAG, "SurfaceControl.screenshot() failed, falling back to screencap: " + e.getMessage());
            return captureScreenFallback(w, h);
        }
    }

    private android.graphics.Bitmap captureScreenFallback(int maxW, int maxH) {
        File tmp = new File(getCacheDir(), "mdm_remote_" + System.currentTimeMillis() + ".png");
        try {
            java.lang.Process p = Runtime.getRuntime().exec(
                    new String[]{"screencap", "-p", tmp.getAbsolutePath()});
            p.waitFor();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 2; // half resolution as fallback
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(
                    tmp.getAbsolutePath(), opts);
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "screencap fallback failed: " + e.getMessage());
            return null;
        } finally {
            tmp.delete();
        }
    }

    private void handleInputEvent(JSONObject msg) throws Exception {
        RemoteInput.handle(this, msg);
    }

    private void processWsLogcatRequest(JSONObject req) throws Exception {
        String requestId = req.getString("id");
        String level = req.optString("level", "V");
        int lines = req.optInt("lines", 500);
        String tag = req.optString("tag", "");

        String[] cmd;
        if (tag.isEmpty()) {
            cmd = new String[]{"logcat", "-t", String.valueOf(lines), "-v", "threadtime", "*:" + level};
        } else {
            cmd = new String[]{"logcat", "-t", String.valueOf(lines), "-v", "threadtime", tag + ":" + level, "*:S"};
        }

        Log.d(TAG, "Logcat cmd: " + java.util.Arrays.toString(cmd));

        final int MAX_LOGCAT_BYTES = 5 * 1024 * 1024;
        java.lang.Process process = Runtime.getRuntime().exec(cmd);
        // Drain stderr in background — prevents the process blocking on a full stderr pipe
        Thread stderrDrain = new Thread(() -> {
            try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
            catch (Exception ignored) {}
        });
        stderrDrain.start();

        StringBuilder sb = new StringBuilder();
        try (InputStream is = process.getInputStream()) {
            byte[] buf = new byte[65536];
            int n, total = 0;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                total += n;
                if (total >= MAX_LOGCAT_BYTES) {
                    sb.append("\n[truncated — exceeded 5 MB limit]");
                    break;
                }
            }
        }
        process.destroy();
        process.waitFor();
        stderrDrain.join();

        String content = sb.toString();
        Log.d(TAG, "Logcat result: " + content.length() + " bytes");
        reportLogcat(requestId, content);
    }

    /**
     * Downloads a splash image and hands it to the broker via {@link SplashUpdater}.
     * Streams straight from the network into staging — a wrong file fails before
     * the whole file is pulled. Returns the broker's final status string (or an
     * {@code error_*} reason on transport failure).
     */
    private String downloadAndUpdateSplash(String imageUrl, long partitionSize) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Splash download failed: HTTP " + conn.getResponseCode());
                return "error_download";
            }
            try (InputStream in = conn.getInputStream()) {
                return SplashUpdater.updateSplash(in, partitionSize);
            }
        } catch (Exception e) {
            Log.e(TAG, "Splash update error: " + e.getMessage());
            return "error_download";
        }
    }

    /**
     * Downloads and installs an APK. Returns "" on success, otherwise a short error
     * reason that is sent back to the server in the command ack so install failures
     * are diagnosable from the dashboard (previously this returned a bare boolean and
     * acked with an empty output, so a failed install gave no clue why).
     */
    private String installApk(String apkUrl, String cmdId, String serial, String[] outPkg,
                              long expectedSize, String etag) {
        return installApk(apkUrl, cmdId, serial, outPkg, expectedSize, etag, false, null);
    }

    /** [selfUpdate] installs a new build of THIS client: verified against the running one
     *  and written down first, because the install ends this process (see ClientUpdater). */
    private String installApk(String apkUrl, String cmdId, String serial, String[] outPkg,
                              long expectedSize, String etag, boolean selfUpdate, String sha256) {
        // Starts as "an app" and learns the app's label, version and icon once the APK is
        // parsed, so the on-device notification (and its final result) names the app.
        InstallNote note = new InstallNote();
        // Distinct notification per install so concurrent installs don't overwrite each
        // other's progress (they all shared INSTALL_NOTIFICATION_ID, so the notification
        // "kept switching" between apps). Derived from cmdId → stable for this install.
        int notifId = installNotifId(cmdId);
        String err = installApkInner(apkUrl, cmdId, serial, outPkg, expectedSize, etag, note, notifId,
                selfUpdate, sha256);
        // Final result notification (dismissible; not ongoing) — reuses this install's id so
        // it replaces the progress notification in place. Keep it human; the raw error goes
        // to the log + server, not the user.
        if (!err.isEmpty()) Log.w(TAG, "APK install failed: " + err);
        if (err.isEmpty()) {
            // Android's "Updated by your admin" (only when this client is Device Owner) says
            // less than ours does: take it down.
            Notices.dismissAdminNoticeSoon(note.pkg);
            android.graphics.Bitmap icon = Notices.installedIcon(this, note.pkg);
            if (icon != null) note.icon = icon;
            showInstallNotification(notifId, note, "done", null, 100);
        } else if (ClientUpdater.notNewer(err)) {
            // Already on this build (an update that arrived after another one landed it):
            // nothing failed, so take the progress notice down instead of "Couldn't install".
            getSystemService(NotificationManager.class).cancel(notifId);
        } else {
            showInstallNotification(notifId, note, "failed", null, 0);
        }
        return err;
    }

    /** A per-install notification id, distinct from the status (1001) and legacy install
     *  (1002) ids, so several installs running at once each keep their own notification. */
    private int installNotifId(String cmdId) {
        return 2000 + Math.floorMod(cmdId == null ? 0 : cmdId.hashCode(), 100000);
    }

    private String installApkInner(String apkUrl, String cmdId, String serial, String[] outPkg,
                                   long expectedSize, String etag, InstallNote note, int notifId,
                                   boolean selfUpdate, String sha256) {
        File apkFile = new File(getCacheDir(), "mdm_install_" + System.currentTimeMillis() + ".apk");
        try {
            // Download with Range resume + retry. A stale keep-alive socket ("unexpected
            // end of stream") or a transient stall on a large APK ("SocketTimeoutException")
            // no longer fails the whole install — the next attempt resumes from disk and the
            // final size is verified before we hand the file to PackageInstaller.
            // The 15-min retry budget lets a real network disturbance recover and resume
            // instead of hard-failing after a few attempts, matched to the server-side
            // stalled-install sweep so a device that never recovers gives up in step. FW-2026-000020.
            reportProgress(cmdId, serial, "downloading", expectedSize > 0 ? 0 : -1);
            showInstallNotification(notifId, note, "download", "Starting…", expectedSize > 0 ? 0 : -1);
            final int[] lastPct = { -1 };       // notification: fine-grained, every 1%
            final int[] lastReported = { -1 };  // server report: throttled to 5% to avoid spam
            HttpDownloader.downloadWithResume(apkUrl, apkFile, expectedSize, etag,
                    30_000, 60_000, 5, 15 * 60 * 1000L,
                    (downloaded, total) -> {
                        if (total > 0) {
                            int pct = (int) (downloaded * 100 / total);
                            // Update the on-device notification on every 1% step, showing
                            // downloaded / total MB alongside the progress bar.
                            if (pct != lastPct[0] && pct < 100) {
                                lastPct[0] = pct;
                                double tMb = total / 1048576.0;
                                showInstallNotification(notifId, note, "download",
                                        String.format(java.util.Locale.US, "%d %% of %.1f MB", pct, tMb),
                                        pct);
                                // Report to the server less often (every 5%) so a fast link
                                // doesn't flood check-ins.
                                if (pct >= lastReported[0] + 5) {
                                    lastReported[0] = pct;
                                    reportProgress(cmdId, serial, "downloading", pct);
                                }
                            }
                        }
                    },
                    // Abort mid-download if the operator deleted the action (server
                    // pushes a cancel_command frame → cancelledCommands).
                    () -> cancelledCommands.contains(cmdId));
            long written = apkFile.length();
            Log.i(TAG, "APK downloaded to " + apkFile.getAbsolutePath() + " (" + written + " bytes)");
            if (written == 0) return "download failed: empty file";
            // Download done → now installing.
            reportProgress(cmdId, serial, "installing", -1);

            // Extract package name (+ display label) from the APK for fallback verification,
            // a clearer error, and the user-facing notification title.
            String apkPackageName = null;
            android.content.pm.PackageInfo apkInfo = getPackageManager().getPackageArchiveInfo(
                    apkFile.getAbsolutePath(), 0);
            if (apkInfo != null) {
                apkPackageName = apkInfo.packageName;
                if (outPkg != null) outPkg[0] = apkPackageName; // report to server on ack
                // Load the human-readable app label (needs sourceDir set for an uninstalled APK).
                if (apkInfo.applicationInfo != null) {
                    apkInfo.applicationInfo.sourceDir = apkFile.getAbsolutePath();
                    apkInfo.applicationInfo.publicSourceDir = apkFile.getAbsolutePath();
                    CharSequence lbl = apkInfo.applicationInfo.loadLabel(getPackageManager());
                    if (lbl != null && lbl.length() > 0) note.name = lbl.toString();
                    note.icon = Notices.bitmap(apkInfo.applicationInfo.loadIcon(getPackageManager()));
                }
                note.pkg = apkPackageName;
                note.version = Notices.versionOf(apkInfo);
                try {
                    getPackageManager().getPackageInfo(apkPackageName, 0);
                    note.replacing = true;
                } catch (android.content.pm.PackageManager.NameNotFoundException notYet) {
                    note.replacing = false;
                }
                Log.i(TAG, "APK package: " + apkPackageName);
            } else {
                // The downloaded file doesn't parse as an APK — almost always a corrupt or
                // truncated download (a flaky host with no known size to verify against).
                // Fail here with a clear, actionable reason instead of handing a bad file to
                // PackageInstaller, which only reports a generic "status=1". The retry budget
                // will re-download and resume.
                Log.e(TAG, "APK could not be parsed (corrupt/truncated download): " + apkUrl
                        + " (" + written + " bytes)");
                apkFile.delete();
                return "download appears corrupt (unparseable APK, " + written + " bytes) — will retry";
            }
            // A new build of this client: prove it is one before handing it to the installer,
            // and write down the intent — committing the session ends this process, so the
            // ack has to come from the version that replaces us.
            if (selfUpdate) {
                String why = ClientUpdater.verify(this, apkFile, sha256);
                if (why != null) {
                    apkFile.delete();
                    return why;
                }
                ClientUpdater.recordPending(this, cmdId, apkFile);
            }
            showInstallNotification(notifId, note, "install", null, -1);

            // Install via PackageInstaller API
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicReference<String> failReason = new AtomicReference<>("");

            BroadcastReceiver resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        Log.i(TAG, "APK installed successfully");
                        success.set(true);
                    } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Log.e(TAG, "PackageInstaller requires user action — is the client still running as the system uid?");
                        failReason.set("needs user action — client not running as the system uid");
                    } else {
                        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        int legacyStatus = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", -999);
                        Log.e(TAG, "PackageInstaller failed status=" + status + " legacy=" + legacyStatus + " msg=" + msg);
                        failReason.set("install failed: status=" + status + " legacy=" + legacyStatus
                                + (msg != null ? " " + msg : ""));
                    }
                    latch.countDown();
                }
            };

            String action = "com.aioapp.mdm.INSTALL_RESULT_" + System.currentTimeMillis();
            registerReceiver(resultReceiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
            boolean completed;
            // Hold the verifier off for the length of this install only.
            suspendPackageVerifier();
            try {
                int sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream in = new java.io.FileInputStream(apkFile);
                         OutputStream out = session.openWrite("base.apk", 0, apkFile.length())) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(this, sessionId,
                            new Intent(action).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
                    session.commit(pi.getIntentSender());
                }
                completed = latch.await(180, TimeUnit.SECONDS);
            } finally {
                // A successful self-install kills this process before either line runs; the
                // saved value is restored at the next startup instead.
                restorePackageVerifier();
                try { unregisterReceiver(resultReceiver); } catch (Exception ignored) {}
            }

            if (success.get()) return "";

            // Fallback: the package may be present even if the callback reported failure
            // or never fired (USER_ACTION_NOT_REQUIRED installs sometimes don't broadcast).
            //
            // For a SELF-update "is the package present?" is always true — the build asking
            // is that package — so presence alone declared every failed self-install a
            // success: installApk returned "", app_update sent no ack (the replacement build
            // is meant to settle it), no replacement ever started, and the command sat at
            // "installing" forever. Compare version codes instead.
            if (selfUpdate) {
                long installed = ClientUpdater.installedVersionCode(this);
                long wanted = apkInfo != null ? (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? apkInfo.getLongVersionCode() : apkInfo.versionCode) : 0;
                if (wanted > 0 && installed >= wanted) {
                    Log.i(TAG, "self-update landed (running " + installed + ")");
                    return "";
                }
                ClientUpdater.clearPending(this);
                String why = failReason.get();
                if (why.isEmpty()) {
                    why = completed ? "install reported no result"
                                    : "install timed out after 180s (no result)";
                    // A session that never reports anything is almost always parked in the
                    // package verifier (Play Protect on a GMS image): it asks for a verdict
                    // and none comes back. The client cannot disable verification for its own
                    // session — PackageInstallerService only honours that for adb — so say
                    // whether the verifier is enabled, which is the fact needed to act on it.
                    why += verifierState();
                }
                Log.e(TAG, "self-update did not install: " + why + " (still running " + installed + ")");
                return why + " — still running " + installed + ", wanted " + wanted;
            }
            // Same question for an ordinary app install, asked properly: "is the package
            // present?" says nothing when the app was already installed and the update did
            // not take. Only a version code at or above the APK's proves this install landed.
            if (apkPackageName != null && apkInfo != null) {
                long wanted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? apkInfo.getLongVersionCode() : apkInfo.versionCode;
                long installed = -1;
                try {
                    android.content.pm.PackageInfo cur =
                            getPackageManager().getPackageInfo(apkPackageName, 0);
                    installed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? cur.getLongVersionCode() : cur.versionCode;
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                }
                if (installed >= wanted) {
                    Log.i(TAG, "APK package " + apkPackageName + " is at " + installed
                            + " (verified via PackageManager"
                            + (completed ? " after callback reported failure)" : " after timeout)"));
                    return "";
                }
                Log.e(TAG, "APK package " + apkPackageName + " is at " + installed + ", wanted "
                        + wanted + (completed ? " (callback reported failure)"
                                              : " (timed out after 180s)"));
                String why = failReason.get();
                if (!why.isEmpty()) return why;
                return (completed ? "install failed" : "install timed out after 180s")
                        + verifierState() + " — package at " + installed + ", wanted " + wanted;
            }
            if (apkPackageName != null) {
                try {
                    getPackageManager().getPackageInfo(apkPackageName, 0);
                    Log.i(TAG, "APK package " + apkPackageName + " is installed (verified via PackageManager"
                            + (completed ? " after callback reported failure)" : " after timeout)"));
                    return "";
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "APK package " + apkPackageName + " not found after install"
                            + (completed ? " (callback reported failure)" : " (timed out after 180s)"));
                }
            }
            String reason = failReason.get();
            if (!reason.isEmpty()) return reason;
            if (!completed) return "install timed out after 180s (no result; package not present)";
            if (apkPackageName == null) return "could not parse APK (corrupt download?)";
            return "install failed: package not present after commit";
        } catch (Exception e) {
            Log.e(TAG, "installApk error: " + e.getMessage());
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            apkFile.delete();
        }
    }

    /**
     * Silently uninstalls a package via the PackageInstaller API. Running this from the app
     * (calling package = com.aioapp.mdm) avoids the AppOps NullPointerException that "pm
     * uninstall" hits when invoked from the system UID with no calling package. Requires
     * the DELETE_PACKAGES permission (granted to this platform-signed privileged app).
     * Returns null on success, or an error message on failure.
     */
    private String uninstallPackage(String pkg) {
        try {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            final StringBuilder errMsg = new StringBuilder();

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        success.set(true);
                    } else {
                        String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        Log.e(TAG, "Uninstall failed status=" + status + " msg=" + m);
                        errMsg.append(m != null ? m : ("status " + status));
                    }
                    latch.countDown();
                }
            };

            String action = "com.aioapp.mdm.UNINSTALL_RESULT_" + System.currentTimeMillis();
            registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
            try {
                PendingIntent pi = PendingIntent.getBroadcast(this, action.hashCode(),
                        new Intent(action).setPackage(getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                installer.uninstall(pkg, pi.getIntentSender());
                latch.await(60, TimeUnit.SECONDS);
            } finally {
                try { unregisterReceiver(receiver); } catch (Exception ignored) {}
            }

            if (success.get()) return null;

            // Fallback: the callback can be flaky — treat "package is gone" as success.
            try {
                getPackageManager().getPackageInfo(pkg, 0);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return null;
            }
            return errMsg.length() > 0 ? errMsg.toString() : "uninstall failed";
        } catch (Exception e) {
            Log.e(TAG, "uninstallPackage error: " + e.getMessage());
            return e.getMessage() != null ? e.getMessage() : "uninstall error";
        }
    }

    private JSONArray getInstalledApps() {
        JSONArray apps = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> activities = pm.queryIntentActivities(launcherIntent, 0);
            for (android.content.pm.ResolveInfo ri : activities) {
                JSONObject app = new JSONObject();
                String pkg = ri.activityInfo.packageName;
                app.put("package", pkg);
                app.put("name", ri.loadLabel(pm).toString());
                // Flag system apps so the dashboard only offers uninstall for user apps.
                boolean isSystem = false;
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    isSystem = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                            || (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                } catch (Exception ignore) {}
                app.put("is_system", isSystem);
                // Version name for the dashboard's app-info popup. Best-effort.
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    if (pi.versionName != null) app.put("version_name", pi.versionName);
                } catch (Exception ignore) {}
                // Launcher icon as a base64 PNG so the dashboard can render a real
                // app-drawer icon. Best-effort — a failure just omits it (server falls
                // back to a monogram). Sent only with the full app list (on change), so
                // this doesn't ride every check-in.
                try {
                    String icon = drawableToBase64Png(ri.loadIcon(pm), 96);
                    if (icon != null) app.put("icon", icon);
                } catch (Exception ignore) {}
                apps.put(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "getInstalledApps error: " + e.getMessage());
        }
        return apps;
    }

    /** Renders a Drawable to a size×size base64-encoded PNG (NO_WRAP), or null on error. */
    private String drawableToBase64Png(android.graphics.drawable.Drawable d, int size) {
        try {
            if (d == null || size <= 0) return null;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Builds the current full telemetry "extra" — a complete snapshot of every field. */
    /** Wireless adb switched on by "adb_tcp" goes off by itself once its time is up. */
    private void expireAdbTcp() {
        long until;
        try {
            until = Long.parseLong(SystemPropertiesProxy.get(ADB_TCP_UNTIL_PROP, "0"));
        } catch (NumberFormatException e) {
            return;
        }
        if (until <= 0 || System.currentTimeMillis() / 1000 < until) return;
        Log.i(TAG, "wireless adb time is up, turning it off");
        SystemPropertiesProxy.set(ADB_TCP_UNTIL_PROP, "0");
        SystemPropertiesProxy.set(ADB_TCP_PROP, "0");
    }

    private JSONObject buildExtra() throws JSONException {
        Intent batteryIntent = getBatteryIntent();
        JSONObject extra = new JSONObject();
        // What this client is, separately from the OS build it ships in. build_id names
        // the firmware; these name the app, so the server can see a client that is behind
        // and update it on its own — without a firmware OTA.
        extra.put("agent_package", SELF_PACKAGE);
        extra.put("agent_version", clientVersionName());
        extra.put("agent_version_code", clientVersionCode());
        // This client runs commands handed back in a check-in response. The server reads it
        // to decide whether to use the response as a command channel for a device with no
        // socket — the gate follows what the device can do, not a server-wide flag.
        extra.put("http_commands", true);
        // Remote control takes keys with modifiers, text, scroll and the clipboard (1.16.0).
        extra.put("remote_input", RemoteInput.LEVEL);
        // wifi_config "connect" switches networks and goes back if the new one fails (1.18.0).
        extra.put("wifi_connect", true);
        // This client runs interactive shell sessions (shell_open / shell_input / shell_close).
        extra.put("shell_session", true);
        // This client runs the "wipe" command (factory reset). A flag, not extra.capabilities:
        // a capabilities list from this client would replace the server's firmware defaults.
        extra.put("factory_reset", true);
        // This client runs lost_mode and wifi_config (1.12.0+). Flags for the same reason.
        extra.put("lost_mode_cmd", true);
        extra.put("wifi_config_cmd", true);
        // This client runs pull_apk, Save to library (1.21.0). A flag for the same reason.
        extra.put("apk_pull_cmd", true);
        // Lost mode as it stands on the device, and the saved Wi-Fi networks (no passwords).
        extra.put("lost_mode", LostMode.report(MdmService.this));
        // Lost-device reporting over Bluetooth LE (1.13.0): what the hardware can do, the
        // beacon's state while lost, and what this device heard while listening.
        BleLost.putCapabilities(MdmService.this, extra);
        JSONObject beacon = BleLost.beaconReport(MdmService.this);
        if (beacon != null) extra.put("lost_beacon", beacon);
        BleLost.putListenReport(MdmService.this, extra);
        JSONArray savedWifi = WifiConfig.networks(MdmService.this);
        if (savedWifi != null) extra.put("wifi_networks", savedWifi);
        // The Android IDs the Nugget apps see, which only the OS knows (see getNuggetAndroidIds).
        JSONArray nuggetIds = getNuggetAndroidIds();
        if (nuggetIds.length() > 0) extra.put("app_android_ids", nuggetIds);
        extra.put("app_android_ids_readable", ssaidReadable);
        // A device identifying as msm-<ANDROID_ID> because its serial read corrupt says so,
        // with what the serial actually read, so the fleet can list it as such.
        getDeviceSerial();
        if (serialCorrupt) {
            extra.put("serial_corrupt", true);
            extra.put("serial_raw", serialRaw != null ? serialRaw : "");
        }
        // Which platform key signed this image: user builds carry release-keys, userdebug
        // test-keys. An update APK must be signed with the same one, so the server needs
        // this to hand the device the right build.
        extra.put("build_tags", SystemPropertiesProxy.get("ro.build.tags", ""));
        extra.put("build_type", SystemPropertiesProxy.get("ro.build.type", ""));
        // Whether this image can take an MDM OTA at all. The server reads this before it
        // decides between an MDM update and the legacy otautil path, and until now had to
        // guess from build-id strings. A/B is the precondition and the honest test:
        // OtaUpdateManager hands the package to UpdateEngine, which only exists on a
        // seamless-update device. Deliberately a lone flag and not part of a capability
        // list: what a firmware image can do belongs in the server's product catalog,
        // where it is reviewable, not in per-device reporting (internal/db/db.go, CapSet).
        // A build fact this specific — does THIS image have an A/B slot — is the part the
        // catalog cannot know, so it is reported on its own.
        extra.put("ota_supported",
                "true".equalsIgnoreCase(SystemPropertiesProxy.get("ro.build.ab_update", "")));
        populateWifiInfo(extra);
        populateWifiScanResults(extra);
        extra.put("storage_free_gb", getStorageFreeGb());
        extra.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000);
        // Wireless-charging pad reading — only on products that have a pad. Omitted on
        // kiosks so the server sees "no such hardware", not a stuck -1.
        if (product.hasWlc()) {
            extra.put("wlc_status", getWlcStatus());
            extra.put("wlc_charging", readWlcCharging()); // reported so the dashboard shows real state
        }
        extra.put("ram_usage_mb", getRamUsageMb());
        ScreenBrightness.report(this, extra);
        extra.put("timezone", java.util.TimeZone.getDefault().getID());
        // Mic capture gain (TX_DEC0..7 Volume): 102 = trinket mic fix present, 84 = codec
        // default. Admin-only on the dashboard. Omitted when the device has no mixer-paths
        // XML (not a trinket audio platform). See MicGain for why this isn't a live read.
        JSONObject micGain = MicGain.snapshot();
        if (micGain != null) extra.put("mic_gain", micGain);
        // Offline kiosk-exit: surface a pending "exited kiosk offline" event (epoch secs) so
        // the server can audit/alert. The event keeps riding check-ins until the server
        // echoes offline_exit_ack (see applyConfig).
        // Actual live kiosk state: true once a technician has exited offline (the device is
        // out of lock-task even though the server's desired config still says kiosk on).
        extra.put("kiosk_suspended", KioskExit.isSuspended(MdmService.this));
        long offlineExitAt = KioskExit.pendingEventAt(MdmService.this);
        if (offlineExitAt > 0) {
            extra.put("offline_exit_at", offlineExitAt);
        }
        // Staff mode (the pill is up) and a "Back to guest mode" on the tablet, which rides
        // check-ins until the server's config has the lock on again (see applyConfig).
        extra.put("kiosk_staff_mode", KioskExit.isStaffMode(MdmService.this));
        long relockedAt = KioskExit.pendingRelockAt(MdmService.this);
        if (relockedAt > 0) {
            extra.put("kiosk_relocked_at", relockedAt);
        }
        // Temperature rides the battery broadcast (EXTRA_TEMPERATURE) but the sensor is a
        // board thermal channel present on every product, kiosks included (same wiring as
        // the T7). So report it whenever we have a reading, regardless of hasBattery().
        if (batteryIntent != null) {
            extra.put("battery_temp_c", extractBatteryTemperature(batteryIntent));
        }
        // Charging state + charger detail (voltage mV, plug type) — only on products with a
        // charger input. A wall-powered kiosk has none, so these are omitted rather than
        // reported as a permanent "not charging".
        if (product.hasCharging()) {
            // While the charger flaps, charging and charger_type hold the values they had
            // going in and charger_flapping says why — the server stores the flap as one
            // state instead of a row per toggle. Both flap keys are always present, so a
            // delta can clear them (a key that just disappears cannot be merged away).
            boolean flapping = chargerFlap.isFlapping();
            extra.put("charging", flapping ? chargerFlap.heldCharging() : extractCharging(batteryIntent));
            extra.put("charger_voltage_mv", extractChargerVoltage(batteryIntent));
            extra.put("charger_type", flapping ? chargerFlap.heldType() : extractChargerType(batteryIntent));
            extra.put("charger_flapping", flapping);
            extra.put("charger_flaps_5m", chargerFlap.flipsIn5m(SystemClock.elapsedRealtime()));
        }
        // Whether the pack is actually there, on a product that is supposed to have one.
        // The T7's charger IC infers presence from the NTC (thermistor) fault bits rather
        // than a presence pin -- see sgm4154x_charger.c POWER_SUPPLY_PROP_PRESENT -- so an
        // open or out-of-range thermistor, or a pack left unplugged on the bench, reports
        // "no battery" AND suspends charging. The level keeps reading whatever the fuel
        // gauge sees on the rail (e.g. 96%), so without this flag the server shows a
        // healthy-looking percentage for a device that is not on battery power at all.
        if (product.hasBattery() && batteryIntent != null) {
            extra.put("battery_present",
                    batteryIntent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true));
        }
        // The last finished constant-current climb (BatteryWear) — the wear signal. It
        // changes at most once per charge, so it is gated rather than volatile.
        String climb = lastClimbJson;
        if (climb != null) {
            try { extra.put("battery_cc", new JSONObject(climb)); }
            catch (JSONException ignored) { }
        }
        // Screen on/off. The service already reads this to pace its polling; reporting it
        // lets the server separate "powered and in use" from "powered and idle", which is
        // the difference between a device working a shift and one sitting on a shelf.
        // A boolean per sample is enough because the server weights every sample by the
        // gap to the next one, so a slower idle cadence does not skew the totals.
        extra.put("screen_on", isScreenOn());
        // Wi-Fi stability: disconnects observed in the last hour (WifiStateTracker).
        extra.put("wifi_disconnects_1h", getWifiDisconnects1h());
        // System health: reboot reason + per-boot id (changes every reboot → the server
        // detects unexpected reboots) + recent DropBox crashes/ANRs (server dedupes).
        extra.put("boot_reason", SystemPropertiesProxy.get("sys.boot.reason",
                SystemPropertiesProxy.get("ro.boot.bootreason", "")));
        extra.put("boot_id", getBootId());
        extra.put("crash_events", getRecentCrashEvents());
        // The app this device is there to run, read from outside it (HostApp).
        JSONObject hostApp = HostApp.snapshot(MdmService.this);
        if (hostApp != null) extra.put("host_app", hostApp);
        // Security posture (adb, developer options, unknown sources, root, accessibility
        // services, Play Protect): the compliance rules' shared vocabulary across agents.
        expireAdbTcp();
        try {
            SecurityPosture.put(MdmService.this, extra);
        } catch (Throwable t) {
            Log.w(TAG, "security posture failed: " + t.getMessage());
        }
        // Include OTA progress if an update is in progress. Snapshot otaCommandId once: the OTA
        // listener nulls it on a binder callback thread, so re-reading the field after the
        // null-check could NPE at the put() below.
        String otaId = otaCommandId;
        if (otaUpdateManager != null && otaUpdateManager.isActive() && otaId != null) {
            JSONObject otaProgress = new JSONObject();
            otaProgress.put("command_id", otaId);
            otaProgress.put("phase", otaUpdateManager.getCurrentPhase());
            otaProgress.put("percent", otaUpdateManager.getCurrentPercent());
            extra.put("ota_progress", otaProgress);
        }
        return extra;
    }

    private String currentBuildId() {
        return SystemPropertiesProxy.get("ro.build.id", Build.UNKNOWN);
    }

    /**
     * Turns the package verifier off for the duration of an install, remembering what it
     * was. No-op when it is already off, or when a value is already saved — that means an
     * install is in flight, or one was interrupted and has not been restored yet, and
     * overwriting the saved value would lose the original setting.
     */
    private void suspendPackageVerifier() {
        try {
            android.content.SharedPreferences p =
                    getSharedPreferences(PREFS_VERIFIER, Context.MODE_PRIVATE);
            if (p.contains(KEY_VERIFIER_SAVED)) return;
            int cur = Settings.Global.getInt(getContentResolver(), VERIFIER_SETTING, 1);
            if (cur == 0) return;
            // commit(), not apply(): committing the install can kill this process within
            // milliseconds, and an unwritten value would strand the verifier off.
            p.edit().putInt(KEY_VERIFIER_SAVED, cur).commit();
            Settings.Global.putInt(getContentResolver(), VERIFIER_SETTING, 0);
            Log.i(TAG, "package verifier suspended for install (was " + cur + ")");
        } catch (Exception e) {
            // Not fatal: the install just runs with the verifier as it was.
            Log.w(TAG, "could not suspend package verifier: " + e.getMessage());
        }
    }

    /** Puts the verifier back to whatever suspendPackageVerifier() saved. */
    private void restorePackageVerifier() {
        try {
            android.content.SharedPreferences p =
                    getSharedPreferences(PREFS_VERIFIER, Context.MODE_PRIVATE);
            if (!p.contains(KEY_VERIFIER_SAVED)) return;
            int saved = p.getInt(KEY_VERIFIER_SAVED, 1);
            Settings.Global.putInt(getContentResolver(), VERIFIER_SETTING, saved);
            p.edit().remove(KEY_VERIFIER_SAVED).commit();
            Log.i(TAG, "package verifier restored to " + saved);
        } catch (Exception e) {
            Log.w(TAG, "could not restore package verifier: " + e.getMessage());
        }
    }

    /** " (package verifier on/off)", for an install that timed out with no result. */
    private String verifierState() {
        try {
            int on = Settings.Global.getInt(getContentResolver(), "package_verifier_enable", 1);
            return " (package verifier " + (on != 0 ? "on" : "off") + ")";
        } catch (Exception e) {
            return "";
        }
    }

    /** This app's versionName from its own manifest ("" when it cannot be read). */
    /** This app's package, named rather than asked for: running with the system shared
     *  uid, getPackageName() can resolve to the framework's own "android" package, which
     *  reported the platform version (15) and SDK (35) as if they were the client's. */
    private static final String SELF_PACKAGE = "com.aioapp.mdm";
    // Why the version code jumps to 36: clients up to 1.0.2 read their *own* installed
    // version through getPackageName(), which under the system shared uid resolves to the
    // framework's "android" package — versionCode 35 on A15. Such a client refuses any
    // update numbered below 35 as a downgrade, so the first build able to replace it has
    // to clear that number. Codes stay monotonic from here.

    private String clientVersionName() {
        try {
            return getPackageManager().getPackageInfo(SELF_PACKAGE, 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** This app's versionCode — what the server compares to decide a client is behind. */
    private long clientVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(SELF_PACKAGE, 0);
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Full keyframe payload (HTTP check-in + forced WS resync): complete snapshot + app
     *  inventory delta. The server replaces latest_extra from this, clearing any stale keys. */
    private JSONObject buildCheckinPayload() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("serial_number", getDeviceSerial());
        payload.put("build_id", currentBuildId());
        payload.put("product", product.key());
        String hw = getHardwareSerial();
        if (!hw.isEmpty()) payload.put("hw_serial", hw);
        // Battery percent only for products with a battery; a no-battery kiosk omits it
        // (server keeps its prior value / default rather than storing a bogus reading, and
        // its check-in validation rejects the -1 "unknown" sentinel anyway).
        if (product.hasBattery()) {
            payload.put("battery_pct", extractBatteryPct(getBatteryIntent()));
        }
        payload.put("extra", buildExtra());

        // Send full app list only when packages changed or server explicitly requests it
        JSONArray apps = getCachedInstalledApps();
        String currentHash = computeAppsHash(apps);
        if (sendFullAppList || !currentHash.equals(lastAppsHash)) {
            payload.put("installed_apps", apps);
            lastAppsHash = currentHash;
            sendFullAppList = false;
        }
        payload.put("apps_hash", currentHash);
        return payload;
    }

    // ── Delta telemetry ──────────────────────────────────────────────────────────
    // Gated keys: a change in any of these (or battery_pct, or a temperature trigger) is what
    // makes us transmit a WS frame; unchanged gated keys are omitted (server keeps the prior
    // value via merge). Volatile keys are always included in any frame we do send.
    private static final String[] GATED_EXTRA_KEYS = {
            "charging", "storage_free_gb", "wlc_status", "wifi", "ip_address",
            "timezone", "boot_reason", "charger_type", "boot_id", "screen_on",
            // Posture: a change (adb switched on, a new accessibility service) is sent at
            // once rather than waiting for the next keyframe.
            "adb_enabled", "adb_tcp", "dev_options_enabled", "unknown_sources",
            "unknown_source_apps", "su_present", "accessibility_services", "play_protect",
            // Compliance (1.12.0): build facts that change with an OTA, not minute to minute.
            "security_patch", "verified_boot", "selinux_enforcing", "storage_encrypted",
            // Lost mode switching on or off, and a Wi-Fi network added or forgotten.
            "lost_mode", "wifi_networks",
            // Lost-device reporting (1.13.0): beacon state, what was heard, scan trouble, and
            // the capability flags (they only change when Bluetooth hardware is first seen).
            "lost_beacon", "ble_sightings", "ble_scan", "ble_listen", "ble_beacon",
            // Kiosk exited on the device with the PIN, and the timestamp the server acks
            // to clear it. Neither was volatile nor gated, so a flip did not even wake a
            // delta send: the exit reached the server on the next keyframe at best, and
            // the dashboard showed kiosk on over an unlocked device until then. Gated,
            // the flip itself now forces a frame that carries both. (A frame lost in
            // flight is recovered server-side from latest_extra — processOfflineExit.)
            "kiosk_suspended", "offline_exit_at", "kiosk_staff_mode", "kiosk_relocked_at",
            // A flap starting or settling is one frame; the toggles inside it are none.
            "charger_flapping",
            // A new charge climb (BatteryWear), at most one per charge.
            "battery_cc",
            // The host app (HostApp): coarse and cached a minute, so it changes when the app
            // comes to the front, dies, or is updated, and with its usage minute by minute.
            "host_app"
    };
    private static final String[] VOLATILE_EXTRA_KEYS = {
            "battery_temp_c", "ram_usage_mb", "uptime_seconds", "wifi_rssi", "ota_progress",
            // Always carried in any frame we send: charger voltage moves continuously, and
            // crash/disconnect signals must never be dropped waiting for a gated change.
            "charger_voltage_mv", "wifi_disconnects_1h", "crash_events",
            // The flap rate rides along for the server's faulty-charger alert; it moves on
            // every toggle, so it must never be what causes a frame.
            "charger_flaps_5m"
    };

    /** Delta payload: the volatile set + any changed gated fields; battery_pct only if changed. */
    private JSONObject buildDeltaPayload(JSONObject curExtra, int curBattery) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("serial_number", getDeviceSerial());
        payload.put("build_id", currentBuildId()); // identity — always present
        payload.put("product", product.key());     // identity — cheap, lets delta-only devices report it
        JSONObject extra = new JSONObject();
        for (String k : VOLATILE_EXTRA_KEYS) {
            if (curExtra.has(k)) extra.put(k, curExtra.get(k));
        }
        // A lost device sends its Wi-Fi scan in every frame, so the server can keep locating it.
        if (LostMode.isOn(this) && curExtra.has("wifi_scan")) extra.put("wifi_scan", curExtra.get("wifi_scan"));
        synchronized (baselineLock) {
            for (String k : GATED_EXTRA_KEYS) {
                // A key that is gone (host_app once the server names no host app) goes as
                // null, so the server's merged latest_extra drops it rather than keeping it.
                if (!sameAsBaseline(k, curExtra)) extra.put(k, curExtra.has(k) ? curExtra.get(k) : JSONObject.NULL);
            }
            // Battery only for products that have one (matches the keyframe path).
            if (product.hasBattery() && lastSentBattery != curBattery) payload.put("battery_pct", curBattery);
        }
        payload.put("extra", extra);
        return payload;
    }

    /** Caller holds baselineLock. True when key's current value equals the last sent value. */
    private boolean sameAsBaseline(String key, JSONObject curExtra) {
        if (lastSentExtra == null) return false;
        return String.valueOf(lastSentExtra.opt(key)).equals(String.valueOf(curExtra.opt(key)));
    }

    /** Caller holds baselineLock. Any gated field changed since the last successful send? */
    private boolean gatedChanged(JSONObject curExtra) {
        if (lastSentExtra == null) return true;
        for (String k : GATED_EXTRA_KEYS) {
            if (!sameAsBaseline(k, curExtra)) return true;
        }
        return false;
    }

    /** Temperature moved enough to warrant an out-of-band push (keeps thermal alerts fresh). */
    private boolean tempTrigger(JSONObject curExtra) {
        if (lastSentExtra == null) return true;
        double cur = curExtra.optDouble("battery_temp_c", -999);
        double prev = lastSentExtra.optDouble("battery_temp_c", -999);
        if (prev <= -999) return true;
        // 1 °C, not the old 2 °C: at 2 °C the dashboard sat on a stale temperature for
        // 4–6 minutes at a time on battery. In fast mode the tick above decides, with its
        // own step and heartbeat.
        if (tempFastSec > 0) {
            if (Math.abs(cur - prev) >= TEMP_FAST_STEP_C) return true;
            if (SystemClock.elapsedRealtime() - lastTempSentMs >= TEMP_FAST_HEARTBEAT_MS) return true;
        }
        if (Math.abs(cur - prev) >= 1.0) return true;
        return tempBand(cur) != tempBand(prev);
    }

    private int tempBand(double c) { return c >= 45 ? 2 : (c >= 38 ? 1 : 0); }

    /** Record what the server now knows, after a confirmed send. */
    private void rememberSent(JSONObject extra, int battery) {
        synchronized (baselineLock) {
            try { lastSentExtra = new JSONObject(extra.toString()); }
            catch (JSONException e) { lastSentExtra = null; }
            lastSentBattery = battery;
        }
        lastTempSentMs = SystemClock.elapsedRealtime();
        forceKeyframe = false;
    }

    private String hardwareSerial;  // cached: the chip serial is fused in and never changes

    private static final String SSAID_FILE = "/data/system/users/0/settings_ssaid.xml";
    private static final String NUGGET_PACKAGE_PREFIX = "aio.app.nugget";
    private JSONArray ssaidCache = new JSONArray();
    private long ssaidFileMtime = -1, ssaidCachedAt;
    private boolean ssaidReadable;

    /**
     * Settings.Secure.ANDROID_ID is different for every app signing key, so what the Nugget
     * apps use to identify the device is not what this client sees. The OS keeps each app's
     * value in settings_ssaid.xml, keyed by the app's uid; firmware with the settings_ssaid_file
     * sepolicy lets this client read it. Only the aio.app.nugget* entries are returned (package,
     * uid and id); the file's "userkey" entry, the secret every ID is made from, is never
     * read past (its name is not a uid) and nothing else in the file leaves the device.
     * Re-read when the file changes or every 15 minutes, since installs change the uid map.
     * Empty, with ssaidReadable false, on firmware without the sepolicy.
     */
    private synchronized JSONArray getNuggetAndroidIds() {
        File f = new File(SSAID_FILE);
        long now = SystemClock.elapsedRealtime();
        long mtime = f.lastModified();
        if (mtime == ssaidFileMtime && ssaidCachedAt != 0 && now - ssaidCachedAt < 15 * 60 * 1000L) return ssaidCache;
        java.util.TreeMap<String, JSONObject> byPkg = new java.util.TreeMap<>();
        boolean ok = false;
        try (InputStream in = new FileInputStream(f)) {
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.resolvePullParser(in); // plain or binary XML
            ok = true;
            int ev;
            while ((ev = p.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev != org.xmlpull.v1.XmlPullParser.START_TAG || !"setting".equals(p.getName())) continue;
                String name = p.getAttributeValue(null, "name");
                String value = p.getAttributeValue(null, "value");
                if (name == null || value == null || value.isEmpty()) continue;
                int uid;
                try { uid = Integer.parseInt(name); } catch (NumberFormatException e) { continue; } // "userkey" etc.
                String[] pkgs = getPackageManager().getPackagesForUid(uid);
                if (pkgs == null) continue;
                for (String pkg : pkgs) {
                    if (!pkg.startsWith(NUGGET_PACKAGE_PREFIX)) continue;
                    JSONObject o = new JSONObject();
                    o.put("package", pkg);
                    o.put("uid", uid);
                    o.put("android_id", value);
                    byPkg.put(pkg, o);
                }
            }
        } catch (Exception e) {
            if (ssaidFileMtime == -1) Log.i(TAG, "Android IDs not readable (firmware without the sepolicy?): " + e.getMessage());
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : byPkg.values()) out.put(o);
        ssaidCache = out;
        ssaidReadable = ok;
        ssaidFileMtime = mtime;
        ssaidCachedAt = now;
        return out;
    }

    /**
     * The SoC's own serial as upper-case hex ("C45BCE30"), from the Qualcomm
     * /sys/devices/soc0/serial_number (decimal). It is set by the chip, so unlike the AIO
     * serial it survives a reflash; the server keeps a map from it to the AIO serials it has
     * been seen under. Empty when the node is missing or unreadable (non-Qualcomm hardware).
     */
    private String getHardwareSerial() {
        if (hardwareSerial != null) return hardwareSerial;
        String hex = "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream("/sys/devices/soc0/serial_number")))) {
            String line = r.readLine();
            if (line != null) {
                long v = Long.parseLong(line.trim());
                if (v > 0) hex = Long.toHexString(v).toUpperCase(java.util.Locale.US);
            }
        } catch (Exception e) {
            Log.w(TAG, "No hardware serial: " + e.getMessage());
        }
        hardwareSerial = hex;
        return hex;
    }

    /** Fetches the lost screen's venue icon when its version changed (VenueIcon), off the main thread. */
    private void syncVenueIcon() {
        final MdmApiService api = apiService;
        final String serial = getDeviceSerial();
        if (api == null) return;
        try {
            heavyExecutor.execute(() -> VenueIcon.sync(MdmService.this, api, serial));
        } catch (Exception e) {
            Log.w(TAG, "venue icon sync not scheduled: " + e.getMessage());
        }
    }

    private String getDeviceSerial() {
        if (deviceSerial == null) {
            String raw;
            try {
                raw = Build.getSerial();
            } catch (SecurityException e) {
                raw = Build.UNKNOWN;
            }
            serialRaw = raw;
            if (raw != null && VALID_SERIAL.matcher(raw).matches()) {
                deviceSerial = raw;
                // Publish it where apps look for a serial (Nugget reads sys.serialnumber among
                // others; see aio-serial.rc). Only a VALID_SERIAL is ever published here — a
                // corrupted read (the androidboot.baseband=msm case below) never reaches this
                // branch, so Nugget keeps falling back to its own Android ID in that case
                // instead of adopting a shared, non-unique value.
                SystemPropertiesProxy.set(AIO_SERIAL_PROP, raw);
            } else {
                // A corrupted bootloader hands the kernel a command line with no serial on
                // it, and ro.serialno picks up the next token instead — the fleet has seen
                // "androidboot.baseband=msm". Every tablet in that state then reported the
                // same "serial", and the server merged them into one device (19 boots and
                // 29 IP addresses taking turns on one record). ANDROID_ID is per device
                // and survives reboots, so msm-<ANDROID_ID> keeps each one apart; the server
                // shows these as corrupted-serial devices and refuses the raw value.
                String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                deviceSerial = "msm-" + (androidId != null && !androidId.isEmpty() ? androidId : "unknown");
                serialCorrupt = true;
                Log.w(TAG, "Serial \"" + raw + "\" is not a device serial; identifying as " + deviceSerial);
            }
        }
        return deviceSerial;
    }

    private Intent getBatteryIntent() {
        return cachedBatteryIntent;
    }

    private int extractBatteryPct(Intent batteryStatus) {
        if (batteryStatus == null) return -1;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return (int) ((level / (float) scale) * 100);
    }

    private float extractBatteryTemperature(Intent batteryStatus) {
        if (batteryStatus == null) return SENTINEL_TEMP_C;
        int tenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        return tenths / 10.0f;
    }

    private boolean extractCharging(Intent batteryStatus) {
        if (batteryStatus == null) return false;
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != 0) return true;
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    /** Charger voltage in millivolts (BatteryManager.EXTRA_VOLTAGE); -1 if unknown. A plain
     *  5V (~5000 mV) supply distinguishes a basic charger from a higher-voltage fast charger. */
    private int extractChargerVoltage(Intent batteryStatus) {
        if (batteryStatus == null) return -1;
        int raw = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        if (raw < 0) return -1;
        synchronized (quantLock) {
            if (reportedChargerMv < 0 || Math.abs(raw - reportedChargerMv) >= CHARGER_STEP_MV) {
                reportedChargerMv = Math.round(raw / (float) CHARGER_STEP_MV) * CHARGER_STEP_MV;
            }
            return reportedChargerMv;
        }
    }

    /** Plug type: none | ac | usb | wireless | unknown (from BatteryManager.EXTRA_PLUGGED). */
    private String extractChargerType(Intent batteryStatus) {
        if (batteryStatus == null) return "unknown";
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged == 0) return "none";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "ac";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "usb";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "wireless";
        return "unknown";
    }

    /** Raw CHARGING status — FULL and NOT_CHARGING (thermal suspend) are not, though plugged in. */
    private static boolean isChargingStatus(Intent intent) {
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                && intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                        == BatteryManager.BATTERY_STATUS_CHARGING;
    }

    /**
     * healthd's current_now, which on the T7 is the charger's programmed charge current
     * (ICHG), not a measurement. A binder call into healthd (an I2C read of the charger), so
     * it is made only while charging; MIN_VALUE otherwise.
     */
    private int readChargeCurrentUa(Intent intent) {
        if (intent == null || !isChargingStatus(intent)) return Integer.MIN_VALUE;
        BatteryManager bm = getSystemService(BatteryManager.class);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                : Integer.MIN_VALUE;
    }

    /**
     * One BatteryLog row from a battery broadcast (ev b) or a screen edge (ev s, with the
     * cached broadcast). wlc is the pad's guest line as last read, wlc_on the pad's saved
     * charging switch, so the analysis can tell whether a guest on the pad moves the drain.
     */
    private void logBattery(String ev, Intent intent, int ichgUa) {
        BatteryLog log = batteryLog;
        if (log == null || intent == null) return;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int screen = isScreenOn() ? 1 : 0;
        int wlc = -1, wlcOn = -1;
        if (product.hasWlc()) {
            synchronized (wlcLock) { wlc = cachedWlcStatus; }
            wlcOn = getSharedPreferences(PREFS_WLC, MODE_PRIVATE)
                    .getBoolean(KEY_WLC_CHARGING, true) ? 1 : 0;
        }
        int ichgMa = ichgUa == Integer.MIN_VALUE || ichgUa == 0 ? -1 : Math.abs(ichgUa) / 1000;
        int iin = intent.getIntExtra("max_charging_current", -1);
        log.log(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ev,
                status + "/" + plugged + "/" + screen + "/" + wlc + "/" + wlcOn,
                intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
                extractBatteryPct(intent),
                status, plugged,
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000) / 10.0,
                screen, ichgMa, iin > 0 ? iin / 1000 : -1,
                chargerFlap.isFlapping() ? 1 : 0, wlc, wlcOn);
    }

    /**
     * Feeds one battery broadcast to BatteryWear; true when it finished a climb, which is
     * then stored as battery_cc.
     */
    private boolean feedBatteryWear(Intent intent, int ichgUa) {
        BatteryWear wear = batteryWear;
        if (wear == null || intent == null) return false;
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean charging = isChargingStatus(intent);
        BatteryWear.Climb climb = wear.onSample(SystemClock.elapsedRealtime(), charging,
                plugged != 0, chargerFlap.isFlapping(),
                intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000) / 10.0,
                ichgUa, intent.getIntExtra("max_charging_current", -1),
                extractChargerType(intent));
        if (climb == null) return false;
        try {
            String json = climbToJson(climb).toString();
            lastClimbJson = json;
            getSharedPreferences(PREFS_BATTERY_WEAR, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_CLIMB, json).apply();
            Log.i(TAG, "Battery climb: " + json);
            return true;
        } catch (JSONException e) {
            Log.w(TAG, "Battery climb not stored", e);
            return false;
        }
    }

    /**
     * battery_cc: at (device wall clock, ms), mv0 + step (rung voltages), s (seconds from
     * the first rung to each rung crossed), scr (cumulative screen-on seconds at each rung),
     * end (top | unplug | stop), ichg_ma [min, max] (programmed charge current; -1 unknown),
     * iin_ma (input limit, -1 unknown), plug, temp_c [min, max], gap_s (widest sample gap
     * bracketing a crossing), n (samples).
     */
    private static JSONObject climbToJson(BatteryWear.Climb c) throws JSONException {
        JSONArray s = new JSONArray();
        JSONArray scr = new JSONArray();
        for (int i = 0; i < c.sec.length; i++) {
            s.put(c.sec[i]);
            scr.put(c.screenSec[i]);
        }
        JSONObject o = new JSONObject();
        o.put("at", System.currentTimeMillis());
        o.put("mv0", c.firstRungMv);
        o.put("step", BatteryWear.RUNG_STEP_MV);
        o.put("s", s);
        o.put("scr", scr);
        o.put("end", c.end);
        o.put("ichg_ma", new JSONArray().put(c.ichgMinMa).put(c.ichgMaxMa));
        o.put("iin_ma", c.inputLimitMa);
        o.put("plug", c.plug != null ? c.plug : "unknown");
        if (!Double.isNaN(c.tempMinC)) {
            o.put("temp_c", new JSONArray().put(c.tempMinC).put(c.tempMaxC));
        }
        o.put("gap_s", c.maxGapSec);
        o.put("n", c.samples);
        return o;
    }

    /** Record a Wi-Fi disconnect and prune events older than an hour. */
    private void recordWifiDisconnect() {
        long now = SystemClock.elapsedRealtime();
        synchronized (wifiDisconnects) {
            wifiDisconnects.addLast(now);
            while (!wifiDisconnects.isEmpty() && now - wifiDisconnects.peekFirst() > CRASH_LOOKBACK_MS) {
                wifiDisconnects.removeFirst();
            }
        }
        Log.i(TAG, "Wi-Fi disconnect recorded (" + wifiDisconnects.size() + " in last hour)");
    }

    /** Count of Wi-Fi disconnects in the last hour (prunes old events first). */
    private int getWifiDisconnects1h() {
        long now = SystemClock.elapsedRealtime();
        synchronized (wifiDisconnects) {
            while (!wifiDisconnects.isEmpty() && now - wifiDisconnects.peekFirst() > CRASH_LOOKBACK_MS) {
                wifiDisconnects.removeFirst();
            }
            return wifiDisconnects.size();
        }
    }

    /** Per-boot UUID from /proc (changes on every reboot). Cached — it is constant per boot.
     *  The server compares it across check-ins to detect an (unexpected) reboot. */
    private String cachedBootId;
    private String getBootId() {
        if (cachedBootId != null) return cachedBootId;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"));
            String line = r.readLine();
            cachedBootId = (line != null) ? line.trim() : "";
        } catch (Exception e) {
            cachedBootId = "";
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
        return cachedBootId;
    }

    // DropBox tags that represent an app/system crash, ANR, or native tombstone.
    private static final String[] CRASH_TAGS = {
            "data_app_crash", "data_app_anr", "data_app_native_crash",
            "system_app_crash", "system_app_anr", "system_app_native_crash",
            "system_server_crash", "system_server_anr", "system_server_native_crash",
            "SYSTEM_TOMBSTONE"
    };
    private static final long CRASH_LOOKBACK_MS = 60 * 60 * 1000L; // last hour
    // Bounds on the crash_events field. crash_events is only ONE part of `extra` (wifi,
    // storage, ram, boot ids, etc. stack on top), and the server rejects an oversized
    // `extra` outright (413 / dropped WS frame). A crash-heavy device (e.g. full GMS) used
    // to blow the whole check-in past the limit. Keep the trace budget well under any sane
    // server cap so the total `extra` stays small, and bound the entry count so a device
    // with hundreds of crashes can't balloon the array with summary-only objects.
    private static final int  MAX_TRACE_BYTES = 48 * 1024;   // full trace body per crash
    private static final int  MAX_TOTAL_TRACE_BYTES = 128 * 1024; // trace budget across one check-in
    private static final int  MAX_CRASH_ENTRIES = 40;        // hard cap on entries per check-in

    /** Recent crash/ANR/tombstone entries from DropBoxManager (readable to this system-UID
     *  app), as [{kind,time_ms,summary,trace}]. The DropBox entry holds the real diagnostic —
     *  the Java stack trace, the ANR (blocked-thread) trace, or the native tombstone — so we
     *  send it as `trace` (capped per-entry and across the check-in), keeping `summary` as the
     *  headline first line. Reports the last hour on every check-in; the server dedupes by
     *  (device,kind,time_ms), so a repeated report is harmless and a lost send self-recovers. */
    private JSONArray getRecentCrashEvents() {
        JSONArray arr = new JSONArray();
        try {
            android.os.DropBoxManager dbm =
                    (android.os.DropBoxManager) getSystemService(Context.DROPBOX_SERVICE);
            if (dbm == null) return arr;
            long since = System.currentTimeMillis() - CRASH_LOOKBACK_MS;
            int traceBudget = MAX_TOTAL_TRACE_BYTES;
            for (String tag : CRASH_TAGS) {
                if (arr.length() >= MAX_CRASH_ENTRIES) break;
                long cursor = since;
                for (int i = 0; i < 50; i++) { // bound work per tag
                    if (arr.length() >= MAX_CRASH_ENTRIES) break;
                    android.os.DropBoxManager.Entry e = dbm.getNextEntry(tag, cursor);
                    if (e == null) break;
                    try {
                        long t = e.getTimeMillis();
                        JSONObject o = new JSONObject();
                        o.put("kind", tag);
                        o.put("time_ms", t);
                        // Read the full entry (up to the remaining budget). getText's arg is a
                        // byte cap, so the old getText(200) truncated to the first line only.
                        int cap = Math.min(MAX_TRACE_BYTES, Math.max(0, traceBudget));
                        String txt = readEntry(e, cap > 0 ? cap : 200);
                        if (txt != null && !txt.isEmpty()) {
                            o.put("summary", crashHeadline(txt));
                            if (cap > 0) {
                                o.put("trace", txt);
                                traceBudget -= txt.length();
                            }
                        }
                        arr.put(o);
                        if (t <= cursor) t = cursor + 1; // guard against equal timestamps
                        cursor = t;
                    } finally {
                        e.close();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "crash events read failed: " + t.getMessage());
        }
        return arr;
    }

    /** A useful one-line headline from a DropBox crash/ANR/tombstone body. The entry opens with
     *  "Key: value" header lines (SystemUptimeMs, Process, PID, …), then a blank line, then the
     *  real exception/abort message. The bare first line is "SystemUptimeMs: …", which is
     *  useless as an alert headline — so prefer "&lt;process&gt; — &lt;first body line&gt;",
     *  falling back to the first non-blank line (e.g. native tombstones with no header). */
    private static String crashHeadline(String txt) {
        String[] lines = txt.split("\\n");
        String proc = null;
        int body = 0; // index of the first line after the header's blank separator
        for (int i = 0; i < lines.length; i++) {
            if (proc == null && lines[i].startsWith("Process:")) proc = lines[i].substring(8).trim();
            if (lines[i].trim().isEmpty()) { body = i + 1; break; }
        }
        String exc = "";
        for (int i = body; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) { exc = lines[i].trim(); break; }
        }
        if (exc.isEmpty()) exc = lines.length > 0 ? lines[0].trim() : "";
        if (proc != null && !proc.isEmpty() && !exc.isEmpty()) return proc + " — " + exc;
        if (!exc.isEmpty()) return exc;
        return proc != null ? proc : "";
    }

    /** Read up to {@code cap} bytes of a DropBox entry's text. Prefers getText(), but falls
     *  back to the raw (gzip-decoded) input stream when getText() returns null — which it does
     *  for any entry not flagged IS_TEXT (notably native tombstones). Without this fallback the
     *  crash event is reported with only its kind/time and no trace at all. */
    private static String readEntry(android.os.DropBoxManager.Entry e, int cap) {
        String txt = e.getText(cap);
        if (txt != null) return txt;
        java.io.InputStream is = null;
        try {
            is = e.getInputStream(); // already gunzipped by the framework when IS_GZIPPED
            if (is == null) return null;
            byte[] buf = new byte[cap];
            int off = 0, n;
            while (off < cap && (n = is.read(buf, off, cap - off)) > 0) off += n;
            return new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (java.io.IOException ignore) {}
        }
    }

    private void populateWifiInfo(JSONObject extra) throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if (cachedWifiExtra != null && now - wifiLastMs < WIFI_CACHE_MS) {
            extra.put("ip_address", cachedWifiExtra.opt("ip_address"));
            extra.put("wifi", cachedWifiExtra.opt("wifi"));
            extra.put("wifi_rssi", cachedWifiExtra.opt("wifi_rssi"));
            copyWifiDetail(extra);
            return;
        }
        cachedWifiExtra = new JSONObject();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            cachedWifiExtra.put("ip_address", JSONObject.NULL);
            cachedWifiExtra.put("wifi", JSONObject.NULL);
            cachedWifiExtra.put("wifi_rssi", JSONObject.NULL);
        } else {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                cachedWifiExtra.put("ip_address", JSONObject.NULL);
                cachedWifiExtra.put("wifi", JSONObject.NULL);
                cachedWifiExtra.put("wifi_rssi", JSONObject.NULL);
            } else {
                int ip4 = info.getIpAddress();
                cachedWifiExtra.put("ip_address", String.format("%d.%d.%d.%d",
                        ip4 & 0xff, (ip4 >> 8) & 0xff, (ip4 >> 16) & 0xff, (ip4 >> 24) & 0xff));
                cachedWifiExtra.put("wifi", info.getSSID());
                cachedWifiExtra.put("wifi_rssi", info.getRssi());
                // Band, link speed and access point (1.17.0): the Network tab shows them and
                // a change of BSSID with the same SSID is roaming between access points.
                int mhz = info.getFrequency();
                if (mhz > 0) cachedWifiExtra.put("wifi_band", mhz >= 5925 ? "6" : mhz >= 4900 ? "5" : "2.4");
                if (info.getLinkSpeed() > 0) cachedWifiExtra.put("wifi_link_mbps", info.getLinkSpeed());
                String bssid = info.getBSSID();
                if (bssid != null && !"02:00:00:00:00:00".equals(bssid)) cachedWifiExtra.put("wifi_bssid", bssid);
            }
        }
        wifiLastMs = now;
        extra.put("ip_address", cachedWifiExtra.opt("ip_address"));
        extra.put("wifi", cachedWifiExtra.opt("wifi"));
        extra.put("wifi_rssi", cachedWifiExtra.opt("wifi_rssi"));
        copyWifiDetail(extra);
    }

    private void copyWifiDetail(JSONObject extra) throws JSONException {
        for (String k : new String[] {"wifi_band", "wifi_link_mbps", "wifi_bssid"}) {
            Object v = cachedWifiExtra.opt(k);
            if (v != null) extra.put(k, v);
        }
    }

    /** Non-blocking: writes the latest cached scan into the checkin extra. */
    private void populateWifiScanResults(JSONObject extra) throws JSONException {
        if (cachedWifiScan != null) {
            extra.put("wifi_scan", cachedWifiScan);
        } else {
            extra.put("wifi_scan", new JSONArray());
        }
    }

    /** Runs on wifiScanExecutor every WIFI_SCAN_INTERVAL_SEC; blocks up to 5s per scan. */
    private void runWifiScan() {
        Log.i(LOCATION_TAG, "Starting WiFi scan...");
        boolean scanOk = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                Log.w(LOCATION_TAG, "WiFi scan skipped: WifiManager is null");
                return;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final List<ScanResult>[] holder = new List[1];

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(LOCATION_TAG, "Scan results available broadcast received");
                    holder[0] = wm.getScanResults();
                    latch.countDown();
                }
            };

            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            getApplicationContext().registerReceiver(receiver, filter);

            try {
                boolean scanStarted = wm.startScan();
                Log.i(LOCATION_TAG, "startScan() returned: " + scanStarted);

                if (scanStarted) {
                    boolean gotResults = latch.await(5, TimeUnit.SECONDS);
                    if (!gotResults) {
                        Log.w(LOCATION_TAG, "WiFi scan timed out after 5s, falling back to cached results");
                        holder[0] = wm.getScanResults();
                    }
                } else {
                    Log.w(LOCATION_TAG, "startScan() returned false, using cached results");
                    holder[0] = wm.getScanResults();
                }
            } finally {
                try {
                    getApplicationContext().unregisterReceiver(receiver);
                } catch (IllegalArgumentException ignored) {
                }
            }

            List<ScanResult> results = holder[0];
            if (results != null) {
                scanOk = true;
                cachedWifiScan = new JSONArray();
                Log.i(LOCATION_TAG, "WiFi scan returned " + results.size() + " APs");
                for (ScanResult sr : results) {
                    JSONObject ap = new JSONObject();
                    ap.put("bssid", sr.BSSID);
                    ap.put("ssid", sr.SSID);
                    ap.put("rssi", sr.level);
                    cachedWifiScan.put(ap);
                }
            } else {
                Log.w(LOCATION_TAG, "WiFi scan returned null");
            }
        } catch (SecurityException e) {
            Log.w(LOCATION_TAG, "WiFi scan permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(LOCATION_TAG, "WiFi scan error: " + e.getMessage());
        }
        Log.i(LOCATION_TAG, "WiFi scan complete: " + (cachedWifiScan != null ? cachedWifiScan.length() : 0) + " APs in cache");
        // A pending change to the network the device was on goes in once that network is
        // gone, and comes back out if it does not connect (WifiConfig).
        try {
            WifiConfig.tick(this);
        } catch (Throwable t) {
            Log.w(TAG, "wifi pending tick: " + t.getMessage());
        }
        // Self-lock (1.13.0): has the device left its venue, or come back to it?
        if (dpm != null) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                boolean phoneOnPad = product.hasWlc() && getWlcStatus() == 1;
                if (SelfLock.onScan(this, dpm, adminComponent, scanOk ? cachedWifiScan : null,
                        info == null ? "" : info.getSSID(), phoneOnPad, getDeviceSerial())) {
                    forceKeyframe = true;
                    sendTelemetryOverWs();
                }
            } catch (Throwable t) {
                Log.w(TAG, "self-lock tick: " + t.getMessage());
            }
        }
    }

    /**
     * Every 10 s on bleExecutor: listen for lost devices when the server asks (BleLost does the
     * timing), and once a minute bring the lost beacon in line with lost mode.
     */
    private void bleTick() {
        try {
            if (BleLost.listenTick(this)) sendTelemetryOverWs();
            if (bleTicks++ % 6 == 0) BleLost.syncBeacon(this);
        } catch (Throwable t) {
            Log.w(TAG, "ble tick: " + t.getMessage());
        }
    }

    private JSONObject getRamUsageMb() throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if (cachedRam != null && now - ramLastMs < RAM_CACHE_MS) return cachedRam;
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        cachedRam = new JSONObject();
        cachedRam.put("total", mi.totalMem / (1024 * 1024));
        cachedRam.put("available", mi.availMem / (1024 * 1024));
        cachedRam.put("used", (mi.totalMem - mi.availMem) / (1024 * 1024));
        ramLastMs = now;
        return cachedRam;
    }

    private int getWlcStatus() {
        long now = SystemClock.elapsedRealtime();
        synchronized (wlcLock) {
            if (wlcLastMs > 0 && now - wlcLastMs < WLC_CACHE_MS) return cachedWlcStatus;
        }
        // Cold path (the watcher hasn't populated the cache yet). Non-pulsing best effort so
        // we never write gpio127 off the watcher thread: charging on -> settled gpio27;
        // charging off -> unknown (the watcher will populate a real reading on its cadence).
        int v = readWlcCharging() ? readSettledWlc() : -1;
        synchronized (wlcLock) {
            cachedWlcStatus = v;
            wlcLastMs = now;
        }
        return v;
    }

    /** Instantaneous, uncached read of the wireless-charging pad's guest-detection GPIO
     *  (gpio27): 1 = line high, 0 = line low, -1 = unreadable. This is the BARE line; it
     *  only means guest-present(1)/vacant(0) while charging is ON (gpio127=1). With charging
     *  off the line free-runs and a single sample is meaningless — classifyWlc() reports
     *  unknown in that case rather than sampling it. */
    private int readWlcStatusUncached() {
        final String gpioPath = "/sys/devices/platform/soc/soc:customer_gpio/gpio27";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(gpioPath))) {
            String line = reader.readLine();
            String v = line == null ? "" : line.replace("\0", "").trim();
            return v.equals("1") ? 1 : (v.equals("0") ? 0 : -1);
        } catch (Exception e) {
            Log.e(TAG, "getWlcStatus error: " + e.getMessage());
            return -1;
        }
    }

    // ── WLC charging enable/disable via customer_gpio gpio127 (1 = charging on, 0 = off) ──
    private static final String WLC_CHARGING_GPIO = "/sys/devices/platform/soc/soc:customer_gpio/gpio127";
    private static final String PREFS_WLC = "mdm_wlc";
    private static final String KEY_WLC_CHARGING = "wlc_charging_enabled";
    // Last value actually written to the gpio, so repeated/interleaved config frames don't
    // re-write (and flap) the line. null = not applied yet this process.
    private Boolean wlcLastApplied = null;
    private String kioskLastSig = null; // last applied "kiosk_enabled|kiosk_package"; gates re-apply

    /** Write the wireless-charging enable line. Requires sepolicy allowing system_app to
     *  write the customer_gpio sysfs node (added in debug-GMS). Returns true on success. */
    private boolean setWlcCharging(boolean enable) {
        try (java.io.FileWriter w = new java.io.FileWriter(WLC_CHARGING_GPIO)) {
            w.write(enable ? "1" : "0");
            w.flush();
            Log.i(TAG, "WLC charging set " + (enable ? "on" : "off"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "setWlcCharging error: " + e.getMessage());
            return false;
        }
    }

    /** Current WLC charging line: true = on (1), false = off (0); true if unreadable. */
    private boolean readWlcCharging() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(WLC_CHARGING_GPIO))) {
            String line = r.readLine();
            String v = line == null ? "" : line.replace("\0", "").trim();
            return !v.equals("0");
        } catch (Exception e) {
            return true;
        }
    }

    /** Re-apply the saved WLC charging setting — the sysfs line resets to on across a
     *  reboot, so a device left "charging off" must be re-disabled on boot. */
    private void applySavedWlcCharging() {
        if (!product.hasWlc()) return;
        boolean enabled = getSharedPreferences(PREFS_WLC, MODE_PRIVATE)
                .getBoolean(KEY_WLC_CHARGING, true);
        if (setWlcCharging(enabled)) wlcLastApplied = Boolean.valueOf(enabled);
    }

    // Reported when charging is ON but gpio27 shows WLC_SETTLE_MIN_EDGES+ transitions within
    // one burst-sample window — the line is genuinely oscillating (observed in the field on
    // hardware with a faulty/loose pad connection), not just a single glitchy sample. Distinct
    // from -1 (charging off / unreadable).
    private static final int WLC_STATUS_FLAPPING = 2;

    // Last settled WLC value we logged at debug, so the ~1.3s watcher only writes a line when
    // the value actually changes (watcher thread only, like the rest of the settle state).
    private int lastLoggedSettledWlc = Integer.MIN_VALUE;

    /** Burst-samples gpio27 over ~WLC_SETTLE_READS*WLC_SETTLE_STEP_MS and classifies by edge
     *  count in that raw sequence (mirrors tools/wlc-check.sh's classify()) — see the
     *  WLC_SETTLE_* field comments for why this replaced a "3 consecutive reads agree"
     *  scheme. Returns 1 (guest on pad) / 0 (vacant) when the burst shows fewer than
     *  WLC_SETTLE_MIN_EDGES transitions, WLC_STATUS_FLAPPING when it doesn't, or -1 if every
     *  read was unreadable. Only meaningful while charging is ON — the caller ensures that. */
    private int readSettledWlc() {
        int last = -1, edges = 0, reads = 0;
        for (int i = 0; i < WLC_SETTLE_READS; i++) {
            reads++;
            int v = readWlcStatusUncached();
            if (v >= 0) {
                if (last >= 0 && v != last) edges++;
                last = v;
            }
            try {
                Thread.sleep(WLC_SETTLE_STEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (edges >= WLC_SETTLE_MIN_EDGES) {
            Log.w(TAG, "readSettledWlc: FLAPPING — " + edges + " edges across " + reads
                    + " reads (~" + (reads * WLC_SETTLE_STEP_MS) + "ms window)");
            return WLC_STATUS_FLAPPING;
        }
        // Only when it changed, and at verbose: the watcher calls this every ~1.3s, and logging
        // every settled read held the whole main logcat buffer to about two minutes on a T7 —
        // every line older than that was gone before anyone could pull it, which is what made
        // the dead-WS-thread bug above impossible to post-mortem in the field.
        if (last != lastLoggedSettledWlc) {
            lastLoggedSettledWlc = last;
            Log.d(TAG, "readSettledWlc: settled=" + last + " (" + edges + " edge(s) across "
                    + reads + " reads)");
        } else {
            Log.v(TAG, "readSettledWlc: settled=" + last + " (" + edges + " edge(s) across "
                    + reads + " reads)");
        }
        return last; // best effort (may be -1 if every read was unreadable)
    }

    /** Charging-aware WLC classification (watcher thread only). Returns 1 = guest on pad,
     *  0 = vacant, WLC_STATUS_FLAPPING = line won't settle, -1 = unknown/not-available.
     *  gpio27 is a valid guest-detection line ONLY while charging is ON (gpio127=1); with
     *  charging OFF it free-runs and is meaningless. We deliberately do NOT pulse charging
     *  on to read it — that would deliver charge the operator disabled — so charging-off
     *  reports unknown (-1) and the dashboard shows "Not available". */
    private int classifyWlc() {
        if (readWlcCharging()) {           // gpio127 == 1: normally stable, but verify
            return readSettledWlc();
        }
        return -1;                         // charging OFF: line not readable, report unknown
    }

    /** Cross-tick flapping check — see wlcRecentTicks' field comment for why this exists
     *  alongside readSettledWlc()'s own within-call check. Watcher-thread only (wlcRecentTicks
     *  is not synchronized). */
    private boolean tickLooksFlapping(int w) {
        wlcRecentTicks.addLast(w);
        while (wlcRecentTicks.size() > WLC_FLAP_WINDOW_TICKS) wlcRecentTicks.removeFirst();
        int flips = 0;
        Integer prev = null;
        for (int v : wlcRecentTicks) {
            if (prev != null && prev != v) flips++;
            prev = v;
        }
        return flips >= WLC_FLAP_MIN_FLIPS;
    }

    /** Poll the Qi pad's guest-detection GPIO on a short cadence and push telemetry the
     *  instant it changes, so placing/removing a device on the pad is reflected within
     *  ~WLC_WATCH_MS instead of waiting out the telemetry cache + next check-in. Each tick
     *  also refreshes the wlc cache so the HTTP safety-net path sees a fresh value too. */
    private void startWlcWatcher() {
        wlcWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mdm-wlc-watch");
            t.setDaemon(true);
            return t;
        });
        wlcWatcher.scheduleWithFixedDelay(() -> {
            try {
                int w = classifyWlc();
                if (w == 0 || w == 1) {
                    if (tickLooksFlapping(w)) {
                        Log.w(TAG, "WLC watcher: settled=" + w + " but " + WLC_FLAP_MIN_FLIPS
                                + "+ flips across the last " + wlcRecentTicks.size()
                                + " ticks — reporting flapping instead");
                        w = WLC_STATUS_FLAPPING;
                    }
                } else {
                    wlcRecentTicks.clear(); // charging off / call-level flapping / unreadable: no tick trend to track
                }
                synchronized (wlcLock) {
                    cachedWlcStatus = w;
                    wlcLastMs = SystemClock.elapsedRealtime();
                }
                if (w != lastWatchedWlc) {
                    boolean first = lastWatchedWlc == Integer.MIN_VALUE;
                    lastWatchedWlc = w;
                    if (!first) {
                        Log.i(TAG, "WLC pad state changed: " + w + " — pushing immediate telemetry");
                        sendTelemetryOverWs();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "wlc watcher error: " + e.getMessage());
            }
        }, WLC_WATCH_MS, WLC_WATCH_MS, TimeUnit.MILLISECONDS);
    }

    private double getStorageFreeGb() {
        long now = SystemClock.elapsedRealtime();
        if (storageLastMs > 0 && now - storageLastMs < STORAGE_CACHE_MS) return cachedStorageFreeGb;
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long bytesAvailable = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        cachedStorageFreeGb = Math.round(bytesAvailable / (1024.0 * 1024.0 * 1024.0) * 10.0) / 10.0;
        storageLastMs = now;
        return cachedStorageFreeGb;
    }

    private void registerPackageChangeReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        packageChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Package changed, invalidating app cache + pushing refreshed list");
                cachedInstalledApps = null;
                // Push the refreshed app list right away as a keyframe so the dashboard
                // reflects an install/uninstall in seconds instead of waiting for the
                // ~5-minute HTTP safety-net check-in (WS deltas don't carry the app list).
                forceKeyframe = true;
                if (networkAvailable && !polling) performCheckin();
            }
        };
        registerReceiver(packageChangeReceiver, filter);
    }

    private JSONArray getCachedInstalledApps() {
        if (cachedInstalledApps == null) {
            cachedInstalledApps = getInstalledApps();
        }
        return cachedInstalledApps;
    }

    private String computeAppsHash(JSONArray apps) {
        List<String> packages = new ArrayList<>(apps.length());
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app != null) packages.add(app.optString("package", ""));
        }
        Collections.sort(packages);
        CRC32 crc = new CRC32();
        for (String pkg : packages) {
            crc.update(pkg.getBytes(StandardCharsets.UTF_8));
        }
        return Long.toHexString(crc.getValue());
    }

    /**
     * Whether the display is on right now. PowerManager.isInteractive() is the same signal
     * getAdaptivePollInterval() uses to slow polling down when nobody is watching; here it
     * is reported so the server can measure standby time. Returns true when PowerManager is
     * unavailable, matching the polling code: assume in use rather than invent idle time.
     */
    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private long getAdaptivePollInterval() {
        long base = apiService.getPollInterval();
        Intent battery = getBatteryIntent();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();

        if (battery == null) return interactive ? base : base * 2;
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != 0) return base; // charging — use normal interval

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = (scale > 0 && level >= 0) ? (int) ((level / (float) scale) * 100) : 100;
        if (pct <= 15) return base * 6;  // critical battery: poll far less often
        if (pct <= 30) return base * 3;  // low battery: poll less often
        if (!interactive) return base * 4; // screen off, not charging: nobody's watching
        return base;
    }

    private void setOtaStatus(String title, String text) {
        otaTitle = title;
        otaText = text;
        otaErrorAt = 0;
        refreshStatus();
    }

    /** A failed firmware update: said for an hour, then the status line goes back to normal. */
    private void setOtaError() {
        otaTitle = "Couldn't finish the update";
        otaText = "It can be retried from the dashboard";
        otaErrorAt = System.currentTimeMillis();
        refreshStatus();
    }

    private void clearOtaStatus() {
        otaTitle = null;
        otaText = null;
        otaErrorAt = 0;
        refreshStatus();
    }

    /** {title, text} of the status line right now. */
    private String[] statusLine() {
        if (otaErrorAt > 0 && System.currentTimeMillis() - otaErrorAt > 60 * 60_000L) {
            otaTitle = null;
            otaText = null;
            otaErrorAt = 0;
        }
        String venue = VenueIcon.venue(this);
        String detail = notificationVersion.isEmpty() ? "" : "MDM " + notificationVersion;
        if (!venue.isEmpty()) detail = detail.isEmpty() ? venue : venue + " · " + detail;
        String title = otaTitle;
        if (title != null) return new String[] { title, otaText != null ? otaText : detail };
        boolean up = networkAvailable && (serverReachable || (wsClient != null && wsClient.isConnected()));
        return new String[] { up ? "Connected to AIO" : "Offline · trying again", detail };
    }

    /** Re-posts the status line when what it says changed (connection, venue, an update). */
    private synchronized void refreshStatus() {
        try {
            String[] l = statusLine();
            String key = l[0] + "\n" + l[1];
            if (key.equals(lastStatusKey)) return;
            lastStatusKey = key;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildStatus());
        } catch (Exception e) {
            Log.w(TAG, "refreshStatus failed: " + e.getMessage());
        }
    }

    /**
     * Re-posts the persistent status notification if it is no longer showing. setOngoing +
     * FLAG_NO_CLEAR stop a swipe, but nothing stops the notification being lost another way
     * (channel recreated, a restart that didn't re-enter startForeground); this makes the
     * status line self-healing on every check-in instead of only at service start.
     */
    private void ensureStatusNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (sbn.getId() == NOTIFICATION_ID) return;
            }
            nm.notify(NOTIFICATION_ID, buildStatus());
        } catch (Exception e) {
            Log.w(TAG, "ensureStatusNotification failed: " + e.getMessage());
        }
        refreshStatus();
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        // Persistent status: silent, low-importance, no badge.
        NotificationChannel status = new NotificationChannel(
                CHANNEL_ID, "Device status", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Shows that this device is set up and protected.");
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
        // Updates & app installs: gently alerting so a finished update can be noticed,
        // without the persistent status ever making a sound.
        NotificationChannel updates = new NotificationChannel(
                UPDATES_CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT);
        updates.setDescription("Progress and results for system updates and app installs.");
        nm.createNotificationChannel(updates);
    }

    private Notification buildStatus() {
        String[] l = statusLine();
        Notification n = Notices.base(this, CHANNEL_ID, "Managed device")
                .setContentTitle(l[0])
                // The venue and which client build this device runs — the first thing asked
                // in the field, and since 1.0.0 it can differ from the firmware it shipped with.
                .setContentText(l[1])
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // Swipe → this fires → we post it straight back (see NOTIF_DISMISSED_ACTION).
                .setDeleteIntent(PendingIntent.getBroadcast(this, 0,
                        new Intent(NOTIF_DISMISSED_ACTION).setPackage(getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        // Keep the persistent status non-dismissible — setOngoing alone lets the user
        // swipe a foreground-service notification away on modern Android.
        n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        return n;
    }

    /** What the install notice says about the app being installed (notifications N2). */
    private static final class InstallNote {
        String name = "";     // the app's label once the APK is read
        String version = "";  // "2-pos (code 3)"
        String pkg = "";
        android.graphics.Bitmap icon;
        boolean replacing;

        String what() { return name.isEmpty() ? "an app" : name; }
    }

    /**
     * Posts/updates an install's notice (one id per install, separate from the status line).
     * [phase] download / install show progress (determinate when {@code percent >= 0});
     * done / failed are the result, dismissible, and clear themselves after a while.
     */
    private void showInstallNotification(int notifId, InstallNote a, String phase, String text, int percent) {
        Notification.Builder b = Notices.base(this, UPDATES_CHANNEL_ID, "Updates")
                .setGroup(Notices.GROUP_UPDATES)
                .setOnlyAlertOnce(true)
                .setLargeIcon(a.icon);
        switch (phase) {
            case "download":
            case "install":
                b.setContentTitle(("download".equals(phase) ? "Downloading " : "Installing ") + a.what())
                        .setContentText(text != null ? text : a.version)
                        .setOngoing(true)
                        .setSilent(true);
                if (percent >= 0) b.setProgress(100, percent, false);
                else b.setProgress(0, 0, true); // indeterminate (installing / unknown size)
                break;
            case "done":
                b.setContentTitle(a.what() + (a.replacing ? " updated" : " installed"))
                        .setContentText(a.version.isEmpty() ? "Ready" : a.version + " · ready")
                        .setTimeoutAfter(Notices.DONE_TIMEOUT_MS);
                break;
            default:
                b.setContentTitle("Couldn't install " + a.what())
                        .setContentText("We'll try again")
                        .setTimeoutAfter(Notices.FAILED_TIMEOUT_MS);
        }
        getSystemService(NotificationManager.class).notify(notifId, b.build());
    }

    @Override
    public void onDestroy() {
        running = false;
        isCapturing = false;
        stopAllLogcatStreams();
        stopAllShellSessions();
        releaseRemoteWakeLock();
        alarmManager.cancel(pollIntent);
        if (pollReceiver != null) {
            try { unregisterReceiver(pollReceiver); } catch (Exception ignored) {}
            try { unregisterReceiver(notifDismissReceiver); } catch (Exception ignored) {}
        }
        flapHandler.removeCallbacks(flapSettleCheck);
        tempFastHandler.removeCallbacks(tempFastTick);
        if (batteryLog != null) batteryLog.shutdown();
        executor.shutdownNow();
        if (heavyExecutor != null) heavyExecutor.shutdownNow();
        if (wlcWatcher != null) wlcWatcher.shutdownNow();
        if (commandPoller != null) commandPoller.shutdownNow();
        if (wifiScanExecutor != null) wifiScanExecutor.shutdownNow();
        if (appUsageExecutor != null) appUsageExecutor.shutdownNow();
        if (bleExecutor != null) bleExecutor.shutdownNow();
        if (wsClient != null) wsClient.stop();
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        if (packageChangeReceiver != null) {
            try { unregisterReceiver(packageChangeReceiver); } catch (Exception ignored) {}
        }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        }
        if (wifiReceiver != null) {
            try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
            try { unregisterReceiver(bootstrapReceiver); } catch (Exception ignored) {}
        }
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Exception ignored) {}
        }
        if (kioskExitRequestObserver != null) {
            try { getContentResolver().unregisterContentObserver(kioskExitRequestObserver); } catch (Exception ignored) {}
        }
        if (staffPill != null) staffPill.hide();
        if (batteryPill != null) batteryPill.hide();
        relockHandler.removeCallbacks(relockRunnable);
        relockHandler.removeCallbacks(lockDroppedRunnable);
        if (lockTaskListener != null) {
            try { ActivityTaskManager.getService().unregisterTaskStackListener(lockTaskListener); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }
        if (packageChangeReceiver != null) {
            try { unregisterReceiver(packageChangeReceiver); } catch (Exception ignored) {}
            packageChangeReceiver = null;
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
