#!/usr/bin/env bash
set -euo pipefail

APK="arabvpn/build/outputs/apk/debug/arabvpn-debug.apk"
PACKAGE="com.malik05255.arabvpn"
ACTIVITY="com.arabvpn.app.MainActivity"
UI_DUMP="/tmp/arabvpn-update-window.xml"
NOTIFICATION_DUMP="/tmp/arabvpn-notifications.txt"

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

# Give the real GitHub update endpoint enough time on the emulator network.
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

# Store the dump as a file instead of a huge shell variable. The old pipeline could return a
# false negative under pipefail even though NotificationManager clearly contained id=8101.
adb shell dumpsys notification --noredact > "$NOTIFICATION_DUMP"
if ! grep -Fq "pkg=$PACKAGE" "$NOTIFICATION_DUMP" || \
   ! grep -Fq "id=8101" "$NOTIFICATION_DUMP" || \
   ! grep -Fq "arab_vpn_updates_v2" "$NOTIFICATION_DUMP" || \
   ! grep -Fq "تحديث الآن" "$NOTIFICATION_DUMP"; then
  echo "Expected Android update notification was not posted" >&2
  tail -800 "$NOTIFICATION_DUMP" >&2
  exit 1
fi

LOGCAT_FILE="/tmp/arabvpn-update-logcat.txt"
adb logcat -d -v threadtime > "$LOGCAT_FILE"
if grep -qE "FATAL EXCEPTION|Process: ${PACKAGE}" "$LOGCAT_FILE"; then
  echo "Fatal runtime crash detected during update alert test" >&2
  grep -A120 -B30 -E "FATAL EXCEPTION|Process: ${PACKAGE}" "$LOGCAT_FILE" || true
  exit 1
fi

echo "Arab VPN update popup + Android notification smoke test passed"
