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
import org.json.JSONObject;
import com.hairocraft.dialer.sync.SyncManager;
import com.hairocraft.dialer.sync.SyncScheduler;

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
    private String lastProcessedNumber = null;
    private long lastProcessedTime = 0;

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
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                super.onCallStateChanged(state, phoneNumber);

                Log.d(TAG, "Call state changed: " + getStateString(state) +
                      ", Number: " + phoneNumber +
                      ", lastProcessedNumber: " + lastProcessedNumber +
                      ", lastProcessedTime: " + lastProcessedTime);

                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        Log.d(TAG, "Incoming call from: " + phoneNumber);
                        lastProcessedNumber = phoneNumber;
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        Log.d(TAG, "Call answered or outgoing call");
                        if (lastProcessedNumber == null) {
                            lastProcessedNumber = phoneNumber;
                        }
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        Log.d(TAG, "Call ended - Processing upload");

                        // Only process if we have a tracked call
                        if (lastProcessedNumber != null) {
                            Log.d(TAG, "Valid call to process, waiting 2s for call log to be written");
                            // Wait a bit for call log to be written
                            new android.os.Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    uploadLatestCallLog();
                                    lastProcessedNumber = null;
                                }
                            }, 2000); // Wait 2 seconds
                        } else {
                            Log.d(TAG, "No call to process (lastProcessedNumber is null), likely initial IDLE state");
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
        Log.d(TAG, "uploadLatestCallLog() called - lastProcessedTime: " + lastProcessedTime +
              ", lastProcessedNumber: " + lastProcessedNumber);

        if (!prefsManager.isLoggedIn()) {
            Log.w(TAG, "User not logged in, skipping upload");
            return;
        }

        // Trigger sync of any pending uploads from previous failed attempts
        Log.d(TAG, "Triggering sync of pending uploads before processing new call");
        SyncScheduler.triggerImmediateSync(context);

        try {
            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            );

            if (cursor != null && cursor.moveToFirst()) {
                String number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                int type = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE));
                long duration = cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DURATION));
                long dateMillis = cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE));

                Log.d(TAG, "Latest call from system: Number=" + number +
                      ", Type=" + getCallType(type) +
                      ", Duration=" + duration +
                      ", Timestamp=" + dateMillis);

                // Check if we already processed this call
                boolean timeMatches = (dateMillis == lastProcessedTime);
                boolean numberMatches = (number != null && number.equals(lastProcessedNumber));
                Log.d(TAG, "Duplicate check: timeMatches=" + timeMatches +
                      ", numberMatches=" + numberMatches);

                if (timeMatches && numberMatches) {
                    cursor.close();
                    Log.w(TAG, "Call already processed, skipping (duplicate detected)");
                    return;
                }

                lastProcessedTime = dateMillis;
                Log.d(TAG, "Setting lastProcessedTime to: " + lastProcessedTime);

                String callType = getCallType(type);
                String callerName = getContactName(number);
                String timestamp = formatTimestamp(dateMillis);

                // Get SIM information
                JSONObject simInfo = simInfoHelper.getSimInfoForCallLog(cursor);

                cursor.close();

                Log.d(TAG, "Uploading call log - Number: " + number + ", Type: " + callType +
                      ", Duration: " + duration + ", SIM: " + simInfo.toString());

                String token = prefsManager.getAuthToken();
                final String finalNumber = number;
                final long finalDuration = duration;
                final long finalDateMillis = dateMillis;
                final String finalCallType = callType;
                final String finalCallerName = callerName;
                final JSONObject finalSimInfo = simInfo;

                apiService.uploadCallLog(token, callerName, number, callType, duration, timestamp, simInfo,
                    new ApiService.ApiCallback() {
                        @Override
                        public void onSuccess(String result) {
                            Log.d(TAG, "Call log uploaded successfully");

                            // Update device status after call ends
                            updateDeviceStatusAfterCall(token);

                            // Try to parse call log ID from result
                            try {
                                int callLogId = Integer.parseInt(result);
                                Log.d(TAG, "Call log ID: " + callLogId);

                                // Search for recording and upload if found
                                searchAndUploadRecording(callLogId, finalNumber, finalDateMillis, finalDuration);
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Could not parse call log ID from response");
                            }
                        }

                        @Override
                        public void onFailure(String error) {
                            Log.e(TAG, "Failed to upload call log: " + error);

                            // Queue for retry
                            String simSlot = finalSimInfo != null ? finalSimInfo.optString("sim_slot_index", null) : null;
                            String simOperator = finalSimInfo != null ? finalSimInfo.optString("sim_name", null) : null;
                            String simNumber = finalSimInfo != null ? finalSimInfo.optString("sim_number", null) : null;

                            syncManager.queueCallLog(
                                finalNumber,
                                finalCallType,
                                finalDuration,
                                finalDateMillis,
                                finalCallerName,
                                simSlot,
                                simOperator,
                                simNumber
                            );
                            Log.d(TAG, "Call log queued for retry");

                            // Still update device status even if call log upload failed
                            updateDeviceStatusAfterCall(token);
                        }
                    });
            } else {
                Log.w(TAG, "No call logs found");
                if (cursor != null) {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading call log", e);
        }
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
                    // Wait a bit for the recording to be finalized
                    Thread.sleep(3000);

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
}
