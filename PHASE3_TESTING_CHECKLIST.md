# Phase 3 Testing Checklist

## Pre-Testing Setup

- [ ] Build and install the app on a test device
- [ ] Grant all required permissions (Phone, Contacts, Storage/Audio)
- [ ] Enable call recording on the device
- [ ] Clear app data for fresh testing (optional)
- [ ] Enable USB debugging and connect ADB

## Test 1: ContentObserver Detection

**Objective:** Verify ContentObserver detects new recordings immediately

**Steps:**
1. Open ADB logcat with filter: `adb logcat -s RecordingContentObserver:D PersistentService:D`
2. Ensure app is running (check for "Recording ContentObserver started" log)
3. Make a test call (use another phone or test number)
4. Answer and record the call
5. End the call

**Expected Results:**
- [ ] Log shows "Recording ContentObserver started" on app launch
- [ ] Log shows "MediaStore content changed" when recording is finalized
- [ ] Log shows "New call recording detected" with filename
- [ ] Detection happens within 1-3 seconds of call ending

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 2: WorkManager Recording Search

**Objective:** Verify WorkManager schedules and executes recording searches with proper retry

**Steps:**
1. Open ADB logcat with filter: `adb logcat -s RecordingSearchWorker:D SyncManager:D`
2. Make a test call and record it
3. Wait for call to upload to server
4. Observe WorkManager scheduling and execution

**Expected Results:**
- [ ] Log shows "Scheduling recording search with work name: RecordingSearch-{uuid}"
- [ ] Log shows "Starting recording search (attempt 1/4)" after ~5 seconds
- [ ] If recording found: Log shows "Recording found: {path}"
- [ ] If recording found: Log shows "Recording queued successfully"
- [ ] Work name includes unique UUID (no duplicates)

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 3: Exponential Backoff Retry

**Objective:** Verify retry mechanism works with exponential backoff

**Steps:**
1. Temporarily disable call recording (or delete recording immediately after call)
2. Make a test call without recording
3. Monitor logs for retry attempts

**Expected Results:**
- [ ] Attempt 1 after ~5 seconds: "Starting recording search (attempt 1/4)"
- [ ] Attempt 2 after ~15 seconds: "Starting recording search (attempt 2/4)"
- [ ] Attempt 3 after ~35 seconds: "Starting recording search (attempt 3/4)"
- [ ] Attempt 4 after ~75 seconds: "Starting recording search (attempt 4/4)"
- [ ] After attempt 4: "No recording found after 4 attempts"
- [ ] Database marked with `noRecordingFound = true`

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 4: UTC Timestamp Formatting

**Objective:** Verify timestamps are formatted in UTC

**Steps:**
1. Open ADB logcat with filter: `adb logcat -s TimeUtils:D SyncManager:D`
2. Launch the app and check initialization logs
3. Make a test call
4. Check the timestamp in server API logs (server-side verification)

**Expected Results:**
- [ ] Log shows "Current time info:" on app initialization
- [ ] Log shows "UTC time: {yyyy-MM-dd HH:mm:ss}"
- [ ] Log shows "Device timezone: {timezone}"
- [ ] Server receives timestamp in UTC format
- [ ] No timezone offset in timestamp string

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 5: Time Drift Tracking

**Objective:** Verify time drift detection and logging

**Setup:**
1. Temporarily change device time (Settings > Date & Time > Automatic off)
2. Set device time 10 minutes ahead or behind

**Steps:**
1. Open ADB logcat with filter: `adb logcat -s TimeUtils:D`
2. Call `SyncManager.getInstance(context).updateServerTimeDrift(serverTime)` with known server timestamp
3. Check logs for time drift calculation

**Expected Results:**
- [ ] Log shows "Server time drift: {time} {ahead/behind}"
- [ ] If drift > 5 minutes: Log shows "WARNING: Significant time drift detected"
- [ ] Drift value is calculated correctly (device - server)

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 6: No Duplicate Searches

**Objective:** Verify unique work names prevent duplicate recording searches

**Steps:**
1. Make a test call
2. Quickly force-stop and restart the app while recording search is in progress
3. Monitor WorkManager logs

