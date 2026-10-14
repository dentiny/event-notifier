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

permission_check() {
  "$ADB" shell am instrument -w -e mode "$1" dev.remember.app.test/dev.remember.app.SmokeInstrumentation | tee "$ROOT/.toolchain/permission-$1.log"
  rg -q '^PASS: [0-9]+ permission assertions' "$ROOT/.toolchain/permission-$1.log"
}
backup_present() {
  "$ADB" shell dumpsys alarm > "$ROOT/.toolchain/recovery-alarms.log"
  awk '/RTC_WAKEUP #[0-9]+: Alarm.*dev.remember.app/ { getline; if ($0 ~ /FALLBACK_REMINDER/) found=1 } END { exit !found }' "$ROOT/.toolchain/recovery-alarms.log"
}
permission_check seed-recovery
"$ADB" shell pm revoke dev.remember.app android.permission.POST_NOTIFICATIONS
permission_check check-denied
"$ADB" shell pm grant dev.remember.app android.permission.POST_NOTIFICATIONS
permission_check rearm
"$ADB" shell appops set dev.remember.app SCHEDULE_EXACT_ALARM ignore
backup_present
permission_check check-recovery
if [[ "${TEST_REBOOT:-0}" == 1 ]]; then
  [[ "${ANDROID_SERIAL:-}" == emulator-* ]] || { printf 'Reboot checks require an explicit emulator ANDROID_SERIAL.\n' >&2; exit 1; }
  "$ADB" reboot
  "$ADB" wait-for-device
  for attempt in {1..60}; do
    [[ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break
    sleep 1
  done
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell input keyevent KEYCODE_MENU
  restored=0
  for attempt in {1..20}; do
    if backup_present; then restored=1; break; fi
    sleep 1
  done
  [[ "$restored" == 1 ]]
  permission_check check-reboot
fi
"$ADB" shell appops set dev.remember.app SCHEDULE_EXACT_ALARM allow
permission_check cleanup-recovery
