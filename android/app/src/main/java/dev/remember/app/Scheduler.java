package dev.remember.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.provider.Settings;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import org.json.*;
import java.util.HashSet;

final class Scheduler {
    static final long RETRY_DELAY_MS = 60_000;
    static final String FALLBACK_ACTION = "dev.remember.app.FALLBACK_REMINDER";
    interface NotificationSender { void send(Context c, JSONObject item) throws Exception; }
    static final String CHANNEL = "important_reminders";
    static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Important reminders", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Birthdays, anniversaries, and one-time events");
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
    }
    static boolean notificationsAllowed(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
        return nm.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static boolean exactAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    static long epoch(String time) { return LocalDateTime.parse(time, Core.FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, ReminderReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent fallbackAlarm(Context c) {
        Intent intent = new Intent(c, ReminderReceiver.class).setAction(FALLBACK_ACTION);
        return PendingIntent.getBroadcast(c, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    // Batch simultaneous reminders; the inexact backup survives exact-permission revocation.
    static void rebuild(Context c) throws Exception {
        channel(c);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent primary = alarm(c), fallback = fallbackAlarm(c);
        if (!notificationsAllowed(c)) { alarms.cancel(primary); alarms.cancel(fallback); return; }
        JSONArray list = Core.list(c);
        JSONArray pending = (JSONArray) Core.call(c, new JSONObject().put("action", "pending"));
        HashSet<Long> queued = new HashSet<>();
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < pending.length(); i++) {
            JSONObject e = pending.getJSONObject(i);
            queued.add(e.getLong("id"));
            earliest = Math.min(earliest, e.getLong("retry_at"));
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            if (e.getBoolean("enabled") && !e.isNull("next_at") && !queued.contains(e.getLong("id")))
                earliest = Math.min(earliest, epoch(e.getString("next_at")));
        }
        if (earliest == Long.MAX_VALUE) { alarms.cancel(primary); alarms.cancel(fallback); return; }
        long when = Math.max(earliest, System.currentTimeMillis() + 1000);
        // Arm the backup first; a scheduling failure must not remove the previous working alarm.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fallback);
        if (exactAllowed(c)) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, primary); return; }
            catch (SecurityException ignored) { /* Fall back if exact access is unavailable. */ }
        }
        alarms.cancel(primary);
    }
    static Object claimDue(Context c, JSONObject e, long now) throws Exception {
        String expected = e.getString("next_at");
        if (epoch(expected) > now) return JSONObject.NULL;
        String localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).format(Core.FORMAT);
        // During the second DST hour, an already-due first 01:30 may compare after local 01:15.
        // Absolute time above establishes eligibility; preserve the store wall-time guard and avoid rearming every second.
        if (expected.compareTo(localNow) > 0) localNow = expected;
        return Core.callAt(c, new JSONObject().put("action", "fire").put("id", e.getLong("id")).put("expected_at", expected), localNow);
    }
    static void deliver(Context c) throws Exception {
        deliver(c, System.currentTimeMillis(), Scheduler::postNotification);
    }
    static void deliver(Context c, long now, NotificationSender sender) throws Exception {
        Exception failure = null;
        try {
            channel(c);
            if (!notificationsAllowed(c)) return;
            JSONArray list = Core.list(c);
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.getJSONObject(i);
                if (e.getBoolean("enabled") && !e.isNull("next_at") && epoch(e.getString("next_at")) <= now) claimDue(c, e, now);
            }
            JSONArray pending = (JSONArray) Core.call(c, new JSONObject().put("action", "pending"));
            for (int i = 0; i < pending.length(); i++) {
                JSONObject item = pending.getJSONObject(i);
                if (item.getLong("retry_at") > now) continue;
                String expected = item.getString("next_at");
                try {
                    if (!notificationsAllowed(c)) break;
                    // Persist the next attempt before submission so a process exit cannot lose the retry.
                    Core.call(c, new JSONObject().put("action", "retry").put("id", item.getLong("id"))
                        .put("expected_at", expected).put("retry_at", now + RETRY_DELAY_MS));
                    sender.send(c, item);
                    // A permission change during submission leaves the delivery available for retry.
                    if (!notificationsAllowed(c)) break;
                    String localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).format(Core.FORMAT);
                    if (expected.compareTo(localNow) > 0) localNow = expected;
                    Core.callAt(c, new JSONObject().put("action", "ack").put("id", item.getLong("id")).put("expected_at", expected), localNow);
                } catch (Exception e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
        } finally { rebuild(c); }
        if (failure != null) throw failure;
        c.getSharedPreferences("status", 0).edit().remove("error").apply();
    }
    static void postNotification(Context c, JSONObject item) throws Exception {
        Intent intent = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(c, item.getInt("id"), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String notes = item.getString("notes");
        String text = notes.isEmpty() ? "Make time for what matters." : notes;
        Notification notification = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(item.getString("title"))
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(item.optLong("retry_at", 0) > 0).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setColor(0xff236b54).build();
        c.getSystemService(NotificationManager.class).notify(item.getInt("id"), notification);
    }
    static void openNotificationSettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName()));
    }
    private Scheduler() {}
}
