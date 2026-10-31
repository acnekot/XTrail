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
    private final int bg, fg, sub, line, blue;
    private final ArrayDeque<Runnable> stack = new ArrayDeque<>();
    private final FrameLayout root;
    private Dialog dialog;

    private SettingsDialog(Activity a) {
        this.a = a;
        dark = Theme.of(a).dark;
        bg = dark ? Color.BLACK : Color.WHITE;
        fg = Color.parseColor(dark ? "#E7E9EA" : "#0F1419");
        sub = Color.parseColor("#71767B");
        line = Color.parseColor(dark ? "#2F3336" : "#EFF3F4");
        blue = Color.parseColor("#1D9BF0");
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
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(fg);
        bar.addView(t);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        View div = new View(a);
        div.setBackgroundColor(line);
        col.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return col;
    }

    /** 1 = going deeper (new page slides in from the right), -1 = going back, 0 = first page */
    private int dir;

    private void showPage(LinearLayout col, LinearLayout body) {
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

    private LinearLayout body() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void header(LinearLayout l, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(sub);
        t.setPadding(dp(16), dp(20), dp(16), dp(6));
        l.addView(t);
    }

    private LinearLayout row(String title, String subtitle, int color) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(subtitle == null ? 56 : 68));
        r.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(color);
        texts.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(a);
            s.setText(subtitle);
            s.setTextSize(13);
            s.setTextColor(sub);
            s.setPadding(0, dp(2), 0, 0);
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
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{blue, Color.parseColor(dark ? "#39434A" : "#CFD9DE")}));
        sw.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        sw.setOnCheckedChangeListener((b, v) -> f.set(v));
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        l.addView(r);
    }

    private void link(LinearLayout l, String title, String subtitle, Runnable open) {
        LinearLayout r = row(title, subtitle, fg);
        TextView chev = new TextView(a);
        chev.setText("›");
        chev.setTextSize(24);
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
        LinearLayout b = body();
        header(b, "界面");
        toggle(b, "自绘足迹列表（实验）", "用模块自己的列表显示足迹，点赞立即变色。图片和引用推文为简化样式。", Settings.nativeList(), Settings::setNativeList);
        toggle(b, "固定底部导航栏", "滑动时底部导航栏不再隐藏。需重启 X 后生效。", Settings.pinBar(), Settings::setPin);
        header(b, "足迹");
        toggle(b, "记录足迹", "浏览过的推文会记录在「历史记录」的足迹页。", Settings.recordAll(), Settings::setRecordAll);
        link(b, "记录的页面类型", "选择在哪些页面记录足迹", this::categories);
        link(b, "分组方式", groupName(Settings.group()), this::groups);
        link(b, "最多保留条数", Settings.cap() + " 条", this::caps);
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
        showPage(col, b);
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
