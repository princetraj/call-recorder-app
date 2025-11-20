package com.hairocraft.dialer.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;

import com.hairocraft.dialer.ApiService;
import com.hairocraft.dialer.AudioCompressor;
import com.hairocraft.dialer.PrefsManager;
import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * PHASE 1.3: WorkManager-based worker for uploading recordings
 * PHASE 4.3: Includes checksum calculation
 * PHASE 4.5: Keeps compressed file until confirmed
 */
public class RecordingUploadWorker extends Worker {

    private static final String TAG = "RecordingUploadWorker";
    private static final String KEY_UUID = "uuid";
    private static final String CHANNEL_ID = "recording_upload";

    public RecordingUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uuid = getInputData().getString(KEY_UUID);
        Log.d(TAG, "Starting recording upload for UUID: " + uuid);

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
        UploadQueue upload = queueDao.findRecordingByUuid(uuid);
        if (upload == null) {
            Log.w(TAG, "Recording upload not found for UUID: " + uuid);
            return Result.failure();
        }

        // Check if already completed
        if ("completed".equals(upload.status)) {
            Log.d(TAG, "Recording already uploaded: " + uuid);
            return Result.success();
        }

        // Update status to uploading
        upload.status = "uploading";
        upload.lastAttemptAt = System.currentTimeMillis();
        queueDao.update(upload);

        // Determine file to upload (compressed or original)
        String filePath = upload.compressedFilePath != null ?
                upload.compressedFilePath : upload.recordingFilePath;

        if (filePath == null) {
            Log.e(TAG, "No file path available");
            upload.status = "failed";
            upload.errorMessage = "No file path";
            queueDao.update(upload);
            return Result.failure();
        }

        File recordingFile = new File(filePath);

        // Check if file exists
        if (!recordingFile.exists()) {
            // Try original if compressed is missing
            if (upload.compressedFilePath != null && upload.recordingFilePath != null) {
                File originalFile = new File(upload.recordingFilePath);
                if (originalFile.exists()) {
                    recordingFile = originalFile;
                    Log.d(TAG, "Compressed file missing, using original");
                } else {
                    Log.e(TAG, "Both compressed and original files missing");
                    upload.status = "failed";
                    upload.errorMessage = "File deleted";
                    queueDao.update(upload);
                    return Result.failure();
                }
            } else {
                Log.e(TAG, "Recording file not found: " + filePath);
                upload.status = "failed";
                upload.errorMessage = "File not found";
                queueDao.update(upload);
                return Result.failure();
            }
        }

        // Verify file is readable
        if (!recordingFile.canRead() || recordingFile.length() == 0) {
            Log.e(TAG, "File not readable or empty: " + recordingFile.getPath());
            upload.status = "failed";
            upload.errorMessage = "File not readable";
            queueDao.update(upload);
            return Result.failure();
        }

        // PHASE 4.3: Calculate checksum if not already done
        if (upload.checksum == null || upload.checksum.isEmpty()) {
            try {
                upload.checksum = calculateMD5(recordingFile);
                queueDao.update(upload);
                Log.d(TAG, "Calculated checksum: " + upload.checksum);
            } catch (Exception e) {
                Log.w(TAG, "Failed to calculate checksum", e);
            }
        }

        // Compress if not already compressed
        final File fileToUpload;
        if (upload.compressedFilePath == null && upload.recordingFilePath != null) {
            File compressed = compressRecording(recordingFile, upload);
            if (compressed != null) {
                fileToUpload = compressed;
                upload.compressedFilePath = compressed.getAbsolutePath();
                queueDao.update(upload);
            } else {
                fileToUpload = recordingFile;
            }
        } else {
            fileToUpload = recordingFile;
        }

        Log.d(TAG, "Uploading file: " + fileToUpload.getPath() +
                " (size: " + fileToUpload.length() + " bytes)");

        // Upload with idempotency key
        final boolean[] success = {false};
        final Object lock = new Object();

