package com.office.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RecordingUploader {
    private static final String TAG = "RecordingUploader";
    private Context context;
    private PrefsManager prefsManager;
    private ApiService apiService;

    // Common recording directories by manufacturer
    private static final String[] RECORDING_PATHS = {
        // Samsung (most common paths first)
        "Call",
        "Recordings/Call",
        "Voice Recorder",
        "VoiceRecorder",
        "Recordings",
        "My Files/Call",

        // Xiaomi/MIUI/Redmi/POCO
        "MIUI/sound_recorder/call_rec",
        "sound_recorder/call_rec",
        "Recordings/Call",
        "sound_recorder",

        // OnePlus/OxygenOS
        "Recordings",
        "Recordings/Call Recordings",
        "CallRecordings",

        // Oppo/Realme/ColorOS
        "Recordings",
        "Recordings/Call",
        "PhoneRecord",

        // Vivo/FuntouchOS
        "Record",
        "Recordings/Call",

        // Huawei/Honor/EMUI
        "Sounds/CallRecord",
        "CallRecord",
        "Recordings",

        // Google Pixel
        "Recorder",
        "Recordings",

        // Motorola
        "Recordings",
        "Call Recordings",

        // Nokia
        "CallRecordings",
        "Recordings",

        // Generic/Other brands
        "CallRecordings",
        "Call Recordings",
        "PhoneRecord",
        "CallRecord",
        "AudioRecorder",
        "Call",
        "Calls"
    };

    // Supported audio file extensions
    private static final String[] AUDIO_EXTENSIONS = {
        ".mp3", ".wav", ".m4a", ".3gp", ".amr", ".ogg", ".aac"
    };

    public RecordingUploader(Context context) {
        this.context = context;
        this.prefsManager = new PrefsManager(context);
        this.apiService = ApiService.getInstance();
    }

    /**
     * Search for a call recording file matching the phone number and timestamp
     * @param phoneNumber The phone number to search for
     * @param callTimestamp The timestamp of the call (in milliseconds)
     * @param callDuration The duration of the call (in seconds)
     * @return The recording file if found, null otherwise
     */
    public File findRecording(String phoneNumber, long callTimestamp, long callDuration) {
        if (!prefsManager.isLoggedIn()) {
            Log.w(TAG, "User not logged in, skipping recording search");
            return null;
        }

        List<File> searchDirs = getRecordingDirectories();
        List<RecordingCandidate> candidates = new ArrayList<>();

        // Search within 5 minutes before and after the call
        long searchStartTime = callTimestamp - (5 * 60 * 1000);
        long searchEndTime = callTimestamp + callDuration * 1000 + (5 * 60 * 1000);

        String cleanNumber = phoneNumber != null ? phoneNumber.replaceAll("[^0-9]", "") : "";

        Log.d(TAG, "Searching for recording - Phone: " + phoneNumber +
              ", Call time: " + callTimestamp + ", Duration: " + callDuration + "s");

        for (File dir : searchDirs) {
            if (!dir.exists() || !dir.isDirectory()) {
                continue;
            }

            Log.d(TAG, "Searching in: " + dir.getAbsolutePath());

            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                Log.d(TAG, "  No files found in directory");
                continue;
            }

            Log.d(TAG, "  Found " + files.length + " files in directory");

            for (File file : files) {
                if (!file.isFile()) continue;

                // Check if it's an audio file
                if (!isAudioFile(file.getName())) continue;

                long fileTime = file.lastModified();
                long fileSize = file.length();

                Log.d(TAG, "  Checking: " + file.getName() +
                      " (size: " + fileSize + " bytes, modified: " + fileTime + ")");

                // Check if file was created around the call time
                if (fileTime >= searchStartTime && fileTime <= searchEndTime) {
                    int score = calculateMatchScore(file, cleanNumber, callTimestamp, fileTime, fileSize);

                    if (score > 0) {
                        candidates.add(new RecordingCandidate(file, score, fileTime));
                        Log.d(TAG, "    ✓ Potential match! Score: " + score);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No recording candidates found for call at " + callTimestamp);
            return null;
        }

        // Sort by score (highest first), then by timestamp (newest first)
        java.util.Collections.sort(candidates, new java.util.Comparator<RecordingCandidate>() {
            @Override
            public int compare(RecordingCandidate a, RecordingCandidate b) {
                if (a.score != b.score) {
                    return b.score - a.score; // Higher score first
                }
                return Long.compare(b.timestamp, a.timestamp); // Newer first
            }
        });

        RecordingCandidate best = candidates.get(0);
        Log.d(TAG, "Selected best match: " + best.file.getName() +
              " (score: " + best.score + ")");

        return best.file;
    }

    /**
     * Calculate match score for a recording file
     * Higher score = better match
     */
    private int calculateMatchScore(File file, String cleanNumber, long callTimestamp,
                                     long fileTime, long fileSize) {
        int score = 0;
        String fileName = file.getName().toLowerCase();

        // Bonus for files with phone number in name (+50 points)
        if (!cleanNumber.isEmpty() && fileName.contains(cleanNumber)) {
            score += 50;
            Log.d(TAG, "      +50 (phone number in filename)");
        }

        // Bonus for timestamp proximity (+30 to +10 points based on closeness)
        long timeDiff = Math.abs(fileTime - callTimestamp);
        if (timeDiff < 10000) { // Within 10 seconds
            score += 30;
            Log.d(TAG, "      +30 (created within 10s of call)");
        } else if (timeDiff < 30000) { // Within 30 seconds
            score += 20;
            Log.d(TAG, "      +20 (created within 30s of call)");
        } else if (timeDiff < 60000) { // Within 1 minute
            score += 10;
            Log.d(TAG, "      +10 (created within 1min of call)");
        }

        // Bonus for reasonable file size (+20 points if > 10KB)
        if (fileSize > 10240) { // > 10KB
            score += 20;
            Log.d(TAG, "      +20 (file size > 10KB)");
        }

        // Bonus for call-related keywords in filename (+15 points)
        if (fileName.contains("call") || fileName.contains("record") ||
            fileName.contains("voice") || fileName.contains("incoming") ||
            fileName.contains("outgoing")) {
            score += 15;
            Log.d(TAG, "      +15 (call-related keyword in filename)");
        }

        // Penalty for very old files (-10 points if > 2 minutes old)
        if (timeDiff > 120000) {
            score -= 10;
            Log.d(TAG, "      -10 (file is old)");
        }

        return score;
    }

    /**
     * Helper class to store recording candidates with scores
     */
    private static class RecordingCandidate {
        File file;
        int score;
        long timestamp;

        RecordingCandidate(File file, int score, long timestamp) {
            this.file = file;
            this.score = score;
            this.timestamp = timestamp;
        }
    }

    /**
     * Upload a recording file to the server
     * @param callLogId The ID of the call log from the server
     * @param recordingFile The recording file to upload
     * @param duration The duration of the call
     * @param callback Callback for success/failure
     */
    public void uploadRecording(int callLogId, File recordingFile, long duration,
                                final ApiService.ApiCallback callback) {
        if (!prefsManager.isLoggedIn()) {
            Log.w(TAG, "User not logged in, skipping recording upload");
            callback.onFailure("User not logged in");
            return;
        }

        if (recordingFile == null || !recordingFile.exists()) {
            Log.w(TAG, "Recording file does not exist");
            callback.onFailure("Recording file not found");
            return;
        }

        String token = prefsManager.getAuthToken();
        Log.d(TAG, "Uploading recording: " + recordingFile.getName() +
              " (size: " + recordingFile.length() + " bytes)");

        apiService.uploadRecording(token, callLogId, recordingFile, duration, callback);
    }

    /**
     * Get list of directories to search for recordings
     */
    private List<File> getRecordingDirectories() {
        List<File> dirs = new ArrayList<>();

        // External storage directories
        File externalStorage = Environment.getExternalStorageDirectory();
        for (String path : RECORDING_PATHS) {
            dirs.add(new File(externalStorage, path));
        }

        // Also check in Music, Documents, and Downloads
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        for (String path : RECORDING_PATHS) {
            dirs.add(new File(musicDir, path));
            dirs.add(new File(documentsDir, path));
            dirs.add(new File(downloadsDir, path));
        }

        return dirs;
    }

    /**
     * Check if a file is an audio file based on extension
     */
    private boolean isAudioFile(String filename) {
        String lowerName = filename.toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
