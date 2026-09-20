#!/usr/bin/env bash
set -euo pipefail

diagnostics=app/build/reports/emulator-diagnostics
mkdir -p "$diagnostics"
capture() {
  status=$?
  trap - EXIT
  timeout 15s adb logcat -d -v threadtime > "$diagnostics/logcat.txt" 2>&1 || true
  timeout 15s adb shell dumpsys window windows > "$diagnostics/windows.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity activities > "$diagnostics/activities.txt" 2>&1 || true
  timeout 15s adb shell dumpsys package > "$diagnostics/packages.txt" 2>&1 || true
  timeout 15s adb shell df -h /data > "$diagnostics/storage.txt" 2>&1 || true
  exit "$status"
}
trap capture EXIT
timeout 15s adb shell pm path android > "$diagnostics/package-manager-before.txt" 2>&1

# emulator-runner unlocks immediately after boot. NexusLauncher can ANR on that
# first input and leave a system dialog covering every subsequent test activity.
# Reset only this emulator launcher before tests, including a pending ANR whose
# dialog has not appeared yet. App crashes and test failures remain untouched.
timeout 15s adb logcat -d -v threadtime > "$diagnostics/boot-logcat.txt" 2>&1
launcher=$(timeout 15s adb shell pm list packages com.google.android.apps.nexuslauncher | tr -d '\r')
if [[ "$launcher" == 'package:com.google.android.apps.nexuslauncher' ]]; then
  timeout 15s adb shell am force-stop com.google.android.apps.nexuslauncher
fi
for attempt in {1..5}; do
  timeout 15s adb shell dumpsys window windows > "$diagnostics/windows-before-tests.txt" 2>&1
  if ! grep -Fq 'Application Not Responding: com.google.android.apps.nexuslauncher' "$diagnostics/windows-before-tests.txt"; then
    "$@"
    exit 0
  fi
  sleep 1
done
echo 'NexusLauncher ANR dialog is still covering the emulator; tests were not started.' >&2
exit 1
