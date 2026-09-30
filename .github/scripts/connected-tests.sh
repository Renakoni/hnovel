#!/usr/bin/env bash
set -euo pipefail

api_level=$(timeout 15s adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$api_level" == 35 ]]; then
  test_classes="indi.renakoni.nextvol.sourceexecution.NativeBrowserInstrumentedTest,indi.renakoni.nextvol.sourceexecution.NativeBrowserPoolInstrumentedTest,indi.renakoni.nextvol.sourceexecution.NativeBrowserAdmissionCookieInstrumentedTest,indi.renakoni.nextvol.sourceexecution.NativeBrowserStorageInstrumentedTest,indi.renakoni.nextvol.sourceexecution.NativeBrowserRouteInstrumentedTest,indi.renakoni.nextvol.sourceexecution.SourceVerificationInstrumentedTest"
else
  test_classes="indi.renakoni.nextvol.sourceexecution.IsolatedExecutionInstrumentedTest,indi.renakoni.nextvol.sourceexecution.SourceAccountInstrumentedTest,indi.renakoni.nextvol.sourceexecution.SourceBrowserInstrumentedTest,indi.renakoni.nextvol.sourceexecution.SourceVpnInstrumentedTest"
  test_classes+=",indi.renakoni.nextvol.sourceexecution.SourceCompatibilityInstrumentedTest,indi.renakoni.nextvol.sourceexecution.PixivLifecycleInstrumentedTest"
  test_classes+=",indi.renakoni.nextvol.sourceexecution.IsolatedConcurrencyInstrumentedTest"
fi
reader_test_classes="indi.renakoni.nextvol.reader.ReaderPositionInstrumentedTest"
test_classes+="${1:-}"

# API 24 can hang after UTP/ddmlib has streamed all APK bytes into install-write.
# Push installation avoids that path while retaining the same runner and test selection.
# See https://github.com/maplibre/maplibre-compose/issues/1047.
# API 35 uses the same prebuilt APKs without starting a second Gradle invocation.
if [[ "$api_level" == 24 || "$api_level" == 35 ]]; then
  bash .github/scripts/run-prebuilt-tests.sh app/build/outputs/apk/debug \
    app/build/outputs/apk/androidTest/debug app/build/outputs/androidTest-results/connected/debug "$test_classes"
  # Preserve the narrow-screen checks above; reader reflow fixtures also need a wide host.
  original_size=$(timeout 15s adb shell wm size | tr -d '\r' | sed -n 's/^Override size: //p')
  original_density=$(timeout 15s adb shell wm density | tr -d '\r' | sed -n 's/^Override density: //p')
  restore_viewport() {
    timeout 15s adb shell wm size "${original_size:-reset}" || true
    timeout 15s adb shell wm density "${original_density:-reset}" || true
  }
  trap restore_viewport EXIT
  timeout 15s adb shell wm size 2560x1440
  timeout 15s adb shell wm density 160
  reader_size=$(timeout 15s adb shell wm size | tr -d '\r' | sed -n 's/.*size: //p' | tail -n 1)
  if [[ "$reader_size" != 2560x1440 ]]; then
    echo "Reader tests require a 2560x1440 viewport; device reported $reader_size." >&2
    exit 1
  fi
  bash .github/scripts/run-prebuilt-tests.sh app/build/outputs/apk/debug \
    app/build/outputs/apk/androidTest/debug app/build/outputs/androidTest-results/connected/debug/reader "$reader_test_classes"
  exit 0
fi
./gradlew --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$test_classes" --console=plain --stacktrace --max-workers=2
