package com.aioapp.mdm;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.json.JSONObject;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Log.d(TAG, "onReceive: " + intent.getAction());

        // MY_PACKAGE_REPLACED arrives after this app updates itself: the install
        // force-stopped the old process, which START_STICKY does not survive, so the
        // service has to be started again or the client is gone until the next boot.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.i(TAG, "package replaced — restarting MDM service");
            try {
                context.startForegroundService(new Intent(context, MdmService.class));
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart MdmService after update: " + e.getMessage(), e);
            }
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            // Factory test boot: stay entirely out of the way of the QMMI test app. MdmService is
            // only ever started from here, so this also keeps check-ins, remote commands and the
            // kiosk watchdog off for the whole factory session.
            if (KioskManager.isFactoryTestBoot()) {
                Log.i(TAG, "Factory test boot (ffbm-02) - MDM service and kiosk not started");
                return;
            }
            Log.i(TAG, "Boot completed - starting MDM service");
            try {
                Intent serviceIntent = new Intent(context, MdmService.class);
                context.startForegroundService(serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MdmService: " + e.getMessage(), e);
            }

            // loadConfig + apply are both inside the try: on LOCKED_BOOT_COMPLETED (Direct Boot)
            // loadConfig now reads device-protected storage, but keep the whole block guarded so
            // any failure here can never abort the boot handler (which also starts the service).
            try {
                // Offline exit persists across reboot: if the device was exited from kiosk, the
                // saved config is re-applied but KioskManager.apply honours the exited guard and
                // does NOT re-lock. The guard clears only when the server acks the exit (which
                // also flips the saved config to kiosk-off), so a reboot never traps the device
                // back into kiosk.
                JSONObject savedConfig = KioskManager.loadConfig(context);
                if (savedConfig != null) {
                    DevicePolicyManager dpm = (DevicePolicyManager)
                            context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                    ComponentName admin = new ComponentName(context, MdmAdminReceiver.class);
                    KioskManager.apply(context, dpm, admin, savedConfig);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply kiosk config on boot: " + e.getMessage(), e);
            }
        }
    }
}
