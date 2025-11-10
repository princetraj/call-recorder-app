# API Configuration Guide

## API Base URL Configuration

The API base URL is configured in the `ApiService.java` file at line 13:

```java
private static final String BASE_URL = "http://10.0.2.2:8000/api";
```

### For Testing on Android Emulator:
Use `http://10.0.2.2:8000/api` (10.0.2.2 is the emulator's alias to localhost)

### For Real Android Device:
Replace with your computer's IP address:
```java
private static final String BASE_URL = "http://YOUR_COMPUTER_IP:8000/api";
```

To find your computer's IP:
- **Windows**: Run `ipconfig` in command prompt and look for IPv4 Address
- **Mac/Linux**: Run `ifconfig` in terminal and look for inet address

Example: `http://192.168.1.100:8000/api`

### For Production Server:
Replace with your production server URL:
```java
private static final String BASE_URL = "https://yourdomain.com/api";
```

## Test Credentials

Based on your API documentation, you can create a test user account. Default test credentials:
- Email: user@example.com
- Password: password123

Make sure your Laravel API server is running before testing the app!

## How to Use the App

1. **First Time Setup**:
   - Open the app
   - Grant all requested permissions (Call Log, Phone State, Contacts)
   - Enter your API credentials (email and password)
   - Click "Login"

2. **After Login**:
   - The app will run in the background as a foreground service
   - It will automatically detect incoming and outgoing calls
   - Call logs will be uploaded to your API server after each call ends
   - The app will automatically start on device boot
   - The app will restart itself if killed

3. **Logout**:
   - Open the app
   - Click "Logout" button
   - Call logs will not be uploaded until you login again

## Troubleshooting

### Login fails with "Network error"
- Check that your API server is running
- Verify the BASE_URL is correct
- For real device: Make sure device and computer are on same WiFi network
- Check firewall settings

### Call logs not uploading
- Make sure you're logged in
- Check that all permissions are granted
- Check Android logcat for error messages
- Verify the API token is valid

### Permissions not working
- Go to Android Settings > Apps > Office App > Permissions
- Manually enable Phone and Contacts permissions
