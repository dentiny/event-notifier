package dev.remember.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.lang.reflect.Field;
import java.util.ArrayList;

/** Exercises real notification failures, retry scheduling, editor limits, and list refresh. */
final class ReliabilityChecks {
    static void run(Instrumentation test, StoreChecks.Check check) throws Exception {
        Context c=test.getTargetContext();
        ArrayList<Integer> created=new ArrayList<>();
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        try {
            String due=LocalDateTime.now().plusDays(1).withNano(0).format(Core.FORMAT);
            long now=Scheduler.epoch(due);
            int failing=save(c,created,"Retry this reminder",due);
            int healthy=save(c,created,"Independent reminder",due);
            try {
                Scheduler.deliver(c,now,(context,item) -> {
                    if(item.getInt("id")==failing) throw new IllegalStateException("Injected notification failure");
                    Scheduler.postNotification(context,item);
                });
                throw new AssertionError("Notification failure was swallowed");
            } catch(IllegalStateException expected) {
                check.accept(expected.getMessage().equals("Injected notification failure"),"Notification submission failure is reported");
            }
            check.accept(!find(c,failing).getBoolean("completed") && find(c,failing).getBoolean("enabled"),"Failed notification stays active and incomplete");
            check.accept(find(c,healthy).getBoolean("completed") && visible(nm,healthy),"One failed notification does not prevent other reminders from sending");
            check.accept(!onlyAlertOnce(nm,healthy),"Each new occurrence can alert even if an older notification remains");
            JSONArray pending=(JSONArray)Core.call(c,new JSONObject().put("action","pending"));
            check.accept(pending.length()==1 && pending.getJSONObject(0).getLong("retry_at")==now+Scheduler.RETRY_DELAY_MS,"Retry deadline is stored in SQLite");
            int[] earlyAttempts={0};
            Scheduler.deliver(c,now+Scheduler.RETRY_DELAY_MS-1,(context,item) -> earlyAttempts[0]++);
            check.accept(earlyAttempts[0]==0 && !find(c,failing).getBoolean("completed"),"Retry waits until its deadline");
            Scheduler.deliver(c,now+Scheduler.RETRY_DELAY_MS,Scheduler::postNotification);
            check.accept(find(c,failing).getBoolean("completed") && visible(nm,failing),"Retry posts the real notification and completes once");
            check.accept(onlyAlertOnce(nm,failing),"Retry updates suppress repeated alerts for the same occurrence");
            check.accept(((JSONArray)Core.call(c,new JSONObject().put("action","pending"))).length()==0,"Successful retry drains the queue");
            int interrupted=save(c,created,"Submission interrupted",due);
            try {
                Scheduler.deliver(c,now,(context,item) -> { Scheduler.postNotification(context,item); throw new IllegalStateException("Interrupted before acknowledgment"); });
            } catch(IllegalStateException expected) { }
            check.accept(visible(nm,interrupted) && !find(c,interrupted).getBoolean("completed"),"Interruption after posting keeps a recoverable delivery");
            Scheduler.deliver(c,now+Scheduler.RETRY_DELAY_MS,Scheduler::postNotification);
            int count=0;
            for(android.service.notification.StatusBarNotification notification:nm.getActiveNotifications()) if(notification.getId()==interrupted) count++;
            check.accept(count==1 && find(c,interrupted).getBoolean("completed"),"Retry uses the same notification ID without creating duplicate entries");
            unicode(test,check);
            scroll(test,c,created,check,due);
        } finally {
            for(int id:created) { Core.call(c,new JSONObject().put("action","delete").put("id",id)); nm.cancel(id); }
            Scheduler.rebuild(c);
        }
    }
    private static void unicode(Instrumentation test, StoreChecks.Check check) {
        test.runOnMainSync(() -> {
            EditText title=new EditText(test.getTargetContext()); title.setFilters(new InputFilter[]{new CodePointLengthFilter(120)});
            String emojis="🎂".repeat(70); title.setText(emojis);
            check.accept(title.getText().toString().equals(emojis),"Editor preserves a 70-emoji title");
            EditText notes=new EditText(test.getTargetContext()); notes.setFilters(new InputFilter[]{new CodePointLengthFilter(2000)});
            String longNote="🎂".repeat(1500); notes.setText(longNote);
            check.accept(notes.getText().toString().equals(longNote),"Editor preserves a 1500-emoji note");
            title.setText("🎂".repeat(120)); title.append("🎂");
            check.accept(title.getText().toString().codePointCount(0,title.length())==120,"Code-point limit blocks additional input");
            title.getText().replace(0,2,"é");
            check.accept(title.getText().toString().startsWith("é") && title.getText().toString().codePointCount(0,title.length())==120,"Replacement counts removed code points correctly");
            CodePointLengthFilter filter=new CodePointLengthFilter(1);
            SpannableString styled=new SpannableString("🎂é"); styled.setSpan(new StyleSpan(Typeface.BOLD),0,2,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            CharSequence clipped=filter.filter(styled,0,styled.length(),new SpannableString(""),0,0);
            check.accept(clipped.toString().equals("🎂") && clipped instanceof Spanned && ((Spanned)clipped).getSpans(0,2,StyleSpan.class).length==1,"Truncation keeps surrogate pairs and styling intact");
        });
    }
    private static void scroll(Instrumentation test,Context c,ArrayList<Integer> created,StoreChecks.Check check,String due) throws Exception {
        for(int i=0;i<8;i++) save(c,created,"Scroll sample "+i,due);
        Activity activity=test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Field field=MainActivity.class.getDeclaredField("reminderScroll"); field.setAccessible(true);
        Core.WORK.submit(() -> {}).get(); test.waitForIdleSync();
        int[] before={0}, after={0};
        long deadline=SystemClock.elapsedRealtime()+5000;
        do {
            test.runOnMainSync(() -> {
                try { ScrollView view=(ScrollView)field.get(activity); view.fullScroll(View.FOCUS_DOWN); before[0]=view.getScrollY(); }
                catch(Exception e) { throw new RuntimeException(e); }
            });
            if(before[0]>0) break;
            SystemClock.sleep(50);
        } while(SystemClock.elapsedRealtime()<deadline);
        check.accept(before[0]>0,"Populated reminder list can scroll");
        java.lang.reflect.Method load=MainActivity.class.getDeclaredMethod("load"); load.setAccessible(true);
        test.runOnMainSync(() -> { try { load.invoke(activity); } catch(Exception e) { throw new RuntimeException(e); } });
        Core.WORK.submit(() -> {}).get(); test.waitForIdleSync();
        deadline=SystemClock.elapsedRealtime()+5000;
        do {
            test.runOnMainSync(() -> { try { after[0]=((ScrollView)field.get(activity)).getScrollY(); } catch(Exception e) { throw new RuntimeException(e); } });
            if(after[0]==before[0]) break;
            SystemClock.sleep(50);
        } while(SystemClock.elapsedRealtime()<deadline);
        check.accept(after[0]==before[0],"Refreshing reminders preserves the list scroll position");
        test.runOnMainSync(activity::finish);
    }
    private static int save(Context c,ArrayList<Integer> ids,String title,String due) throws Exception {
        JSONObject item=(JSONObject)Core.call(c,new JSONObject().put("action","save").put("id",JSONObject.NULL).put("title",title).put("notes","").put("start_at",due).put("recurrence","once").put("enabled",true));
        int id=item.getInt("id"); ids.add(id); return id;
    }
    private static JSONObject find(Context c,int id) throws Exception {
        JSONArray list=Core.list(c);
        for(int i=0;i<list.length();i++) if(list.getJSONObject(i).getInt("id")==id) return list.getJSONObject(i);
        throw new AssertionError("Reminder missing: "+id);
    }
    private static boolean onlyAlertOnce(NotificationManager nm,int id) {
        for(android.service.notification.StatusBarNotification item:nm.getActiveNotifications())
            if(item.getId()==id) return (item.getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE)!=0;
        throw new AssertionError("Notification missing: "+id);
    }
    private static boolean visible(NotificationManager nm,int id) {
        for(android.service.notification.StatusBarNotification item:nm.getActiveNotifications()) if(item.getId()==id) return true;
        return false;
    }
    private ReliabilityChecks() {}
}
