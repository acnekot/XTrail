package com.catsuki.xtrail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨版本符号表。
 *
 * X（com.twitter.android）是 R8 混淆的，同一个短名在版本之间会被重新分配给别的类：
 * 12.37.2 的 com.x.urt.b2 是 Success 状态，12.38.1 的同一个名字变成了 Loading 单例。
 * 所以「类能 loadClass」不代表「还是那个类」，必须再验一次结构。
 *
 * 这里给每个锚点存一串候选名，按顺序 loadClass 并校验，用第一个通过的。
 * 新版本适配时只需要在 add(...) 后面追加一个名字，其余逻辑不用动。
 * 全部候选都不通过时退回到「第一个能加载的」，行为不会比硬编码更差。
 */
final class Symbols {
    private static final Map<String, String[]> CANDIDATES = new HashMap<>();
    private static final Map<String, String> ALIAS = new HashMap<>();
    private static boolean aliasBuilt;
    private static final Map<String, Class<?>> CACHE = new HashMap<>();
    private static final List<String> FAILED = new ArrayList<>();
    private static XposedLog log;

    interface XposedLog {
        void w(String msg);
    }

    static void setLogger(XposedLog l) {
        log = l;
    }

    /** key 是 12.37.2 里的名字，后面的参数依次是各版本的名字（新版本追加在后面） */
    private static void add(String key, String... names) {
        CANDIDATES.put(key, names);
    }

