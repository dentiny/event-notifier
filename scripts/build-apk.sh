#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.toolchain"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLS/android-sdk}"
export GRADLE_USER_HOME="$TOOLS/gradle-cache"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
"$TOOLS/gradle-8.11.1/bin/gradle" -p android --no-daemon assembleDebug lintDebug
mkdir -p dist
cp android/app/build/outputs/apk/debug/app-debug.apk dist/remember-0.1.0.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose dist/remember-0.1.0.apk
shasum -a 256 dist/remember-0.1.0.apk > dist/remember-0.1.0.apk.sha256
printf '\nAPK: %s/dist/remember-0.1.0.apk\n' "$ROOT"
