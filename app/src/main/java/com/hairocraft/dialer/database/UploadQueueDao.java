package com.hairocraft.dialer.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface UploadQueueDao {

    @Insert
    long insert(UploadQueue uploadQueue);

    @Update
    void update(UploadQueue uploadQueue);

    // PHASE 1.1: UUID-based queries for idempotency
    @Query("SELECT * FROM upload_queue WHERE localCallUuid = :uuid LIMIT 1")
    UploadQueue findByUuid(String uuid);

    @Query("SELECT * FROM upload_queue WHERE localCallUuid = :uuid AND uploadType = 'call_log' LIMIT 1")
    UploadQueue findCallLogByUuid(String uuid);

    @Query("SELECT * FROM upload_queue WHERE localCallUuid = :uuid AND uploadType = 'recording' LIMIT 1")
    UploadQueue findRecordingByUuid(String uuid);

    @Query("SELECT * FROM upload_queue WHERE status IN ('pending', 'failed') AND nextRetryAt <= :currentTime ORDER BY createdAt ASC")
    List<UploadQueue> getPendingUploads(long currentTime);

    @Query("SELECT * FROM upload_queue WHERE id = :id")
    UploadQueue getById(long id);

    @Query("SELECT * FROM upload_queue WHERE status IN ('pending', 'failed') ORDER BY createdAt ASC")
    List<UploadQueue> getAllPendingAndFailed();

    @Query("UPDATE upload_queue SET status = :status WHERE id = :id")
    void updateStatus(long id, String status);

    @Query("UPDATE upload_queue SET status = :status, lastAttemptAt = :attemptTime, retryCount = :retryCount, nextRetryAt = :nextRetryAt, errorMessage = :error WHERE id = :id")
    void updateFailedAttempt(long id, String status, long attemptTime, int retryCount, long nextRetryAt, String error);

    @Query("DELETE FROM upload_queue WHERE id = :id")
    void delete(long id);

    @Query("DELETE FROM upload_queue WHERE status = 'completed' AND createdAt < :olderThan")
    void deleteOldCompleted(long olderThan);

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status IN ('pending', 'failed')")
    int getPendingCount();

    // IMPROVED: Find call log with time window tolerance (±1 second)
    // This handles clock variations and prevents false duplicates
    @Query("SELECT * FROM upload_queue WHERE uploadType = 'call_log' " +
           "AND phoneNumber = :phoneNumber " +
           "AND callTimestamp >= :timestampStart " +
           "AND callTimestamp <= :timestampEnd " +
           "LIMIT 1")
    UploadQueue findCallLogWithinWindow(String phoneNumber, long timestampStart, long timestampEnd);

    // Legacy method - kept for backwards compatibility
    @Query("SELECT * FROM upload_queue WHERE uploadType = 'call_log' AND phoneNumber = :phoneNumber AND callTimestamp = :timestamp LIMIT 1")
    UploadQueue findCallLog(String phoneNumber, long timestamp);

    // IMPROVED: Find recording with time window tolerance (±1 second)
    @Query("SELECT * FROM upload_queue WHERE uploadType = 'recording' " +
           "AND phoneNumber = :phoneNumber " +
           "AND callTimestamp >= :timestampStart " +
           "AND callTimestamp <= :timestampEnd " +
           "LIMIT 1")
    UploadQueue findRecordingWithinWindow(String phoneNumber, long timestampStart, long timestampEnd);

    // Legacy method - kept for backwards compatibility
    @Query("SELECT * FROM upload_queue WHERE uploadType = 'recording' AND phoneNumber = :phoneNumber AND callTimestamp = :timestamp LIMIT 1")
    UploadQueue findRecording(String phoneNumber, long timestamp);

    // Find recording by parent call UUID (for compression workflow)
    @Query("SELECT * FROM upload_queue WHERE uploadType = 'recording' AND parentCallUuid = :parentUuid LIMIT 1")
    UploadQueue findRecordingByParentUuid(String parentUuid);

    // Update compressed file path for a recording by parent UUID
    @Query("UPDATE upload_queue SET compressedFilePath = :path WHERE parentCallUuid = :parentUuid AND uploadType = 'recording'")
    void updateCompressedPathByParentUuid(String parentUuid, String path);
}
