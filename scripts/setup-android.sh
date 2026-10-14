#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.toolchain"
mkdir -p "$TOOLS/downloads" "$TOOLS/android-sdk/cmdline-tools"
fetch() { if [ ! -f "$2" ]; then curl -fL --retry 3 "$1" -o "$2.tmp"; mv "$2.tmp" "$2"; fi; }
if [ ! -d "$TOOLS/jdk" ]; then
  fetch 'https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse' "$TOOLS/downloads/jdk.tar.gz"
  mkdir -p "$TOOLS/jdk"
  tar -xzf "$TOOLS/downloads/jdk.tar.gz" -C "$TOOLS/jdk" --strip-components=1
fi
export JAVA_HOME="$TOOLS/jdk/Contents/Home"
export ANDROID_HOME="$TOOLS/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
if [ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  fetch https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip "$TOOLS/downloads/cmdline-tools.zip"
  unzip -q "$TOOLS/downloads/cmdline-tools.zip" -d "$TOOLS/downloads/sdk-unpack"
  mv "$TOOLS/downloads/sdk-unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
if [ ! -d "$TOOLS/gradle-8.11.1" ]; then
  fetch https://services.gradle.org/distributions/gradle-8.11.1-bin.zip "$TOOLS/downloads/gradle.zip"
  unzip -q "$TOOLS/downloads/gradle.zip" -d "$TOOLS"
fi
# The caller accepts Google's SDK licenses by running this setup script.
set +o pipefail
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
set -o pipefail
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
printf '\nAndroid toolchain ready in %s\n' "$TOOLS"
