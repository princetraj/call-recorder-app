package com.office.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RestartReceiver extends BroadcastReceiver {

    private static final String TAG = "RestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Service destroyed - restarting app");

        // Restart the main activity
        Intent activityIntent = new Intent(context, MainActivity.class);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(activityIntent);

        // Restart the foreground service
        Intent serviceIntent = new Intent(context, PersistentService.class);
        context.startForegroundService(serviceIntent);
    }
}
