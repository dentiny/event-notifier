package dev.remember.app;
import android.content.*;
public final class RestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            && !Intent.ACTION_TIME_CHANGED.equals(action)
            && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
            && !"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) return;
        PendingResult pending = goAsync();
        Core.WORK.execute(() -> {
            try { Scheduler.rebuild(c.getApplicationContext()); }
            catch (Exception e) { Core.failure(c, e); }
            finally { pending.finish(); }
        });
    }
}
