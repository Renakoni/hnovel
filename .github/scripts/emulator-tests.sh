#!/usr/bin/env bash
set -euo pipefail

diagnostics=app/build/reports/emulator-diagnostics
mkdir -p "$diagnostics"
logcat_pid=''
capture() {
  status=$?
  trap - EXIT
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
  fi
  timeout 15s adb logcat -d -v threadtime > "$diagnostics/logcat.txt" 2>&1 || true
  timeout 15s adb shell dumpsys window windows > "$diagnostics/windows.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity activities > "$diagnostics/activities.txt" 2>&1 || true
  timeout 15s adb shell dumpsys package > "$diagnostics/packages.txt" 2>&1 || true
  timeout 15s adb shell df -h /data > "$diagnostics/storage.txt" 2>&1 || true
  exit "$status"
}
trap capture EXIT
timeout 15s adb shell pm path android > "$diagnostics/package-manager-before.txt" 2>&1
# Keep crash/ANR/process-death evidence on the host even if instrumentation never
# finishes its report, or a later snapshot no longer contains the original event.
adb logcat -b crash -b events -b system -v threadtime > "$diagnostics/runtime-events.txt" 2>&1 &
logcat_pid=$!

# The launcher unlocks immediately after boot. NexusLauncher can ANR on that
# first input and leave a system dialog covering every subsequent test activity.
# Reset only this emulator launcher before tests, including a pending ANR whose
# dialog has not appeared yet. App crashes and test failures remain untouched.
# Diagnostic capture can disconnect while the emulator resizes its log buffer.
# Launcher and window readiness checks below must still succeed before testing.
timeout 15s adb logcat -d -v threadtime > "$diagnostics/boot-logcat.txt" 2>&1 ||
  echo 'Boot logcat capture failed; continuing with emulator readiness checks.' >&2
# The API 24 Google image's old Messaging app can crash during boot with a
# RejectedExecutionException, leaving a system-owned dialog over every test.
# Disable only that unused image package, before tests, to prevent it restarting.
api=$(timeout 15s adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$api" == 24 ]]; then
  messaging=$(timeout 15s adb shell pm list packages com.google.android.apps.messaging | tr -d '\r')
  if [[ "$messaging" == 'package:com.google.android.apps.messaging' ]]; then
    timeout 15s adb shell pm disable-user --user 0 com.google.android.apps.messaging
    timeout 15s adb shell am force-stop com.google.android.apps.messaging
  fi
fi
launcher=$(timeout 15s adb shell pm list packages com.google.android.apps.nexuslauncher | tr -d '\r')
if [[ "$launcher" == 'package:com.google.android.apps.nexuslauncher' ]]; then
  timeout 15s adb shell am force-stop com.google.android.apps.nexuslauncher
fi
for attempt in {1..5}; do
  timeout 15s adb shell dumpsys window windows > "$diagnostics/windows-before-tests.txt" 2>&1
  if ! grep -Eq 'Application (Error|Not Responding): com\.google\.android\.apps\.(nexuslauncher|messaging)([[:space:]}]|$)' "$diagnostics/windows-before-tests.txt"; then
    "$@"
    exit 0
  fi
  sleep 1
done
echo 'A known emulator system-app error dialog is still covering the emulator; tests were not started.' >&2
exit 1
