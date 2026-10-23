package com.touchone.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/** Small drawing helpers shared by the card renderer and the table view. */
final class Gfx {
    private Gfx() {}

    private static final Rect BOUNDS = new Rect();

    /**
     * High-quality resize. A single bilinear pass aliases badly when shrinking by
     * more than 2x (the old 1500px speech bubbles were drawn at ~300px), so shrink in
     * halves first. Returns {@code src} untouched when the size already matches.
     */
    static Bitmap scaleSmooth(Bitmap src, int tw, int th) {
        if (src == null) return null;
        tw = Math.max(1, tw);
        th = Math.max(1, th);
        if (src.getWidth() == tw && src.getHeight() == th) return src;
        Bitmap cur = src;
        while (cur.getWidth() > tw * 2 && cur.getHeight() > th * 2) {
            Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        Bitmap out = Bitmap.createScaledBitmap(cur, tw, th, true);
        if (cur != src && cur != out) cur.recycle();
        return out;
    }

    /** Baseline y that visually centres {@code s} on {@code cy}. */
    static float centeredBaseline(Paint p, String s, float cy) {
        p.getTextBounds(s, 0, s.length(), BOUNDS);
        return cy - (BOUNDS.top + BOUNDS.bottom) / 2f;
    }

    /** Outline + fill text, centred on cx at the given baseline. Restores the paint style. */
    static void outlinedText(Canvas c, Paint p, String s, float cx, float baseline,
                             int fill, int outline, float outlineWidth) {
        Paint.Style prev = p.getStyle();
        p.setTextAlign(Paint.Align.CENTER);
        if (outlineWidth > 0f && Color_alpha(outline) > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(outlineWidth);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(outline);
            c.drawText(s, cx, baseline, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(0f);
        p.setColor(fill);
        c.drawText(s, cx, baseline, p);
        p.setStyle(prev);
    }

    /**
     * Applies a shader to a paint. A paint's alpha multiplies its shader's output, so any
     * translucent colour left over from a previous draw would silently fade the gradient.
     */
    static void shader(Paint p, android.graphics.Shader s) {
        p.setColor(0xFFFFFFFF);
        p.setShader(s);
    }

    private static int Color_alpha(int color) {
        return (color >>> 24);
    }
}
