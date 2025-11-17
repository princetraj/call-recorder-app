package com.hairocraft.dialer.sync;

import android.content.Context;
import android.util.Log;

import com.hairocraft.dialer.ApiService;
import com.hairocraft.dialer.PrefsManager;
import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncManager {
    private static final String TAG = "SyncManager";
    private static SyncManager instance;

    private Context context;
    private AppDatabase database;
    private UploadQueueDao queueDao;
    private ApiService apiService;
    private PrefsManager prefsManager;
    private ExecutorService executorService;
    private com.hairocraft.dialer.RecordingUploader recordingUploader;

    // PHASE 3.1: ContentObserver for immediate recording detection
    private RecordingContentObserver recordingObserver;

    private SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.database = AppDatabase.getInstance(context);
        this.queueDao = database.uploadQueueDao();
        this.apiService = ApiService.getInstance();
        this.prefsManager = new PrefsManager(context);
        this.executorService = Executors.newSingleThreadExecutor();
        this.recordingUploader = new com.hairocraft.dialer.RecordingUploader(context);

        // PHASE 3.1: Initialize ContentObserver for recording detection
        this.recordingObserver = null; // Initialized on demand via startRecordingObserver()

        // PHASE 3.3: Log time information on initialization
        TimeUtils.logTimeInfo();
    }

    public static synchronized SyncManager getInstance(Context context) {
        if (instance == null) {
            instance = new SyncManager(context);
        }
        return instance;
    }

    // REMOVED: queueCallLog() method
    // Call logs are now handled by CallLogUploadWorker via WorkManager
    // This old method was causing duplicate uploads

    /**
     * Add a recording to the upload queue
     * IMPROVED: Uses time window for duplicate detection
     * PHASE 1.1: Now accepts parentUuid to link with call log
     */
    public void queueRecording(int callLogId, String parentUuid, String phoneNumber,
                                long callTimestamp, String recordingPath,
                                String compressedPath, long fileSize) {
        executorService.execute(() -> {
            try {
                // IMPROVED: Check if already queued within ±1 second window
                long timestampStart = callTimestamp - 1000; // -1 second
                long timestampEnd = callTimestamp + 1000;   // +1 second
                UploadQueue existing = queueDao.findRecordingWithinWindow(
                    phoneNumber, timestampStart, timestampEnd
                );

                if (existing == null) {
                    UploadQueue queue = UploadQueue.createRecordingQueue(
                            callLogId, parentUuid, phoneNumber, callTimestamp,
                            recordingPath, compressedPath, fileSize
                    );
                    long id = queueDao.insert(queue);
                    Log.d(TAG, "Queued recording with UUID: " + parentUuid +
                          ", ID: " + id + ", call_log_id=" + callLogId);
                } else {
                    Log.d(TAG, "Recording already queued within time window (ID=" + existing.id +
                          ", Timestamp diff=" + (callTimestamp - existing.callTimestamp) + "ms), skipping");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error queueing recording", e);
            }
        });
    }

    /**
     * Process all pending uploads
     */
    public void syncPendingUploads(SyncCallback callback) {
        executorService.execute(() -> {
            try {
                Log.d(TAG, "syncPendingUploads() called");

                String token = prefsManager.getAuthToken();
                if (token == null || token.isEmpty()) {
                    Log.w(TAG, "No auth token available, skipping sync");
                    if (callback != null) {
                        callback.onComplete(0, 0);
                    }
                    return;
                }

                long currentTime = System.currentTimeMillis();
                Log.d(TAG, "Current time: " + currentTime + ", querying database for pending uploads...");

                List<UploadQueue> pendingUploads = queueDao.getPendingUploads(currentTime);

                Log.d(TAG, "Found " + pendingUploads.size() + " pending uploads ready to sync");

                if (pendingUploads.size() > 0) {
                    for (UploadQueue upload : pendingUploads) {
                        Log.d(TAG, "Pending upload: ID=" + upload.id +
                              ", Type=" + upload.uploadType +
                              ", Status=" + upload.status +
                              ", RetryCount=" + upload.retryCount +
                              ", NextRetryAt=" + upload.nextRetryAt);
                    }
                }

                int successCount = 0;
                int failureCount = 0;

                for (UploadQueue upload : pendingUploads) {
                    // IMPORTANT: Skip call logs - they are now handled by CallLogUploadWorker
                    // This prevents duplicate uploads (SyncManager vs WorkManager)
                    if ("call_log".equals(upload.uploadType)) {
                        Log.d(TAG, "Skipping call_log upload (handled by CallLogUploadWorker): " + upload.id);
                        continue;
                    }

                    // Update status to uploading
                    upload.status = "uploading";
                    queueDao.update(upload);

                    boolean success = processUpload(upload, token);

                    if (success) {
                        successCount++;
                        upload.status = "completed";
                        queueDao.update(upload);
                        Log.d(TAG, "Upload completed: " + upload.id);
                    } else {
                        failureCount++;
                        upload.status = "failed";
                        upload.retryCount++;
                        upload.lastAttemptAt = System.currentTimeMillis();
                        upload.calculateNextRetry();
                        queueDao.update(upload);
                        Log.w(TAG, "Upload failed: " + upload.id + ", retry count: " + upload.retryCount);
                    }
                }

                // Clean up old completed uploads (older than 7 days)
                long sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L);
                queueDao.deleteOldCompleted(sevenDaysAgo);

                Log.d(TAG, "Sync completed: " + successCount + " succeeded, " + failureCount + " failed");

                if (callback != null) {
                    callback.onComplete(successCount, failureCount);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during sync", e);
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    /**
     * Process a single upload from the queue
     * NOTE: Only handles recordings now. Call logs are handled by CallLogUploadWorker.
     */
    private boolean processUpload(UploadQueue upload, String token) {
        try {
            if ("recording".equals(upload.uploadType)) {
                return uploadRecordingSync(upload, token);
            }
            // Call logs should never reach here (filtered out in syncPendingUploads)
            Log.w(TAG, "Unexpected upload type: " + upload.uploadType);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error processing upload: " + upload.id, e);
            upload.errorMessage = e.getMessage();
            return false;
        }
    }

    // REMOVED: uploadCallLogSync() method
    // Call log uploads now handled by CallLogUploadWorker via WorkManager
    // This eliminates duplicate uploads and improves reliability

    // REMOVED: searchAndQueueRecording() deprecated method
    // Recording search now handled exclusively by RecordingSearchWorker

    /**
     * Upload recording synchronously
     */
    private boolean uploadRecordingSync(UploadQueue upload, String token) {
        final boolean[] success = {false};
        final Object lock = new Object();

        try {
            // Determine which file to use (compressed or original)
            String filePath = upload.compressedFilePath != null ?
                    upload.compressedFilePath : upload.recordingFilePath;

            if (filePath == null) {
                Log.e(TAG, "Both compressed and original file paths are null");
                upload.errorMessage = "No file path available";
                return false;
            }

            File recordingFile = new File(filePath);

            // FIXED: Check file existence immediately before upload
            if (!recordingFile.exists()) {
                Log.w(TAG, "Recording file not found: " + recordingFile.getPath());

                // If compressed file missing but original exists, try original
                if (upload.compressedFilePath != null && upload.recordingFilePath != null) {
                    File originalFile = new File(upload.recordingFilePath);
                    if (originalFile.exists()) {
                        Log.d(TAG, "Compressed file missing, using original: " +
                              originalFile.getPath());
                        recordingFile = originalFile;
                    } else {
                        upload.errorMessage = "Recording file deleted";
                        return false;
                    }
                } else {
                    upload.errorMessage = "Recording file deleted";
                    return false;
                }
            }

            // Verify file is readable and has size > 0
            if (!recordingFile.canRead()) {
                Log.e(TAG, "Recording file not readable: " + recordingFile.getPath());
                upload.errorMessage = "File not readable";
                return false;
            }

            if (recordingFile.length() == 0) {
                Log.e(TAG, "Recording file is empty: " + recordingFile.getPath());
                upload.errorMessage = "File is empty";
                return false;
            }

            Log.d(TAG, "Uploading recording file: " + recordingFile.getPath() +
                  " (size: " + recordingFile.length() + " bytes)");

            // Use the stored call_log_id for accurate recording association
            final File finalRecordingFile = recordingFile; // For lambda
            apiService.uploadRecording(
                    token,
                    upload.callLogId, // Use the actual call_log_id from when the call was uploaded
                    finalRecordingFile,
                    upload.callDuration,
                    new ApiService.ApiCallback() {
                        @Override
                        public void onSuccess(String response) {
                            synchronized (lock) {
                                success[0] = true;
                                // Clean up compressed file after successful upload
                                if (upload.compressedFilePath != null) {
                                    File compressedFile = new File(upload.compressedFilePath);
                                    if (compressedFile.exists() && compressedFile.delete()) {
                                        Log.d(TAG, "Deleted compressed file after upload: " +
                                              compressedFile.getName());
                                    }
                                }
                                lock.notify();
                            }
                        }

                        @Override
                        public void onFailure(String error) {
                            synchronized (lock) {
                                upload.errorMessage = error;
                                success[0] = false;
                                lock.notify();
                            }
                        }
                    }
            );

            // Wait for callback (increased to 95s to accommodate ApiService 90s write timeout + buffer)
            synchronized (lock) {
                lock.wait(95000); // 95 second timeout for file upload
            }
        } catch (Exception e) {
            Log.e(TAG, "Error uploading recording", e);
            upload.errorMessage = e.getMessage();
            return false;
        }

        return success[0];
    }

    /**
     * Get count of pending uploads
     */
    public void getPendingCount(CountCallback callback) {
        executorService.execute(() -> {
            try {
                int count = queueDao.getPendingCount();
                callback.onCount(count);
            } catch (Exception e) {
                Log.e(TAG, "Error getting pending count", e);
                callback.onCount(0);
            }
        });
    }

    /**
     * PHASE 3.1: Start monitoring MediaStore for new recordings
     * Call this from your Application class or main activity
     */
    public void startRecordingObserver() {
        if (recordingObserver == null) {
            recordingObserver = new RecordingContentObserver(context,
                new RecordingContentObserver.RecordingDetectedCallback() {
                    @Override
                    public void onRecordingDetected(android.net.Uri recordingUri, long dateAdded) {
                        Log.d(TAG, "Recording detected via ContentObserver: " + recordingUri);
                        // ContentObserver notifies us immediately when a recording is added
                        // The RecordingSearchWorker will handle the actual matching and queueing
                        // This reduces the initial wait time from 5s to near-instant
                    }
                });
            recordingObserver.startObserving();
            Log.i(TAG, "Recording ContentObserver started");
        } else {
            Log.d(TAG, "Recording ContentObserver already running");
        }
    }

    /**
     * PHASE 3.1: Stop monitoring MediaStore
     * Call this when the app is shutting down or user logs out
     */
    public void stopRecordingObserver() {
        if (recordingObserver != null) {
            recordingObserver.stopObserving();
            recordingObserver = null;
            Log.i(TAG, "Recording ContentObserver stopped");
        }
    }

    /**
     * PHASE 3.3: Update server time drift from API response
     * Call this whenever you receive a timestamp from the server
     *
     * @param serverTimestampMs Server's current time in milliseconds
     */
    public void updateServerTimeDrift(long serverTimestampMs) {
        TimeUtils.updateServerTimeDrift(serverTimestampMs);
    }

    /**
     * PHASE 3.3: Log current time information for debugging
     */
    public void logTimeInfo() {
        TimeUtils.logTimeInfo();
    }

    /**
     * Callback interface for sync operations
     */
    public interface SyncCallback {
        void onComplete(int successCount, int failureCount);
        void onError(String error);
    }

    /**
     * Callback interface for count queries
     */
    public interface CountCallback {
        void onCount(int count);
    }
}
