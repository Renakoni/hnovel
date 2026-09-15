#!/usr/bin/env bash
set -euo pipefail

diagnostics=app/build/reports/emulator-diagnostics
mkdir -p "$diagnostics"
# Capture before emulator-runner tears down the device, including failures before the first test.
capture() {
  status=$?
  trap - EXIT
  timeout 15s adb logcat -d -v threadtime > "$diagnostics/logcat.txt" 2>&1 || true
  timeout 15s adb shell dumpsys package > "$diagnostics/packages.txt" 2>&1 || true
  timeout 15s adb shell df -h /data > "$diagnostics/storage.txt" 2>&1 || true
  exit "$status"
}
trap capture EXIT
timeout 15s adb shell pm path android > "$diagnostics/package-manager-before.txt" 2>&1
# Keep app storage until the workflow pulls fixture reports; the emulator is disposable.
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.class=indi.dmzz_yyhyy.lightnovelreader.sourceexecution.IsolatedExecutionInstrumentedTest,indi.dmzz_yyhyy.lightnovelreader.sourceexecution.SourceAccountInstrumentedTest,indi.dmzz_yyhyy.lightnovelreader.sourceexecution.SourceBrowserInstrumentedTest,indi.dmzz_yyhyy.lightnovelreader.sourceexecution.SourceVpnInstrumentedTest${1:-}" --console=plain --stacktrace --max-workers=2
