package com.hairocraft.dialer;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsManager {
    private static final String PREFS_NAME = "HairocraftDialerPrefs";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_LAST_PROCESSED_CALL_TIME = "last_processed_call_time";

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;

    public PrefsManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        editor = prefs.edit();
    }

    public void saveAuthToken(String token) {
        editor.putString(KEY_AUTH_TOKEN, token);
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.apply();
    }

    public String getAuthToken() {
        return prefs.getString(KEY_AUTH_TOKEN, null);
    }

    public void saveUserId(String userId) {
        editor.putString(KEY_USER_ID, userId);
        editor.apply();
    }

    public String getUserId() {
        return prefs.getString(KEY_USER_ID, "");
    }

    public void saveUserName(String userName) {
        editor.putString(KEY_USER_NAME, userName);
        editor.apply();
    }

    public String getUserName() {
        return prefs.getString(KEY_USER_NAME, null);
    }

    @Deprecated
    public void saveUserEmail(String email) {
        editor.putString(KEY_USER_EMAIL, email);
        editor.apply();
    }

    @Deprecated
    public String getUserEmail() {
        return prefs.getString(KEY_USER_EMAIL, "");
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false) && getAuthToken() != null;
    }

    public void logout() {
        editor.clear();
        editor.apply();
    }

    /**
     * Save the timestamp of the last successfully processed call log
     * This ensures we don't process the same call multiple times
     */
    public void saveLastProcessedCallTime(long timestamp) {
        editor.putLong(KEY_LAST_PROCESSED_CALL_TIME, timestamp);
        editor.apply();
    }

    /**
     * Get the timestamp of the last processed call log
     * Returns 0 if no calls have been processed yet
     */
    public long getLastProcessedCallTime() {
        return prefs.getLong(KEY_LAST_PROCESSED_CALL_TIME, 0);
    }
}
