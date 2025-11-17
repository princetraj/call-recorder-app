package com.hairocraft.dialer.workers;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;
import com.hairocraft.dialer.sync.SyncManager;
import com.hairocraft.dialer.sync.SyncScheduler;

import java.io.File;

/**
 * WorkManager worker for queueing recordings for upload
 * Runs after CompressionWorker completes successfully
 * Reads compressed path from DB and triggers immediate sync
 */
public class QueueRecordingWorker extends Worker {
    private static final String TAG = "QueueRecordingWorker";

    // Input data keys
    public static final String KEY_RECORDING_PATH = "recording_path";
    public static final String KEY_CALLLOG_ID = "calllog_id";
    public static final String KEY_PARENT_UUID = "parent_uuid";
    public static final String KEY_PHONE_NUMBER = "phone_number";
    public static final String KEY_CALL_TIMESTAMP = "call_timestamp";
    public static final String KEY_FILE_SIZE = "file_size";

    public QueueRecordingWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String recordingPath = getInputData().getString(KEY_RECORDING_PATH);
        int callLogId = getInputData().getInt(KEY_CALLLOG_ID, 0);
        String parentUuid = getInputData().getString(KEY_PARENT_UUID);
        String phoneNumber = getInputData().getString(KEY_PHONE_NUMBER);
        long callTimestamp = getInputData().getLong(KEY_CALL_TIMESTAMP, 0);
        long fileSize = getInputData().getLong(KEY_FILE_SIZE, 0);

        Log.d(TAG, "Queueing recording for upload - parentUuid: " + parentUuid);

        if (TextUtils.isEmpty(recordingPath) || TextUtils.isEmpty(parentUuid)) {
            Log.e(TAG, "Missing required parameters");
            return Result.failure();
        }

        try {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            UploadQueueDao dao = db.uploadQueueDao();

            // DB entry should already exist (created by RecordingSearchWorker)
            // CompressionWorker may have updated it with compressedFilePath
            UploadQueue upload = dao.findRecordingByParentUuid(parentUuid);

            if (upload == null) {
                Log.e(TAG, "Upload entry not found for parentUuid: " + parentUuid + " - this should not happen!");
                return Result.failure();
            }

            // Check what files we have
            File recordingFile = new File(recordingPath);
            boolean hasRecording = recordingFile.exists();

            String compressedPath = upload.compressedFilePath;
            File compressedFile = compressedPath != null ? new File(compressedPath) : null;
            boolean hasCompressed = compressedFile != null && compressedFile.exists();

            if (!hasRecording && !hasCompressed) {
                Log.e(TAG, "Neither original nor compressed file exists");
                return Result.failure();
            }

            // Log what we're using
            if (hasCompressed) {
                Log.d(TAG, "Using compressed file: " + compressedFile.length() + " bytes (original: " + recordingFile.length() + " bytes)");
            } else {
                Log.d(TAG, "Using original file: " + recordingFile.length() + " bytes (no compression or compression failed)");
            }

            // Ensure upload entry is ready for sync
            upload.status = "pending";
            upload.nextRetryAt = System.currentTimeMillis();
            dao.update(upload);
            Log.d(TAG, "Updated upload entry to pending status");

            // Trigger immediate sync to start uploading
            SyncScheduler.triggerImmediateSync(getApplicationContext());
            Log.d(TAG, "Triggered immediate sync for recording upload");

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Error queueing recording for upload", e);
            return Result.failure();
        }
    }
}
