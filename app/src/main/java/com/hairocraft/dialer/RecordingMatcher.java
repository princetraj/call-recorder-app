package com.hairocraft.dialer;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Advanced recording matcher with improved scoring algorithm
 *
 * PHASE 2.3: Improved Scoring System
 *
 * Scoring breakdown:
 * - Exact phone match: +60
 * - Contact name match: +45
 * - Duration within ±5s: +40
 * - Created within ≤10s: +50
 * - Created within ≤60s: +30
 * - MediaStore duration match: +40
 * - File size > 10KB: +10
 * - Call keywords in filename: +15
 * - Size plausibility: +10
 *
 * Threshold: ≥80 auto-select, 50-79 review, <50 reject
 */
public class RecordingMatcher {
    private static final String TAG = "RecordingMatcher";

    // Scoring thresholds
    private static final int THRESHOLD_AUTO_SELECT = 80;
    private static final int THRESHOLD_REVIEW = 50;

    // Scoring weights
    private static final int SCORE_EXACT_PHONE_MATCH = 60;
    private static final int SCORE_CONTACT_NAME_MATCH = 45;
    private static final int SCORE_DURATION_EXACT = 40;
    private static final int SCORE_CREATED_WITHIN_10S = 50;
    private static final int SCORE_CREATED_WITHIN_60S = 30;
    private static final int SCORE_MEDIASTORE_DURATION = 40;
    private static final int SCORE_FILE_SIZE_OK = 10;
    private static final int SCORE_CALL_KEYWORDS = 15;
    private static final int SCORE_SIZE_PLAUSIBLE = 10;

    private Context context;
    private MediaStoreRecordingFinder mediaStoreFinder;

    public RecordingMatcher(Context context) {
        this.context = context;
        this.mediaStoreFinder = new MediaStoreRecordingFinder(context);
    }

    /**
     * Find the best matching recording for a call
     *
     * @param phoneNumber Phone number of the call
     * @param callTimestamp Timestamp of the call (milliseconds)
     * @param callDuration Duration of the call (seconds)
     * @param contactName Contact name (optional, can be null)
     * @return The best matching recording file, or null if no good match found
     */
    public File findBestMatch(String phoneNumber, long callTimestamp, long callDuration,
                             String contactName) {
        Log.d(TAG, "=== Starting recording match ===");
        Log.d(TAG, "Phone: " + phoneNumber);
        Log.d(TAG, "Timestamp: " + callTimestamp);
        Log.d(TAG, "Duration: " + callDuration + "s");
        Log.d(TAG, "Contact: " + (contactName != null ? contactName : "Unknown"));

        // Step 1: Get candidates from MediaStore
        List<MediaStoreRecordingFinder.RecordingCandidate> candidates =
            mediaStoreFinder.findCandidates(phoneNumber, callTimestamp, callDuration);

        // Step 2: Fallback to file scanning if MediaStore returns no results
        if (candidates.isEmpty()) {
            Log.d(TAG, "MediaStore returned no candidates, falling back to file scanning...");
            candidates = mediaStoreFinder.findCandidatesByFileScanning(
                phoneNumber, callTimestamp, callDuration
            );
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No recording candidates found");
            return null;
        }

        Log.d(TAG, "Found " + candidates.size() + " candidates, scoring...");

        // Step 3: Score each candidate
        for (MediaStoreRecordingFinder.RecordingCandidate candidate : candidates) {
            candidate.score = calculateScore(candidate, phoneNumber, callTimestamp,
                                            callDuration, contactName);
            Log.d(TAG, "  " + candidate.file.getName() + " -> Score: " + candidate.score);
        }

        // Step 4: Sort by score (highest first)
        Collections.sort(candidates, new Comparator<MediaStoreRecordingFinder.RecordingCandidate>() {
            @Override
            public int compare(MediaStoreRecordingFinder.RecordingCandidate a,
                             MediaStoreRecordingFinder.RecordingCandidate b) {
                if (a.score != b.score) {
                    return b.score - a.score; // Higher score first
                }
                // If scores are equal, prefer newer files
                return Long.compare(b.dateAdded, a.dateAdded);
            }
        });

        // Step 5: Get the best match
        MediaStoreRecordingFinder.RecordingCandidate best = candidates.get(0);

        Log.d(TAG, "=== Best match ===");
        Log.d(TAG, "File: " + best.file.getName());
        Log.d(TAG, "Score: " + best.score);
        Log.d(TAG, "Size: " + best.size + " bytes");
        Log.d(TAG, "Duration: " + best.duration + "ms");

        // Step 6: Check threshold
        if (best.score >= THRESHOLD_AUTO_SELECT) {
            Log.d(TAG, "✓ Auto-selected (score >= " + THRESHOLD_AUTO_SELECT + ")");
            return best.file;
        } else if (best.score >= THRESHOLD_REVIEW) {
            Log.d(TAG, "⚠ Review recommended (score " + THRESHOLD_REVIEW + "-" +
                  (THRESHOLD_AUTO_SELECT - 1) + "), but accepting anyway");
            return best.file;
        } else {
            Log.w(TAG, "✗ Rejected (score < " + THRESHOLD_REVIEW + ")");
            return null;
        }
    }

