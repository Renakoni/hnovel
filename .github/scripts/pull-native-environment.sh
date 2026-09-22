#!/usr/bin/env bash
set -euo pipefail

report=app/build/outputs/androidTest-results/connected/native-environment.json
mkdir -p "$(dirname "$report")"
for attempt in {1..3}; do
  if timeout 15s adb wait-for-device &&
    timeout 15s adb pull /sdcard/Android/data/indi.renakoni.nextvol.debug/files/native-environment.json "$report"; then
    exit 0
  fi
  echo "Native environment report pull failed (attempt $attempt/3)." >&2
  if (( attempt < 3 )); then
    timeout 10s adb reconnect offline || true
  fi
done
echo 'Required native environment report could not be collected after reconnecting the device.' >&2
exit 1
