package com.hairocraft.dialer.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Data;
import androidx.work.BackoffPolicy;

import com.hairocraft.dialer.RecordingUploader;
import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * PHASE 1.5: WorkManager-based worker for searching recordings with exponential backoff
 * Retries: 5s, 15s, 45s, 120s, 300s (total ~8 minutes)
 */
public class RecordingSearchWorker extends Worker {

    private static final String TAG = "RecordingSearchWorker";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_CALL_LOG_ID = "call_log_id";
    private static final String KEY_PHONE_NUMBER = "phone_number";
    private static final String KEY_CALL_TIMESTAMP = "call_timestamp";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_ATTEMPT = "attempt";

    // PHASE 1.5: Exponential backoff schedule
    private static final long[] BACKOFF_DELAYS = {
            5000,      // 5 seconds
            15000,     // 15 seconds
            45000,     // 45 seconds
            120000,    // 2 minutes
            300000     // 5 minutes
    };

    public RecordingSearchWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uuid = getInputData().getString(KEY_UUID);
        int callLogId = getInputData().getInt(KEY_CALL_LOG_ID, 0);
        String phoneNumber = getInputData().getString(KEY_PHONE_NUMBER);
        long callTimestamp = getInputData().getLong(KEY_CALL_TIMESTAMP, 0);
        long duration = getInputData().getLong(KEY_DURATION, 0);
        int attempt = getInputData().getInt(KEY_ATTEMPT, 0);

        Log.d(TAG, "Recording search attempt " + (attempt + 1) + "/" + BACKOFF_DELAYS.length +
                " for UUID: " + uuid);

        Context context = getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(context);
        UploadQueueDao queueDao = db.uploadQueueDao();
        RecordingUploader uploader = new RecordingUploader(context);

        // Check if recording already found/uploaded
        UploadQueue existingRecording = queueDao.findRecordingByUuid(uuid);
        if (existingRecording != null && "completed".equals(existingRecording.status)) {
            Log.d(TAG, "Recording already uploaded for UUID: " + uuid);
            return Result.success();
        }

        // Search for recording
        File recordingFile = uploader.findRecording(phoneNumber, callTimestamp, duration);

        if (recordingFile != null) {
            Log.d(TAG, "Recording found: " + recordingFile.getAbsolutePath());

            // Create recording queue entry
            UploadQueue recordingQueue = UploadQueue.createRecordingQueue(
                    callLogId,
                    uuid, // Use same UUID as parent call log
                    phoneNumber,
                    callTimestamp,
                    recordingFile.getAbsolutePath(),
                    null, // No compressed path yet
                    recordingFile.length()
            );

            // Insert into database
            queueDao.insert(recordingQueue);
            Log.d(TAG, "Recording queued for upload with UUID: " + uuid);

            // Schedule upload
            RecordingUploadWorker.scheduleUpload(context, uuid);

            return Result.success();
        } else {
            // Recording not found yet
            if (attempt < BACKOFF_DELAYS.length - 1) {
                // Schedule next retry
                Log.d(TAG, "Recording not found, scheduling retry " + (attempt + 2) +
                        " in " + (BACKOFF_DELAYS[attempt + 1] / 1000) + "s");

                scheduleRetry(context, uuid, callLogId, phoneNumber, callTimestamp,
                        duration, attempt + 1);

                return Result.success(); // Current work succeeded, retry is scheduled
            } else {
                // PHASE 1.5: Mark no_recording_found after all attempts exhausted
                Log.w(TAG, "Recording not found after " + BACKOFF_DELAYS.length +
                        " attempts for UUID: " + uuid);

                // Update call log entry to mark no recording found
                UploadQueue callLog = queueDao.findCallLogByUuid(uuid);
                if (callLog != null) {
                    callLog.noRecordingFound = true;
                    queueDao.update(callLog);
                    Log.d(TAG, "Marked no_recording_found for UUID: " + uuid);
                }

                return Result.success();
            }
        }
    }

    /**
     * Schedule initial recording search
     */
    public static void scheduleSearch(Context context, String uuid, int callLogId,
                                       String phoneNumber, long callTimestamp, long duration) {
        Data inputData = new Data.Builder()
                .putString(KEY_UUID, uuid)
                .putInt(KEY_CALL_LOG_ID, callLogId)
                .putString(KEY_PHONE_NUMBER, phoneNumber)
                .putLong(KEY_CALL_TIMESTAMP, callTimestamp)
                .putLong(KEY_DURATION, duration)
                .putInt(KEY_ATTEMPT, 0)
                .build();

        androidx.work.OneTimeWorkRequest searchWork =
                new androidx.work.OneTimeWorkRequest.Builder(RecordingSearchWorker.class)
                        .setInputData(inputData)
                        .setInitialDelay(BACKOFF_DELAYS[0], TimeUnit.MILLISECONDS)
                        .addTag("recording_search")
                        .addTag("search_" + uuid)
                        .build();

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "recording_search_" + uuid,
                androidx.work.ExistingWorkPolicy.KEEP,
                searchWork
        );

        Log.d(TAG, "Scheduled recording search for UUID: " + uuid +
                " (first attempt in " + (BACKOFF_DELAYS[0] / 1000) + "s)");
    }

    /**
     * Schedule retry with exponential backoff
     */
    private static void scheduleRetry(Context context, String uuid, int callLogId,
                                       String phoneNumber, long callTimestamp,
                                       long duration, int attempt) {
        Data inputData = new Data.Builder()
                .putString(KEY_UUID, uuid)
                .putInt(KEY_CALL_LOG_ID, callLogId)
                .putString(KEY_PHONE_NUMBER, phoneNumber)
                .putLong(KEY_CALL_TIMESTAMP, callTimestamp)
                .putLong(KEY_DURATION, duration)
                .putInt(KEY_ATTEMPT, attempt)
                .build();

        androidx.work.OneTimeWorkRequest retryWork =
                new androidx.work.OneTimeWorkRequest.Builder(RecordingSearchWorker.class)
                        .setInputData(inputData)
                        .setInitialDelay(BACKOFF_DELAYS[attempt], TimeUnit.MILLISECONDS)
                        .addTag("recording_search")
                        .addTag("search_" + uuid)
                        .build();

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "recording_search_" + uuid + "_" + attempt,
                androidx.work.ExistingWorkPolicy.REPLACE, // Replace to update delay
                retryWork
        );
    }
}
