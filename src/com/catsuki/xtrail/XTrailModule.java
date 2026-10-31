package com.catsuki.xtrail;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

/**
 * XTrail for X (com.twitter.android) 12.37.2.
 * 1) pins the bottom navigation bar (never slides away on scroll),
 * 2) records every post that is composed in a timeline (home / search / profile / ...),
 *    plus posts opened from a timeline ("open") or by link/notification ("jump"),
 * 3) adds a native third tab to X's History screen (see NativeTab).
 */
public class XTrailModule extends XposedModule {
    private static final String TAG = "XTrail";
    private static final String TARGET = "com.twitter.android";

    /** timeline component implementation -> category (class names are specific to X 12.37.2). */
    private static final String[][] TIMELINES = {
            {"com.x.home.foryou.w", "home"},
            {"com.x.home.following.l", "home"},
            {"com.x.home.subscribed.f", "home"},
            {"com.x.home.topic.d", "home"},
            {"com.x.search.result.a0", "search"},
            {"com.x.profile.timeline.h", "profile"},
            {"com.x.profile.k2", "profile"},
            {"com.x.notifications.tab.q0", "notif"},
            {"com.x.notifications.detail.c", "notif"},
            {"com.x.list.w", "list"},
            {"com.x.list.detail.s", "list"},
            {"com.x.list.detail.l", "list"},
            {"com.x.list.detail.c", "list"},
            {"com.x.communities.impl.postsearch.r", "comm"},
            {"com.x.communities.impl.detail.e0", "comm"},
            {"com.x.communities.impl.detail.c0", "comm"},
            {"com.x.urt.generictimeline.c", "other"},
            {"com.x.video.tab.z1", "other"},
            {"com.x.postdetail.n", "detail"},
    };

    private static final Pattern URL = Pattern.compile("(?:x|twitter)\\.com/([^/?#]+)/status/(\\d+)");

