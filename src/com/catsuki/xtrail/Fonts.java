package com.catsuki.xtrail;

import android.content.res.AssetManager;
import android.graphics.Typeface;

import java.lang.reflect.Method;

/**
 * Piko's "disable Chirp font". X now always ships Chirp as res/font/chirp_*.otf, so the font files are intercepted
 * when the framework loads them (Typeface.createFromResources) and replaced with the system font of the same weight.
 */
final class Fonts {
    static void install(final XTrailModule mod) throws Throwable {
        Method m = Typeface.class.getDeclaredMethod("createFromResources", AssetManager.class, String.class, int.class);
        mod.hook(m).intercept(chain -> {
            try {
                Object p = chain.getArg(1);
                if (p instanceof String && ((String) p).contains("/chirp_") && Settings.tweak("font")) {
                    java.util.regex.Matcher w = java.util.regex.Pattern.compile("_(\\d{3})\\.").matcher((String) p);
                    int weight = w.find() ? Integer.parseInt(w.group(1)) : 400;
                    return Typeface.create(Typeface.DEFAULT, weight, false);
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });
    }
}
