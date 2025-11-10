package com.office.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class OutgoingCallReceiver extends BroadcastReceiver {
    private static final String TAG = "OutgoingCallReceiver";
    private static String lastOutgoingNumber = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) {
            String phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                lastOutgoingNumber = phoneNumber;
                Log.d(TAG, "Outgoing call detected: " + phoneNumber);

                // Notify CallStateListener about the outgoing number
                CallStateListener.setLastOutgoingNumber(phoneNumber);
            }
        }
    }

    public static String getLastOutgoingNumber() {
        return lastOutgoingNumber;
    }

    public static void clearLastOutgoingNumber() {
        lastOutgoingNumber = null;
    }
}
