#!/usr/bin/env bash
set -euo pipefail

test_classes="indi.renakoni.nextvol.sourceexecution.IsolatedExecutionInstrumentedTest,indi.renakoni.nextvol.sourceexecution.SourceAccountInstrumentedTest,indi.renakoni.nextvol.sourceexecution.SourceBrowserInstrumentedTest,indi.renakoni.nextvol.sourceexecution.SourceVpnInstrumentedTest${1:-}"

# API 24 can hang after UTP/ddmlib has streamed all APK bytes into install-write.
# Push installation avoids that path while retaining the same runner and test selection.
# See https://github.com/maplibre/maplibre-compose/issues/1047.
if [[ "$(timeout 15s adb shell getprop ro.build.version.sdk | tr -d '\r')" == 24 ]]; then
  app_apks=(app/build/outputs/apk/debug/*.apk)
  test_apks=(app/build/outputs/apk/androidTest/debug/*.apk)
  if [[ ${#app_apks[@]} != 1 || ${#test_apks[@]} != 1 || ! -f "${app_apks[0]}" || ! -f "${test_apks[0]}" ]]; then
    echo "Expected one app APK and one test APK from the preceding build" >&2
    exit 1
  fi
  timeout 180s adb install --no-streaming -r -t "${app_apks[0]}"
  timeout 180s adb install --no-streaming -r -t "${test_apks[0]}"
  test_package=$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/androidTest/debug/output-metadata.json"))["applicationId"])')
  results=app/build/outputs/androidTest-results/connected/debug
  mkdir -p "$results"
  timeout 12m adb shell am instrument -w -r -e class "$test_classes" \
    "$test_package/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$results/instrumentation.txt"
  # am instrument may exit zero after assertion failures, crashes, or an empty selection.
  grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$results/instrumentation.txt"
  grep -qx 'INSTRUMENTATION_STATUS_CODE: 0' "$results/instrumentation.txt"
  grep -qx 'INSTRUMENTATION_CODE: -1' "$results/instrumentation.txt"
  if grep -Eq '^INSTRUMENTATION_(FAILED|ABORTED)|^FAILURES!!!|^INSTRUMENTATION_STATUS_CODE: -[12]$' "$results/instrumentation.txt"; then
    exit 1
  fi
  exit 0
fi
# Keep app storage until the workflow pulls fixture reports; the emulator is disposable.
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.class=$test_classes" --console=plain --stacktrace --max-workers=2
