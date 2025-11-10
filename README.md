# Office Call Logger App

An Android application that automatically uploads call logs to a Laravel API server. The app runs as a persistent foreground service and captures all incoming, outgoing, missed, and rejected calls.

## Features

- **Automatic Call Log Upload**: Automatically detects and uploads call logs when a call is made or received
- **Persistent Background Service**: Runs continuously in the background
- **Auto-Start on Boot**: Automatically starts when the device boots
- **Auto-Restart**: Restarts itself if the service is killed
- **Contact Name Resolution**: Fetches contact names from the device's contact list
- **Secure Authentication**: Uses Bearer token authentication
- **Login/Logout**: Simple UI for API authentication

## Prerequisites

1. **API Server**: Your Laravel Call Logs API must be running
   - API documentation: `f:\works\call-logs-api\API_DOCUMENTATION.md`
   - Default API URL: `http://localhost:8000/api`

2. **Android Development**:
   - Android Studio (latest version recommended)
   - Android SDK 26 or higher
   - Physical Android device or emulator running Android 8.0+

## Setup Instructions

### 1. Configure API URL

Edit the file `app/src/main/java/com/office/app/ApiService.java` (line 13):

```java
private static final String BASE_URL = "http://10.0.2.2:8000/api"; // For emulator
```

**For real device**, replace with your computer's IP:
```java
private static final String BASE_URL = "http://192.168.1.100:8000/api";
```

See `API_CONFIG.md` for detailed instructions.

### 2. Start Your API Server

Make sure your Laravel API server is running:
```bash
cd f:\works\call-logs-api
php artisan serve
```

### 3. Build and Install the App

#### Method 1: Build with Android Studio

1. Open Android Studio
2. Click "Open an Existing Project"
3. Navigate to the `OfficeApp` folder and select it
4. Wait for Gradle to sync
5. Connect your Android device via USB (enable USB debugging in Developer Options)
6. Click the "Run" button (green play icon) or press Shift+F10
7. Select your device and click OK

#### Method 2: Build with Gradle Command Line