    private static volatile WeakReference<Activity> top = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Class<?> postIface;                       // com.x.models.s5 (getText/getUrl)
    private final Map<Class<?>, Field[]> postFields = new HashMap<>();

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        ClassLoader cl = param.getClassLoader();
        log(android.util.Log.INFO, TAG, "loaded into " + param.getPackageName());
        step("activity", this::hookActivity);
        step("switch", () -> hookSwitch(cl));
        step("pin-bottom-bar", () -> hookPin(cl));
        step("detail-recorder", () -> hookDetail(cl));
        step("timeline-recorder", () -> hookTimelines(cl));
        step("native-tab", () -> NativeTab.install(this, cl));
    }

    private interface Step { void run() throws Throwable; }

    private void step(String name, Step s) {
        try {
            s.run();
            log(android.util.Log.INFO, TAG, "hook ok: " + name);
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "hook FAILED: " + name, t);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private void hookActivity() throws Throwable {
        Method onResume = Activity.class.getDeclaredMethod("onResume");
        hook(onResume).intercept(chain -> {
            Object r = chain.proceed();
            Object o = chain.getThisObject();
            if (o instanceof Activity) {
                top = new WeakReference<>((Activity) o);
                openSettingsIfAsked((Activity) o, ((Activity) o).getIntent());
            }
            return r;
        });
        // long press on a post of the footprint list shows its view time; the touch is never consumed
        hook(Activity.class.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent.class)).intercept(chain -> {
            try {
                Object o = chain.getThisObject();
                if (o instanceof Activity && chain.getArg(0) instanceof android.view.MotionEvent) {
                    if (LongPress.onTouch((Activity) o, (android.view.MotionEvent) chain.getArg(0))) return true;
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });
        // result of the file picker used by the history import
        hook(Activity.class.getDeclaredMethod("onActivityResult", int.class, int.class, android.content.Intent.class)).intercept(chain -> {
            Object r = chain.proceed();
            try {
                Object o = chain.getThisObject();
                if (o instanceof Activity && (Integer) chain.getArg(0) == Transfer.REQ_IMPORT && (Integer) chain.getArg(1) == Activity.RESULT_OK) {
                    Transfer.onResult((Activity) o, (android.content.Intent) chain.getArg(2));
                }
            } catch (Throwable ignored) {
            }
            return r;
        });
        // X already running: the launcher entry arrives as a new intent
        hook(Activity.class.getDeclaredMethod("onNewIntent", android.content.Intent.class)).intercept(chain -> {
            Object r = chain.proceed();
            Object o = chain.getThisObject();
            if (o instanceof Activity && chain.getArg(0) instanceof android.content.Intent) {
                openSettingsIfAsked((Activity) o, (android.content.Intent) chain.getArg(0));
            }
            return r;
        });
    }

    /** the module's launcher icon opens X with this flag: show the XTrail settings page once */
    private static void openSettingsIfAsked(final Activity a, android.content.Intent i) {
        if (i == null || !i.getBooleanExtra("xtrail_open_settings", false)) return;
        i.removeExtra("xtrail_open_settings");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> SettingsDialog.show(a), 400);
    }

    static Activity topActivity() {
        return top.get();
    }

    private static Context appContext() {
        try {
            Activity a = top.get();
            if (a != null) return a.getApplicationContext();
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 1) native History row

    private void hookSwitch(ClassLoader cl) throws Throwable {
        Class<?> impl = cl.loadClass("com.x.featureswitches.FeatureSwitchesRepositoryImpl");
        Method m = impl.getDeclaredMethod("getBoolean", String.class, boolean.class);
        hook(m).intercept(chain -> {
            Object r = chain.proceed();
            if ("android_history_enabled".equals(chain.getArg(0))) return Boolean.TRUE;
            return r;
        });
    }

    // ---------------------------------------------------------------- 2) pin bottom bar

    /**
     * com.x.ui.common.n2.a(...) is the app scaffold. The bar is shown when (!p6 && (p5 || ...)):
     * p6 true collapses it to zero height (confirmed on device), so force p5=true and p6=false.
     */
    private void hookPin(ClassLoader cl) throws Throwable {
        Class<?> scaffold = cl.loadClass("com.x.ui.common.n2");
        Method target = null;
        for (Method m : scaffold.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("a") && Modifier.isStatic(m.getModifiers()) && p.length == 11
                    && p[5] == boolean.class && p[6] == boolean.class && p[7] == boolean.class) {
                target = m;
                break;
            }
        }
        if (target == null) throw new NoSuchMethodException("n2.a scaffold not found");
        hook(target).intercept(chain -> {
            if (!Settings.pinBar()) return chain.proceed();
            Object[] a = chain.getArgs().toArray();
            a[5] = Boolean.TRUE;
            a[6] = Boolean.FALSE;
            return chain.proceed(a);
        });
    }

    // ---------------------------------------------------------------- 3) recording

    private void initPostIface(ClassLoader cl) throws ClassNotFoundException {
        if (postIface == null) postIface = cl.loadClass("com.x.models.s5");
    }

    /** First field of holder whose value is a post (implements s5). */
    private Object findPost(Object holder) {
        if (holder == null) return null;
        try {
            Class<?> k = holder.getClass();
            Field[] fs;
            synchronized (postFields) {
                fs = postFields.get(k);
                if (fs == null) {
                    java.util.ArrayList<Field> l = new java.util.ArrayList<>();
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (postIface.isAssignableFrom(f.getType()) || f.getType().isInterface()
                                || !f.getType().isPrimitive()) {
                            f.setAccessible(true);
                            l.add(f);
                        }
                    }
                    fs = l.toArray(new Field[0]);
                    postFields.put(k, fs);
                }
            }
            for (Field f : fs) {
                Object v = f.get(holder);
                if (v != null && postIface.isInstance(v)) return v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** returns {id, handle, text, "1" when the post is a reply} or null */
    private String[] describe(Object post) {
        if (post == null) return null;
        try {
            String url = null, text = null;
            try { url = (String) postIface.getMethod("getUrl").invoke(post); } catch (Throwable ignored) { }
            try { text = (String) postIface.getMethod("getText").invoke(post); } catch (Throwable ignored) { }
            if (url == null) return null;
            Matcher m = URL.matcher(url);
            if (!m.find()) return null;
            if (text != null && text.length() > 400) text = text.substring(0, 400);
            boolean reply = false;
            try {
                for (Method mm : postIface.getMethods()) {
                    if (mm.getParameterTypes().length != 0) continue;
                    Object r = mm.invoke(post);
                    if (r != null && r.getClass().getSimpleName().contains("ReplyContext")) { reply = true; break; }
                }
            } catch (Throwable ignored) {
            }
            return new String[]{m.group(2), m.group(1), text == null ? "" : text, reply ? "1" : ""};
        } catch (Throwable t) {
            return null;
        }
    }

    private void recordItem(String cat, Object timelineItem) {
        try {
            if (!Settings.recordCat(cat)) return;
            Context c = appContext();
            if (c == null) return;
            Object post = findPost(timelineItem);
            String[] d = describe(post);
            if (d == null) return;
            NativeTab.onItem(cat, timelineItem, Long.parseLong(d[0]), d[1], d[2], !d[3].isEmpty());
            HistoryStore.add(c, cat, Long.parseLong(d[0]), d[1], d[2]);
        } catch (Throwable t) {
            // never break X
        }
    }

    private void hookTimelines(ClassLoader cl) throws Throwable {
        initPostIface(cl);
        Class<?> item = cl.loadClass("com.x.models.timelines.items.o0");
        int ok = 0;
        for (String[] e : TIMELINES) {
            final String cat = e[1];
            try {
                Class<?> impl = cl.loadClass(e[0]);
                Method v = impl.getDeclaredMethod("v", int.class, item);
                hook(v).intercept(chain -> {
                    recordItem(cat, chain.getArg(1));
                    return chain.proceed();
                });
                ok++;
            } catch (Throwable t) {
                log(android.util.Log.WARN, TAG, "timeline hook skipped: " + e[0] + " " + t);
            }
        }
        log(android.util.Log.INFO, TAG, "timeline hooks: " + ok + "/" + TIMELINES.length);
    }

    private void hookDetail(ClassLoader cl) throws Throwable {
        initPostIface(cl);
        // PostDetailArgs.FromId: opened by id (link, notification, share) -> "jump"
        Class<?> fromId = cl.loadClass("com.x.navigation.fl");
        Class<?> intentAction = cl.loadClass("com.x.navigation.ll");
        Constructor<?> c1 = fromId.getDeclaredConstructor(long.class, boolean.class, intentAction, int.class);
        // PostDetailArgs.FromPostResult: opened from a timeline item -> "open"
        Class<?> fromResult = cl.loadClass("com.x.navigation.il");
        Constructor<?> c2 = fromResult.getDeclaredConstructor(
                cl.loadClass("com.x.models.u0"), cl.loadClass("com.x.models.n6"),
                cl.loadClass("com.x.models.cf"), cl.loadClass("com.x.models.f7"), Long.class);
        final Method q1 = fromId.getMethod("q");
        final Method q2 = fromResult.getMethod("q");
        hook(c1).intercept(chain -> {
            Object r = chain.proceed();
            try {
                Context c = appContext();
                long id = ((Number) q1.invoke(chain.getThisObject())).longValue();
                if (c != null) HistoryStore.add(c, "jump", id, "", "");
                NativeTab.noteOpened(id);
            } catch (Throwable ignored) {
            }
            return r;
        });
        hook(c2).intercept(chain -> {
            Object r = chain.proceed();
            try {
                Context c = appContext();
                Object args = chain.getThisObject();
                long id = ((Number) q2.invoke(args)).longValue();
                String[] d = describe(findPost(args));
                if (c != null) HistoryStore.add(c, "open", id, d == null ? "" : d[1], d == null ? "" : d[2]);
                NativeTab.noteOpened(id);
            } catch (Throwable ignored) {
            }
            return r;
        });
    }
}