    static {
        // ---- 应用骨架 / 底栏 ----
        add("com.x.ui.common.n2", "com.x.ui.common.k2", "com.x.ui.common.p2", "com.x.ui.common.t2");

        // ---- 特性开关（X 未混淆，名字稳定，登记只为统一入口） ----
        add("com.x.featureswitches.FeatureSwitchesRepositoryImpl");

        // ---- 模型 ----
        add("com.x.models.s5", "com.x.models.w5", "com.x.models.t5", "com.x.models.u5");
        add("com.x.models.u0", "com.x.models.v0");
        add("com.x.models.eg", "com.x.models.fg");
        add("com.x.models.n6");
        add("com.x.models.cf");
        add("com.x.models.f7");
        add("com.x.models.od", "com.x.models.pd");
        add("com.x.models.timelines.v", "com.x.models.timelines.u");

        // ---- 时间线状态 ----
        add("com.x.urt.b2", "com.x.urt.f2", "com.x.urt.g2", "com.x.urt.e2");
        add("com.x.urt.x1", "com.x.urt.d2", "com.x.urt.c2", "com.x.urt.e2");
        add("com.x.repositories.post.actions.i0", "com.x.repositories.post.actions.h0");

        // ---- 时间线条目 ----
        add("com.x.models.timelines.items.o0", "com.x.models.timelines.items.p0");
        add("com.x.models.timelines.items.l1", "com.x.models.timelines.items.j1", "com.x.models.timelines.items.k1");
        add("com.x.models.timelines.items.v0", "com.x.models.timelines.items.u0");

        // ---- 历史页导航（Pages 的三种配置） ----
        add("com.x.navigation.u9", "com.x.navigation.da", "com.x.navigation.v9", "com.x.navigation.g9");
        add("com.x.navigation.w9", "com.x.navigation.fa", "com.x.navigation.x9", "com.x.navigation.i9");
        add("com.x.navigation.x9", "com.x.navigation.ga", "com.x.navigation.y9", "com.x.navigation.j9");

        // ---- 详情页参数 ----
        add("com.x.navigation.fl", "com.x.navigation.nl", "com.x.navigation.qk", "com.x.navigation.dm");
        add("com.x.navigation.il", "com.x.navigation.ql", "com.x.navigation.tk", "com.x.navigation.gm");
        add("com.x.navigation.ll", "com.x.navigation.tl");

        // ---- 标签描述符 / 插槽 / 图标 ----
        add("com.x.ui.common.tabs.s", "com.x.ui.common.tabs.r", "com.x.ui.common.tabs.t");
        add("com.x.ui.common.tabs.f0", "com.x.ui.common.tabs.e0", "com.x.ui.common.tabs.g0");
        add("com.x.ui.common.tabs.v", "com.x.ui.common.tabs.u", "com.x.ui.common.tabs.w");
        add("com.x.ui.common.tabs.u", "com.x.ui.common.tabs.t", "com.x.ui.common.tabs.v");
        add("com.x.icons.a");

        // ---- 历史页组件 ----
        add("com.x.bookmarks.history.c");
        add("com.x.bookmarks.history.q", "com.x.bookmarks.history.p", "com.x.bookmarks.history.r");
        add("com.x.bookmarks.history.u", "com.x.bookmarks.history.t", "com.x.bookmarks.history.v");
        add("com.x.bookmarks.history.x1", "com.x.bookmarks.history.p1", "com.x.bookmarks.history.s1", "com.x.bookmarks.history.z1", "com.x.bookmarks.history.y1");

        // ---- 第三方库（同样被 X 的 R8 重排） ----
        add("kotlinx.coroutines.flow.b0", "kotlinx.coroutines.flow.b3", "kotlinx.coroutines.flow.b0", "kotlinx.coroutines.flow.a0");
        add("kotlinx.serialization.json.c");
        add("kotlinx.serialization.KSerializer");
        add("kotlinx.serialization.a");
        add("kotlinx.collections.immutable.b");
        add("androidx.compose.foundation.lazy.i0", "androidx.compose.foundation.lazy.g0", "androidx.compose.foundation.lazy.i0");
        add("androidx.compose.foundation.lazy.u", "androidx.compose.foundation.lazy.s", "androidx.compose.foundation.lazy.u");
        add("androidx.compose.foundation.lazy.v", "androidx.compose.foundation.lazy.t", "androidx.compose.foundation.lazy.v");
        add("com.arkivanov.decompose.router.pages.f", "com.arkivanov.decompose.router.pages.h");
        add("com.arkivanov.essenty.lifecycle.e");

        // ---- 时间线组件（用于按来源分类记录） ----
        add("com.x.home.foryou.w");
        add("com.x.home.following.l", "com.x.home.following.m");
        add("com.x.home.subscribed.f");
        add("com.x.home.topic.d");
        add("com.x.search.result.a0");
        add("com.x.profile.timeline.h");
        add("com.x.profile.k2", "com.x.profile.j2");
        add("com.x.notifications.tab.q0", "com.x.notifications.tab.n0");
        add("com.x.notifications.detail.c");
        add("com.x.list.w", "com.x.list.u");
        add("com.x.list.detail.s");
        add("com.x.list.detail.l", "com.x.list.detail.k");
        add("com.x.list.detail.c");
        add("com.x.communities.impl.postsearch.r", "com.x.communities.impl.postsearch.s");
        add("com.x.communities.impl.detail.e0");
        add("com.x.communities.impl.detail.c0", "com.x.communities.impl.detail.g0");
        add("com.x.urt.generictimeline.c", "com.x.urt.generictimeline.b");
        add("com.x.video.tab.z1", "com.x.video.tab.w1");
        add("com.x.postdetail.n", "com.x.postdetail.m");
    }

    // ------------------------------------------------------------------ 解析