```bash
cd OfficeApp
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use:
```bash
cd OfficeApp
gradlew.bat assembleDebug
```

### 4. First Run and Login

1. Open the app
2. Grant permissions when prompted:
   - Call Log access
   - Phone State access
   - Contacts access
3. Enter your API credentials:
   - Email: user@example.com (or your registered email)
   - Password: password123 (or your password)
4. Click "Login"

### 5. Test Call Logging

1. Make a test call or receive a call
2. After the call ends, the app will automatically upload the call log to the API
3. Check your API server logs or database to verify the upload

## Post-Installation Setup

After installing the app, you need to configure the device settings:

### 1. Disable Battery Optimization

To ensure the app keeps running:

1. Go to **Settings** → **Apps** → **Office App**
2. Tap **Battery** or **Battery Usage**
3. Select **Unrestricted** or **Don't optimize**

### 2. Allow Autostart (Manufacturer Specific)

Different manufacturers have different settings:

**Xiaomi/MIUI:**
- Settings → Apps → Manage apps → Office App → Autostart → Enable

**Huawei/EMUI:**
- Settings → Apps → Office App → Battery → App launch → Manage manually
- Enable all three options (Auto-launch, Secondary launch, Run in background)

**Samsung:**
- Settings → Apps → Office App → Battery → Allow background activity

**OnePlus/OxygenOS:**
- Settings → Apps → Office App → Battery → Battery optimization → Don't optimize

**Oppo/ColorOS:**
- Settings → Apps → App Management → Office App → Allow autostart

### 3. Grant Call Log Permissions

When you first launch the app, allow the following:
- **Call Log**: Read call history
- **Phone State**: Detect call events
- **Contacts**: Read contact names
- **Notifications**: Show foreground service notification (Android 13+)

## How It Works

The app uses multiple components for call log tracking:

1. **PhoneStateListener**: Detects when calls start and end
2. **CallLogManager**: Reads call log details (number, duration, type) from the device
3. **Contact Resolver**: Fetches contact names from the phone's contact list
4. **ApiService**: Uploads call log data to the Laravel API server
5. **Foreground Service**: Ensures the app keeps running in the background
6. **Auto-Restart**: Automatically restarts on boot or if killed

**Call Flow:**
1. User makes/receives a call
2. PhoneStateListener detects call state changes
3. After call ends, CallLogManager reads the latest call log entry
4. Contact name is resolved (if available)
5. Call data is uploaded to API server with authentication token

## API Endpoints Used

- `POST /api/login` - Authenticate and get bearer token
- `POST /api/call-logs` - Upload call log data

## Call Log Data Format

```json
{
  "caller_name": "John Smith",
  "caller_number": "+919876543210",
  "call_type": "incoming",
  "call_duration": 125,
  "call_timestamp": "2024-11-02 14:30:00"
}
```

**Call Types**: `incoming`, `outgoing`, `missed`, `rejected`

## Important Notes

- This app is designed for internal office use
- The app will appear in the notification bar when running (required for foreground services)
- Call logs are only uploaded when user is logged in
- Authentication token is stored securely in SharedPreferences
- For production, use HTTPS instead of HTTP

## File Structure

```
OfficeApp/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/office/app/
│   │       │   ├── MainActivity.java          # Main UI and login/logout
│   │       │   ├── PersistentService.java     # Foreground service
│   │       │   ├── CallLogManager.java        # Call detection & upload
│   │       │   ├── ApiService.java            # API communication
│   │       │   ├── PrefsManager.java          # Auth token storage
│   │       │   ├── BootReceiver.java          # Boot startup handler
│   │       │   └── RestartReceiver.java       # Service restart handler
│   │       ├── res/
│   │       │   └── layout/
│   │       │       └── activity_main.xml      # Login/Status UI
│   │       └── AndroidManifest.xml            # Permissions & components
│   └── build.gradle                           # Dependencies (OkHttp, Gson)
├── API_CONFIG.md                               # API configuration guide
└── README.md                                   # This file
```

## Permissions Explained

- **READ_CALL_LOG**: Read call history from the device
- **READ_PHONE_STATE**: Detect when calls start/end
- **READ_CONTACTS**: Get contact names for phone numbers
- **INTERNET**: Upload call logs to API server
- **FOREGROUND_SERVICE**: Run service in foreground with notification
- **RECEIVE_BOOT_COMPLETED**: Start app when device boots
- **WAKE_LOCK**: Keep CPU running when needed
- **POST_NOTIFICATIONS**: Show notifications on Android 13+

## Troubleshooting

### Login Issues

**"Network error" or "Connection refused"**
- Verify API server is running (`php artisan serve`)
- Check API URL in `ApiService.java` (line 13)
- For real device: Ensure device and computer are on same WiFi
- Check firewall settings

**"Invalid credentials"**
- Verify email and password are correct
- Check user exists in API database

### Call Logs Not Uploading

**Calls not being detected:**
- Grant all required permissions (Call Log, Phone, Contacts)
- Verify you're logged in (check app UI)
- Check logcat: `adb logcat -s CallLogManager ApiService`

**Upload fails with 401 Unauthorized:**
- Token may have expired - logout and login again

### Service Issues

**App gets killed:**
- Disable battery optimization: Settings > Apps > Office App > Battery > Unrestricted
- Add to protected apps list (manufacturer specific)

**App doesn't start on boot:**
- Check autostart permission for your device manufacturer
- Ensure RECEIVE_BOOT_COMPLETED permission is granted

## Viewing Logs

```bash
# View app logs
adb logcat -s ApiService CallLogManager PersistentService MainActivity

# View only errors
adb logcat -s ApiService:E CallLogManager:E
```

## Development

### Dependencies

```gradle
dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'
}
```

### Building for Production

1. Change BASE_URL to production HTTPS URL
2. Generate a signing key
3. Configure signing in `app/build.gradle`
4. Build the release APK:
   ```bash
   ./gradlew assembleRelease
   ```

## Security Notes

1. **HTTPS**: Always use HTTPS for production servers
2. **Token Storage**: Auth token stored in SharedPreferences (encrypted on Android 6+)
3. **Permissions**: App only accesses call logs when user is logged in

## Additional Resources

- **API Configuration Guide**: See `API_CONFIG.md` for detailed API setup
- **API Documentation**: `f:\works\call-logs-api\API_DOCUMENTATION.md`

## Support

This is an internal office application. For issues, check the troubleshooting section above.

## License

Internal use only - All rights reserved.
#   c a l l - r e c o r d e r - a p p  
 