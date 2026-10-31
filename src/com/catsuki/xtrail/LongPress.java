package com.catsuki.xtrail;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Long-press on a post of the 足迹 list: shows when it was actually viewed. Never consumes the touch. */
final class LongPress {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable pending;
    private static float downX, downY;
    private static long downTime;
    /** set once the popup fired: the rest of the gesture must not reach X, or it opens the post */
    private static boolean swallow;

    /** returns true when the event must be swallowed */
    static boolean onTouch(final Activity a, MotionEvent e) {
        int act = e.getActionMasked();
        if (swallow && act != MotionEvent.ACTION_DOWN) {
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) swallow = false;
            return true;
        }
        switch (act) {
            case MotionEvent.ACTION_DOWN:
                swallow = false;
                cancel();
                downTime = e.getDownTime();
                if (!NativeTab.ourVisible() || NativeList.shown()) return false;
                downX = e.getRawX();
                downY = e.getRawY();
                final float y = downY;
                pending = () -> { pending = null; fire(a, y); };
                H.postDelayed(pending, ViewConfiguration.getLongPressTimeout() + 150L);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pending != null) {
                    float s = ViewConfiguration.get(a).getScaledTouchSlop();
                    if (Math.abs(e.getRawX() - downX) > s || Math.abs(e.getRawY() - downY) > s) cancel();
                }
                break;
            default:
                cancel();
        }
        return false;
    }

    private static void cancel() {
        if (pending != null) H.removeCallbacks(pending);
        pending = null;
    }

    private static void fire(Activity a, float rawY) {
        if (a.isFinishing() || !NativeTab.ourVisible()) return;
        int top = Sticky.listTop();
        if (top < 0 || rawY <= top) return;
        String[] info = NativeTab.browseInfoAt((int) (rawY - top));
        if (info == null) return;
        View decor = a.getWindow().getDecorView();
        decor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // cancel the gesture X is tracking, then drop its remaining events: lifting the finger must not open the post
        MotionEvent c = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0);
        decor.dispatchTouchEvent(c); // comes back through Activity.dispatchTouchEvent, so the flag is set only afterwards
        c.recycle();
        swallow = true;
        show(a, info);
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** info = {exact time, relative time, source, opened ("1" or "")} */
    private static void show(final Activity a, String[] info) {
        final Theme t = Theme.of(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 24), dp(a, 20), dp(a, 24), dp(a, 20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.bg);
        bg.setStroke(dp(a, 1), t.line);
        bg.setCornerRadius(dp(a, 16));
        col.setBackground(bg);

        TextView title = new TextView(a);
        title.setText("浏览时间");
        title.setTextSize(13);
        title.setTextColor(t.sub);
        col.addView(title);

        TextView time = new TextView(a);
        time.setText(info[0]);
        time.setTextSize(22);
        time.setTextColor(t.fg);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        time.setPadding(0, dp(a, 4), 0, dp(a, 8));
        col.addView(time);

        String more = info[1] + (info[2].isEmpty() ? "" : " · 来源：" + info[2]) + (info[3].isEmpty() ? "" : " · 已打开");
        TextView sub = new TextView(a);
        sub.setText(more);
        sub.setTextSize(14);
        sub.setTextColor(t.sub);
        col.addView(sub);

        col.setOnClickListener(v -> d.dismiss());
        d.setContentView(col);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(dp(a, 320), a.getResources().getDisplayMetrics().widthPixels - dp(a, 48)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.35f);
        }
        d.show();
    }
}
