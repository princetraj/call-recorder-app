package com.hairocraft.dialer.sync;

import android.content.Context;
import android.util.Log;

import com.hairocraft.dialer.ApiService;
import com.hairocraft.dialer.PrefsManager;
import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;

import org.json.JSONObject;

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

    private SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.database = AppDatabase.getInstance(context);
        this.queueDao = database.uploadQueueDao();
        this.apiService = ApiService.getInstance();
        this.prefsManager = new PrefsManager(context);
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public static synchronized SyncManager getInstance(Context context) {
        if (instance == null) {
            instance = new SyncManager(context);
        }
        return instance;
    }

    /**
     * Add a call log to the upload queue
     */
    public void queueCallLog(String phoneNumber, String callType, long duration, long timestamp,
                              String contactName, String simSlot, String simOperator, String simNumber) {
        Log.d(TAG, "queueCallLog() called for: " + phoneNumber + ", timestamp=" + timestamp);
        executorService.execute(() -> {
            try {
                // Check if already queued
                UploadQueue existing = queueDao.findCallLog(phoneNumber, timestamp);
                if (existing == null) {
                    UploadQueue queue = UploadQueue.createCallLogQueue(
                            phoneNumber, callType, duration, timestamp,
                            contactName, simSlot, simOperator, simNumber
                    );
                    long id = queueDao.insert(queue);
                    Log.d(TAG, "Successfully queued call log with ID: " + id +
                          ", Status=" + queue.status +
                          ", NextRetryAt=" + queue.nextRetryAt);

                    // Verify it was inserted
                    int totalPending = queueDao.getPendingCount();
                    Log.d(TAG, "Total pending/failed uploads in database: " + totalPending);
                } else {
                    Log.d(TAG, "Call log already queued (ID=" + existing.id +
                          ", Status=" + existing.status + "), skipping duplicate");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error queueing call log", e);
                e.printStackTrace();
            }
        });
    }

    /**
     * Add a recording to the upload queue
     */
    public void queueRecording(String phoneNumber, long callTimestamp, String recordingPath,
                                String compressedPath, long fileSize) {
        executorService.execute(() -> {
            try {
                // Check if already queued
                UploadQueue existing = queueDao.findRecording(phoneNumber, callTimestamp);
                if (existing == null) {
                    UploadQueue queue = UploadQueue.createRecordingQueue(
                            phoneNumber, callTimestamp, recordingPath, compressedPath, fileSize
                    );
                    long id = queueDao.insert(queue);
                    Log.d(TAG, "Queued recording with ID: " + id);
                } else {
                    Log.d(TAG, "Recording already queued, skipping");
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
     */
    private boolean processUpload(UploadQueue upload, String token) {
        try {
            if ("call_log".equals(upload.uploadType)) {
                return uploadCallLogSync(upload, token);
            } else if ("recording".equals(upload.uploadType)) {
                return uploadRecordingSync(upload, token);
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error processing upload: " + upload.id, e);
            upload.errorMessage = e.getMessage();
            return false;
        }
    }

    /**
     * Upload call log synchronously
     */
    private boolean uploadCallLogSync(UploadQueue upload, String token) {
        final boolean[] success = {false};
        final Object lock = new Object();

        try {
            // Build SIM info JSON
            JSONObject simInfo = new JSONObject();
            if (upload.simSlot != null) simInfo.put("sim_slot_index", upload.simSlot);
            if (upload.simOperator != null) simInfo.put("sim_name", upload.simOperator);
            if (upload.simNumber != null) simInfo.put("sim_number", upload.simNumber);

            // Convert timestamp to ISO format
            String timestamp = String.valueOf(upload.callTimestamp);

            apiService.uploadCallLog(
                    token,
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

            // Wait for callback
            synchronized (lock) {
                lock.wait(35000); // 35 second timeout
            }
        } catch (Exception e) {
            Log.e(TAG, "Error uploading call log", e);
            upload.errorMessage = e.getMessage();
            return false;
        }

        return success[0];
    }

    /**
     * Upload recording synchronously
     */
    private boolean uploadRecordingSync(UploadQueue upload, String token) {
        final boolean[] success = {false};
        final Object lock = new Object();

        try {
            // Check if file still exists
            File recordingFile = new File(upload.compressedFilePath != null ?
                    upload.compressedFilePath : upload.recordingFilePath);

            if (!recordingFile.exists()) {
                Log.w(TAG, "Recording file not found: " + recordingFile.getPath());
                upload.errorMessage = "File not found";
                return false;
            }

            // For recordings, we need the call log ID
            // Since we may not have it, we'll need to modify the API to accept phone number and timestamp
            // For now, we'll use a placeholder ID of 0 and the server should handle it
            apiService.uploadRecording(
                    token,
                    0, // Placeholder - server should match by phone number and timestamp
                    recordingFile,
                    upload.callDuration,
                    new ApiService.ApiCallback() {
                        @Override
                        public void onSuccess(String response) {
                            synchronized (lock) {
                                success[0] = true;
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

            // Wait for callback
            synchronized (lock) {
                lock.wait(60000); // 60 second timeout for file upload
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
