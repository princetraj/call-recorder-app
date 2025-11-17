package com.hairocraft.dialer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaStore-based recording finder for better compatibility with Android 10+
 * Uses ContentResolver to query audio files instead of direct file system access
 *
 * PHASE 2.2: MediaStore Integration
 */
public class MediaStoreRecordingFinder {
    private static final String TAG = "MediaStoreFinder";

    private Context context;

    // Columns to query from MediaStore
    private static final String[] PROJECTION = {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATA,              // File path
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_ADDED,        // Unix timestamp (seconds)
        MediaStore.Audio.Media.DATE_MODIFIED,     // Unix timestamp (seconds)
        MediaStore.Audio.Media.DURATION,          // Duration in milliseconds
        MediaStore.Audio.Media.MIME_TYPE
    };

    public MediaStoreRecordingFinder(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Recording candidate from MediaStore with metadata
     */
    public static class RecordingCandidate {
        public File file;
        public String displayName;
        public long size;
        public long dateAdded;      // Unix timestamp in milliseconds
        public long dateModified;   // Unix timestamp in milliseconds
        public long duration;       // Duration in milliseconds (from MediaStore)
        public int score;

        public RecordingCandidate(File file, String displayName, long size,
                                 long dateAdded, long dateModified, long duration) {
            this.file = file;
            this.displayName = displayName;
            this.size = size;
            this.dateAdded = dateAdded;
            this.dateModified = dateModified;
            this.duration = duration;
            this.score = 0;
        }

        @Override
        public String toString() {
            return "RecordingCandidate{" +
                    "file=" + file.getName() +
                    ", size=" + size +
                    ", dateAdded=" + dateAdded +
                    ", dateModified=" + dateModified +
                    ", duration=" + duration +
                    ", score=" + score +
                    '}';
        }
    }

    /**
     * Find recording candidates using MediaStore
     *
     * @param phoneNumber Phone number to search for
     * @param callTimestamp Call timestamp in milliseconds
     * @param callDuration Call duration in seconds
     * @return List of recording candidates with metadata
     */
    public List<RecordingCandidate> findCandidates(String phoneNumber, long callTimestamp,
                                                    long callDuration) {
        List<RecordingCandidate> candidates = new ArrayList<>();

        // Define time window for search (in milliseconds)
        long searchStartTime = callTimestamp - (120 * 1000);  // -2 minutes
        long searchEndTime = callTimestamp + callDuration * 1000 + (300 * 1000); // +5 minutes

        // Convert to Unix timestamp in seconds for MediaStore query
        long searchStartSeconds = searchStartTime / 1000;
        long searchEndSeconds = searchEndTime / 1000;

        ContentResolver resolver = context.getContentResolver();

        // Query MediaStore for audio files within the time window
        // Note: We use DATE_ADDED because it's more reliable than DATE_MODIFIED for new files
        String selection = MediaStore.Audio.Media.DATE_ADDED + " >= ? AND " +
                          MediaStore.Audio.Media.DATE_ADDED + " <= ? AND " +
                          MediaStore.Audio.Media.IS_MUSIC + " = 0"; // Exclude music files

        String[] selectionArgs = {
            String.valueOf(searchStartSeconds),
            String.valueOf(searchEndSeconds)
        };

        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

        Uri queryUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        Log.d(TAG, "Querying MediaStore for recordings...");
        Log.d(TAG, "  Phone: " + phoneNumber);
        Log.d(TAG, "  Time window: " + searchStartSeconds + " to " + searchEndSeconds +
              " (" + ((searchEndSeconds - searchStartSeconds) / 60) + " minutes)");

        Cursor cursor = null;
        try {
            cursor = resolver.query(queryUri, PROJECTION, selection, selectionArgs, sortOrder);

            if (cursor == null) {
                Log.w(TAG, "MediaStore query returned null cursor");
                return candidates;
            }

            Log.d(TAG, "Found " + cursor.getCount() + " audio files in MediaStore within time window");

            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            int dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String displayName = cursor.getString(nameColumn);
                String filePath = cursor.getString(dataColumn);
                long size = cursor.getLong(sizeColumn);
                long dateAdded = cursor.getLong(dateAddedColumn) * 1000; // Convert to milliseconds
                long dateModified = cursor.getLong(dateModifiedColumn) * 1000; // Convert to milliseconds
                long duration = cursor.getLong(durationColumn); // Already in milliseconds

                File file = new File(filePath);

                // Skip if file doesn't exist or is too small
                if (!file.exists()) {
                    Log.d(TAG, "  Skipping non-existent file: " + displayName);
                    continue;
                }

                if (size < 1024) { // Skip files smaller than 1KB
                    Log.d(TAG, "  Skipping too small file: " + displayName + " (" + size + " bytes)");
                    continue;
                }

                RecordingCandidate candidate = new RecordingCandidate(
                    file, displayName, size, dateAdded, dateModified, duration
                );

                candidates.add(candidate);

                Log.d(TAG, "  Candidate: " + displayName +
                      " (size: " + size + ", dateAdded: " + dateAdded +
                      ", duration: " + duration + "ms)");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        Log.d(TAG, "Total candidates found: " + candidates.size());
        return candidates;
    }

    /**
     * Fallback: Find recordings using traditional file scanning
     * Used when MediaStore query fails or returns no results
     *
     * @param phoneNumber Phone number to search for
     * @param callTimestamp Call timestamp in milliseconds
     * @param callDuration Call duration in seconds
     * @return List of recording candidates
     */
    public List<RecordingCandidate> findCandidatesByFileScanning(String phoneNumber,
                                                                  long callTimestamp,
                                                                  long callDuration) {
        List<RecordingCandidate> candidates = new ArrayList<>();

        // Time window
        long searchStartTime = callTimestamp - (120 * 1000);
        long searchEndTime = callTimestamp + callDuration * 1000 + (300 * 1000);

        // Get recording directories
        List<File> directories = getRecordingDirectories();

        Log.d(TAG, "Fallback: Scanning file system for recordings...");

        for (File dir : directories) {
            if (!dir.exists() || !dir.isDirectory()) {
                continue;
            }

            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                continue;
            }

            for (File file : files) {
                if (!file.isFile()) continue;

                // Check if it's an audio file
                if (!isAudioFile(file.getName())) continue;

                long fileTime = file.lastModified();
                long fileSize = file.length();

                // Check if file was created within time window
                if (fileTime >= searchStartTime && fileTime <= searchEndTime) {
                    RecordingCandidate candidate = new RecordingCandidate(
                        file, file.getName(), fileSize, fileTime, fileTime, 0
                    );
                    candidates.add(candidate);

                    Log.d(TAG, "  Fallback candidate: " + file.getName() +
                          " (size: " + fileSize + ", modified: " + fileTime + ")");
                }
            }
        }

        Log.d(TAG, "Fallback scan found: " + candidates.size() + " candidates");
        return candidates;
    }

    /**
     * Get list of common recording directories
     */
    private List<File> getRecordingDirectories() {
        List<File> dirs = new ArrayList<>();

        File externalStorage = android.os.Environment.getExternalStorageDirectory();

        // Common recording paths
        String[] paths = {
            "Call", "Recordings/Call", "Voice Recorder", "VoiceRecorder",
            "Recordings", "My Files/Call", "MIUI/sound_recorder/call_rec",
            "sound_recorder/call_rec", "Recordings/Call Recordings",
            "CallRecordings", "PhoneRecord", "Record", "Sounds/CallRecord",
            "CallRecord", "Recorder", "Call Recordings"
        };

        for (String path : paths) {
            dirs.add(new File(externalStorage, path));
        }

        return dirs;
    }

    /**
     * Check if a file is an audio file
     */
    private boolean isAudioFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") ||
               lower.endsWith(".m4a") || lower.endsWith(".3gp") ||
               lower.endsWith(".amr") || lower.endsWith(".ogg") ||
               lower.endsWith(".aac");
    }
}
