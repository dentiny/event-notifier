package dev.remember.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.concurrent.*;

/** Checks real Android SQLite transactions, validation and a non-destructive schema upgrade. */
final class StoreChecks {
    interface Check { void accept(boolean condition, String message); }
    static void run(Context c, Check check) throws Exception {
        String name = "store-checks.sqlite";
        c.deleteDatabase(name);
        try (ReminderStore store = new ReminderStore(c, name)) {
            expectInvalid(store, save("", "2026-01-02T09:00:00", "once"), check);
            expectInvalid(store, save("Past reminder", "2025-12-01T09:00:00", "once"), check);
            expectInvalid(store, save("Date", "2026-02-30T09:00:00", "yearly"), check);
            expectInvalid(store, save("Invalid recurrence", "2026-01-02T09:00:00", "invalid"), check);
            JSONObject yearly = (JSONObject) call(store,save("Birthday", "2020-02-29T09:00:00", "yearly"),"2026-01-01T00:00:00");
            int id = yearly.getInt("id");
            check.accept(yearly.getString("next_at").equals("2026-02-28T09:00:00"), "SQLite stores clamped birthday");
            check.accept(c.getDatabasePath(name).isFile(), "Actual database file exists in app storage");
            call(store,new JSONObject().put("action","toggle").put("id",id).put("enabled",false),"2026-01-02T00:00:00");
            check.accept(call(store,fire(id,"2026-02-28T09:00:00"),"2026-03-01T00:00:00") == JSONObject.NULL, "Paused reminder ignores stale callback");
            JSONObject resumed=(JSONObject)call(store,save("Birthday","2020-02-29T09:00:00","yearly").put("id",id),"2026-03-01T00:00:00");
            check.accept(resumed.getString("next_at").equals("2027-02-28T09:00:00"), "Editor resume skips paused occurrences");
            send(store,id,"2027-02-28T09:00:00","2029-03-01T00:00:00");
            JSONObject edited=(JSONObject)call(store,save("Updated title","2020-02-29T09:00:00","yearly").put("id",id),"2029-03-01T00:00:00");
            check.accept(edited.getString("next_at").equals("2030-02-28T09:00:00"), "Delayed delivery skips history and title edit preserves next date");
            check.accept(call(store,fire(id,"2027-02-28T09:00:00"),"2029-03-01T00:00:00") == JSONObject.NULL, "Changed occurrence rejects duplicate callback");
            JSONObject once=(JSONObject)call(store,save("Concurrent reminder","2026-01-02T09:00:00","once"),"2026-01-01T00:00:00");
            int onceId=once.getInt("id");
            check.accept(call(store,fire(onceId,"2026-01-02T09:00:00"),"2026-01-02T08:59:59") == JSONObject.NULL, "Early callback does not consume event");
            JSONObject annual = (JSONObject)call(store,save("Annual date","2015-02-07T09:00:00","yearly"),"2015-02-07T09:00:01");
            check.accept(annual.getString("recurrence").equals("yearly") && annual.getString("next_at").equals("2016-02-07T09:00:00"), "Yearly enum persists its stable value and same calendar date");
            send(store,annual.getInt("id"),"2016-02-07T09:00:00","2016-02-07T09:00:00");
            JSONArray events=(JSONArray)call(store,new JSONObject().put("action","list"),"2016-02-07T09:00:00");
            JSONObject advanced=null;
            for(int i=0;i<events.length();i++) if(events.getJSONObject(i).getInt("id")==annual.getInt("id")) advanced=events.getJSONObject(i);
            check.accept(advanced != null && advanced.getString("next_at").equals("2017-02-07T09:00:00"), "Yearly delivery advances one calendar year");
            ExecutorService pool=Executors.newFixedThreadPool(2);
            CountDownLatch gate=new CountDownLatch(1);
            try {
                Callable<Boolean> claim=() -> { gate.await(); return call(store,fire(onceId,"2026-01-02T09:00:00"),"2026-01-02T09:01:00") != JSONObject.NULL; };
                Future<Boolean> first=pool.submit(claim), second=pool.submit(claim); gate.countDown();
                check.accept(first.get(5,TimeUnit.SECONDS) != second.get(5,TimeUnit.SECONDS), "Concurrent SQLite transactions consume exactly once");
            } finally { pool.shutdownNow(); }
            call(store,new JSONObject().put("action","delete").put("id",id),"2029-03-01T00:00:00");
            check.accept(call(store,fire(id,"2030-02-28T09:00:00"),"2030-03-01T00:00:00") == JSONObject.NULL, "Deleted reminder rejects callback");
        } finally { c.deleteDatabase(name); }
        migration(c,check);
        deliveryPersistence(c,check);
    }
    private static void migration(Context c, Check check) throws Exception {
        String name="migration-check.sqlite"; c.deleteDatabase(name);
        try {
            try (SQLiteDatabase old=c.openOrCreateDatabase(name,0,null)) {
                old.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, title TEXT NOT NULL, notes TEXT NOT NULL, start_at TEXT NOT NULL, recurrence TEXT NOT NULL, enabled INTEGER NOT NULL, completed INTEGER NOT NULL, next_at TEXT)");
                old.execSQL("INSERT INTO events VALUES (41,?,?,?,?,1,0,?)",new Object[]{"Legacy birthday","Keep this note","2020-02-29T09:00:00","\"yearly\"","2026-02-28T09:00:00"});
                old.setVersion(1);
            }
            try (ReminderStore updated=new ReminderStore(c,name)) {
                JSONObject item=((JSONArray)call(updated,new JSONObject().put("action","list"),"2026-01-01T00:00:00")).getJSONObject(0);
                check.accept(item.getString("recurrence").equals("yearly") && item.getInt("id")==41 && item.getString("notes").equals("Keep this note") && item.getString("next_at").equals("2026-02-28T09:00:00"),"Legacy database upgrade preserves event and date");
                check.accept(updated.getReadableDatabase().getVersion()==ReminderStore.VERSION,"Database schema upgraded to current version");
            }
        } finally { c.deleteDatabase(name); }
    }
    private static void send(ReminderStore store, int id, String expected, String now) throws Exception {
        call(store,fire(id,expected),now);
        call(store,new JSONObject().put("action","ack").put("id",id).put("expected_at",expected),now);
    }
    private static void deliveryPersistence(Context c, Check check) throws Exception {
        String name="delivery-check.sqlite"; c.deleteDatabase(name);
        String due="2026-01-02T09:00:00", now="2026-01-02T09:01:00";
        int id;
        try {
            try(ReminderStore store=new ReminderStore(c,name)) {
                id=((JSONObject)call(store,save("  Keep my title  ",due,"once"),"2026-01-01T00:00:00")).getInt("id");
                check.accept(call(store,fire(id,due),now)!=JSONObject.NULL,"Due occurrence creates a durable delivery");
                check.accept(call(store,fire(id,due),now)==JSONObject.NULL,"Duplicate claim does not enqueue twice");
                JSONObject item=((JSONArray)call(store,new JSONObject().put("action","list"),now)).getJSONObject(0);
                check.accept(!item.getBoolean("completed") && item.getBoolean("enabled"),"Enqueue does not complete a one-time reminder");
                call(store,new JSONObject().put("action","retry").put("id",id).put("expected_at",due).put("retry_at",123456789L),now);
            }
            try(ReminderStore store=new ReminderStore(c,name)) {
                JSONArray pending=(JSONArray)call(store,new JSONObject().put("action","pending"),now);
                check.accept(pending.length()==1 && pending.getJSONObject(0).getLong("retry_at")==123456789L,"Pending delivery and retry time survive database reopening");
                check.accept(pending.getJSONObject(0).getString("title").equals("  Keep my title  "),"User title whitespace is preserved");
                check.accept(call(store,new JSONObject().put("action","ack").put("id",id).put("expected_at","2026-01-02T08:00:00"),now)==JSONObject.NULL,"Stale acknowledgment does not consume pending delivery");
                JSONObject sent=(JSONObject)call(store,new JSONObject().put("action","ack").put("id",id).put("expected_at",due),now);
                check.accept(sent.getBoolean("completed") && !sent.getBoolean("enabled") && sent.isNull("next_at"),"Successful acknowledgment completes once");
                check.accept(((JSONArray)call(store,new JSONObject().put("action","pending"),now)).length()==0,"Acknowledgment removes the queued delivery");
                check.accept(call(store,new JSONObject().put("action","ack").put("id",id).put("expected_at",due),now)==JSONObject.NULL,"Duplicate acknowledgment is harmless");
                int paused=((JSONObject)call(store,save("Pause pending",due,"daily"),"2026-01-01T00:00:00")).getInt("id");
                call(store,fire(paused,due),now);
                call(store,new JSONObject().put("action","toggle").put("id",paused).put("enabled",false),now);
                check.accept(((JSONArray)call(store,new JSONObject().put("action","pending"),now)).length()==0,"Pausing removes a pending notification");
                call(store,new JSONObject().put("action","toggle").put("id",paused).put("enabled",true),"2026-01-02T08:00:00");
                call(store,fire(paused,due),now);
                call(store,save("Rescheduled", "2026-01-03T10:00:00", "daily").put("id",paused),now);
                check.accept(((JSONArray)call(store,new JSONObject().put("action","pending"),now)).length()==0,"Changing the date removes an obsolete pending notification");
                int edited=((JSONObject)call(store,save("Edit pending",due,"daily"),"2026-01-01T00:00:00")).getInt("id");
                call(store,fire(edited,due),now);
                call(store,save("Updated pending title",due,"daily").put("id",edited),now);
                JSONObject unchanged=(JSONObject)call(store,new JSONObject().put("action","toggle").put("id",edited).put("enabled",true),now);
                check.accept(unchanged.getString("next_at").equals(due),"Idempotent enable preserves a pending occurrence");
                pending=(JSONArray)call(store,new JSONObject().put("action","pending"),now);
                check.accept(pending.length()==1 && pending.getJSONObject(0).getString("title").equals("Updated pending title"),"A title edit preserves the delivery and uses current text");
                call(store,new JSONObject().put("action","delete").put("id",edited),now);
                check.accept(((JSONArray)call(store,new JSONObject().put("action","pending"),now)).length()==0,"Deleting a reminder cascades to its pending delivery");
            }
        } finally { c.deleteDatabase(name); }
        String legacy="version-two-check.sqlite"; c.deleteDatabase(legacy);
        try {
            try(SQLiteDatabase db=c.openOrCreateDatabase(legacy,0,null)) {
                db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, title TEXT NOT NULL, notes TEXT NOT NULL, start_at TEXT NOT NULL, recurrence TEXT NOT NULL, enabled INTEGER NOT NULL, completed INTEGER NOT NULL, next_at TEXT)");
                db.execSQL("INSERT INTO events VALUES (7,'Existing reminder','Keep notes','2026-01-02T09:00:00','daily',1,0,'2026-01-02T09:00:00')");
                db.setVersion(2);
            }
            try(ReminderStore store=new ReminderStore(c,legacy)) {
                JSONObject item=((JSONArray)call(store,new JSONObject().put("action","list"),now)).getJSONObject(0);
                check.accept(item.getInt("id")==7 && item.getString("notes").equals("Keep notes") && item.getString("next_at").equals(due),"Version 2 upgrade preserves existing reminder data");
                check.accept(call(store,fire(7,due),now)!=JSONObject.NULL,"Version 2 upgrade creates a working delivery queue");
            }
        } finally { c.deleteDatabase(legacy); }
    }
    private static JSONObject save(String title,String time,String rule) throws Exception {
        return new JSONObject().put("action","save").put("id",JSONObject.NULL).put("title",title).put("notes","").put("start_at",time).put("recurrence",rule).put("enabled",true);
    }
    private static JSONObject fire(int id,String time) throws Exception { return new JSONObject().put("action","fire").put("id",id).put("expected_at",time); }
    private static Object call(ReminderStore store,JSONObject request,String now) throws Exception { return store.execute(request,Core.parse(now)); }
    private static void expectInvalid(ReminderStore store,JSONObject request,Check check) throws Exception {
        try { call(store,request,"2026-01-01T00:00:00"); throw new AssertionError("Invalid data accepted"); }
        catch (IllegalArgumentException expected) { check.accept(true,"Invalid reminder rejected: " + expected.getMessage()); }
    }
    private StoreChecks() {}
}
