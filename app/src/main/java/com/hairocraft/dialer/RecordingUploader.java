package com.hairocraft.dialer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.hairocraft.dialer.sync.SyncManager;

public class RecordingUploader {
    private static final String TAG = "RecordingUploader";
    private Context context;
    private PrefsManager prefsManager;
    private ApiService apiService;
    private RecordingMatcher recordingMatcher; // PHASE 2: New advanced matcher
    // Removed direct reference to avoid circular dependency - will get instance when needed

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
        this.recordingMatcher = new RecordingMatcher(context); // PHASE 2: Initialize new matcher
        // FIXED: Removed SyncManager initialization here to break circular dependency
        // SyncManager creates RecordingUploader, and RecordingUploader was creating SyncManager
        // Now we get SyncManager instance only when needed (lazy initialization)
    }

    /**
     * Search for a call recording file matching the phone number and timestamp
     * PHASE 2: Updated to use advanced RecordingMatcher with improved scoring
     *
     * @param phoneNumber The phone number to search for
     * @param callTimestamp The timestamp of the call (in milliseconds)
     * @param callDuration The duration of the call (in seconds)
     * @param contactName The contact name (optional, can be null)
     * @return The recording file if found, null otherwise
     */
    public File findRecording(String phoneNumber, long callTimestamp, long callDuration,
                             String contactName) {
        if (!prefsManager.isLoggedIn()) {
            Log.w(TAG, "User not logged in, skipping recording search");
            return null;
        }

        Log.d(TAG, "PHASE 2: Using advanced RecordingMatcher for improved accuracy");

        // Use the new RecordingMatcher with MediaStore integration and improved scoring
        return recordingMatcher.findBestMatch(phoneNumber, callTimestamp, callDuration, contactName);
    }

    /**
     * Overload for backward compatibility (without contactName)
     * @deprecated Use findRecording with contactName parameter for better matching
     */
    @Deprecated
    public File findRecording(String phoneNumber, long callTimestamp, long callDuration) {
        return findRecording(phoneNumber, callTimestamp, callDuration, null);
    }

    /**
     * PHASE 2: Old calculateMatchScore removed - now handled by RecordingMatcher
     * RecordingMatcher provides improved scoring with:
     * - Phone number normalization (E.164)
     * - MediaStore integration for accurate metadata
     * - Enhanced scoring algorithm with multiple factors
     */

    /**
     * Upload a recording file to the server
     * @param callLogId The ID of the call log from the server
     * @param recordingFile The recording file to upload
     * @param duration The duration of the call
     * @param phoneNumber The phone number associated with the call
     * @param callTimestamp The timestamp of the call
     * @param callback Callback for success/failure
     */
    public void uploadRecording(int callLogId, File recordingFile, long duration,
                                String phoneNumber, long callTimestamp,
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
        long originalSize = recordingFile.length();
        Log.d(TAG, "Preparing to upload recording: " + recordingFile.getName() +
              " (original size: " + originalSize + " bytes)");

        // Compress the audio file before uploading
        File compressedFile = null;
        try {
            // Create persistent compressed file with unique identifier
            // Use filesDir instead of cacheDir to prevent Android from deleting it
            File compressedDir = new File(context.getFilesDir(), "compressed_recordings");
            if (!compressedDir.exists()) {
                compressedDir.mkdirs();
            }

            // Create unique filename: rec_{phoneNumber}_{timestamp}_{callLogId}.m4a
            String cleanNumber = phoneNumber != null ? phoneNumber.replaceAll("[^0-9]", "") : "unknown";
            String compressedFileName = String.format("rec_%s_%d_%d.m4a",
                cleanNumber, callTimestamp, callLogId);
            compressedFile = new File(compressedDir, compressedFileName);

            Log.d(TAG, "Compressing audio file...");
            boolean compressionSuccess = AudioCompressor.compressAudio(recordingFile, compressedFile);

            final File fileToUpload;
            if (compressionSuccess && compressedFile.exists()) {
                fileToUpload = compressedFile;
                long compressedSize = compressedFile.length();
                double reduction = (1 - (double)compressedSize / originalSize) * 100;
                Log.d(TAG, "Compression successful! Size reduced by " +
                      String.format("%.1f%%", reduction) + " (" +
                      originalSize + " -> " + compressedSize + " bytes)");
            } else {
                // If compression failed, upload original file
                Log.w(TAG, "Compression failed, uploading original file");
                fileToUpload = recordingFile;
            }

            final File tempCompressedFile = (fileToUpload == compressedFile) ? compressedFile : null;

            final File finalFileToUpload = fileToUpload;
            final String recordingPath = recordingFile.getAbsolutePath();
            final String compressedPath = (fileToUpload == compressedFile && compressedFile != null) ?
                compressedFile.getAbsolutePath() : null;

            // Upload the file
            apiService.uploadRecording(token, callLogId, fileToUpload, duration,
                new ApiService.ApiCallback() {
                    @Override
                    public void onSuccess(String result) {
                        // Clean up compressed file after successful upload
                        if (tempCompressedFile != null && tempCompressedFile.exists()) {
                            boolean deleted = tempCompressedFile.delete();
                            Log.d(TAG, "Cleaned up compressed file: " + tempCompressedFile.getName() +
                                  " (deleted=" + deleted + ")");
                        }
                        // Also clean up old compressed files (older than 7 days)
                        cleanupOldCompressedFiles();
                        callback.onSuccess(result);
                    }

                    @Override
                    public void onFailure(String error) {
                        Log.e(TAG, "Failed to upload recording: " + error);

                        // Queue for retry with call_log_id for accurate association
                        long fileSize = finalFileToUpload.length();

                        // PHASE 1.1: Generate UUID for this recording if retrying from old flow
                        String recordingUuid = java.util.UUID.randomUUID().toString();

                        // FIXED: Get SyncManager instance when needed (lazy initialization)
                        SyncManager.getInstance(context).queueRecording(
                            callLogId,
                            recordingUuid,
                            phoneNumber,
                            callTimestamp,
                            recordingPath,
                            compressedPath,
                            fileSize
                        );
                        Log.d(TAG, "Recording queued for retry with UUID: " + recordingUuid +
                              ", call_log_id=" + callLogId);

                        // FIXED: Keep compressed file for retry if it was successfully created
                        // Only delete if compression failed or file doesn't exist
                        if (tempCompressedFile != null && tempCompressedFile.exists()) {
                            if (compressedPath != null) {
                                Log.d(TAG, "Keeping compressed file for retry: " + tempCompressedFile.getName());
                            } else {
                                // Compression was attempted but path not saved - clean up
                                boolean deleted = tempCompressedFile.delete();
                                Log.d(TAG, "Cleaned up orphaned compressed file: " + deleted);
                            }
                        }

                        callback.onFailure(error);
                    }
                });

        } catch (Exception e) {
            Log.e(TAG, "Error during compression/upload process", e);
            // Clean up on error
            if (compressedFile != null && compressedFile.exists()) {
                compressedFile.delete();
            }
            callback.onFailure("Error: " + e.getMessage());
        }
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

    /**
     * Clean up old compressed recordings to free storage space
     * IMPROVED: Removes compressed files older than 24 hours OR orphaned files
     */
    private void cleanupOldCompressedFiles() {
        try {
            File compressedDir = new File(context.getFilesDir(), "compressed_recordings");
            if (!compressedDir.exists() || !compressedDir.isDirectory()) {
                return;
            }

            File[] files = compressedDir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }

            // IMPROVED: More aggressive cleanup - 24 hours instead of 7 days
            // This prevents storage bloat from failed uploads
            long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L);
            int deletedOldCount = 0;
            int deletedOrphanCount = 0;

            for (File file : files) {
                if (!file.isFile()) continue;

                boolean shouldDelete = false;
                String reason = "";

                // Delete files older than 24 hours
                if (file.lastModified() < oneDayAgo) {
                    shouldDelete = true;
                    reason = "older than 24 hours";
                    deletedOldCount++;
                }
                // Delete very small files (< 1KB) as they're likely corrupted
                else if (file.length() < 1024) {
                    shouldDelete = true;
                    reason = "too small (corrupted)";
                    deletedOrphanCount++;
                }
                // Delete files with zero size
                else if (file.length() == 0) {
                    shouldDelete = true;
                    reason = "zero size";
                    deletedOrphanCount++;
                }

                if (shouldDelete) {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted compressed file: " + file.getName() +
                              " (reason: " + reason + ")");
                    } else {
                        Log.w(TAG, "Failed to delete compressed file: " + file.getName());
                    }
                }
            }

            if (deletedOldCount > 0 || deletedOrphanCount > 0) {
                Log.d(TAG, "Cleanup complete: " + deletedOldCount + " old files, " +
                      deletedOrphanCount + " orphaned/corrupted files deleted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up old compressed files", e);
        }
    }

    /**
     * Perform comprehensive cleanup of compressed recordings directory
     * Should be called periodically (e.g., on app startup or daily)
     */
    public void performComprehensiveCleanup() {
        try {
            Log.d(TAG, "Starting comprehensive cleanup of compressed recordings...");

            File compressedDir = new File(context.getFilesDir(), "compressed_recordings");
            if (!compressedDir.exists() || !compressedDir.isDirectory()) {
                Log.d(TAG, "Compressed recordings directory does not exist, nothing to clean");
                return;
            }

            File[] files = compressedDir.listFiles();
            if (files == null || files.length == 0) {
                Log.d(TAG, "No compressed files to clean");
                return;
            }

            long totalSize = 0;
            int totalFiles = 0;
            int deletedFiles = 0;
            long freedSpace = 0;

            // Get current time thresholds
            long twoDaysAgo = System.currentTimeMillis() - (2 * 24 * 60 * 60 * 1000L);

            for (File file : files) {
                if (!file.isFile()) continue;

                totalFiles++;
                long fileSize = file.length();
                totalSize += fileSize;

                // Delete if older than 2 days (aggressive cleanup)
                if (file.lastModified() < twoDaysAgo) {
                    if (file.delete()) {
                        deletedFiles++;
                        freedSpace += fileSize;
                        Log.d(TAG, "Deleted old compressed file: " + file.getName() +
                              " (" + (fileSize / 1024) + " KB)");
                    }
                }
            }

            Log.d(TAG, "Comprehensive cleanup complete:");
            Log.d(TAG, "  Total files scanned: " + totalFiles);
            Log.d(TAG, "  Files deleted: " + deletedFiles);
            Log.d(TAG, "  Space freed: " + (freedSpace / 1024) + " KB");
            Log.d(TAG, "  Remaining files: " + (totalFiles - deletedFiles));
            Log.d(TAG, "  Remaining space used: " + ((totalSize - freedSpace) / 1024) + " KB");

        } catch (Exception e) {
            Log.e(TAG, "Error during comprehensive cleanup", e);
        }
    }
}
