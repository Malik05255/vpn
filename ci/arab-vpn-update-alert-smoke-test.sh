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

# Avoid permission sheets masking the updater UI. REQUEST_INSTALL_PACKAGES is still exercised by
# the app; the emulator app-op simply represents the user having enabled "Allow from this source".
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
adb shell appops set "$PACKAGE" REQUEST_INSTALL_PACKAGES allow || true
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

adb shell dumpsys notification --noredact > "$NOTIFICATION_DUMP"
if ! grep -Fq "pkg=$PACKAGE" "$NOTIFICATION_DUMP" || \
   ! grep -Fq "id=8101" "$NOTIFICATION_DUMP" || \
   ! grep -Fq "arab_vpn_updates_v2" "$NOTIFICATION_DUMP" || \
   ! grep -Fq "تحديث الآن" "$NOTIFICATION_DUMP"; then
  echo "Expected Android update notification was not posted" >&2
  tail -800 "$NOTIFICATION_DUMP" >&2
  exit 1
fi

# Tap the actual in-app "Update now" action. This is the regression check for the previous bug:
# download used to finish and stop at a second notification instead of opening Android's installer.
read -r TAP_X TAP_Y < <(
  python3 - "$UI_DUMP" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.attrib.get("text") == "تحديث الآن":
        bounds = node.attrib.get("bounds", "")
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            raise SystemExit(0)
raise SystemExit("Update-now button bounds not found")
PY
)
adb shell input tap "$TAP_X" "$TAP_Y"

INSTALLER_VISIBLE=0
for _ in $(seq 1 90); do
  ACTIVITY_DUMP="$(adb shell dumpsys activity activities 2>/dev/null || true)"
  if printf '%s\n' "$ACTIVITY_DUMP" | grep -Eqi \
    'com\.android\.packageinstaller|packageinstaller|PackageInstallerActivity|InstallStart'; then
    INSTALLER_VISIBLE=1
    break
  fi

  # Fail early if Arab VPN crashes while downloading/verifying/handover is running.
  if adb logcat -d -v brief | grep -qE "FATAL EXCEPTION|Process: ${PACKAGE}"; then
    echo "Arab VPN crashed during update install handoff" >&2
    adb logcat -d -v threadtime | tail -1000 >&2
    exit 1
  fi
  sleep 2
done

if [[ "$INSTALLER_VISIBLE" -ne 1 ]]; then
  echo "Update downloaded/processed but Android package installer was not opened automatically" >&2
  adb shell dumpsys activity activities | head -500 >&2 || true
  adb shell dumpsys jobscheduler | grep -A80 -B20 "$PACKAGE" >&2 || true
  adb logcat -d -v threadtime | tail -1200 >&2
  exit 1
fi

LOGCAT_FILE="/tmp/arabvpn-update-logcat.txt"
adb logcat -d -v threadtime > "$LOGCAT_FILE"
if grep -qE "FATAL EXCEPTION|Process: ${PACKAGE}" "$LOGCAT_FILE"; then
  echo "Fatal runtime crash detected during update alert/install test" >&2
  grep -A120 -B30 -E "FATAL EXCEPTION|Process: ${PACKAGE}" "$LOGCAT_FILE" || true
  exit 1
fi

echo "Arab VPN update popup + notification + installer handoff smoke test passed"
