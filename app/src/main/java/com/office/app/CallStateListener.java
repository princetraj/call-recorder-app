package com.office.app;

import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import org.json.JSONObject;

public class CallStateListener extends PhoneStateListener {
    private static final String TAG = "CallStateListener";
    private Context context;
    private String currentCallNumber = null;
    private String currentCallStatus = "idle";
    private boolean isIncomingCall = false;
    private static String lastOutgoingNumber = null;

    public CallStateListener(Context context) {
        this.context = context;
    }

    public static void setLastOutgoingNumber(String number) {
        lastOutgoingNumber = number;
    }

    @Override
    public void onCallStateChanged(int state, String phoneNumber) {
        super.onCallStateChanged(state, phoneNumber);

        String previousStatus = currentCallStatus;

        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE:
                // No call activity
                currentCallStatus = "idle";
                currentCallNumber = null;
                isIncomingCall = false;
                lastOutgoingNumber = null; // Clear the cached outgoing number
                Log.d(TAG, "Call ended - Status: idle");
                break;

            case TelephonyManager.CALL_STATE_RINGING:
                // Incoming call ringing
                currentCallStatus = "in_call";
                currentCallNumber = phoneNumber;
                isIncomingCall = true;
                Log.d(TAG, "Incoming call from: " + phoneNumber);
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                // Call is active (either outgoing or answered incoming)
                currentCallStatus = "in_call";

                // Get the call number
                if (isIncomingCall) {
                    // For incoming calls, update number if available
                    if (phoneNumber != null && !phoneNumber.isEmpty()) {
                        currentCallNumber = phoneNumber;
                    }
                } else {
                    // For outgoing calls, use the cached number from OutgoingCallReceiver
                    if (lastOutgoingNumber != null && !lastOutgoingNumber.isEmpty()) {
                        currentCallNumber = lastOutgoingNumber;
                    }
                }
                Log.d(TAG, "Call active - Status: in_call, Number: " + currentCallNumber);
                break;
        }

        // Only send update if status changed
        if (!currentCallStatus.equals(previousStatus)) {
            sendCallStatusUpdate();
        }
    }

    private void sendCallStatusUpdate() {
        PrefsManager prefsManager = new PrefsManager(context);

        if (!prefsManager.isLoggedIn()) {
            return;
        }

        String token = prefsManager.getAuthToken();
        if (token == null || token.isEmpty()) {
            return;
        }

        try {
            DeviceInfoCollector deviceInfo = new DeviceInfoCollector(context);
            JSONObject statusData = deviceInfo.getDeviceStatusInfo();

            // Add call status
            statusData.put("current_call_status", currentCallStatus);
            statusData.put("current_call_number", currentCallNumber != null ? currentCallNumber : JSONObject.NULL);

            ApiService apiService = ApiService.getInstance();
            apiService.updateDeviceStatus(token, statusData, context, new ApiService.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    Log.d(TAG, "Call status updated: " + currentCallStatus);
                }

                @Override
                public void onFailure(String error) {
                    Log.e(TAG, "Failed to update call status: " + error);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error sending call status update", e);
        }
    }

    public String getCurrentCallStatus() {
        return currentCallStatus;
    }

    public String getCurrentCallNumber() {
        return currentCallNumber;
    }
}
