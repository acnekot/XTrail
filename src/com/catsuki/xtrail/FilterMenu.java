package com.catsuki.xtrail;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

/** Bottom sheet opened by tapping the selected "足迹" tab (like the folder sheet of the Bookmarks tab). */
final class FilterMenu {
    static String label(String key) {
        switch (key) {
            case "all": return "全部";
            case "home": return "首页";
            case "search": return "搜索";
            case "profile": return "主页";
            case "notif": return "通知";
            case "list": return "列表";
            case "comm": return "社群";
            case "detail": return "详情";
            case "opened": return "已打开";
            case "post": return "推文";
            case "reply": return "回复";
            default: return "其他";
        }
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static void styleChips(Theme t, TextView[] views, String[][] ranges) {
        String cur = NativeTab.timeRange();
        for (int i = 0; i < views.length; i++) {
            boolean on = ranges[i][0].equals(cur);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(1000);
            g.setColor(on ? t.chipOn : Color.TRANSPARENT);
            g.setStroke(2, on ? t.chipOn : t.chipOff);
            views[i].setBackground(g);
            views[i].setTextColor(on ? t.chipOnText : t.fg);
            views[i].setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
    }

    static void show(final Activity a) {
        if (a == null || a.isFinishing()) return;
        Theme.refresh(a, () -> { if (!a.isFinishing()) build(a); });
    }

    private static void build(Activity a) {
        final Theme t = Theme.of(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(a, 12), 0, dp(a, 24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.bg);
        bg.setStroke(dp(a, 1), t.line);
        float r = dp(a, 16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        col.setBackground(bg);

        View grip = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.grip);
        g.setCornerRadius(dp(a, 3));
        grip.setBackground(g);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(a, 12);
        col.addView(grip, gp);

        // search box: filters the list live
        final EditText search = new EditText(a);
        search.setHint("搜索足迹");
        search.setSingleLine(true);
        search.setText(NativeTab.query());
        search.setTextSize(15);
        search.setTextColor(t.fg);
        search.setHintTextColor(t.sub);
        search.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10));
        Drawable mag = Icons.of("search", t.sub);
        mag.setBounds(0, 0, dp(a, 20), dp(a, 20));
        search.setCompoundDrawables(mag, null, null, null);
        search.setCompoundDrawablePadding(dp(a, 10));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(t.field);
        sbg.setCornerRadius(dp(a, 20));
        search.setBackground(sbg);
        final TextView empty = new TextView(a);
        empty.setText("没有符合条件的足迹");
        empty.setTextSize(13);
        empty.setTextColor(t.sub);
        empty.setPadding(dp(a, 20), 0, dp(a, 20), dp(a, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                NativeTab.setQuery(s.toString());
                empty.setVisibility(NativeTab.shownCount() == 0 ? View.VISIBLE : View.GONE);
            }
        });
        search.setOnEditorActionListener((v, id, ev) -> { d.dismiss(); return true; });
        d.setOnDismissListener(x -> NativeTab.hintIfEmpty());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(dp(a, 16), 0, dp(a, 16), dp(a, 12));
        col.addView(search, sp);

        // time range chips
        final String[][] ranges = {{"all", "全部时间"}, {"today", "今天"}, {"7d", "近7天"}, {"30d", "近30天"}};
        final LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 8));
        final TextView[] chipViews = new TextView[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            final String key = ranges[i][0];
            TextView c = new TextView(a);
            c.setText(ranges[i][1]);
            c.setTextSize(14);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(a, 6), dp(a, 8), dp(a, 6), dp(a, 8));
            c.setOnClickListener(v -> {
                NativeTab.setTimeRange(key);
                styleChips(t, chipViews, ranges);
            });
            chipViews[i] = c;
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.setMargins(dp(a, 3), 0, dp(a, 3), 0);
            chips.addView(c, cp);
        }
        styleChips(t, chipViews, ranges);
        col.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty.setVisibility(NativeTab.shownCount() == 0 ? View.VISIBLE : View.GONE);
        col.addView(empty);

        String cur = NativeTab.filter();
        for (Map.Entry<String, Integer> e : NativeTab.counts().entrySet()) {
            final String key = e.getKey();
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(a, 52));
            row.setPadding(dp(a, 20), 0, dp(a, 20), 0);

            View icon = new View(a);
            icon.setBackground(Icons.of(key, key.equals(cur) ? Color.parseColor("#1D9BF0") : t.fg));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
            ip.rightMargin = dp(a, 16);
            row.addView(icon, ip);

            TextView name = new TextView(a);
            name.setText(label(key));
            name.setTextSize(17);
            name.setTextColor(t.fg);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setTypeface(Typeface.create("sans-serif-medium", key.equals(cur) ? Typeface.BOLD : Typeface.NORMAL));
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView count = new TextView(a);
            count.setText(key.equals(cur) ? "✓  " + e.getValue() : String.valueOf(e.getValue()));
            count.setTextSize(15);
            count.setTextColor(key.equals(cur) ? Color.parseColor("#1D9BF0") : t.sub);
            row.addView(count);

            row.setOnClickListener(v -> {
                NativeTab.setFilter(key);
                d.dismiss();
            });
            col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.4f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
    }
}
