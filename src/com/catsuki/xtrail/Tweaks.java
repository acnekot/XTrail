package com.catsuki.xtrail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ports of Piko's (https://github.com/crimera/piko, GPLv3, see NOTICE) timeline clean-ups and feature-switch
 * overrides. Piko patches the APK and only knows X 12.19; here the same ideas run as runtime hooks on the
 * current com.x.* model: timeline entries are dropped by entryId prefix when a timeline state is built, and
 * feature switches are overridden in FeatureSwitchesRepositoryImpl.getBoolean.
 */
final class Tweaks {
    /** key, title, subtitle */
    static final String[][] FILTERS = {
            {"ads", "移除广告和推广", "推广帖子、赛事和话题推广位、RTB 广告，并关闭 SSP 广告开关。"},
            {"wtf", "隐藏「推荐关注」", "时间线中的 Who to follow 模块。"},
            {"cts", "隐藏「推荐订阅」", "时间线中的 Who to subscribe 模块。"},
            {"ctj", "隐藏「推荐加入的社群」", "时间线中的 Communities to join 模块。"},
            {"detail", "隐藏详情页推荐内容", "帖子详情页下方的推荐条目。"},
            {"bookmarked", "隐藏书签提示", "时间线中插入的已收藏提示条目。"},
            {"pinned", "隐藏置顶帖子条目", "时间线中额外插入的置顶帖子条目。"},
            {"premium", "隐藏高级版推广", "Premium 提示条目，并关闭启动时的付费墙。"},
            {"people", "隐藏搜索里的热门用户", "搜索结果页顶部的用户模块。"},
            {"news", "隐藏今日新闻", "时间线中的 Today's news 条目。"},
    };

    /** feature switches forced to false while the tweak is on (names are plain strings and survive obfuscation) */
    private static final String[][] SWITCHES = {
            {"ads", "ssp_ads_home_enabled"}, {"ads", "ssp_ads_immersive"}, {"ads", "ssp_ads_profile"},
            {"ads", "ssp_ads_dsp_client_context_enabled"}, {"ads", "ssp_ads_google_dsp_client_context_enabled"},
            {"ads", "android_x_lite_ssp_ads_enabled"},
            {"premium", "premium_paywall_on_app_load_journey_enabled"},
    };

    private static Method entryId;
    private static String customSrc = "";
    private static Map<String, Boolean> custom = new HashMap<>();

    // ------------------------------------------------------------ feature switches

    /** null = leave X's own value */
    static Boolean override(String name) {
        try {
            for (String[] s : SWITCHES) if (s[1].equals(name) && Settings.tweak(s[0])) return Boolean.FALSE;
            String src = Settings.text("flags_custom");
            if (!src.equals(customSrc)) parse(src);
            return custom.get(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** one "name=true|false" per line (also accepts ':' and ',' separators) */
    private static synchronized void parse(String src) {
        Map<String, Boolean> m = new HashMap<>();
        for (String line : src.split("[\n,;]")) {
            String[] kv = line.trim().split("\s*[=:]\s*");
            if (kv.length == 2 && !kv[0].isEmpty()) {
                if (kv[1].equalsIgnoreCase("true")) m.put(kv[0], Boolean.TRUE);
                else if (kv[1].equalsIgnoreCase("false")) m.put(kv[0], Boolean.FALSE);
            }
        }
        custom = m;
        customSrc = src;
    }

    // ------------------------------------------------------------ timeline entries

    /** ids Piko leaves alone no matter what */
    private static boolean protectedId(String head) {
        return head.equals("cursor") || head.equals("Guide") || head.startsWith("semantic_core");
    }

    static boolean drop(String id) {
        if (id == null || id.isEmpty()) return false;
        String[] split = id.split("-");
        String head = split[0];
        if (protectedId(head)) return false;
        if (Settings.tweak("ads")) {
            if (id.contains("promoted") || id.contains("rtb") || id.startsWith("main-event-")) return true;
            if (head.equals("superhero") || head.equals("eventsummary") || head.equals("pivot")) return true;
            if (head.equals("conversationthread") && split.length == 3) return true;
        }
        if (head.startsWith("tweetdetail") && Settings.tweak("detail")) return true;
        if (head.equals("bookmarked") && Settings.tweak("bookmarked")) return true;
        if (id.startsWith("community-to-join") && Settings.tweak("ctj")) return true;
        if (id.startsWith("who-to-follow") && Settings.tweak("wtf")) return true;
        if (id.startsWith("who-to-subscribe") && Settings.tweak("cts")) return true;
        if (id.startsWith("pinned-tweets") && Settings.tweak("pinned")) return true;
        if (id.startsWith("messageprompt-") && Settings.tweak("premium")) return true;
        if (head.equals("toptabsrpusermodule") && Settings.tweak("people")) return true;
        return id.startsWith("stories") && Settings.tweak("news");
    }

    /** hook the timeline state constructor (urt Success: type, items, idle, bool, bool) and filter its item list */
    static void install(final XTrailModule mod, final ClassLoader cl) throws Throwable {
        final Class<?> success = Symbols.load(cl, "com.x.urt.b2");
        final Class<?> item = Symbols.load(cl, "com.x.models.timelines.items.o0");
        final Class<?> immutable = Symbols.load(cl, "kotlinx.collections.immutable.b");
        entryId = item.getMethod("c");
        final Object favs = Symbols.load(cl, "com.x.models.timelines.u").getField("USER_PROFILE_FAVS").get(null);
        Constructor<?> ctor = null;
        for (Constructor<?> c : success.getDeclaredConstructors()) if (c.getParameterTypes().length == 5) ctor = c;
        if (ctor == null) throw new NoSuchMethodException("Success ctor");
        mod.hook(ctor).intercept(chain -> {
            Object[] a = null;
            try {
                if (Settings.anyTweak(FILTERS)) {
                    Object[] args = chain.getArgs().toArray();
                    if (args[0] != favs && args[1] instanceof List) {
                        List<?> in = (List<?>) args[1];
                        ArrayList<Object> out = new ArrayList<>(in.size());
                        for (Object o : in) {
                            String id = null;
                            if (item.isInstance(o)) {
                                try { id = (String) entryId.invoke(o); } catch (Throwable ignored) { }
                            }
                            if (!drop(id)) out.add(o);
                        }
                        if (out.size() != in.size()) {
                            args[1] = NativeTab.immutableList(immutable, out);
                            a = args;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return a != null ? chain.proceed(a) : chain.proceed();
        });
    }
}
