package dev.remember.app;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.time.*;

/** Host-driven checks run across permission revocation and an optional emulator reboot. */
final class PermissionChecks {
    static void run(Instrumentation test,String mode) {
        Context c=test.getTargetContext(); Bundle result=new Bundle();
        int assertions=0;
        try {
            if(mode.equals("seed-recovery")) {
                String due=LocalDateTime.now().plusDays(1).withNano(0).format(Core.FORMAT);
                JSONObject saved=(JSONObject)Core.call(c,new JSONObject().put("action","save").put("id",JSONObject.NULL).put("title","Permission recovery check").put("notes","").put("start_at",due).put("recurrence","once").put("enabled",true));
                c.getSharedPreferences("permission-check",0).edit().putInt("id",saved.getInt("id")).putString("due",due).commit();
                Scheduler.rebuild(c);
                if(!Scheduler.exactAllowed(c) || !Scheduler.notificationsAllowed(c)) throw new AssertionError("Seed requires notification and exact access");
                assertions++;
            } else {
                int id=c.getSharedPreferences("permission-check",0).getInt("id",-1);
                JSONObject event=null;
                JSONArray list=Core.list(c);
                for(int i=0;i<list.length();i++) if(list.getJSONObject(i).getInt("id")==id) event=list.getJSONObject(i);
                if(event==null) throw new AssertionError("Recovery fixture missing");
                if(mode.equals("check-denied")) {
                    if(Scheduler.notificationsAllowed(c)) throw new AssertionError("Notification denial not applied");
                    assertions++;
                    long due=Scheduler.epoch(event.getString("next_at"));
                    Scheduler.deliver(c,due,(context,item) -> { throw new AssertionError("Notification attempted with permission denied"); });
                    JSONArray after=Core.list(c);
                    for(int i=0;i<after.length();i++) if(after.getJSONObject(i).getInt("id")==id) event=after.getJSONObject(i);
                    if(event.getBoolean("completed") || !event.getBoolean("enabled")) throw new AssertionError("Denial consumed reminder");
                    assertions++;
                    if(((JSONArray)Core.call(c,new JSONObject().put("action","pending"))).length()!=0) throw new AssertionError("Denial enqueued a notification");
                    assertions++;
                } else if(mode.equals("rearm")) {
                    Scheduler.rebuild(c);
                    if(!Scheduler.notificationsAllowed(c)) throw new AssertionError("Notification access not restored");
                    assertions++;
                } else if(mode.equals("check-recovery") || mode.equals("check-reboot")) {
                    if(mode.equals("check-recovery")) {
                        if(Scheduler.exactAllowed(c)) throw new AssertionError("Exact access still enabled");
                        assertions++;
                    }
                    if(!event.getBoolean("enabled") || event.getBoolean("completed")) throw new AssertionError("Revocation or reboot consumed reminder");
                    assertions++;
                    if(!event.getString("next_at").equals(c.getSharedPreferences("permission-check",0).getString("due",""))) throw new AssertionError("Recovery changed the date");
                    assertions++;
                    // Do not rebuild: the host verifies the surviving or restored alarm before launching this check.
                } else if(mode.equals("cleanup-recovery")) {
                    Core.call(c,new JSONObject().put("action","delete").put("id",id));
                    c.getSharedPreferences("permission-check",0).edit().clear().commit(); Scheduler.rebuild(c);
                    assertions++;
                } else throw new IllegalArgumentException("Unknown permission test mode: "+mode);
            }
            result.putString("stream","\nPASS: "+assertions+" permission assertions ("+mode+")\n");
            test.finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("stream","\nFAIL: "+android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED,result);
        }
    }
    private PermissionChecks() {}
}
