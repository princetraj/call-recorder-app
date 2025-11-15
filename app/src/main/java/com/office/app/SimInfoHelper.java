package com.office.app;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.CallLog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import org.json.JSONObject;
import java.util.List;

public class SimInfoHelper {
    private static final String TAG = "SimInfoHelper";
    private Context context;
    private SubscriptionManager subscriptionManager;
    private TelephonyManager telephonyManager;

    public SimInfoHelper(Context context) {
        this.context = context;
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            this.subscriptionManager = SubscriptionManager.from(context);
        }
    }

    /**
     * Get SIM information for a specific call log entry
     */
    public JSONObject getSimInfoForCallLog(Cursor callLogCursor) {
        JSONObject simInfo = new JSONObject();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // Try to get phone account ID from call log (available from API 21+)
                int phoneAccountIdIndex = callLogCursor.getColumnIndex("phone_account_id");

                if (phoneAccountIdIndex >= 0) {
                    String phoneAccountId = callLogCursor.getString(phoneAccountIdIndex);

                    if (phoneAccountId != null && !phoneAccountId.isEmpty()) {
                        // Get SIM info based on phone account ID
                        JSONObject details = getSimInfoByPhoneAccountId(phoneAccountId);
                        if (details != null) {
                            return details;
                        }
                    }
                }
            }

            // Fallback: try to get default SIM info
            return getDefaultSimInfo();

        } catch (Exception e) {
            Log.e(TAG, "Error getting SIM info from call log", e);
        }

        return simInfo;
    }

    /**
     * Get SIM information by phone account ID
     */
    private JSONObject getSimInfoByPhoneAccountId(String phoneAccountId) {
        JSONObject simInfo = new JSONObject();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionManager != null) {
                List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();

                if (subscriptionInfoList != null) {
                    for (SubscriptionInfo subInfo : subscriptionInfoList) {
                        String subIdStr = String.valueOf(subInfo.getSubscriptionId());

                        // Match phone account ID with subscription ID
                        if (phoneAccountId.contains(subIdStr)) {
                            simInfo.put("sim_slot_index", subInfo.getSimSlotIndex());
                            simInfo.put("sim_name", getCarrierName(subInfo));
                            simInfo.put("sim_number", getPhoneNumber(subInfo));
                            simInfo.put("sim_serial_number", getSimSerialNumber(subInfo));

                            Log.d(TAG, "Found SIM info for slot " + subInfo.getSimSlotIndex() +
                                  ": " + simInfo.toString());
                            return simInfo;
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied to access SIM info", e);
        } catch (Exception e) {
            Log.e(TAG, "Error getting SIM info by phone account ID", e);
        }

        return null;
    }

    /**
     * Get default SIM information (when dual SIM detection fails)
     */
    private JSONObject getDefaultSimInfo() {
        JSONObject simInfo = new JSONObject();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionManager != null) {
                List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();

                if (subscriptionInfoList != null && !subscriptionInfoList.isEmpty()) {
                    // Get the first active SIM
                    SubscriptionInfo subInfo = subscriptionInfoList.get(0);

                    simInfo.put("sim_slot_index", subInfo.getSimSlotIndex());
                    simInfo.put("sim_name", getCarrierName(subInfo));
                    simInfo.put("sim_number", getPhoneNumber(subInfo));
                    simInfo.put("sim_serial_number", getSimSerialNumber(subInfo));

                    Log.d(TAG, "Using default SIM info from slot " + subInfo.getSimSlotIndex());
                }
            } else {
                // For devices below API 22, get basic info from TelephonyManager
                if (telephonyManager != null) {
                    simInfo.put("sim_slot_index", 0);
                    simInfo.put("sim_name", telephonyManager.getNetworkOperatorName());

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            simInfo.put("sim_number", telephonyManager.getLine1Number());
                        }
                    } catch (SecurityException e) {
                        Log.w(TAG, "Permission denied to read phone number");
                    }
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied to access SIM info", e);
        } catch (Exception e) {
            Log.e(TAG, "Error getting default SIM info", e);
        }

        return simInfo;
    }

    /**
     * Get carrier name from SubscriptionInfo
     */
    private String getCarrierName(SubscriptionInfo subInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                CharSequence carrierName = subInfo.getCarrierName();
                if (carrierName != null && !carrierName.toString().isEmpty()) {
                    return carrierName.toString();
                }

                // Fallback to display name
                CharSequence displayName = subInfo.getDisplayName();
                if (displayName != null && !displayName.toString().isEmpty()) {
                    return displayName.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting carrier name", e);
        }

        return null;
    }

    /**
     * Get phone number from SubscriptionInfo
     */
    private String getPhoneNumber(SubscriptionInfo subInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                String number = subInfo.getNumber();
                if (number != null && !number.isEmpty()) {
                    return number;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting phone number", e);
        }

        return null;
    }

    /**
     * Get SIM serial number (ICCID) from SubscriptionInfo
     */
    private String getSimSerialNumber(SubscriptionInfo subInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                String iccId = subInfo.getIccId();
                if (iccId != null && !iccId.isEmpty()) {
                    return iccId;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting SIM serial number", e);
        }

        return null;
    }

    /**
     * Check if device has dual SIM support
     */
    public boolean isDualSimDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionManager != null) {
                List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
                return subscriptionInfoList != null && subscriptionInfoList.size() > 1;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied to check dual SIM", e);
        } catch (Exception e) {
            Log.e(TAG, "Error checking dual SIM", e);
        }

        return false;
    }

    /**
     * Get count of active SIM cards
     */
    public int getActiveSimCount() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionManager != null) {
                List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
                return subscriptionInfoList != null ? subscriptionInfoList.size() : 0;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied to get SIM count", e);
        } catch (Exception e) {
            Log.e(TAG, "Error getting SIM count", e);
        }

        return 0;
    }
}
