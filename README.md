# Open Source Android Virtual Camera

**Developer:** Afaq Ahmad  
**License:** Apache License 2.0  
**Repository:** https://github.com/yourusername/OpenSourceAndroidVirtualCamera  

---

## Overview

This is a fully open-source Android virtual camera service that substitutes the physical camera with a pre-recorded video. The service runs entirely in the background and feeds the chosen video in an endless loop to the Android camera framework, appearing as a standard camera to applications such as Zoom, Google Meet, Teams, WhatsApp, OBS, or any live-streaming client.

This project is developed by **Afaq Ahmad** and is released under the **Apache License 2.0** to ensure it remains fully open-source, modifiable, and study-friendly for the community.

---

## Features

- **Endless Video Loop** - Selected video plays in a seamless infinite loop without user intervention.
- **Background Operation** - Runs as a foreground service; continues even when UI is dismissed or screen is off.
- **Minimal Resource Usage** - Optimized decoder loop keeps battery and CPU impact low.
- **Start/Stop Control** - Can be started or stopped programmatically or through the small control screen.
- **Custom Video Selection** - Use the bundled sample video or select any video from device storage.
- **Boot Auto-Start** - Optionally starts automatically on device boot (requires user grant).
- **No Root Required** - Uses `MediaProjection` APIs available on Android 10+.
- **Fully Open Source** - Apache 2.0 licensed; all code is readable and modifiable.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              MainActivity (UI)               │
│  - Permission handling                       │
│  - Video picker (custom / default)           │
│  - Start / Stop buttons                      │
│  - MediaProjection permission grant flow     │
└──────────────────┬──────────────────────────┘
                   │ binds/starts
┌──────────────────▼──────────────────────────┐
│        VirtualCameraService (Foreground)     │
│  - Receives projection token from Activity   │
│  - Creates MediaProjection + VirtualDisplay  │
│  - Owns VideoDecoder + CoreVirtualCamera     │
│  - Manages lifecycle (start/stop/boot)       │
│  - Persistent notification with Stop action  │
└──────────────────┬──────────────────────────┘
                   │
       ┌───────────┴────────────┐
       ▼                        ▼
┌──────────────┐      ┌───────────────────┐
│ VideoDecoder │      │ CoreVirtualCamera │
│ -Extractor/  │      │ - ImageReader     │
│  MediaCodec  │      │ - RGBA→NV21       │
│ -Seek/loop   │      │ - Frame sink      │
│ -Callback    │      │ - VirtualDisplay  │
└──────────────┘      └───────────────────┘
```

---

## Requirements

- **Android 10 (API 29)** or higher
- **Android Studio** Hedgehog (2023.1.1) or newer
- **Java 11** or **Kotlin 1.9+** runtime
- **Gradle 8+** wrapper included

### Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| `CAMERA` | Register virtual camera capability | Yes |
| `FOREGROUND_SERVICE` | Background service operation | Yes |
| `FOREGROUND_SERVICE_CAMERA` | Camera-type foreground service | Yes |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) | Conditional |
| `READ_EXTERNAL_STORAGE` | Pick custom video from storage | Conditional |
| `SYSTEM_ALERT_WINDOW` | Overlay permission (if needed) | No |

---

## Build Steps

### 1. Clone or extract the project

```bash
cd OpenSourceAndroidVirtualCamera
```

### 2. Verify Android SDK path

Ensure `local.properties` exists:

```properties
sdk.dir=/Users/macos/Library/Android/sdk
```

Or set environment variable:

```bash
export ANDROID_HOME=/Users/macos/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

### 3. Clean and build the APK

```bash
./gradlew clean assembleDebug
```

### 4. Locate the APK

