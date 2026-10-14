package dev.remember.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.time.*;
import java.util.*;

/** Runs against the real Android SQLite file, AlarmManager and notification system. */
public final class SmokeInstrumentation extends Instrumentation {
    private int assertions;
    private final ArrayList<Integer> created = new ArrayList<>();
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context c = getTargetContext();
        int resultCode = Activity.RESULT_OK;
        try {
            StoreChecks.run(c, this::check);
            Scheduler.channel(c);
            check(Scheduler.notificationsAllowed(c), "Grant POST_NOTIFICATIONS before running");
            String due = LocalDateTime.now().plusSeconds(4).withNano(0).format(Core.FORMAT);
            JSONObject once = save(c, "SQLite test 🎂", "Unicode note · café", due, "once");
            JSONObject repeat = save(c, "Daily plan", "Recurring reminder test", due, "daily");
            check(once.getString("title").equals("SQLite test 🎂"), "UTF-8 title round trip");
            check(once.getString("notes").contains("café"), "UTF-8 notes round trip");
            check(find(c, once.getInt("id")).getString("next_at").equals(due), "DB reopen persistence");
            Scheduler.rebuild(c);
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (SystemClock.elapsedRealtime() < deadline && !find(c, once.getInt("id")).getBoolean("completed")) SystemClock.sleep(150);
            JSONObject firedOnce = find(c, once.getInt("id"));
            JSONObject firedRepeat = find(c, repeat.getInt("id"));
            check(firedOnce.getBoolean("completed") && !firedOnce.getBoolean("enabled"), "Actual alarm completes one-shot");
            check(firedRepeat.getBoolean("enabled") && !firedRepeat.getString("next_at").equals(due), "Actual alarm advances repeat");
            SystemClock.sleep(250);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            boolean hasOnce = false, hasRepeat = false;
            for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications()) {
                if (n.getId() == once.getInt("id")) hasOnce = true;
                if (n.getId() == repeat.getInt("id")) hasRepeat = true;
            }
            check(hasOnce && hasRepeat, "Both real system notifications posted");
            check(Core.call(c, new JSONObject().put("action","fire").put("id",once.getInt("id")).put("expected_at",due)) == JSONObject.NULL, "Duplicate callback suppressed");
            Core.call(c, new JSONObject().put("action","toggle").put("id",repeat.getInt("id")).put("enabled",false));
            check(!find(c,repeat.getInt("id")).getBoolean("enabled"), "Pause persists");
            Core.call(c, new JSONObject().put("action","toggle").put("id",repeat.getInt("id")).put("enabled",true));
            check(find(c,repeat.getInt("id")).getBoolean("enabled"), "Resume persists");
            String next = find(c, repeat.getInt("id")).getString("next_at");
            Core.call(c, new JSONObject().put("action","save").put("id",repeat.getInt("id")).put("title","Updated plan").put("notes","").put("start_at",due).put("recurrence","daily").put("enabled",true));
            check(find(c,repeat.getInt("id")).getString("next_at").equals(next), "Title edit preserves next occurrence");
            TimeZone old = TimeZone.getDefault();
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
                long gap = Scheduler.epoch("2026-03-08T02:30:00");
                check(Instant.ofEpochMilli(gap).atZone(ZoneId.systemDefault()).getHour() == 3, "DST missing hour shifts forward");
                long overlap = Scheduler.epoch("2026-11-01T01:30:00");
                check(Instant.ofEpochMilli(overlap).equals(Instant.parse("2026-11-01T08:30:00Z")), "DST overlap uses first occurrence");
                JSONObject overlapEvent = (JSONObject) Core.callAt(c,new JSONObject().put("action","save").put("id",JSONObject.NULL).put("title","DST catch-up").put("notes","").put("start_at","2026-11-01T01:30:00").put("recurrence","once").put("enabled",true),"2026-10-31T00:00:00");
                created.add(overlapEvent.getInt("id"));
                Object overlapClaim = Scheduler.claimDue(c,overlapEvent,Instant.parse("2026-11-01T09:15:00Z").toEpochMilli());
                check(overlapClaim != JSONObject.NULL && find(c,overlapEvent.getInt("id")).getBoolean("completed"), "Overdue first DST hour delivers during repeated hour");
            } finally { TimeZone.setDefault(old); }
            Activity activity = startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync(); check(activity != null && uiWait("+  New reminder") != null,"MainActivity launches");
            uiClick("+  New reminder");
            AccessibilityNodeInfo input = uiWait("e.g. Alex's birthday");
            check(input != null && input.isEditable(), "New reminder form opens");
            Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"UI test birthday");
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text)) throw new AssertionError("Title input failed");
            uiClick("One-time"); uiClick("Yearly · Birthday / anniversary"); uiClick("Save"); uiWait("UI test birthday");
            JSONObject uiEvent = null;
            for (int i = 0; i < Core.list(c).length(); i++) {
                JSONObject entry = Core.list(c).getJSONObject(i);
                if (entry.getString("title").equals("UI test birthday")) uiEvent = entry;
            }
            check(uiEvent != null && uiEvent.getString("recurrence").equals("yearly"), "UI saves annual reminder into Android SQLite database");
            int uiId = uiEvent.getInt("id"); created.add(uiId);
            uiClick("Pause"); uiWait("Paused 1");
            check(!find(c,uiId).getBoolean("enabled"), "UI pause persists");
            uiClick("Paused 1"); uiWait("UI test birthday"); uiClick("Edit"); uiClick("Delete"); uiWait("Delete this reminder?"); uiClick("Delete");
            long deleteDeadline = SystemClock.elapsedRealtime() + 5000;
            while (contains(c,uiId) && SystemClock.elapsedRealtime() < deleteDeadline) SystemClock.sleep(100);
            check(!contains(c,uiId), "UI confirmed delete persists");
            result.putString("stream", "\nPASS: " + assertions + " Android assertions (SQLite, real alarm, notifications, DST, UI)\n");
        } catch (Throwable e) {
            android.util.Log.e("RememberSmoke", "Smoke test failed",e);
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(e));
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            for (int id : created) {
                try { Core.call(c,new JSONObject().put("action","delete").put("id",id)); c.getSystemService(NotificationManager.class).cancel(id); } catch (Exception ignored) { }
            }
            try { Scheduler.rebuild(c); } catch (Exception ignored) { }
        }
        finish(resultCode,result);
    }
    private AccessibilityNodeInfo locate(AccessibilityNodeInfo root, String text) {
        if (root == null) return null;
        if (text.equalsIgnoreCase(root.getText() == null ? "" : root.getText().toString()) || text.equalsIgnoreCase(root.getHintText() == null ? "" : root.getHintText().toString())) return root;
        for (int i=0;i<root.getChildCount();i++) { AccessibilityNodeInfo child=locate(root.getChild(i),text); if(child!=null) return child; }
        return null;
    }
    private AccessibilityNodeInfo uiWait(String text) {
        long deadline=SystemClock.elapsedRealtime()+5000;
        do {
            AccessibilityNodeInfo node=locate(getUiAutomation().getRootInActiveWindow(),text);
            if(node!=null) return node;
            SystemClock.sleep(100);
        } while(SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError("UI element missing: " + text);
    }
    private void uiClick(String text) {
        AccessibilityNodeInfo node=uiWait(text);
        while(!node.isClickable() && node.getParent()!=null) node=node.getParent();
        if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("UI click failed: " + text);
        waitForIdleSync();
    }
    private boolean contains(Context c,int id) throws Exception {
        JSONArray entries=Core.list(c); for(int i=0;i<entries.length();i++) if(entries.getJSONObject(i).getInt("id")==id) return true; return false;
    }
    private JSONObject save(Context c, String title, String notes, String time, String rule) throws Exception {
        JSONObject e = (JSONObject)Core.call(c,new JSONObject().put("action","save").put("id",JSONObject.NULL).put("title",title).put("notes",notes).put("start_at",time).put("recurrence",rule).put("enabled",true));
        created.add(e.getInt("id")); return e;
    }
    private JSONObject find(Context c, int id) throws Exception {
        JSONArray list=Core.list(c); for (int i=0;i<list.length();i++) if(list.getJSONObject(i).getInt("id")==id) return list.getJSONObject(i);
        throw new AssertionError("Missing event " + id);
    }
}
