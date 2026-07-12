#!/usr/bin/env bash
#
# Dev-build + deploy-to-test-device flow.
#
# Builds the debug APK, installs it on a connected/paired device (USB or
# wireless adb), launches it, and tails the app's Logcat output for a bounded
# duration (default 15s) so it is safe to run non-interactively — it will not
# hang your terminal waiting for Ctrl-C.
#
# Usage:
#   scripts/dev_deploy.sh                    # build, install, launch, tail logs 15s
#   scripts/dev_deploy.sh --log-seconds 60    # tail logs for 60s instead
#   scripts/dev_deploy.sh --no-launch         # build + install only
#   scripts/dev_deploy.sh --no-log            # build + install + launch, no log tail
#   scripts/dev_deploy.sh --serial R58...     # target a specific device (adb -s)
#   scripts/dev_deploy.sh --connect IP:PORT   # adb connect first (wireless debugging)
#
# For an open-ended log tail (interactive terminal only, Ctrl-C to stop), run
# plain `adb logcat` yourself in a separate shell instead of this script.
#
# Requires: ANDROID_HOME set (or falls back to ~/Android/Sdk), a device
# already paired for wireless debugging (Settings > Developer options >
# Wireless debugging > Pair device with pairing code, once per machine) or
# connected via USB.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
PACKAGE="com.polarppgbp"
ACTIVITY="$PACKAGE/.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

LAUNCH=1
TAIL_LOG=1
LOG_DURATION=15
SERIAL=""
CONNECT_TARGET=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-launch) LAUNCH=0; shift ;;
        --no-log) TAIL_LOG=0; shift ;;
        --log-seconds) LOG_DURATION="$2"; shift 2 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --connect) CONNECT_TARGET="$2"; shift 2 ;;
        -h|--help) grep '^#' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [[ ! -x "$ADB" ]]; then
    echo "adb not found at $ADB. Set ANDROID_HOME or pass its location." >&2
    exit 1
fi

ADB_ARGS=()
if [[ -n "$CONNECT_TARGET" ]]; then
    echo "==> adb connect $CONNECT_TARGET"
    "$ADB" connect "$CONNECT_TARGET"
    SERIAL="${SERIAL:-$CONNECT_TARGET}"
fi
if [[ -n "$SERIAL" ]]; then
    ADB_ARGS=(-s "$SERIAL")
fi

echo "==> Checking for a connected device"
DEVICE_COUNT=$("$ADB" "${ADB_ARGS[@]}" devices | grep -cE '\bdevice$' || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "No device found. Connect via USB, or pair + connect wireless debugging first:" >&2
    echo "  Phone: Settings > Developer options > Wireless debugging > Pair device with pairing code" >&2
    echo "  Then: scripts/dev_deploy.sh --connect <IP:PORT>" >&2
    exit 1
fi
"$ADB" "${ADB_ARGS[@]}" devices

echo "==> Building debug APK"
./gradlew :app:assembleDebug --console=plain

if [[ ! -f "$APK_PATH" ]]; then
    echo "Build did not produce $APK_PATH" >&2
    exit 1
fi

echo "==> Installing on device"
"$ADB" "${ADB_ARGS[@]}" install -r "$APK_PATH"

if [[ "$LAUNCH" -eq 1 ]]; then
    echo "==> Launching $ACTIVITY"
    "$ADB" "${ADB_ARGS[@]}" shell am start -n "$ACTIVITY"
fi

if [[ "$TAIL_LOG" -eq 1 ]]; then
    echo "==> Tailing Logcat for ${LOG_DURATION}s (timeboxed, safe for non-interactive use)"
    echo "    Use --log-seconds N for a longer/shorter window, or --no-log to skip."
    echo "    Useful debug commands in another shell:"
    echo "      adb shell am broadcast -a $PACKAGE.debug.STATUS"
    echo "      adb shell am broadcast -a $PACKAGE.debug.START_RECORDING --es profile calibration"
    timeout "${LOG_DURATION}" "$ADB" "${ADB_ARGS[@]}" logcat \
        | grep --line-buffered -E "PolarRepo|RecordingService|SyncWorker|SyncScheduler|DebugCmd|AndroidRuntime" || true
    echo "==> Log tail finished (timeout reached)."
fi
