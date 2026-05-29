import re

with open('app/src/main/java/com/jonas/x24/CameraStreamManager.kt', 'r') as f:
    content = f.read()

# Replace android.os.HandlerExecutor with something else. It was added in API 30 but we target API 26 minimum.
# Wait, this is called inside an `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)` which is API 28. HandlerExecutor is API 30.
# We can just write a quick wrapper or use a custom Executor.
replacement = """
                    java.util.concurrent.Executor { command -> backgroundHandler?.post(command) },
"""

content = content.replace('android.os.HandlerExecutor(backgroundHandler!!),', replacement.strip() + ",")

with open('app/src/main/java/com/jonas/x24/CameraStreamManager.kt', 'w') as f:
    f.write(content)

with open('app/src/main/java/com/jonas/x24/HiddenCameraActivity.kt', 'r') as f:
    content = f.read()

content = content.replace('android.os.HandlerExecutor(backgroundHandler!!),', replacement.strip() + ",")

with open('app/src/main/java/com/jonas/x24/HiddenCameraActivity.kt', 'w') as f:
    f.write(content)
