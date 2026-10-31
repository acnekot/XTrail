package com.catsuki.xtrail;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;

/**
 * X-style palette for the views the module draws itself. X has its own light/dark setting that can differ from the
 * system's, so the real theme is read from the pixels of the window (see {@link #refresh}); the system setting is only
 * the fallback until that has run once.
 */
final class Theme {
    final boolean dark;
    final int bg, fg, sub, line, field, grip, chipOn, chipOnText, chipOff, press;

    /** null until sampled */
    private static volatile Boolean xDark;

    private Theme(boolean dark) {
        this.dark = dark;
        bg = dark ? Color.BLACK : Color.WHITE;
        fg = Color.parseColor(dark ? "#E7E9EA" : "#0F1419");
        sub = Color.parseColor(dark ? "#71767B" : "#536471");
        line = Color.parseColor(dark ? "#2F3336" : "#EFF3F4");
        field = Color.parseColor(dark ? "#202327" : "#EFF3F4");
        grip = Color.parseColor(dark ? "#3E4144" : "#CFD9DE");
        chipOn = Color.parseColor(dark ? "#EFF3F4" : "#0F1419");
        chipOnText = dark ? Color.BLACK : Color.WHITE;
        chipOff = Color.parseColor(dark ? "#536471" : "#CFD9DE");
        press = Color.parseColor(dark ? "#14FFFFFF" : "#0F000000");
    }

    static Theme of(Context c) {
        Boolean d = xDark;
        if (d == null) {
            int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            d = night == Configuration.UI_MODE_NIGHT_YES;
        }
        return new Theme(d);
    }

    /** the theme the last {@link #refresh} found, or null */
    static Boolean sampled() {
        return xDark;
    }

    /** reads the page colour behind the status bar; calls done on the main thread either way */
    static void refresh(final Activity a, final Runnable done) {
        try {
            final int w = a.getWindow().getDecorView().getWidth();
            if (w <= 0) { done.run(); return; }
            final Bitmap bm = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            PixelCopy.request(a.getWindow(), new Rect(w / 2, 2, w / 2 + 1, 3), bm, res -> {
                try {
                    if (res == PixelCopy.SUCCESS) {
                        int p = bm.getPixel(0, 0);
                        xDark = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000 < 128;
                    }
                } finally {
                    bm.recycle();
                    done.run();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            done.run();
        }
    }
}
