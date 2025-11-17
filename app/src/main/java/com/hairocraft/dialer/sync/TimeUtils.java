package com.hairocraft.dialer.sync;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * PHASE 3.3: Time utilities for UTC handling and time drift tracking
 * Ensures consistent timestamp handling across the app
 */
public class TimeUtils {
    private static final String TAG = "TimeUtils";

    // Server time drift tracking
    private static Long serverTimeDriftMs = null;
    private static long lastDriftCheckTime = 0;
    private static final long DRIFT_CHECK_INTERVAL = 3600000; // Check every hour

    /**
     * Get current timestamp in milliseconds (UTC)
     */
    public static long getCurrentTimestampMs() {
        return System.currentTimeMillis();
    }

    /**
     * Get current timestamp in seconds (UTC)
     */
    public static long getCurrentTimestampSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * Convert timestamp to Laravel format (Y-m-d H:i:s) in UTC
     * PHASE 3.3: Uses UTC timezone for consistency
     *
     * @param timestampMs Timestamp in milliseconds
     * @return Formatted string in UTC
     */
    public static String formatTimestampForServer(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestampMs));
    }

    /**
     * Convert timestamp to ISO 8601 format in UTC
     *
     * @param timestampMs Timestamp in milliseconds
     * @return ISO 8601 formatted string
     */
    public static String formatTimestampISO8601(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestampMs));
    }

    /**
     * PHASE 3.3: Update server time drift from server response
     * Call this when you receive a timestamp from the server
     *
     * @param serverTimestampMs Server's current timestamp in milliseconds
     */
    public static void updateServerTimeDrift(long serverTimestampMs) {
        long deviceTimestampMs = System.currentTimeMillis();
        long driftMs = serverTimestampMs - deviceTimestampMs;

        serverTimeDriftMs = driftMs;
        lastDriftCheckTime = deviceTimestampMs;

        // Log the drift
        logTimeDrift(driftMs);
    }

    /**
     * PHASE 3.3: Update server time drift from server response (in seconds)
     * Call this when you receive a timestamp from the server in seconds
     *
     * @param serverTimestampSeconds Server's current timestamp in seconds
     */
    public static void updateServerTimeDrift(long serverTimestampSeconds, boolean isSeconds) {
        if (isSeconds) {
            updateServerTimeDrift(serverTimestampSeconds * 1000);
        } else {
            updateServerTimeDrift(serverTimestampSeconds);
        }
    }

    /**
     * Get the current server time drift in milliseconds
     * Positive value means server is ahead of device
     * Negative value means device is ahead of server
     *
     * @return Time drift in milliseconds, or null if not yet measured
     */
    public static Long getServerTimeDrift() {
        return serverTimeDriftMs;
    }

    /**
     * Get device time adjusted for server drift
     * Use this when you need to ensure synchronization with server
     *
     * @return Adjusted timestamp in milliseconds
     */
    public static long getServerAdjustedTime() {
        long deviceTime = System.currentTimeMillis();
        if (serverTimeDriftMs != null) {
            return deviceTime + serverTimeDriftMs;
        }
        return deviceTime;
    }

    /**
     * PHASE 3.3: Log time drift information
     * Helps diagnose timestamp-related issues
     */
    private static void logTimeDrift(long driftMs) {
        String direction;
        long absDrift = Math.abs(driftMs);

        if (driftMs > 0) {
            direction = "ahead";
        } else if (driftMs < 0) {
            direction = "behind";
        } else {
            direction = "synchronized";
        }

        // Convert to human-readable format
        long seconds = absDrift / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        String driftStr;
        if (hours > 0) {
            driftStr = hours + "h " + (minutes % 60) + "m";
        } else if (minutes > 0) {
            driftStr = minutes + "m " + (seconds % 60) + "s";
        } else {
            driftStr = seconds + "s";
        }

        Log.i(TAG, "Server time drift: " + driftStr + " " + direction +
              " (" + driftMs + "ms)");

        // Warn if drift is significant (> 5 minutes)
        if (absDrift > 300000) {
            Log.w(TAG, "WARNING: Significant time drift detected (" + driftStr + ")! " +
                  "This may cause timestamp-related issues.");
        }
    }

    /**
     * Check if time drift should be rechecked
     * Returns true if drift hasn't been checked in the last hour
     */
    public static boolean shouldRecheckDrift() {
        return serverTimeDriftMs == null ||
               (System.currentTimeMillis() - lastDriftCheckTime) > DRIFT_CHECK_INTERVAL;
    }

    /**
     * Format duration in seconds to human-readable string
     *
     * @param durationSeconds Duration in seconds
     * @return Formatted string (e.g., "1h 23m 45s", "23m 45s", "45s")
     */
    public static String formatDuration(long durationSeconds) {
        if (durationSeconds < 0) {
            return "0s";
        }

        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;

        if (hours > 0) {
            return String.format(Locale.US, "%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(Locale.US, "%dm %ds", minutes, seconds);
        } else {
            return String.format(Locale.US, "%ds", seconds);
        }
    }

    /**
     * Calculate time difference between two timestamps
     *
     * @param timestamp1Ms First timestamp in milliseconds
     * @param timestamp2Ms Second timestamp in milliseconds
     * @return Absolute difference in milliseconds
     */
    public static long getTimeDifference(long timestamp1Ms, long timestamp2Ms) {
        return Math.abs(timestamp1Ms - timestamp2Ms);
    }

    /**
     * Check if two timestamps are within a certain window
     *
     * @param timestamp1Ms First timestamp in milliseconds
     * @param timestamp2Ms Second timestamp in milliseconds
     * @param windowMs Window size in milliseconds
     * @return true if timestamps are within the window
     */
    public static boolean isWithinWindow(long timestamp1Ms, long timestamp2Ms, long windowMs) {
        return getTimeDifference(timestamp1Ms, timestamp2Ms) <= windowMs;
    }

    /**
     * Get device timezone info for debugging
     */
    public static String getDeviceTimezoneInfo() {
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        int offsetMs = tz.getOffset(now);
        int offsetHours = offsetMs / 3600000;
        int offsetMinutes = Math.abs((offsetMs % 3600000) / 60000);

        return String.format(Locale.US, "%s (UTC%+d:%02d)",
            tz.getID(), offsetHours, offsetMinutes);
    }

    /**
     * Log current time information for debugging
     */
    public static void logTimeInfo() {
        long now = System.currentTimeMillis();
        String utcTime = formatTimestampForServer(now);
        String timezone = getDeviceTimezoneInfo();

        Log.d(TAG, "Current time info:");
        Log.d(TAG, "  Device timestamp: " + now + "ms");
        Log.d(TAG, "  UTC time: " + utcTime);
        Log.d(TAG, "  Device timezone: " + timezone);

        if (serverTimeDriftMs != null) {
            Log.d(TAG, "  Server drift: " + serverTimeDriftMs + "ms");
            Log.d(TAG, "  Server-adjusted time: " + getServerAdjustedTime() + "ms");
        } else {
            Log.d(TAG, "  Server drift: not yet measured");
        }
    }
}
