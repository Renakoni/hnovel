#!/usr/bin/env bash
set -euo pipefail

app_apk_dir=$1
test_apk_dir=$2
results=$3
test_classes=$4
shift 4

app_apks=("$app_apk_dir"/*.apk)
test_apks=("$test_apk_dir"/*.apk)
if [[ ${#app_apks[@]} != 1 || ${#test_apks[@]} != 1 || ! -f "${app_apks[0]}" || ! -f "${test_apks[0]}" ]]; then
  echo "Expected one app APK and one test APK from the preceding build" >&2
  exit 1
fi
timeout 180s adb install --no-streaming -r -t "${app_apks[0]}"
timeout 180s adb install --no-streaming -r -t "${test_apks[0]}"
test_package=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["applicationId"])' "$test_apk_dir/output-metadata.json")
mkdir -p "$results"
adb shell am instrument -w -r -e class "$test_classes" "$@" \
  "$test_package/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$results/instrumentation.txt"
# am instrument may exit zero after assertion failures, crashes, or an empty selection.
grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$results/instrumentation.txt"
grep -qx 'INSTRUMENTATION_STATUS_CODE: 0' "$results/instrumentation.txt"
grep -qx 'INSTRUMENTATION_CODE: -1' "$results/instrumentation.txt"
if grep -Eq '^INSTRUMENTATION_(FAILED|ABORTED)|^FAILURES!!!|^INSTRUMENTATION_STATUS_CODE: -[12]$' "$results/instrumentation.txt"; then
  exit 1
fi
