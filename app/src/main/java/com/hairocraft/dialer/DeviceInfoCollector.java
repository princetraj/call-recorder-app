package com.hairocraft.dialer;

import android.Manifest;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.PhoneStateListener;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;

public class DeviceInfoCollector {

    private Context context;
    private int currentSignalStrength = -1;

    public DeviceInfoCollector(Context context) {
        this.context = context;
        startSignalStrengthMonitoring();
    }

    /**
     * Get unique device ID (Android ID)
     */
    public String getDeviceId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /**
     * Get device manufacturer
     */
    public String getManufacturer() {
        return Build.MANUFACTURER;
    }

    /**
     * Get device model
     */
    public String getDeviceModel() {
        return Build.MODEL;
    }

    /**
     * Get Android OS version
     */
    public String getOsVersion() {
        return Build.VERSION.RELEASE;
    }

    /**
     * Get app version from BuildConfig
     */
    public String getAppVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /**
     * Get current connection type: wifi, mobile, or none
     */
    public String getConnectionType() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return "none";
        }

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
            return "none";
        }

        if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
            return "wifi";
        } else if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) {
            return "mobile";
        }

        return "none";
    }

    /**
     * Get battery percentage
     */
    public int getBatteryPercentage() {
        try {
            BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (batteryManager != null) {
                return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * Check if device is charging
     */
    public boolean isCharging() {
        try {
            IntentFilter ifilter = new IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent batteryStatus = context.registerReceiver(null, ifilter);

            if (batteryStatus != null) {
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Get signal strength (0-4: none, poor, moderate, good, great)
     */
    public int getSignalStrength() {
        // If currentSignalStrength is not yet set, try to get it immediately
        if (currentSignalStrength == -1) {
            return getImmediateSignalStrength();
        }
        return currentSignalStrength;
    }

    /**
     * Get immediate signal strength without waiting for listener
     */
    private int getImmediateSignalStrength() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // For Android P and above, we can get signal strength directly
                android.telephony.SignalStrength signalStrength = telephonyManager.getSignalStrength();
                if (signalStrength != null) {
                    return signalStrength.getLevel();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Return 0 (none) if we can't get signal strength
        return 0;
    }

    /**
     * Start monitoring signal strength
     */
    private void startSignalStrengthMonitoring() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                PhoneStateListener phoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                        super.onSignalStrengthsChanged(signalStrength);
                        try {
                            // Get signal level (0-4)
                            Method method = signalStrength.getClass().getMethod("getLevel");
                            currentSignalStrength = (int) method.invoke(signalStrength);
                        } catch (Exception e) {
                            // Fallback to basic signal strength calculation
                            int gsmSignalStrength = signalStrength.getGsmSignalStrength();
                            currentSignalStrength = calculateSignalLevel(gsmSignalStrength);
                        }
                    }
                };

                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
            }
        } catch (Exception e) {
            e.printStackTrace();
            currentSignalStrength = 0;
        }
    }

    /**
     * Calculate signal level from GSM signal strength
     */
    private int calculateSignalLevel(int gsmSignalStrength) {
        if (gsmSignalStrength <= 2 || gsmSignalStrength == 99) {
            return 0; // None
        } else if (gsmSignalStrength <= 10) {
            return 1; // Poor
        } else if (gsmSignalStrength <= 15) {
            return 2; // Moderate
        } else if (gsmSignalStrength <= 20) {
            return 3; // Good
        } else {
            return 4; // Great
        }
    }

    /**
     * Get device registration info as JSON
     */
    public JSONObject getDeviceRegistrationInfo() {
        JSONObject json = new JSONObject();
        try {
            json.put("device_id", getDeviceId());
            json.put("device_model", getDeviceModel());
            json.put("manufacturer", getManufacturer());
            json.put("os_version", getOsVersion());
            json.put("app_version", getAppVersion());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    /**
     * Check if a specific permission is granted
     */
    public boolean isPermissionGranted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Get all permissions status
     */
    public JSONObject getPermissionsStatus() {
        JSONObject permissions = new JSONObject();
        try {
            permissions.put("read_call_log", isPermissionGranted(Manifest.permission.READ_CALL_LOG));
            permissions.put("read_phone_state", isPermissionGranted(Manifest.permission.READ_PHONE_STATE));
            permissions.put("read_contacts", isPermissionGranted(Manifest.permission.READ_CONTACTS));

            // Android 13+ (API 33+) uses READ_MEDIA_AUDIO instead of READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.put("read_external_storage", false);  // Not applicable on Android 13+
                permissions.put("read_media_audio", isPermissionGranted(Manifest.permission.READ_MEDIA_AUDIO));
            } else {
                permissions.put("read_external_storage", isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE));
                permissions.put("read_media_audio", false);  // Not applicable on Android 12 and below
            }

            permissions.put("post_notifications", isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return permissions;
    }

    /**
     * Get device status info as JSON
     */
    public JSONObject getDeviceStatusInfo() {
        JSONObject json = new JSONObject();
        try {
            json.put("device_id", getDeviceId());
            json.put("connection_type", getConnectionType());
            json.put("battery_percentage", getBatteryPercentage());
            json.put("signal_strength", getSignalStrength());
            json.put("is_charging", isCharging());
            json.put("app_running_status", "active");
            json.put("permissions", getPermissionsStatus());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }
}