```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Installation and Testing

### Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch the app

```bash
adb shell am start -n com.virtualcamera/.MainActivity
```

### Grant projection permission

1. Open the app on your device.
2. Tap **"Start Virtual Camera"** or **"Start with Custom Video"**.
3. Android will show a **"Screen Capture"** permission dialog.
4. Tap **"Start Now"** to grant `MediaProjection` access.
   - This permission is safe: it only lets the app capture its own virtual display content, not your real screen.

### Verify the virtual camera

1. Open **Zoom**, **Google Meet**, or any camera app.
2. In camera settings, select **"virtual_camera_fd"**.
3. You should see the looping video feed.

---

## How to Replace the Default Video

The project does **not** bundle a large video file. Use one of the two methods below:

### Method A: Select custom video at runtime (recommended)

1. Open the app.
2. Tap **"Start with Custom Video"**.
3. Pick any MP4/WebM file from your device.
4. Grant projection permission when prompted.

### Method B: Bundle a video in the APK

Place your video file at:

```
app/src/main/res/raw/sample_video.mp4
```

The app will automatically detect and use it when you tap **"Start Virtual Camera"**.

---

## How It Works

### Video Decoding (`VideoDecoder.kt`)

- Uses `MediaExtractor` + `MediaCodec` for hardware-accelerated decoding.
- Reads the video track, configures the decoder, and starts a decode loop on a dedicated thread.
- On end-of-stream, the decoder seeks back to frame 0 and continues looping.
- Calls `DecoderCallback.onFrameAvailable()` for each decoded frame.

### Virtual Camera Pipeline (`CoreVirtualCamera.kt`)

- Creates a `MediaProjection` from the cached permission token.
- Creates a `VirtualDisplay` backed by an `ImageReader` (RGBA_8888).
- Converts RGBA frames to NV21 format using `rgbaToNV21()`.
- Stores the latest frame for consumption by the decoder callback.

### Foreground Service (`VirtualCameraService.kt`)

- Receives start/stop/boot intents.
- Manages `VideoDecoder` and `CoreVirtualCamera` lifecycles.
- Posts a persistent notification with a **Stop** action.
- Uses `START_STICKY` so the service restarts if killed by the system.

### Main Activity (`MainActivity.kt`)

- Handles runtime permissions (`CAMERA`, `POST_NOTIFICATIONS`, storage).
- Launches `MediaProjection` permission flow.
- Binds to the service for state observation (`IDLE` / `RUNNING` / `ERROR`).
- Observes `stateFlow` to update the UI status.

---

## Testing Instructions

### Quick verification (2 minutes)

```bash
# 1. Install app
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Launch main activity
adb shell am start -n com.virtualcamera/.MainActivity

# 3. Watch logcat for service state
adb logcat -s VirtualCameraService VideoDecoder CoreVirtualCamera MainActivity
```

### Long-loop test (10+ minutes)

1. Install and launch the app.
2. Tap **"Start with Custom Video"** and pick a short video (e.g., 30 seconds).
3. Grant the screen capture permission.
4. Open Zoom/Meet, select the virtual camera.
5. Let it run for 10 minutes.
6. Confirm:
   - No unexpected pauses or freezes.
   - Video loops seamlessly at the end of each cycle.
   - Foreground notification remains visible.
   - Logcat shows no crash loops.

### Automated stress test (optional)

```bash
# Monitor logs for 10 minutes
adb logcat -s VirtualCameraService VideoDecoder | grep --line-buffered -E "(error|ERROR|STOPPED|START)"
```

---

## Code Layout

```
app/src/main/java/com/virtualcamera/
├── CoreVirtualCamera.kt     # MediaProjection + VirtualDisplay + frame conversion
├── VideoDecoder.kt          # MediaExtractor + MediaCodec decode loop
├── VirtualCameraService.kt  # Foreground service + notification lifecycle
├── MainActivity.kt          # UI, permissions, projection flow
└── FrameSink.kt             # Interface for frame data transport
```

---

## Modifying and Extending

### Add a bundled sample video

Place `sample_video.mp4` in `app/src/main/res/raw/`.

### Change resolution

Edit `VideoDecoder` output format or `CoreVirtualCamera` width/height (default 1280×720).

### Add video selection UI

Use `Intent.ACTION_OPEN_DOCUMENT` + `VideoDecoder` URI-fed `MediaExtractor`.

### Change decoder behavior

Edit `decodeLoop()` in `VideoDecoder.kt` — the loop is a standard MediaCodec input/output pump.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `MediaProjection` denied | Re-tap **Re-grant** in the app or reinstall. |
| Virtual camera not listed | Ensure you granted projection and the service is `RUNNING`. |
| APK won't install | Verify `compileSdk`/`targetSdk` versions, uninstall old APK first. |
| Build fails on `minSdk` | Ensure SDK Platform 29 is installed in SDK Manager. |

---

## License

```
Apache License 2.0 (OSI-approved)

Copyright 2024 Afaq Ahmad

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## Contributing

This project is maintained by **Afaq Ahmad**. Contributions, issues, and pull requests are welcome on GitHub.

1. Fork the repository.
2. Create your feature branch (`git checkout -b feature/new-feature`).
3. Commit your changes.
4. Push to the branch (`git push origin feature/new-feature`).
5. Open a Pull Request.

---

## Contact

- **Developer:** Afaq Ahmad
- **Project:** Open Source Android Virtual Camera
- **License:** Apache 2.0
