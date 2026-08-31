#!/usr/bin/env bash
set -euo pipefail

APK="arabvpn/build/outputs/apk/debug/arabvpn-debug.apk"
PACKAGE="com.malik05255.arabvpn"
ACTIVITY="com.arabvpn.app.MainActivity"
UI_DUMP="/tmp/arabvpn-update-window.xml"

if [[ ! -f "$APK" ]]; then
  echo "Missing APK: $APK" >&2
  exit 1
fi

adb logcat -c
adb install -r "$APK"

# Avoid the Android 13+ notification permission dialog masking the updater dialog in this test.
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
adb shell am force-stop "$PACKAGE" || true
adb shell am start -W -n "$PACKAGE/$ACTIVITY"

# Give the real GitHub release API/update.json request enough time on the emulator network.
sleep 15

if ! adb shell pidof "$PACKAGE" >/dev/null 2>&1; then
  echo "Arab VPN process died while checking for an update" >&2
  adb logcat -d -v threadtime | tail -800
  exit 1
fi

adb shell uiautomator dump /sdcard/arabvpn-update-window.xml >/dev/null
adb pull /sdcard/arabvpn-update-window.xml "$UI_DUMP" >/dev/null

if ! grep -Fq "تحديث جديد" "$UI_DUMP" || ! grep -Fq "تحديث الآن" "$UI_DUMP"; then
  echo "Expected in-app update dialog was not visible" >&2
  cat "$UI_DUMP" >&2
  adb logcat -d -v threadtime | tail -500 >&2
  exit 1
fi

NOTIFICATION_DUMP="$(adb shell dumpsys notification --noredact)"
if ! printf '%s\n' "$NOTIFICATION_DUMP" | grep -Fq "$PACKAGE" || \
   ! printf '%s\n' "$NOTIFICATION_DUMP" | grep -Fq "8101"; then
  echo "Expected Android update notification was not posted" >&2
  printf '%s\n' "$NOTIFICATION_DUMP" | tail -800 >&2
  exit 1
fi

LOGCAT="$(adb logcat -d -v threadtime)"
if printf '%s\n' "$LOGCAT" | grep -qE "FATAL EXCEPTION|Process: ${PACKAGE}"; then
  echo "Fatal runtime crash detected during update alert test" >&2
  printf '%s\n' "$LOGCAT" | grep -A120 -B30 -E "FATAL EXCEPTION|Process: ${PACKAGE}" || true
  exit 1
fi

echo "Arab VPN update popup + Android notification smoke test passed"
