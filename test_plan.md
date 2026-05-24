Plan:
1. Fix Accessibility Screenshot:
In `app/src/main/res/xml/accessibility_service_config.xml`, add `android:canTakeScreenshot="true"`. Otherwise, the accessibility service will throw an error when taking screenshots.

2. Fix Audio Recording:
In `app/src/main/java/com/jonas/x24/CommandReceiverActivity.kt`, the `recordAudio` method starts recording, and then uses a `Handler` to stop recording after 10 seconds. However, `CommandReceiverActivity` does not finish the activity until recording stops, which leaves a transparent activity hanging on top of the screen or gets paused.
Actually, the user says "the app does activate the microphone but for like 2 second n then it auto closes or the app crahss n nothing happens".
The bug could be that `MediaRecorder` requires `.MPEG_4` and `.AAC`, because `THREE_GPP` and `AMR_NB` are older and might not work perfectly or get rejected by some devices or it fails when parsing.
Also, reading the entire file at once `FileInputStream(outputFile).readBytes()` might crash due to OOM. We will just fix the codec to MPEG_4 and AAC. Wait, more likely `cacheDir` can be an issue.
Actually, the best way to record audio and not crash is to ensure `recorder.start()` is wrapped properly.
Wait, let's look at `MediaRecorder` initialization. If target sdk is 31+, `MediaRecorder` constructor with context is needed.
```kotlin
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
```
This is correctly checking for Android 12+.

3. Fix Media Projection Screenshot Crash:
When taking a Media Projection screenshot, it shows up like a screen recorder, but crashes when acquiring the image.
The crash happens here:
```kotlin
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
```
If `rowPadding / pixelStride` is not evenly divisible or causes `width` to be 0 or negative. Wait, if `width + rowPadding / pixelStride` is less than or equal to 0, it throws `IllegalArgumentException: width and height must be > 0`.
Better yet, we can use PixelCopy or just wrap it in a try-catch and log the exact error.
Wait, the easiest way to fix MediaProjection screenshot crash when padding is involved is:
```kotlin
val bitmapWidth = rowStride / pixelStride
val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
bitmap.copyPixelsFromBuffer(buffer)
val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
```
Also `ImageReader` might return a null image sometimes, or `acquireLatestImage()` throws `IllegalStateException`. We must ensure it's closed. We will fix this.

4. Fix Notifications:
The issue is `readNotifications` returns "No active notifications" even when there are notifications.
Why? Because `getActiveNotificationsList` skips `sbn.isOngoing` and specific packages, but the main issue is that we need to request the `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and the user needs to grant it. If the user granted it, why is it returning empty?
Because `activeNotifications` can be null if the service isn't fully connected, or the system unbinds it when it is running in the background. But `NotificationListenerService` should be kept alive by the system.
Let's modify `getActiveNotificationsList` to ensure it falls back to `recentNotifications` if `activeNotifs` is empty but `recentNotifications` isn't. But `CommandReceiverActivity` has `val text = if (notifs.isEmpty()) "No active notifications." else notifs.joinToString("\n")`. It doesn't use `recentNotifications`.
I'll change `CommandReceiverActivity` to call a new method in `x24NotificationService` that combines both or falls back to recent notifications.

5. Fix Session Memory:
The user states that "after its logged in once as the be monitored it stays like that rather than always required the selection n key".
The code in `MainActivity.kt` says:
```kotlin
        if (savedRole != null && savedKey != null) {
            if (savedRole == "MONITOR") {
                ...
                finish()
                return
            } else if (savedRole == "BE_MONITORED") {
                ...
                finish()
                return
            }
        }
```
This is fine. So why does it require selection? Because `CommandReceiverActivity` might be updating the preferences? No.
Maybe the app is crashing, and the user clears app data? Or maybe the user is logging out?
Wait, look at `CommandReceiverActivity`:
```kotlin
        val command = intent.getStringExtra("COMMAND")
        val newSessionKey = intent.getStringExtra("SESSION_KEY")

        if (newSessionKey != null) {
            sessionKey = newSessionKey
        }
```
This doesn't save to prefs.
Wait, what if `CommandReceiverActivity` is launched by `FirebaseCommandService`? The service is started in `BeMonitoredActivity`.
If the user kills the app, `FirebaseCommandService` will restart because it returns `START_STICKY`.
When it restarts, `intent` is null in `onStartCommand`!
Let's check `FirebaseCommandService.kt`:
```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra("SESSION_KEY")
        if (key != null) {
            sessionKey = key
            setupFirebaseListener(key)
        }
```
If the service is restarted by the system (e.g. after a crash or being killed), `intent` is null. So `key` is null, and `setupFirebaseListener` is never called!
To fix this, `FirebaseCommandService` should read the `SESSION_KEY` from `SharedPreferences` if `key` is null.

Let's refine the plan.
