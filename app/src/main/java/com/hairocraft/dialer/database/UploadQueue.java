package com.hairocraft.dialer.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "upload_queue")
public class UploadQueue {

    @PrimaryKey(autoGenerate = true)
    public long id;

    // Type of upload: "call_log" or "recording"
    public String uploadType;

    // For call logs
    public String phoneNumber;
    public String callType; // incoming, outgoing, missed, rejected
    public long callDuration;
    public long callTimestamp;
    public String contactName;
    public String simSlot;
    public String simOperator;
    public String simNumber;

    // For recordings
    public String recordingFilePath;
    public String compressedFilePath;
    public long fileSize;

    // Upload tracking
    public String status; // pending, uploading, failed, completed
    public int retryCount;
    public long createdAt;
    public long lastAttemptAt;
    public long nextRetryAt;
    public String errorMessage;

    // Constructor for call log
    public static UploadQueue createCallLogQueue(String phoneNumber, String callType, long duration,
                                                   long timestamp, String contactName, String simSlot,
                                                   String simOperator, String simNumber) {
        UploadQueue queue = new UploadQueue();
        queue.uploadType = "call_log";
        queue.phoneNumber = phoneNumber;
        queue.callType = callType;
        queue.callDuration = duration;
        queue.callTimestamp = timestamp;
        queue.contactName = contactName;
        queue.simSlot = simSlot;
        queue.simOperator = simOperator;
        queue.simNumber = simNumber;
        queue.status = "pending";
        queue.retryCount = 0;
        queue.createdAt = System.currentTimeMillis();
        queue.lastAttemptAt = 0;
        queue.nextRetryAt = System.currentTimeMillis();
        return queue;
    }

    // Constructor for recording
    public static UploadQueue createRecordingQueue(String phoneNumber, long callTimestamp,
                                                     String recordingPath, String compressedPath,
                                                     long fileSize) {
        UploadQueue queue = new UploadQueue();
        queue.uploadType = "recording";
        queue.phoneNumber = phoneNumber;
        queue.callTimestamp = callTimestamp;
        queue.recordingFilePath = recordingPath;
        queue.compressedFilePath = compressedPath;
        queue.fileSize = fileSize;
        queue.status = "pending";
        queue.retryCount = 0;
        queue.createdAt = System.currentTimeMillis();
        queue.lastAttemptAt = 0;
        queue.nextRetryAt = System.currentTimeMillis();
        return queue;
    }

    // Calculate next retry time with exponential backoff
    public void calculateNextRetry() {
        // Exponential backoff: 1min, 2min, 4min, 8min, 16min, 30min, 1hr, 2hr, 4hr, 24hr
        long[] backoffIntervals = {
            60 * 1000,           // 1 minute
            2 * 60 * 1000,       // 2 minutes
            4 * 60 * 1000,       // 4 minutes
            8 * 60 * 1000,       // 8 minutes
            16 * 60 * 1000,      // 16 minutes
            30 * 60 * 1000,      // 30 minutes
            60 * 60 * 1000,      // 1 hour
            2 * 60 * 60 * 1000,  // 2 hours
            4 * 60 * 60 * 1000,  // 4 hours
            24 * 60 * 60 * 1000  // 24 hours
        };

        int index = Math.min(retryCount, backoffIntervals.length - 1);
        nextRetryAt = System.currentTimeMillis() + backoffIntervals[index];
    }
}
