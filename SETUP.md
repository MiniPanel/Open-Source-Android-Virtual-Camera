# Running Open Source Android Virtual Camera on Emulator

## Prerequisites
1. Android Studio installed (includes emulator and SDK tools)
2. Android SDK Platform 33 (Android 13.0) or higher installed
3. Android Virtual Device (AVD) configured with API level 33+

## Building the APK

The project has already been built successfully. The debug APK is located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

To rebuild:
```bash
./gradlew assembleDebug
```

## Setting Up the Emulator

### Option 1: Using Android Studio AVD Manager
1. Open Android Studio
2. Go to Tools > AVD Manager
3. Click "Create Virtual Device..."
4. Select a phone definition (e.g., Pixel 5)
5. Choose system image: API Level 33, x86_64 (Android 13.0)
6. Finish setup

### Option 2: Using Command Line
```bash
# List available system images
emulator -list-avds

# Create a new AVD (if not exists)
avdmanager create avd -n pixel5_api33 -k "system-images;android-33;google_apis;x86_64"

# Start the emulator
emulator -avd pixel5_api33 -no-window &
# or with window:
emulator -avd pixel5_api33
```

## Installing the APK

Once the emulator is running:

```bash
# Check if device is connected
adb devices

# Install the APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Adding a Test Video

The app requires a video file to stream as the virtual camera. You have two options:

### Option 1: Use Default Video (Recommended for Testing)
1. Create a test video file using FFmpeg:
   ```bash
   ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 \
          -f lavfi -i sine=frequency=440:duration=10 \
          -c:v libx264 -pix_fmt yuv420p -c:a aac \
          -shortest test_video.mp4
   ```

2. Push the video to the emulator:
   ```bash
   adb push test_video.mp4 /sdcard/
   ```

3. In the app, use the file picker to select `/sdcard/test_video.mp4`

### Option 2: Use Existing Video
1. Transfer any MP4 video to the emulator:
   ```bash
   adb push your_video.mp4 /sdcard/
   ```

2. Select it via the file picker in the app

### Option 3: Use Android Resource Video
1. Place a video file named `sample_video.mp4` in:
   `app/src/main/res/raw/`
2. Rebuild the APK:
   ```bash
   ./gradlew assembleDebug
   ```
3. The app will automatically use this as the default video

## Running the Virtual Camera

1. Launch the app from the emulator's app drawer
2. Grant camera permission when prompted
3. Tap "Start Virtual Camera" (or "Start with Custom Video" and select your video)
4. A notification will appear indicating the virtual camera is active
5. Open any camera app (Camera, Zoom, Google Meet, etc.) on the emulator
6. Select "virtual_camera_fd" as the camera source
7. You should see your video playing in the camera preview
8. Tap "Stop Virtual Camera" in the app to stop the service

## Testing Recommendations

1. **Test Duration**: Run for at least 5-10 minutes to verify stable looping
2. **Multiple Apps**: Test with different camera apps to ensure compatibility
3. **Background Test**: Switch away from the app and verify video continues playing
4. **Resource Usage**: Monitor battery and CPU impact (should be minimal)

## Troubleshooting

### "Virtual Camera not available" Error
- Ensure you're running on Android 13+ (API 33+)
- Check emulator system image specifications

### Video Not Playing
- Verify video format is supported (MP4/H.264 recommended)
- Check that the video file is readable by the app
- Look for errors in Logcat: `adb logcat | grep VirtualCamera`

### Permission Issues
- Ensure CAMERA permission is granted
- For Android 13+, POST_NOTIFICATIONS permission may be needed

### Performance Issues
- Lower resolution videos perform better
- Consider reducing video bitrate for smoother playback

## Notes on VirtualCamera API

This implementation uses the Android 13+ VirtualCamera API which requires:
- Android 13 (API level 33) or higher
- The app to declare the camera permission
- Running as a foreground service

On devices/emulators below API 33, the app will show a toast indicating the requirement.

## Source Code Overview

- `MainActivity.kt`: UI for starting/stopping camera and selecting videos
- `VirtualCameraService.kt`: Background service managing the virtual camera lifecycle
- `CameraProvider.kt`: Core logic for video processing and camera framework integration
- `AndroidManifest.xml`: Includes required permissions and service declaration

## License
MIT License - see LICENSE file for details.