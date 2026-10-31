package com.catsuki.xtrail;

import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Adds a third page to X's native History screen (next to Bookmarks and Likes).
 *
 * The page list is Decompose "Pages" of HistoryTab configs. We append the (otherwise unused) OfflineVideos
 * config, answer its child factory with a second Likes timeline component, and feed that component's UI
 * state flow with the timeline items we captured, so cards and interactions are X's own.
 */
final class NativeTab {
    private static final String TAG = "XTrail";
    private static final int TITLE_RES = 0x7f131541; // string used as the OfflineVideos tab title (2131957057)
    private static final String TITLE = "足迹";

    private static ClassLoader cl;
    private static XposedModule mod;
    private static Class<?> cU9, cW9, cX9, cItemL1;
    private static Object x9Instance, w9Instance;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** our Likes-clone components -> their state flow */
    private static final Map<Object, Object> OURS = Collections.synchronizedMap(new WeakHashMap<>());
    /** post id -> timeline item, oldest first (re-put on every sighting) */
    private static final LinkedHashMap<Long, Object> LIVE = new LinkedHashMap<>();
    private static final java.util.HashMap<Long, Long> POST_KEY = new java.util.HashMap<>();
    private static final java.util.HashSet<Long> PENDING = new java.util.HashSet<>();
    private static boolean publishQueued;
    private static final String FILE = "xtrail_native.tsv";   // one row per post: id 	 json
    private static final char TAB = (char) 9, NL = (char) 10;
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static Object json, itemSerializer;   // kotlinx Json (c.d) and l1's KSerializer
    private static Method jsonEncode, jsonDecode;
    private static File store;
    /** post id -> source category (home/search/...), and the ids the user actually opened */
    private static final Map<Long, String> CATS = new java.util.HashMap<>();
    private static final java.util.Set<Long> OPENED = new java.util.HashSet<>();
    /** keys of posts that are replies (a post's JSON carries "repliedPostId":<id> only then) */
    private static final java.util.Set<Long> REPLIES = new java.util.HashSet<>();
    private static final java.util.regex.Pattern REPLY_JSON = java.util.regex.Pattern.compile("\"repliedPostId\":\\d");
    private static volatile String filter = "all";
    private static volatile String timeRange = "all";   // all | today | 7d | 30d
    private static volatile String query = "";
    /** post key -> last time it was seen, and its lower-cased searchable text (handle + post text) */
    private static final Map<Long, Long> TIMES = new java.util.HashMap<>();
    private static final Map<Long, String> TEXTS = new java.util.HashMap<>();

    static void install(XposedModule m, ClassLoader loader) throws Throwable {
        mod = m;
        cl = loader;
        cU9 = cl.loadClass("com.x.navigation.u9");
        cW9 = cl.loadClass("com.x.navigation.w9");
        cX9 = cl.loadClass("com.x.navigation.x9");
        cItemL1 = cl.loadClass("com.x.models.timelines.items.l1");
        x9Instance = cX9.getField("INSTANCE").get(null);
        w9Instance = cW9.getField("INSTANCE").get(null);
        hookPages();
        hookChildFactory();
        hookComponent();
        try { hookListState(); } catch (Throwable t) { mod.log(android.util.Log.WARN, TAG, "list state hook failed: " + t); }
        hookTitle();
        hookTabChevron();
        hookTabClick();
    }

    // ---------------------------------------------------------------- persistence

    private static boolean storeTried;

    /** The Application does not exist yet when the module loads, so this runs on first use. */
    private static synchronized void initStore() {
        if (storeTried) return;
        try {
            android.app.Application app = (android.app.Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) return;
            storeTried = true;
            store = new File(app.getFilesDir(), FILE);
            Class<?> jc = cl.loadClass("kotlinx.serialization.json.c");
            Class<?> ks = cl.loadClass("kotlinx.serialization.KSerializer");
            json = jc.getDeclaredField("d").get(null);
            jsonEncode = jc.getMethod("d", Object.class, ks);
            jsonDecode = jc.getMethod("b", String.class, cl.loadClass("kotlinx.serialization.a"));
            Object companion = cItemL1.getField("Companion").get(null);
            itemSerializer = companion.getClass().getMethod("serializer").invoke(companion);
        } catch (Throwable t) {
            storeTried = true;
            mod.log(android.util.Log.ERROR, TAG, "persistence unavailable", t);
            store = null;
            return;
        }
        IO.execute(NativeTab::load);
    }

