#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_BIN="${ADB:-adb}"
PACKAGE_NAME="com.nexusflow.app"
ACTIVITY_NAME="${PACKAGE_NAME}/.MainActivity"
APK_PATH="$ROOT_DIR/app/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
DEVICE_ID="${ANDROID_SERIAL:-}"
TARGET_KIND="auto"

usage() {
  cat <<'EOF'
Usage: scripts/run_android_debug.sh [--emulator|--device] [--help]

Builds the Android debug APK, installs it on an online Android device, and
starts NexusFlow. Android emulators access the Mac host at 10.0.2.2:8080;
physical devices use 127.0.0.1:8080 through adb reverse. The target type is
detected automatically unless --emulator or --device is supplied. When multiple
devices are connected, select one interactively or set ANDROID_SERIAL. Set ADB
to override the adb executable.

Environment:
  ADB=/path/to/adb          adb executable to use (default: adb)
  ANDROID_SERIAL=<serial>   target device serial
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --emulator)
      if [[ "$TARGET_KIND" == "device" ]]; then
        echo "Conflicting target options: use only one of --emulator or --device." >&2
        exit 2
      fi
      TARGET_KIND="emulator"
      ;;
    --device)
      if [[ "$TARGET_KIND" == "emulator" ]]; then
        echo "Conflicting target options: use only one of --emulator or --device." >&2
        exit 2
      fi
      TARGET_KIND="device"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools or set ADB=/path/to/adb." >&2
  exit 1
fi

if [[ -z "$DEVICE_ID" ]]; then
  DEVICE_IDS=()
  while IFS=$'\t' read -r serial state _; do
    if [[ "$state" == "device" ]]; then
      DEVICE_IDS+=("$serial")
    fi
  done < <("$ADB_BIN" devices | tail -n +2)

  case "${#DEVICE_IDS[@]}" in
    0)
      echo "No online Android device found. Start an emulator or connect an authorized device." >&2
      exit 1
      ;;
    1)
      DEVICE_ID="${DEVICE_IDS[0]}"
      ;;
    *)
      if [[ ! -t 0 ]]; then
        echo "Multiple Android devices are online. Set ANDROID_SERIAL to choose one:" >&2
        printf '  %s\n' "${DEVICE_IDS[@]}" >&2
        exit 1
      fi

      echo "Multiple Android devices are online:"
      device_index=1
      for device in "${DEVICE_IDS[@]}"; do
        printf '  %d) %s\n' "$device_index" "$device"
        device_index=$((device_index + 1))
      done

      printf 'Select device [0-%d, 0 to cancel]: ' "${#DEVICE_IDS[@]}"
      if ! IFS= read -r selected_device; then
        echo "Cancelled."
        exit 1
      fi

      case "$selected_device" in
        ''|*[!0-9]*)
          echo "Cancelled: enter a valid device number." >&2
          exit 1
          ;;
      esac

      selected_device=$((10#$selected_device))
      if [[ "$selected_device" -eq 0 ]]; then
        echo "Cancelled."
        exit 1
      fi
      if [[ "$selected_device" -gt "${#DEVICE_IDS[@]}" ]]; then
        echo "Cancelled: device number is out of range." >&2
        exit 1
      fi

      DEVICE_ID="${DEVICE_IDS[$((selected_device - 1))]}"
      echo "Selected device: $DEVICE_ID"
      ;;
  esac
fi

if [[ "$("$ADB_BIN" -s "$DEVICE_ID" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Android device '$DEVICE_ID' is not connected or not authorized." >&2
  exit 1
fi

if [[ "$DEVICE_ID" == emulator-* ]] || [[ "$("$ADB_BIN" -s "$DEVICE_ID" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" == "1" ]]; then
  ACTUAL_TARGET_KIND="emulator"
else
  ACTUAL_TARGET_KIND="device"
fi

if [[ "$TARGET_KIND" != "auto" && "$TARGET_KIND" != "$ACTUAL_TARGET_KIND" ]]; then
  echo "Target '$DEVICE_ID' is a $ACTUAL_TARGET_KIND, but --$TARGET_KIND was requested." >&2
  exit 2
fi

if [[ "$ACTUAL_TARGET_KIND" == "emulator" ]]; then
  DEBUG_API_BASE_URL="http://10.0.2.2:8080"
  echo "Using emulator debug API: $DEBUG_API_BASE_URL"
else
  DEBUG_API_BASE_URL="http://127.0.0.1:8080"
  echo "Forwarding debug API: device 127.0.0.1:8080 -> Mac 127.0.0.1:8080"
  if ! "$ADB_BIN" -s "$DEVICE_ID" reverse tcp:8080 tcp:8080; then
    echo "Failed to configure adb reverse for device '$DEVICE_ID'." >&2
    exit 1
  fi
fi

cd "$ROOT_DIR"
echo "Building Android debug APK..."
./gradlew :app:composeApp:assembleDebug "-PDEBUG_API_BASE_URL=$DEBUG_API_BASE_URL"

if [[ ! -f "$APK_PATH" ]]; then
  echo "Debug APK not found. Expected: $APK_PATH" >&2
  exit 1
fi

echo "Installing on device: $DEVICE_ID"
"$ADB_BIN" -s "$DEVICE_ID" install -r "$APK_PATH"

"$ADB_BIN" -s "$DEVICE_ID" shell am force-stop "$PACKAGE_NAME"
"$ADB_BIN" -s "$DEVICE_ID" shell am start -n "$ACTIVITY_NAME" >/dev/null

echo "Started $PACKAGE_NAME on device: $DEVICE_ID"
echo "APK: $APK_PATH"
