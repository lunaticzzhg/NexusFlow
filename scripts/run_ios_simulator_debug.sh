#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly PROJECT_PATH="$ROOT_DIR/app/iosApp/OrbitApp.xcodeproj"
readonly SCHEME="OrbitApp"
readonly BUNDLE_ID="com.nexusflow.app"
readonly DERIVED_DATA_PATH="$ROOT_DIR/app/iosApp/build"

usage() {
  cat <<'EOF'
Usage: scripts/run_ios_simulator_debug.sh [--help]

Builds, installs, and launches the Orbit debug app on an iOS Simulator.
Set IOS_SIMULATOR_UDID to select a specific available simulator; otherwise the
first available iPhone simulator is used. Set IOS_API_BASE_URL to override the
default Simulator endpoint (http://localhost:8080). Physical devices are not
supported by this host project.
EOF
}

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 2
fi

if [[ $# -eq 1 ]]; then
  if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    usage
    exit 0
  fi
  usage >&2
  exit 2
fi

for command in xcodebuild xcrun; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required Apple developer tool not found: $command" >&2
    exit 1
  fi
done

if [[ ! -d "$PROJECT_PATH" ]]; then
  echo "iOS host project not found: $PROJECT_PATH" >&2
  exit 1
fi

available_devices="$(xcrun simctl list devices available)"
if [[ -n "${IOS_SIMULATOR_UDID:-}" ]]; then
  simulator_udid="$IOS_SIMULATOR_UDID"
  if ! grep -Fq "($simulator_udid)" <<<"$available_devices"; then
    echo "IOS_SIMULATOR_UDID is not an available iOS Simulator: $simulator_udid" >&2
    exit 1
  fi
else
  simulator_udid="$(awk -F '[()]' '/^[[:space:]]+iPhone / { print $2; exit }' <<<"$available_devices")"
  if [[ -z "$simulator_udid" ]]; then
    echo "No available iPhone Simulator found. Create one in Xcode first." >&2
    exit 1
  fi
fi

simulator_state="$(awk -F '[()]' -v udid="$simulator_udid" '$2 == udid { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $4); print $4; exit }' <<<"$available_devices")"
if command -v open >/dev/null 2>&1; then
  open -a Simulator >/dev/null 2>&1 || true
fi
if [[ "$simulator_state" != "Booted" ]]; then
  xcrun simctl boot "$simulator_udid"
fi
xcrun simctl bootstatus "$simulator_udid" -b

xcodebuild \
  -project "$PROJECT_PATH" \
  -scheme "$SCHEME" \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$simulator_udid" \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  ORBIT_API_BASE_URL="${IOS_API_BASE_URL:-http://localhost:8080}"

app_path="$DERIVED_DATA_PATH/Build/Products/Debug-iphonesimulator/$SCHEME.app"
if [[ ! -d "$app_path" ]]; then
  echo "Built app was not found: $app_path" >&2
  exit 1
fi

xcrun simctl install "$simulator_udid" "$app_path"
xcrun simctl launch "$simulator_udid" "$BUNDLE_ID"
