package com.hairocraft.dialer.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "SyncWorker started");

        // Check network connectivity
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No network available, skipping sync");
            return Result.retry();
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] syncSuccess = {false};

        SyncManager syncManager = SyncManager.getInstance(getApplicationContext());
        syncManager.syncPendingUploads(new SyncManager.SyncCallback() {
            @Override
            public void onComplete(int successCount, int failureCount) {
                Log.d(TAG, "Sync completed: " + successCount + " succeeded, " + failureCount + " failed");
                syncSuccess[0] = true;
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Sync error: " + error);
                syncSuccess[0] = false;
                latch.countDown();
            }
        });

        try {
            // Wait for sync to complete (max 5 minutes)
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Log.e(TAG, "Sync interrupted", e);
            return Result.retry();
        }

        return syncSuccess[0] ? Result.success() : Result.retry();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }

        return false;
    }
}
