# Quick Start Guide - Office Call Logger

## Step 1: Configure API URL

Open `app/src/main/java/com/office/app/ApiService.java` and update line 13:

**For Android Emulator:**
```java
private static final String BASE_URL = "http://10.0.2.2:8000/api";
```

**For Real Android Device:**
```java
private static final String BASE_URL = "http://YOUR_COMPUTER_IP:8000/api";
```
Replace `YOUR_COMPUTER_IP` with your computer's actual IP address (find it using `ipconfig` on Windows).

## Step 2: Start Your API Server

```bash
cd f:\works\call-logs-api
php artisan serve
```

Keep this running in a terminal window.

## Step 3: Build & Install the App

### Option A: Using Android Studio
1. Open Android Studio
2. Open the `OfficeApp` folder as a project
3. Wait for Gradle sync to complete
4. Connect your Android device or start an emulator
5. Click Run (green play button)

### Option B: Using Command Line
```bash
cd F:\works\OfficeApp
gradlew.bat assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
```

## Step 4: First Time Setup on Phone

1. **Open the app** on your Android device
2. **Grant permissions** when prompted:
   - Allow Call Log access
   - Allow Phone State access
   - Allow Contacts access
3. **Login with your API credentials:**
   - Email: `user@example.com` (or your actual user email)
   - Password: `password123` (or your actual password)
4. **Click Login button**

## Step 5: Test It

1. Make a test phone call or receive one
2. After the call ends, wait 2-3 seconds
3. Check your API server logs or database to see if the call log was uploaded

### Verify upload in database:
```bash
cd f:\works\call-logs-api
php artisan tinker
>>> \App\Models\CallLog::latest()->first();
```

## Troubleshooting

### "Network error" when logging in
- Verify API server is running (`php artisan serve`)
- Check the BASE_URL is correct
- For real device: Make sure phone and computer are on same WiFi network
- Check Windows Firewall isn't blocking port 8000

### Call logs not uploading
- Make sure you're logged in (check app shows "Logged in as: your@email.com")
- Verify all permissions were granted
- Check Android logcat: `adb logcat -s CallLogManager ApiService`

### App crashes or won't build
- Make sure you have Android SDK 26+ installed
- Sync Gradle files in Android Studio
- Clean and rebuild: `gradlew.bat clean assembleDebug`

## What Data Gets Uploaded

For each call, the following data is sent to the API:

```json
{
  "caller_name": "John Smith",      // From contacts, or "Unknown"
  "caller_number": "+919876543210",  // Phone number
  "call_type": "incoming",           // incoming, outgoing, missed, rejected
  "call_duration": 125,              // Seconds
  "call_timestamp": "2024-11-02 14:30:00"  // When call occurred
}
```

## Important Notes

- The app runs as a background service with a persistent notification
- Call logs are only uploaded when you're logged in
- The app will auto-start on device boot
- To stop logging: Open the app and click "Logout"

## Need More Help?

See the full documentation:
- **README.md** - Complete documentation
- **API_CONFIG.md** - Detailed API configuration
- **f:\works\call-logs-api\API_DOCUMENTATION.md** - API endpoints reference