    private static void load() {
        LinkedHashMap<Long, Object> loaded = new LinkedHashMap<>();
        int bad = 0;
        if (store.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(store), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // id TAB cat TAB opened TAB json   (first version: id TAB json)
                    String[] f = line.split(String.valueOf(TAB), 6);
                    if (f.length == 6) { // id cat opened time text json
                        f = new String[]{f[0], f[1], f[2], f[5], f[3], f[4]};
                    } else {
                        f = line.split(String.valueOf(TAB), 4);
                    }
                    try {
                        long id = Long.parseLong(f[0]);
                        if (id == 0L || id == Long.MAX_VALUE) continue; // junk keys from early detail items
                        boolean old = f.length == 2;
                        if (f.length < 4 && !old) continue;
                        String js = old ? f[1] : f[3];
                        loaded.put(id, jsonDecode.invoke(json, js, itemSerializer));
                        synchronized (LIVE) {
                            TIMES.put(id, f.length == 6 ? Long.parseLong(f[4]) : store.lastModified());
                            TEXTS.put(id, f.length == 6 ? f[5] : js.toLowerCase());
                            CATS.put(id, old ? "home" : f[1]);
                            if (!old && f[2].equals("1")) OPENED.add(id);
                            if (REPLY_JSON.matcher(js).find()) REPLIES.add(id);
                        }
                    } catch (Throwable t) {
                        bad++;
                    }
                }
            } catch (Throwable t) {
                mod.log(android.util.Log.WARN, TAG, "load failed: " + t);
            }
        }
        synchronized (LIVE) {
            for (Map.Entry<Long, Object> e : LIVE.entrySet()) { loaded.remove(e.getKey()); loaded.put(e.getKey(), e.getValue()); }
            LIVE.clear();
            LIVE.putAll(loaded);
            trimLocked();
        }
        mod.log(android.util.Log.INFO, TAG, "native history loaded: " + loaded.size() + " items, " + bad + " unreadable");
        queuePublish();
    }

    private static void save() {
        if (store == null) return;
        final ArrayList<Object[]> snap = new ArrayList<>();
        synchronized (LIVE) {
            for (Map.Entry<Long, Object> e : LIVE.entrySet()) {
                snap.add(new Object[]{e.getKey(), e.getValue(), CATS.get(e.getKey()), OPENED.contains(e.getKey()),
                        TIMES.get(e.getKey()), TEXTS.get(e.getKey())});
            }
        }
        IO.execute(() -> {
            try {
                File tmp = new File(store.getPath() + ".tmp");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) {
                    for (Object[] e : snap) {
                        try {
                            String j = (String) jsonEncode.invoke(json, e[1], itemSerializer);
                            w.write(e[0] + String.valueOf(TAB) + (e[2] == null ? "other" : e[2]) + TAB + (((Boolean) e[3]) ? "1" : "0")
                                    + TAB + (e[4] == null ? 0L : e[4]) + TAB + (e[5] == null ? "" : ((String) e[5]).replace(TAB, ' ').replace(NL, ' '))
                                    + TAB + j.replace(NL, ' ') + NL);
                        } catch (Throwable t) {
                            // skip items that cannot be serialized
                        }
                    }
                }
                if (!tmp.renameTo(store)) { store.delete(); tmp.renameTo(store); }
            } catch (Throwable t) {
                mod.log(android.util.Log.WARN, TAG, "save failed: " + t);
            }
        });
    }

    // ---------------------------------------------------------------- export / import / browse info

    /** every post as one text row (id, cat, opened, time, text, json), or null when there is nothing */
    static String exportText() {
        initStore();
        if (json == null) return null;
        ArrayList<Object[]> snap = new ArrayList<>();
        synchronized (LIVE) {
            for (Map.Entry<Long, Object> e : LIVE.entrySet()) {
                snap.add(new Object[]{e.getKey(), e.getValue(), CATS.get(e.getKey()), OPENED.contains(e.getKey()),
                        TIMES.get(e.getKey()), TEXTS.get(e.getKey())});
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Object[] e : snap) {
            try {
                String j = (String) jsonEncode.invoke(json, e[1], itemSerializer);
                sb.append(e[0]).append(TAB).append(e[2] == null ? "other" : e[2]).append(TAB).append(((Boolean) e[3]) ? "1" : "0")
                        .append(TAB).append(e[4] == null ? 0L : e[4]).append(TAB)
                        .append(e[5] == null ? "" : ((String) e[5]).replace(TAB, ' ').replace(NL, ' '))
                        .append(TAB).append(j.replace(NL, ' ')).append(NL);
            } catch (Throwable ignored) {
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** merges exported rows into the history (the newer sighting of a post wins); returns how many were added or updated, -1 on failure */
    static int importText(String text) {
        initStore();
        if (json == null) return -1;
        int n = 0;
        for (String line : text.split(String.valueOf(NL))) {
            String[] f = line.split(String.valueOf(TAB), 6);
            if (f.length != 6) continue;
            try {
                long id = Long.parseLong(f[0]);
                if (id == 0L || id == Long.MAX_VALUE) continue;
                long time = Long.parseLong(f[3]);
                Object item = jsonDecode.invoke(json, f[5], itemSerializer);
                synchronized (LIVE) {
                    Long cur = TIMES.get(id);
                    if (cur != null && cur >= time && LIVE.containsKey(id)) continue;
                    LIVE.put(id, item);
                    TIMES.put(id, time);
                    TEXTS.put(id, f[4]);
                    CATS.put(id, f[1]);
                    if ("1".equals(f[2])) OPENED.add(id);
                    if (REPLY_JSON.matcher(f[5]).find()) REPLIES.add(id);
                }
                n++;
            } catch (Throwable ignored) {
            }
        }
        synchronized (LIVE) {
            // the list order is the insertion order: put everything back in time order
            ArrayList<Long> ids = new ArrayList<>(LIVE.keySet());
            Collections.sort(ids, (x, y) -> Long.compare(TIMES.containsKey(x) ? TIMES.get(x) : 0L, TIMES.containsKey(y) ? TIMES.get(y) : 0L));
            java.util.HashMap<Long, Object> copy = new java.util.HashMap<>(LIVE);
            LIVE.clear();
            for (Long id : ids) LIVE.put(id, copy.get(id));
            trimLocked();
        }
        save();
        queuePublish();
        return n;
    }

    /** list rows on screen: {key, top, bottom}, relative to the list's top edge */
    private static volatile ArrayList<Object[]> ROWS = new ArrayList<>();
    private static final java.util.regex.Pattern ID_IN_KEY = java.util.regex.Pattern.compile("-?\\d{5,}");
    private static final java.text.SimpleDateFormat FULL = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());

    /** {exact time, how long ago, source, opened} of the post at y (px below the list top), or null */
    static String[] browseInfoAt(int y) {
        for (Object[] r : ROWS) {
            if (y < (Integer) r[1] || y >= (Integer) r[2]) continue;
            java.util.regex.Matcher m = ID_IN_KEY.matcher((String) r[0]);
            while (m.find()) {
                long id;
                try { id = Long.parseLong(m.group()); } catch (NumberFormatException e) { continue; }
                Long t;
                String cat;
                boolean opened;
                synchronized (LIVE) { t = TIMES.get(id); cat = CATS.get(id); opened = OPENED.contains(id); }
                if (t == null) continue;
                long min = Math.max(0, (System.currentTimeMillis() - t) / 60000L);
                String ago = min < 1 ? "刚刚" : min < 60 ? min + " 分钟前" : min < 1440 ? min / 60 + " 小时前" : min / 1440 + " 天前";
                return new String[]{FULL.format(new java.util.Date(t)), ago, cat == null ? "" : FilterMenu.label(cat), opened ? "1" : ""};
            }
            return null;
        }
        return null;
    }

    // ---------------------------------------------------------------- capture

    /** postId = id of the post inside the item (the item's own key is an entry id, not the post id). */
    static void onItem(String cat, Object timelineItem, long postId, String handle, String text, boolean reply) {
        initStore();
        if (timelineItem == null || cItemL1 == null || !cItemL1.isInstance(timelineItem)) return;
        long id;
        try {
            Field f = cItemL1.getDeclaredField("b");
            f.setAccessible(true);
            id = f.getLong(timelineItem);
        } catch (Throwable t) {
            return;
        }
        synchronized (LIVE) {
            if (postId != 0L) {
                // one row per post: reuse the key it was first seen under; detail items carry junk keys (0, MAX)
                Long ex = POST_KEY.get(postId);
                if (ex != null) id = ex;
                else if ("detail".equals(cat) || id == 0L || id == Long.MAX_VALUE) id = postId;
            }
            boolean had = LIVE.containsKey(id);
            String oldCat = CATS.get(id);
            LIVE.remove(id);
            LIVE.put(id, timelineItem);
            TIMES.put(id, System.currentTimeMillis());
            TEXTS.put(id, ((handle == null ? "" : handle) + " " + (text == null ? "" : text)).toLowerCase());
            CATS.put(id, had && oldCat != null ? oldCat : cat);
            if (reply) REPLIES.add(id); else REPLIES.remove(id);
            if (postId != 0L) {
                POST_KEY.put(postId, id);
                if (PENDING.remove(postId)) OPENED.add(id);
            }
            trimLocked();
        }
        queuePublish();
    }

    /** drop the oldest entries beyond the configured cap; caller holds LIVE's lock */
    private static void trimLocked() {
        int cap = Settings.cap();
        while (LIVE.size() > cap) {
            Long old = LIVE.keySet().iterator().next();
            LIVE.remove(old);
            CATS.remove(old);
            TIMES.remove(old);
            TEXTS.remove(old);
            OPENED.remove(old);
            REPLIES.remove(old);
        }
        POST_KEY.values().removeIf(k -> !LIVE.containsKey(k));
    }

    static void trim() {
        synchronized (LIVE) { trimLocked(); }
        queuePublish();
    }

    static void clearAll() {
        synchronized (LIVE) {
            LIVE.clear(); CATS.clear(); TIMES.clear(); TEXTS.clear(); OPENED.clear(); REPLIES.clear(); POST_KEY.clear(); PENDING.clear();
        }
        queuePublish();
    }

    /** A post was opened (from a timeline or by link / notification). */
    static void noteOpened(long id) {
        synchronized (LIVE) {
            Long key = POST_KEY.get(id);
            if (key == null && LIVE.containsKey(id)) key = id;
            mod.log(android.util.Log.INFO, TAG, "opened " + id + (key == null ? " (not in history)" : " -> " + key));
            if (key == null || !LIVE.containsKey(key)) {
                PENDING.add(id); // the detail screen records the post right after; mark it then
                return;
            }
            OPENED.add(key);
        }
        queuePublish();
    }

    // ---------------------------------------------------------------- filter

    static String filter() { return filter; }

    static String timeRange() { return timeRange; }

    static String query() { return query; }

    static void setTimeRange(String t) {
        timeRange = t;
        refresh();
    }

    static void setQuery(String q) {
        query = q == null ? "" : q.trim().toLowerCase();
        refresh();
    }

    static void setFilter(String f) {
        filter = f;
        refresh();
    }

    private static void refresh() {
        MAIN.post(() -> {
            try {
                synchronized (OURS) {
                    for (Object flow : OURS.values()) setState(flow, buildState());
                }
            } catch (Throwable t) {
                mod.log(android.util.Log.WARN, TAG, "filter failed: " + t);
            }
        });
    }

    /** category -> number of stored posts, plus "all" and "opened" */
    static LinkedHashMap<String, Integer> counts() {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        synchronized (LIVE) {
            m.put("all", LIVE.size());
            int rep = 0;
            for (Long id : LIVE.keySet()) if (REPLIES.contains(id)) rep++;
            if (rep > 0) { m.put("post", LIVE.size() - rep); m.put("reply", rep); }
            for (Long id : LIVE.keySet()) {
                String c = CATS.get(id);
                if (c == null) c = "other";
                Integer n = m.get(c);
                m.put(c, n == null ? 1 : n + 1);
            }
            if (!OPENED.isEmpty()) m.put("opened", OPENED.size());
        }
        return m;
    }

    private static void queuePublish() {
        synchronized (LIVE) {
            if (publishQueued) return;
            publishQueued = true;
        }
        MAIN.postDelayed(() -> {
            synchronized (LIVE) { publishQueued = false; }
            publishBody();
        }, 700);
    }

    /** user-driven changes (fold a group, change grouping) must show at once */
    private static void publishNow() {
        MAIN.post(NativeTab::publishBody);
    }

    private static void publishBody() {
        try {
            synchronized (OURS) {
                for (Object flow : OURS.values()) setState(flow, buildState());
            }
            save();
        } catch (Throwable t) {
            mod.log(android.util.Log.WARN, TAG, "publish failed: " + t);
        }
    }

    // ---------------------------------------------------------------- hooks

    /** Our tab gets the same trailing chevron the Bookmarks tab has (RichTabDescriptor slot d). */
    private static void hookTabChevron() throws Throwable {
        Class<?> desc = cl.loadClass("com.x.ui.common.tabs.s");
        final Class<?> slot = cl.loadClass("com.x.ui.common.tabs.f0");
        final Class<?> label = cl.loadClass("com.x.ui.common.tabs.v");
        Class<?> icon = cl.loadClass("com.x.ui.common.tabs.u");
        final Object chevron = icon.getConstructors()[0].newInstance(cl.loadClass("com.x.icons.a").getField("z1").get(null));
        final Object historyIcon = icon.getConstructors()[0].newInstance(cl.loadClass("com.x.icons.a").getField("S4").get(null));
        final Field slotPersist = slot.getField("b");
        final Constructor<?> slotCtor = slot.getConstructors()[0];
        final Field slotElement = slot.getField("a");
        final Field labelText = label.getField("a");
        Constructor<?> target = null;
        for (Constructor<?> c : desc.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == 6) target = c;
        }
        if (target == null) throw new NoSuchMethodException("RichTabDescriptor 6-arg ctor");
        mod.hook(target).intercept(chain -> {
            Object[] a = chain.getArgs().toArray();
            try {
                Object lab = a[2];
                if (lab != null && a[3] == null) {
                    Object el = slotElement.get(lab);
                    if (label.isInstance(el) && TITLE.equals(labelText.get(el))) {
                        a[3] = slotCtor.newInstance(chevron, Boolean.FALSE);
                        if (a[1] != null) { // swap the offline-download icon for the history clock
                            boolean keep = slotPersist.getBoolean(a[1]);
                            a[1] = slotCtor.newInstance(historyIcon, keep);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed(a);
        });
    }

    /** Tapping the already-selected tab sends x1(index); on our tab that opens the category menu. */
    private static void hookTabClick() throws Throwable {
        Class<?> q = cl.loadClass("com.x.bookmarks.history.q");
        final Class<?> reclick = cl.loadClass("com.x.bookmarks.history.x1");
        final Field index = reclick.getField("a");
        Method b = null;
        for (Method m : q.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("b") && p.length == 1 && p[0].isAssignableFrom(reclick)) b = m;
        }
        if (b == null) throw new NoSuchMethodException("history.q.b(event)");
        mod.hook(b).intercept(chain -> {
            Object ev = chain.getArg(0);
            if (reclick.isInstance(ev) && index.getInt(ev) == 2) {
                MAIN.post(() -> FilterMenu.show(XTrailModule.topActivity()));
                return null;
            }
            return chain.proceed();
        });
    }

    /** Pages(selectedIndex, items): [Bookmarks, Likes] -> [Bookmarks, Likes, OfflineVideos(ours)] */
    private static void hookPages() throws Throwable {
        Class<?> pages = cl.loadClass("com.arkivanov.decompose.router.pages.f");
        Constructor<?> target = null;
        for (Constructor<?> c : pages.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == int.class && List.class.isAssignableFrom(p[1])) target = c;
        }
        if (target == null) throw new NoSuchMethodException("Pages(int,List)");
        mod.hook(target).intercept(chain -> {
            Object[] a = chain.getArgs().toArray();
            List<?> l = (List<?>) a[1];
            if (l.size() == 3 && ((Integer) a[0]) == 2 && l.get(2) == x9Instance) hintIfEmpty();
            if (l.size() == 2 && cU9.isInstance(l.get(0)) && cW9.isInstance(l.get(1))) {
                ArrayList<Object> n = new ArrayList<>(l);
                n.add(x9Instance);
                a[1] = n;
                mod.log(android.util.Log.INFO, TAG, "history pages extended to " + n.size());
            }
            return chain.proceed(a);
        });
    }

    /** history.c.invoke(config, ctx): OfflineVideos child -> a second Likes timeline component. */
    private static void hookChildFactory() throws Throwable {
        Class<?> fac = cl.loadClass("com.x.bookmarks.history.c");
        Method inv = fac.getDeclaredMethod("invoke", Object.class, Object.class);
        final Field kind = fac.getDeclaredField("a");
        kind.setAccessible(true);
        mod.hook(inv).intercept(chain -> {
            Object[] a = chain.getArgs().toArray();
            boolean ours = a[0] == x9Instance && kind.getByte(chain.getThisObject()) == 1;
            if (ours) a[0] = w9Instance;
            Object r = chain.proceed(a);
            if (ours && r != null) {
                initStore();
                OURS.put(r, newFlow());
                mod.log(android.util.Log.INFO, TAG, "native tab component created: " + r.getClass().getName());
            }
            return r;
        });
    }

    /** history.u (Likes timeline component): our instance serves our own state flow and ignores refreshes. */
    private static void hookComponent() throws Throwable {
        Class<?> u = cl.loadClass("com.x.bookmarks.history.u");
        Method d = u.getDeclaredMethod("d");
        mod.hook(d).intercept(chain -> {
            Object flow = OURS.get(chain.getThisObject());
            return flow != null ? flow : chain.proceed();
        });
        Method l = null;
        for (Method m : u.getDeclaredMethods()) {
            if (m.getName().equals("l") && m.getParameterTypes().length == 1) l = m;
        }
        if (l != null) {
            mod.hook(l).intercept(chain -> OURS.containsKey(chain.getThisObject()) ? null : chain.proceed());
        }
    }

    private static void hookTitle() throws Throwable {
        mod.hook(Resources.class.getDeclaredMethod("getString", int.class)).intercept(chain -> {
            if (((Integer) chain.getArg(0)) == TITLE_RES) return TITLE;
            return chain.proceed();
        });
        mod.hook(Resources.class.getDeclaredMethod("getText", int.class)).intercept(chain -> {
            if (((Integer) chain.getArg(0)) == TITLE_RES) return TITLE;
            return chain.proceed();
        });
    }

    // ---------------------------------------------------------------- state construction

    private static Object newFlow() {
        try {
            Method c = cl.loadClass("kotlinx.coroutines.flow.b0").getMethod("c", Object.class);
            return c.invoke(null, buildState());
        } catch (Throwable t) {
            mod.log(android.util.Log.ERROR, TAG, "newFlow failed", t);
            return null;
        }
    }

    private static void setState(Object flow, Object state) throws Throwable {
        if (flow == null) return;
        Method get = flow.getClass().getMethod("getValue");
        Method cas = null;
        for (Method m : flow.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == Object.class && p[1] == Object.class && m.getReturnType() == boolean.class) cas = m;
        }
        if (cas == null) throw new NoSuchMethodException("StateFlow.compareAndSet");
        for (int i = 0; i < 5; i++) if ((Boolean) cas.invoke(flow, get.invoke(flow), state)) return;
    }

    /** com.x.urt.b2 (Success state): timelineType, items, refreshState, autoPlay, dataSaver */
    private static long sinceMillis(String range) {
        long now = System.currentTimeMillis();
        switch (range) {
            case "today": {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
                c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
                return c.getTimeInMillis();
            }
            case "7d": return now - 7L * 86400000L;
            case "30d": return now - 30L * 86400000L;
            default: return Long.MIN_VALUE;
        }
    }

    /** ids passing the current category / time / search filters, newest first */
    private static ArrayList<Long> filteredIds() {
        ArrayList<Long> out = new ArrayList<>();
        synchronized (LIVE) {
            ArrayList<Long> ids = new ArrayList<>(LIVE.keySet());
            String f = filter, q = query;
            long since = sinceMillis(timeRange);
            for (int i = ids.size() - 1; i >= 0; i--) {
                Long id = ids.get(i);
                boolean ok = f.equals("all") || (f.equals("opened") ? OPENED.contains(id)
                        : f.equals("reply") ? REPLIES.contains(id)
                        : f.equals("post") ? !REPLIES.contains(id)
                        : f.equals(CATS.get(id)));
                if (!ok) continue;
                Long t = TIMES.get(id);
                if (t != null && t < since) continue;
                String tx = TEXTS.get(id);
                if (!q.isEmpty() && (tx == null || !tx.contains(q))) continue;
                out.add(id);
            }
        }
        return out;
    }

    private static ArrayList<Object> filtered() {
        ArrayList<Object> items = new ArrayList<>();
        synchronized (LIVE) {
            for (Long id : filteredIds()) items.add(LIVE.get(id));
        }
        return items;
    }

    private static final java.text.SimpleDateFormat HM = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
    private static final java.text.SimpleDateFormat MD = new java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault());

    private static String dayLabel(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        if (t >= today) return "今天";
        if (t >= today - 86400000L) return "昨天";
        return MD.format(new java.util.Date(t));
    }

    /** a native timeline label (UrtTimelineLabel) used as a section header */
    private static Object separator(String header, String sub, long key) throws Throwable {
        Class<?> od = cl.loadClass("com.x.models.od");
        Class<?> eg = cl.loadClass("com.x.models.eg");
        Object label = od.getConstructor(String.class, String.class, eg).newInstance(header, sub, null);
        Class<?> v0 = cl.loadClass("com.x.models.timelines.items.v0");
        Class<?> ci = cl.loadClass("com.x.models.u0");
        return v0.getConstructor(od, long.class, String.class, ci).newInstance(label, key, "xtrail-sep-" + key, null);
    }

    /** closing label: the whole history is local, so there is never a next page */
    private static Object endLabel() throws Throwable {
        Class<?> od = cl.loadClass("com.x.models.od");
        Class<?> eg = cl.loadClass("com.x.models.eg");
        Object label = od.getConstructor(String.class, String.class, eg).newInstance("没有更多的历史记录", null, null);
        Class<?> v0 = cl.loadClass("com.x.models.timelines.items.v0");
        Class<?> ci = cl.loadClass("com.x.models.u0");
        return v0.getConstructor(od, long.class, String.class, ci).newInstance(label, Long.MIN_VALUE + 1, "xtrail-end", null);
    }

    private static final java.util.regex.Pattern SEP = java.util.regex.Pattern.compile("xtrail-sep-(-?\\d+)");
    private static final java.util.IdentityHashMap<Object, Long> KEYDAY = new java.util.IdentityHashMap<>();
    private static final java.util.Set<Long> COLLAPSED = new java.util.HashSet<>();

    /** local hour or day bucket, depending on the grouping setting */
    private static long dayKey(long t) {
        return (t + java.util.TimeZone.getDefault().getOffset(t)) / ("day".equals(Settings.group()) ? 86400000L : 3600000L);
    }

    static void setGroup(String g) {
        Settings.setGroup(g);
        synchronized (COLLAPSED) { COLLAPSED.clear(); }
        publishNow();
    }

    /** toggles a day group (called from the Sticky panel) and republishes the list */
    static void toggleDay(long day) {
        synchronized (COLLAPSED) { if (!COLLAPSED.remove(day)) COLLAPSED.add(day); }
        publishNow();
    }

    /** posts with one header per day ("今天 02:00" = latest view time of that day); collapsed days keep only the header */
    private static ArrayList<Object> withHeaders() throws Throwable {
        if ("off".equals(Settings.group())) {
            HEADERS = new ArrayList<>();
            ArrayList<Object> plain = new ArrayList<Object>(filtered());
            if (!plain.isEmpty()) plain.add(endLabel());
            lastCount = plain.size();
            return plain;
        }
        boolean perDay = "day".equals(Settings.group());
        ArrayList<Long> ids = filteredIds();
        ArrayList<Object> out = new ArrayList<>();
        ArrayList<Object[]> heads = new ArrayList<>();
        int i = 0;
        while (i < ids.size()) {
            Long t0 = TIMES.get(ids.get(i));
            int j = i;
            long day = t0 == null ? -1 : dayKey(t0);
            while (j < ids.size()) {
                Long t = TIMES.get(ids.get(j));
                if ((t == null ? -1 : dayKey(t)) != day) break;
                j++;
            }
            if (t0 != null) {
                boolean closed;
                synchronized (COLLAPSED) { closed = COLLAPSED.contains(day); }
                Long tl = TIMES.get(ids.get(j - 1));
                String h = perDay ? dayLabel(t0) : dayLabel(t0) + "  " + HM.format(new java.util.Date(t0)).substring(0, 2) + ":00";
                String sub = (closed ? "已收起" : "浏览于 " + HM.format(new java.util.Date(tl))
                        + (j - i > 1 ? " – " + HM.format(new java.util.Date(t0)) : "")) + " · " + (j - i) + " 条";
                heads.add(new Object[]{out.size(), h, sub, j - i, day, closed});
                out.add(separator(h, sub, day));
            }
            if (t0 == null || !heads.get(heads.size() - 1)[5].equals(Boolean.TRUE)) {
                synchronized (LIVE) {
                    for (int k = i; k < j; k++) out.add(LIVE.get(ids.get(k)));
                }
            }
            i = j;
        }
        if (!out.isEmpty()) out.add(endLabel());
        HEADERS = heads;
        lastCount = out.size();
        return out;
    }

    private static volatile int lastCount = -10;
    private static volatile ArrayList<Object[]> HEADERS = new ArrayList<>();

    /** time groups of the current list: {listIndex, title, subtitle, count} */
    static ArrayList<Object[]> headers() {
        return new ArrayList<>(HEADERS);
    }

    /** scroll our list so that the item at index is at the top (LazyListState.requestScrollToItem) */
    static void jumpTo(int index) {
        try {
            Object st = ourList;
            if (st == null) return;
            st.getClass().getMethod("i", int.class, int.class).invoke(st, index, 0);
        } catch (Throwable t) {
            mod.log(android.util.Log.WARN, TAG, "jump failed: " + t);
        }
    }
    private static volatile Object ourList;   // the LazyListState that shows our list

    /** true while our tab's page is the resumed one (Decompose lifecycle) */
    static boolean ourVisible() {
        try {
            synchronized (OURS) {
                for (Object comp : OURS.keySet()) {
                    Object lc = comp.getClass().getMethod("getLifecycle").invoke(comp);
                    Object st = cl.loadClass("com.arkivanov.essenty.lifecycle.e").getMethod("d").invoke(lc);
                    if ("RESUMED".equals(String.valueOf(st))) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** LazyListState.g(layoutInfo, ..) runs after every scroll/measure: pin the header of the first visible group */
    private static void hookListState() throws Throwable {
        Class<?> state = cl.loadClass("androidx.compose.foundation.lazy.i0");
        Class<?> info = cl.loadClass("androidx.compose.foundation.lazy.u");
        Class<?> vi = cl.loadClass("androidx.compose.foundation.lazy.v");
        final Field fFirst = info.getField("a"), fOff = info.getField("b"), fItems = info.getField("l"), fTotal = info.getField("o"), fVp = info.getField("n");
        final Field fIdx = vi.getField("a"), fKey = vi.getField("l"), fP = vi.getField("p"), fQ = vi.getField("q");
        Method g = null;
        for (Method m : state.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("g") && p.length == 3 && p[0] == info) g = m;
        }
        if (g == null) throw new NoSuchMethodException("LazyListState.g");
        mod.hook(g).intercept(chain -> {
            Object r = chain.proceed();
            try {
                Object self = chain.getThisObject();
                Object u = chain.getArg(0);
                if (self != ourList && ourVisible()) {
                    int total = fTotal.getInt(u);
                    if (total == lastCount || total == lastCount + 1) ourList = self;
                }
                if (self != ourList) {
                    for (Object it : (List<?>) fItems.get(u)) {
                        Object k = fKey.get(it);
                        if (k != null && String.valueOf(k).contains("xtrail-sep-")) { ourList = self; break; }
                    }
                }
                if (self != ourList) return r;
                Object first = fFirst.get(u);
                if (first == null || HEADERS.isEmpty()) { Sticky.hide(); return r; }
                int idx = fIdx.getInt(first), off = fOff.getInt(u);
                Object[] cur = HEADERS.get(0);
                for (Object[] h : HEADERS) { if ((Integer) h[0] <= idx) cur = h; else break; }
                ArrayList<long[]> vis = new ArrayList<>();
                ArrayList<Object[]> rows = new ArrayList<>();
                int endBottom = -1;
                for (Object it : (List<?>) fItems.get(u)) {
                    Object key = fKey.get(it);
                    Long cached = KEYDAY.get(key);
                    if (cached == null) {
                        java.util.regex.Matcher m = SEP.matcher(String.valueOf(key));
                        cached = m.find() ? Long.parseLong(m.group(1)) : String.valueOf(key).contains("xtrail-end") ? Long.MAX_VALUE : Long.MIN_VALUE;
                        if (KEYDAY.size() > 600) KEYDAY.clear();
                        KEYDAY.put(key, cached);
                    }
                    if (cached == Long.MAX_VALUE) { endBottom = fP.getInt(it) + fQ.getInt(it); continue; }
                    if (cached == Long.MIN_VALUE) { rows.add(new Object[]{String.valueOf(key), fP.getInt(it), fP.getInt(it) + fQ.getInt(it)}); continue; }
                    long day = cached;
                    boolean closed;
                    synchronized (COLLAPSED) { closed = COLLAPSED.contains(day); }
                    vis.add(new long[]{day, fP.getInt(it), fQ.getInt(it), closed ? 1 : 0});
                }
                ROWS = rows;
                boolean covered = (Integer) cur[0] == idx && off <= 0;
                // while the header itself is scrolling out, the pinned copy grows from X's label size to its own with it
                int hh = (Integer) cur[0] == idx ? fQ.getInt(first) : 0;
                float grow = hh > 0 ? Math.min(1f, off / (float) hh) : 1f;
                Object[] pinned = covered ? null : new Object[]{cur[4], cur[1], cur[2], cur[5], grow};
                Sticky.update(vis.toArray(new long[0][]), pinned, endBottom, fVp.getInt(u));
            } catch (Throwable t) {
                // never break X
            }
            return r;
        });
    }

    /** number of posts the tab currently shows */
    static int shownCount() {
        return filtered().size();
    }

    /** the native list only spins when empty, so say so with a toast */
    static void hintIfEmpty() {
        if (!filtered().isEmpty()) return;
        final android.app.Activity a = XTrailModule.topActivity();
        if (a == null) return;
        MAIN.post(() -> android.widget.Toast.makeText(a,
                LIVE.isEmpty() ? "暂无足迹" : "没有符合条件的足迹", android.widget.Toast.LENGTH_SHORT).show());
    }

    private static Object buildState() throws Throwable {
        Class<?> b2 = cl.loadClass("com.x.urt.b2");
        Class<?> vType = cl.loadClass("com.x.models.timelines.v");
        Class<?> immutable = cl.loadClass("kotlinx.collections.immutable.b");
        Object type = vType.getField("USER_PROFILE_FAVS").get(null);
        Object idle = cl.loadClass("com.x.urt.x1").getField("a").get(null);
        ArrayList<Object> items;
        try { items = withHeaders(); } catch (Throwable t) { mod.log(android.util.Log.WARN, TAG, "headers failed: " + t); items = filtered(); }
        Constructor<?> ctor = null;
        for (Constructor<?> c : b2.getDeclaredConstructors()) if (c.getParameterTypes().length == 5) ctor = c;
        if (ctor == null) throw new NoSuchMethodException("b2 ctor");
        return ctor.newInstance(type, immutableList(immutable, items), idle, Boolean.TRUE, Boolean.FALSE);
    }

    @SuppressWarnings("unchecked")
    private static Object immutableList(final Class<?> iface, final List<Object> backing) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                if (m.getName().equals("equals") && args != null && args.length == 1) return proxy == args[0];
                Object r = m.invoke(backing, args);
                if (r instanceof List && iface.isAssignableFrom(m.getReturnType())) return immutableList(iface, (List<Object>) r);
                return r;
            }
        });
    }
}
