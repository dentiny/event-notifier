package dev.remember.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.provider.Settings;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import org.json.*;

final class Scheduler {
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
    // A single alarm batches simultaneous events and avoids per-event Doze alarm quotas.
    static void rebuild(Context c) throws Exception {
        channel(c);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent pi = alarm(c);
        alarms.cancel(pi);
        if (!notificationsAllowed(c)) return;
        JSONArray list = Core.list(c);
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            if (e.getBoolean("enabled") && !e.isNull("next_at")) earliest = Math.min(earliest, epoch(e.getString("next_at")));
        }
        if (earliest == Long.MAX_VALUE) return;
        long when = Math.max(earliest, System.currentTimeMillis() + 1000);
        if (exactAllowed(c)) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi); return; }
            catch (SecurityException ignored) { /* Permission can be revoked between check and call. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
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
        channel(c);
        if (!notificationsAllowed(c)) { rebuild(c); return; }
        JSONArray list = Core.list(c);
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            if (!e.getBoolean("enabled") || e.isNull("next_at") || epoch(e.getString("next_at")) > now) continue;
            Object fired = claimDue(c, e, now);
            if (fired == JSONObject.NULL) continue;
            JSONObject item = (JSONObject) fired;
            Intent intent = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(c, item.getInt("id"), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String notes = item.getString("notes");
            String text = notes.isEmpty() ? "Make time for what matters." : notes;
            Notification notification = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(item.getString("title"))
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setColor(0xff236b54).build();
            c.getSystemService(NotificationManager.class).notify(item.getInt("id"), notification);
        }
        rebuild(c);
    }
    static void openNotificationSettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName()));
    }
    private Scheduler() {}
}
