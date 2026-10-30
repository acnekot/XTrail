package com.catsuki.xtrail;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tab-separated history kept inside X's private files directory. Row = time cat id handle text. */
final class HistoryStore {
    static final String[] CATS = {"home", "search", "open", "jump", "profile", "notif", "list", "comm", "other"};
    static final String[] LABELS = {"首页", "搜索", "点开", "跳转", "个人页", "通知", "列表", "社区", "其他"};

    static String label(String cat) {
        for (int i = 0; i < CATS.length; i++) if (CATS[i].equals(cat)) return LABELS[i];
        return cat;
    }

    /** One post, merged across every place it was seen. */
    static final class Item {
        long id;
        long lastTime;
        String handle = "";
        String text = "";
        final java.util.LinkedHashSet<String> cats = new java.util.LinkedHashSet<>();
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final HashSet<String> SEEN = new HashSet<>();

    private static File file(Context c) {
        return new File(c.getFilesDir(), "xtrail_history2.tsv");
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Cheap, safe to call from the UI thread; writes happen on a background thread. */
    static void add(final Context c, final String cat, final long id, final String handle, final String text) {
        if (c == null || id <= 0) return;
        String key = cat + ":" + id;
        synchronized (SEEN) {
            if (!SEEN.add(key)) {
                // already written this process; only rewrite when we now know text we did not before
                if (text == null || text.isEmpty()) return;
                if (!SEEN.add(key + ":t")) return;
            }
        }
        final long now = System.currentTimeMillis();
        IO.execute(() -> {
            synchronized (LOCK) {
                try (FileOutputStream out = new FileOutputStream(file(c), true)) {
                    out.write((now + "\t" + cat + "\t" + id + "\t" + clean(handle) + "\t"
                            + clean(text) + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                }
                trimIfNeeded(c);
            }
        });
    }

    /** Newest first, one entry per post id. */
    static List<Item> load(Context c) {
        List<String[]> rows = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists()) return new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", 5);
                    if (p.length < 3) continue;
                    try {
                        times.add(Long.parseLong(p[0]));
                        rows.add(p);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Map<Long, Item> map = new LinkedHashMap<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            String[] p = rows.get(i);
            long id;
            try {
                id = Long.parseLong(p[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            Item it = map.get(id);
            if (it == null) {
                it = new Item();
                it.id = id;
                it.lastTime = times.get(i);
                map.put(id, it);
            }
            if (!"detail".equals(p[1])) it.cats.add(p[1]);
            if (it.handle.isEmpty() && p.length > 3) it.handle = p[3];
            if (p.length > 4 && p[4].length() > it.text.length()) it.text = p[4];
        }
        List<Item> out = new ArrayList<>();
        for (Item it : map.values()) if (!it.cats.isEmpty()) out.add(it);
        return out;
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            file(c).delete();
        }
        synchronized (SEEN) {
            SEEN.clear();
        }
    }

    private static void trimIfNeeded(Context c) {
        File f = file(c);
        if (f.length() < 2_500_000) return;
        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            }
            int from = Math.max(0, lines.size() - 12000);
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                for (int i = from; i < lines.size(); i++) out.write((lines.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
