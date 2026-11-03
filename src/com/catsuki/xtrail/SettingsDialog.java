package com.catsuki.xtrail;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * XTrail settings inside X (last row of the 足迹 sheet): a full-screen page laid out like X's own settings,
 * same structure as the launcher screen ({@link SettingsActivity}).
 */
final class SettingsDialog {
    private final Activity a;
    private final boolean dark;
    private final int bg, fg, sub, line, blue, card;
    private final ArrayDeque<Runnable> stack = new ArrayDeque<>();
    private final FrameLayout root;
    private Dialog dialog;

    private SettingsDialog(Activity a) {
        this.a = a;
        dark = Theme.of(a).dark;
        bg = Color.parseColor(dark ? "#1F2026" : "#F9F9FF");
        fg = Color.parseColor(dark ? "#E3E2E9" : "#1A1B20");
        sub = Color.parseColor(dark ? "#C4C6D0" : "#44474F");
        line = Color.parseColor(dark ? "#2F3036" : "#E0E2EC");
        blue = Color.parseColor(dark ? "#A8C7FA" : "#0B57D0");
        card = Color.parseColor(dark ? "#37383E" : "#E8E9F0");
        root = new FrameLayout(a);
        root.setBackgroundColor(bg);
    }

    static void show(Activity a) {
        if (a == null || a.isFinishing()) return;
        Theme.refresh(a, () -> { if (!a.isFinishing()) new SettingsDialog(a).open(); });
    }

