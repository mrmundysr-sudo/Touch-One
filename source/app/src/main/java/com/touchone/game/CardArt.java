package com.touchone.game;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

/**
 * Draws every card face once into a bitmap and caches it. The old view rebuilt
 * paths, text and shadows for every card on every frame; now a frame is just a
 * handful of bitmap blits, which is what keeps animation steady at 60 fps.
 */
final class CardArt {
    final int w;
    final int h;
    final int shadowPad;

    private final Resources res;
    private final LruCache<String, Bitmap> faces = new LruCache<String, Bitmap>(26 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private Bitmap back;
    private Bitmap shadow;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF plate = new RectF();
    private final RectF oval = new RectF();
    private final RectF tmp = new RectF();
    private final Path path = new Path();

    CardArt(Resources res, int cardW, int cardH) {
        this.res = res;
        this.w = cardW;
        this.h = cardH;
        this.shadowPad = Math.max(6, Math.round(cardW * 0.08f));
        tp.setTypeface(Theme.font);
    }

    Bitmap back() {
        if (back == null) {
            Bitmap src = BitmapFactory.decodeResource(res,
                    res.getIdentifier("card_back", "drawable", "com.touchone.game"));
            back = src == null ? fallbackBack() : Gfx.scaleSmooth(src, w, h);
        }
        return back;
    }

    /** Soft blurred drop shadow sized for a full card plus padding on every side. */
    Bitmap shadow() {
        if (shadow == null) {
            int sw = w + shadowPad * 2, sh = h + shadowPad * 2;
            shadow = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(shadow);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setStyle(Paint.Style.FILL);
            int layers = 14;
            float r = w * 0.12f;
            for (int i = 0; i < layers; i++) {
                float inset = shadowPad * (i / (float) layers);
                sp.setColor(Color.argb(11, 10, 4, 0));
                tmp.set(inset, inset, sw - inset, sh - inset);
                float rr = r + (shadowPad - inset) * 0.6f;
                c.drawRoundRect(tmp, rr, rr, sp);
            }
        }
        return shadow;
    }

    /** @param live colour a wild takes once played; null shows the wild's own face. */
    Bitmap face(Card card, Card.Color live) {
        Card.Color liveKey = card.isWild() ? live : null;
        String key = card.kind + ":" + card.color + ":" + card.number + ":" + liveKey;
        Bitmap b = faces.get(key);
        if (b == null) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            drawFace(new Canvas(b), card, liveKey);
            faces.put(key, b);
        }
        return b;
    }

