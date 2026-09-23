#!/usr/bin/env bash
set -euo pipefail

api=${1:-}
case "$api" in ''|24|35) ;; *) echo "Unsupported Android API: $api" >&2; exit 1 ;; esac
sdk="${RUNNER_TEMP:?}/hnovel-android-sdk"
metadata="$(cd "$(dirname "${BASH_SOURCE[0]}")/../ci-environment/sdk-packages" && pwd)"
mkdir -p "$sdk"

install_archive() {
  local destination="$sdk/$1" url="$2" description="$metadata/$3.xml" temporary
  if [[ -f "$destination/source.properties" ]]; then
    cp "$description" "$destination/package.xml"
    return 0
  fi
  temporary=$(mktemp -d "${RUNNER_TEMP}/android-package.XXXXXX")
  if ! curl --fail --location --silent --show-error --retry 2 --retry-delay 2 \
      --connect-timeout 20 --max-time 300 --output "$temporary/package.zip" "$url"; then
    rm -rf -- "$temporary"
    return 1
  fi
  if ! unzip -q "$temporary/package.zip" -d "$temporary/unpacked"; then
    rm -rf -- "$temporary"
    return 1
  fi
  local directories=("$temporary/unpacked"/*)
  if [[ ${#directories[@]} != 1 || ! -f "${directories[0]}/source.properties" ]]; then
    echo "Unexpected Android package layout: $url" >&2
    rm -rf -- "$temporary"
    return 1
  fi
  mkdir -p "$(dirname "$destination")"
  # SDK ZIPs omit sdkmanager's installed-package metadata. AGP needs this to
  # recognize the exact platform revision without querying Google's repository.
  cp "$description" "${directories[0]}/package.xml"
  # An incomplete existing directory must not turn the install into a nested SDK.
  if ! mv -T -- "${directories[0]}" "$destination"; then
    rm -rf -- "$temporary"
    return 1
  fi
  rm -rf -- "$temporary"
}

# Every URL names an exact published revision. No SDK repository scan or update.
install_archive platforms/android-37.0 https://dl.google.com/android/repository/platform-37.0_r02.zip platform
install_archive build-tools/36.0.0 https://dl.google.com/android/repository/build-tools_r36_linux.zip build-tools
install_archive platform-tools https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip platform-tools
if [[ -n "$api" ]]; then
  install_archive emulator https://dl.google.com/android/repository/emulator-linux_x64-15917651.zip emulator
  case "$api" in 24) revision=27 ;; 35) revision=09 ;; esac
  install_archive "system-images/android-$api/google_apis/x86_64" \
    "https://dl.google.com/android/repository/sys-img/google_apis/x86_64-${api}_r${revision}.zip" "api-$api"
fi

mkdir -p "${RUNNER_TEMP}/android-user" "${RUNNER_TEMP}/android-avd"
cat >> "${GITHUB_ENV:?}" <<EOF
ANDROID_HOME=$sdk
ANDROID_SDK_ROOT=$sdk
ANDROID_USER_HOME=${RUNNER_TEMP}/android-user
ANDROID_AVD_HOME=${RUNNER_TEMP}/android-avd
EOF
printf '%s\n' "$sdk/platform-tools" "$sdk/emulator" >> "${GITHUB_PATH:?}"