    /**
     * Calculate match score for a recording candidate
     *
     * @param candidate The recording candidate
     * @param phoneNumber Phone number of the call
     * @param callTimestamp Timestamp of the call
     * @param callDuration Duration of the call (seconds)
     * @param contactName Contact name (optional)
     * @return Match score (higher is better)
     */
    private int calculateScore(MediaStoreRecordingFinder.RecordingCandidate candidate,
                               String phoneNumber, long callTimestamp, long callDuration,
                               String contactName) {
        int score = 0;
        String fileName = candidate.displayName.toLowerCase();

        // 1. Exact phone match (+60 points)
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            if (PhoneNumberNormalizer.filenameContainsNumber(fileName, phoneNumber)) {
                score += SCORE_EXACT_PHONE_MATCH;
                Log.d(TAG, "    +60 (exact phone match)");
            }
        }

        // 2. Contact name match (+45 points)
        if (contactName != null && !contactName.isEmpty() && !contactName.equalsIgnoreCase("Unknown")) {
            String cleanContactName = contactName.toLowerCase().replaceAll("[^a-z0-9]", "");
            String cleanFileName = fileName.replaceAll("[^a-z0-9]", "");

            if (cleanFileName.contains(cleanContactName) || cleanContactName.contains(cleanFileName)) {
                score += SCORE_CONTACT_NAME_MATCH;
                Log.d(TAG, "    +45 (contact name match)");
            }
        }

        // 3. Duration match using MediaStore duration (+40 points)
        if (candidate.duration > 0 && callDuration > 0) {
            long candidateDurationSeconds = candidate.duration / 1000;
            long durationDiff = Math.abs(candidateDurationSeconds - callDuration);

            if (durationDiff <= 5) { // Within ±5 seconds
                score += SCORE_DURATION_EXACT;
                Log.d(TAG, "    +40 (duration within ±5s, diff: " + durationDiff + "s)");
            } else if (durationDiff <= 10) { // Within ±10 seconds
                score += SCORE_MEDIASTORE_DURATION / 2;
                Log.d(TAG, "    +20 (duration within ±10s, diff: " + durationDiff + "s)");
            }
        }

        // 4. Time proximity scoring
        // Use dateAdded as it's more reliable for new recordings
        long fileTimestamp = candidate.dateAdded > 0 ? candidate.dateAdded : candidate.dateModified;
        long timeDiff = Math.abs(fileTimestamp - callTimestamp);
        long timeDiffSeconds = timeDiff / 1000;

