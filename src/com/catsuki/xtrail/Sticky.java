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
    /** the gear sits GEAR_ABOVE_DP above the list top (bottom of the tab strip), GEAR_RIGHT_DP in from the right edge */
    private static final int GEAR_SIZE_DP = 56, GEAR_ABOVE_DP = 79, GEAR_RIGHT_DP = 39;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static FrameLayout layer;
    /** transparent hit area over the History top bar gear: on the footprint tab it opens XTrail settings instead */
    private static View gearHit;
    private static boolean layerDark;
    private static long[][] lastVis;
    private static Object[] lastPin;
    private static int lastEnd = -1;
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
            p.setColor(Theme.of(c).sub);
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
            final Theme t = Theme.of(a);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(t.press));
            bg.addState(new int[]{}, new ColorDrawable(withText ? t.bg : Color.TRANSPARENT));
            setBackground(bg);
            setClickable(true);
            if (withText) {
                LinearLayout col = new LinearLayout(a);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(a, 12), dp(a, 12), dp(a, 56), dp(a, 12));
                title = new TextView(a);
                title.setTextSize(20);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                title.setTextColor(t.fg);
                sub = new TextView(a);
                sub.setTextSize(14);
                sub.setTextColor(t.sub);
                col.addView(title);
                col.addView(sub);
                addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            chev = new Chevron(a);
            LayoutParams cp = new LayoutParams(dp(a, 28), dp(a, 28), Gravity.END | Gravity.CENTER_VERTICAL);
            cp.rightMargin = dp(a, 16);
            addView(chev, cp);
            View line = new View(a);
            line.setBackgroundColor(t.line);
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

    /** screen y of the list top edge, or -1 until measured */
    static int listTop() {
        return lastTop < 0 ? -1 : currentTop();
    }

    private static int dp(Context a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static void ensure(Activity a) {
        if (layer != null && layer.getContext() == a && layer.getParent() != null && layerDark == Theme.of(a).dark) {
            layer.setVisibility(View.VISIBLE);
            if (gearHit != null) gearHit.setVisibility(View.VISIBLE);
            return;
        }
        detach();
        layer = new FrameLayout(a);
        layerDark = Theme.of(a).dark;
        rows.clear();
        pinned = new Row(a, true);
        pinned.setVisibility(View.GONE);
        cover = new View(a);
        cover.setBackgroundColor(Theme.of(a).bg);
        cover.setVisibility(View.GONE);
        layer.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP));
        layer.addView(pinned, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP);
        layer.setTranslationY(lastTop >= 0 ? lastTop : dp(a, LIST_TOP_DP));
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        decor.addView(layer, lp);
        gearHit = new View(a);
        gearHit.setClickable(true);
        final Activity act = a;
        gearHit.setOnClickListener(v -> SettingsDialog.show(act));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(a, GEAR_SIZE_DP), dp(a, GEAR_SIZE_DP), Gravity.TOP | Gravity.END);
        gp.topMargin = -dp(a, GEAR_SIZE_DP);
        decor.addView(gearHit, gp);
        place(a);
    }

    // ------------------------------------------------------------------ update

    /**
     * vis: visible time headers {dayKey, offsetPx, heightPx, collapsed(0/1)}, offset relative to the list top;
     * pin: {dayKey, title, sub, collapsed} for the current group when its own header is scrolled away, else null.
     */
    static void update(final long[][] vis, final Object[] pin, final int endBottom, final int vp) {
        if (Looper.myLooper() == Looper.getMainLooper()) apply(vis, pin, endBottom, vp);
        else MAIN.post(() -> apply(vis, pin, endBottom, vp));
    }

    private static void apply(long[][] vis, Object[] pin, int endBottom, int vp) {
        curVp = vp;
        lastVis = vis;
        lastPin = pin;
        lastEnd = endBottom;
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
        lastMeasure = now;
        measure(a);
        place(a);
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
    private static boolean measuring, remeasure;
    /**
     * The measured top is a frame or two old, but the list's viewport height (reported by X's layout, same frame) grows
     * by exactly what the top bar gives up: the list's bottom edge stays put. So learn that bottom edge from the
     * measurement and derive the top from the viewport height.
     */
    private static int curVp, bottomEdge = -1;

    private static int currentTop() {
        if (lastTop < 0) return -1;
        return bottomEdge > 0 && curVp > 0 ? bottomEdge - curVp : lastTop;
    }

    private static void measure(final Activity a) {
        if (measuring) { remeasure = true; return; }
        try {
            measuring = true;
            final int vpAtReq = curVp;
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
                            // only trust a sample taken while the viewport was not changing
                            if (vpAtReq == curVp && curVp > 0) bottomEdge = lastTop + curVp;
                            place(a);
                            return;
                        }
                    }
                } finally {
                    bm.recycle();
                    measuring = false;
                    if (remeasure) { remeasure = false; measure(a); }
                }
            }, MAIN);
        } catch (Throwable t) {
            measuring = false;
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
        int top = lastTop >= 0 ? currentTop() : dp(a, LIST_TOP_DP);
        // translation, not a margin: a margin change waits for the next layout pass and the header would trail the list by a frame
        layer.setTranslationY(top);
        if (gearHit != null) {
            // while the top bar is collapsed the gear is off screen: park the hit area there too
            int cy = top - dp(a, GEAR_ABOVE_DP);
            FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) gearHit.getLayoutParams();
            int want = cy < dp(a, 40) ? -dp(a, GEAR_SIZE_DP) : cy - dp(a, GEAR_SIZE_DP) / 2;
            int right = dp(a, GEAR_RIGHT_DP) - dp(a, GEAR_SIZE_DP) / 2;
            if (gp.topMargin != want || gp.rightMargin != right) { gp.topMargin = want; gp.rightMargin = right; gearHit.setLayoutParams(gp); }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    static void hide() {
        MAIN.post(() -> {
            if (layer == null) return;
            for (Row r : rows) r.setVisibility(View.GONE);
            if (gearHit != null) gearHit.setVisibility(View.GONE);
            pinned.setVisibility(View.GONE);
            cover.setVisibility(View.GONE);
        });
    }

    /**
     * Hiding only flips visibility: the layer stays attached to the decor. Adding/removing a decor child every time the tab
     * comes and goes raced with other code walking the decor (e.g. the screenshot editor), which crashed with
     * "DecorView contains null child". The layer is only really detached when another Activity takes over.
     */
    private static void hideNow() {
        if (layer != null) layer.setVisibility(View.GONE);
        if (gearHit != null) gearHit.setVisibility(View.GONE);
    }

    private static void detach() {
        if (layer != null) {
            try {
                ViewGroup p = (ViewGroup) layer.getParent();
                if (p != null) p.removeView(layer);
                if (gearHit != null && gearHit.getParent() != null) ((ViewGroup) gearHit.getParent()).removeView(gearHit);
            } catch (Throwable t) {
                android.util.Log.w("XTrailSticky", "detach failed: " + t);
            }
            layer = null;
            gearHit = null;
        }
        lastTop = -1;
        bottomEdge = -1;
    }

    /** while shown, drop the overlay as soon as the History tab is no longer on screen */
    private static void tick() {
        if (ticking) return;
        ticking = true;
        MAIN.postDelayed(() -> {
            ticking = false;
            if (layer == null || layer.getVisibility() != View.VISIBLE) return;
            if (!NativeTab.ourVisible()) { hideNow(); return; }
            final Activity top = XTrailModule.topActivity();
            if (top == null) return;
            Theme.refresh(top, () -> {
                // X's own theme was switched: rebuild the overlay in the new colours
                if (layer != null && layerDark != Theme.of(top).dark && lastVis != null) apply(lastVis, lastPin, lastEnd, curVp);
            });
            measure(top);
            tick();
        }, 300);
    }
}
