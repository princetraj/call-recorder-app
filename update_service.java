// Add after line 25 in PersistentService.java:
    private CallStateListener callStateListener;
    private TelephonyManager telephonyManager;

// Add in onCreate() after line 40:
        // Setup call state listener
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        callStateListener = new CallStateListener(this);
        telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE);

// Add in onDestroy() after line 87:
        // Stop call state listener
        if (telephonyManager != null && callStateListener != null) {
            telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_NONE);
        }

// Add imports:
import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
