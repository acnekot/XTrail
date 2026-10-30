package com.catsuki.xtrail;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Time-group headers of the 足迹 list. The real headers are X's own label items (not clickable and, with
 * R8, no stickyHeader), so a transparent View is laid over each visible one to receive the tap and draw the
 * chevron, and the header of the current group is pinned under the tab strip while its items scroll.
 * Positions are applied with setTranslationY straight from the list's layout pass, so the overlay moves in the
 * same frame as the list instead of one frame behind.
 */
final class Sticky {
    /** top of the list below the History tab strip, in dp (fallback until measured) */
    private static final int LIST_TOP_DP = 155;
    private static final int DIVIDER = Color.parseColor("#2F3336");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static FrameLayout layer;
    private static Row pinned;
    private static View cover;
    private static final ArrayList<Row> rows = new ArrayList<>();
    private static boolean ticking;
    private static long lastMeasure;
    private static int lastTop = -1;

    // ------------------------------------------------------------------ views

    /** thin chevron in X's icon style (2dp round stroke), rotates when the group folds */
    private static final class Chevron extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(Color.parseColor("#71767B"));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(w * 0.22f, h * 0.38f);
            path.lineTo(w * 0.5f, h * 0.64f);
            path.lineTo(w * 0.78f, h * 0.38f);
            c.drawPath(path, p);
        }
    }

    private static final class Row extends FrameLayout {
        long day = Long.MIN_VALUE;
        boolean closed;
        final Chevron chev;
        TextView title, sub;

        Row(Activity a, boolean withText) {
            super(a);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#14FFFFFF")));
            bg.addState(new int[]{}, new ColorDrawable(withText ? Color.BLACK : Color.TRANSPARENT));
            setBackground(bg);
            setClickable(true);
            if (withText) {
                LinearLayout col = new LinearLayout(a);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(a, 12), dp(a, 12), dp(a, 56), dp(a, 12));
                title = new TextView(a);
                title.setTextSize(20);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                title.setTextColor(Color.parseColor("#E7E9EA"));
                sub = new TextView(a);
                sub.setTextSize(14);
                sub.setTextColor(Color.parseColor("#71767B"));
                col.addView(title);
                col.addView(sub);
                addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            chev = new Chevron(a);
            LayoutParams cp = new LayoutParams(dp(a, 28), dp(a, 28), Gravity.END | Gravity.CENTER_VERTICAL);
            cp.rightMargin = dp(a, 16);
            addView(chev, cp);
            View line = new View(a);
            line.setBackgroundColor(DIVIDER);
            addView(line, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM));
            setOnClickListener(v -> {
                if (day == Long.MIN_VALUE) return;
                closed = !closed;
                chev.animate().rotation(closed ? 180f : 0f).setDuration(150).start();
                NativeTab.toggleDay(day);
            });
        }

        /** same group -> animate the chevron, another group (row was reused) -> jump */
        void bind(long d, boolean isClosed) {
            boolean same = d == day;
            day = d;
            closed = isClosed;
            float target = isClosed ? 180f : 0f;
            if (chev.getRotation() == target) return;
            chev.animate().cancel();
            if (same) chev.animate().rotation(target).setDuration(150).start();
            else chev.setRotation(target);
        }
    }

    private static int dp(Context a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static void ensure(Activity a) {
        if (layer != null && layer.getContext() == a && layer.getParent() != null) return;
        hideNow();
        layer = new FrameLayout(a);
        rows.clear();
        pinned = new Row(a, true);
        pinned.setVisibility(View.GONE);
        cover = new View(a);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        layer.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP));
        layer.addView(pinned, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP);
        lp.topMargin = lastTop >= 0 ? lastTop : dp(a, LIST_TOP_DP);
        ((ViewGroup) a.getWindow().getDecorView()).addView(layer, lp);
    }

    // ------------------------------------------------------------------ update

    /**
     * vis: visible time headers {dayKey, offsetPx, heightPx, collapsed(0/1)}, offset relative to the list top;
     * pin: {dayKey, title, sub, collapsed} for the current group when its own header is scrolled away, else null.
     */
    static void update(final long[][] vis, final Object[] pin, final int endBottom) {
        if (Looper.myLooper() == Looper.getMainLooper()) apply(vis, pin, endBottom);
        else MAIN.post(() -> apply(vis, pin, endBottom));
    }

    private static void apply(long[][] vis, Object[] pin, int endBottom) {
        Activity a = XTrailModule.topActivity();
        if (a == null || !NativeTab.ourVisible()) { hideNow(); return; }
        ensure(a);
        int i = 0;
        for (long[] h : vis) {
            Row row;
            if (i < rows.size()) row = rows.get(i);
            else {
                row = new Row(a, false);
                layer.addView(row, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) h[2], Gravity.TOP));
                rows.add(row);
            }
            row.bind(h[0], h[3] == 1);
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp.height != (int) h[2]) { lp.height = (int) h[2]; row.setLayoutParams(lp); }
            row.setTranslationY(h[1]);
            row.setVisibility(View.VISIBLE);
            i++;
        }
        for (; i < rows.size(); i++) rows.get(i).setVisibility(View.GONE);
        // below the closing label X would draw its own "loading more" spinner: nothing follows the local history
        if (endBottom < 0) cover.setVisibility(View.GONE);
        else { cover.setTranslationY(endBottom); cover.setVisibility(View.VISIBLE); sampleBackground(a, endBottom); }
        if (pin == null) pinned.setVisibility(View.GONE);
        else {
            pinned.bind((Long) pin[0], Boolean.TRUE.equals(pin[3]));
            String t = (String) pin[1], s = (String) pin[2];
            if (!t.contentEquals(pinned.title.getText())) pinned.title.setText(t);
            if (!s.contentEquals(pinned.sub.getText())) pinned.sub.setText(s);
            pinned.setVisibility(View.VISIBLE);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (lastTop < 0 || now - lastMeasure > 50) {
            lastMeasure = now;
            measure(a);
        }
        MAIN.removeCallbacks(REMEASURE);
        MAIN.postDelayed(REMEASURE, 120);
        tick();
    }

    /** the History top bar can move the list without a layout pass of its items: re-measure once things settle */
    private static final Runnable REMEASURE = () -> {
        Activity a = XTrailModule.topActivity();
        if (a != null && layer != null && NativeTab.ourVisible()) measure(a);
    };

    // ------------------------------------------------------------------ placement

    /**
     * PixelCopy of the right-most pixel column: the selected tab's white underline reaches the screen edge and its
     * bottom edge is the top of the list (which moves when X's top bar collapses). Runs at most every 50 ms while the list moves.
     */
    private static void measure(final Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            final int w = decor.getWidth(), h = decor.getHeight() / 3;
            if (w <= 0 || h <= 0) return;
            final android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(4, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(a.getWindow(), new android.graphics.Rect(w - 4, 0, w, h), bm, res -> {
                try {
                    if (res != android.view.PixelCopy.SUCCESS) return;
                    for (int y = dp(a, 40); y < h; y++) {
                        if (bright(bm.getPixel(1, y))) {
                            int yy = y;
                            while (yy + 1 < h && bright(bm.getPixel(1, yy + 1))) yy++;
                            lastTop = yy + 2;
                            place(a);
                            return;
                        }
                    }
                } finally {
                    bm.recycle();
                }
            }, MAIN);
        } catch (Throwable t) {
            android.util.Log.w("XTrailSticky", "measure failed: " + t);
        }
    }

    private static long lastSample;

    /** the cover must match the page colour of whichever X theme is active: read it from the closing label's blank right edge */
    private static void sampleBackground(final Activity a, int endBottom) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSample < 1000) return;
        lastSample = now;
        try {
            View decor = a.getWindow().getDecorView();
            int x = decor.getWidth() - 3, y = (lastTop >= 0 ? lastTop : 0) + endBottom - dp(a, 12);
            if (x <= 0 || y <= 0 || y >= decor.getHeight()) return;
            final android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(a.getWindow(), new android.graphics.Rect(x, y, x + 1, y + 1), bm, res -> {
                try {
                    if (res == android.view.PixelCopy.SUCCESS && cover != null) cover.setBackgroundColor(bm.getPixel(0, 0) | 0xFF000000);
                } finally {
                    bm.recycle();
                }
            }, MAIN);
        } catch (Throwable t) {
            android.util.Log.w("XTrailSticky", "sample failed: " + t);
        }
    }

    private static boolean bright(int p) {
        return Color.red(p) > 235 && Color.green(p) > 235 && Color.blue(p) > 235;
    }

    private static void place(Activity a) {
        if (layer == null || layer.getParent() == null) return;
        int top = lastTop >= 0 ? lastTop : dp(a, LIST_TOP_DP);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) layer.getLayoutParams();
        if (lp.topMargin != top) { lp.topMargin = top; layer.setLayoutParams(lp); }
    }

    // ------------------------------------------------------------------ lifecycle

    static void hide() {
        MAIN.post(() -> {
            if (layer == null) return;
            for (Row r : rows) r.setVisibility(View.GONE);
            pinned.setVisibility(View.GONE);
            cover.setVisibility(View.GONE);
        });
    }

    private static void hideNow() {
        if (layer != null) {
            ViewGroup p = (ViewGroup) layer.getParent();
            if (p != null) p.removeView(layer);
            layer = null;
        }
        lastTop = -1;
    }

    /** while shown, drop the overlay as soon as the History tab is no longer on screen */
    private static void tick() {
        if (ticking) return;
        ticking = true;
        MAIN.postDelayed(() -> {
            ticking = false;
            if (layer == null) return;
            if (!NativeTab.ourVisible()) { hideNow(); return; }
            measure(XTrailModule.topActivity());
            tick();
        }, 300);
    }
}
