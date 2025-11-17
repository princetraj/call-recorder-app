package com.hairocraft.dialer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed - starting Hairocraft Dialer");

            // Start the main activity
            Intent activityIntent = new Intent(context, MainActivity.class);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(activityIntent);

            // Only start the service if user is logged in and has permissions
            PrefsManager prefsManager = new PrefsManager(context);
            if (prefsManager.isLoggedIn()) {
                // Check if we have required permissions before starting service
                boolean hasPermissions = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED;

                if (hasPermissions) {
                    // Start the foreground service
                    Intent serviceIntent = new Intent(context, PersistentService.class);
                    context.startForegroundService(serviceIntent);
                    Log.d(TAG, "Service started after boot");
                } else {
                    Log.w(TAG, "Service not started - permissions not granted");
                }
            } else {
                Log.d(TAG, "Service not started - user not logged in");
            }
        }
    }
}
