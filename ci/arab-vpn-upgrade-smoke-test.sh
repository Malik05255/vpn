#!/usr/bin/env bash
set -euo pipefail

OLD_APK="upgrade-test/ArabVPN-dev.apk"
NEW_APK="upgrade-test/current.apk"
PACKAGE="com.malik05255.arabvpn"
ACTIVITY="com.arabvpn.app.MainActivity"

for apk in "$OLD_APK" "$NEW_APK"; do
  if [[ ! -f "$apk" ]]; then
    echo "Missing upgrade-test APK: $apk" >&2
    exit 1
  fi
done

adb logcat -c

echo "Installing previous published development APK..."
adb install "$OLD_APK"
OLD_VERSION=$(adb shell dumpsys package "$PACKAGE" | grep -m1 'versionCode=' | sed -E 's/.*versionCode=([0-9]+).*/\1/')
test -n "$OLD_VERSION"

echo "Installing current APK over previous APK without uninstall..."
adb install -r "$NEW_APK"
NEW_VERSION=$(adb shell dumpsys package "$PACKAGE" | grep -m1 'versionCode=' | sed -E 's/.*versionCode=([0-9]+).*/\1/')
test -n "$NEW_VERSION"

if (( NEW_VERSION <= OLD_VERSION )); then
  echo "Upgrade version did not increase: old=$OLD_VERSION new=$NEW_VERSION" >&2
  exit 1
fi

adb shell am start -W -n "$PACKAGE/$ACTIVITY"
sleep 5

if ! adb shell pidof "$PACKAGE" >/dev/null; then
  echo "Arab VPN process died after in-place upgrade" >&2
  adb logcat -d -v threadtime | tail -500
  exit 1
fi

if adb logcat -d -v threadtime | grep -A80 -B20 -E "FATAL EXCEPTION|Process: $PACKAGE"; then
  echo "Fatal runtime crash detected after in-place upgrade" >&2
  exit 1
fi

echo "In-place upgrade succeeded: $OLD_VERSION -> $NEW_VERSION"
