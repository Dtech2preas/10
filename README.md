# x24 - Android AI Assistant

x24 is a native Android assistant that integrates with a Cloudflare Worker (Llama 3) to control your phone's hardware and apps.

## Features
- **Voice Interaction**: Talk to x24 using the "TALK" button.
- **Hardware Control**:
  - Toggle Flashlight
  - Control Bluetooth
  - Adjust Volume & Brightness
- **App & Communication**:
  - Open Apps (e.g., "Open WhatsApp")
  - Make Calls
  - Send SMS

## Setup Instructions

### 1. Cloudflare Worker (The Brain)
1.  Copy the content of `worker.js` from this repository.
2.  Go to your Cloudflare Dashboard -> Workers.
3.  Edit your existing worker (or create a new one).
4.  Paste the code and deploy.
5.  **Important**: Ensure your worker has a binding to Workers AI (`env.AI`).

### 2. Android App (The Body)
This repository contains a GitHub Actions workflow to automatically build the APK.

1.  Push this code to GitHub.
2.  Go to the **Actions** tab in your repository.
3.  Click on the "Android Build" workflow.
4.  Once the build finishes, download the `x24-debug` artifact.
5.  Extract the zip and install `app-debug.apk` on your Android phone.

### 3. Permissions
When you first run the app, it will ask for several permissions (Microphone, Camera, Phone, SMS, etc.). Grant them all for full functionality.
**Note**: To control Brightness, the app will redirect you to a system settings page. You must manually enable "Allow modifying system settings" for x24.

## Development
- **Language**: Kotlin
- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: Retrofit for Networking, Coroutines for Async, Native Android Managers for hardware control.
