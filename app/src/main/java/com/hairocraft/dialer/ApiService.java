package com.hairocraft.dialer;

import android.util.Log;
import com.google.gson.Gson;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ApiService {
    private static final String TAG = "ApiService";
    // DEVELOPMENT: Using local IP for physical device testing
    // private static final String BASE_URL = "http://192.168.1.3:8000/api"; // Physical device testing
    // EMULATOR: Using 10.0.2.2 to access host machine's localhost from Android emulator
    // private static final String BASE_URL = "http://10.0.2.2:8000/api"; // Emulator testing
    // PRODUCTION: Production server URL
    private static final String BASE_URL = "https://calllog.aptinfotech.com/api"; // Production server

    private static ApiService instance;
    private OkHttpClient client;
    private Gson gson;

    private ApiService() {
        client = new OkHttpClient.Builder()
                .connectTimeout(40, TimeUnit.SECONDS)  // Increased from 30s
                .writeTimeout(90, TimeUnit.SECONDS)    // Increased from 30s for large file uploads
                .readTimeout(40, TimeUnit.SECONDS)     // Increased from 30s
                .build();
        gson = new Gson();
    }

    public static synchronized ApiService getInstance() {
        if (instance == null) {
            instance = new ApiService();
        }
        return instance;
    }

    // Login to get auth token
    public void login(String username, String password, android.content.Context context, final LoginCallback callback) {
        try {
            DeviceInfoCollector deviceInfo = new DeviceInfoCollector(context);
            JSONObject json = new JSONObject();
            json.put("username", username);
            json.put("password", password);
            json.put("device_id", deviceInfo.getDeviceId());

            RequestBody body = RequestBody.create(
                json.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/login")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Login failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            JSONObject data = jsonResponse.getJSONObject("data");
                            String token = data.getString("token");
                            JSONObject user = data.getJSONObject("user");
                            String userName = user.optString("name", "");
                            callback.onSuccess(token, userName);
                        } else {
                            String message = jsonResponse.optString("message", "Login failed");
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing login response", e);
                        callback.onFailure("Error parsing response");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating login request", e);
            callback.onFailure("Error: " + e.getMessage());
        }
    }



    // Verify token validity
    public void verifyToken(String token, final VerifyCallback callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/me")
                .get()
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Token verification failed", e);
                callback.onInvalid("Network error");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                try {
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                        JSONObject data = jsonResponse.getJSONObject("data");
                        JSONObject user = data.getJSONObject("user");
                        String userName = user.optString("name", "");
                        String status = user.optString("status", "");
                        
                        // Check if user is active
                        if ("active".equals(status)) {
                            callback.onValid(userName);
                        } else {
                            callback.onInvalid("Account is inactive");
                        }
                    } else {
                        callback.onInvalid("Token invalid");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing verify response", e);
                    callback.onInvalid("Error parsing response");
                }
            }
        });
    }

    // Upload single call log
    public void uploadCallLog(String token, String callerName, String callerNumber,
                              String callType, long duration, String timestamp,
                              JSONObject simInfo, final ApiCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("caller_name", callerName != null ? callerName : "Unknown");
            json.put("caller_number", callerNumber);
            json.put("call_type", callType);
            json.put("call_duration", duration);
            json.put("call_timestamp", timestamp);

            // Add SIM information if available
            if (simInfo != null) {
                if (simInfo.has("sim_slot_index")) {
                    json.put("sim_slot_index", simInfo.get("sim_slot_index"));
                }
                if (simInfo.has("sim_name")) {
                    json.put("sim_name", simInfo.get("sim_name"));
                }
                if (simInfo.has("sim_number")) {
                    json.put("sim_number", simInfo.get("sim_number"));
                }
                if (simInfo.has("sim_serial_number")) {
                    json.put("sim_serial_number", simInfo.get("sim_serial_number"));
                }
            }

            RequestBody body = RequestBody.create(
                json.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/call-logs")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Upload call log failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Call log upload response code: " + response.code());
                    Log.d(TAG, "Call log upload response body: " + responseBody);

                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Log.d(TAG, "Call log uploaded successfully");

                            // IMPROVED: Robust call_log_id extraction with multiple fallback strategies
                            Integer callLogId = null;

                            // Strategy 1: Extract from data.call_logs array (expected format)
                            if (jsonResponse.has("data")) {
                                JSONObject data = jsonResponse.getJSONObject("data");

                                if (data.has("call_logs")) {
                                    JSONArray callLogs = data.getJSONArray("call_logs");
                                    if (callLogs.length() > 0) {
                                        JSONObject callLog = callLogs.getJSONObject(0);
                                        if (callLog.has("id")) {
                                            callLogId = callLog.getInt("id");
                                            Log.d(TAG, "Extracted call_log_id from data.call_logs[0].id: " + callLogId);
                                        }
                                    }
                                }

                                // Strategy 2: Fallback - Try top-level data.id
                                if (callLogId == null && data.has("id")) {
                                    callLogId = data.getInt("id");
                                    Log.d(TAG, "Extracted call_log_id from data.id (fallback): " + callLogId);
                                }

                                // Strategy 3: Fallback - Try data.call_log_id
                                if (callLogId == null && data.has("call_log_id")) {
                                    callLogId = data.getInt("call_log_id");
                                    Log.d(TAG, "Extracted call_log_id from data.call_log_id (fallback): " + callLogId);
                                }
                            }

                            // Strategy 4: Fallback - Try top-level call_log_id
                            if (callLogId == null && jsonResponse.has("call_log_id")) {
                                callLogId = jsonResponse.getInt("call_log_id");
                                Log.d(TAG, "Extracted call_log_id from root (fallback): " + callLogId);
                            }

                            // Validate extracted ID
                            if (callLogId != null && callLogId > 0) {
                                Log.d(TAG, "Successfully extracted call_log_id: " + callLogId);
                                callback.onSuccess(String.valueOf(callLogId));
                            } else {
                                // All extraction strategies failed
                                Log.e(TAG, "Failed to extract valid call_log_id from response");
                                Log.e(TAG, "Response body: " + responseBody);
                                callback.onFailure("Call log ID not found in response");
                            }
                        } else {
                            String message = jsonResponse.optString("message", "Upload failed");
                            Log.e(TAG, "Call log upload failed: " + message);
                            // Log validation errors if present
                            if (jsonResponse.has("errors")) {
                                Log.e(TAG, "Validation errors: " + jsonResponse.get("errors").toString());
                            }
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing upload response. Body: " + responseBody, e);
                        callback.onFailure("Error parsing response: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating upload request", e);
            callback.onFailure("Error: " + e.getMessage());
        }
    }

    // Upload call recording file
    public void uploadRecording(String token, int callLogId, java.io.File recordingFile,
                                long duration, final ApiCallback callback) {
        try {
            // Get the correct MIME type based on file extension
            String mimeType = getMimeTypeFromFileName(recordingFile.getName());
            Log.d(TAG, "Uploading recording with MIME type: " + mimeType + ", file: " + recordingFile.getName());

            // Create multipart request body
            RequestBody fileBody = RequestBody.create(
                recordingFile,
                MediaType.parse(mimeType)
            );

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("call_log_id", String.valueOf(callLogId))
                    .addFormDataPart("duration", String.valueOf(duration))
                    .addFormDataPart("recording", recordingFile.getName(), fileBody)
                    .build();

            Request request = new Request.Builder()
                    .url(BASE_URL + "/call-recordings")
                    .post(requestBody)
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Upload recording failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Recording upload response code: " + response.code());
                    Log.d(TAG, "Recording upload response body: " + responseBody);
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Log.d(TAG, "Recording uploaded successfully");
                            callback.onSuccess("Recording uploaded");
                        } else {
                            String message = jsonResponse.optString("message", "Upload failed");
                            // Log validation errors if present
                            if (jsonResponse.has("errors")) {
                                Log.e(TAG, "Validation errors: " + jsonResponse.getJSONObject("errors").toString());
                            }
                            Log.e(TAG, "Recording upload failed: " + message);
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing upload response: " + responseBody, e);
                        callback.onFailure("Error parsing response");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating recording upload request", e);
            callback.onFailure("Error: " + e.getMessage());
        }
    }

    // PHASE 1.2: Upload call log with idempotency key
    public void uploadCallLogWithIdempotency(String token, String idempotencyKey,
                                              String callerName, String callerNumber,
                                              String callType, long duration, String timestamp,
                                              JSONObject simInfo, final ApiCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("caller_name", callerName != null ? callerName : "Unknown");
            json.put("caller_number", callerNumber);
            json.put("call_type", callType);
            json.put("call_duration", duration);
            json.put("call_timestamp", timestamp);

            // Add SIM information if available
            if (simInfo != null) {
                if (simInfo.has("sim_slot_index")) {
                    json.put("sim_slot_index", simInfo.get("sim_slot_index"));
                }
                if (simInfo.has("sim_name")) {
                    json.put("sim_name", simInfo.get("sim_name"));
                }
                if (simInfo.has("sim_number")) {
                    json.put("sim_number", simInfo.get("sim_number"));
                }
            }

            RequestBody body = RequestBody.create(
                json.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/call-logs")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Idempotency-Key", idempotencyKey) // PHASE 1.2
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Upload call log failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Integer callLogId = extractCallLogId(jsonResponse, responseBody);
                            if (callLogId != null && callLogId > 0) {
                                callback.onSuccess(String.valueOf(callLogId));
                            } else {
                                callback.onFailure("Call log ID not found");
                            }
                        } else {
                            String message = jsonResponse.optString("message", "Upload failed");
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        callback.onFailure("Error parsing response: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure("Error: " + e.getMessage());
        }
    }

    // PHASE 1.2: Upload recording with idempotency key and checksum
    public void uploadRecordingWithIdempotency(String token, String idempotencyKey,
                                                String checksum, int callLogId,
                                                java.io.File recordingFile, long duration,
                                                final ApiCallback callback) {
        try {
            RequestBody fileBody = RequestBody.create(
                recordingFile,
                MediaType.parse("audio/*")
            );

            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("call_log_id", String.valueOf(callLogId))
                    .addFormDataPart("duration", String.valueOf(duration))
                    .addFormDataPart("recording", recordingFile.getName(), fileBody);

            // PHASE 4.3: Add checksum if available
            if (checksum != null && !checksum.isEmpty()) {
                builder.addFormDataPart("checksum", checksum);
            }

            RequestBody requestBody = builder.build();

            Request request = new Request.Builder()
                    .url(BASE_URL + "/call-recordings")
                    .post(requestBody)
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("Idempotency-Key", idempotencyKey) // PHASE 1.2
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Recording upload failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Log.d(TAG, "Recording uploaded successfully");
                            callback.onSuccess("Recording uploaded");
                        } else {
                            String message = jsonResponse.optString("message", "Upload failed");
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        callback.onFailure("Error parsing response");
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure("Error: " + e.getMessage());
        }
    }

    /**
     * Get MIME type from file extension
     * Returns appropriate MIME type that the server will accept
     */
    private String getMimeTypeFromFileName(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }

        String lowerName = fileName.toLowerCase();

        // Audio formats
        if (lowerName.endsWith(".m4a")) {
            return "audio/m4a";
        } else if (lowerName.endsWith(".mp3")) {
            return "audio/mpeg";
        } else if (lowerName.endsWith(".wav")) {
            return "audio/wav";
        } else if (lowerName.endsWith(".3gp")) {
            return "audio/3gpp";
        } else if (lowerName.endsWith(".amr")) {
            return "audio/amr";
        } else if (lowerName.endsWith(".ogg")) {
            return "audio/ogg";
        } else if (lowerName.endsWith(".aac")) {
            return "audio/aac";
        } else if (lowerName.endsWith(".flac")) {
            return "audio/flac";
        }
        // Video formats
        else if (lowerName.endsWith(".mp4")) {
            return "video/mp4";
        } else if (lowerName.endsWith(".3gpp")) {
            return "video/3gpp";
        }
        // Default fallback
        else {
            return "application/octet-stream";
        }
    }

    // Helper method to extract call log ID from various response formats
    private Integer extractCallLogId(JSONObject jsonResponse, String responseBody) {
        try {
            // Strategy 1: data.call_logs[0].id
            if (jsonResponse.has("data")) {
                JSONObject data = jsonResponse.getJSONObject("data");
                if (data.has("call_logs")) {
                    JSONArray callLogs = data.getJSONArray("call_logs");
                    if (callLogs.length() > 0) {
                        return callLogs.getJSONObject(0).getInt("id");
                    }
                }
                // Strategy 2: data.id
                if (data.has("id")) {
                    return data.getInt("id");
                }
            }
            // Strategy 3: call_log_id
            if (jsonResponse.has("call_log_id")) {
                return jsonResponse.getInt("call_log_id");
            }
            // Strategy 4: id
            if (jsonResponse.has("id")) {
                return jsonResponse.getInt("id");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting call log ID", e);
        }
        return null;
    }

    // Register device
    public void registerDevice(String token, JSONObject deviceInfo, final ApiCallback callback) {
        try {
            RequestBody body = RequestBody.create(
                deviceInfo.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/devices/register")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Device registration failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Log.d(TAG, "Device registered successfully");
                            callback.onSuccess("Device registered");
                        } else {
                            String message = jsonResponse.optString("message", "Registration failed");
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing registration response", e);
                        callback.onFailure("Error parsing response");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating registration request", e);
            callback.onFailure("Error: " + e.getMessage());
        }
    }

    // Update device status
    public void updateDeviceStatus(String token, JSONObject statusInfo, final ApiCallback callback) {
        updateDeviceStatus(token, statusInfo, null, callback);
    }

    // Update device status with context for remote logout handling
    public void updateDeviceStatus(String token, JSONObject statusInfo, final android.content.Context context, final ApiCallback callback) {
        try {
            RequestBody body = RequestBody.create(
                statusInfo.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/devices/status")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Device status update failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Log.d(TAG, "Device status updated successfully");

                            // Check if device should logout
                            boolean shouldLogout = jsonResponse.optBoolean("should_logout", false);
                            if (shouldLogout && context != null) {
                                Log.w(TAG, "Remote logout requested by admin");
                                // Perform logout
                                performRemoteLogout(context);
                            }

                            callback.onSuccess("Status updated");
                        } else {
                            String message = jsonResponse.optString("message", "Status update failed");
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing status update response", e);
                        callback.onFailure("Error parsing response");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating status update request", e);
            callback.onFailure("Error: " + e.getMessage());
        }
    }

    // Perform remote logout
    private void performRemoteLogout(android.content.Context context) {
        try {
            // Clear preferences
            PrefsManager prefsManager = new PrefsManager(context);
            prefsManager.logout();

            Log.d(TAG, "Device logged out remotely");

            // Show notification to user
            showLogoutNotification(context);
        } catch (Exception e) {
            Log.e(TAG, "Error performing remote logout", e);
        }
    }

    // Show notification about remote logout
    private void showLogoutNotification(android.content.Context context) {
        try {
            android.app.NotificationManager notificationManager =
                (android.app.NotificationManager) context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);

            String channelId = "office_app_channel";

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId,
                    "Office App Notifications",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                );
                notificationManager.createNotificationChannel(channel);
            }

            android.app.Notification notification = new android.app.Notification.Builder(context, channelId)
                .setContentTitle("Logged Out")
                .setContentText("You have been logged out remotely by administrator")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build();

            notificationManager.notify(999, notification);
        } catch (Exception e) {
            Log.e(TAG, "Error showing logout notification", e);
        }
    }

    // Logout and remove device
    public void logout(String token, String deviceId, final ApiCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("device_id", deviceId);

            RequestBody body = RequestBody.create(
                json.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/logout")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Logout failed", e);
                    callback.onFailure("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Log.d(TAG, "Logout successful");
                            callback.onSuccess("Logged out");
                        } else {
                            String message = jsonResponse.optString("message", "Logout failed");
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing logout response", e);
                        callback.onFailure("Error parsing response");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating logout request", e);
            callback.onFailure("Error: " + e.getMessage());
        }
    }

    // Get call statistics
    public void getStatistics(String token, String period, final StatisticsCallback callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/call-logs/statistics?period=" + period)
                .get()
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Statistics request failed", e);
                callback.onFailure("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                try {
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                        JSONObject data = jsonResponse.getJSONObject("data");
                        callback.onSuccess(data);
                    } else {
                        String message = jsonResponse.optString("message", "Failed to fetch statistics");
                        callback.onFailure(message);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing statistics response", e);
                    callback.onFailure("Error parsing response");
                }
            }
        });
    }

    // Callback interfaces
    public interface ApiCallback {
        void onSuccess(String result);
        void onFailure(String error);
    }

    public interface LoginCallback {
        void onSuccess(String token, String userName);
        void onFailure(String error);
    }



    public interface VerifyCallback {
        void onValid(String userName);
        void onInvalid(String reason);
    }

    public interface StatisticsCallback {
        void onSuccess(JSONObject stats);
        void onFailure(String error);
    }
}
