package dev.remember.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class MainActivity extends Activity {
    private static final int INK = 0xff24382e, GREEN = 0xff236b54, MUTED = 0xff737e75, PAPER = 0xfff5f4ef;
    private static final String[] RULES = {"once", "daily", "weekly", "monthly", "yearly"};
    private static final String[] LABELS = {"一次性", "每天", "每周", "每月", "每年 · 生日 / 纪念日"};
    private LinearLayout root, content;
    private int filter;
    private JSONArray events = new JSONArray();
    private boolean busy;
    private AlertDialog editor;
    private EditText titleInput, notesInput;
    private Spinner repeatInput;
    private Switch enabledInput;
    private LocalDateTime chosenTime;
    private JSONObject editingEvent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshClock = new Runnable() { public void run() { load(); handler.postDelayed(this, 60000); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 27 ? PAPER : INK);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        if (state != null) {
            filter = state.getInt("filter", 0);
            if (state.containsKey("draft")) {
                try {
                    JSONObject draft = new JSONObject(state.getString("draft"));
                    JSONObject original = state.containsKey("original") ? new JSONObject(state.getString("original")) : null;
                    handler.post(() -> openEditor(original, draft));
                } catch (JSONException e) { toast("草稿恢复失败，请重新填写"); }
            }
        }
        Scheduler.channel(this);
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        Core.WORK.execute(() -> { try { Scheduler.rebuild(getApplicationContext()); } catch (Exception e) { Core.failure(getApplicationContext(), e); } });
        handler.post(refreshClock);
    }
    @Override protected void onPause() { handler.removeCallbacks(refreshClock); super.onPause(); }
    @Override protected void onDestroy() { if (editor != null) editor.dismiss(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("filter", filter);
        if (editor != null && editor.isShowing()) {
            try { state.putString("draft", draft().toString()); if (editingEvent != null) state.putString("original", editingEvent.toString()); }
            catch (JSONException ignored) { }
        }
    }
    private void load() {
        Core.WORK.execute(() -> {
            try {
                JSONArray items = Core.list(getApplicationContext());
                runOnUiThread(() -> { if (!isDestroyed()) { events = items; render(); } });
            } catch (Exception e) { report(e); }
        });
    }
    private void mutate(JSONObject request, Runnable done) {
        if (busy) return;
        busy = true;
        Core.WORK.execute(() -> {
            try {
                Core.call(getApplicationContext(), request);
                // A stored event remains saved if scheduling fails; expose the scheduling error separately.
                try { Scheduler.rebuild(getApplicationContext()); }
                catch (Exception e) { Core.failure(getApplicationContext(), e); }
                runOnUiThread(() -> { busy = false; if (!isDestroyed()) { done.run(); load(); } });
            } catch (Exception e) { runOnUiThread(() -> busy = false); report(e); }
        });
    }
    private void report(Exception e) {
        if (!(e instanceof IllegalArgumentException)) Core.failure(getApplicationContext(), e);
        runOnUiThread(() -> { if (!isDestroyed()) { toast(e.getMessage() == null ? "操作失败" : e.getMessage()); render(); } });
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setIncludeFontPadding(false); v.setLineSpacing(dp(4), 1); return v;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void space(LinearLayout l, int height) { View v = new View(this); l.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private TextView button(String label, int color, int background, Runnable action) {
        TextView b = text(label, 15, color, true); b.setGravity(Gravity.CENTER); b.setPadding(dp(14), dp(13), dp(14), dp(13));
        b.setBackground(shape(background, 14)); b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run()); b.setFocusable(true); return b;
    }
    private void render() {
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = column(); content.setPadding(dp(24), dp(22), dp(24), dp(24)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("记得", 28, INK, true); brand.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = button("提醒设置", GREEN, 0xffe8eee5, this::settings); brand.addView(settings);
        content.addView(brand); space(content, 8);
        content.addView(text("给重要的日子，留一个位置。", 14, MUTED, false)); space(content, 24);
        int active = 0, paused = 0, completed = 0;
        JSONObject soon = null;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i); if (e == null) continue;
            if (e.optBoolean("completed")) completed++; else if (e.optBoolean("enabled")) { active++; if (soon == null) soon = e; } else paused++;
        }
        LinearLayout hero = column(); hero.setPadding(dp(22), dp(22), dp(22), dp(22)); hero.setBackground(shape(0xffe6ecdf, 22));
        hero.addView(text("NEXT UP  /  下一件重要的事", 11, GREEN, true)); space(hero, 14);
        hero.addView(text(soon == null ? "把重要的事，\n放在心上。" : soon.optString("title"), 27, INK, true)); space(hero, 12);
        if (soon != null) hero.addView(text(displayTime(soon.optString("next_at")) + "  ·  " + distance(soon.optString("next_at")), 14, GREEN, false));
        else hero.addView(text("生日、纪念日，或一个小小的约定。", 14, MUTED, false));
        space(hero, 20); hero.addView(text(active + " 个待提醒   /   " + paused + " 个已暂停", 12, MUTED, false));
        content.addView(hero); space(content, 16);
        if (!Scheduler.notificationsAllowed(this)) {
            content.addView(button("通知尚未开启 · 点此开启", 0xff855c25, 0xffffeed3, this::requestNotifications)); space(content, 12);
        } else if (!Scheduler.exactAllowed(this)) {
            content.addView(button("允许准时提醒，减少通知延迟 →", 0xff855c25, 0xffffeed3, this::requestExact)); space(content, 12);
        }
        String error = getSharedPreferences("status", 0).getString("error", "");
        if (!error.isEmpty()) {
            content.addView(button("提醒异常：" + error + " · 点此重试", 0xff9a4438, 0xffffe7df, () -> {
                Core.WORK.execute(() -> { try { Scheduler.rebuild(this); getSharedPreferences("status",0).edit().remove("error").apply(); load(); } catch (Exception e) { report(e); } });
            })); space(content, 12);
        }
        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"待提醒 " + active, "已暂停 " + paused, "已完成 " + completed};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            TextView tab = button(names[i], filter == i ? PAPER : MUTED, filter == i ? INK : PAPER, () -> { filter = f; render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (i < 2) lp.rightMargin = dp(5); tabs.addView(tab, lp);
        }
        content.addView(tabs); space(content, 16);
        int shown = 0;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i); if (e == null) continue;
            int group = e.optBoolean("completed") ? 2 : e.optBoolean("enabled") ? 0 : 1;
            if (group != filter) continue;
            addCard(e); shown++;
        }
        if (shown == 0) {
            space(content, 24);
            content.addView(text(filter == 0 ? "还没有待提醒的事情" : filter == 1 ? "没有暂停的提醒" : "这里会留下已提醒的事项", 19, INK, true));
            space(content, 10); content.addView(text(filter == 0 ? "从一个生日开始，或记下下一次约定。\n点击下方按钮，添加你的第一条提醒。" : "每件重要的事情，都有自己的节奏。", 14, MUTED, false));
        }
        LinearLayout bottom = column(); bottom.setPadding(dp(24), dp(12), dp(24), dp(16));
        bottom.addView(button("＋  新建提醒", PAPER, GREEN, () -> openEditor(null, null)));
        space(bottom, 8); TextView local = text("仅保存在这台设备 · 无需联网", 11, MUTED, false); local.setGravity(Gravity.CENTER); bottom.addView(local);
        root.addView(bottom);
    }
    private void addCard(JSONObject e) {
        LinearLayout card = column(); card.setPadding(dp(18), dp(17), dp(18), dp(17)); card.setBackground(shape(Color.WHITE, 18));
        String rule = e.optString("recurrence"); int index = ruleIndex(rule);
        card.addView(text((index == 4 ? "●  " : "◷  ") + LABELS[index], 11, GREEN, true)); space(card, 10);
        card.addView(text(e.optString("title"), 20, INK, true)); space(card, 8);
        if (!e.optString("notes").isEmpty()) { TextView notes = text(e.optString("notes"), 13, MUTED, false); notes.setMaxLines(2); notes.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(notes); space(card, 8); }
        card.addView(text(e.optBoolean("completed") ? "已提醒 · " + displayTime(e.optString("start_at")) : displayTime(e.optString("next_at")) + "  ·  " + distance(e.optString("next_at")), 13, MUTED, false));
        space(card, 12);
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("编辑", GREEN, 0xffedf3eb, () -> openEditor(e, null)), new LinearLayout.LayoutParams(0, -2, 1));
        if (!e.optBoolean("completed")) {
            TextView toggle = button(e.optBoolean("enabled") ? "暂停" : "恢复提醒", MUTED, PAPER, () -> {
                try { mutate(new JSONObject().put("action", "toggle").put("id", e.getLong("id")).put("enabled", !e.getBoolean("enabled")), () -> toast(e.optBoolean("enabled") ? "已暂停" : "已恢复提醒")); }
                catch (JSONException ex) { report(ex); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = dp(8); actions.addView(toggle, lp);
        }
        card.addView(actions);
        card.setOnClickListener(v -> openEditor(e, null));
        content.addView(card); space(content, 12);
    }
    private String displayTime(String value) {
        try { return LocalDateTime.parse(value, Core.FORMAT).format(DateTimeFormatter.ofPattern("yyyy.MM.dd  HH:mm")); } catch (Exception ignored) { return "时间待设置"; }
    }
    private String distance(String value) {
        try {
            LocalDateTime t = LocalDateTime.parse(value, Core.FORMAT); long minutes = ChronoUnit.MINUTES.between(LocalDateTime.now(), t);
            if (minutes <= 0) return "即将提醒";
            long days = ChronoUnit.DAYS.between(LocalDate.now(), t.toLocalDate());
            if (days == 0) return minutes < 60 ? minutes + " 分钟后" : "今天";
            if (days == 1) return "明天"; return days + " 天后";
        } catch (Exception ignored) { return ""; }
    }
    private int ruleIndex(String rule) { for (int i = 0; i < RULES.length; i++) if (RULES[i].equals(rule)) return i; return 0; }
    private EditText input(String hint, int max) {
        EditText v = new EditText(this); v.setHint(hint); v.setTextSize(16); v.setTextColor(INK); v.setPadding(dp(10), dp(10), dp(10), dp(10)); v.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(max) }); return v;
    }
    private void label(LinearLayout l, String s) { space(l, 16); l.addView(text(s, 12, MUTED, true)); space(l, 5); }
    private void openEditor(JSONObject item, JSONObject restored) {
        if (editor != null && editor.isShowing()) return;
        editingEvent = item;
        JSONObject values = restored == null ? item : restored;
        chosenTime = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);
        if (values != null) { try { chosenTime = LocalDateTime.parse(values.getString("start_at"), Core.FORMAT); } catch (Exception ignored) { } }
        LinearLayout form = column(); form.setPadding(dp(22), dp(4), dp(22), dp(16));
        label(form, "事项名称"); titleInput = input("例如：小林的生日",120); titleInput.setSingleLine(true); form.addView(titleInput);
        label(form, "重复方式"); repeatInput = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, LABELS); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); repeatInput.setAdapter(adapter); form.addView(repeatInput);
        label(form, "首次日期与提醒时间");
        TextView date = button(chosenTime.toLocalDate().toString(), INK, 0xffedf3eb, () -> {
            new DatePickerDialog(this, (view, y, m, d) -> { chosenTime = LocalDateTime.of(LocalDate.of(y, m + 1, d), chosenTime.toLocalTime()); updateDateButtons(form); }, chosenTime.getYear(), chosenTime.getMonthValue() - 1, chosenTime.getDayOfMonth()).show();
        }); date.setTag("date"); form.addView(date); space(form, 8);
        TextView time = button(chosenTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), INK, 0xffedf3eb, () -> {
            new TimePickerDialog(this, (view, h, m) -> { chosenTime = chosenTime.withHour(h).withMinute(m).withSecond(0); updateDateButtons(form); }, chosenTime.getHour(), chosenTime.getMinute(), true).show();
        }); time.setTag("time"); form.addView(time); space(form, 8);
        form.addView(text("按手机当地时间提醒。月末按当月最后一天；\n2 月 29 日在非闰年按 2 月 28 日。", 11, MUTED, false));
        label(form, "备注（可选）"); notesInput = input("例如：提前准备礼物，记得打电话",2000); notesInput.setMinLines(2); notesInput.setMaxLines(4); notesInput.setGravity(Gravity.TOP); form.addView(notesInput);
        space(form, 14); enabledInput = new Switch(this); enabledInput.setText("开启提醒"); enabledInput.setTextColor(INK); enabledInput.setMinHeight(dp(48)); enabledInput.setChecked(values == null || values.optBoolean("enabled", true)); form.addView(enabledInput);
        if (values != null) { titleInput.setText(values.optString("title")); notesInput.setText(values.optString("notes")); repeatInput.setSelection(ruleIndex(values.optString("recurrence"))); }
        if (item != null && item.optBoolean("completed") && restored == null) { chosenTime = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0); enabledInput.setChecked(true); updateDateButtons(form); }
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(item == null ? "新建提醒" : "编辑提醒").setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null);
        if (item != null) builder.setNeutralButton("删除", null);
        editor = builder.create(); editor.setOnDismissListener(d -> { editor = null; editingEvent = null; });
        editor.setOnShowListener(d -> {
            editor.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (titleInput.getText().toString().trim().isEmpty()) { titleInput.setError("请填写事项名称"); return; }
                try {
                    JSONObject request = draft().put("action", "save").put("id", item == null ? JSONObject.NULL : item.getLong("id"));
                    mutate(request, () -> { if (editor != null) editor.dismiss(); filter = 0; toast("已保存提醒"); if (!Scheduler.notificationsAllowed(this)) requestNotifications(); });
                } catch (JSONException e) { report(e); }
            });
            if (item != null) editor.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("删除这条提醒？").setMessage(item.optString("title")).setNegativeButton("保留",null).setPositiveButton("删除", (dialog, which) -> {
                try {
                    mutate(new JSONObject().put("action","delete").put("id",item.getLong("id")), () -> { getSystemService(NotificationManager.class).cancel(item.optInt("id")); if (editor != null) editor.dismiss(); toast("已删除"); });
                } catch (JSONException e) { report(e); }
            }).show());
        }); editor.show();
    }
    private void updateDateButtons(LinearLayout form) {
        ((TextView)form.findViewWithTag("date")).setText(chosenTime.toLocalDate().toString());
        ((TextView)form.findViewWithTag("time")).setText(chosenTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
    }
    private JSONObject draft() throws JSONException {
        return new JSONObject().put("title",titleInput.getText().toString()).put("notes",notesInput.getText().toString()).put("start_at",chosenTime.format(Core.FORMAT)).put("recurrence",RULES[repeatInput.getSelectedItemPosition()]).put("enabled",enabledInput.isChecked());
    }
    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!getPreferences(0).getBoolean("asked_notifications",false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                getPreferences(0).edit().putBoolean("asked_notifications",true).apply(); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10); return;
            }
        }
        Scheduler.openNotificationSettings(this);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 10) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                Core.WORK.execute(() -> { try { Scheduler.rebuild(this); } catch (Exception e) { report(e); } });
                toast("通知已开启");
            } else toast("事项已保存，开启通知后才能提醒");
            render();
        }
    }
    private void requestExact() {
        if (Build.VERSION.SDK_INT >= 31) {
            try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException e) { toast("当前设备不支持此设置，将使用系统普通提醒"); }
        }
    }
    private void settings() {
        String notification = Scheduler.notificationsAllowed(this) ? "已开启" : "未开启";
        String timing = Scheduler.exactAllowed(this) ? "已允许准时提醒" : "普通提醒，可能延迟";
        new AlertDialog.Builder(this).setTitle("提醒设置").setMessage("通知：" + notification + "\n时间：" + timing + "\n\n提醒在后台运行，无需一直打开应用。手机重启后会恢复。\n\n如果手机限制后台活动，请在系统电池设置中允许「记得」后台运行。强行停止应用后，需重新打开。\n\n数据仅存于本机，卸载后会清除。")
            .setPositiveButton("通知设置", (d, w) -> requestNotifications()).setNeutralButton("准时提醒", (d, w) -> requestExact()).setNegativeButton("关闭", null).show();
    }
}
