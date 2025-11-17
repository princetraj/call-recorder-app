package com.hairocraft.dialer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.graphics.Color;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import com.hairocraft.dialer.sync.SyncScheduler;
import com.hairocraft.dialer.sync.SyncManager;

public class PersistentService extends Service {

    private static final String CHANNEL_ID = "HairocraftDialerChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int STATUS_UPDATE_INTERVAL = 5 * 60 * 1000; // 5 minutes

    private CallLogManager callLogManager;
    private Handler statusUpdateHandler;
    private Runnable statusUpdateRunnable;
    private DeviceInfoCollector deviceInfoCollector;
    private CallStateListener callStateListener;
    private TelephonyManager telephonyManager;
    // PHASE 3.1: SyncManager for ContentObserver management
    private SyncManager syncManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // Initialize and start call log manager
        callLogManager = new CallLogManager(this);
        callLogManager.startListening();

        // Initialize device info collector
        deviceInfoCollector = new DeviceInfoCollector(this);

        // Setup periodic device status updates
        setupDeviceStatusUpdates();

        // Setup call state listener
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        callStateListener = new CallStateListener(this);
        if (telephonyManager != null) {
            // Check if we have the required permission before listening
            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                android.util.Log.d("PersistentService", "Call state listener registered");
            } else {
                android.util.Log.w("PersistentService", "READ_PHONE_STATE permission not granted, call state listener not registered");
            }
        }

        // Schedule periodic sync for failed uploads
        SyncScheduler.schedulePeriodicSync(this);
        android.util.Log.d("PersistentService", "Sync scheduler initialized");

        // PHASE 3.1: Start MediaStore ContentObserver for immediate recording detection
        syncManager = SyncManager.getInstance(this);
        syncManager.startRecordingObserver();
        android.util.Log.d("PersistentService", "Recording ContentObserver started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Create notification for foreground service
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
            notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Get current time for notification
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String currentTime = sdf.format(new Date());

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hairocraft Dialer is Running")
                .setContentText("Active since " + currentTime + " - Tap to open")
                .setSubText("Call Monitoring Service")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(false)
                .setColor(Color.parseColor("#4CAF50"))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Stop call log manager
        if (callLogManager != null) {
            callLogManager.stopListening();
        }

        // Stop device status updates
        if (statusUpdateHandler != null && statusUpdateRunnable != null) {
            statusUpdateHandler.removeCallbacks(statusUpdateRunnable);
        }

        // Stop call state listener
        if (telephonyManager != null && callStateListener != null) {
            telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_NONE);
        }

        // PHASE 3.1: Stop MediaStore ContentObserver
        if (syncManager != null) {
            syncManager.stopRecordingObserver();
            android.util.Log.d("PersistentService", "Recording ContentObserver stopped");
        }

        // Broadcast to restart the service
        Intent broadcastIntent = new Intent(this, RestartReceiver.class);
        sendBroadcast(broadcastIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Restart service when task is removed
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        startForegroundService(restartServiceIntent);

        super.onTaskRemoved(rootIntent);
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Hairocraft Dialer Active Status",
                NotificationManager.IMPORTANCE_HIGH
        );
        serviceChannel.setDescription("Shows when Hairocraft Dialer is running");
        serviceChannel.enableLights(true);
        serviceChannel.setLightColor(Color.GREEN);
        serviceChannel.setShowBadge(true);
        serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void setupDeviceStatusUpdates() {
        statusUpdateHandler = new Handler();
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDeviceStatus();
                // Schedule next update
                statusUpdateHandler.postDelayed(this, STATUS_UPDATE_INTERVAL);
            }
        };

        // Start first update after 1 minute, then repeat every 5 minutes
        statusUpdateHandler.postDelayed(statusUpdateRunnable, 60 * 1000);
    }

    private void updateDeviceStatus() {
        PrefsManager prefsManager = new PrefsManager(this);

        // Only update if user is logged in
        if (!prefsManager.isLoggedIn()) {
            return;
        }

        String token = prefsManager.getAuthToken();
        if (token == null || token.isEmpty()) {
            return;
        }

        ApiService apiService = ApiService.getInstance();
        // IMPORTANT: Pass context to enable remote logout handling from admin panel
        apiService.updateDeviceStatus(token, deviceInfoCollector.getDeviceStatusInfo(), this,
            new ApiService.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    android.util.Log.d("PersistentService", "Device status updated successfully");
                }

                @Override
                public void onFailure(String error) {
                    android.util.Log.e("PersistentService", "Device status update failed: " + error);
                }
            });
    }
}
