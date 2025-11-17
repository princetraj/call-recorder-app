package com.hairocraft.dialer.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hairocraft.dialer.RecordingUploader;
import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;
import com.hairocraft.dialer.workers.CompressionWorker;
import com.hairocraft.dialer.workers.QueueRecordingWorker;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * PHASE 3.2: WorkManager Worker for recording search
 * Replaces Thread-based search with proper WorkManager implementation
 * Features:
 * - Exponential backoff retry
 * - Unique work names to prevent duplicates
 * - Proper lifecycle management
 * - Handles call_log_id updates
 */
public class RecordingSearchWorker extends Worker {
    private static final String TAG = "RecordingSearchWorker";

    // Input data keys
    private static final String KEY_CALL_LOG_ID = "call_log_id";
    private static final String KEY_PHONE_NUMBER = "phone_number";
    private static final String KEY_CALL_TIMESTAMP = "call_timestamp";
    private static final String KEY_CALL_DURATION = "call_duration";
    private static final String KEY_CONTACT_NAME = "contact_name";
    private static final String KEY_LOCAL_UUID = "local_uuid";
    private static final String KEY_ATTEMPT_NUMBER = "attempt_number";

    public RecordingSearchWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Get input data
        int callLogId = getInputData().getInt(KEY_CALL_LOG_ID, 0);
        String phoneNumber = getInputData().getString(KEY_PHONE_NUMBER);
        long callTimestamp = getInputData().getLong(KEY_CALL_TIMESTAMP, 0);
        long callDuration = getInputData().getLong(KEY_CALL_DURATION, 0);
        String contactName = getInputData().getString(KEY_CONTACT_NAME);
        String localUuid = getInputData().getString(KEY_LOCAL_UUID);
        int attemptNumber = getInputData().getInt(KEY_ATTEMPT_NUMBER, 1);

        Log.d(TAG, "Starting recording search (attempt " + attemptNumber + "/4) for " +
              "call_log_id=" + callLogId + ", uuid=" + localUuid);

