package com.touchone.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Setup screen and Scoring Game scoreboard, drawn as real tappable UI over the art plates (no flattened mock-up).
 * Layout and look mirror www/js/screens.js. State lives in {@link ModeState}; this class only draws and hit-tests.
 *
 * Adding a Setup option: add a row in {@link #rowCount()}/layoutSetup()/drawSetup() the same way the mode list and the
 * "Points to win" field are laid out; rows flow top to bottom so the screen does not need redesigning.
 */
final class ModeUi {
    interface Host { void sfx(String name); }

    static final int GOLD = Color.rgb(255, 214, 64);
    static final int GOLD_DEEP = Color.rgb(255, 160, 24);
    static final int CREAM = Color.rgb(255, 244, 220);
    static final int WOOD_INK = Color.rgb(58, 30, 12);
    static final int ALERT = Color.rgb(226, 38, 38);
    static final int GREEN = Color.rgb(34, 197, 74);
    static final int PURPLE = Color.rgb(147, 51, 234);
    static final int PINK = Color.rgb(236, 72, 153);
    static final int ORANGE = Color.rgb(249, 115, 22);

    static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "back", "0", "done"};

    private static final int P_NONE = 0, P_BACK = 1, P_CARD = 2, P_FIELD = 3, P_KEY = 4, P_BUTTON = 5;

    private final ModeState st;
    private final Host host;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // ---- layout (rebuilt every frame from the current size and state)
    final RectF back = new RectF();
    final RectF[] cards = new RectF[ModeState.MODES.length];
    final RectF field = new RectF();
    final RectF pad = new RectF();
    final RectF[] keys = new RectF[KEYS.length];
    final RectF button = new RectF();
    boolean hasField, hasPad;
    private float q, titleBase, hintBase;
    private final float[] labelBase = new float[2];
    private int labelCount;

    private int pressKind = P_NONE, pressIdx = -1;
    private long errAt = -1000000L;
    long setupAt, scoresAt;

    ModeUi(ModeState st, Host host) {
        this.st = st;
        this.host = host;
        t.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = 0; i < cards.length; i++) cards[i] = new RectF();
        for (int i = 0; i < keys.length; i++) keys[i] = new RectF();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------------
    private static int shade(int c, float m) {
        return Color.rgb(Math.min(255, (int) (Color.red(c) * m)), Math.min(255, (int) (Color.green(c) * m)),
                Math.min(255, (int) (Color.blue(c) * m)));
    }

    private void fillRR(Canvas c, float l, float tp, float r, float b, float rad, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(new RectF(l, tp, r, b), rad, rad, p);
    }

    private void fillRRGrad(Canvas c, float l, float tp, float r, float b, float rad, int top, int bottom) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, tp, 0, b, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(l, tp, r, b), rad, rad, p);
        p.setShader(null);
    }

    private void strokeRR(Canvas c, float l, float tp, float r, float b, float rad, int color, float w) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawRoundRect(new RectF(l, tp, r, b), rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    private float fit(String s, float size, float maxW, float minSize) {
        t.setTextSize(size);
        float w = t.measureText(s);
        return w <= maxW ? size : Math.max(minSize, size * maxW / w);
    }

    private float baselineFor(float cy) {
        Paint.FontMetrics fm = t.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    /** Plain text that shrinks to maxW (maxW <= 0: no limit). */
    private void text(Canvas c, String s, float x, float base, float size, float maxW, int color, Paint.Align align) {
        t.setStyle(Paint.Style.FILL);
        t.setTextSize(maxW > 0 ? fit(s, size, maxW, size * 0.6f) : size);
        t.setTextAlign(align);
        t.setColor(color);
        c.drawText(s, x, base, t);
    }

    /** Centred text with a dark outline and soft drop shadow, like the other labels in the game. */
    private void outlined(Canvas c, String s, float cx, float base, float size, int fill, int outline, float ow) {
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(size);
        t.setStyle(Paint.Style.FILL);
        t.setColor(Color.argb(120, 20, 8, 0));
        c.drawText(s, cx, base + size * 0.07f, t);
        t.setStyle(Paint.Style.STROKE);
        t.setStrokeWidth(ow);
        t.setStrokeJoin(Paint.Join.ROUND);
        t.setColor(outline);
        c.drawText(s, cx, base, t);
        t.setStyle(Paint.Style.FILL);
        t.setColor(fill);
        c.drawText(s, cx, base, t);
    }

    private void drawBg(Canvas c, Bitmap bg, int w, int h) {
        if (bg != null) c.drawBitmap(bg, null, new Rect(0, 0, w, h), p);
        else {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(74, 40, 70), Color.rgb(120, 70, 36), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
    }

    private void drawBackPill(Canvas c, int w, boolean pressed) {
        float l = w * 0.03f, tp = w * 0.03f, r = l + w * 0.20f, b = tp + w * 0.095f;
        back.set(l, tp, r, b);
        c.save();
        c.translate((l + r) / 2f, (tp + b) / 2f);
        float s = pressed ? 0.92f : 1f;
        c.scale(s, s);
        float hw = (r - l) / 2f, hh = (b - tp) / 2f;
        fillRR(c, -hw, -hh, hw, hh, hh, Color.argb(120, 20, 8, 0));
        strokeRR(c, -hw, -hh, hw, hh, hh, Color.argb(90, 255, 244, 220), Math.max(2f, w * 0.003f));
        t.setTextSize(w * 0.038f);
        text(c, "‹ Menu", 0, baselineFor(0), w * 0.038f, 0, CREAM, Paint.Align.CENTER);
        c.restore();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Setup screen
    // ---------------------------------------------------------------------------------------------------------------
    private void layoutSetup(int w, int h) {
        q = Math.min(w, h * 0.5f);
        float left = w * 0.06f, cw = w * 0.88f;
        titleBase = h * 0.125f;
        labelCount = 0;
        hasField = false;
        hasPad = false;
        float y = h * 0.20f;
        // Row 1: the mode list (one card per entry in ModeState.MODES).
        labelBase[labelCount++] = y + q * 0.05f;
        y += q * 0.085f;
        float oh = q * 0.21f, gap = q * 0.03f;
        for (int i = 0; i < cards.length; i++) {
            cards[i].set(left, y, left + cw, y + oh);
            y += oh + gap;
        }
        y += q * 0.02f;
        // Row 2: points to win (Scoring Game only).
        if (st.mode == ModeState.Mode.SCORING) {
            labelBase[labelCount++] = y + q * 0.05f;
            y += q * 0.085f;
            float fh = q * 0.17f, fw = cw * 0.62f;
            field.set(w / 2f - fw / 2f, y, w / 2f + fw / 2f, y + fh);
            hasField = true;
            y += fh;
            hintBase = y + q * 0.072f;
        }
        if (st.editing() && hasField) {
            float kh = q * 0.125f, kg = q * 0.022f, kw = Math.min(cw * 0.3f, q * 0.3f);
            float total = 4 * kh + 3 * kg, bottom = h - Math.max(h * 0.03f, q * 0.04f);
            float top = bottom - total, pl = w / 2f - (3 * kw + 2 * kg) / 2f;
            pad.set(pl - kg * 1.4f, top - kg * 1.4f, pl + 3 * kw + 2 * kg + kg * 1.4f, bottom + kg * 1.4f);
            hasPad = true;
            for (int i = 0; i < KEYS.length; i++) {
                int col = i % 3, row = i / 3;
                float x0 = pl + col * (kw + kg), y0 = top + row * (kh + kg);
                keys[i].set(x0, y0, x0 + kw, y0 + kh);
            }
        }
    }

    /** Draws the Setup screen. Returns true while something animates (caret), so the caller keeps redrawing. */
    boolean drawSetup(Canvas c, int w, int h, Bitmap bg, long now) {
        drawBg(c, bg, w, h);
        layoutSetup(w, h);
        float u = w / 1080f;
        outlined(c, "Setup", w / 2f, titleBase, q * 0.15f, GOLD, WOOD_INK, q * 0.035f);
        String[] labels = {"Game mode", "Points to win"};
        for (int i = 0; i < labelCount; i++) {
            outlined(c, labels[i], w / 2f, labelBase[i], q * 0.052f, CREAM, WOOD_INK, q * 0.013f);
        }
        for (int i = 0; i < cards.length; i++) {
            ModeState.ModeInfo info = ModeState.MODES[i];
            boolean sel = st.mode == info.mode;
            RectF rc = cards[i];
            float ow = rc.width(), oh = rc.height(), rad = oh * 0.26f;
            c.save();
            c.translate(rc.centerX(), rc.centerY());
            float s = (pressKind == P_CARD && pressIdx == i) ? 0.97f : 1f;
            c.scale(s, s);
            float x = -ow / 2f, y = -oh / 2f;
            fillRR(c, x, y + oh * 0.05f, x + ow, y + oh * 1.05f, rad, Color.argb(70, 20, 8, 0));
            if (sel) fillRRGrad(c, x, y, x + ow, y + oh, rad, Color.argb(235, 74, 34, 82), Color.argb(235, 48, 20, 58));
            else fillRR(c, x, y, x + ow, y + oh, rad, Color.argb(190, 36, 16, 42));
            strokeRR(c, x, y, x + ow, y + oh, rad, sel ? GOLD : Color.argb(120, 255, 244, 220),
                    sel ? Math.max(3f, u * 5f) : Math.max(2f, u * 2.5f));
            float rcx = x + oh * 0.5f, rrad = oh * 0.19f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(120, 0, 0, 0));
            c.drawCircle(rcx, 0, rrad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, u * 3f));
            p.setColor(sel ? GOLD : CREAM);
            c.drawCircle(rcx, 0, rrad, p);
            if (sel) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(GOLD);
                c.drawCircle(rcx, 0, rrad * 0.55f, p);
            }
            float tx = x + oh * 0.95f, tw = ow - oh * 1.15f;
            text(c, info.title, tx, -oh * 0.04f, q * 0.066f, tw, sel ? GOLD : Color.WHITE, Paint.Align.LEFT);
            text(c, info.blurb, tx, oh * 0.30f, q * 0.038f, tw, sel ? CREAM : Color.argb(217, 255, 244, 220), Paint.Align.LEFT);
            c.restore();
        }
        boolean animating = false;
        if (hasField) animating = drawTargetField(c, w, now);
        if (hasPad) drawKeypad(c, u);
        drawBackPill(c, w, pressKind == P_BACK);
        return animating;
    }

    private boolean drawTargetField(Canvas c, int w, long now) {
        float u = w / 1080f;
        boolean editing = st.editing();
        String shown = editing ? st.draft : st.targetText;
        boolean valid = Scoring.normalizeTarget(shown) != null;
        boolean err = editing && (!valid || now - errAt < 900);
        float fw = field.width(), fh = field.height(), rad = fh * 0.3f;
        c.save();
        c.translate(field.centerX(), field.centerY());
        float s = (pressKind == P_FIELD) ? 0.97f : 1f;
        c.scale(s, s);
        float x = -fw / 2f, y = -fh / 2f, rim = fh * 0.06f;
        fillRR(c, x, y + fh * 0.06f, x + fw, y + fh * 1.06f, rad, Color.argb(70, 20, 8, 0));
        fillRR(c, x, y, x + fw, y + fh, rad, Color.WHITE);
        if (editing) fillRR(c, x + rim, y + rim, x + fw - rim, y + fh - rim, rad - rim, Color.rgb(255, 250, 235));
        else fillRRGrad(c, x + rim, y + rim, x + fw - rim, y + fh - rim, rad - rim, Color.rgb(255, 244, 220), Color.rgb(240, 222, 190));
        if (editing) strokeRR(c, x + rim, y + rim, x + fw - rim, y + fh - rim, rad - rim, err ? ALERT : GOLD_DEEP, Math.max(3f, u * 4f));
        String txt = shown.isEmpty() ? "0" : shown;
        float maxW = fw - fh * 0.7f;
        float size = fit(txt, fh * 0.56f, maxW, fh * 0.26f);
        t.setTextSize(size);
        float tw = t.measureText(txt);
        if (tw > maxW) { // very long targets keep their last digits
            while (txt.length() > 2 && t.measureText("…" + txt) > maxW) txt = txt.substring(1);
            txt = "…" + txt;
            tw = t.measureText(txt);
        }
        float base = baselineFor(0);
        if (editing && st.fresh) fillRR(c, -tw / 2f - fh * 0.08f, -size * 0.55f, tw / 2f + fh * 0.08f, size * 0.55f, fh * 0.12f, Color.argb(90, 255, 190, 60));
        if (!shown.isEmpty()) text(c, txt, 0, base, size, 0, WOOD_INK, Paint.Align.CENTER);
        boolean animating = false;
        if (editing && !st.fresh) {
            animating = true;
            if ((now / 480) % 2 == 0) fillRR(c, tw / 2f + size * 0.06f, -size * 0.5f, tw / 2f + size * 0.06f + Math.max(2f, u * 3f), size * 0.5f, 0f, GOLD_DEEP);
        }
        c.restore();
        String hint = !editing ? "Tap the box to change it" : (!valid ? "Enter a whole number above 0" : "Tap Done when finished");
        outlined(c, hint, w / 2f, hintBase, q * 0.04f, err ? Color.rgb(255, 150, 140) : CREAM, WOOD_INK, q * 0.011f);
        return animating || (editing && now - errAt < 900);
    }

    private void drawKeypad(Canvas c, float u) {
        fillRR(c, pad.left, pad.top, pad.right, pad.bottom, q * 0.06f, Color.argb(185, 28, 12, 34));
        strokeRR(c, pad.left, pad.top, pad.right, pad.bottom, q * 0.06f, Color.argb(110, 255, 244, 220), Math.max(2f, u * 2.5f));
        boolean valid = st.editing() && Scoring.normalizeTarget(st.draft) != null;
        for (int i = 0; i < KEYS.length; i++) {
            String k = KEYS[i];
            RectF rc = keys[i];
            float kw = rc.width(), kh = rc.height();
            boolean isDone = k.equals("done"), isBack = k.equals("back");
            int base = isDone ? Color.rgb(36, 200, 92) : isBack ? PINK : PURPLE;
            c.save();
            c.translate(rc.centerX(), rc.centerY());
            float s = (pressKind == P_KEY && pressIdx == i) ? 0.93f : 1f;
            c.scale(s, s);
            float x = -kw / 2f, y = -kh / 2f, rad = kh * 0.27f, rim = kh * 0.06f;
            int alpha = (isDone && !valid) ? 140 : 255;
            fillRR(c, x, y + kh * 0.06f, x + kw, y + kh * 1.06f, rad, Color.argb(Math.min(80, alpha), 10, 4, 0));
            fillRR(c, x, y, x + kw, y + kh, rad, Color.argb(alpha, 255, 255, 255));
            fillRRGrad(c, x + rim, y + rim, x + kw - rim, y + kh - rim, rad - rim,
                    withAlpha(shade(base, 1.25f), alpha), withAlpha(shade(base, 0.82f), alpha));
            if (isBack) drawBackspace(c, kh);
            else {
                String label = isDone ? "Done" : k;
                float size = kh * (isDone ? 0.4f : 0.56f);
                t.setTextSize(size);
                float by = baselineFor(0);
                outlined(c, label, 0, by, size, Color.argb(alpha, 255, 255, 255), shade(base, 0.55f), kh * 0.05f);
            }
            c.restore();
        }
    }

    private static int withAlpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void drawBackspace(Canvas c, float h) {
        float w = h * 0.62f, hh = h * 0.36f;
        path.reset();
        path.moveTo(-w / 2f, 0);
        path.lineTo(-w / 2f + hh * 0.7f, -hh);
        path.lineTo(w / 2f, -hh);
        path.lineTo(w / 2f, hh);
        path.lineTo(-w / 2f + hh * 0.7f, hh);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(h * 0.05f);
        p.setColor(shade(PINK, 0.55f));
        c.drawPath(path, p);
        p.setStrokeWidth(h * 0.07f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(shade(PINK, 0.7f));
        float cx = w * 0.12f, d = hh * 0.42f;
        c.drawLine(cx - d, -d, cx + d, d, p);
        c.drawLine(cx + d, -d, cx - d, d, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- Setup touch -------------------------------------------------------------------------------------------------
    private boolean in(RectF r, float x, float y, float by) {
        return x >= r.left - by && x <= r.right + by && y >= r.top - by && y <= r.bottom + by;
    }

    private int[] hitSetup(float x, float y, float u) {
        if (in(back, x, y, 6 * u)) return new int[]{P_BACK, 0};
        if (hasPad) {
            for (int i = 0; i < keys.length; i++) if (in(keys[i], x, y, 3 * u)) return new int[]{P_KEY, i};
            if (in(pad, x, y, 0)) return new int[]{P_NONE, 0};
        }
        for (int i = 0; i < cards.length; i++) if (in(cards[i], x, y, 0)) return new int[]{P_CARD, i};
        if (hasField && in(field, x, y, 8 * u)) return new int[]{P_FIELD, 0};
        return new int[]{P_NONE, 0};
    }

    /** action: 0 down, 1 up, 2 cancel. Returns true when Back was tapped (the caller leaves Setup). */
    boolean touchSetup(int action, float x, float y, float u, long now) {
        if (action == 0) {
            if (now - setupAt < 350) { pressKind = P_NONE; return false; }
            int[] h = hitSetup(x, y, u);
            pressKind = h[0];
            pressIdx = h[1];
            return false;
        }
        if (action == 2) { pressKind = P_NONE; return false; }
        int kind = pressKind, idx = pressIdx;
        pressKind = P_NONE;
        int[] h = hitSetup(x, y, u);
        if (kind == P_NONE) {
            // tapping empty space while typing finishes the edit (a valid value is kept, an invalid one dropped)
            if (st.editing() && h[0] == P_NONE && !(hasPad && in(pad, x, y, 0))) st.commitDraftOrRevert();
            return false;
        }
        if (h[0] != kind || h[1] != idx) return false;
        switch (kind) {
            case P_BACK:
                return true;
            case P_CARD:
                if (st.mode != ModeState.MODES[idx].mode) host.sfx("sfx_select");
                st.selectMode(ModeState.MODES[idx].mode);
                break;
            case P_FIELD:
                if (!st.editing()) { st.openEdit(); host.sfx("sfx_click"); }
                break;
            case P_KEY:
                host.sfx("sfx_click");
                if (st.padKey(KEYS[idx]) == ModeState.KeyResult.REJECTED) errAt = now;
                break;
            default:
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Scoreboard
    // ---------------------------------------------------------------------------------------------------------------
    void drawScores(Canvas c, int w, int h, Bitmap bg, Bitmap buttonBmp, String[] names) {
        drawBg(c, bg, w, h);
        Scoring.Match m = st.match;
        Scoring.RoundResult res = st.result;
        float u = w / 1080f;
        button.set(w * 0.18f, h * 0.78f, w * 0.82f, h * 0.88f);
        if (m == null || res == null) { drawBackPill(c, w, pressKind == P_BACK); return; }
        float qq = Math.min(w, h * 0.5f);
        boolean over = res.matchOver;
        String head = over ? "Match Over" : "Round " + res.round;
        float hs = fit(head, qq * 0.14f, w * 0.5f, qq * 0.07f);
        outlined(c, head, w / 2f, h * 0.098f, hs, GOLD, WOOD_INK, hs * 0.25f);
        String winName = names[over ? res.matchWinner : res.winner];
        String line = over ? (res.matchWinner == 0 ? "You win the match!" : winName + " wins the match!")
                : (res.winner == 0 ? "You won the round!" : winName + " won the round");
        outlined(c, line, w / 2f, h * 0.142f, qq * 0.062f, CREAM, WOOD_INK, qq * 0.014f);

        int seats = m.seats;
        float rh = qq * 0.17f, head1 = qq * 0.09f, foot = qq * 0.1f;
        float pl = w * 0.05f, pw = w * 0.9f, pt = h * 0.17f, ph = head1 + seats * rh + foot;
        fillRR(c, pl, pt + qq * 0.01f, pl + pw, pt + qq * 0.01f + ph, qq * 0.05f, Color.argb(70, 10, 4, 0));
        fillRR(c, pl, pt, pl + pw, pt + ph, qq * 0.05f, Color.argb(205, 30, 12, 38));
        strokeRR(c, pl, pt, pl + pw, pt + ph, qq * 0.05f, Color.argb(120, 255, 244, 220), Math.max(2f, u * 2.5f));
        float colRound = pl + pw * 0.62f, colTotal = pl + pw * 0.86f;
        int dim = Color.argb(191, 255, 244, 220);
        text(c, "Player", pl + pw * 0.05f, pt + head1 * 0.68f, qq * 0.036f, 0, dim, Paint.Align.LEFT);
        text(c, "Round", colRound, pt + head1 * 0.68f, qq * 0.036f, 0, dim, Paint.Align.CENTER);
        text(c, "Total", colTotal, pt + head1 * 0.68f, qq * 0.036f, 0, dim, Paint.Align.CENTER);
        for (int s = 0; s < seats; s++) {
            float y = pt + head1 + s * rh;
            Scoring.SeatResult r = res.seats.get(s);
            boolean win = s == res.winner;
            if (win) {
                fillRR(c, pl + pw * 0.015f, y + rh * 0.06f, pl + pw * 0.985f, y + rh * 0.94f, qq * 0.035f, Color.argb(60, 255, 214, 64));
                strokeRR(c, pl + pw * 0.015f, y + rh * 0.06f, pl + pw * 0.985f, y + rh * 0.94f, qq * 0.035f, GOLD, Math.max(2f, u * 3f));
            } else if (s > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(40, 255, 244, 220));
                c.drawRect(pl + pw * 0.05f, y, pl + pw * 0.95f, y + Math.max(1f, u * 1.5f), p);
            }
            float nameW = pw * 0.5f;
            text(c, names[s], pl + pw * 0.05f, y + rh * 0.45f, qq * 0.06f, nameW, win ? GOLD : Color.WHITE, Paint.Align.LEFT);
            String sub = win ? "Went out" : r.cards + (r.cards == 1 ? " card" : " cards") + " left = " + r.value + " pts";
            text(c, sub, pl + pw * 0.05f, y + rh * 0.8f, qq * 0.036f, nameW, Color.argb(217, 255, 244, 220), Paint.Align.LEFT);
            t.setTextSize(qq * 0.07f);
            text(c, win ? "+" + res.points : "–", colRound, baselineFor(y + rh * 0.5f), qq * 0.07f, 0,
                    win ? GOLD : Color.argb(120, 255, 244, 220), Paint.Align.CENTER);
            String tot = String.valueOf(res.totals[s]);
            t.setTextSize(qq * 0.08f);
            text(c, tot, colTotal, baselineFor(y + rh * 0.5f), qq * 0.08f, pw * 0.2f, Color.WHITE, Paint.Align.CENTER);
        }
        float fy = pt + head1 + seats * rh + foot * 0.62f;
        text(c, "First to " + m.target + " points wins", w / 2f, fy, qq * 0.04f, pw * 0.9f, Color.argb(230, 255, 244, 220), Paint.Align.CENTER);

        if (buttonBmp != null) {
            c.save();
            float s = pressKind == P_BUTTON ? 0.94f : 1f;
            c.translate(button.centerX(), button.centerY());
            c.scale(s, s);
            c.drawBitmap(buttonBmp, null, new RectF(-button.width() / 2f, -button.height() / 2f, button.width() / 2f, button.height() / 2f), p);
            c.restore();
        }
        drawBackPill(c, w, pressKind == P_BACK);
    }

    /** action: 0 down, 1 up, 2 cancel. Returns 1 for the Next Round / New Game button, 2 for Back, else 0. */
    int touchScores(int action, float x, float y, float u, long now) {
        if (action == 0) {
            if (in(back, x, y, 6 * u)) pressKind = P_BACK;
            else if (now - scoresAt > 750 && in(button, x, y, 0)) pressKind = P_BUTTON;
            else pressKind = P_NONE;
            return 0;
        }
        int kind = pressKind;
        pressKind = P_NONE;
        if (action == 2) return 0;
        if (kind == P_BUTTON && in(button, x, y, 0)) return 1;
        if (kind == P_BACK && in(back, x, y, 6 * u)) return 2;
        return 0;
    }
}
