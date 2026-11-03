package com.catsuki.xtrail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Piko's "hide view count" and "bookmark in timeline", re-done for the current inline action bar.
 * The bar state (InlineActionBarState) holds an immutable list of InlineActionEntry(actionType, count, isEnabled);
 * we hook the state constructor, drop the ViewCount entry and optionally add a bookmark entry. Classes are found
 * by shape because their R8 names change between releases; the action type is matched by enum constant name.
 */
final class ActionBar {
    private static final String PKG = "com.x.inlineactionbar.";

    private static Field typeField;
    private static Constructor<?> entryCtor;
    private static Class<?> typeEnum;

    private static Class<?> findState(ClassLoader cl, Class<?> immutable) {
        Class<?> found = null;
        for (int i = 0; i < 26 * 101 + 26; i++) {
            String n = i < 26 ? String.valueOf((char) ('a' + i)) : ((char) ('a' + (i - 26) / 101)) + String.valueOf((i - 26) % 101);
            Class<?> c;
            try { c = Class.forName(PKG + n, false, cl); } catch (Throwable t) { continue; }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                Class<?>[] p = k.getParameterTypes();
                if (p.length == 5 && p[0] == immutable && p[1] == String.class && p[3] == boolean.class
                        && p[4].getName().equals("kotlin.jvm.functions.Function1")
                        && java.lang.reflect.Modifier.isAbstract(p[2].getModifiers())
                        && p[2].getName().startsWith(PKG)) {
                    if (found != null && found != c) return null;     // ambiguous
                    found = c;
                }
            }
        }
        return found;
    }

    private static boolean learn(Object entry) {
        if (typeField != null) return typeField.getDeclaringClass() == entry.getClass();
        for (Field f : entry.getClass().getDeclaredFields()) {
            if (f.getType().isEnum()) {
                f.setAccessible(true);
                typeField = f;
                typeEnum = f.getType();
                for (Constructor<?> c : entry.getClass().getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 3 && p[0] == typeEnum && p[1] == Long.class && p[2] == boolean.class) entryCtor = c;
                }
                return true;
            }
        }
        return false;
    }

    private static String type(Object entry) {
        try {
            if (!learn(entry)) return null;
            return ((Enum<?>) typeField.get(entry)).name();
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> rewrite(List<?> in) {
        boolean hideViews = Settings.tweak("views"), addMark = Settings.tweak("bookmarks");
        if (!hideViews && !addMark) return null;
        ArrayList<Object> out = new ArrayList<>(in.size());
        boolean hasMark = false, hasReply = false;
        int share = -1;
        for (Object o : in) {
            String t = o == null ? null : type(o);
            if (t != null) {
                if (hideViews && t.equals("ViewCount")) continue;
                if (t.equals("AddToBookmarks") || t.equals("RemoveFromBookmarks")) hasMark = true;
                if (t.equals("Reply")) hasReply = true;
                if (t.equals("Share")) share = out.size();
            }
            out.add(o);
        }
        if (addMark && !hasMark && hasReply && entryCtor != null && typeEnum != null) {
            try {
                Object e = entryCtor.newInstance(Enum.valueOf((Class) typeEnum, "AddToBookmarks"), null, Boolean.TRUE);
                out.add(share >= 0 ? share : out.size(), e);
            } catch (Throwable ignored) {
            }
        }
        return out.size() == in.size() && !(addMark && !hasMark) ? null : out;
    }

    static void install(final XTrailModule mod, final ClassLoader cl) throws Throwable {
        final Class<?> immutable = Symbols.load(cl, "kotlinx.collections.immutable.b");
        final Class<?> state = findState(cl, immutable);
        if (state == null) throw new ClassNotFoundException("inline action bar state");
        Constructor<?> ctor = null;
        for (Constructor<?> k : state.getDeclaredConstructors()) if (k.getParameterTypes().length == 5) ctor = k;
        mod.hook(ctor).intercept(chain -> {
            Object[] a = null;
            try {
                Object[] args = chain.getArgs().toArray();
                if (args[0] instanceof List) {
                    List<Object> out = rewrite((List<?>) args[0]);
                    if (out != null) {
                        args[0] = NativeTab.immutableList(immutable, out);
                        a = args;
                    }
                }
            } catch (Throwable ignored) {
            }
            return a != null ? chain.proceed(a) : chain.proceed();
        });
    }
}
