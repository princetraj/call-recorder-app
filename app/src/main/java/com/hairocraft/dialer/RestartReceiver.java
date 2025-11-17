package com.hairocraft.dialer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RestartReceiver extends BroadcastReceiver {

    private static final String TAG = "RestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Service destroyed - restarting service");

        // Only restart the foreground service, not the activity
        // This keeps the app running in background without showing UI
        Intent serviceIntent = new Intent(context, PersistentService.class);
        context.startForegroundService(serviceIntent);

        Log.d(TAG, "PersistentService restarted in background");
    }
}