        if (timeDiffSeconds <= 10) { // Created within ≤10 seconds
            score += SCORE_CREATED_WITHIN_10S;
            Log.d(TAG, "    +50 (created within 10s, diff: " + timeDiffSeconds + "s)");
        } else if (timeDiffSeconds <= 60) { // Created within ≤60 seconds
            score += SCORE_CREATED_WITHIN_60S;
            Log.d(TAG, "    +30 (created within 60s, diff: " + timeDiffSeconds + "s)");
        } else if (timeDiffSeconds <= 120) { // Within 2 minutes
            score += 15;
            Log.d(TAG, "    +15 (created within 2min, diff: " + timeDiffSeconds + "s)");
        } else if (timeDiffSeconds <= 300) { // Within 5 minutes
            score += 5;
            Log.d(TAG, "    +5 (created within 5min, diff: " + timeDiffSeconds + "s)");
        }

        // 5. File size validation (+10 points)
        if (candidate.size > 10240) { // > 10KB
            score += SCORE_FILE_SIZE_OK;
            Log.d(TAG, "    +10 (file size > 10KB)");
        }

        // 6. Call-related keywords in filename (+15 points)
        if (fileName.contains("call") || fileName.contains("record") ||
            fileName.contains("voice") || fileName.contains("incoming") ||
            fileName.contains("outgoing") || fileName.contains("rec_")) {
            score += SCORE_CALL_KEYWORDS;
            Log.d(TAG, "    +15 (call-related keywords)");
        }

        // 7. Size plausibility check (+10 points)
        if (callDuration > 0) {
            // Rough estimates for compressed audio:
            // - AMR: ~0.3-0.5 KB/s
            // - M4A: ~1-2 KB/s
            // - MP3: ~1.5-3 KB/s
            long minExpectedSize = (long)(callDuration * 300);  // 0.3 KB/s
            long maxExpectedSize = (long)(callDuration * 3000); // 3 KB/s

            if (candidate.size >= minExpectedSize && candidate.size <= maxExpectedSize) {
                score += SCORE_SIZE_PLAUSIBLE;
                Log.d(TAG, "    +10 (size plausible for duration)");
            } else {
                Log.d(TAG, "    +0 (size outside plausible range: " +
                      candidate.size + " bytes for " + callDuration + "s call)");
            }
        }

        return score;
    }

    /**
     * Get a detailed match report for debugging
     *
     * @param phoneNumber Phone number of the call
     * @param callTimestamp Timestamp of the call
     * @param callDuration Duration of the call
     * @param contactName Contact name (optional)
     * @return Human-readable match report
     */
    public String getMatchReport(String phoneNumber, long callTimestamp, long callDuration,
                                String contactName) {
        StringBuilder report = new StringBuilder();
        report.append("=== Recording Match Report ===\n");
        report.append("Phone: ").append(phoneNumber).append("\n");
        report.append("Timestamp: ").append(callTimestamp).append("\n");
        report.append("Duration: ").append(callDuration).append("s\n");
        report.append("Contact: ").append(contactName != null ? contactName : "Unknown").append("\n\n");

        List<MediaStoreRecordingFinder.RecordingCandidate> candidates =
            mediaStoreFinder.findCandidates(phoneNumber, callTimestamp, callDuration);

        if (candidates.isEmpty()) {
            candidates = mediaStoreFinder.findCandidatesByFileScanning(
                phoneNumber, callTimestamp, callDuration
            );
        }

        report.append("Candidates found: ").append(candidates.size()).append("\n\n");

        for (MediaStoreRecordingFinder.RecordingCandidate candidate : candidates) {
            candidate.score = calculateScore(candidate, phoneNumber, callTimestamp,
                                            callDuration, contactName);
            report.append("File: ").append(candidate.file.getName()).append("\n");
            report.append("  Score: ").append(candidate.score).append("\n");
            report.append("  Size: ").append(candidate.size).append(" bytes\n");
            report.append("  Duration: ").append(candidate.duration).append("ms\n");
            report.append("  Date Added: ").append(candidate.dateAdded).append("\n\n");
        }

        return report.toString();
    }
}
