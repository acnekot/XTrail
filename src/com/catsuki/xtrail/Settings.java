package com.catsuki.xtrail;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User options, kept in the target app's own SharedPreferences ("xtrail_settings") and cached in memory.
 * The module's launcher screen edits the same file as root ({@link Config}).
 */
final class Settings {
    /** categories that can be switched off individually, in display order */
    static final String[][] CATEGORIES = {
            {"home", "首页"}, {"search", "搜索"}, {"profile", "主页"}, {"notif", "通知"},
            {"list", "列表"}, {"comm", "社群"}, {"detail", "详情"}, {"other", "其他"},
    };
    static final int[] CAPS = {200, 400, 1000, 3000};

    private static SharedPreferences prefs;
    private static volatile boolean pin = true, recordAll = true;
    private static volatile int cap = 400;
    private static final java.util.Set<String> OFF = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static volatile String group = "hour";
    private static boolean loaded;

    private static synchronized void init() {
        if (loaded) return;
        try {
            Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (c == null) return;
            prefs = c.getSharedPreferences("xtrail_settings", Context.MODE_PRIVATE);
            pin = prefs.getBoolean("pin", true);
            recordAll = prefs.getBoolean("record", true);
            cap = prefs.getInt("cap", 400);
            group = prefs.getString("group", "hour");
            for (String[] cat : CATEGORIES) if (!prefs.getBoolean("cat_" + cat[0], true)) OFF.add(cat[0]);
            loaded = true;
        } catch (Throwable ignored) {
        }
    }

    static boolean pinBar() { init(); return pin; }

    static boolean recordAll() { init(); return recordAll; }

    static boolean recordCat(String cat) { init(); return recordAll && !OFF.contains(cat); }

    static boolean catOn(String cat) { init(); return !OFF.contains(cat); }

    static int cap() { init(); return cap; }

    /** how the 足迹 list is grouped: "hour", "day" or "off" */
    static String group() { init(); return group; }

    static void setGroup(String g) {
        init();
        group = g;
        if (prefs != null) prefs.edit().putString("group", g).apply();
    }

    static void setPin(boolean v) { init(); pin = v; put("pin", v); }

    static void setRecordAll(boolean v) { init(); recordAll = v; put("record", v); }

    static void setCat(String cat, boolean on) {
        init();
        if (on) OFF.remove(cat); else OFF.add(cat);
        put("cat_" + cat, on);
    }

    static void setCap(int v) {
        init();
        cap = v;
        if (prefs != null) prefs.edit().putInt("cap", v).apply();
    }

    private static void put(String k, boolean v) {
        if (prefs != null) prefs.edit().putBoolean(k, v).apply();
    }
}
