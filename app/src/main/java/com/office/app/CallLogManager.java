package com.office.app;

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

public class CallLogManager {
    private static final String TAG = "CallLogManager";
    private Context context;
    private PrefsManager prefsManager;
    private ApiService apiService;
    private RecordingUploader recordingUploader;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private String lastProcessedNumber = null;
    private long lastProcessedTime = 0;

    public CallLogManager(Context context) {
        this.context = context;
        this.prefsManager = new PrefsManager(context);
        this.apiService = ApiService.getInstance();
        this.recordingUploader = new RecordingUploader(context);
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public void startListening() {
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                super.onCallStateChanged(state, phoneNumber);

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
                        Log.d(TAG, "Call ended");
                        // Wait a bit for call log to be written
                        new android.os.Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                uploadLatestCallLog();
                                lastProcessedNumber = null;
                            }
                        }, 2000); // Wait 2 seconds
                        break;
                }
            }
        };

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        Log.d(TAG, "Started listening for call state changes");
    }

    public void stopListening() {
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            Log.d(TAG, "Stopped listening for call state changes");
        }
    }

    private void uploadLatestCallLog() {
        if (!prefsManager.isLoggedIn()) {
            Log.w(TAG, "User not logged in, skipping upload");
            return;
        }

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

                // Check if we already processed this call
                if (dateMillis == lastProcessedTime && number != null && number.equals(lastProcessedNumber)) {
                    cursor.close();
                    Log.d(TAG, "Call already processed, skipping");
                    return;
                }

                lastProcessedTime = dateMillis;

                String callType = getCallType(type);
                String callerName = getContactName(number);
                String timestamp = formatTimestamp(dateMillis);

                cursor.close();

                Log.d(TAG, "Uploading call log - Number: " + number + ", Type: " + callType + ", Duration: " + duration);

                String token = prefsManager.getAuthToken();
                final String finalNumber = number;
                final long finalDuration = duration;
                final long finalDateMillis = dateMillis;

                apiService.uploadCallLog(token, callerName, number, callType, duration, timestamp,
                    new ApiService.ApiCallback() {
                        @Override
                        public void onSuccess(String result) {
                            Log.d(TAG, "Call log uploaded successfully");

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
                            new ApiService.ApiCallback() {
                                @Override
                                public void onSuccess(String result) {
                                    Log.d(TAG, "Recording uploaded successfully!");
                                }

                                @Override
                                public void onFailure(String error) {
                                    Log.e(TAG, "Failed to upload recording: " + error);
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
