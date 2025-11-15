# Upload Sync and Retry Mechanism

## Overview

The app now includes a robust sync mechanism that automatically queues and retries failed uploads of call logs and recordings. This ensures no data is lost when the server is unavailable or there are network connectivity issues.

## Key Features

1. **Automatic Queueing**: Failed uploads are automatically saved to a local SQLite database
2. **Exponential Backoff**: Retry intervals increase progressively (1min → 2min → 4min → 8min → ... → 24hr)
3. **Periodic Sync**: Background worker checks for pending uploads every 15 minutes
4. **Network-Aware**: Immediately syncs when network connectivity is restored
5. **Next Call Trigger**: Syncs pending uploads when a new call happens (fastest recovery!)
6. **Persistent Storage**: Uses Room database for reliable data persistence

## Architecture

### Components

#### 1. Database Layer (`database/`)

- **`UploadQueue.java`**: Entity class representing queued uploads
  - Stores call log metadata (number, type, duration, timestamp, SIM info)
  - Stores recording file paths (original and compressed)
  - Tracks retry count, status, and next retry time

- **`UploadQueueDao.java`**: Data Access Object for database operations
  - Insert, update, query pending uploads
  - Delete completed/old uploads

- **`AppDatabase.java`**: Room database singleton
  - Single source of truth for app's local data

#### 2. Sync Layer (`sync/`)

- **`SyncManager.java`**: Core sync orchestration
  - `queueCallLog()`: Add failed call log to queue
  - `queueRecording()`: Add failed recording to queue
  - `syncPendingUploads()`: Process all pending uploads from database
  - Handles exponential backoff calculation

- **`SyncWorker.java`**: WorkManager background worker
  - Runs periodically (every 15 minutes)
  - Only executes when network is available
  - Calls SyncManager to process queue

- **`SyncScheduler.java`**: WorkManager scheduling helper
  - `schedulePeriodicSync()`: Set up recurring sync work
  - `triggerImmediateSync()`: Force immediate sync (used on network reconnect)

- **`NetworkChangeReceiver.java`**: BroadcastReceiver for network state
  - Listens for CONNECTIVITY_ACTION broadcasts
  - Triggers immediate sync when network reconnects

#### 3. Integration Points

- **`CallLogManager.java`** (Modified):
  - Line 14: Import SyncManager
  - Line 25: Add SyncManager field
  - Line 36: Initialize SyncManager in constructor
  - Lines 159-174: Queue call log on upload failure

- **`RecordingUploader.java`** (Modified):
  - Line 10: Import SyncManager
  - Line 17: Add SyncManager field
  - Line 85: Initialize SyncManager in constructor
  - Lines 250-251: Accept phoneNumber and callTimestamp parameters
  - Lines 316-340: Queue recording on upload failure

- **`PersistentService.java`** (Modified):
  - Line 18: Import SyncScheduler
  - Lines 56-57: Schedule periodic sync on service start

## How It Works

### Sync Triggers

The app has **THREE** automatic sync triggers to ensure fast recovery:

1. **📞 Next Call (FASTEST)** - When a new call happens
   - Syncs any pending uploads immediately before processing the new call
   - Typically happens within seconds if server is back online
   - Located in: `CallLogManager.uploadLatestCallLog()` line 93

2. **🌐 Network Reconnect** - When WiFi/mobile data reconnects
   - Detects network state changes
   - Triggers immediate sync when connection is restored
   - Located in: `NetworkChangeReceiver.onReceive()`

3. **⏰ Periodic (Every 15 minutes)** - Background worker
   - Runs even if no calls are made
   - Ensures eventual sync even if network stays connected
   - Located in: `SyncWorker.doWork()`

### Upload Flow

```
Call Ends
    ↓
CallLogManager.uploadLatestCallLog()
    ↓
ApiService.uploadCallLog()
    ├─ Success → Search for recording
    │               ↓
    │           RecordingUploader.uploadRecording()
    │               ├─ Success → Done
    │               └─ Failure → Queue in Database (SyncManager.queueRecording)
    │
    └─ Failure → Queue in Database (SyncManager.queueCallLog)
```

### Retry Flow

```
Network Reconnects OR 15-Minute Timer OR Next Call Happens
    ↓
NetworkChangeReceiver OR SyncWorker OR CallLogManager
    ↓
SyncScheduler.triggerImmediateSync()
    ↓
SyncManager.syncPendingUploads()
    ↓
For each pending upload in database:
    ├─ Upload via ApiService
    ├─ Success → Mark as completed in DB
    └─ Failure → Increment retry count
                 Calculate next retry time (exponential backoff)
                 Update status in DB
```

### Exponential Backoff Schedule

| Retry # | Wait Time | Total Time Elapsed |
|---------|-----------|-------------------|
| 1       | 1 minute  | 1 minute          |
| 2       | 2 minutes | 3 minutes         |
| 3       | 4 minutes | 7 minutes         |
| 4       | 8 minutes | 15 minutes        |
| 5       | 16 minutes| 31 minutes        |
| 6       | 30 minutes| 1 hour            |
| 7       | 1 hour    | 2 hours           |
| 8       | 2 hours   | 4 hours           |
| 9       | 4 hours   | 8 hours           |
| 10+     | 24 hours  | 32+ hours         |

## Database Schema

