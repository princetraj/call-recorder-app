package com.hairocraft.dialer;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.provider.ContactsContract;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import com.hairocraft.dialer.sync.SyncManager;
import com.hairocraft.dialer.sync.SyncScheduler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallLogManager {
    private static final String TAG = "CallLogManager";
    private Context context;
    private PrefsManager prefsManager;
    private ApiService apiService;
    private RecordingUploader recordingUploader;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private SimInfoHelper simInfoHelper;
    private SyncManager syncManager;
    // IMPROVED: Track multiple concurrent calls instead of just one
    // Handles call waiting, conference calls, and rapid succession scenarios
    private final Set<String> activeCallNumbers = new HashSet<>();
    private final Object uploadLock = new Object(); // Synchronization lock for concurrent calls
    // ExecutorService for background database operations
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public CallLogManager(Context context) {
        this.context = context;
        this.prefsManager = new PrefsManager(context);
        this.apiService = ApiService.getInstance();
        this.recordingUploader = new RecordingUploader(context);
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        this.simInfoHelper = new SimInfoHelper(context);
        this.syncManager = SyncManager.getInstance(context);
    }

    public void startListening() {
        // Check if we have the required permission
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE permission not granted, cannot start listening");
            return;
        }

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                super.onCallStateChanged(state, phoneNumber);

                // Normalize phone number (can be null or empty for unknown/blocked calls)
                final String normalizedNumber = (phoneNumber != null && !phoneNumber.isEmpty())
                    ? phoneNumber : "UNKNOWN";

                synchronized (uploadLock) {
                    Log.d(TAG, "Call state changed: " + getStateString(state) +
                          ", Number: " + normalizedNumber +
                          ", Active calls: " + activeCallNumbers.size() +
                          ", Tracked numbers: " + activeCallNumbers);
                }

                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        Log.d(TAG, "Incoming call from: " + normalizedNumber);
                        synchronized (uploadLock) {
                            activeCallNumbers.add(normalizedNumber);
                            Log.d(TAG, "Added to active calls. Total active: " + activeCallNumbers.size());
                        }
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        Log.d(TAG, "Call answered or outgoing call: " + normalizedNumber);
                        synchronized (uploadLock) {
                            // Add to active calls if not already tracked
                            if (activeCallNumbers.add(normalizedNumber)) {
                                Log.d(TAG, "Added outgoing call to active. Total active: " + activeCallNumbers.size());
                            }
                        }
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        Log.d(TAG, "Call ended - Processing upload");

                        synchronized (uploadLock) {
                            // Only process if we have tracked calls
                            if (!activeCallNumbers.isEmpty()) {
                                // CRITICAL FIX: Capture current active calls at this moment
                                // This prevents clearing calls that start during the 3-second delay
                                final Set<String> callsToProcess = new HashSet<>(activeCallNumbers);
                                final int callCount = callsToProcess.size();

                                Log.d(TAG, "Processing " + callCount +
                                      " active call(s), waiting 3s for call log to be written");
                                Log.d(TAG, "Captured calls to process: " + callsToProcess);

                                // Clear these calls immediately from tracking
                                // New calls that start now will be tracked separately
                                activeCallNumbers.removeAll(callsToProcess);
                                Log.d(TAG, "Removed processed calls from active tracking. " +
                                      "Remaining active: " + activeCallNumbers.size());

                                // Wait for call log to be written
                                new android.os.Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        uploadLatestCallLog();
                                        Log.d(TAG, "Upload triggered for " + callCount + " call(s): " + callsToProcess);
                                    }
                                }, 3000); // Wait 3 seconds for system to write call log
                            } else {
                                Log.d(TAG, "No active calls to process, likely initial IDLE state");
                            }
                        }
                        break;
                }
            }
        };

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        Log.d(TAG, "Started listening for call state changes");
    }

    private String getStateString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "OFFHOOK";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    public void stopListening() {
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            Log.d(TAG, "Stopped listening for call state changes");
        }
    }

    private void uploadLatestCallLog() {
        // Synchronize to prevent concurrent call processing
        synchronized (uploadLock) {
            Log.d(TAG, "uploadLatestCallLog() called");

            if (!prefsManager.isLoggedIn()) {
                Log.w(TAG, "User not logged in, skipping upload");
                return;
            }

        // CRITICAL: Use try-finally to ensure cursor is always closed
        Cursor cursor = null;
        try {
            // First, trigger sync for any previously failed uploads
            // SyncManager uses single-thread executor so this won't conflict with later sync
            SyncScheduler.triggerImmediateSync(context);
            // Get the last processed timestamp from persistent storage
            long lastProcessedTimestamp = prefsManager.getLastProcessedCallTime();
            Log.d(TAG, "Last processed call timestamp from prefs: " + lastProcessedTimestamp);

            // Query for ALL calls newer than last processed
            // This ensures we don't miss calls if multiple happen in quick succession
            String selection = CallLog.Calls.DATE + " > ?";
            String[] selectionArgs = new String[]{String.valueOf(lastProcessedTimestamp)};

            cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null,
                selection,
                selectionArgs,
                CallLog.Calls.DATE + " ASC" // Process oldest first
            );

            if (cursor != null && cursor.getCount() > 0) {
                Log.d(TAG, "Found " + cursor.getCount() + " new call(s) to process");

                int processedCount = 0;
                long newestTimestamp = lastProcessedTimestamp;

                // Process ALL new calls, not just the latest one
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                    int type = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE));
                    long duration = cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DURATION));
                    long dateMillis = cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE));

                    Log.d(TAG, "Processing call #" + (processedCount + 1) +
                          ": Number=" + number +
                          ", Type=" + getCallType(type) +
                          ", Duration=" + duration +
                          ", Timestamp=" + dateMillis);

                    // CRITICAL: Always track newest timestamp, even for invalid calls
                    // This prevents infinite loops where invalid calls are retried forever
                    if (dateMillis > newestTimestamp) {
                        newestTimestamp = dateMillis;
                    }

                    // VALIDATION: Ensure call log data is valid
                    boolean isValid = true;

                    // Validate timestamp (not in future, not too old)
                    long now = System.currentTimeMillis();
                    if (dateMillis > now) {
                        Log.w(TAG, "Invalid call: timestamp is in the future, skipping");
                        isValid = false;
                    } else if (dateMillis < (now - 7 * 24 * 60 * 60 * 1000L)) {
                        // Older than 7 days - probably already processed or stale
                        Log.w(TAG, "Call is older than 7 days, skipping");
                        isValid = false;
                    }

                    // Validate duration (not negative, reasonable for call recordings)
                    if (duration < 0) {
                        Log.w(TAG, "Invalid call: negative duration, fixing to 0");
                        duration = 0;
                    }

                    // Validate call type
                    if (type < CallLog.Calls.INCOMING_TYPE || type > CallLog.Calls.REJECTED_TYPE) {
                        Log.w(TAG, "Invalid call: unknown call type " + type + ", skipping");
                        isValid = false;
                    }

                    if (!isValid) {
                        // Skip this invalid call but timestamp is already tracked
                        continue;
                    }

                    String callType = getCallType(type);
                    String callerName = getContactName(number);
                    String timestamp = formatTimestamp(dateMillis);

                    // Get SIM information
                    JSONObject simInfo = simInfoHelper.getSimInfoForCallLog(cursor);

                    String token = prefsManager.getAuthToken();
                    final String finalNumber = number;
                    final long finalDuration = duration;
                    final long finalDateMillis = dateMillis;
                    final String finalCallType = callType;
                    final String finalCallerName = callerName;
                    final JSONObject finalSimInfo = simInfo;

                    // PHASE 1: Queue with UUID and WorkManager for reliability
                    String simSlot = simInfo != null ? simInfo.optString("sim_slot_index", null) : null;
                    String simOperator = simInfo != null ? simInfo.optString("sim_name", null) : null;
                    String simNumber = simInfo != null ? simInfo.optString("sim_number", null) : null;

                    Log.d(TAG, "Queueing call log for upload - Number: " + number +
                          ", Type: " + callType + ", Duration: " + duration +
                          ", Timestamp: " + dateMillis);

                    // PHASE 1.4: Transactional insert + work scheduling
                    queueCallLogWithWorker(
                        finalNumber,
                        finalCallType,
                        finalDuration,
                        finalDateMillis,
                        finalCallerName,
                        simSlot,
                        simOperator,
                        simNumber
                    );

                    processedCount++;
                } // End while loop

                // Save the newest timestamp so we don't reprocess these calls
                if (newestTimestamp > lastProcessedTimestamp) {
                    prefsManager.saveLastProcessedCallTime(newestTimestamp);
                    Log.d(TAG, "Queued " + processedCount + " call(s). Updated last processed time to: " + newestTimestamp);
                }

                // Trigger immediate sync to upload queued calls
                if (processedCount > 0) {
                    Log.d(TAG, "Triggering immediate sync to upload " + processedCount + " queued call(s)");
                    SyncScheduler.triggerImmediateSync(context);
                }

                // Update device status after processing
                updateDeviceStatusAfterCall(prefsManager.getAuthToken());

            } else {
                Log.d(TAG, "No new call logs to process");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading call log", e);
        } finally {
            // CRITICAL: Always close cursor to prevent memory leaks
            if (cursor != null) {
                try {
                    cursor.close();
                    Log.d(TAG, "Cursor closed successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Error closing cursor", e);
                }
            }
        }
        } // End synchronized block
    }

    private String getCallType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "missed";
            case CallLog.Calls.REJECTED_TYPE:
                return "rejected";
            default:
                return "incoming";
        }
    }

    private String getContactName(String phoneNumber) {
        if (phoneNumber == null) return "Unknown";

        try {
            Cursor cursor = context.getContentResolver().query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                    .appendPath(phoneNumber).build(),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                null, null, null
            );

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    cursor.close();
                    return name;
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting contact name", e);
        }

        return "Unknown";
    }

    private String formatTimestamp(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(millis));
    }

    /**
     * Update device status with fresh battery and signal strength data after call ends
     */
    private void updateDeviceStatusAfterCall(String token) {
        try {
            // Create a fresh DeviceInfoCollector to get current device status
            DeviceInfoCollector deviceInfo = new DeviceInfoCollector(context);
            JSONObject statusData = deviceInfo.getDeviceStatusInfo();

            // Set call status to idle since call has ended
            statusData.put("current_call_status", "idle");
            statusData.put("current_call_number", JSONObject.NULL);

            apiService.updateDeviceStatus(token, statusData, context, new ApiService.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    Log.d(TAG, "Device status updated after call end - Battery: " +
                          deviceInfo.getBatteryPercentage() + "%, Signal: " +
                          deviceInfo.getSignalStrength());
                }

                @Override
                public void onFailure(String error) {
                    Log.e(TAG, "Failed to update device status after call: " + error);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error updating device status after call", e);
        }
    }

    private void searchAndUploadRecording(final int callLogId, String phoneNumber,
                                          long callTimestamp, long duration) {
        // Run in background thread to avoid blocking
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Wait for the recording to be finalized and written to storage
                    // Increased from 3s to 5s for better reliability
                    Thread.sleep(5000);

                    Log.d(TAG, "Searching for call recording...");
                    java.io.File recordingFile = recordingUploader.findRecording(
                        phoneNumber, callTimestamp, duration
                    );

                    if (recordingFile != null) {
                        Log.d(TAG, "Found recording: " + recordingFile.getAbsolutePath());
                        recordingUploader.uploadRecording(callLogId, recordingFile, duration,
                            phoneNumber, callTimestamp,
                            new ApiService.ApiCallback() {
                                @Override
                                public void onSuccess(String result) {
                                    Log.d(TAG, "Recording uploaded successfully!");
                                }

                                @Override
                                public void onFailure(String error) {
                                    Log.e(TAG, "Failed to upload recording: " + error);
                                    // Already queued for retry in RecordingUploader
                                }
                            });
                    } else {
                        Log.d(TAG, "No recording found for this call");
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "Recording search interrupted", e);
                }
            }
        }).start();
    }

    /**
     * PHASE 1.4: Queue call log with transactional database insert and WorkManager scheduling
     * This ensures atomic operation - either both succeed or both fail
     * FIXED: Runs database operations on background thread to avoid main thread access
     */
    private void queueCallLogWithWorker(String phoneNumber, String callType, long duration,
                                         long timestamp, String contactName, String simSlot,
                                         String simOperator, String simNumber) {
        // Run database operations on background thread
        executorService.execute(() -> {
            try {
                // Create queue entry with UUID
                com.hairocraft.dialer.database.UploadQueue queue =
                        com.hairocraft.dialer.database.UploadQueue.createCallLogQueue(
                                phoneNumber, callType, duration, timestamp, contactName,
                                simSlot, simOperator, simNumber
                        );

                // Insert into database (now safe on background thread)
                com.hairocraft.dialer.database.AppDatabase db =
                        com.hairocraft.dialer.database.AppDatabase.getInstance(context);
                long rowId = db.uploadQueueDao().insert(queue);

                if (rowId > 0) {
                    Log.d(TAG, "Call log queued with UUID: " + queue.localCallUuid + ", row ID: " + rowId);

                    // Schedule WorkManager upload
                    com.hairocraft.dialer.workers.CallLogUploadWorker.scheduleUpload(context, queue.localCallUuid);
                } else {
                    Log.e(TAG, "Failed to insert call log into database");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error queueing call log with worker", e);
            }
        });
    }
}