    private Bitmap fallbackBack() {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(16, 16, 22));
        c.drawRoundRect(new RectF(0, 0, w, h), w * 0.12f, w * 0.12f, p);
        return b;
    }

    // ------------------------------------------------------------------ face
    private void drawFace(Canvas c, Card card, Card.Color live) {
        final float W = w, H = h;
        boolean wild = card.isWild();
        boolean wildFace = wild && live == null;
        int base = wildFace ? Color.rgb(36, 36, 46)
                : Theme.cardColor(wild ? live : card.color);
        int deep = wildFace ? Color.rgb(20, 20, 26) : Theme.shade(base, 0.42f);
        float r = W * 0.12f;

        // Black card border with a faint rim light.
        p.setStyle(Paint.Style.FILL);
        Gfx.shader(p, new LinearGradient(0, 0, 0, H, Color.rgb(34, 34, 42), Color.rgb(8, 8, 12),
                Shader.TileMode.CLAMP));
        tmp.set(0, 0, W, H);
        c.drawRoundRect(tmp, r, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, W * 0.010f));
        p.setColor(Color.argb(60, 255, 255, 255));
        tmp.set(W * 0.006f, W * 0.006f, W - W * 0.006f, H - W * 0.006f);
        c.drawRoundRect(tmp, r, r, p);

        // Colour plate: diagonal gradient, soft vignette, top gloss, inner keyline.
        float inset = W * 0.065f;
        plate.set(inset, inset, W - inset, H - inset);
        float ir = r * 0.62f;
        p.setStyle(Paint.Style.FILL);
        Gfx.shader(p, new LinearGradient(plate.left, plate.top, plate.right, plate.bottom,
                Theme.shade(base, 1.24f), Theme.shade(base, 0.80f), Shader.TileMode.CLAMP));
        c.drawRoundRect(plate, ir, ir, p);
        Gfx.shader(p, new RadialGradient(W / 2f, H * 0.46f, H * 0.66f,
                Color.argb(0, 0, 0, 0), Color.argb(80, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRoundRect(plate, ir, ir, p);
        Gfx.shader(p, new LinearGradient(0, plate.top, 0, plate.top + plate.height() * 0.55f,
                Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRoundRect(plate, ir, ir, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, W * 0.012f));
        p.setColor(Color.argb(140, Color.red(deep), Color.green(deep), Color.blue(deep)));
        tmp.set(plate.left + 1.5f, plate.top + 1.5f, plate.right - 1.5f, plate.bottom - 1.5f);
        c.drawRoundRect(tmp, ir, ir, p);

        // Cream oval with a soft inner ring and a hint of depth.
        float ox = W * 0.20f, oy = H * 0.255f;
        oval.set(ox, oy, W - ox, H - oy);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(70, 0, 0, 0));
        tmp.set(oval.left, oval.top + H * 0.012f, oval.right, oval.bottom + H * 0.012f);
        c.drawOval(tmp, p);
        Gfx.shader(p, new RadialGradient(oval.centerX() - oval.width() * 0.12f,
                oval.centerY() - oval.height() * 0.18f, oval.height() * 0.75f,
                Color.rgb(255, 252, 242), Color.rgb(250, 228, 190), Shader.TileMode.CLAMP));
        c.drawOval(oval, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, W * 0.016f));
        p.setColor(Color.argb(190, Color.red(deep), Color.green(deep), Color.blue(deep)));
        c.drawOval(oval, p);
        p.setStyle(Paint.Style.FILL);

        float cx = W / 2f, cy = H / 2f;
        switch (card.kind) {
            case JUMP:
                drawJump(c, cx, cy, W * 0.19f, W * 0.062f, deep);
                break;
            case SPIN:
                drawSpin(c, cx, cy, W * 0.19f, W * 0.055f, deep);
                break;
            case TOUCH:
            case PULL4:
                if (wildFace) {
                    drawWildWheel(c, oval, card.kind == Card.Kind.TOUCH ? "T1" : "+4");
                } else {
                    glyph(c, card.label(), cx, cy, H * 0.25f, deep);
                }
                break;
            case PULL2:
                glyph(c, "+2", cx, cy, H * 0.25f, deep);
                break;
            default:
                glyph(c, card.label(), cx, cy, H * 0.40f, deep);
                if (card.number == 6 || card.number == 9) underline(c, cx, oval.bottom - oval.height() * 0.17f, W * 0.15f, W * 0.025f, deep);
                break;
        }

        // Corner marks, identical top-left and (rotated) bottom-right.
        drawPip(c, card, W * 0.205f, H * 0.14f, deep, base);
        c.save();
        c.rotate(180, W / 2f, H / 2f);
        drawPip(c, card, W * 0.205f, H * 0.14f, deep, base);
        c.restore();
    }

    private void glyph(Canvas c, String s, float cx, float cy, float size, int color) {
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        float by = Gfx.centeredBaseline(tp, s, cy);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(Color.argb(46, 0, 0, 0));
        c.drawText(s, cx, by + size * 0.035f, tp);
        tp.setColor(color);
        c.drawText(s, cx, by, tp);
    }

    private void drawPip(Canvas c, Card card, float cx, float cy, int deep, int base) {
        float W = w, H = h;
        if (card.kind == Card.Kind.JUMP) {
            drawJump(c, cx, cy, W * 0.062f, W * 0.036f, Color.argb(200, 20, 10, 5));
            drawJump(c, cx, cy, W * 0.062f, W * 0.022f, Theme.CREAM);
            return;
        }
        if (card.kind == Card.Kind.SPIN) {
            drawSpin(c, cx, cy, W * 0.062f, W * 0.032f, Color.argb(200, 20, 10, 5));
            drawSpin(c, cx, cy, W * 0.062f, W * 0.019f, Theme.CREAM);
            return;
        }
        String pip;
        if (card.kind == Card.Kind.NUMBER) pip = String.valueOf(card.number);
        else if (card.kind == Card.Kind.PULL2) pip = "+2";
        else if (card.kind == Card.Kind.PULL4) pip = "+4";
        else pip = "1";
        if (card.kind == Card.Kind.NUMBER && (card.number == 6 || card.number == 9)) {
            underline(c, cx, cy + H * 0.075f, W * 0.075f, W * 0.016f, Theme.CREAM);
        }
        float size = H * (pip.length() > 1 ? 0.105f : 0.125f);
        tp.setTextSize(size);
        float by = Gfx.centeredBaseline(tp, pip, cy);
        Gfx.outlinedText(c, tp, pip, cx, by, Theme.CREAM,
                Color.argb(210, Color.red(deep), Color.green(deep), Color.blue(deep)), W * 0.030f);
    }

    private void underline(Canvas c, float cx, float y, float len, float thick, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        tmp.set(cx - len / 2f, y - thick / 2f, cx + len / 2f, y + thick / 2f);
        c.drawRoundRect(tmp, thick / 2f, thick / 2f, p);
    }

    private void drawJump(Canvas c, float cx, float cy, float r, float stroke, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(stroke);
        p.setColor(color);
        c.drawCircle(cx, cy, r, p);
        float d = r * 0.70f;
        c.drawLine(cx - d, cy + d, cx + d, cy - d, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSpin(Canvas c, float cx, float cy, float r, float stroke, int color) {
        arcArrow(c, cx, cy, r, 200, 125, stroke, color);
        arcArrow(c, cx, cy, r, 20, 125, stroke, color);
    }

    /** A clockwise arc ending in a solid arrowhead aligned to the arc's tangent. */
    private void arcArrow(Canvas c, float cx, float cy, float r, float startDeg, float sweep,
                          float stroke, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(stroke);
        p.setColor(color);
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(tmp, startDeg, sweep, false, p);
        double e = Math.toRadians(startDeg + sweep);
        float px = cx + (float) Math.cos(e) * r;
        float py = cy + (float) Math.sin(e) * r;
        float tx = (float) -Math.sin(e), ty = (float) Math.cos(e);
        float nx = (float) Math.cos(e), ny = (float) Math.sin(e);
        float hl = stroke * 2.1f, hw = stroke * 1.55f;
        path.reset();
        path.moveTo(px + tx * hl, py + ty * hl);
        path.lineTo(px + nx * hw, py + ny * hw);
        path.lineTo(px - nx * hw, py - ny * hw);
        path.close();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(path, p);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawWildWheel(Canvas c, RectF ov, String label) {
        float cx = ov.centerX(), cy = ov.centerY();
        float rr = Math.min(ov.width(), ov.height()) * 0.41f;
        Card.Color[] cols = Card.Color.values();
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        tmp.set(cx - rr, cy - rr, cx + rr, cy + rr);
        for (int i = 0; i < 4; i++) {
            int col = Theme.cardColor(cols[i]);
            Gfx.shader(p, new RadialGradient(cx - rr * 0.3f, cy - rr * 0.35f, rr * 1.5f,
                    Theme.shade(col, 1.18f), Theme.shade(col, 0.82f), Shader.TileMode.CLAMP));
            c.drawArc(tmp, -90 + i * 90, 90, true, p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, rr * 0.05f));
        p.setColor(Color.argb(150, 20, 10, 5));
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(-90 + i * 90);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr, p);
        }
        c.drawCircle(cx, cy, rr, p);
        p.setStyle(Paint.Style.FILL);
        float hub = rr * 0.56f;
        p.setColor(Theme.CREAM);
        c.drawCircle(cx, cy, hub, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, rr * 0.06f));
        p.setColor(Color.argb(200, 20, 10, 5));
        c.drawCircle(cx, cy, hub, p);
        p.setStyle(Paint.Style.FILL);
        glyph(c, label, cx, cy, hub * 1.05f, Color.rgb(24, 20, 28));
    }
}
