1. **Fix Accessibility Screenshot:**
   - In `app/src/main/res/xml/accessibility_service_config.xml`, add `android:canTakeScreenshot="true"`.

2. **Fix Media Projection Screenshot Crash:**
   - In `app/src/main/java/com/jonas/x24/CommandReceiverActivity.kt`, fix the bitmap creation logic for `takeMediaProjectionScreenshot`.
   - Update from `width + rowPadding / pixelStride` to `rowStride / pixelStride`.

3. **Fix Notifications:**
   - In `app/src/main/java/com/jonas/x24/CommandReceiverActivity.kt`, update `readNotifications` to fall back to `recentNotifications` from `x24NotificationService` when `activeNotifications` are empty. Also, handle cases where `instance` is null gracefully.

4. **Fix Audio Recording Crash:**
   - In `app/src/main/java/com/jonas/x24/CommandReceiverActivity.kt`, update `recordAudio()` to use `MPEG_4` and `AAC` encoders, which are standard and less crash-prone. Add better error handling to prevent the activity from silently dying if recording fails.

5. **Fix Stability / Session Persistence:**
   - In `app/src/main/java/com/jonas/x24/services/FirebaseCommandService.kt`, update `onStartCommand` to retrieve the `SESSION_KEY` from `SharedPreferences` if it is null from the intent (which happens when the service is restarted by the system).

6. **Pre-commit step:**
   - Ensure proper testing, verification, review, and reflection are done using the `pre_commit_instructions` tool.

7. **Submit:**
   - Submit the changes with a clear commit message.
