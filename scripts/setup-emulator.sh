#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="$ROOT/.toolchain/jdk/Contents/Home"
export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
export ANDROID_USER_HOME="$ROOT/.toolchain/android-user"
export ANDROID_AVD_HOME="$ROOT/.toolchain/avd"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'emulator' 'system-images;android-35;google_apis;arm64-v8a'
if [ ! -f "$ANDROID_AVD_HOME/remember-api35.ini" ]; then
  printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --name remember-api35 --package 'system-images;android-35;google_apis;arm64-v8a' --device pixel_6
fi