    /** 按符号表解析类；候选都失败时退回第一个能加载的名字 */
    static Class<?> load(ClassLoader cl, String key) throws ClassNotFoundException {
        aliasOfEveryCandidate();
        final String k = ALIAS.getOrDefault(key, key);
        synchronized (CACHE) {
            Class<?> hit = CACHE.get(k);
            if (hit != null) return hit;
        }
        // key 本身（12.37.2 的名字）永远排在最前面，候选按新旧顺序跟在后面
        String[] extra = CANDIDATES.get(k);
        String[] names;
        if (extra == null || extra.length == 0) {
            names = new String[]{k};
        } else {
            names = new String[extra.length + 1];
            names[0] = k;
            System.arraycopy(extra, 0, names, 1, extra.length);
        }
        Class<?> firstLoadable = null;
        for (String name : names) {
            Class<?> c;
            try {
                c = Class.forName(name, false, cl);
            } catch (Throwable t) {
                continue;
            }
            if (firstLoadable == null) firstLoadable = c;
            if (ok(k, c)) {
                return remember(k, name, c);
            }
        }
        if (firstLoadable != null) {
            record(k, "结构校验没过，退回 " + firstLoadable.getName());
            return remember(k, firstLoadable.getName(), firstLoadable);
        }
        record(k, "所有候选都加载失败");
        throw new ClassNotFoundException(k);
    }

    /** 调用点写哪个版本的名字都能落到同一组候选上 */
    private static void aliasOfEveryCandidate() {
        if (aliasBuilt) return;
        // 先登记所有 key：某个名字既是旧 key、又是另一个锚点的新名字时（tabs.v→u 和 tabs.u→t），
        // key 优先，否则两个锚点会被解析到同一组候选上
        for (String k : CANDIDATES.keySet()) ALIAS.put(k, k);
        for (Map.Entry<String, String[]> e : CANDIDATES.entrySet())
            for (String n : e.getValue()) ALIAS.putIfAbsent(n, e.getKey());
        aliasBuilt = true;
    }

    private static Class<?> remember(String key, String name, Class<?> c) {
        synchronized (CACHE) {
            CACHE.put(key, c);
        }
        if (!key.equals(name)) record(key, "-> " + name);
        return c;
    }

    private static void record(String key, String msg) {
        if (log != null) log.w(key + " " + msg);
        FAILED.add(key + " " + msg);
    }

    static List<String> report() {
        synchronized (FAILED) {
            return new ArrayList<>(FAILED);
        }
    }

    // ------------------------------------------------------------------ 校验

    /** 结构特征：能 loadClass 到同名类但内容已经换掉的版本，靠这里挡下来 */
    private static boolean ok(String key, Class<?> c) {
        switch (key) {
            case "com.x.ui.common.n2": {          // scaffold：静态 a(...) 11 参，5/6/7 位是 boolean
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("a") && Modifier.isStatic(m.getModifiers()) && p.length == 11
                            && p[5] == boolean.class && p[6] == boolean.class && p[7] == boolean.class) return true;
                }
                return false;
            }
            case "com.x.models.s5":               // 推文接口：getUrl + getText
                return hasMethod(c, "getUrl", 0) && hasMethod(c, "getText", 0);