    private void back() {
        dir = -1;
        stack.pop().run();
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private void open() {
        dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public void onBackPressed() {
                if (stack.isEmpty()) leave();
                else back();
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(bg));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            if (!dark) {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }
        root.setFitsSystemWindows(true);
        dir = 0;
        main();
        dialog.show();
        root.post(() -> {
            float sw = root.getWidth();
            root.setTranslationX(sw);
            root.animate().translationX(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        });
    }

    // ------------------------------------------------------------ building blocks

    private LinearLayout page(String title) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        View arrow = new View(a);
        arrow.setBackground(new ColorDrawable(Color.TRANSPARENT) {
            @Override
            public void draw(Canvas c) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(fg);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                float w = getBounds().width(), h = getBounds().height();
                Path path = new Path();
                path.moveTo(w * 0.83f, h * 0.5f);
                path.lineTo(w * 0.17f, h * 0.5f);
                path.moveTo(w * 0.46f, h * 0.17f);
                path.lineTo(w * 0.17f, h * 0.5f);
                path.lineTo(w * 0.46f, h * 0.83f);
                c.drawPath(path, p);
            }
        });
        FrameLayout hit = new FrameLayout(a);
        hit.addView(arrow, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        hit.setOnClickListener(v -> {
            if (stack.isEmpty()) leave();
            else back();
        });
        bar.addView(hit, new LinearLayout.LayoutParams(dp(56), dp(56)));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(32);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(fg);
        t.setPadding(dp(24), dp(8), dp(24), dp(12));
        col.addView(t);
        return col;
    }

    /** 1 = going deeper (new page slides in from the right), -1 = going back, 0 = first page */
    private int dir;

    private void showPage(LinearLayout col, LinearLayout body) {
        restyle(body);
        ScrollView sv = new ScrollView(a);
        sv.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.setBackgroundColor(bg);
        final View old = root.getChildCount() > 0 ? root.getChildAt(root.getChildCount() - 1) : null;
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (old == null || dir == 0) {
            while (root.getChildCount() > 1) root.removeViewAt(0);
            return;
        }
        final int d = dir;
        final float w = root.getWidth() > 0 ? root.getWidth() : a.getResources().getDisplayMetrics().widthPixels;
        final long ms = 240;
        android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.6f);
        if (d > 0) {
            col.setTranslationX(w);
            col.animate().translationX(0).setDuration(ms).setInterpolator(ease).start();
            old.animate().translationX(-w * 0.25f).alpha(0.6f).setDuration(ms).setInterpolator(ease).withEndAction(() -> root.removeView(old)).start();
        } else {
            // going back: the new page sits below and the old one slides away to the right
            root.removeView(col);
            root.addView(col, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            col.setTranslationX(-w * 0.25f);
            col.setAlpha(0.6f);
            col.animate().translationX(0).alpha(1f).setDuration(ms).setInterpolator(ease).start();
            old.animate().translationX(w).setDuration(ms).setInterpolator(ease).withEndAction(() -> root.removeView(old)).start();
        }
    }

    /** whole page leaves to the right, then the dialog closes */
    private void leave() {
        if (leaving) return;
        leaving = true;
        float w = root.getWidth() > 0 ? root.getWidth() : a.getResources().getDisplayMetrics().widthPixels;
        root.animate().translationX(w).setDuration(220).setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(() -> dialog.dismiss()).start();
    }

    private boolean leaving;


    /** Material 3 switch: pill track, round thumb that carries a check (on) or a cross (off) */
    private void styleSwitch(Switch sw) {
        final int on = blue, offTrack = card, outline = Color.parseColor(dark ? "#8E9099" : "#74777F");
        final int onThumb = Color.parseColor(dark ? "#062E6F" : "#FFFFFF"), offThumb = outline;
        android.graphics.drawable.GradientDrawable t1 = new android.graphics.drawable.GradientDrawable();
        t1.setColor(on);
        t1.setCornerRadius(dp(16));
        t1.setSize(dp(52), dp(32));
        android.graphics.drawable.GradientDrawable t0 = new android.graphics.drawable.GradientDrawable();
        t0.setColor(offTrack);
        t0.setStroke(dp(2), outline);
        t0.setCornerRadius(dp(16));
        t0.setSize(dp(52), dp(32));
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, t1);
        track.addState(new int[]{}, t0);
        final float d = a.getResources().getDisplayMetrics().density;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(2f * d);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        sw.setTrackDrawable(track);
        sw.setThumbDrawable(new android.graphics.drawable.Drawable() {
            boolean checked;
            @Override public void draw(Canvas c) {
                float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY(), r = 12f * d;
                fill.setColor(checked ? onThumb : offThumb);
                c.drawCircle(cx, cy, r, fill);
                Path p = new Path();
                if (checked) {
                    ink.setColor(on);
                    p.moveTo(cx - 5f * d, cy + 0.5f * d);
                    p.lineTo(cx - 1.5f * d, cy + 4f * d);
                    p.lineTo(cx + 5.5f * d, cy - 4f * d);
                } else {
                    ink.setColor(offTrack);
                    p.moveTo(cx - 4f * d, cy - 4f * d);
                    p.lineTo(cx + 4f * d, cy + 4f * d);
                    p.moveTo(cx + 4f * d, cy - 4f * d);
                    p.lineTo(cx - 4f * d, cy + 4f * d);
                }
                c.drawPath(p, ink);
            }
            @Override public boolean isStateful() { return true; }
            @Override protected boolean onStateChange(int[] st) {
                boolean now = false;
                for (int x : st) if (x == android.R.attr.state_checked) now = true;
                boolean ch = now != checked;
                checked = now;
                return ch;
            }
            @Override public int getIntrinsicWidth() { return (int) (32 * d); }
            @Override public int getIntrinsicHeight() { return (int) (32 * d); }
            @Override public void setAlpha(int al) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
        sw.setSwitchMinWidth(dp(52));
        sw.setSwitchPadding(0);
    }

    /** consecutive rows form one card group: big outer corners, small inner ones, thin gaps */
    private void restyle(ViewGroup g) {
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = g.getChildAt(i);
            if (v.getTag() == null && v instanceof ViewGroup) { restyle((ViewGroup) v); continue; }
            if (!"row".equals(v.getTag())) continue;
            boolean first = i == 0 || !"row".equals(g.getChildAt(i - 1).getTag());
            boolean last = i == n - 1 || !"row".equals(g.getChildAt(i + 1).getTag());
            float big = dp(26), small = dp(6);
            float tl = first ? big : small, bl = last ? big : small;
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setColor(card);
            shape.setCornerRadii(new float[]{tl, tl, tl, tl, bl, bl, bl, bl});
            android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadii(new float[]{tl, tl, tl, tl, bl, bl, bl, bl});
            v.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Color.parseColor(dark ? "#22FFFFFF" : "#1A000000")), shape, mask));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(16), first ? 0 : dp(3), dp(16), 0);
            v.setLayoutParams(lp);
        }
    }

    private LinearLayout body() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void header(LinearLayout l, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(blue);
        t.setPadding(dp(28), dp(22), dp(28), dp(8));
        t.setTag("head");
        l.addView(t);
    }

    private LinearLayout row(String title, String subtitle, int color) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(subtitle == null ? 60 : 76));
        r.setPadding(dp(24), dp(12), dp(20), dp(12));
        r.setTag("row");
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(color);
        texts.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(a);
            s.setText(subtitle);
            s.setTextSize(14);
            s.setTextColor(sub);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private interface Flag {
        void set(boolean on);
    }

    private void toggle(LinearLayout l, String title, String subtitle, boolean on, final Flag f) {
        LinearLayout r = row(title, subtitle, fg);
        final Switch sw = new Switch(a);
        sw.setChecked(on);
        styleSwitch(sw);
        sw.setOnCheckedChangeListener((b, v) -> f.set(v));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(16);
        r.addView(sw, slp);
        r.setOnClickListener(v -> sw.toggle());
        l.addView(r);
    }

    private void link(LinearLayout l, String title, String subtitle, Runnable open) {
        LinearLayout r = row(title, subtitle, fg);
        TextView chev = new TextView(a);
        chev.setText("›");
        chev.setTextSize(26);
        chev.setTextColor(sub);
        r.addView(chev);
        r.setOnClickListener(v -> open.run());
        l.addView(r);
    }

    private void choice(LinearLayout l, String label, boolean on, Runnable pick) {
        LinearLayout r = row(label, null, fg);
        TextView mark = new TextView(a);
        mark.setText(on ? "✓" : "");
        mark.setTextSize(20);
        mark.setTextColor(blue);
        r.addView(mark);
        r.setOnClickListener(v -> pick.run());
        l.addView(r);
    }

    // ------------------------------------------------------------ pages

    private static String groupName(String g) {
        return "day".equals(g) ? "按天" : "off".equals(g) ? "不分组" : "按小时";
    }

    private void main() {
        if (dir != -1) stack.clear();
        LinearLayout col = page("XTrail 设置");
        final LinearLayout top = body();
        final LinearLayout results = body();
        final LinearLayout normal = body();
        top.addView(searchBox(results, normal));
        top.addView(results);
        top.addView(normal);
        results.setVisibility(View.GONE);
        LinearLayout b = normal;
        header(b, "界面");
        toggle(b, "固定底部导航栏", "滑动时底部导航栏不再隐藏。需重启 X 后生效。", Settings.pinBar(), Settings::setPin);
        header(b, "足迹");
        toggle(b, "记录足迹", "浏览过的推文会记录在「历史记录」的足迹页。", Settings.recordAll(), Settings::setRecordAll);
        link(b, "记录的页面类型", "选择在哪些页面记录足迹", this::categories);
        link(b, "分组方式", groupName(Settings.group()), this::groups);
        link(b, "最多保留条数", Settings.cap() + " 条", this::caps);
        header(b, "精简（移植自 Piko）");
        for (final String[] g : GROUPS) {
            link(b, g[1], g[2] + "　已开启 " + countOn(g[0]) + " 项", () -> tweaks(g));
        }
        link(b, "功能开关", "按名称强制开启或关闭 X 的功能开关，需重启 X 生效", this::flags);
        header(b, "数据");
        LinearLayout clear = row("清空足迹", "删除全部足迹记录，此操作不可恢复。", Color.parseColor("#F4212E"));
        clear.setOnClickListener(v -> new AlertDialog.Builder(a)
                .setMessage("清空全部足迹记录？此操作不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (x, w) -> {
                    NativeTab.clearAll();
                    dialog.dismiss();
                })
                .show());
        b.addView(clear);
        LinearLayout exp = row("导出足迹", "保存为「下载」文件夹里的文本文件。", fg);
        exp.setOnClickListener(v -> Transfer.export(a));
        b.addView(exp);
        LinearLayout imp = row("导入足迹", "选择之前导出的文件，与现有记录合并。", fg);
        imp.setOnClickListener(v -> Transfer.pickImport(a));
        b.addView(imp);
        header(b, "关于");
        link(b, "检查更新", "当前版本 " + Updater.VERSION, () -> Updater.check(a));
        showPage(col, top);
    }

    private void categories() {
        dir = 1;
        stack.push(this::main);
        LinearLayout col = page("记录的页面类型");
        LinearLayout b = body();
        for (String[] cat : Settings.CATEGORIES) {
            final String key = cat[0];
            toggle(b, cat[1], null, Settings.catOn(key), on -> Settings.setCat(key, on));
        }
        showPage(col, b);
    }

    /** group id, page title, short description, member keys (comma separated) */
    private static final String[][] GROUPS = {
            {"feed", "广告与推荐", "去广告、隐藏推荐模块", "ads,wtf,cts,ctj,detail,bookmarked,pinned,premium,people,news"},
            {"post", "帖子操作栏", "浏览量、书签按钮", "views,bookmarks"},
            {"look", "外观与发帖", "字体、发帖按钮菜单", "font,fab"},
    };

    private static String[] findGroup(String id) {
        for (String[] g : GROUPS) if (g[0].equals(id)) return g;
        return null;
    }

    private static String[] findFilter(String key) {
        for (String[] f : Tweaks.FILTERS) if (f[0].equals(key)) return f;
        return null;
    }

    private static int countOn(String groupId) {
        int n = 0;
        for (String k : findGroup(groupId)[3].split(",")) if (Settings.tweak(k)) n++;
        return n;
    }

    private void tweaks(String[] g) {
        dir = 1;
        stack.push(this::main);
        LinearLayout col = page(g[1]);
        LinearLayout b = body();
        for (String k : g[3].split(",")) {
            final String[] f = findFilter(k);
            if (f == null) continue;
            toggle(b, f[1], f[2], Settings.tweak(k), on -> Settings.setTweak(f[0], on));
        }
        showPage(col, b);
    }

    private View searchBox(final LinearLayout results, final LinearLayout normal) {
        final android.widget.EditText et = new android.widget.EditText(a);
        et.setHint("搜索设置");
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setTextColor(fg);
        et.setHintTextColor(sub);
        et.setPadding(dp(16), dp(10), dp(16), dp(10));
        android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
        bgd.setColor(card);
        bgd.setCornerRadius(dp(26));
        et.setBackground(bgd);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(dp(16), dp(10), dp(16), dp(6));
        wrap.addView(et, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim().toLowerCase();
                results.removeAllViews();
                if (q.isEmpty()) {
                    results.setVisibility(View.GONE);
                    normal.setVisibility(View.VISIBLE);
                    return;
                }
                normal.setVisibility(View.GONE);
                results.setVisibility(View.VISIBLE);
                int hits = 0;
                for (String[] f : Tweaks.FILTERS) {
                    final String key = f[0];
                    if (!(f[1] + f[2]).toLowerCase().contains(q)) continue;
                    toggle(results, f[1], f[2], Settings.tweak(key), on -> Settings.setTweak(key, on));
                    hits++;
                }
                restyle(results);
                if (hits == 0) {
                    TextView none = new TextView(a);
                    none.setText("没有找到匹配的设置");
                    none.setTextColor(sub);
                    none.setPadding(dp(20), dp(24), dp(20), dp(24));
                    results.addView(none);
                }
            }
        });
        return wrap;
    }

    private void flags() {
        dir = 1;
        stack.push(this::main);
        LinearLayout col = page("功能开关");
        LinearLayout b = body();
        String cur = Settings.text("flags_custom");
        int n = 0;
        for (String line : cur.split("\n")) if (line.contains("=")) n++;
        link(b, "自定义开关", n == 0 ? "未设置" : "已设置 " + n + " 项", this::editFlags);
        TextView hint = new TextView(a);
        hint.setText("每行一项，格式：开关名=true 或 开关名=false。开关名可以是 X 的 feature switch，例如 ssp_ads_home_enabled=false。设置后需要重启 X。");
        hint.setTextSize(13);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(dp(20), dp(8), dp(20), dp(8));
        b.addView(hint);
        showPage(col, b);
    }

    private void editFlags() {
        final android.widget.EditText et = new android.widget.EditText(a);
        et.setText(Settings.text("flags_custom"));
        et.setHint("ssp_ads_home_enabled=false");
        et.setMinLines(4);
        et.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(a)
                .setTitle("自定义功能开关")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (x, w) -> {
                    Settings.setText("flags_custom", et.getText().toString().trim());
                    stack.clear();
                    dir = -1;
                    main();
                })
                .show();
    }

    private void groups() {
        dir = 1;
        stack.push(this::main);
        LinearLayout col = page("分组方式");
        LinearLayout b = body();
        for (String[] g : new String[][]{{"hour", "按小时"}, {"day", "按天"}, {"off", "不分组"}}) {
            final String key = g[0];
            choice(b, g[1], key.equals(Settings.group()), () -> {
                NativeTab.setGroup(key);
                stack.clear();
                dir = -1;
                main();
            });
        }
        showPage(col, b);
    }

    private void caps() {
        dir = 1;
        stack.push(this::main);
        LinearLayout col = page("最多保留条数");
        LinearLayout b = body();
        for (final int n : Settings.CAPS) {
            choice(b, n + " 条", n == Settings.cap(), () -> {
                Settings.setCap(n);
                NativeTab.trim();
                stack.clear();
                dir = -1;
                main();
            });
        }
        showPage(col, b);
    }
}
