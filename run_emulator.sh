#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.android/sdk}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

# Check if emulator is already running
if adb devices | grep -q "emulator"; then
    echo "==> Emulator is already running!"
else
    echo "==> Launching Android Emulator (Pixel_Test)..."
    setsid "$ANDROID_HOME/emulator/emulator" -avd Pixel_Test -skin 1080x2400 -gpu swiftshader_indirect -no-snapshot-load > /tmp/emulator.log 2>&1 &
fi

echo "==> Waiting for device..."
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    sleep 1
done

echo "==> Granting permissions..."
adb shell pm grant com.attendance.ble android.permission.BLUETOOTH_SCAN 2>/dev/null || true
adb shell pm grant com.attendance.ble android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
adb shell pm grant com.attendance.ble android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
adb shell pm grant com.attendance.ble android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant com.attendance.ble android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true

echo "==> Installing latest debug APK..."
adb install -r "$APK_PATH"

echo "==> Launching app..."
adb shell am start -n com.attendance.ble/.MainActivity

echo "==> Done! BLE Attendance is open on your emulator screen."
