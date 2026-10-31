package com.catsuki.xtrail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The 足迹 list drawn with plain Android views instead of X's Compose rows. Everything is read from the stored items
 * (author, text, pictures, counts), so a like can be shown the moment it is made: the module owns the row's state.
 * It sits over X's own list (which keeps running underneath, unseen) at the position {@link Sticky} measures.
 */
final class NativeList {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int PINK = Color.parseColor("#F91880");

    private static FrameLayout root;
    private static ListView list;
    private static TextView empty;
    private static Adapter adapter;
    private static HeaderView pinHead;
    private static boolean rootDark;
    private static ArrayList<Object[]> data = new ArrayList<>();
    private static final HashMap<Long, Post> POSTS = new HashMap<>();

    static boolean enabled() { return Settings.nativeList(); }

    static boolean shown() { return root != null && root.getVisibility() == View.VISIBLE; }

    // ------------------------------------------------------------------ model

    private static final class Post {
        long id, pid, ts;
        String replyTo = "";
        String name = "", handle = "", avatar, text = "";
        int reply, rt, fav, views;
        boolean snapLiked, hasVideo;
        final ArrayList<String> imgs = new ArrayList<>();
        float ratio = 1.5f;
        Post quote;
        final ArrayList<String> links = new ArrayList<>();   // shortened link texts as shown in the body
    }

    private static Post parse(long id) {
        Post p = POSTS.get(id);
        if (p != null) return p;
        String j = NativeTab.jsonOf(id);
        p = new Post();
        p.id = id;
        if (j != null) {
            try {
                JSONObject o = new JSONObject(j);
                java.util.regex.Matcher rm = java.util.regex.Pattern.compile("\"repliedToUserScreenName\":\"([^\"]+)\"").matcher(j);
                if (rm.find()) p.replyTo = rm.group(1);
                JSONObject cp = o.optJSONObject("postResult");
                if (cp != null) fill(p, cp.optJSONObject("canonicalPost"), true);
                if (cp != null && cp.optJSONObject("quotedPost") != null) {
                    JSONObject q = cp.optJSONObject("quotedPost");
                    JSONObject qc = q.optJSONObject("canonicalPost");
                    if (qc == null && q.optJSONObject("postResult") != null) qc = q.optJSONObject("postResult").optJSONObject("canonicalPost");
                    if (qc != null) { p.quote = new Post(); fill(p.quote, qc, false); }
                }
            } catch (Throwable ignored) {
            }
        }
        if (POSTS.size() > 1500) POSTS.clear();
        POSTS.put(id, p);
        return p;
    }

