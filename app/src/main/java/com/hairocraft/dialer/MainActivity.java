package com.hairocraft.dialer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private PrefsManager prefsManager;
    private ApiService apiService;

    private LinearLayout loginSection;
    private LinearLayout dashboardSection;
    private LinearLayout permissionsWarning;
    private TextView permissionsMessage;
    private Button grantPermissionsButton;
    private EditText emailInput;
    private EditText passwordInput;
    private Button loginButton;
    private TextView loginStatus;
    private TextView welcomeText;
    private TextView statTotal;
    private TextView statIncoming;
    private TextView statOutgoing;
    private TextView statMissed;
    private TextView statRejected;
    private TextView statDuration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize
        prefsManager = new PrefsManager(this);
        apiService = ApiService.getInstance();

        // Find views
        loginSection = findViewById(R.id.login_section);
        dashboardSection = findViewById(R.id.dashboard_section);
        permissionsWarning = findViewById(R.id.permissions_warning);
        permissionsMessage = findViewById(R.id.permissions_message);
        grantPermissionsButton = findViewById(R.id.grant_permissions_button);
        emailInput = findViewById(R.id.email_input);
        passwordInput = findViewById(R.id.password_input);
        loginButton = findViewById(R.id.login_button);
        loginStatus = findViewById(R.id.login_status);
        welcomeText = findViewById(R.id.welcome_text);
        statTotal = findViewById(R.id.stat_total);
        statIncoming = findViewById(R.id.stat_incoming);
        statOutgoing = findViewById(R.id.stat_outgoing);
        statMissed = findViewById(R.id.stat_missed);
        statRejected = findViewById(R.id.stat_rejected);
        statDuration = findViewById(R.id.stat_duration);

        // Setup click listeners
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleLogin();
            }
        });

        grantPermissionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAndRequestPermissions();
            }
        });

        // Update UI based on login status
        updateUI();

        // Check permissions first - service will start after permissions are granted
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Update permissions warning UI
        updatePermissionsWarning();

        // Check if all permissions are now granted (user may have returned from settings)
        if (areAllPermissionsGranted()) {
            // Ensure service is running if not already
            if (prefsManager.isLoggedIn()) {
                startPersistentService();
            }
            // Update device permissions status
            updateDevicePermissions();
        } else {
            // Still missing permissions - update status to reflect current state
            updateDevicePermissions();
        }
    }

    private void checkAndRequestPermissions() {
        java.util.ArrayList<String> permissionsList = new java.util.ArrayList<>();
        permissionsList.add(Manifest.permission.READ_CALL_LOG);
        permissionsList.add(Manifest.permission.READ_PHONE_STATE);
        permissionsList.add(Manifest.permission.READ_CONTACTS);

        // Android 13+ (API 33+) uses READ_MEDIA_AUDIO instead of READ_EXTERNAL_STORAGE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        String[] permissions = permissionsList.toArray(new String[0]);

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            // All permissions granted, start the service
            startPersistentService();
        } else {
            // Request permissions
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    private boolean areAllPermissionsGranted() {
        java.util.ArrayList<String> permissionsList = new java.util.ArrayList<>();
        permissionsList.add(Manifest.permission.READ_CALL_LOG);
        permissionsList.add(Manifest.permission.READ_PHONE_STATE);
        permissionsList.add(Manifest.permission.READ_CONTACTS);

        // Android 13+ (API 33+) uses READ_MEDIA_AUDIO instead of READ_EXTERNAL_STORAGE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        for (String permission : permissionsList) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startPersistentService() {
        Intent serviceIntent = new Intent(this, PersistentService.class);
        startForegroundService(serviceIntent);
        Log.d("MainActivity", "Persistent service started");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check which permissions were denied
            java.util.ArrayList<String> deniedPermissions = new java.util.ArrayList<>();
            java.util.ArrayList<String> permanentlyDeniedPermissions = new java.util.ArrayList<>();

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i]);

                    // Check if permission is permanently denied (user checked "Don't ask again")
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
                        permanentlyDeniedPermissions.add(permissions[i]);
                    }
                }
            }

            if (deniedPermissions.isEmpty()) {
                // All permissions granted
                Toast.makeText(this, "All permissions granted - Starting service", Toast.LENGTH_SHORT).show();
                startPersistentService();
                updateDevicePermissions();
                updatePermissionsWarning();
            } else {
                // Some permissions denied
                if (!permanentlyDeniedPermissions.isEmpty()) {
                    // User permanently denied some permissions
                    showPermissionDeniedDialog(permanentlyDeniedPermissions, deniedPermissions);
                } else {
                    // Permissions denied but can request again
                    showPermissionRationaleDialog(deniedPermissions);
                }
                updateDevicePermissions();
                updatePermissionsWarning();
            }
        }
    }

    private void showPermissionRationaleDialog(final java.util.ArrayList<String> deniedPermissions) {
        String message = "This app requires the following permissions to function properly:\n\n";
        message += getPermissionDescriptions(deniedPermissions);
        message += "\nWithout these permissions, the app cannot monitor calls and recordings.";

        new AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Grant Permissions", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Request permissions again
                    String[] permissionsArray = deniedPermissions.toArray(new String[0]);
                    ActivityCompat.requestPermissions(MainActivity.this, permissionsArray, PERMISSION_REQUEST_CODE);
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Toast.makeText(MainActivity.this, "App will not function properly without required permissions", Toast.LENGTH_LONG).show();
                }
            })
            .setCancelable(false)
            .show();
    }

    private void showPermissionDeniedDialog(final java.util.ArrayList<String> permanentlyDenied, final java.util.ArrayList<String> allDenied) {
        String message = "You have permanently denied the following permissions:\n\n";
        message += getPermissionDescriptions(permanentlyDenied);
        message += "\n\nTo enable these permissions, please go to:\nSettings > Apps > " + getString(R.string.app_name) + " > Permissions";

        new AlertDialog.Builder(this)
            .setTitle("Permissions Permanently Denied")
            .setMessage(message)
            .setPositiveButton("Open Settings", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Open app settings
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Toast.makeText(MainActivity.this, "App requires permissions to function", Toast.LENGTH_LONG).show();
                }
            })
            .setCancelable(false)
            .show();
    }

    private String getPermissionDescriptions(java.util.ArrayList<String> permissions) {
        StringBuilder descriptions = new StringBuilder();
        for (String permission : permissions) {
            String description = getPermissionDescription(permission);
            if (description != null) {
                descriptions.append("• ").append(description).append("\n");
            }
        }
        return descriptions.toString();
    }

    private String getPermissionDescription(String permission) {
        switch (permission) {
            case Manifest.permission.READ_CALL_LOG:
                return "Call Logs - To monitor and upload call history";
            case Manifest.permission.READ_PHONE_STATE:
                return "Phone State - To detect incoming/outgoing calls";
            case Manifest.permission.READ_CONTACTS:
                return "Contacts - To identify caller names";
            case Manifest.permission.READ_EXTERNAL_STORAGE:
                return "Storage - To access call recordings";
            case Manifest.permission.READ_MEDIA_AUDIO:
                return "Media Audio - To read audio recordings";
            default:
                return null;
        }
    }

    private void updateDevicePermissions() {
        // Only update if user is logged in
        if (!prefsManager.isLoggedIn()) {
            return;
        }

        String token = prefsManager.getAuthToken();
        if (token == null || token.isEmpty()) {
            return;
        }

        DeviceInfoCollector deviceInfo = new DeviceInfoCollector(this);
        apiService.updateDeviceStatus(token, deviceInfo.getDeviceStatusInfo(), this, new ApiService.ApiCallback() {
            @Override
            public void onSuccess(String result) {
                Log.d("MainActivity", "Device permissions updated");
            }

            @Override
            public void onFailure(String error) {
                Log.e("MainActivity", "Failed to update device permissions: " + error);
            }
        });
    }

    private void updateUI() {
        if (prefsManager.isLoggedIn()) {
            loginSection.setVisibility(View.GONE);
            dashboardSection.setVisibility(View.VISIBLE);

            // Set welcome message
            String userId = prefsManager.getUserId();
            String userName = prefsManager.getUserName();
            if (userName != null && !userName.isEmpty()) {
                welcomeText.setText("Welcome, " + userName);
            } else {
                welcomeText.setText("Welcome, User " + userId);
            }

            // Fetch and display statistics
            fetchStatistics();
        } else {
            loginSection.setVisibility(View.VISIBLE);
            dashboardSection.setVisibility(View.GONE);
            // Reset login button state when showing login section
            loginButton.setEnabled(true);
            loginStatus.setText("");
        }

        // Update permissions warning
        updatePermissionsWarning();
    }
    private void showLoginScreen() {
        loginSection.setVisibility(View.VISIBLE);
        dashboardSection.setVisibility(View.GONE);
        loginButton.setEnabled(true);
        loginStatus.setText("");
    }

    private void fetchStatistics() {
        String token = prefsManager.getAuthToken();
        if (token == null || token.isEmpty()) {
            return;
        }

        apiService.getStatistics(token, "daily", new ApiService.StatisticsCallback() {
            @Override
            public void onSuccess(final org.json.JSONObject stats) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            statTotal.setText(String.valueOf(stats.getInt("total_calls")));
                            statIncoming.setText(String.valueOf(stats.getInt("incoming")));
                            statOutgoing.setText(String.valueOf(stats.getInt("outgoing")));
                            statMissed.setText(String.valueOf(stats.getInt("missed")));
                            statRejected.setText(String.valueOf(stats.getInt("rejected")));

                            // Format duration
                            int totalDuration = stats.getInt("total_duration");
                            int hours = totalDuration / 3600;
                            int minutes = (totalDuration % 3600) / 60;
                            String durationText;
                            if (hours > 0) {
                                durationText = hours + "h " + minutes + "m";
                            } else {
                                durationText = minutes + "m";
                            }
                            statDuration.setText(durationText);
                        } catch (org.json.JSONException e) {
                            Log.e("MainActivity", "Error parsing statistics", e);
                        }
                    }
                });
            }

            @Override
            public void onFailure(final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.e("MainActivity", "Failed to fetch statistics: " + error);
                        // Keep default values (0) on error
                    }
                });
            }
        });
    }

    private void updatePermissionsWarning() {
        java.util.ArrayList<String> permissionsList = new java.util.ArrayList<>();
        permissionsList.add(Manifest.permission.READ_CALL_LOG);
        permissionsList.add(Manifest.permission.READ_PHONE_STATE);
        permissionsList.add(Manifest.permission.READ_CONTACTS);

        // Android 13+ (API 33+) uses READ_MEDIA_AUDIO instead of READ_EXTERNAL_STORAGE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        java.util.ArrayList<String> missingPermissions = new java.util.ArrayList<>();
        for (String permission : permissionsList) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (missingPermissions.isEmpty()) {
            // All permissions granted - hide warning
            permissionsWarning.setVisibility(View.GONE);
        } else {
            // Some permissions missing - show warning
            permissionsWarning.setVisibility(View.VISIBLE);

            // Update message with specific missing permissions
            int missingCount = missingPermissions.size();
            String message = missingCount + " permission" + (missingCount > 1 ? "s" : "") + " missing:\n\n";
            for (String permission : missingPermissions) {
                String description = getPermissionDescription(permission);
                if (description != null) {
                    message += "• " + description.split(" - ")[0] + "\n";
                }
            }
            message += "\nTap below to grant permissions.";
            permissionsMessage.setText(message);
        }
    }

    private void handleLogin() {
        String username = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            loginStatus.setText("Please enter username and password");
            return;
        }

        loginButton.setEnabled(false);
        loginStatus.setText("Logging in...");

        apiService.login(username, password, this, new ApiService.LoginCallback() {
            @Override
            public void onSuccess(String token, String userName) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        prefsManager.saveAuthToken(token);
                        prefsManager.saveUserId(username);
                        prefsManager.saveUserName(userName);
                        loginStatus.setText("Login successful!");
                        emailInput.setText("");
                        passwordInput.setText("");
                        updateUI();
                        Toast.makeText(MainActivity.this, "Logged in successfully", Toast.LENGTH_SHORT).show();

                        // Register device after successful login
                        registerDevice(token);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loginButton.setEnabled(true);
                        loginStatus.setText("Login failed: " + error);
                        Toast.makeText(MainActivity.this, "Login failed: " + error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void handleLogout() {
        String token = prefsManager.getAuthToken();

        // If we have a token, call logout API to remove device from server
        if (token != null && !token.isEmpty()) {
            DeviceInfoCollector deviceInfo = new DeviceInfoCollector(this);
            String deviceId = deviceInfo.getDeviceId();

            apiService.logout(token, deviceId, new ApiService.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Clear local storage after successful server logout
                            prefsManager.logout();
                            updateUI();
                            Toast.makeText(MainActivity.this, "Logged out successfully", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Even if server logout fails, clear local storage
                            prefsManager.logout();
                            updateUI();
                            Toast.makeText(MainActivity.this, "Logged out (offline)", Toast.LENGTH_SHORT).show();
                            android.util.Log.e("MainActivity", "Logout API failed: " + error);
                        }
                    });
                }
            });
        } else {
            // No token, just clear local storage
            prefsManager.logout();
            updateUI();
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Restart the service when activity is destroyed
        Intent broadcastIntent = new Intent(this, RestartReceiver.class);
        sendBroadcast(broadcastIntent);
    }

    private void registerDevice(String token) {
        DeviceInfoCollector deviceInfo = new DeviceInfoCollector(this);
        apiService.registerDevice(token, deviceInfo.getDeviceRegistrationInfo(), new ApiService.ApiCallback() {
            @Override
            public void onSuccess(String result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Device registered", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                // Silent failure - device registration is not critical
                android.util.Log.e("MainActivity", "Device registration failed: " + error);
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Move to background instead of closing
        moveTaskToBack(true);
    }
}
