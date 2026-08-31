#!/usr/bin/env bash
set -euo pipefail

APK="arabvpn/build/outputs/apk/debug/arabvpn-debug.apk"
PACKAGE="com.malik05255.arabvpn"
ACTIVITY="com.arabvpn.app.MainActivity"

if [[ ! -f "$APK" ]]; then
  echo "Missing APK: $APK" >&2
  exit 1
fi

adb logcat -c
adb install -r "$APK"
adb shell am force-stop "$PACKAGE" || true
adb shell am start -W -n "$PACKAGE/$ACTIVITY"
sleep 7

if ! adb shell pidof "$PACKAGE" >/dev/null 2>&1; then
  echo "Arab VPN process died during startup" >&2
  adb logcat -d -v threadtime | tail -800
  exit 1
fi

LOGCAT="$(adb logcat -d -v threadtime)"
if printf '%s\n' "$LOGCAT" | grep -qE "FATAL EXCEPTION|Process: ${PACKAGE}"; then
  echo "Fatal runtime crash detected" >&2
  printf '%s\n' "$LOGCAT" | grep -A120 -B30 -E "FATAL EXCEPTION|Process: ${PACKAGE}" || true
  exit 1
fi

if ! adb shell dumpsys activity activities | grep -F "$PACKAGE" >/dev/null 2>&1; then
  echo "Arab VPN is running but no activity is visible/resumed" >&2
  adb shell dumpsys activity activities | tail -300
  exit 1
fi

echo "Arab VPN startup smoke test passed"
