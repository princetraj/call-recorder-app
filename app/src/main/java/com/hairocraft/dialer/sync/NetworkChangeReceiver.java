package com.hairocraft.dialer.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

/**
 * Receiver that listens for network connectivity changes
 * and triggers immediate sync when network becomes available
 */
public class NetworkChangeReceiver extends BroadcastReceiver {
    private static final String TAG = "NetworkChangeReceiver";
    private static boolean wasDisconnected = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            boolean isConnected = isNetworkAvailable(context);

            Log.d(TAG, "Network state changed. Connected: " + isConnected + ", Was disconnected: " + wasDisconnected);

            // Only trigger sync if we just reconnected after being disconnected
            if (isConnected && wasDisconnected) {
                Log.d(TAG, "Network reconnected! Triggering immediate sync");
                SyncScheduler.triggerImmediateSync(context);
                wasDisconnected = false;
            } else if (!isConnected) {
                Log.d(TAG, "Network disconnected");
                wasDisconnected = true;
            }
        }
    }

    private boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }

        return false;
    }
}