        // PHASE 1.2: Use parent call UUID for idempotency (one recording per call)
        String idempotencyKey = "rec_" + (upload.parentCallUuid != null ? upload.parentCallUuid : uuid);

        apiService.uploadRecordingWithIdempotency(
                token,
                idempotencyKey, // PHASE 1.2: Idempotency key links to parent call
                upload.checksum, // PHASE 4.3: Send checksum
                upload.callLogId,
                fileToUpload,
                upload.callDuration,
                new ApiService.ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        synchronized (lock) {
                            success[0] = true;
                            Log.d(TAG, "Recording uploaded successfully");
                            lock.notify();
                        }
                    }

                    @Override
                    public void onFailure(String error) {
                        synchronized (lock) {
                            success[0] = false;
                            upload.errorMessage = error;
                            Log.e(TAG, "Recording upload failed: " + error);
                            lock.notify();
                        }
                    }
                }
        );

        // Wait for callback
        synchronized (lock) {
            try {
                lock.wait(95000); // 95 second timeout for large files
            } catch (InterruptedException e) {
                Log.e(TAG, "Upload interrupted", e);
                return Result.retry();
            }
        }

        if (success[0]) {
            // PHASE 4.5: Delete compressed file only after confirmed success
            if (upload.compressedFilePath != null) {
                File compressedFile = new File(upload.compressedFilePath);
                if (compressedFile.exists() && compressedFile.delete()) {
                    Log.d(TAG, "Deleted compressed file after successful upload");
                }
            }

            // Update status to completed
            upload.status = "completed";
            queueDao.update(upload);
            Log.d(TAG, "Recording upload completed for UUID: " + uuid);

            return Result.success();
        } else {
            // Update status to failed with retry info
            upload.status = "failed";
            upload.retryCount++;
            upload.calculateNextRetry();
            queueDao.update(upload);

            // PHASE 4.5: Keep compressed file for retry
            Log.w(TAG, "Recording upload failed, will retry. Attempt: " + upload.retryCount);
            return Result.retry();
        }
    }

    /**
     * Compress recording file
     */
    private File compressRecording(File original, UploadQueue upload) {
        try {
            Context context = getApplicationContext();
            File compressedDir = new File(context.getFilesDir(), "compressed_recordings");
            if (!compressedDir.exists()) {
                compressedDir.mkdirs();
            }

            String cleanNumber = upload.phoneNumber != null ?
                    upload.phoneNumber.replaceAll("[^0-9]", "") : "unknown";
            String fileName = String.format("rec_%s_%d_%d.m4a",
                    cleanNumber, upload.callTimestamp, upload.callLogId);
            File compressedFile = new File(compressedDir, fileName);

            boolean success = AudioCompressor.compressAudio(original, compressedFile);
            if (success && compressedFile.exists()) {
                long originalSize = original.length();
                long compressedSize = compressedFile.length();
                double reduction = (1 - (double)compressedSize / originalSize) * 100;
                Log.d(TAG, String.format("Compression successful! Reduced by %.1f%% (%d -> %d bytes)",
                        reduction, originalSize, compressedSize));
                return compressedFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "Compression failed", e);
        }
        return null;
    }

    /**
     * PHASE 4.3: Calculate MD5 checksum of file
     * Fixed: Use try-with-resources to prevent resource leak
     */
    private String calculateMD5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");

        // Try-with-resources automatically closes FileInputStream even on exception
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Schedule recording upload work
     */
    public static void scheduleUpload(Context context, String uuid) {
        Data inputData = new Data.Builder()
                .putString(KEY_UUID, uuid)
                .build();

        androidx.work.OneTimeWorkRequest uploadWork =
                new androidx.work.OneTimeWorkRequest.Builder(RecordingUploadWorker.class)
                        .setInputData(inputData)
                        .addTag("recording_upload")
                        .addTag("upload_rec_" + uuid)
                        .build();

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "recording_upload_" + uuid,
                androidx.work.ExistingWorkPolicy.KEEP,
                uploadWork
        );

        Log.d(TAG, "Scheduled recording upload for UUID: " + uuid);
    }
}