    private static void fill(Post p, JSONObject c, boolean actions) {
        if (c == null) return;
        if (actions) {
            JSONObject io = c.optJSONObject("id");
            String is = io != null ? io.optString("value", "") : c.optString("id", "");
            try { p.pid = Long.parseLong(is); } catch (Throwable ignored) { }
        }
        JSONObject au = c.optJSONObject("author");
        if (au != null) {
            p.name = au.optString("name", "");
            p.handle = au.optString("screenName", "");
            String av = au.optString("profileImageUrl", "");
            p.avatar = av.isEmpty() ? null : av.replace("_normal.", "_bigger.");
        }
        String text = c.optString("text", "");
        JSONObject ent = c.optJSONObject("entityList");
        if (ent != null) {
            JSONArray media = ent.optJSONArray("media");
            if (media != null) for (int i = 0; i < media.length(); i++) {
                String u = media.optJSONObject(i).optString("url", "");
                if (!u.isEmpty()) text = text.replace(u, "");
            }
            JSONArray urls = ent.optJSONArray("urls");
            if (urls != null) for (int i = 0; i < urls.length(); i++) {
                JSONObject u = urls.optJSONObject(i);
                String t = u.optString("url", ""), d = u.optString("displayUrl", "");
                if (!t.isEmpty() && !d.isEmpty()) { text = text.replace(t, d); p.links.add(d); }
            }
        }
        p.text = text.trim();
        p.ts = parseTime(c.optString("timestamp", ""));
        JSONArray media = c.optJSONArray("media");
        if (media != null) for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.optJSONObject(i);
            if (m == null) continue;
            String type = m.optString("type", "");
            String url = m.optString("imageUrl", "");
            if (url.isEmpty()) url = m.optString("previewImageUrl", "");
            if (url.isEmpty()) url = m.optString("thumbnailUrl", "");
            if (!type.contains("Image")) p.hasVideo = true;
            if (!url.isEmpty()) {
                p.imgs.add(url);
                if (p.imgs.size() == 1) {
                    int w = m.optInt("originalImgWidth", 0), h = m.optInt("originalImgHeight", 0);
                    if (w > 0 && h > 0) p.ratio = (float) w / h;
                }
            }
        }
        if (!actions) return;
        JSONArray acts = c.optJSONArray("inlineActionEntry");
        if (acts != null) for (int i = 0; i < acts.length(); i++) {
            JSONObject a = acts.optJSONObject(i);
            if (a == null) continue;
            String t = a.optString("actionType", "");
            int n = a.optInt("count", 0);
            switch (t) {
                case "Reply": p.reply = n; break;
                case "Retweet": p.rt = n; break;
                case "Unretweet": p.rt = n; break;
                case "Favorite": p.fav = n; p.snapLiked = false; break;
                case "Unfavorite": p.fav = n; p.snapLiked = true; break;
                case "ViewCount": p.views = n; break;
                default: break;
            }
        }
    }

    private static final java.util.regex.Pattern TAGS = java.util.regex.Pattern.compile("[@#][\\p{L}\\p{N}_]+");

    /** the post text with links, @mentions and #hashtags in the accent colour, like X draws them */
    private static CharSequence rich(String text, ArrayList<String> links) {
        SpannableStringBuilder s = new SpannableStringBuilder(text);
        int blue = Color.parseColor("#1D9BF0");
        java.util.regex.Matcher m = TAGS.matcher(text);
        while (m.find()) s.setSpan(new ForegroundColorSpan(blue), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (String l : links) {
            for (int at = text.indexOf(l); at >= 0; at = text.indexOf(l, at + l.length())) {
                s.setSpan(new ForegroundColorSpan(blue), at, at + l.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return s;
    }

    private static long parseTime(String iso) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String ago(long ts) {
        if (ts <= 0) return "";
        long d = System.currentTimeMillis() - ts;
        if (d < 60000) return "刚刚";
        if (d < 3600000) return d / 60000 + "分钟";
        if (d < 86400000) return d / 3600000 + "小时";
        return new java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault()).format(new java.util.Date(ts));
    }

    private static String count(int n) {
        if (n <= 0) return "";
        if (n < 1000) return String.valueOf(n);
        if (n < 10000) return String.format(java.util.Locale.US, "%.1fK", n / 1000f).replace(".0K", "K");
        if (n < 1000000) return n / 1000 + "K";
        return String.format(java.util.Locale.US, "%.1fM", n / 1000000f).replace(".0M", "M");
    }

    // ------------------------------------------------------------------ likes

    private static SharedPreferences likePrefs;

    private static SharedPreferences prefs(Context c) {
        if (likePrefs == null) likePrefs = c.getSharedPreferences("xtrail_likes", Context.MODE_PRIVATE);
        return likePrefs;
    }

    /** the like state of a post: what the viewer last did through X or this list, else what the stored copy says */
    private static boolean liked(Context c, Post p) {
        String v = prefs(c).getString(String.valueOf(p.id), null);
        if (v != null) {
            try {
                String[] f = v.split(":");
                if (Long.parseLong(f[1]) > NativeTab.timeOf(p.id)) return f[0].equals("1");
            } catch (Throwable ignored) {
            }
        }
        return p.snapLiked;
    }

    private static void remember(Context c, long id, boolean on) {
        prefs(c).edit().putString(String.valueOf(id), (on ? "1:" : "0:") + System.currentTimeMillis()).apply();
    }

    /** a like made elsewhere in X (a detail page, another list) */
    static void likedElsewhere(final long id, final boolean on) {
        MAIN.post(() -> {
            Activity a = XTrailModule.topActivity();
            if (a == null) return;
            remember(a, id, on);
            for (Post q : POSTS.values()) if (q.pid == id && q.id != id) remember(a, q.id, on);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    // ------------------------------------------------------------------ images

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private static void load(final ImageView v, final String url) {
        v.setTag(url);
        v.setImageDrawable(null);
        if (url == null) return;
        Bitmap b = CACHE.get(url);
        if (b != null) { v.setImageBitmap(b); return; }
        POOL.execute(() -> {
            Bitmap bm = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                try (java.io.InputStream in = c.getInputStream()) { bm = BitmapFactory.decodeStream(in); }
            } catch (Throwable ignored) {
            }
            if (bm == null) return;
            CACHE.put(url, bm);
            final Bitmap fin = bm;
            MAIN.post(() -> { if (url.equals(v.getTag())) v.setImageBitmap(fin); });
        });
    }

    private static String sized(String url) {
        return url.contains("pbs.twimg.com/media/") && !url.contains("?") ? url + "?name=small" : url;
    }

    // ------------------------------------------------------------------ views

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static void round(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                if (radius < 0) o.setOval(0, 0, view.getWidth(), view.getHeight());
                else o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private static final class HeaderView extends LinearLayout {
        final TextView title, sub;
        final Sticky.Chevron chev;
        long day = Long.MIN_VALUE;

        HeaderView(Activity a, Theme t) {
            super(a);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundColor(t.bg);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(VERTICAL);
            col.setPadding(dp(a, 12), dp(a, 12), dp(a, 4), dp(a, 12));
            title = new TextView(a);
            title.setTextSize(17);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            title.setTextColor(t.fg);
            sub = new TextView(a);
            sub.setTextSize(14);
            sub.setTextColor(t.sub);
            col.addView(title);
            col.addView(sub);
            addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            chev = new Sticky.Chevron(a);
            LayoutParams cp = new LayoutParams(dp(a, 28), dp(a, 28));
            cp.rightMargin = dp(a, 16);
            addView(chev, cp);
            setOnClickListener(v -> { if (day != Long.MIN_VALUE) NativeTab.toggleDay(day); });
        }

        void bind(Object[] e) {
            day = (Long) e[3];
            title.setText((String) e[1]);
            sub.setText((String) e[2]);
            chev.setRotation(Boolean.TRUE.equals(e[4]) ? 180f : 0f);
        }
    }

    private static final class Bar extends LinearLayout {
        final ImageView[] icons = new ImageView[4];
        final TextView[] counts = new TextView[4];

        Bar(Activity a) {
            super(a);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < 4; i++) {
                LinearLayout cell = new LinearLayout(a);
                cell.setOrientation(HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(0, dp(a, 6), 0, dp(a, 6));
                icons[i] = new ImageView(a);
                counts[i] = new TextView(a);
                counts[i].setTextSize(13);
                cell.addView(icons[i], new LayoutParams(dp(a, 18), dp(a, 18)));
                LayoutParams tp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.leftMargin = dp(a, 6);
                cell.addView(counts[i], tp);
                addView(cell, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
    }

    private static final class Cell extends LinearLayout {
        final ImageView avatar;
        final TextView head, body, reply;
        final LinearLayout media;
        final LinearLayout quote;
        final Bar bar;
        Post post;

        Cell(Activity a) {
            super(a);
            setOrientation(HORIZONTAL);
            setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 4));
            avatar = new ImageView(a);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            round(avatar, -1);
            LayoutParams ap = new LayoutParams(dp(a, 40), dp(a, 40));
            ap.rightMargin = dp(a, 12);
            addView(avatar, ap);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(VERTICAL);
            head = new TextView(a);
            head.setSingleLine(true);
            head.setEllipsize(TextUtils.TruncateAt.END);
            head.setTextSize(15);
            reply = new TextView(a);
            reply.setTextSize(13);
            reply.setSingleLine(true);
            body = new TextView(a);
            body.setTextSize(15);
            body.setLineSpacing(0, 1.12f);
            media = new LinearLayout(a);
            media.setOrientation(VERTICAL);
            quote = new LinearLayout(a);
            quote.setOrientation(VERTICAL);
            quote.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
            bar = new Bar(a);
            col.addView(head);
            col.addView(reply);
            LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            col.addView(body, bp);
            LayoutParams mp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.topMargin = dp(a, 10);
            col.addView(media, mp);
            LayoutParams qp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qp.topMargin = dp(a, 10);
            col.addView(quote, qp);
            LayoutParams barp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            barp.topMargin = dp(a, 4);
            col.addView(bar, barp);
            addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private static SpannableStringBuilder headline(Post p, Theme t, boolean withTime) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        s.append(p.name);
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(t.fg), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int from = s.length();
        s.append("  @").append(p.handle);
        if (withTime && p.ts > 0) s.append(" · ").append(ago(p.ts));
        s.setSpan(new ForegroundColorSpan(t.sub), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private static ImageView picture(Activity a, Theme t, String url, int w, int h) {
        ImageView v = new ImageView(a);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackgroundColor(t.field);
        round(v, dp(a, 12));
        load(v, url == null ? null : sized(url));
        return v;
    }

    private static void bindMedia(Activity a, Theme t, LinearLayout box, Post p) {
        box.removeAllViews();
        if (p.imgs.isEmpty()) {
            if (p.hasVideo) {
                TextView tv = new TextView(a);
                tv.setText("▶ 视频");
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(t.sub);
                tv.setBackground(boxBg(a, t, 12));
                box.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 120)));
                box.setVisibility(View.VISIBLE);
            } else box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        int width = a.getResources().getDisplayMetrics().widthPixels - dp(a, 14 + 14 + 40 + 12);
        int n = Math.min(4, p.imgs.size());
        if (n == 1) {
            float r = Math.max(0.8f, Math.min(1.9f, p.ratio));
            box.addView(picture(a, t, p.imgs.get(0), width, 0), new LinearLayout.LayoutParams(width, Math.round(width / r)));
            return;
        }
        int gap = dp(a, 3), cw = (width - gap) / 2, ch = Math.round(cw * 0.9f);
        for (int row = 0; row * 2 < n; row++) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2 && row * 2 + k < n; k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(n == 3 && row == 1 ? width : cw, ch);
                if (k == 1) lp.leftMargin = gap;
                r.addView(picture(a, t, p.imgs.get(row * 2 + k), cw, ch), lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ch);
            if (row > 0) rp.topMargin = gap;
            box.addView(r, rp);
        }
    }

    private static GradientDrawable boxBg(Activity a, Theme t, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(a, radius));
        g.setStroke(dp(a, 1) > 0 ? 1 : 1, t.line);
        g.setColor(Color.TRANSPARENT);
        return g;
    }

    private static void bindQuote(Activity a, Theme t, LinearLayout box, Post q) {
        box.removeAllViews();
        if (q == null) { box.setVisibility(View.GONE); return; }
        box.setVisibility(View.VISIBLE);
        box.setBackground(boxBg(a, t, 14));
        TextView h = new TextView(a);
        h.setTextSize(14);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        h.setText(headline(q, t, true));
        box.addView(h);
        if (!q.text.isEmpty()) {
            TextView b = new TextView(a);
            b.setTextSize(14);
            b.setTextColor(t.fg);
            b.setMaxLines(6);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setText(rich(q.text, q.links));
            box.addView(b);
        }
        if (!q.imgs.isEmpty()) {
            ImageView v = picture(a, t, q.imgs.get(0), 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 140));
            lp.topMargin = dp(a, 8);
            box.addView(v, lp);
        }
    }

    private static void bindBar(final Activity a, final Theme t, final Cell c, final Post p) {
        boolean on = liked(a, p);
        int delta = on == p.snapLiked ? 0 : on ? 1 : -1;
        String[] icon = {"p-reply", "p-rt", on ? "p-heartfill" : "p-heart", "p-views"};
        int[] n = {p.reply, p.rt, Math.max(0, p.fav + delta), p.views};
        for (int i = 0; i < 4; i++) {
            int col = i == 2 && on ? PINK : t.sub;
            c.bar.icons[i].setImageDrawable(Icons.of(icon[i], col));
            c.bar.counts[i].setTextColor(col);
            c.bar.counts[i].setText(count(n[i]));
        }
        View heart = (View) c.bar.icons[2].getParent();
        heart.setOnClickListener(v -> toggleLike(a, t, c, p));
    }

    private static void toggleLike(Activity a, Theme t, Cell c, Post p) {
        boolean now = !liked(a, p);
        if (!NativeTab.like(p.id, now)) {
            Toast.makeText(a, "还不能点赞：请先在 X 里随便点一次赞或打开一条推文", Toast.LENGTH_SHORT).show();
            return;
        }
        remember(a, p.id, now);
        // rebind just this bar: a full notifyDataSetChanged swaps the recycled views and would cut the animation
        bindBar(a, t, c, p);
        if (now) pop(a, c.bar.icons[2]);
        MAIN.postDelayed(() -> { if (adapter != null) adapter.notifyDataSetChanged(); }, 700);
    }

    /** X's like animation: the heart springs up from small, with a ring and a few sparks flying out */
    private static void pop(Activity a, final ImageView icon) {
        // the heart overshoots and the sparks fly out of its small row: let every ancestor draw outside its bounds
        for (android.view.ViewParent v = icon.getParent(); v instanceof android.view.ViewGroup; v = v.getParent()) ((android.view.ViewGroup) v).setClipChildren(false);
        // measure before scaling: the window location of a scaled view is that of its scaled box
        final View host = a.getWindow().getDecorView();
        int[] at = new int[2], base = new int[2];
        icon.getLocationInWindow(at);
        host.getLocationInWindow(base);
        final float cx = at[0] - base[0] + icon.getWidth() / 2f, cy = at[1] - base[1] + icon.getHeight() / 2f, r0 = icon.getWidth() / 2f;
        icon.setScaleX(0.3f);
        icon.setScaleY(0.3f);
        icon.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(3.2f)).start();
        final Burst d = new Burst(cx, cy, r0);
        d.setBounds(0, 0, host.getWidth(), host.getHeight());
        host.getOverlay().add(d);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(480);
        va.addUpdateListener(an -> {
            d.set((Float) an.getAnimatedValue());
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { host.getOverlay().remove(d); }
        });
        va.start();
    }

    private static final class Burst extends android.graphics.drawable.Drawable {
        final float cx, cy, r0;
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float f;

        Burst(float cx, float cy, float r0) { this.cx = cx; this.cy = cy; this.r0 = r0; }

        void set(float v) { f = v; invalidateSelf(); }

        @Override public void draw(android.graphics.Canvas c) {
            paint.setColor(PINK);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(r0 * 0.4f * (1f - f));
            paint.setAlpha((int) (230 * (1f - f)));
            c.drawCircle(cx, cy, r0 * (0.6f + 1.4f * f), paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setAlpha((int) (255 * (1f - f)));
            for (int i = 0; i < 7; i++) {
                double ang = Math.PI * 2 / 7 * i;
                float rad = r0 * (1.1f + 1.3f * f);
                c.drawCircle(cx + (float) Math.cos(ang) * rad, cy + (float) Math.sin(ang) * rad, r0 * 0.16f * (1f - f), paint);
            }
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static void open(Activity a, Post p) {
        try {
            NativeTab.noteOpened(p.id);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/" + (p.handle.isEmpty() ? "i" : p.handle) + "/status/" + (p.pid != 0 ? p.pid : p.id) + "/photo/1")); // plain /status/<id> links bounce to home in this build; the photo route opens the detail page
            i.setPackage(a.getPackageName());
            a.startActivity(i);
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable t) {
            Toast.makeText(a, "无法打开这条推文", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ adapter

    private static final class Adapter extends BaseAdapter {
        final Activity a;
        Adapter(Activity a) { this.a = a; }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return "H".equals(data.get(i)[0]) ? 0 : 1; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Theme t = Theme.of(a);
            Object[] e = data.get(i);
            if ("H".equals(e[0])) {
                HeaderView h = v instanceof HeaderView ? (HeaderView) v : new HeaderView(a, t);
                h.bind(e);
                return h;
            }
            final Cell c = v instanceof Cell ? (Cell) v : new Cell(a);
            final Post p = parse((Long) e[1]);
            c.post = p;
            c.head.setText(headline(p, t, true));
            c.reply.setVisibility(p.replyTo.isEmpty() ? View.GONE : View.VISIBLE);
            if (!p.replyTo.isEmpty()) {
                SpannableStringBuilder rs = new SpannableStringBuilder("回复给 ");
                rs.setSpan(new ForegroundColorSpan(t.sub), 0, rs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int st = rs.length();
                rs.append("@").append(p.replyTo);
                rs.setSpan(new ForegroundColorSpan(Color.parseColor("#1D9BF0")), st, rs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                c.reply.setText(rs);
            }
            c.body.setTextColor(t.fg);
            c.body.setText(rich(p.text, p.links));
            c.body.setVisibility(p.text.isEmpty() ? View.GONE : View.VISIBLE);
            load(c.avatar, p.avatar);
            c.avatar.setBackgroundColor(t.field);
            bindMedia(a, t, c.media, p);
            bindQuote(a, t, c.quote, p.quote);
            bindBar(a, t, c, p);
            c.setOnClickListener(x -> open(a, p));
            c.setOnLongClickListener(x -> {
                long ts = NativeTab.timeOf(p.id);
                Toast.makeText(a, ts == 0 ? "浏览时间未知" : "浏览于 " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(ts)), Toast.LENGTH_SHORT).show();
                return true;
            });
            return c;
        }
    }

    // ------------------------------------------------------------------ pinned header

    private static void updatePin() {
        if (list == null || pinHead == null) return;
        int n = data.size(), f = list.getFirstVisiblePosition();
        if (n == 0 || f >= n || "off".equals(Settings.group())) { pinHead.setVisibility(View.GONE); return; }
        int h = -1;
        for (int i = Math.min(f, n - 1); i >= 0; i--) if ("H".equals(data.get(i)[0])) { h = i; break; }
        View first = list.getChildAt(0);
        if (h < 0 || (h == f && first != null && first.getTop() >= 0)) { pinHead.setVisibility(View.GONE); return; }
        pinHead.bind(data.get(h));
        pinHead.setVisibility(View.VISIBLE);
        pinHead.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int ph = pinHead.getMeasuredHeight(), push = 0;
        for (int i = f + 1; i < n; i++) {
            if ("H".equals(data.get(i)[0])) {
                View nv = list.getChildAt(i - f);
                if (nv != null && nv.getTop() < ph) push = nv.getTop() - ph;
                break;
            }
        }
        pinHead.setTranslationY(push);
    }

    // ------------------------------------------------------------------ lifecycle

    static void dataChanged() {
        MAIN.post(() -> {
            data = NativeTab.entries();
            if (adapter != null) adapter.notifyDataSetChanged();
            if (empty != null) empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
            updatePin();
        });
    }

    static void show(Activity a) {
        Theme t = Theme.of(a);
        if (root == null || root.getContext() != a || rootDark != t.dark || root.getParent() == null) {
            hide();
            detach();
            rootDark = t.dark;
            root = new FrameLayout(a);
            root.setBackgroundColor(t.bg);
            root.setClickable(true);
            list = new ListView(a);
            list.setBackgroundColor(t.bg);
            list.setDivider(new ColorDrawable(t.line));
            list.setDividerHeight(1);
            list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            list.setOverScrollMode(View.OVER_SCROLL_NEVER);
            list.setVerticalScrollBarEnabled(false);
            list.setHeaderDividersEnabled(false);
            list.setFooterDividersEnabled(false);
            TextView end = new TextView(a);
            end.setText("没有更多的历史记录");
            end.setGravity(Gravity.CENTER);
            end.setTextSize(14);
            end.setTextColor(t.sub);
            end.setPadding(0, dp(a, 28), 0, dp(a, 140));
            list.addFooterView(end, null, false);
            adapter = new Adapter(a);
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView v, int s) { }
                @Override public void onScroll(AbsListView v, int f, int c, int total) { updatePin(); }
            });
            root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pinHead = new HeaderView(a, t);
            pinHead.setVisibility(View.GONE);
            root.addView(pinHead, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            empty = new TextView(a);
            empty.setText("暂无足迹");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(t.sub);
            empty.setTextSize(15);
            empty.setPadding(0, dp(a, 80), 0, 0);
            root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ((ViewGroup) a.getWindow().getDecorView()).addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP));
            data = NativeTab.entries();
            adapter.notifyDataSetChanged();
            empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        }
        root.setVisibility(View.VISIBLE);
    }

    /** the list top/bottom edge in window pixels */
    static void place(Activity a, int top, int bottom) {
        if (root == null || root.getParent() == null) return;
        int h = Math.max(1, bottom - top);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) root.getLayoutParams();
        if (lp.height != h) { lp.height = h; root.setLayoutParams(lp); }
        root.setTranslationY(top);
    }

    static void hide() {
        if (root != null) root.setVisibility(View.GONE);
    }

    private static void detach() {
        try {
            if (root != null && root.getParent() != null) ((ViewGroup) root.getParent()).removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        list = null;
        adapter = null;
        pinHead = null;
    }
}
