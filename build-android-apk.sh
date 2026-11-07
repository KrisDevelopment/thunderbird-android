#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

APK_PATH="app-thunderbird/build/outputs/apk/full/debug/app-thunderbird-full-debug.apk"
PKG="net.thunderbird.android.debug"
IMAGE="tb-android-build"

# Keep the debug keystore outside the ephemeral container so subsequent builds are
# signed with the same key and can be installed as an update (without wiping app data).
ANDROID_HOME_DIR="$PROJECT_DIR/.signing/android-home"
mkdir -p "$ANDROID_HOME_DIR/.android"

# Persist the Gradle user home (wrapper distribution + dependency cache) between builds.
GRADLE_USER_HOME_DIR="$PROJECT_DIR/.gradle/container-home"
mkdir -p "$GRADLE_USER_HOME_DIR"

deploy_only=false
for arg in "$@"; do
    case "$arg" in
        --deploy-only) deploy_only=true ;;
    esac
done

if [ "$deploy_only" = false ]; then
    docker build -t "$IMAGE" -f Dockerfile.android .
    docker run --rm \
      -v "$PROJECT_DIR:/projects/thunderbird-android" \
      -v "$ANDROID_HOME_DIR/.android:/root/.android" \
      -v "$GRADLE_USER_HOME_DIR:/root/.gradle" \
      -w /projects/thunderbird-android \
      "$IMAGE" \
      -c "cd /projects/thunderbird-android && ./gradlew :app-thunderbird:assembleFullDebug"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "APK not found at $APK_PATH"
    exit 1
fi

# Deploy prompt
if [ -t 1 ]; then
    read -p "Do you want to deploy the APK to a connected Android device? (y/n): " deploy
    if [[ "$deploy" != "y" ]]; then
        echo "Skipping deploy."
        exit 0
    fi
else
    echo "Not in TTY mode. Skipping deploy prompt."
    exit 0
fi

# Ensure adb
if ! command -v adb &> /dev/null; then
    read -p "adb not found. Install it now? (y/n): " install_adb
    if [[ "$install_adb" != "y" ]]; then
        echo "adb is required. Install manually."
        exit 1
    fi
    sudo apt update && sudo apt install -y adb
fi

echo "Checking for connected Android device..."
if adb devices | grep -q "device$"; then
    echo "Device found! Installing APK..."
    if adb install -r "$APK_PATH"; then
        echo "APK installed successfully!"
    else
        echo "Update failed. A fresh install deletes all accounts and settings in the debug app."
        read -r -p "Uninstall Thunderbird Debug and delete its data? (y/n): " uninstall_debug
        if [[ "$uninstall_debug" != "y" ]]; then
            echo "Leaving the existing app and its data untouched."
            exit 1
        fi
        adb shell pm uninstall "$PKG"
        adb install "$APK_PATH"
        echo "APK installed successfully (app data was reset)."
    fi

    echo "Launching app..."
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

    read -p "Connect device logcat to terminal? (y/n): " connect_output
    if [[ "$connect_output" == "y" ]]; then
        APP_PID=$(adb shell pidof "$PKG")
        if [[ -n "$APP_PID" ]]; then
            adb logcat --pid="$APP_PID"
        else
            echo "App not running; showing full logcat. Ctrl+C to stop."
            adb logcat
        fi
    fi
else
    echo "No authorized Android device found. Connect a device with USB debugging enabled and authorized."
    exit 1
fi