**Expected Results:**
- [ ] Only one RecordingSearchWorker with UUID runs
- [ ] Log shows "ExistingWorkPolicy.KEEP" prevents duplicate
- [ ] No parallel searches for the same UUID

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 7: ContentObserver Lifecycle

**Objective:** Verify ContentObserver starts/stops with service

**Steps:**
1. Open ADB logcat with filter: `adb logcat -s PersistentService:D RecordingContentObserver:D`
2. Launch the app
3. Force-stop the app
4. Relaunch the app

**Expected Results:**
- [ ] On launch: Log shows "Recording ContentObserver started"
- [ ] On force-stop: Log shows "Recording ContentObserver stopped"
- [ ] On relaunch: Log shows "Recording ContentObserver started"
- [ ] ContentObserver properly registered/unregistered

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 8: Integration Test - Full Flow

**Objective:** Test complete end-to-end flow with all Phase 3 components

**Steps:**
1. Clear app data
2. Login to the app
3. Grant all permissions
4. Make 5 test calls (mix of incoming/outgoing)
5. Ensure recordings are enabled
6. Monitor all logs

**Expected Results:**
- [ ] All 5 calls detected and uploaded
- [ ] All 5 recordings detected within 5 seconds
- [ ] All 5 recordings matched and uploaded
- [ ] No duplicate searches
- [ ] All timestamps in UTC
- [ ] No errors in logs

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 9: Server API Integration

**Objective:** Verify server correctly handles UTC timestamps and idempotency

**Prerequisites:**
- Server must support idempotency keys (Phase 1)
- Server must return `server_time` in responses (for time drift)

**Steps:**
1. Make a test call
2. Inspect API request headers and body
3. Inspect API response

**Expected Results:**
- [ ] Request header contains `Idempotency-Key: {uuid}`
- [ ] Request body timestamp is in UTC format: "yyyy-MM-dd HH:mm:ss"
- [ ] Server stores timestamp correctly in UTC
- [ ] Response contains `server_time` field (if implemented)
- [ ] Duplicate requests with same idempotency key return same response

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Test 10: Performance Test

**Objective:** Measure performance improvements from Phase 3

**Steps:**
1. Make 10 test calls
2. Measure time from call end to recording detected
3. Check battery usage (Android Settings > Battery)
4. Check WorkManager job count

**Metrics to Record:**
- Average detection time: _____ seconds
- Fastest detection: _____ seconds
- Slowest detection: _____ seconds
- Battery usage during test: _____ %
- Number of WorkManager jobs created: _____

**Expected Results:**
- [ ] Average detection time < 5 seconds
- [ ] 90% of recordings detected within 3 seconds
- [ ] Battery usage reasonable (< 5% for 10 calls)
- [ ] No orphaned WorkManager jobs

**Pass/Fail:** ___________

**Notes:**
```


```

---

## Common Issues & Solutions

### Issue 1: ContentObserver not triggering
**Symptoms:** No "MediaStore content changed" logs
**Solutions:**
- Check READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE permission
- Verify recording is saved to MediaStore (not private directory)
- Check if ContentObserver is registered: `adb shell dumpsys content`

### Issue 2: WorkManager not retrying
**Symptoms:** Only one search attempt, no retries
**Solutions:**
- Check WorkManager status: `adb shell dumpsys jobscheduler | grep RecordingSearch`
- Verify device not in Doze mode: `adb shell dumpsys deviceidle`
- Check app battery optimization settings

### Issue 3: Time drift warnings
**Symptoms:** "WARNING: Significant time drift detected"
**Solutions:**
- Enable automatic date & time on device
- Check network time sync
- Verify server time is accurate
- May be expected if device/server time is incorrect

### Issue 4: Duplicate recordings
**Symptoms:** Same recording uploaded twice
**Solutions:**
- Check for multiple WorkManager jobs: `adb shell dumpsys jobscheduler`
- Verify idempotency keys are unique per call
- Check database for duplicate entries

---

## Sign-Off

**Tested by:** _____________________

**Date:** _____________________

**Device:** _____________________

**Android Version:** _____________________

**App Version:** _____________________

**Overall Result:** PASS / FAIL

**Critical Issues Found:**
```


```

**Recommendations:**
```


```
