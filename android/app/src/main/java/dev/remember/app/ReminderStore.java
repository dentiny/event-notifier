package dev.remember.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDateTime;

/** All SQL uses Android's SQLite implementation in the app's private database directory. */
final class ReminderStore extends SQLiteOpenHelper {
    static final int VERSION = 2;
    ReminderStore(Context context) { this(context, "reminders.sqlite"); }
    ReminderStore(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(id <= 2147483646)," +
            "title TEXT NOT NULL, notes TEXT NOT NULL, start_at TEXT NOT NULL," +
            "recurrence TEXT NOT NULL, enabled INTEGER NOT NULL," +
            "completed INTEGER NOT NULL DEFAULT 0, next_at TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 stored recurrence as a JSON string, including quotes. Preserve every event.
        if (oldVersion < 2) db.execSQL("UPDATE events SET recurrence = trim(recurrence, '\"')");
    }
    Object execute(JSONObject request, LocalDateTime now) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        switch (request.getString("action")) {
            case "list": {
                JSONArray items = new JSONArray();
                try (Cursor rows = db.query("events", null, null, null, null, null, "enabled DESC, completed ASC, next_at ASC, id DESC")) {
                    while (rows.moveToNext()) items.put(event(rows));
                }
                return items;
            }
            case "save": return save(db, request, now);
            case "delete":
                db.delete("events", "id=?", new String[]{Long.toString(request.getLong("id"))});
                return JSONObject.NULL;
            case "toggle": {
                long id = request.getLong("id");
                JSONObject item = require(db, id);
                boolean enabled = request.getBoolean("enabled");
                if (enabled && item.getBoolean("completed")) throw new IllegalArgumentException("已完成的提醒请编辑时间后重新开启");
                String next = nullable(item, "next_at");
                if (enabled && !item.getString("recurrence").equals("once"))
                    next = stamp(Recurrence.next(Core.parse(item.getString("start_at")), item.getString("recurrence"), now, true));
                ContentValues values = new ContentValues();
                values.put("enabled", enabled ? 1 : 0); putNullable(values, "next_at", next);
                db.update("events", values, "id=?", new String[]{Long.toString(id)});
                return require(db, id);
            }
            case "fire": return fire(db, request, now);
            default: throw new IllegalArgumentException("请求参数无效");
        }
    }
    private JSONObject save(SQLiteDatabase db, JSONObject request, LocalDateTime now) throws Exception {
        String title = request.getString("title").trim();
        String notes = request.getString("notes");
        if (title.isEmpty() || title.codePointCount(0, title.length()) > 120)
            throw new IllegalArgumentException("标题需为 1–120 个字符");
        if (notes.codePointCount(0, notes.length()) > 2000) throw new IllegalArgumentException("备注不能超过 2000 个字符");
        String start = request.getString("start_at");
        LocalDateTime anchor = Core.parse(start);
        if (anchor.getYear() < 1970 || anchor.getYear() > 9998) throw new IllegalArgumentException("年份需在 1970–9998 之间");
        String rule = request.getString("recurrence"); Recurrence.validate(rule);
        boolean enabled = request.getBoolean("enabled");
        Long id = request.isNull("id") ? null : request.getLong("id");
        JSONObject existing = id == null ? null : require(db, id);
        boolean unchanged = existing != null && existing.getString("start_at").equals(start)
            && existing.getString("recurrence").equals(rule) && !existing.getBoolean("completed");
        if (rule.equals("once") && anchor.isBefore(now) && !unchanged)
            throw new IllegalArgumentException("一次性提醒请选择未来的时间");
        boolean resumingRepeat = enabled && !rule.equals("once") && existing != null && !existing.getBoolean("enabled");
        String next = unchanged && !resumingRepeat ? nullable(existing, "next_at") : stamp(Recurrence.next(anchor, rule, now, true));
        ContentValues values = new ContentValues();
        values.put("title", title); values.put("notes", notes); values.put("start_at", start);
        values.put("recurrence", rule); values.put("enabled", enabled ? 1 : 0); values.put("completed", 0);
        putNullable(values, "next_at", next);
        if (id == null) id = db.insertOrThrow("events", null, values);
        else db.update("events", values, "id=?", new String[]{Long.toString(id)});
        return require(db, id);
    }
    private Object fire(SQLiteDatabase db, JSONObject request, LocalDateTime now) throws Exception {
        db.beginTransactionNonExclusive();
        try {
            long id = request.getLong("id");
            String expected = request.getString("expected_at");
            JSONObject item = get(db, id);
            if (item == null || !item.getBoolean("enabled") || item.getBoolean("completed")
                || !expected.equals(nullable(item, "next_at")) || Core.parse(expected).isAfter(now)) return JSONObject.NULL;
            String rule = item.getString("recurrence");
            LocalDateTime expectedTime = Core.parse(expected);
            LocalDateTime after = now.isAfter(expectedTime) ? now : expectedTime;
            String next = stamp(Recurrence.next(Core.parse(item.getString("start_at")), rule, after, false));
            boolean completed = rule.equals("once");
            ContentValues values = new ContentValues();
            putNullable(values, "next_at", next); values.put("enabled", completed ? 0 : 1); values.put("completed", completed ? 1 : 0);
            db.update("events", values, "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
            return item;
        } finally { db.endTransaction(); }
    }
    private JSONObject require(SQLiteDatabase db, long id) throws Exception {
        JSONObject item = get(db, id);
        if (item == null) throw new IllegalArgumentException("提醒不存在");
        return item;
    }
    private JSONObject get(SQLiteDatabase db, long id) throws Exception {
        try (Cursor row = db.query("events", null, "id=?", new String[]{Long.toString(id)}, null, null, null)) {
            return row.moveToFirst() ? event(row) : null;
        }
    }
    private JSONObject event(Cursor row) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", row.getLong(row.getColumnIndexOrThrow("id")));
        for (String key : new String[]{"title", "notes", "start_at", "recurrence", "next_at"}) {
            int index = row.getColumnIndexOrThrow(key);
            item.put(key, row.isNull(index) ? JSONObject.NULL : row.getString(index));
        }
        item.put("enabled", row.getInt(row.getColumnIndexOrThrow("enabled")) != 0);
        item.put("completed", row.getInt(row.getColumnIndexOrThrow("completed")) != 0);
        return item;
    }
    private static String nullable(JSONObject object, String key) throws Exception { return object.isNull(key) ? null : object.getString(key); }
    private static void putNullable(ContentValues values, String key, String value) { if (value == null) values.putNull(key); else values.put(key, value); }
    private static String stamp(LocalDateTime time) { return time == null ? null : time.format(Core.FORMAT); }
}