        try {
            // Initialize components
            RecordingUploader recordingUploader = new RecordingUploader(getApplicationContext());
            AppDatabase database = AppDatabase.getInstance(getApplicationContext());
            UploadQueueDao queueDao = database.uploadQueueDao();

            // Search for recording
            File recordingFile = recordingUploader.findRecording(
                phoneNumber, callTimestamp, callDuration, contactName
            );

            if (recordingFile != null) {
                Log.d(TAG, "Recording found: " + recordingFile.getAbsolutePath());

                // Get parent call log's UUID
                String parentUuid = localUuid;
                if (parentUuid == null) {
                    // Fallback: Try to get from database
                    try {
                        UploadQueue callLog = queueDao.getById(callLogId);
                        if (callLog != null) {
                            parentUuid = callLog.localCallUuid;
                        } else {
                            parentUuid = java.util.UUID.randomUUID().toString();
                            Log.w(TAG, "Call log not found, generated new UUID: " + parentUuid);
                        }
                    } catch (Exception e) {
                        parentUuid = java.util.UUID.randomUUID().toString();
                        Log.e(TAG, "Error getting UUID, generated new: " + parentUuid, e);
                    }
                }

                // COMPRESSION WORKFLOW: Create DB entry first, then compress, then queue
                // Create DB entry FIRST so CompressionWorker can update it with compressed path
                try {
                    SyncManager.getInstance(getApplicationContext()).queueRecording(
                        callLogId,
                        parentUuid,
                        phoneNumber,
                        callTimestamp,
                        recordingFile.getAbsolutePath(),
                        null, // No compressed path yet
                        recordingFile.length()
                    );
                    Log.d(TAG, "Created DB entry for recording before compression");
                } catch (Exception e) {
                    Log.e(TAG, "Error creating DB entry", e);
                }

                // Build input data for CompressionWorker
                Data compressionInput = new Data.Builder()
                    .putString(CompressionWorker.KEY_INPUT_PATH, recordingFile.getAbsolutePath())
                    .putString(CompressionWorker.KEY_CALLLOG_ID, String.valueOf(callLogId))
                    .putString(CompressionWorker.KEY_PARENT_UUID, parentUuid)
                    .putString(CompressionWorker.KEY_PHONE_NUMBER, phoneNumber)
                    .putInt(CompressionWorker.KEY_DURATION_SEC, (int) (callDuration / 1000))
                    .putLong(CompressionWorker.KEY_TIMESTAMP_MS, callTimestamp)
                    .build();

                // Build input data for QueueRecordingWorker
                Data queueInput = new Data.Builder()
                    .putString(QueueRecordingWorker.KEY_RECORDING_PATH, recordingFile.getAbsolutePath())
                    .putInt(QueueRecordingWorker.KEY_CALLLOG_ID, callLogId)
                    .putString(QueueRecordingWorker.KEY_PARENT_UUID, parentUuid)
                    .putString(QueueRecordingWorker.KEY_PHONE_NUMBER, phoneNumber)
                    .putLong(QueueRecordingWorker.KEY_CALL_TIMESTAMP, callTimestamp)
                    .putLong(QueueRecordingWorker.KEY_FILE_SIZE, recordingFile.length())
                    .build();

                // Create work requests
                OneTimeWorkRequest compressionRequest = new OneTimeWorkRequest.Builder(CompressionWorker.class)
                    .setInputData(compressionInput)
                    .build();

                OneTimeWorkRequest queueRequest = new OneTimeWorkRequest.Builder(QueueRecordingWorker.class)
                    .setInputData(queueInput)
                    .build();

                // Chain: Compression -> Queue (queue runs even if compression fails)
                // Using unique work name based on parentUuid to prevent duplicates
                String workChainName = "RecordingCompress-" + parentUuid;

                WorkManager.getInstance(getApplicationContext())
                    .beginUniqueWork(
                        workChainName,
                        ExistingWorkPolicy.KEEP, // Don't replace if already running
                        compressionRequest
                    )
                    .then(queueRequest)
                    .enqueue();

                Log.d(TAG, "Scheduled compression and queue workflow for parentUuid: " + parentUuid);
                return Result.success();

            } else if (attemptNumber >= 4) {
                // All retries exhausted, mark as no recording found
                Log.w(TAG, "No recording found after " + attemptNumber + " attempts");

                try {
                    // Update the call log entry to mark no recording found
                    UploadQueue callLog = queueDao.getById(callLogId);
                    if (callLog != null) {
                        callLog.noRecordingFound = true;
                        queueDao.update(callLog);
                        Log.d(TAG, "Marked call_log_id=" + callLogId + " as no_recording_found");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating no_recording_found flag", e);
                }

                return Result.success(); // Don't retry anymore

            } else {
                // Recording not found yet, will retry automatically via WorkManager
                Log.d(TAG, "Recording not found, WorkManager will retry with exponential backoff");
                return Result.retry();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during recording search", e);

            // Retry on error unless we've exhausted attempts
            if (attemptNumber >= 4) {
                return Result.failure();
            } else {
                return Result.retry();
            }
        }
    }

    /**
     * Schedule a recording search with exponential backoff
     * PHASE 3.2: Uses unique work name to prevent duplicates
     */
    public static void scheduleSearch(Context context, int callLogId, String phoneNumber,
                                     long callTimestamp, long callDuration,
                                     String contactName, String localUuid) {
        // Create unique work name using UUID
        String workName = "RecordingSearch-" + localUuid;

        Log.d(TAG, "Scheduling recording search with work name: " + workName);

        // Build input data
        Data inputData = new Data.Builder()
            .putInt(KEY_CALL_LOG_ID, callLogId)
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .putLong(KEY_CALL_TIMESTAMP, callTimestamp)
            .putLong(KEY_CALL_DURATION, callDuration)
            .putString(KEY_CONTACT_NAME, contactName)
            .putString(KEY_LOCAL_UUID, localUuid)
            .putInt(KEY_ATTEMPT_NUMBER, 1)
            .build();

        // Create work request with exponential backoff
        // Initial delay: 5s, then exponential backoff: 10s, 20s, 40s
        OneTimeWorkRequest searchRequest = new OneTimeWorkRequest.Builder(RecordingSearchWorker.class)
            .setInputData(inputData)
            .setInitialDelay(5, TimeUnit.SECONDS) // Wait 5s before first attempt
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10, // Initial backoff: 10 seconds
                TimeUnit.SECONDS
            )
            .build();

        // Enqueue with unique work name (KEEP policy prevents duplicates)
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP, // Don't replace if already running
            searchRequest
        );

        Log.d(TAG, "Recording search scheduled for call_log_id=" + callLogId +
              ", uuid=" + localUuid);
    }

    /**
     * Update the attempt number for retry tracking
     */
    private Data buildRetryData(int newAttemptNumber) {
        return new Data.Builder()
            .putInt(KEY_CALL_LOG_ID, getInputData().getInt(KEY_CALL_LOG_ID, 0))
            .putString(KEY_PHONE_NUMBER, getInputData().getString(KEY_PHONE_NUMBER))
            .putLong(KEY_CALL_TIMESTAMP, getInputData().getLong(KEY_CALL_TIMESTAMP, 0))
            .putLong(KEY_CALL_DURATION, getInputData().getLong(KEY_CALL_DURATION, 0))
            .putString(KEY_CONTACT_NAME, getInputData().getString(KEY_CONTACT_NAME))
            .putString(KEY_LOCAL_UUID, getInputData().getString(KEY_LOCAL_UUID))
            .putInt(KEY_ATTEMPT_NUMBER, newAttemptNumber)
            .build();
    }
}
