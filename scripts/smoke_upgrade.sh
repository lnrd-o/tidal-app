#!/usr/bin/env bash
# Starts the previous release, installs the new APK over it and starts that.
# The check fails when the app is not running afterwards or when it logged a crash.
# Needs adb and a running emulator or device.
#
# Usage: scripts/smoke_upgrade.sh NEW_APK [PREVIOUS_APK]
set -euo pipefail

new=${1:?usage: smoke_upgrade.sh NEW_APK [PREVIOUS_APK]}
previous=${2:-}
package=io.github.lnrdo.mytidal

start() {
  adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 > /dev/null
  sleep 12
}

check() {
  if ! adb shell pidof "$package" > /dev/null; then
    echo "The app is not running after $1." >&2
    adb logcat -d -t 200 >&2
    exit 1
  fi
  if adb logcat -d -b crash | grep -q "Process: $package"; then
    echo "The app crashed after $1." >&2
    adb logcat -d -b crash >&2
    exit 1
  fi
}

# Older emulators refuse to clear a log. The check below reads the crash log of this run only, so a leftover does no harm.
adb logcat -b all -c || true
if [ -n "$previous" ]; then
  adb install -r "$previous" > /dev/null
  start
  check "starting the previous release"
  adb shell am force-stop "$package"
  echo "The previous release started."
fi
adb install -r "$new" > /dev/null
start
check "the upgrade"
adb shell dumpsys package "$package" | grep -m1 versionName
adb exec-out screencap -p > "${SMOKE_SCREENSHOT:-upgrade.png}" || true
echo "The upgrade works."