            case "com.x.urt.b2": {                // Success 状态：5 字段 5 参构造
                Constructor<?>[] cs = c.getDeclaredConstructors();
                return c.getDeclaredFields().length == 5 && cs.length > 0 && cs[0].getParameterTypes().length == 5;
            }
            case "com.x.repositories.post.actions.i0": {   // 点赞队列仓库：有静态 c(自己, 帖子, 动作类型, String, MapBuilder, f6, int)
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("c") && Modifier.isStatic(m.getModifiers()) && p.length == 7 && p[0] == c
                            && p[4].getName().endsWith("MapBuilder") && p[2].isEnum()) return true;
                }
                return false;
            }
            case "com.x.urt.x1": {                // Idle 单例：静态字段 a 是自己类型，且实现 Success 状态构造函数第 3 个参数的接口
                if (c.getDeclaredFields().length != 1 || !hasStaticSelf(c)) return false;
                try {
                    Class<?> success = load(c.getClassLoader(), "com.x.urt.b2");
                    for (Constructor<?> ct : success.getDeclaredConstructors()) {
                        Class<?>[] p = ct.getParameterTypes();
                        if (p.length == 5 && p[2].isAssignableFrom(c)) return true;
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }

            case "com.x.models.timelines.v":      // 时间线类型枚举：有 USER_PROFILE_FAVS（枚举常量名不混淆）；
                // 12.38.1 里旧名 timelines.v 还在，但已是别的类，必须靠这里挡下
                return c.isEnum() && hasStaticField(c, "USER_PROFILE_FAVS");

            case "com.x.models.timelines.items.l1": // 时间线条目：有 long 字段 b
                return hasField(c, "b", long.class);
            case "com.x.models.timelines.items.v0": // 分组标题条目：od + long + String + 第四参数
                for (Constructor<?> ct : c.getDeclaredConstructors()) {
                    Class<?>[] p = ct.getParameterTypes();
                    if (p.length == 4 && p[1] == long.class && p[2] == String.class) return true;
                }
                return false;

            case "com.x.navigation.u9": {         // Bookmarks 页配置：单个非静态字段，类型来自 models.bookmarks
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())
                            && f.getType().getName().contains("models.bookmarks.")) return true;
                }
                return false;
            }
            case "com.x.navigation.w9":           // Likes 页配置：单例，toString 就是 "Likes"
                return "Likes".equals(describe(c));
            case "com.x.navigation.x9":           // OfflineVideos 页配置：单例
                return "OfflineVideos".equals(describe(c));

            case "com.x.navigation.fl": {         // FromId：q() 返回 long，且有 (long, ?, ?, int) 构造
                if (!hasMethod(c, "q", 0)) return false;
                boolean ctor = false;
                for (Constructor<?> ct : c.getDeclaredConstructors()) {
                    Class<?>[] p = ct.getParameterTypes();
                    if (p.length == 4 && p[0] == long.class && p[3] == int.class) ctor = true;
                }
                if (!ctor) return false;
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().equals("q") && m.getReturnType() == long.class) return true;
                return false;
            }
            case "com.x.navigation.il": {         // FromPostResult：q() 返回 long，且有 5 参、末位 Long 的构造
                if (!hasMethod(c, "q", 0)) return false;
                boolean ctor = false;
                for (Constructor<?> ct : c.getDeclaredConstructors()) {
                    Class<?>[] p = ct.getParameterTypes();
                    if (p.length == 5 && p[4] == Long.class) ctor = true;
                }
                if (!ctor) return false;
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().equals("q") && m.getReturnType() == long.class) return true;
                return false;
            }

            case "com.x.ui.common.tabs.s": {      // RichTabDescriptor：6 字段 6 参构造，末位 boolean
                Constructor<?>[] cs = c.getDeclaredConstructors();
                if (c.getDeclaredFields().length != 6) return false;
                for (Constructor<?> ct : cs) {
                    Class<?>[] p = ct.getParameterTypes();
                    if (p.length == 6 && p[5] == boolean.class) return true;
                }
                return false;
            }
            case "com.x.ui.common.tabs.f0":       // RichTabSlot：element + boolean
                return c.getDeclaredFields().length == 2 && hasFieldType(c, boolean.class);
            case "com.x.ui.common.tabs.u":        // Icon：单字段，构造参数不是 String
                return c.getDeclaredFields().length == 1 && !String.class.equals(c.getDeclaredFields()[0].getType());
            case "com.x.ui.common.tabs.v":        // Label：单 String 字段
                return c.getDeclaredFields().length == 1 && String.class.equals(c.getDeclaredFields()[0].getType());

            case "com.x.bookmarks.history.x1":    // TabReClicked：int 字段
                return c.getDeclaredFields().length == 1 && hasFieldType(c, int.class);
            case "com.x.models.timelines.items.o0": // 条目接口：c()/m()/s() 三个无参方法
                return c.isInterface() && (hasMethod(c, "c", 0) || hasMethod(c, "a", 0)) && hasMethod(c, "m", 0) && hasMethod(c, "s", 0);
            case "com.x.models.od":                // TimelineMessagePrompt：(String, String, ?) 构造
                for (Constructor<?> ct : c.getDeclaredConstructors()) {
                    Class<?>[] p = ct.getParameterTypes();
                    if (p.length == 3 && p[0] == String.class && p[1] == String.class) return true;
                }
                return false;
            case "com.x.bookmarks.history.q":      // 历史页容器：体量大（几十个成员），同名的占位类只有几个
                return c.getDeclaredMethods().length + c.getDeclaredFields().length >= 15;
            case "com.x.bookmarks.history.u":      // Likes 时间线组件：d() 无参 + 体量大
                return hasMethod(c, "d", 0) && c.getDeclaredMethods().length + c.getDeclaredFields().length >= 15;
            case "androidx.compose.foundation.lazy.i0": { // LazyListState：g(布局信息, boolean, boolean)
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("g") && p.length == 3 && p[1] == boolean.class && p[2] == boolean.class) return true;
                }
                return false;
            }
            case "androidx.compose.foundation.lazy.u":   // 布局结果：items 列表 l + 总数 o
                return hasField(c, "l", java.util.List.class) && hasField(c, "o", int.class) && hasField(c, "n", int.class);
            case "androidx.compose.foundation.lazy.v":   // 条目信息：下标 a + key l + p/q
                return hasField(c, "a", int.class) && hasField(c, "l", Object.class) && hasField(c, "p", int.class);
            case "com.arkivanov.decompose.router.pages.f": // Pages：(int, List) 构造
                for (Constructor<?> ct : c.getDeclaredConstructors()) {
                    Class<?>[] p = ct.getParameterTypes();
                    if (p.length == 2 && p[0] == int.class && java.util.List.class.isAssignableFrom(p[1])) return true;
                }
                return false;
            case "com.x.bookmarks.history.c":     // 子工厂：Function2
                return hasMethod(c, "invoke", 2);

            case "kotlinx.coroutines.flow.b0": {  // StateFlowKt：静态 c(Object) 返回 flow
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().equals("c") && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Object.class && hasMethod(m.getReturnType(), "getValue", 0)) return true;
                return false;
            }

            default:
                return true;
        }
    }

    private static boolean hasMethod(Class<?> c, String name, int params) {
        for (Method m : c.getDeclaredMethods())
            if (m.getName().equals(name) && m.getParameterTypes().length == params) return true;
        return false;
    }

    private static boolean hasField(Class<?> c, String name, Class<?> type) {
        for (Field f : c.getDeclaredFields())
            if (f.getName().equals(name) && f.getType() == type) return true;
        return false;
    }

    private static boolean hasStaticSelf(Class<?> c) {
        for (Field f : c.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) return true;
        return false;
    }

    private static boolean hasStaticField(Class<?> c, String name) {
        for (Field f : c.getDeclaredFields())
            if (f.getName().equals(name) && Modifier.isStatic(f.getModifiers())) return true;
        return false;
    }

    private static boolean hasFieldType(Class<?> c, Class<?> type) {
        for (Field f : c.getDeclaredFields()) if (f.getType() == type) return true;
        return false;
    }

    private static boolean hasStaticInstance(Class<?> c) {
        for (Field f : c.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) return true;
        return false;
    }

    /** 单例的 toString，用来区分那些名字会被复用、结构又长得一样的配置对象 */
    static String describe(Class<?> c) {
        try {
            Object o = c.getField("INSTANCE").get(null);
            return String.valueOf(o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 方法定位

    /**
     * 时间线组件的渲染方法：形如 v(int, item) / u(int, item)。
     * 方法名本身也会被 R8 重排（12.37.2 是 v，12.38.1 是 u），所以按参数形状找，不看名字。
     */
    static Method timelineMethod(Class<?> c, Class<?> item) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == int.class && p[1] == item) return m;
        }
        return null;
    }

    /** 事件处理方法：找 1 个参数、参数类型被 event 实现的方法 */
    static Method eventMethod(Class<?> c, String preferred, Class<?> event) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(preferred) && p.length == 1 && p[0].isAssignableFrom(event)) return m;
        }
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(event) && p[0] != Object.class) return m;
        }
        return null;
    }
}
