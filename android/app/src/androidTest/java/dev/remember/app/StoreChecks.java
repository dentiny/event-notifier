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
            expectInvalid(store, save("过去", "2025-12-01T09:00:00", "once"), check);
            expectInvalid(store, save("日期", "2026-02-30T09:00:00", "yearly"), check);
            JSONObject yearly = (JSONObject) call(store,save("生日", "2020-02-29T09:00:00", "yearly"),"2026-01-01T00:00:00");
            int id = yearly.getInt("id");
            check.accept(yearly.getString("next_at").equals("2026-02-28T09:00:00"), "SQLite stores clamped birthday");
            check.accept(c.getDatabasePath(name).isFile(), "Actual database file exists in app storage");
            call(store,new JSONObject().put("action","toggle").put("id",id).put("enabled",false),"2026-01-02T00:00:00");
            check.accept(call(store,fire(id,"2026-02-28T09:00:00"),"2026-03-01T00:00:00") == JSONObject.NULL, "Paused reminder ignores stale callback");
            JSONObject resumed=(JSONObject)call(store,save("生日","2020-02-29T09:00:00","yearly").put("id",id),"2026-03-01T00:00:00");
            check.accept(resumed.getString("next_at").equals("2027-02-28T09:00:00"), "Editor resume skips paused occurrences");
            call(store,fire(id,"2027-02-28T09:00:00"),"2029-03-01T00:00:00");
            JSONObject edited=(JSONObject)call(store,save("新标题","2020-02-29T09:00:00","yearly").put("id",id),"2029-03-01T00:00:00");
            check.accept(edited.getString("next_at").equals("2030-02-28T09:00:00"), "Delayed delivery skips history and title edit preserves next date");
            check.accept(call(store,fire(id,"2027-02-28T09:00:00"),"2029-03-01T00:00:00") == JSONObject.NULL, "Changed occurrence rejects duplicate callback");
            JSONObject once=(JSONObject)call(store,save("并发","2026-01-02T09:00:00","once"),"2026-01-01T00:00:00");
            int onceId=once.getInt("id");
            check.accept(call(store,fire(onceId,"2026-01-02T09:00:00"),"2026-01-02T08:59:59") == JSONObject.NULL, "Early callback does not consume event");
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
    }
    private static void migration(Context c, Check check) throws Exception {
        String name="migration-check.sqlite"; c.deleteDatabase(name);
        try {
            try (SQLiteDatabase old=c.openOrCreateDatabase(name,0,null)) {
                old.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, title TEXT NOT NULL, notes TEXT NOT NULL, start_at TEXT NOT NULL, recurrence TEXT NOT NULL, enabled INTEGER NOT NULL, completed INTEGER NOT NULL, next_at TEXT)");
                old.execSQL("INSERT INTO events VALUES (41,?,?,?,?,1,0,?)",new Object[]{"旧版生日","保留备注","2020-02-29T09:00:00","\"yearly\"","2026-02-28T09:00:00"});
                old.setVersion(1);
            }
            try (ReminderStore updated=new ReminderStore(c,name)) {
                JSONObject item=((JSONArray)call(updated,new JSONObject().put("action","list"),"2026-01-01T00:00:00")).getJSONObject(0);
                check.accept(item.getString("recurrence").equals("yearly") && item.getInt("id")==41 && item.getString("notes").equals("保留备注") && item.getString("next_at").equals("2026-02-28T09:00:00"),"Legacy database upgrade preserves event and date");
                check.accept(updated.getReadableDatabase().getVersion()==ReminderStore.VERSION,"Database schema upgraded to version 2");
            }
        } finally { c.deleteDatabase(name); }
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
