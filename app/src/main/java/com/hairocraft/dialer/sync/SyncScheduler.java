package com.hairocraft.dialer.sync;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class SyncScheduler {
    private static final String TAG = "SyncScheduler";
    private static final String SYNC_WORK_NAME = "upload_sync_work";

    /**
     * Schedule periodic sync work
     * Runs every 15 minutes when network is available
     */
    public static void schedulePeriodicSync(Context context) {
        Log.d(TAG, "Scheduling periodic sync");

        // Set constraints - only run when network is available
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // Create periodic work request - every 15 minutes
        PeriodicWorkRequest syncWorkRequest = new PeriodicWorkRequest.Builder(
                SyncWorker.class,
                15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        // Schedule the work
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncWorkRequest
        );

        Log.d(TAG, "Periodic sync scheduled successfully");
    }

    /**
     * Trigger immediate sync (one-time work)
     */
    public static void triggerImmediateSync(Context context) {
        Log.d(TAG, "Triggering immediate sync");

        SyncManager syncManager = SyncManager.getInstance(context);
        syncManager.syncPendingUploads(new SyncManager.SyncCallback() {
            @Override
            public void onComplete(int successCount, int failureCount) {
                Log.d(TAG, "Immediate sync completed: " + successCount + " succeeded, " + failureCount + " failed");
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Immediate sync error: " + error);
            }
        });
    }

    /**
     * Cancel all scheduled sync work
     */
    public static void cancelSync(Context context) {
        Log.d(TAG, "Cancelling sync");
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME);
    }
}
