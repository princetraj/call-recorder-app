package com.office.app;

import android.util.Log;
import com.google.gson.Gson;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ApiService {
    private static final String TAG = "ApiService";
    private static final String BASE_URL = "http://10.0.2.2:8000/api"; // Emulator testing

    private static ApiService instance;
    private OkHttpClient client;
    private Gson gson;

    private ApiService() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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
    public void login(String email, String password, final ApiCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("email", email);
            json.put("password", password);

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
                            callback.onSuccess(token);
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

    // Upload single call log
    public void uploadCallLog(String token, String callerName, String callerNumber,
                              String callType, long duration, String timestamp,
                              final ApiCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("caller_name", callerName != null ? callerName : "Unknown");
            json.put("caller_number", callerNumber);
            json.put("call_type", callType);
            json.put("call_duration", duration);
            json.put("call_timestamp", timestamp);

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
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (response.isSuccessful() && jsonResponse.getBoolean("success")) {
                            Log.d(TAG, "Call log uploaded successfully");
                            // Extract call log ID from response
                            JSONObject data = jsonResponse.getJSONObject("data");
                            JSONArray callLogs = data.getJSONArray("call_logs");
                            if (callLogs.length() > 0) {
                                JSONObject callLog = callLogs.getJSONObject(0);
                                int callLogId = callLog.getInt("id");
                                callback.onSuccess(String.valueOf(callLogId));
                            } else {
                                callback.onSuccess("Call log uploaded");
                            }
                        } else {
                            String message = jsonResponse.optString("message", "Upload failed");
                            callback.onFailure(message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing upload response", e);
                        callback.onFailure("Error parsing response");
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
            // Create multipart request body
            RequestBody fileBody = RequestBody.create(
                recordingFile,
                MediaType.parse("audio/*")
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
                        Log.e(TAG, "Error parsing upload response", e);
                        callback.onFailure("Error parsing response");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating recording upload request", e);
            callback.onFailure("Error: " + e.getMessage());
        }
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

    // Callback interface
    public interface ApiCallback {
        void onSuccess(String result);
        void onFailure(String error);
    }
}
