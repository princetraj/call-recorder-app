package com.hairocraft.dialer.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface UploadQueueDao {

    @Insert
    long insert(UploadQueue uploadQueue);

    @Update
    void update(UploadQueue uploadQueue);

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

    @Query("SELECT * FROM upload_queue WHERE uploadType = 'call_log' AND phoneNumber = :phoneNumber AND callTimestamp = :timestamp LIMIT 1")
    UploadQueue findCallLog(String phoneNumber, long timestamp);

    @Query("SELECT * FROM upload_queue WHERE uploadType = 'recording' AND phoneNumber = :phoneNumber AND callTimestamp = :timestamp LIMIT 1")
    UploadQueue findRecording(String phoneNumber, long timestamp);
}
