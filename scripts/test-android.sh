#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$ROOT/.toolchain/android-sdk}"
export GRADLE_USER_HOME="$ROOT/.toolchain/gradle-cache"
ADB="$ANDROID_HOME/platform-tools/adb"
"$ROOT/.toolchain/gradle-8.11.1/bin/gradle" -p "$ROOT/android" --no-daemon assembleDebug assembleDebugAndroidTest lintDebug
"$ADB" install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$ADB" shell pm grant dev.remember.app android.permission.POST_NOTIFICATIONS
"$ADB" shell appops set dev.remember.app SCHEDULE_EXACT_ALARM allow
"$ADB" shell am instrument -w dev.remember.app.test/dev.remember.app.SmokeInstrumentation | tee "$ROOT/.toolchain/android-test.log"
rg -q '^PASS: [0-9]+ Android assertions' "$ROOT/.toolchain/android-test.log"
