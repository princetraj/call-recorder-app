package com.hairocraft.dialer.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Data;

import com.hairocraft.dialer.ApiService;
import com.hairocraft.dialer.PrefsManager;
import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PHASE 1.3: WorkManager-based worker for uploading call logs
 * Replaces ExecutorService approach with WorkManager for better reliability
 */
public class CallLogUploadWorker extends Worker {

    private static final String TAG = "CallLogUploadWorker";
    private static final String KEY_UPLOAD_ID = "upload_id";
    private static final String KEY_UUID = "uuid";

    public CallLogUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uuid = getInputData().getString(KEY_UUID);
        Log.d(TAG, "Starting call log upload for UUID: " + uuid);

        Context context = getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(context);
        UploadQueueDao queueDao = db.uploadQueueDao();
        PrefsManager prefsManager = new PrefsManager(context);
        ApiService apiService = ApiService.getInstance();

        // Check if user is logged in
        if (!prefsManager.isLoggedIn()) {
            Log.w(TAG, "User not logged in, failing upload");
            return Result.failure();
        }

        String token = prefsManager.getAuthToken();
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "No auth token, failing upload");
            return Result.failure();
        }

        // Get upload from database
        UploadQueue upload = queueDao.findByUuid(uuid);
        if (upload == null) {
            Log.w(TAG, "Upload not found for UUID: " + uuid);
            return Result.failure();
        }

        // Check if already completed
        if ("completed".equals(upload.status)) {
            Log.d(TAG, "Upload already completed: " + uuid);
            return Result.success();
        }

        // Update status to uploading
        upload.status = "uploading";
        upload.lastAttemptAt = System.currentTimeMillis();
        queueDao.update(upload);

        // Build SIM info JSON
        JSONObject simInfo = new JSONObject();
        try {
            if (upload.simSlot != null) simInfo.put("sim_slot_index", upload.simSlot);
            if (upload.simOperator != null) simInfo.put("sim_name", upload.simOperator);
            if (upload.simNumber != null) simInfo.put("sim_number", upload.simNumber);
        } catch (Exception e) {
            Log.e(TAG, "Error building SIM info", e);
        }

        // Convert timestamp to Laravel format
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String timestamp = sdf.format(new Date(upload.callTimestamp));

        // Upload with idempotency key
        final boolean[] success = {false};
        final int[] callLogId = {0};
        final Object lock = new Object();

        apiService.uploadCallLogWithIdempotency(
                token,
                uuid, // PHASE 1.2: Use UUID as idempotency key
                upload.contactName,
                upload.phoneNumber,
                upload.callType,
                upload.callDuration,
                timestamp,
                simInfo,
                new ApiService.ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        synchronized (lock) {
                            success[0] = true;
                            try {
                                callLogId[0] = Integer.parseInt(response);
                                Log.d(TAG, "Call log uploaded successfully with ID: " + callLogId[0]);
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Could not parse call log ID: " + response);
                            }
                            lock.notify();
                        }
                    }

                    @Override
                    public void onFailure(String error) {
                        synchronized (lock) {
                            success[0] = false;
                            upload.errorMessage = error;
                            Log.e(TAG, "Call log upload failed: " + error);
                            lock.notify();
                        }
                    }
                }
        );

        // Wait for callback
        synchronized (lock) {
            try {
                lock.wait(45000); // 45 second timeout
            } catch (InterruptedException e) {
                Log.e(TAG, "Upload interrupted", e);
                return Result.retry();
            }
        }

        if (success[0]) {
            // Update status to completed
            upload.status = "completed";
            upload.callLogId = callLogId[0];
            queueDao.update(upload);
            Log.d(TAG, "Call log upload completed for UUID: " + uuid);

            // Schedule recording search ONLY if:
            // 1. We have a valid call log ID
            // 2. Call type is NOT missed or rejected (these calls have no recordings)
            // 3. Call duration is greater than 0 (zero duration = no connection = no recording)
            if (callLogId[0] > 0) {
                boolean shouldSearchRecording = true;

                // Skip recording search for missed calls
                if ("missed".equals(upload.callType)) {
                    Log.d(TAG, "Skipping recording search for MISSED call");
                    shouldSearchRecording = false;
                }

                // Skip recording search for rejected calls
                if ("rejected".equals(upload.callType)) {
                    Log.d(TAG, "Skipping recording search for REJECTED call");
                    shouldSearchRecording = false;
                }

                // Skip recording search for zero duration calls
                if (upload.callDuration <= 0) {
                    Log.d(TAG, "Skipping recording search for ZERO DURATION call (duration: " +
                          upload.callDuration + " seconds)");
                    shouldSearchRecording = false;
                }

                // Only schedule recording search for answered calls with duration > 0
                if (shouldSearchRecording) {
                    Log.d(TAG, "Scheduling recording search for " + upload.callType +
                          " call (duration: " + upload.callDuration + " seconds)");
                    com.hairocraft.dialer.sync.RecordingSearchWorker.scheduleSearch(
                        context,
                        callLogId[0],
                        upload.phoneNumber,
                        upload.callTimestamp,
                        upload.callDuration,
                        upload.contactName,
                        uuid
                    );
                } else {
                    Log.i(TAG, "Recording search skipped - Call type: " + upload.callType +
                          ", Duration: " + upload.callDuration + " seconds");
                }
            }

            return Result.success();
        } else {
            // Update status to failed with retry info
            upload.status = "failed";
            upload.retryCount++;
            upload.calculateNextRetry();
            queueDao.update(upload);

            Log.w(TAG, "Call log upload failed, will retry. Attempt: " + upload.retryCount);
            return Result.retry();
        }
    }

    /**
     * Schedule call log upload work
     */
    public static void scheduleUpload(Context context, String uuid) {
        Data inputData = new Data.Builder()
                .putString(KEY_UUID, uuid)
                .build();

        androidx.work.OneTimeWorkRequest uploadWork =
                new androidx.work.OneTimeWorkRequest.Builder(CallLogUploadWorker.class)
                        .setInputData(inputData)
                        .addTag("call_log_upload")
                        .addTag("upload_" + uuid)
                        .build();

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "call_log_" + uuid,
                androidx.work.ExistingWorkPolicy.KEEP,
                uploadWork
        );

        Log.d(TAG, "Scheduled call log upload for UUID: " + uuid);
    }
}