### `upload_queue` Table

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER | Auto-increment primary key |
| uploadType | TEXT | "call_log" or "recording" |
| phoneNumber | TEXT | Phone number |
| callType | TEXT | "incoming", "outgoing", "missed", "rejected" |
| callDuration | INTEGER | Call duration in seconds |
| callTimestamp | INTEGER | Unix timestamp (milliseconds) |
| contactName | TEXT | Contact name or "Unknown" |
| simSlot | TEXT | SIM slot index |
| simOperator | TEXT | SIM operator name |
| simNumber | TEXT | SIM phone number |
| recordingFilePath | TEXT | Path to original recording file |
| compressedFilePath | TEXT | Path to compressed recording file |
| fileSize | INTEGER | File size in bytes |
| status | TEXT | "pending", "uploading", "failed", "completed" |
| retryCount | INTEGER | Number of retry attempts |
| createdAt | INTEGER | Queue creation timestamp |
| lastAttemptAt | INTEGER | Last upload attempt timestamp |
| nextRetryAt | INTEGER | Next scheduled retry timestamp |
| errorMessage | TEXT | Last error message |

## Configuration

### WorkManager Periodic Sync

- **Interval**: 15 minutes
- **Constraints**: Requires network connectivity
- **Policy**: KEEP (won't replace existing scheduled work)
- **Work Name**: "upload_sync_work"

### Database Settings

- **Database Name**: "hairocraft_dialer_db"
- **Version**: 1
- **Migration Strategy**: Destructive (fresh install only)

### Cleanup

- Completed uploads older than 7 days are automatically deleted during sync

## Dependencies Added

```gradle
// Room Database
def room_version = "2.6.1"
implementation "androidx.room:room-runtime:$room_version"
annotationProcessor "androidx.room:room-compiler:$room_version"

// WorkManager for background sync
def work_version = "2.9.0"
implementation "androidx.work:work-runtime:$work_version"
```

## Permissions

Already included in AndroidManifest.xml:

- `ACCESS_NETWORK_STATE` - Check network connectivity
- `INTERNET` - Upload data

## Manifest Changes

Added NetworkChangeReceiver:

```xml
<receiver
    android:name=".sync.NetworkChangeReceiver"
    android:enabled="true"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.conn.CONNECTIVITY_ACTION" />
    </intent-filter>
</receiver>
```

## Usage Examples

### Manual Trigger (for testing)

```java
// Trigger immediate sync
SyncScheduler.triggerImmediateSync(context);

// Get pending upload count
SyncManager syncManager = SyncManager.getInstance(context);
syncManager.getPendingCount(new SyncManager.CountCallback() {
    @Override
    public void onCount(int count) {
        Log.d("TEST", "Pending uploads: " + count);
    }
});

// Cancel all scheduled sync
SyncScheduler.cancelSync(context);
```

## Testing Scenarios

### Test 1: Server Unavailable

1. Stop the Laravel API server
2. Make a call
3. Verify upload fails and gets queued
4. Check logs for "Call log queued for retry"
5. Start the server
6. Wait up to 15 minutes or trigger immediate sync
7. Verify upload succeeds

### Test 2: Network Disconnected

1. Turn off WiFi and mobile data
2. Make a call
3. Verify upload fails and gets queued
4. Turn on network
5. Verify NetworkChangeReceiver triggers immediate sync
6. Check upload succeeds

### Test 3: Multiple Failed Uploads

1. Disconnect network
2. Make 5 calls
3. Reconnect network
4. Verify all 5 uploads sync successfully

### Test 4: Exponential Backoff

1. Keep server offline
2. Make a call
3. Monitor logs for retry attempts
4. Verify wait times increase: 1min, 2min, 4min, etc.

## Logs

Key log tags to monitor:

```
SyncManager          - Queue operations, sync progress
SyncWorker           - Periodic sync execution
NetworkChangeReceiver - Network state changes
SyncScheduler        - Work scheduling
CallLogManager       - Call log queueing
RecordingUploader    - Recording queueing
```

## File Structure

```
app/src/main/java/com/hairocraft/dialer/
├── database/
│   ├── AppDatabase.java          [Room database]
│   ├── UploadQueue.java           [Entity]
│   ├── UploadQueueDao.java        [DAO]
│   └── Converters.java            [Type converters]
├── sync/
│   ├── SyncManager.java           [Core sync logic]
│   ├── SyncWorker.java            [WorkManager worker]
│   ├── SyncScheduler.java         [Scheduling helper]
│   └── NetworkChangeReceiver.java [Network listener]
├── CallLogManager.java            [Modified]
├── RecordingUploader.java         [Modified]
└── PersistentService.java         [Modified]
```

## Future Enhancements

Potential improvements:

1. **Batch Uploads**: Upload multiple queued items in a single request
2. **Compression Management**: Automatically delete old compressed files
3. **Upload Priority**: Prioritize recent uploads over old ones
4. **Network Type Awareness**: Only upload on WiFi when file is large
5. **User Notifications**: Notify user when uploads are stuck
6. **Manual Retry**: UI button to force retry all pending uploads
7. **Upload Statistics**: Track success/failure rates

## Troubleshooting

### Uploads not retrying

- Check if PersistentService is running
- Verify WorkManager is scheduled: `adb shell dumpsys activity service WorkManagerService`
- Check battery optimization settings
- Review logs for exceptions

### Database errors

- Clear app data to reset database
- Check Room migration logs
- Verify write permissions

### Network detection issues

- Verify ACCESS_NETWORK_STATE permission
- Check if NetworkChangeReceiver is registered
- Test with airplane mode on/off

## Implementation Status

✅ Room database with UploadQueue entity
✅ SyncManager with exponential backoff
✅ WorkManager periodic sync worker
✅ Network connectivity listener
✅ CallLogManager integration
✅ RecordingUploader integration
✅ PersistentService initialization
✅ AndroidManifest configuration
✅ Gradle dependencies

**Ready for testing!**
