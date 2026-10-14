package dev.remember.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Core {
    static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Object DATABASE_LOCK = new Object();
    static String now() { return LocalDateTime.now().format(FORMAT); }
    static LocalDateTime parse(String time) {
        try { return LocalDateTime.parse(time, FORMAT); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Invalid date or time", e); }
    }
    static Object call(Context context, JSONObject request) throws Exception { return callAt(context, request, now()); }
    static Object callAt(Context context, JSONObject request, String localNow) throws Exception {
        synchronized (DATABASE_LOCK) {
            try (ReminderStore store = new ReminderStore(context)) { return store.execute(request, parse(localNow)); }
        }
    }
    static JSONArray list(Context c) throws Exception { return (JSONArray) call(c, new JSONObject().put("action", "list")); }
    static void failure(Context c, Throwable e) {
        android.util.Log.e("Remember", "Reminder operation failed", e);
        c.getSharedPreferences("status", 0).edit().putString("error", e.getMessage() == null ? "Reminder operation failed" : e.getMessage()).apply();
    }
    private Core() {}
}
