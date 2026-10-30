package com.catsuki.xtrail;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small outline icons in X's style (2px round strokes on a 24 grid), drawn by hand because the module's resources are not visible inside X. */
final class Icons extends Drawable {
    private final String key;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Icons(String key, int color) {
        this.key = key;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    static Drawable of(String key, int color) {
        return new Icons(key, color);
    }

    @Override
    public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 24f;
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(s, s);
        paint.setStrokeWidth(1.9f);
        paint.setStyle(Paint.Style.STROKE);
        Path p = new Path();
        switch (key) {
            case "home":
                p.moveTo(3.5f, 11f); p.lineTo(12f, 3.5f); p.lineTo(20.5f, 11f);
                p.moveTo(5.5f, 9.5f); p.lineTo(5.5f, 20.5f); p.lineTo(18.5f, 20.5f); p.lineTo(18.5f, 9.5f);
                p.moveTo(10f, 20.5f); p.lineTo(10f, 14.5f); p.lineTo(14f, 14.5f); p.lineTo(14f, 20.5f);
                c.drawPath(p, paint);
                break;
            case "search":
                c.drawCircle(10.5f, 10.5f, 6.5f, paint);
                c.drawLine(15.3f, 15.3f, 20.5f, 20.5f, paint);
                break;
            case "profile":
                c.drawCircle(12f, 8f, 4f, paint);
                p.moveTo(4.5f, 20.5f); p.cubicTo(4.5f, 15.5f, 8f, 13.5f, 12f, 13.5f); p.cubicTo(16f, 13.5f, 19.5f, 15.5f, 19.5f, 20.5f);
                c.drawPath(p, paint);
                break;
            case "notif":
                p.moveTo(5f, 17f); p.lineTo(19f, 17f); p.cubicTo(17.5f, 15.5f, 17f, 14f, 17f, 11f);
                p.cubicTo(17f, 7.5f, 14.8f, 5.5f, 12f, 5.5f); p.cubicTo(9.2f, 5.5f, 7f, 7.5f, 7f, 11f);
                p.cubicTo(7f, 14f, 6.5f, 15.5f, 5f, 17f);
                p.moveTo(10f, 20f); p.cubicTo(10.6f, 21f, 13.4f, 21f, 14f, 20f);
                c.drawPath(p, paint);
                break;
            case "list":
                c.drawRoundRect(new RectF(4f, 3.5f, 20f, 20.5f), 2.5f, 2.5f, paint);
                c.drawLine(8f, 9f, 16f, 9f, paint);
                c.drawLine(8f, 13f, 16f, 13f, paint);
                c.drawLine(8f, 17f, 13f, 17f, paint);
                break;
            case "comm":
                c.drawCircle(9f, 8.5f, 3.2f, paint);
                c.drawCircle(17f, 9.5f, 2.5f, paint);
                p.moveTo(3f, 19.5f); p.cubicTo(3f, 15.5f, 6f, 13.5f, 9f, 13.5f); p.cubicTo(12f, 13.5f, 15f, 15.5f, 15f, 19.5f);
                p.moveTo(16f, 14f); p.cubicTo(19f, 14f, 21f, 16f, 21f, 19f);
                c.drawPath(p, paint);
                break;
            case "detail":
                p.moveTo(5f, 4.5f); p.lineTo(19f, 4.5f); p.cubicTo(20f, 4.5f, 20.5f, 5f, 20.5f, 6f); p.lineTo(20.5f, 14f);
                p.cubicTo(20.5f, 15f, 20f, 15.5f, 19f, 15.5f); p.lineTo(11f, 15.5f); p.lineTo(6.5f, 19.5f); p.lineTo(6.5f, 15.5f);
                p.lineTo(5f, 15.5f); p.cubicTo(4f, 15.5f, 3.5f, 15f, 3.5f, 14f); p.lineTo(3.5f, 6f); p.cubicTo(3.5f, 5f, 4f, 4.5f, 5f, 4.5f);
                c.drawPath(p, paint);
                break;
            case "opened":
                p.moveTo(10f, 5f); p.lineTo(6f, 5f); p.cubicTo(5f, 5f, 4.5f, 5.5f, 4.5f, 6.5f); p.lineTo(4.5f, 18f);
                p.cubicTo(4.5f, 19f, 5f, 19.5f, 6f, 19.5f); p.lineTo(17.5f, 19.5f); p.cubicTo(18.5f, 19.5f, 19f, 19f, 19f, 18f); p.lineTo(19f, 14f);
                p.moveTo(13f, 4.5f); p.lineTo(19.5f, 4.5f); p.lineTo(19.5f, 11f);
                p.moveTo(19f, 5f); p.lineTo(11f, 13f);
                c.drawPath(p, paint);
                break;
            case "all":
                c.drawCircle(12f, 12f, 8.5f, paint);
                p.moveTo(12f, 7f); p.lineTo(12f, 12f); p.lineTo(15.5f, 14f);
                c.drawPath(p, paint);
                break;
            case "gear":
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine((float) (12 + 7.6 * Math.cos(a)), (float) (12 + 7.6 * Math.sin(a)),
                            (float) (12 + 10 * Math.cos(a)), (float) (12 + 10 * Math.sin(a)), paint);
                }
                c.drawCircle(12f, 12f, 7f, paint);
                c.drawCircle(12f, 12f, 2.8f, paint);
                break;
            default: // other
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(5.5f, 12f, 1.9f, paint);
                c.drawCircle(12f, 12f, 1.9f, paint);
                c.drawCircle(18.5f, 12f, 1.9f, paint);
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
