# 记得 · Remember

离线 Android 提醒应用。全部使用原生 Android / Java：SQLiteOpenHelper 负责手机本地 SQLite 数据库，Java 计算重复提醒日期，AlarmManager 负责系统调度与通知。没有服务器，也不需要网络权限。

## 安装到手机

构建好的安装包：`dist/remember-0.1.0.apk`。复制到手机，在文件管理器中打开，按系统提示允许该来源安装应用。支持 Android 8.0（API 26）以上，使用系统自带 SQLite，无 CPU 架构专用库。

1. 新建提醒，填写名称、重复方式、日期、时间和可选备注。
2. 允许「记得」发送通知。
3. 首页点击「允许准时提醒」，在系统设置中允许「闹钟和提醒」。未允许时仍使用普通系统闹钟，但可能延迟。
4. 用一条几分钟后的提醒测试手机的实际表现。部分厂商需在电池设置中允许后台运行。

这是用于直接安装试用的 debug 签名 APK。后续相同签名的 APK 可覆盖安装；换签名需卸载，会清空数据。正式分发应使用自己保管的 release 签名。

## 功能与规则

- 新建、编辑、删除、暂停与恢复提醒；首页区分待提醒、已暂停和已完成。
- 一次性提醒：必须选择未来时间；通知后自动标记为已完成。编辑时间后可再次开启。
- 每天 / 每周 / 每月 / 每年：以首次日期为锚点；每年适合生日和纪念日。
- 月末不足时取当月最后一天，但保留原始日期：1 月 31 日 → 2 月 28 日 → 3 月 31 日。
- 2 月 29 日生日：非闰年 2 月 28 日，闰年仍为 2 月 29 日。
- 使用手机当地时间；改时区、调整时钟、重启和覆盖升级会重新安排提醒。
- 夏令时跳过的本地时间顺延，例如 02:30 → 03:30；重复的时间使用第一次出现。
- 关机期间错过的待提醒，在开机解锁后补发一次；重复事项再安排下一个未来日期，不补发每一段历史周期。
- 暂停的重复事项恢复后从下一个未来日期开始；暂停的一次性事项若已过期，恢复后尽快提醒。
- 同一时间的事项批量处理。SQLite 事务校验下一次时间，过滤重复、已删除、已暂停或已修改的回调。

数据保存在应用私有目录 `databases/reminders.sqlite`，关闭自动备份。卸载会删除数据；首版不含云同步或导入导出。强行停止应用后，Android 不会继续派发闹钟或重启广播，需要重新打开应用。系统休眠策略、厂商后台限制或通知权限仍会影响实际送达，无法承诺所有设备上绝对准时。

## 本地构建（Apple Silicon macOS）

只需这个 Android 项目。要求已有 curl、unzip。其余构建工具放在本项目忽略的 `.toolchain/`，不需要安装 Android Studio。SDK 设置脚本会接受 Android SDK license。

```sh
./scripts/setup-android.sh
./scripts/build-apk.sh
```

构建使用 Java 17、Gradle 8.11.1、Android Gradle Plugin 8.9.2、SDK 35。打包后执行 Android lint 和 APK 签名校验，输出 APK 与 SHA-256 文件到 `dist/`。也可以设置 `JAVA_HOME`、`ANDROID_HOME` 使用现有工具链。设置脚本提供 Apple Silicon macOS 的 Java 下载；其他系统可以配置自己的 Java 和 Android SDK。

## 验证

```sh
./scripts/test-recurrence.sh
# 配好模拟器或 adb 连接的 Android 13+ 设备之后：
./scripts/test-android.sh
```

Java 日期规则测试覆盖闰年、月末、每日、每周与边界时间。Android instrumentation 使用系统 SQLite 和实际应用代码，在模拟器中验证 SQLite、AlarmManager 真正触发、系统通知、重复任务推进、夏令时转换及界面中新建生日、暂停和删除。测试会临时授予通知与准时提醒权限，并清理所建事项。

可选模拟器安装：

```sh
./scripts/setup-emulator.sh
export ANDROID_HOME="$PWD/.toolchain/android-sdk"
export ANDROID_USER_HOME="$PWD/.toolchain/android-user"
export ANDROID_AVD_HOME="$PWD/.toolchain/avd"
"$ANDROID_HOME/emulator/emulator" -avd remember-api35 -no-window -no-audio -no-boot-anim
```

## 代码结构

- `android/app/src/main/java/dev/remember/app/ReminderStore.java`：SQLiteOpenHelper、CRUD、数据库升级和通知事务。
- `android/app/src/main/java/dev/remember/app/Recurrence.java`：纯 Java 重复日期规则。
- `android/app/src/test/java/dev/remember/app/RecurrenceTest.java`：日期规则测试。
- `android/app/src/main/java/dev/remember/app/`：界面、数据库调用、权限、通知和恢复广播。
- `android/app/src/androidTest/`：实际 Android 环境集成测试。
- `scripts/`：依赖安装、APK 构建和设备验证脚本。

通知权限与调度行为依据 Android 官方文档：[通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)、[系统闹钟](https://developer.android.com/develop/background-work/services/alarms)。
