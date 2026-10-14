# Developing Remember

Remember is a native Java Android app. SQLiteOpenHelper stores reminders on the device, calendar calculations use java.time, and AlarmManager schedules system notifications. There is no Rust, JNI, server, or internet permission.

## Build on Apple Silicon macOS

This repository is self-contained. The setup script requires curl and unzip and stores Java, the Android SDK, and Gradle in the ignored `.toolchain/` directory. Android Studio is optional. Running setup accepts the Android SDK licenses.

```sh
./scripts/setup-android.sh
./scripts/build-apk.sh
```

The build uses Java 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, and SDK 35. It runs Android lint and verifies the APK signature, then writes the APK and its SHA-256 checksum to `dist/`. Set `JAVA_HOME` and `ANDROID_HOME` to use existing installations. The setup script downloads Java for Apple Silicon macOS; other platforms can supply their own Java and Android SDK.

## Verify

```sh
./scripts/test-recurrence.sh
# With an Android 13+ emulator or device connected through adb:
./scripts/test-android.sh
```

Java recurrence checks cover leap years, month-end dates, daily and weekly intervals, and time boundaries. Android instrumentation checks real SQLite persistence, CRUD, concurrent transactions, database upgrades, alarm delivery, system notifications, daylight-saving transitions, and UI creation, pause, and deletion. Tests grant notification and exact-alarm permissions and clean up the reminders they create.

Optional emulator setup:

```sh
./scripts/setup-emulator.sh
export ANDROID_HOME="$PWD/.toolchain/android-sdk"
export ANDROID_USER_HOME="$PWD/.toolchain/android-user"
export ANDROID_AVD_HOME="$PWD/.toolchain/avd"
"$ANDROID_HOME/emulator/emulator" -avd remember-api35 -no-window -no-audio -no-boot-anim
```

## APK signing

The current preview APK uses debug signing for direct installation and testing. Updates with the same signing key preserve app data. A different key requires uninstalling, which deletes the database. Production distribution requires a release signing key.

## Source layout

- `android/app/src/main/java/dev/remember/app/ReminderStore.java`: SQLiteOpenHelper, CRUD, schema upgrades, and delivery transactions.
- `android/app/src/main/java/dev/remember/app/Recurrence.java`: Typed recurrence enum and calendar rules. SQLite and JSON retain stable lowercase values (`once`, `daily`, `weekly`, `monthly`, `yearly`); enum ordinals are never persisted.
- `android/app/src/test/java/dev/remember/app/RecurrenceTest.java`: Calendar-rule checks.
- `android/app/src/main/java/dev/remember/app/`: UI, database access, permissions, alarms, notifications, and restore receivers.
- `android/app/src/androidTest/`: Tests in a real Android environment.
- `scripts/`: Toolchain setup, APK builds, and verification.

Platform behavior follows the Android documentation for [notification permissions](https://developer.android.com/develop/ui/views/notifications/notification-permission) and [alarms](https://developer.android.com/develop/background-work/services/alarms).

## Scheduling and storage

- Create, edit, delete, pause, and resume reminders. View upcoming, paused, and completed items.
- One-time reminders must be scheduled in the future and become completed after delivery. Edit the time to enable them again.
- Daily, weekly, monthly, and yearly recurrence use the original date as their anchor. Yearly recurrence keeps the same month, day, and local time: February 7, 2015 → February 7, 2016. It works for birthdays and anniversaries.
- Month-end dates clamp to the last day of shorter months while retaining the original anchor: January 31 → February 28 → March 31.
- February 29 birthdays use February 28 in non-leap years and February 29 in leap years.
- Reminders follow the phone's local time. Reboot, app updates, clock changes, and time-zone changes reschedule pending reminders.
- Missing daylight-saving times move forward, such as 02:30 → 03:30. Repeated local times use the first occurrence.
- Overdue reminders catch up once after the phone restarts and unlocks. Recurring reminders then advance to the next future date rather than delivering every missed occurrence.
- Resuming a paused recurring reminder selects its next future date. Resuming an overdue one-time reminder delivers it as soon as possible.
- A single alarm batches simultaneous reminders. SQLite transactions reject duplicate, deleted, paused, or outdated callbacks.

The database is stored in the app's private `databases/reminders.sqlite` file. Automatic backup is disabled. Uninstalling deletes the data; cloud sync and import/export are not included. After force-stopping the app, reopen it to restore scheduling. System sleep policies, device-specific background restrictions, and notification permissions can affect delivery.

