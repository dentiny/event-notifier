package dev.remember.app;
import android.content.*;
public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        PendingResult pending = goAsync();
        Core.WORK.execute(() -> {
            try { Scheduler.deliver(c.getApplicationContext()); }
            catch (Exception e) { Core.failure(c, e); }
            finally { pending.finish(); }
        });
    }
}
