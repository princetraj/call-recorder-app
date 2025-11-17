package com.hairocraft.dialer.sync;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * PHASE 3.1: ContentObserver for MediaStore recordings
 * Monitors MediaStore.Audio.Media for new recording files
 * Provides immediate notification when recordings are added
 */
public class RecordingContentObserver extends ContentObserver {
    private static final String TAG = "RecordingContentObserver";

    private Context context;
    private RecordingDetectedCallback callback;
    private Set<Long> processedIds; // Track already processed recordings
    private long lastNotificationTime = 0;
    private static final long DEBOUNCE_DELAY = 500; // 500ms debounce to avoid duplicate notifications

    public interface RecordingDetectedCallback {
        void onRecordingDetected(Uri recordingUri, long dateAdded);
    }

    public RecordingContentObserver(Context context, RecordingDetectedCallback callback) {
        super(new Handler(Looper.getMainLooper()));
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.processedIds = new HashSet<>();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);

        // Debounce: Ignore if another notification came within 500ms
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastNotificationTime < DEBOUNCE_DELAY) {
            Log.d(TAG, "Debouncing notification (too soon after previous)");
            return;
        }
        lastNotificationTime = currentTime;

        Log.d(TAG, "MediaStore content changed: " + uri);

        // Check for new audio files
        checkForNewRecordings();
    }

    /**
     * Check MediaStore for newly added audio files
     */
    private void checkForNewRecordings() {
        // Run in background thread to avoid blocking
        new Thread(() -> {
            Cursor cursor = null;
            try {
                // Query MediaStore for recent audio files (added in last 60 seconds)
                long recentThreshold = System.currentTimeMillis() / 1000 - 60; // Last 60 seconds

                String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE
                };

                String selection = MediaStore.Audio.Media.DATE_ADDED + " > ? AND " +
                                 MediaStore.Audio.Media.SIZE + " > ?";
                String[] selectionArgs = {
                    String.valueOf(recentThreshold),
                    "10240" // Files larger than 10KB
                };

                String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

                cursor = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                );

                if (cursor != null && cursor.moveToFirst()) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                    int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);

                    do {
                        long id = cursor.getLong(idColumn);

                        // Skip if already processed
                        if (processedIds.contains(id)) {
                            continue;
                        }

                        String path = cursor.getString(dataColumn);
                        long dateAdded = cursor.getLong(dateAddedColumn);
                        String name = cursor.getString(nameColumn);

                        // Check if this looks like a call recording
                        if (isLikelyCallRecording(path, name)) {
                            Log.d(TAG, "New call recording detected: " + name +
                                  " (added: " + dateAdded + ")");

                            Uri recordingUri = Uri.withAppendedPath(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                String.valueOf(id)
                            );

                            // Mark as processed
                            processedIds.add(id);

                            // Clean up old processed IDs (keep last 100)
                            if (processedIds.size() > 100) {
                                // Remove oldest entries (simple cleanup)
                                Set<Long> newSet = new HashSet<>();
                                int count = 0;
                                for (Long procId : processedIds) {
                                    if (count++ >= 50) { // Keep last 50
                                        newSet.add(procId);
                                    }
                                }
                                processedIds = newSet;
                            }

                            // Notify callback
                            if (callback != null) {
                                callback.onRecordingDetected(recordingUri, dateAdded);
                            }
                        }
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking for new recordings", e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }, "RecordingObserverCheck").start();
    }

    /**
     * Check if a file path/name indicates it's likely a call recording
     */
    private boolean isLikelyCallRecording(String path, String name) {
        if (path == null && name == null) {
            return false;
        }

        String combined = (path != null ? path.toLowerCase() : "") + " " +
                         (name != null ? name.toLowerCase() : "");

        // Check for call recording keywords
        return combined.contains("call") ||
               combined.contains("record") ||
               combined.contains("voice") ||
               combined.contains("rec_") ||
               combined.contains("callrecord") ||
               combined.contains("phonerecord") ||
               combined.contains("dialer");
    }

    /**
     * Start monitoring MediaStore
     */
    public void startObserving() {
        try {
            context.getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                this
            );
            Log.d(TAG, "Started observing MediaStore for new recordings");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register ContentObserver", e);
        }
    }

    /**
     * Stop monitoring MediaStore
     */
    public void stopObserving() {
        try {
            context.getContentResolver().unregisterContentObserver(this);
            Log.d(TAG, "Stopped observing MediaStore");
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister ContentObserver", e);
        }
    }

    /**
     * Clear processed IDs cache
     */
    public void clearProcessedCache() {
        processedIds.clear();
        Log.d(TAG, "Cleared processed recordings cache");
    }
}
