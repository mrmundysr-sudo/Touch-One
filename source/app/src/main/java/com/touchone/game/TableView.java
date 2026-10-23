package com.touchone.game;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;

/**
 * The whole game surface.
 *
 * Timing model: everything runs off one game clock ({@link #gt}) that advances only
 * while frames are drawn and is clamped per frame. Animations, opponent "thinking"
 * delays and sound cues are all scheduled on that clock, so a hitch slows the game
 * down instead of skipping it, sound can never drift from the picture, and the game
 * pauses by itself when the app leaves the screen.
 *
 * The rules {@link Engine} resolves a move instantly; this view then plays the move
 * out as a short timeline (card flight, landing, effect, hand-over) while input is
 * locked. "Shown" fields below are what the player currently sees, which lags the
 * engine by exactly the animation in flight.
 */
final class TableView extends View {
    enum Screen { SPLASH, GAME, COLOR, WIN, LOSS }

    private static final String PKG = "com.touchone.game";

    // Layout tuned to the artwork (fractions of the screen).
    private static final float[][] HATS1 = {{0.24f, 0.125f}};
    private static final float[][] HATS2 = {{0.22f, 0.135f}, {0.78f, 0.135f}};
    private static final float[][] HATS3 = {{0.16f, 0.225f}, {0.50f, 0.205f}, {0.84f, 0.210f}};
    private static final int[] STYLE1 = {1};
    private static final int[] STYLE2 = {0, 1};
    private static final int[] STYLE3 = {0, 2, 1};
    private static final float[][] GLOW1 = {{0.5582f, 0.2302f}};
    private static final float[][] GLOW2 = {{0.2519f, 0.3776f}, {0.7788f, 0.3992f}};
    private static final float[][] GLOW3 = {{0.1483f, 0.4617f}, {0.5021f, 0.4530f}, {0.8425f, 0.4524f}};

    // Timing (ms, game clock).
    private static final long CALL_HOLD_MS = 750;
    private static final long WHEEL_SPIN_MS = 1150;
    private static final long WHEEL_PICKABLE_MS = 380;
    private static final long FADE_OUT_MS = 190;
    private static final long FADE_IN_MS = 330;
    private static final long END_INPUT_GUARD_MS = 750;

    private final Sfx sfx;
    private final Engine engine;
    private final Resources res;
    private final Random rnd = new Random();

    private Screen screen = Screen.SPLASH;
    private int W, H;
    private float u;                 // 1 unit = W/1080 px

    // ---- clock + timeline ------------------------------------------------
    private long gt;
    private long lastReal;
    private float frameDt = 16f;
    private boolean resumed = true;

    private static final class Task {
        final long at;
        final Runnable run;
        final boolean ui;
        Task(long at, Runnable run, boolean ui) { this.at = at; this.run = run; this.ui = ui; }
    }
    private final ArrayList<Task> tasks = new ArrayList<>();

    // ---- paints ----------------------------------------------------------
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF dst = new RectF();
    private final RectF tmp = new RectF();
    private final Path path = new Path();

    // ---- bitmaps ---------------------------------------------------------
    private final HashMap<String, Bitmap> scenes = new HashMap<>();
    private final HashSet<String> missing = new HashSet<>();
    private volatile Movie winMovie;
    private Bitmap scoobertSheet;
    private CardArt art;
    private float cardW, cardH;
    private Bitmap endSignBmp, againBmp, arrowBmp, wheelBmp;
    private final Bitmap[] tileBmp = new Bitmap[3];
    private final Bitmap[] bubble = new Bitmap[3];     // 0 left trail, 1 right trail, 2 down trail
    private Bitmap bubblePlayer;
    private int bubblesFor = -1;
    private Paint glowPaint;
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- hit rects -------------------------------------------------------
    private final RectF deckRect = new RectF();
    private final RectF discardRect = new RectF();
    private final RectF endRect = new RectF();
    private final RectF againRect = new RectF();
    private final RectF[] tileRect = {new RectF(), new RectF(), new RectF()};

    // ---- what the player currently sees ---------------------------------
    private int shownCurrent = -1;
    private final int[] shownCount = new int[4];
    private final long[] countPopAt = new long[4];
    private final long[] lowFlashUntil = new long[4];
    private final int[] lastShown = new int[4];
    private Card shownTop, prevTop;
    private Card.Color shownLive;
    private float arrowFrom = 1f, arrowTo = 1f;
    private long arrowFlipAt;
    private long discardBumpAt;
    private boolean busy;            // a sequence is playing: input locked
    private boolean dealing;
    private int selected;

    // Hand: one animated slot per card so the fan glides rather than snaps.
    private static final class Slot { float x, y, s, rot; }
    private final IdentityHashMap<Card, Slot> slots = new IdentityHashMap<>();
    private final IdentityHashMap<Card, Boolean> hidden = new IdentityHashMap<>();
    private float[] hx = new float[16], hy = new float[16], hw = new float[16], hr = new float[16];
    private final float[] tgt = new float[4];
    private float dimAmt;
    private Card shakeCard;
    private long shakeAt;
    private float dragDy;
    private boolean dragUp;

    // ---- effects ---------------------------------------------------------
    private static final class Fly {
        Card card;
        boolean back, flip;
        float sx, sy, sw, sr, ex, ey, ew, er, arc;
        boolean fadeOut;
        long start, dur;
        Runnable onLand;
    }
    private final ArrayList<Fly> flies = new ArrayList<>();
    private final ArrayList<Fly> landed = new ArrayList<>();

    private static final class Pop {
        String text; float x, y; long at, dur; int color; float size;
    }
    private final ArrayList<Pop> pops = new ArrayList<>();

    private static final int MAX_P = 120;
    private final float[] px = new float[MAX_P], py = new float[MAX_P], pvx = new float[MAX_P],
            pvy = new float[MAX_P], plife = new float[MAX_P], pmax = new float[MAX_P],
            psize = new float[MAX_P], prot = new float[MAX_P];
    private final int[] pcol = new int[MAX_P];
    private int pn;

    private static final class Ring { float x, y, maxR; long at; int color; }
    private final ArrayList<Ring> rings = new ArrayList<>();

    private long flashAt = -100000;            // TOUCH ONE banner
    private String chipText;                   // "Color is Pink"
    private int chipColor;
    private long chipAt = -100000;
    private String flashMsg;
    private long flashMsgUntil;
    private String statusShown = "", statusPrev = "";
    private long statusChangedAt;
    private long humanTurnAt;

    // ---- hold-to-call ----------------------------------------------------
    private boolean holding;
    private boolean callArmed;
    private long holdStart;
    private int holdStream;

    // ---- wheel -----------------------------------------------------------
    private int colorPickFor = -1;
    private long wheelAt;
    private float wheelTurns;
    private int wheelPicked = -1;
    private long wheelPickAt;
    private int lastTickStep;
    private long lastTickAt;
    private float wheelCx() { return W / 2f; }
    private float wheelCy() { return H * 0.48f; }
    private float wheelR() { return W * 0.28f; }

    // ---- input -----------------------------------------------------------
    private static final int P_NONE = 0, P_TILE = 1, P_AGAIN = 2, P_END = 3, P_DECK = 4, P_DISCARD = 5, P_HAND = 6;
    private int pressId = P_NONE;
    private int pressTile = -1;
    private float downX, downY;
    private int dragStartSel;
    private boolean scrubbed;
    private long lastSelectSfx;

    // ---- screens / transitions ------------------------------------------
    private long splashAt;
    private long endAt;
    private boolean transition;
    private int fadePhase;           // 0 none, 1 out, 2 in
    private long fadePhaseAt;

    TableView(Context ctx, Sfx sfx, Engine engine) {
        super(ctx);
        this.sfx = sfx;
        this.engine = engine;
        this.res = ctx.getResources();
        text.setTypeface(Theme.font);
        text.setTextAlign(Paint.Align.CENTER);
        dashPaint.setStyle(Paint.Style.STROKE);
        setHapticFeedbackEnabled(true);
        preloadWinMovie();
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================
    void onHostPause() {
        resumed = false;
        if (holding) {
            holding = false;
            sfx.stop(holdStream);
        }
    }

    void onHostResume() {
        resumed = true;
        lastReal = 0;
        invalidate();
    }

    /** @return true when the view consumed back (returned to the title screen). */
    boolean onBack() {
        if (transition) return true;
        if (screen == Screen.SPLASH) return false;
        sfx.play("sfx_click");
        fadeTo(new Runnable() {
            @Override public void run() {
                clearGameTasks();
                flies.clear();
                holding = false;
                screen = Screen.SPLASH;
                splashAt = gt;
                trimScenes("bg_splash");
            }
        });
        return true;
    }

    private void preloadWinMovie() {
        new Thread(new Runnable() {
            @Override public void run() {
                int id = res.getIdentifier("win_scoobert", "raw", PKG);
                if (id == 0) return;
                try (InputStream in = res.openRawResource(id)) {
                    winMovie = Movie.decodeStream(in);
                } catch (Exception ignored) {
                    winMovie = null;
                }
            }
        }, "win-movie").start();
    }

    // =====================================================================
    // Clock and timeline
    // =====================================================================
    private void advanceClock() {
        long real = SystemClock.uptimeMillis();
        if (lastReal == 0) lastReal = real;
        long d = Math.max(0, Math.min(50, real - lastReal));
        lastReal = real;
        frameDt = d;
        gt += d;
    }

    private void after(long delay, Runnable r) { addTask(delay, r, false); }

    private void afterUi(long delay, Runnable r) { addTask(delay, r, true); }

    private void addTask(long delay, Runnable r, boolean ui) {
        Task t = new Task(gt + Math.max(0, delay), r, ui);
        int i = tasks.size();
        while (i > 0 && tasks.get(i - 1).at > t.at) i--;
        tasks.add(i, t);
    }

    private void clearGameTasks() {
        for (int i = tasks.size() - 1; i >= 0; i--) if (!tasks.get(i).ui) tasks.remove(i);
    }

    private void runTasks() {
        int guard = 0;
        while (!tasks.isEmpty() && tasks.get(0).at <= gt && guard++ < 300) {
            tasks.remove(0).run.run();
        }
    }

    // =====================================================================
    // Easing helpers
    // =====================================================================
    private static float clamp01(float t) { return t < 0f ? 0f : (t > 1f ? 1f : t); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float easeOutCubic(float t) { float k = 1f - t; return 1f - k * k * k; }
    private static float easeOutQuart(float t) { float k = 1f - t; return 1f - k * k * k * k; }
    private static float easeInCubic(float t) { return t * t * t; }
    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, k = t - 1f;
        return 1f + c3 * k * k * k + c1 * k * k;
    }
    private float smooth(float tauMs) { return 1f - (float) Math.exp(-frameDt / tauMs); }
    private float breath(float periodMs) {
        return 0.5f + 0.5f * (float) Math.sin(gt * 2.0 * Math.PI / periodMs);
    }

    // =====================================================================
    // Size + static assets
    // =====================================================================
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        W = w;
        H = h;
        u = w / 1080f;
        scenes.clear();
        missing.clear();
        bubblesFor = -1;
        buildStatic();
    }

    private Bitmap decode(String name) {
        int id = res.getIdentifier(name, "drawable", PKG);
        if (id == 0) return null;
        return BitmapFactory.decodeResource(res, id);
    }

    private void buildStatic() {
        cardW = W * 0.28f;
        cardH = cardW * 1.4f;
        art = new CardArt(res, Math.round(cardW), Math.round(cardH));

        float cw = W * 0.22f, chh = cw * 1.4f;
        deckRect.set(W * 0.16f, H * 0.50f, W * 0.16f + cw, H * 0.50f + chh);
        discardRect.set(W * 0.58f, H * 0.50f, W * 0.58f + cw, H * 0.50f + chh);
        float es = W * 0.16f, ecx = W * 0.0897f, ecy = H * 0.6151f;
        endRect.set(ecx - es / 2f, ecy - es / 2f, ecx + es / 2f, ecy + es / 2f);
        // Covers the "Play again" plate baked into the win animation (x .197-.803, y .79-.869).
        againRect.set(W * 0.178f, H * 0.781f, W * 0.822f, H * 0.8785f);

        float ts = W * 0.22f, gap = W * 0.06f, start = (W - (3 * ts + 2 * gap)) / 2f, ty = H * 0.86f;
        for (int i = 0; i < 3; i++) {
            float x = start + i * (ts + gap);
            tileRect[i].set(x, ty, x + ts, ty + ts);
        }

        int[] tc = {Theme.cardColor(Card.Color.GREEN), Theme.cardColor(Card.Color.PURPLE),
                Theme.cardColor(Card.Color.PINK)};
        for (int i = 0; i < 3; i++) tileBmp[i] = makeTile(i + 1, tc[i], Math.round(ts));
        againBmp = makeAgain(Math.round(againRect.width()), Math.round(againRect.height()));
        endSignBmp = makeEndSign(Math.round(es));
        arrowBmp = makeArrow(Math.round(W * 0.055f));
        wheelBmp = makeWheel();

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setShader(new RadialGradient(0, 0, 100f, Color.argb(105, 255, 232, 90),
                Color.argb(0, 255, 200, 40), Shader.TileMode.CLAMP));
        dashPaint.setPathEffect(new DashPathEffect(new float[]{16 * u, 12 * u}, 0));
    }

    private Bitmap scene(String name) {
        Bitmap b = scenes.get(name);
        if (b != null || W == 0 || missing.contains(name)) return b;
        Bitmap src = decode(name);
        if (src == null) {
            missing.add(name);
            return null;
        }
        b = Gfx.scaleSmooth(src, W, H);
        if (b != src) src.recycle();
        scenes.put(name, b);
        return b;
    }

    /** Big plates are 8-10 MB each at screen size; keep only what's on screen. */
    private void trimScenes(String... keep) {
        List<String> drop = new ArrayList<>();
        for (String k : scenes.keySet()) {
            boolean keepIt = false;
            for (String s : keep) if (s.equals(k)) keepIt = true;
            if (!keepIt) drop.add(k);
        }
        for (String k : drop) {
            Bitmap b = scenes.remove(k);
            if (b != null) b.recycle();
        }
    }

    private String tableSceneName(int opp) {
        return opp == 1 ? "bg_table_1" : opp == 2 ? "bg_table_2" : "bg_table_3";
    }

    private void prepareBubbles(int opp) {
        if (bubblesFor == opp) return;
        bubblesFor = opp;
        for (int i = 0; i < 3; i++) {
            if (bubble[i] != null) bubble[i].recycle();
            bubble[i] = null;
        }
        if (bubblePlayer != null) bubblePlayer.recycle();
        float bw = W * (opp == 1 ? 0.345f : opp == 2 ? 0.30f : 0.27f);
        int[] styles = opp == 1 ? STYLE1 : opp == 2 ? STYLE2 : STYLE3;
        String[] names = {"bubble_count", "bubble_count_right", "bubble_count_down"};
        for (int s : styles) {
            if (bubble[s] != null) continue;
            Bitmap src = decode(names[s]);
            if (src == null) src = decode("bubble_count");
            if (src == null) continue;
            int bh = Math.round(bw * (s == 2 ? 0.80f : 0.72f));
            bubble[s] = Gfx.scaleSmooth(src, Math.round(bw), bh);
            if (bubble[s] != src) src.recycle();
        }
        Bitmap src = decode("bubble_count");
        if (src != null) {
            float pw = W * 0.26f;
            bubblePlayer = Gfx.scaleSmooth(src, Math.round(pw), Math.round(pw * 0.68f));
            if (bubblePlayer != src) src.recycle();
        }
    }

    // ---- procedural chrome (all drawn in Outfit at full resolution) -------
    private Bitmap makeTile(int n, int color, int s) {
        int m = Math.round(s * 0.14f);
        int B = s + m * 2;
        Bitmap b = Bitmap.createBitmap(B, B, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = s * 0.27f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 12; i++) {
            float k = m * (i / 12f);
            p.setColor(Color.argb(12, 20, 8, 0));
            tmp.set(k, k + m * 0.35f, B - k, B - k + m * 0.35f);
            c.drawRoundRect(tmp, r + (m - k) * 0.5f, r + (m - k) * 0.5f, p);
        }
        p.setColor(Color.WHITE);
        tmp.set(m, m, m + s, m + s);
        c.drawRoundRect(tmp, r, r, p);
        float rim = s * 0.05f;
        tmp.set(m + rim, m + rim, m + s - rim, m + s - rim);
        Gfx.shader(p, new LinearGradient(0, m, 0, m + s, Theme.shade(color, 1.25f), Theme.shade(color, 0.82f),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, r - rim, r - rim, p);
        Gfx.shader(p, new LinearGradient(0, m, 0, m + s * 0.55f, Color.argb(95, 255, 255, 255),
                Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, r - rim, r - rim, p);
        p.setShader(null);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Theme.font);
        t.setTextSize(s * 0.62f);
        String num = String.valueOf(n);
        float by = Gfx.centeredBaseline(t, num, m + s * 0.5f);
        t.setStyle(Paint.Style.FILL);
        t.setTextAlign(Paint.Align.CENTER);
        t.setColor(Color.argb(70, 0, 0, 0));
        c.drawText(num, B / 2f, by + s * 0.03f, t);
        Gfx.outlinedText(c, t, num, B / 2f, by, Color.WHITE, Theme.shade(color, 0.55f), s * 0.05f);
        return b;
    }

    private Bitmap makeAgain(int w, int h) {
        int m = Math.round(h * 0.10f);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        float bw = w - m * 2f, bh = h - m * 2f, r = bh / 2f;
        for (int i = 0; i < 10; i++) {
            float k = m * (i / 10f);
            p.setColor(Color.argb(14, 10, 25, 10));
            tmp.set(k, k + m * 0.45f, w - k, h - k + m * 0.45f - m * 0.1f);
            c.drawRoundRect(tmp, r + (m - k), r + (m - k), p);
        }
        int green = Color.rgb(36, 200, 92);
        p.setColor(Color.rgb(16, 84, 42));
        tmp.set(m, m, m + bw, m + bh);
        c.drawRoundRect(tmp, r, r, p);
        float rim = bh * 0.07f;
        tmp.set(m + rim, m + rim, m + bw - rim, m + bh - rim);
        Gfx.shader(p, new LinearGradient(0, m, 0, m + bh, Theme.shade(green, 1.22f), Theme.shade(green, 0.78f),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, r - rim, r - rim, p);
        Gfx.shader(p, new LinearGradient(0, m, 0, m + bh * 0.5f, Color.argb(90, 255, 255, 255),
                Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        tmp.set(m + rim * 1.6f, m + rim * 1.2f, m + bw - rim * 1.6f, m + bh * 0.52f);
        c.drawRoundRect(tmp, r * 0.8f, r * 0.8f, p);
        p.setShader(null);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Theme.font);
        t.setTextSize(bh * 0.44f);
        String s = "Play again";
        float by = Gfx.centeredBaseline(t, s, m + bh / 2f);
        t.setStyle(Paint.Style.FILL);
        t.setTextAlign(Paint.Align.CENTER);
        t.setColor(Color.argb(80, 0, 40, 10));
        c.drawText(s, w / 2f, by + bh * 0.04f, t);
        Gfx.outlinedText(c, t, s, w / 2f, by, Color.WHITE, Color.rgb(14, 92, 44), bh * 0.055f);
        return b;
    }

    private Bitmap makeEndSign(int s) {
        int m = Math.round(s * 0.12f);
        int B = s + m * 2;
        Bitmap b = Bitmap.createBitmap(B, B, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = B / 2f, cy = B / 2f, R = s / 2f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 10; i++) {
            float k = (i / 10f);
            p.setColor(Color.argb(13, 20, 6, 0));
            octagon(path, cx, cy + m * 0.3f, R + m * (1f - k) * 0.9f);
            c.drawPath(path, p);
        }
        p.setColor(Color.rgb(250, 244, 236));
        octagon(path, cx, cy, R);
        c.drawPath(path, p);
        Gfx.shader(p, new RadialGradient(cx - R * 0.2f, cy - R * 0.3f, R * 1.25f,
                Color.rgb(255, 104, 96), Color.rgb(168, 18, 30), Shader.TileMode.CLAMP));
        octagon(path, cx, cy, R * 0.86f);
        c.drawPath(path, p);
        Gfx.shader(p, new LinearGradient(0, cy - R, 0, cy, Color.argb(90, 255, 255, 255),
                Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        octagon(path, cx, cy, R * 0.80f);
        c.drawPath(path, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(R * 0.05f);
        p.setColor(Color.argb(150, 110, 8, 18));
        octagon(path, cx, cy, R * 0.86f);
        c.drawPath(path, p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Theme.font);
        t.setStyle(Paint.Style.FILL);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(R * 0.62f);
        Gfx.outlinedText(c, t, "END", cx, cy + R * 0.12f, Color.WHITE, Color.argb(190, 100, 6, 14), R * 0.07f);
        t.setTextSize(R * 0.30f);
        Gfx.outlinedText(c, t, "TURN", cx, cy + R * 0.50f, Theme.CREAM, Color.argb(190, 100, 6, 14), R * 0.05f);
        return b;
    }

    private static void octagon(Path pth, float cx, float cy, float r) {
        pth.reset();
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + i * 45);
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i == 0) pth.moveTo(x, y);
            else pth.lineTo(x, y);
        }
        pth.close();
    }

    private Bitmap makeArrow(int s) {
        int B = Math.round(s * 2.4f);
        Bitmap b = Bitmap.createBitmap(B, B, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = B / 2f, cy = B / 2f;
        Path body = new Path();
        body.moveTo(cx - s * 0.55f, cy - s * 0.28f);
        body.lineTo(cx + s * 0.05f, cy - s * 0.28f);
        body.lineTo(cx + s * 0.05f, cy - s * 0.55f);
        body.lineTo(cx + s * 0.85f, cy);
        body.lineTo(cx + s * 0.05f, cy + s * 0.55f);
        body.lineTo(cx + s * 0.05f, cy + s * 0.28f);
        body.lineTo(cx - s * 0.55f, cy + s * 0.28f);
        body.close();
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 6; i++) {
            p.setColor(Color.argb(22, 0, 0, 0));
            c.save();
            c.translate(0, s * 0.05f + i * s * 0.012f);
            c.drawPath(body, p);
            c.restore();
        }
        Gfx.shader(p, new LinearGradient(cx, cy - s, cx, cy + s, Color.rgb(84, 84, 90), Color.rgb(10, 10, 12),
                Shader.TileMode.CLAMP));
        c.drawPath(body, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.07f));
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Color.rgb(16, 16, 18));
        c.drawPath(body, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(80, 255, 255, 255));
        tmp.set(cx - s * 0.5f, cy - s * 0.18f, cx + s * 0.0f, cy - s * 0.08f);
        c.drawRoundRect(tmp, s * 0.04f, s * 0.04f, p);
        return b;
    }

    private Bitmap makeWheel() {
        float r = wheelR();
        int pad = Math.round(r * 0.16f);
        int B = Math.round(r * 2 + pad * 2);
        Bitmap b = Bitmap.createBitmap(B, B, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = B / 2f, cy = B / 2f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 12; i++) {
            float k = i / 12f;
            p.setColor(Color.argb(12, 10, 4, 0));
            c.drawCircle(cx + r * 0.03f, cy + r * 0.07f, r * 1.1f + pad * (1f - k) * 0.8f, p);
        }
        Gfx.shader(p, new LinearGradient(cx - r, cy - r, cx + r, cy + r, Color.rgb(104, 104, 112),
                Color.rgb(14, 14, 18), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.10f, p);
        p.setShader(null);
        p.setColor(Color.rgb(10, 10, 12));
        c.drawCircle(cx, cy, r * 1.03f, p);
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        Card.Color[] cols = Card.Color.values();
        for (int i = 0; i < 4; i++) {
            int body = Theme.cardColor(cols[i]);
            Gfx.shader(p, new RadialGradient(cx - r * 0.18f, cy - r * 0.22f, r * 1.15f,
                    Theme.shade(body, 1.2f), Theme.shade(body, 0.76f), Shader.TileMode.CLAMP));
            c.drawArc(tmp, -90 + i * 90, 90, true, p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, r * 0.02f));
        p.setColor(Color.argb(110, 0, 0, 0));
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(-90 + i * 90);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, p);
        }
        p.setStrokeWidth(r * 0.05f);
        p.setColor(Color.argb(60, 255, 255, 255));
        tmp.set(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f);
        c.drawArc(tmp, 200, 140, false, p);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 8; i++) {          // ratchet pegs: one tick per peg as the wheel slows
            double a = Math.toRadians(i * 45);
            float pxx = cx + (float) Math.cos(a) * r * 1.065f, pyy = cy + (float) Math.sin(a) * r * 1.065f;
            p.setColor(Color.rgb(60, 44, 20));
            c.drawCircle(pxx, pyy + r * 0.008f, r * 0.04f, p);
            Gfx.shader(p, new RadialGradient(pxx - r * 0.01f, pyy - r * 0.012f, r * 0.045f,
                    Color.rgb(255, 236, 150), Color.rgb(214, 150, 30), Shader.TileMode.CLAMP));
            c.drawCircle(pxx, pyy, r * 0.036f, p);
            p.setShader(null);
        }
        float hub = r * 0.20f;
        p.setColor(Color.argb(90, 0, 0, 0));
        c.drawCircle(cx + 3, cy + 5, hub, p);
        Gfx.shader(p, new RadialGradient(cx - hub * 0.25f, cy - hub * 0.3f, hub * 1.1f,
                Color.rgb(255, 248, 230), Color.rgb(210, 190, 150), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, hub, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, hub * 0.12f));
        p.setColor(Color.rgb(40, 32, 24));
        c.drawCircle(cx, cy, hub, p);
        return b;
    }

    // =====================================================================
    // Game flow (the "director")
    // =====================================================================
    private void startGame(final int opp) {
        if (transition) return;
        sfx.play("sfx_click");
        fadeTo(new Runnable() {
            @Override public void run() {
                clearGameTasks();
                engine.start(opp);
                prepareBubbles(opp);
                resetVisuals();
                screen = Screen.GAME;
                trimScenes(tableSceneName(opp));
                dealIntro();
            }
        });
    }

    private void resetVisuals() {
        slots.clear();
        hidden.clear();
        flies.clear();
        pops.clear();
        rings.clear();
        pn = 0;
        shownTop = null;
        prevTop = null;
        shownLive = null;
        shownCurrent = -1;
        arrowFrom = arrowTo = 1f;
        arrowFlipAt = -100000;
        discardBumpAt = -100000;
        flashAt = -100000;
        chipAt = -100000;
        flashMsg = null;
        statusShown = "";
        statusPrev = "";
        selected = 0;
        dragDy = 0;
        dragUp = false;
        holding = false;
        callArmed = false;
        colorPickFor = -1;
        wheelPicked = -1;
        busy = true;
        dealing = true;
        dimAmt = 0f;
        for (int i = 0; i < 4; i++) {
            shownCount[i] = 0;
            lastShown[i] = 0;
            lowFlashUntil[i] = 0;
            countPopAt[i] = -100000;
        }
        for (Card c : engine.hands.get(0)) hidden.put(c, Boolean.TRUE);
    }

    private void dealIntro() {
        final int seats = engine.seats;
        final long step = 38, t0 = 380;
        int k = 0;
        for (int r = 0; r < 7; r++) {
            for (int s = 0; s < seats; s++) {
                final int seat = s, round = r;
                after(t0 + k * step, new Runnable() {
                    @Override public void run() { dealOne(seat, round); }
                });
                k++;
            }
        }
        final long flipAt = t0 + k * step + 140;
        after(flipAt, new Runnable() {
            @Override public void run() { flipFirstCard(); }
        });
        after(flipAt + 330 + 420, new Runnable() {
            @Override public void run() {
                dealing = false;
                advanceTurn();
            }
        });
    }

    private void dealOne(final int seat, int round) {
        sfx.play("sfx_deal", 0.55f, 0.92f + rnd.nextFloat() * 0.16f);
        float dx = deckRect.centerX(), dy = deckRect.centerY();
        if (seat == 0) {
            final Card card = engine.hands.get(0).get(round);
            handTarget(round, 7, 0, tgt);
            Fly f = newFly(card, false, true, dx, dy, deckRect.width(), rnd.nextFloat() * 10 - 5,
                    tgt[0], tgt[1], tgt[2] * cardW, tgt[3], H * 0.035f, 0, 340);
            f.onLand = new Runnable() {
                @Override public void run() { hidden.remove(card); }
            };
            flies.add(f);
        } else {
            float[] sp = seatHand(seat);
            Fly f = newFly(null, true, false, dx, dy, deckRect.width(), 0,
                    sp[0], sp[1], W * 0.10f, rnd.nextFloat() * 30 - 15, H * 0.02f, 0, 320);
            f.fadeOut = true;
            f.onLand = new Runnable() {
                @Override public void run() { addShown(seat, 1); }
            };
            flies.add(f);
        }
    }

    private void flipFirstCard() {
        final Card top = engine.top();
        Fly f = newFly(top, false, true, deckRect.centerX(), deckRect.centerY(), deckRect.width(), 0,
                discardRect.centerX(), discardRect.centerY(), discardRect.width(), restRot(top),
                H * 0.03f, 0, 340);
        f.onLand = new Runnable() {
            @Override public void run() {
                shownTop = top;
                shownLive = engine.liveColor;
                discardBumpAt = gt;
                addRing(discardRect.centerX(), discardRect.centerY(), discardRect.width() * 0.9f, Theme.CREAM);
                sfx.play("sfx_play", 0.55f, 1f);
            }
        };
        sfx.play("sfx_draw", 0.7f, 1f);
        flies.add(f);
    }

    private void advanceTurn() {
        if (screen != Screen.GAME) return;
        if (engine.gameOver) {
            finishGame();
            return;
        }
        shownCurrent = engine.current;
        if (engine.current == 0) beginHumanTurn();
        else opponentThink(engine.current);
    }

    private void beginHumanTurn() {
        busy = false;
        humanTurnAt = gt;
        sfx.play("sfx_turn", 0.9f, 1f);
    }

    private void opponentThink(final int seat) {
        busy = true;
        long think = 800 + rnd.nextInt(400);
        after(think, new Runnable() {
            @Override public void run() { opponentAct(seat); }
        });
    }

    private void opponentAct(int seat) {
        if (screen != Screen.GAME || engine.gameOver || engine.current != seat) return;
        List<Card> hand = engine.hands.get(seat);
        int pick = engine.computerPick(seat);
        if (pick < 0) {
            oppDraw(seat);
            return;
        }
        Card c = hand.get(pick);
        Card.Color col = c.isWild() ? engine.chooseColor(seat) : c.color;
        float[] sp = seatHand(seat);
        String r = engine.play(seat, pick, col);
        List<Card> victimCards = null;
        if (!engine.gameOver && c.isDraw()) victimCards = newestCards(engine.lastDrawTarget, c.kind == Card.Kind.PULL4 ? 4 : 2);
        launchPlay(seat, c, r, sp[0], sp[1], W * 0.10f, rnd.nextFloat() * 30 - 15, 440, victimCards, null, true);
    }

    private void oppDraw(final int seat) {
        engine.drawOne(seat);
        sfx.play("sfx_draw", 0.8f, 1f);
        float[] sp = seatHand(seat);
        Fly f = newFly(null, true, false, deckRect.centerX(), deckRect.centerY(), deckRect.width(), 0,
                sp[0], sp[1], W * 0.10f, rnd.nextFloat() * 30 - 15, H * 0.025f, 0, 380);
        f.fadeOut = true;
        f.onLand = new Runnable() {
            @Override public void run() {
                addShown(seat, 1);
                if (rnd.nextInt(100) < 45) voice(seat, false);
            }
        };
        flies.add(f);
        after(380 + 380, new Runnable() {
            @Override public void run() {
                engine.passTo(engine.nextSeat(seat));
                advanceTurn();
            }
        });
    }

    /** Human plays hand[idx]. Wilds pass the colour they chose. */
    private void humanPlay(int idx, Card.Color chosen, boolean called) {
        List<Card> hand = engine.hands.get(0);
        if (idx < 0 || idx >= hand.size()) return;
        Card card = hand.get(idx);
        boolean mustCall = hand.size() == 2;
        boolean penalty = mustCall && !called;
        float sx = hx[idx], sy = hy[idx], sw = hw[idx], sr = hr[idx];
        String r = engine.play(0, idx, chosen);
        if ("illegal".equals(r)) {
            sfx.play("sfx_error");
            return;
        }
        slots.remove(card);
        hidden.remove(card);
        if (selected >= hand.size()) selected = Math.max(0, hand.size() - 1);
        dragDy = 0;
        dragUp = false;
        busy = true;
        callArmed = false;
        List<Card> victimCards = null;
        if (!engine.gameOver && card.isDraw()) victimCards = newestCards(engine.lastDrawTarget, card.kind == Card.Kind.PULL4 ? 4 : 2);
        List<Card> penaltyCards = null;
        if (penalty && !engine.gameOver) {
            engine.give(0, 2);
            penaltyCards = newestCards(0, 2);
        }
        launchPlay(0, card, r, sx, sy, sw, sr, 330, victimCards, penaltyCards, false);
    }

    /** Marks the newest n cards of a human hand as "in flight"; opponents just report null. */
    private List<Card> newestCards(int seat, int n) {
        if (seat != 0) return null;
        List<Card> hand = engine.hands.get(0);
        List<Card> out = new ArrayList<>();
        for (int i = Math.max(0, hand.size() - n); i < hand.size(); i++) {
            out.add(hand.get(i));
            hidden.put(hand.get(i), Boolean.TRUE);
        }
        return out;
    }

    private void launchPlay(final int seat, final Card card, final String result,
                            float sx, float sy, float sw, float sr, long dur,
                            final List<Card> victimCards, final List<Card> penaltyCards, boolean opp) {
        if (seat != 0) {
            shownCount[seat] = Math.max(0, shownCount[seat] - 1);
            countPopAt[seat] = gt;
        }
        sfx.play("sfx_whoosh", seat == 0 ? 0.35f : 0.5f, 0.95f + rnd.nextFloat() * 0.1f);
        Fly f = newFly(card, false, opp, sx, sy, sw, sr,
                discardRect.centerX(), discardRect.centerY(), discardRect.width(), restRot(card),
                H * (opp ? 0.05f : 0.035f), 0, dur);
        f.onLand = new Runnable() {
            @Override public void run() { landPlay(seat, card, result, victimCards, penaltyCards); }
        };
        flies.add(f);
    }

    private void landPlay(final int seat, Card card, String result,
                          List<Card> victimCards, List<Card> penaltyCards) {
        prevTop = shownTop;
        shownTop = card;
        shownLive = engine.liveColor;
        discardBumpAt = gt;
        int ringColor = card.isWild() ? Theme.GOLD : Theme.cardColor(card.color);
        addRing(discardRect.centerX(), discardRect.centerY(), discardRect.width() * 1.15f, ringColor);
        sfx.play("sfx_play", 1f, 0.96f + rnd.nextFloat() * 0.08f);
        if (seat == 0) haptic(1);

        if ("win".equals(result) || engine.gameOver) {
            after(560, new Runnable() {
                @Override public void run() { finishGame(); }
            });
            return;
        }

        long end = 150;
        boolean plainOpp = card.kind == Card.Kind.NUMBER;
        switch (card.kind) {
            case JUMP: {
                int skipped = engine.nextSeat(seat);
                sfx.play("sfx_jump");
                float[] pp = popPos(skipped);
                popup("Skip!", pp[0], pp[1], Theme.GOLD);
                if (seat > 0) voiceLater(60, seat, true);
                if (skipped > 0) voiceLater(300, skipped, false);
                end = 720;
                break;
            }
            case SPIN: {
                sfx.play("sfx_spin");
                arrowFrom = arrowTo;
                arrowTo = engine.dir >= 0 ? 1f : -1f;
                arrowFlipAt = gt;
                popup(engine.seats == 2 ? "Play again!" : "Reverse!", W * 0.5f, H * 0.585f, Theme.GOLD);
                if (seat > 0) voiceLater(60, seat, true);
                end = 700;
                break;
            }
            case PULL2:
            case PULL4: {
                int n = card.kind == Card.Kind.PULL2 ? 2 : 4;
                int v = engine.lastDrawTarget;
                sfx.play(card.kind == Card.Kind.PULL2 ? "sfx_pull2" : "sfx_pull4", 1f, 1f);
                float[] pp = popPos(v);
                popup("+" + n, pp[0], pp[1], Theme.GOLD);
                if (card.kind == Card.Kind.PULL4) announceColor();
                if (seat > 0) voiceLater(60, seat, true);
                end = launchPullFlights(v, n, 230, victimCards, true);
                break;
            }
            case TOUCH: {
                sfx.play("sfx_touch");
                announceColor();
                sparkle(discardRect.centerX(), discardRect.centerY(), 26, true);
                if (seat > 0) voiceLater(60, seat, true);
                end = 620;
                break;
            }
            default:
                if (seat > 0 && rnd.nextInt(100) < 22) voiceLater(70, seat, true);
                break;
        }

        // Opponent drops to one card: the call is automatic.
        if (seat > 0 && engine.hands.get(seat).size() == 1) {
            triggerTouchOne(seat);
            end = Math.max(end, plainOpp ? 760 : end);
        }

        if (penaltyCards != null) {
            popup("Forgot Touch One!", W * 0.5f, H * 0.665f, Theme.ALERT);
            long pe = launchPullFlights(0, 2, Math.max(420, end - 100), penaltyCards, false);
            sfx.play("sfx_error", 0.8f, 1f);
            end = Math.max(end, pe);
        }
        after(end + 140, new Runnable() {
            @Override public void run() { advanceTurn(); }
        });
    }

    /** Sends n cards from the deck to a victim, staggered. @return ms until the last one lands. */
    private long launchPullFlights(final int victim, int n, long startDelay, List<Card> cards, boolean stinger) {
        long step = n == 2 ? 170 : 125;
        long dur = 340;
        for (int k = 0; k < n; k++) {
            final int kk = k;
            final Card card = (cards != null && k < cards.size()) ? cards.get(k) : null;
            after(startDelay + k * step, new Runnable() {
                @Override public void run() {
                    sfx.play("sfx_deal", 0.6f, 0.9f + kk * 0.07f);
                    Fly f;
                    if (victim == 0 && card != null) {
                        int idx = engine.hands.get(0).indexOf(card);
                        handTarget(Math.max(0, idx), engine.hands.get(0).size(), selected, tgt);
                        f = newFly(card, false, true, deckRect.centerX(), deckRect.centerY(), deckRect.width(),
                                0, tgt[0], tgt[1], tgt[2] * cardW, tgt[3], H * 0.03f, 0, 340);
                        final Card cd = card;
                        f.onLand = new Runnable() {
                            @Override public void run() {
                                hidden.remove(cd);
                                if (kk == 0) haptic(3);
                            }
                        };
                    } else {
                        float[] sp = seatHand(victim);
                        f = newFly(null, true, false, deckRect.centerX(), deckRect.centerY(), deckRect.width(), 0,
                                sp[0], sp[1], W * 0.10f, rnd.nextFloat() * 30 - 15, H * 0.02f, 0, 340);
                        f.fadeOut = true;
                        f.onLand = new Runnable() {
                            @Override public void run() { addShown(victim, 1); }
                        };
                    }
                    flies.add(f);
                }
            });
        }
        if (stinger) voiceLater(startDelay + 330, victim, false);
        return startDelay + (n - 1) * step + dur;
    }

    private void announceColor() {
        chipText = "Color is " + Theme.colorName(engine.liveColor);
        chipColor = Theme.cardColor(engine.liveColor);
        chipAt = gt;
    }

    private void triggerTouchOne(int seat) {
        flashAt = gt;
        sfx.play("sfx_touchone");
        haptic(2);
        sparkle(W * 0.5f, H * 0.44f, 22, false);
        if (seat > 0) voiceLater(320, seat, true);
    }

    private void addShown(int seat, int delta) {
        shownCount[seat] = Math.max(0, shownCount[seat] + delta);
        countPopAt[seat] = gt;
        int n = shownCount[seat];
        if (lastShown[seat] > 3 && n <= 3 && n > 0) lowFlashUntil[seat] = gt + 900;
        lastShown[seat] = n;
    }

    private void finishGame() {
        final boolean won = engine.playerWon;
        fadeTo(new Runnable() {
            @Override public void run() {
                clearGameTasks();
                flies.clear();
                screen = won ? Screen.WIN : Screen.LOSS;
                endAt = gt;
                sfx.play(won ? "sfx_win" : "sfx_loss");
                trimScenes(won ? "bg_win" : "bg_loss");
            }
        });
    }

    private void fadeTo(final Runnable mid) {
        transition = true;
        fadePhase = 1;
        fadePhaseAt = gt;
        afterUi(FADE_OUT_MS, new Runnable() {
            @Override public void run() {
                mid.run();
                fadePhase = 2;
                fadePhaseAt = gt;
            }
        });
        afterUi(FADE_OUT_MS + FADE_IN_MS, new Runnable() {
            @Override public void run() {
                fadePhase = 0;
                transition = false;
            }
        });
    }

    // ---- human actions -----------------------------------------------------
    private boolean humanCanAct() {
        return screen == Screen.GAME && !busy && !transition && !dealing
                && engine.current == 0 && shownCurrent == 0 && !engine.gameOver;
    }

    private void humanDraw() {
        if (!engine.canDraw()) {
            sfx.play("sfx_error", 0.7f, 1f);
            flash("One card from the deck each turn");
            return;
        }
        final Card c = engine.drawOne(0);
        List<Card> hand = engine.hands.get(0);
        hidden.put(c, Boolean.TRUE);
        selected = hand.size() - 1;
        busy = true;
        handTarget(selected, hand.size(), selected, tgt);
        sfx.play("sfx_draw");
        Fly f = newFly(c, false, true, deckRect.centerX(), deckRect.centerY(), deckRect.width(), 0,
                tgt[0], tgt[1], tgt[2] * cardW, 0, H * 0.04f, 0, 400);
        f.onLand = new Runnable() {
            @Override public void run() {
                hidden.remove(c);
                busy = false;
                sfx.play("sfx_deal", 0.6f, 1f);
                haptic(0);
            }
        };
        flies.add(f);
    }

    private void humanEndTurn() {
        if (!engine.canEndTurn()) {
            sfx.play("sfx_error", 0.7f, 1f);
            flash(engine.pendingFollowup ? "Play another card or draw one first" : "Play a card or draw one first");
            return;
        }
        sfx.play("sfx_click");
        engine.passTo(engine.nextSeat(0));
        busy = true;
        after(160, new Runnable() {
            @Override public void run() { advanceTurn(); }
        });
    }

    private void onDiscardTap(boolean called) {
        List<Card> hand = engine.hands.get(0);
        if (selected < 0 || selected >= hand.size()) return;
        Card card = hand.get(selected);
        if (!engine.legal(card, null)) {
            rejectCard(card);
            return;
        }
        if (card.isWild()) {
            openWheel(called);
            return;
        }
        humanPlay(selected, card.color, called);
    }

    private void rejectCard(Card card) {
        shakeCard = card;
        shakeAt = gt;
        sfx.play("sfx_error");
        haptic(3);
        flash("Match the color, number or symbol");
    }

    private void openWheel(boolean called) {
        colorPickFor = selected;
        callArmed = called;
        screen = Screen.COLOR;
        wheelAt = gt;
        wheelTurns = 3f + rnd.nextFloat() * 1.2f;
        wheelPicked = -1;
        lastTickStep = Integer.MIN_VALUE;
        lastTickAt = 0;
        sfx.play("sfx_wheel");
    }

    private void pickColor(final int idx) {
        wheelPicked = idx;
        wheelPickAt = gt;
        sfx.play("sfx_confirm");
        haptic(2);
        final Card.Color chosen = Card.Color.values()[idx];
        after(340, new Runnable() {
            @Override public void run() {
                screen = Screen.GAME;
                int sel = colorPickFor;
                colorPickFor = -1;
                wheelPicked = -1;
                humanPlay(sel, chosen, callArmed);
            }
        });
    }

    private void cancelWheel() {
        screen = Screen.GAME;
        colorPickFor = -1;
        callArmed = false;
        sfx.play("sfx_click", 0.7f, 0.9f);
    }

    private void flash(String msg) {
        flashMsg = msg;
        flashMsgUntil = gt + 1700;
    }

    private void voice(int seat, boolean good) {
        String who = charVoice(seat);
        if (who != null) sfx.play("sfx_" + who + (good ? "_yes" : "_ohno"), 0.95f, 1f);
    }

    private void voiceLater(long delay, final int seat, final boolean good) {
        after(delay, new Runnable() {
            @Override public void run() { voice(seat, good); }
        });
    }

    private String charVoice(int seat) {
        if (seat <= 0) return null;
        int opp = engine.seats - 1;
        if (seat == 1) return "boy";
        if (opp == 2 && seat == 2) return "girl";
        if (opp == 3 && seat == 2) return "dog";
        if (opp == 3 && seat == 3) return "girl";
        return null;
    }

    private void haptic(int kind) {
        int c;
        switch (kind) {
            case 0: c = HapticFeedbackConstants.CLOCK_TICK; break;
            case 1: c = HapticFeedbackConstants.KEYBOARD_TAP; break;
            case 2: c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.LONG_PRESS; break;
            default: c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT
                    : HapticFeedbackConstants.LONG_PRESS; break;
        }
        performHapticFeedback(c);
    }

    // =====================================================================
    // Geometry helpers
    // =====================================================================
    private float[][] glowTable() {
        int opp = engine.seats - 1;
        return opp == 1 ? GLOW1 : opp == 2 ? GLOW2 : GLOW3;
    }

    private float[][] hatTable() {
        int opp = engine.seats - 1;
        return opp == 1 ? HATS1 : opp == 2 ? HATS2 : HATS3;
    }

    /** Where an opponent's cards come from / go to (just under their face). */
    private float[] seatHand(int seat) {
        float[][] g = glowTable();
        int i = Math.max(0, Math.min(g.length - 1, seat - 1));
        return new float[]{W * g[i][0], H * (g[i][1] + 0.06f)};
    }

    private float[] popPos(int seat) {
        if (seat <= 0) return new float[]{W * 0.5f, H * 0.69f};
        float[][] g = glowTable();
        int i = Math.max(0, Math.min(g.length - 1, seat - 1));
        return new float[]{W * g[i][0], H * (g[i][1] - 0.02f)};
    }

    private float restRot(Card c) {
        return ((System.identityHashCode(c) & 15) - 7.5f) * 0.9f;
    }

    /** Target centre / scale / rotation for hand slot i of n with sel selected. out = x, y, scale, rot. */
    private void handTarget(int i, int n, int sel, float[] out) {
        n = Math.max(1, n);
        float overlap = Math.min(cardW * 0.62f, (W * 0.86f) / n);
        float total = overlap * (n - 1) + cardW;
        float x0 = (W - total) / 2f;
        float y0 = H * 0.792f;
        float mid = (n - 1) / 2f, di = i - mid;
        if (i == sel) {
            out[0] = x0 + i * overlap + cardW / 2f - 4 * u;
            out[1] = y0 - 12 * u + cardH / 2f;
            out[2] = 1f;
            out[3] = 0f;
        } else {
            float sc = 0.72f;
            out[0] = x0 + i * overlap + cardW * sc / 2f;
            out[1] = y0 + 28 * u + cardH * sc / 2f + di * di * cardH * 0.0035f;
            out[2] = sc;
            out[3] = di * Math.min(2.6f, 12f / n);
        }
    }

    private void ensureHandArrays(int n) {
        if (hx.length >= n) return;
        int m = Math.max(n, hx.length * 2);
        hx = new float[m];
        hy = new float[m];
        hw = new float[m];
        hr = new float[m];
    }

    private void updateHand() {
        List<Card> hand = engine.hands.get(0);
        int n = hand.size();
        if (selected >= n) selected = n - 1;
        if (selected < 0) selected = 0;
        ensureHandArrays(n);
        float a = smooth(85f);
        for (int i = 0; i < n; i++) {
            Card cd = hand.get(i);
            handTarget(i, n, selected, tgt);
            Slot sl = slots.get(cd);
            if (sl == null) {
                sl = new Slot();
                sl.x = tgt[0]; sl.y = tgt[1]; sl.s = tgt[2]; sl.rot = tgt[3];
                slots.put(cd, sl);
            } else {
                sl.x += (tgt[0] - sl.x) * a;
                sl.y += (tgt[1] - sl.y) * a;
                sl.s += (tgt[2] - sl.s) * a;
                sl.rot += (tgt[3] - sl.rot) * a;
            }
            float shake = 0f;
            if (cd == shakeCard) {
                float t = (gt - shakeAt) / 340f;
                if (t < 1f) shake = (float) Math.sin(t * 38f) * 14f * u * (1f - t);
            }
            hx[i] = sl.x + shake;
            hy[i] = sl.y + (i == selected ? dragDy : 0f);
            hw[i] = sl.s * cardW;
            hr[i] = sl.rot + (i == selected ? dragDy * 0.02f : 0f);
        }
        if (!dragUp) dragDy += (0f - dragDy) * smooth(70f);
        float want = humanCanAct() ? 1f : 0f;
        dimAmt += (want - dimAmt) * smooth(110f);
    }

    /**
     * Which card a touch means. Chosen by horizontal position (nearest fanned slot), not by
     * the selected card's big rectangle: the selected card covers most of its neighbours, and
     * a tap on the sliver beside it should still pick that neighbour.
     */
    private int hitHand(float x, float y) {
        int n = engine.hands.get(0).size();
        if (n == 0) return -1;
        float overlap = Math.min(cardW * 0.62f, (W * 0.86f) / n);
        float x0 = (W - (overlap * (n - 1) + cardW)) / 2f;
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float d = Math.abs(x0 + i * overlap + cardW * 0.36f - x);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private Fly newFly(Card card, boolean back, boolean flip, float sx, float sy, float sw, float sr,
                       float ex, float ey, float ew, float er, float arc, long delay, long dur) {
        Fly f = new Fly();
        f.card = card;
        f.back = back;
        f.flip = flip;
        f.sx = sx; f.sy = sy; f.sw = sw; f.sr = sr;
        f.ex = ex; f.ey = ey; f.ew = ew; f.er = er;
        f.arc = arc;
        f.start = gt + delay;
        f.dur = dur;
        return f;
    }

    // =====================================================================
    // Drawing
    // =====================================================================
    @Override
    protected void onDraw(Canvas c) {
        if (W == 0) return;
        advanceClock();
        runTasks();
        checkHoldArmed();      // advances even while the finger is perfectly still
        switch (screen) {
            case SPLASH: drawSplash(c); break;
            case WIN: drawEnd(c, true); break;
            case LOSS: drawEnd(c, false); break;
            default:
                updateHand();
                drawGame(c);
                if (screen == Screen.COLOR) drawWheel(c);
                break;
        }
        drawFade(c);
        if (resumed) {
            if (screen == Screen.WIN && winMovie != null) postInvalidateDelayed(33L);
            else postInvalidateOnAnimation();
        }
    }

    private void drawScene(Canvas c, String name) {
        Bitmap b = scene(name);
        if (b != null) {
            c.drawBitmap(b, 0, 0, bmp);
        } else {
            Gfx.shader(fill, new LinearGradient(0, 0, 0, H, Color.rgb(74, 40, 70), Color.rgb(120, 70, 36),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, fill);
            fill.setShader(null);
        }
    }

    private void drawFade(Canvas c) {
        float a = 0f;
        if (fadePhase == 1) a = clamp01((gt - fadePhaseAt) / (float) FADE_OUT_MS);
        else if (fadePhase == 2) a = 1f - clamp01((gt - fadePhaseAt) / (float) FADE_IN_MS);
        if (a <= 0.003f) return;
        fill.setColor(Color.argb((int) (255 * a), 22, 10, 4));
        c.drawRect(0, 0, W, H, fill);
    }

    private void label(Canvas c, String s, float cx, float baseline, float size, int fillC, int outline,
                       float outlineW, float alpha) {
        if (alpha <= 0.01f) return;
        text.setTextSize(size);
        text.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Theme.withAlpha(Color.argb(120, 20, 8, 0), alpha));
        c.drawText(s, cx, baseline + size * 0.07f, text);
        Gfx.outlinedText(c, text, s, cx, baseline, Theme.withAlpha(fillC, alpha),
                Theme.withAlpha(outline, alpha), outlineW);
    }

    // ---- splash ------------------------------------------------------------
    private void drawSplash(Canvas c) {
        drawScene(c, "bg_splash");
        float top = tileRect[0].top;
        float pt = clamp01((gt - splashAt - 200) / 400f);
        label(c, "How many opponents?", W / 2f, top - 0.026f * H, W * 0.056f, Theme.CREAM, Theme.WOOD_INK,
                W * 0.014f, pt);
        for (int i = 0; i < 3; i++) {
            Bitmap b = tileBmp[i];
            if (b == null) continue;
            float t = clamp01((gt - splashAt - 300 - i * 110) / 460f);
            if (t <= 0f) continue;
            float sc = easeOutBack(t);
            float bob = (float) Math.sin(gt / 760.0 + i * 1.3) * 3f * u * t;
            float press = (pressId == P_TILE && pressTile == i) ? 0.92f : 1f;
            float cx = tileRect[i].centerX(), cy = tileRect[i].centerY() + bob;
            drawBitmapScaled(c, b, cx, cy, sc * press);
        }
    }

    private void drawBitmapScaled(Canvas c, Bitmap b, float cx, float cy, float s) {
        float w = b.getWidth() * s, h = b.getHeight() * s;
        dst.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        c.drawBitmap(b, null, dst, bmp);
    }

    // ---- end screens ---------------------------------------------------------
    private void drawEnd(Canvas c, boolean win) {
        Movie mv = winMovie;
        if (win && mv != null) {
            int dur = Math.max(1, mv.duration());
            mv.setTime((int) ((gt - endAt) % dur));
            c.save();
            c.scale(W / (float) mv.width(), H / (float) mv.height());
            mv.draw(c, 0, 0);
            c.restore();
        } else {
            drawScene(c, win ? "bg_win" : "bg_loss");
            if (win) drawDanceSheet(c);
        }
        float t = clamp01((gt - endAt - 520) / 420f);
        if (t > 0f && againBmp != null) {
            float pulse = 1f + 0.022f * (float) Math.sin(gt / 330.0);
            float press = pressId == P_AGAIN ? 0.94f : 1f;
            drawBitmapScaled(c, againBmp, againRect.centerX(), againRect.centerY(),
                    easeOutBack(t) * pulse * press);
        }
    }

    private void drawDanceSheet(Canvas c) {
        if (scoobertSheet == null) scoobertSheet = decode("scoobert_dance_sheet");
        if (scoobertSheet == null) return;
        long elapsed = (gt - endAt) % 2000L;
        int frame = (int) (elapsed / 133L) % 15;
        int col = frame % 5, row = frame / 5;
        int sw = scoobertSheet.getWidth(), sh = scoobertSheet.getHeight();
        Rect src = new Rect(col * sw / 5, row * sh / 3, (col + 1) * sw / 5, (row + 1) * sh / 3);
        dst.set(W * 0.18f, H * 0.53f, W * 0.82f, H * 0.96f);
        c.drawBitmap(scoobertSheet, src, dst, bmp);
    }

    // ---- game --------------------------------------------------------------
    private void drawGame(Canvas c) {
        int opp = engine.seats - 1;
        drawScene(c, tableSceneName(opp));
        drawTurnGlow(c, opp);
        drawOpponentBubbles(c, opp);
        drawPiles(c);
        drawDirArrow(c);
        drawHand(c);
        drawPlayerCloud(c);
        drawFlies(c);
        drawEndSign(c);
        drawStatus(c);
        drawRings(c);
        drawParticles(c);
        drawPops(c);
        drawTouchOneBanner(c);
        drawHoldRing(c);
    }

    private void drawTurnGlow(Canvas c, int opp) {
        int seat = shownCurrent;
        if (seat <= 0 || engine.gameOver || glowPaint == null) return;
        float[][] g = glowTable();
        int idx = seat - 1;
        if (idx >= g.length) return;
        float cx = W * g[idx][0], cy = H * g[idx][1];
        float base = W * (opp == 1 ? 0.38f : 0.28f);
        float b = breath(1300f);
        float r = base * (0.94f + 0.08f * b);
        c.save();
        c.translate(cx, cy);
        c.scale(r / 100f, r / 100f);
        c.drawCircle(0, 0, 100f, glowPaint);
        c.restore();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(5f, W * 0.010f));
        line.setColor(Color.argb(70 + (int) (80 * b), 255, 224, 80));
        c.drawCircle(cx, cy, r * 0.62f, line);
    }

    private int countColor(int seat, int n) {
        if (n > 3 || n <= 0) return Theme.WOOD_INK;
        if (gt < lowFlashUntil[seat]) {
            return ((gt / 120) % 2 == 0) ? Theme.ALERT : Theme.WOOD_INK;
        }
        return Theme.ALERT;
    }

    private float popScale(int seat) {
        float t = (gt - countPopAt[seat]) / 320f;
        if (t < 0f || t >= 1f) return 1f;
        return 1f + 0.20f * (float) Math.sin(t * Math.PI);
    }

    private void drawOpponentBubbles(Canvas c, int opp) {
        float[][] hats = hatTable();
        int[] style = opp == 1 ? STYLE1 : opp == 2 ? STYLE2 : STYLE3;
        for (int i = 0; i < opp; i++) {
            int seat = i + 1;
            Bitmap cloud = bubble[style[i]];
            float cx = W * hats[i][0], cy = H * hats[i][1];
            float breathe = shownCurrent == seat ? 1f + 0.035f * breath(1300f) : 1f;
            float sc = breathe * popScale(seat);
            c.save();
            c.translate(cx, cy);
            c.scale(sc, sc);
            float bh = 0f;
            if (cloud != null) {
                bh = cloud.getHeight();
                c.drawBitmap(cloud, -cloud.getWidth() / 2f, -cloud.getHeight() / 2f, bmp);
            }
            if (bh == 0f) bh = W * 0.2f;
            String n = String.valueOf(shownCount[seat]);
            float size = bh * (style[i] == 2 ? 0.44f : 0.52f);
            text.setTextSize(size);
            text.setStyle(Paint.Style.FILL);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(countColor(seat, shownCount[seat]));
            float cloudCy = style[i] == 2 ? -bh * 0.16f : -bh * 0.08f;
            c.drawText(n, 0, Gfx.centeredBaseline(text, n, cloudCy), text);
            c.restore();
        }
    }

    private void drawPlayerCloud(Canvas c) {
        int n = engine.hands.get(0).size() - hidden.size();
        if (n < 0) n = 0;
        if (n > 0 && shownCount[0] != n) {
            if (lastShown[0] > 3 && n <= 3) lowFlashUntil[0] = gt + 900;
            if (shownCount[0] != n) countPopAt[0] = gt;
            shownCount[0] = n;
            lastShown[0] = n;
        } else if (n == 0) {
            shownCount[0] = 0;
        }
        float cx = W * 0.4940f, cy = H * 0.728f;
        float breathe = (shownCurrent == 0 && humanCanAct()) ? 1f + 0.03f * breath(1300f) : 1f;
        float sc = breathe * popScale(0);
        float bw = W * 0.26f, bh = bw * 0.68f;
        c.save();
        c.translate(cx, cy);
        c.scale(sc, sc);
        if (bubblePlayer != null) {
            bw = bubblePlayer.getWidth();
            bh = bubblePlayer.getHeight();
            c.drawBitmap(bubblePlayer, -bw / 2f, -bh / 2f, bmp);
        }
        String s = String.valueOf(shownCount[0]);
        text.setTextSize(bh * 0.52f);
        text.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(countColor(0, shownCount[0]));
        c.drawText(s, bw * 0.03f, Gfx.centeredBaseline(text, s, -bh * 0.06f), text);
        c.restore();
    }

    private void drawCardAt(Canvas c, Bitmap b, float cx, float cy, float w, float rot, float scaleX,
                            float lift, int overlayAlpha) {
        float k = w / cardW;
        float h = w * 1.4f;
        c.save();
        c.translate(cx, cy);
        if (rot != 0f) c.rotate(rot);
        Bitmap sh = art.shadow();
        float pad = art.shadowPad * k;
        float off = h * 0.028f + lift;
        dst.set(-w / 2f * Math.abs(scaleX) - pad, -h / 2f - pad + off, w / 2f * Math.abs(scaleX) + pad,
                h / 2f + pad + off);
        bmp.setAlpha(190);
        c.drawBitmap(sh, null, dst, bmp);
        bmp.setAlpha(255);
        dst.set(-w / 2f * scaleX, -h / 2f, w / 2f * scaleX, h / 2f);
        c.drawBitmap(b, null, dst, bmp);
        if (overlayAlpha > 0) {
            fill.setColor(Color.argb(overlayAlpha, 14, 6, 2));
            c.drawRoundRect(dst, w * 0.12f, w * 0.12f, fill);
        }
        c.restore();
    }

    private void drawPiles(Canvas c) {
        // Deck: a short stack, nudged when pressed or when drawing is the only move.
        boolean canDraw = humanCanAct() && engine.canDraw();
        boolean needDraw = canDraw && !engine.hasLegal(0);
        float press = pressId == P_DECK ? 0.95f : 1f;
        float hint = needDraw ? 1f + 0.035f * breath(700f) : 1f;
        float dw = deckRect.width() * press * hint;
        float dcx = deckRect.centerX(), dcy = deckRect.centerY();
        Bitmap back = art.back();
        if (needDraw) {
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(5f, W * 0.009f));
            line.setColor(Color.argb(90 + (int) (110 * breath(700f)), 255, 224, 80));
            tmp.set(dcx - dw / 2f - 8 * u, dcy - dw * 0.7f - 8 * u, dcx + dw / 2f + 8 * u, dcy + dw * 0.7f + 8 * u);
            c.drawRoundRect(tmp, dw * 0.14f, dw * 0.14f, line);
        }
        for (int k = 3; k >= 1; k--) {
            drawCardAt(c, back, dcx + k * 2.2f * u, dcy - k * 2.2f * u, dw, 0f, 1f, 0f, 70);
        }
        drawCardAt(c, back, dcx, dcy, dw, 0f, 1f, 0f, 0);

        // Discard: empty-slot outline, the card underneath, then the top card with a landing bump.
        float cx = discardRect.centerX(), cy = discardRect.centerY();
        float dwid = discardRect.width();
        dashPaint.setStrokeWidth(3f * u);
        dashPaint.setColor(Color.argb(90, 255, 244, 220));
        tmp.set(cx - dwid / 2f, cy - dwid * 0.7f, cx + dwid / 2f, cy + dwid * 0.7f);
        c.drawRoundRect(tmp, dwid * 0.12f, dwid * 0.12f, dashPaint);
        float bt = (gt - discardBumpAt) / 260f;
        float bump = (bt >= 0f && bt < 1f) ? 1f + 0.07f * (float) Math.sin(bt * Math.PI) * (1f - bt * 0.4f) : 1f;
        float hold = holding ? 1f + 0.04f * clamp01((gt - holdStart) / (float) CALL_HOLD_MS) : 1f;
        float pr = (pressId == P_DISCARD && !holding) ? 0.96f : 1f;
        if (prevTop != null && shownTop != null) {
            drawCardAt(c, art.face(prevTop, shownLive), cx - 3 * u, cy + 2 * u, dwid * 0.98f, restRot(prevTop) * -1.4f,
                    1f, 0f, 60);
        }
        if (shownTop != null) {
            drawCardAt(c, art.face(shownTop, shownLive), cx, cy, dwid * bump * hold * pr, restRot(shownTop), 1f, 0f, 0);
        }
        boolean hintCall = humanCanAct() && engine.hands.get(0).size() == 2 && selectedLegal();
        if (hintCall && !holding) {
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(4f, W * 0.007f));
            line.setColor(Color.argb(60 + (int) (110 * breath(900f)), 255, 224, 80));
            tmp.set(cx - dwid / 2f - 8 * u, cy - dwid * 0.7f - 8 * u, cx + dwid / 2f + 8 * u, cy + dwid * 0.7f + 8 * u);
            c.drawRoundRect(tmp, dwid * 0.15f, dwid * 0.15f, line);
        }
        float ls = W * 0.036f;
        String dl = engine.drewThisTurn && shownCurrent == 0 ? "Drawn" : "Draw";
        label(c, dl, deckRect.centerX(), deckRect.bottom + 36 * u + ls * 0.4f, ls, Theme.CREAM, Theme.WOOD_INK,
                W * 0.010f, 1f);
        label(c, "Discard", discardRect.centerX(), discardRect.bottom + 36 * u + ls * 0.4f, ls, Theme.CREAM,
                Theme.WOOD_INK, W * 0.010f, 1f);
    }

    private boolean selectedLegal() {
        List<Card> hand = engine.hands.get(0);
        return selected >= 0 && selected < hand.size() && engine.legal(hand.get(selected), null);
    }

    private void drawDirArrow(Canvas c) {
        if (arrowBmp == null) return;
        float cx = W * 0.50f, cy = H * 0.635f;
        float t = clamp01((gt - arrowFlipAt) / 520f);
        float sx = lerp(arrowFrom, arrowTo, easeOutBack(t));
        float sy = 1f + 0.18f * (float) Math.sin(t * Math.PI);
        c.save();
        c.translate(cx, cy);
        c.scale(sx, sy);
        c.drawBitmap(arrowBmp, -arrowBmp.getWidth() / 2f, -arrowBmp.getHeight() / 2f, bmp);
        c.restore();
    }

    private void drawHand(Canvas c) {
        List<Card> hand = engine.hands.get(0);
        int n = hand.size();
        for (int i = 0; i < n; i++) {
            if (i != selected) drawHandCard(c, hand, i);
        }
        if (selected < n) drawHandCard(c, hand, selected);
    }

    private void drawHandCard(Canvas c, List<Card> hand, int i) {
        Card cd = hand.get(i);
        if (hidden.containsKey(cd)) return;
        int overlay = 0;
        if (dimAmt > 0.01f && !engine.legal(cd, null)) overlay = (int) (112 * dimAmt);
        float lift = i == selected ? 6 * u : 0f;
        drawCardAt(c, art.face(cd, null), hx[i], hy[i], hw[i], hr[i], 1f, lift, overlay);
    }

    private void drawFlies(Canvas c) {
        landed.clear();
        for (int i = 0; i < flies.size(); i++) {
            Fly f = flies.get(i);
            float t = (gt - f.start) / (float) f.dur;
            if (t < 0f) continue;
            if (t >= 1f) {
                landed.add(f);
                continue;
            }
            drawFly(c, f, t);
        }
        for (int i = 0; i < landed.size(); i++) {
            Fly f = landed.get(i);
            flies.remove(f);
            if (f.onLand != null) f.onLand.run();
        }
    }

    private void drawFly(Canvas c, Fly f, float t) {
        float e = easeOutCubic(t);
        float arcT = 4f * e * (1f - e);
        float x = lerp(f.sx, f.ex, e);
        float y = lerp(f.sy, f.ey, e) - f.arc * arcT;
        float w = lerp(f.sw, f.ew, e);
        float rot = lerp(f.sr, f.er, e);
        float lift = f.arc * arcT * 0.6f;
        Bitmap b;
        float scaleX = 1f;
        if (f.back || f.card == null) {
            b = art.back();
        } else if (f.flip) {
            float k = (float) Math.cos(Math.PI * t);
            scaleX = Math.max(0.02f, Math.abs(k));
            b = t < 0.5f ? art.back() : art.face(f.card, null);
        } else {
            b = art.face(f.card, null);
        }
        if (f.fadeOut && t > 0.7f) bmp.setAlpha((int) (255 * (1f - (t - 0.7f) / 0.3f)));
        drawCardAt(c, b, x, y, w, rot, scaleX, lift, 0);
        bmp.setAlpha(255);
    }

    private void drawEndSign(Canvas c) {
        if (endSignBmp == null) return;
        boolean can = humanCanAct() && engine.canEndTurn();
        boolean urge = can && !engine.hasLegal(0);
        float press = pressId == P_END ? 0.92f : 1f;
        float pulse = urge ? 1f + 0.07f * breath(650f) : 1f;
        bmp.setAlpha(can ? 255 : 110);
        drawBitmapScaled(c, endSignBmp, endRect.centerX(), endRect.centerY(), press * pulse);
        bmp.setAlpha(255);
    }

    private String statusTarget() {
        if (flashMsg != null && gt < flashMsgUntil) return flashMsg;
        if (dealing) return "Dealing";
        if (screen == Screen.COLOR) return "";
        if (shownCurrent > 0) {
            int d = (int) ((gt / 340) % 4);
            return d == 0 ? "Opponent is thinking" : d == 1 ? "Opponent is thinking." :
                    d == 2 ? "Opponent is thinking.." : "Opponent is thinking...";
        }
        if (!humanCanAct()) return "";          // a sequence is playing: say nothing rather than something stale
        if (!engine.hasLegal(0)) {
            if (engine.canDraw()) return "No match. Draw a card";
            if (engine.canEndTurn()) return "No match. End your turn";
        }
        if (engine.hands.get(0).size() == 2 && selectedLegal()) return "Hold the pile to call Touch One";
        if (engine.pendingFollowup && !engine.drewThisTurn) return "Play again or draw one";
        if (engine.drewThisTurn) return "Play it or end your turn";
        return "Your turn";
    }

    private void drawStatus(Canvas c) {
        String want = statusTarget();
        if (!want.equals(statusShown)) {
            statusPrev = statusShown;
            statusShown = want;
            statusChangedAt = gt;
        }
        if (gt - flashAt < 1500) return;        // the TOUCH ONE banner owns this space
        float fade = clamp01((gt - statusChangedAt) / 170f);
        float size = W * 0.044f;
        float base = H * 0.4875f;
        boolean chipOn = chipText != null && gt - chipAt < 1800;
        if (chipOn) {
            float t = clamp01((gt - chipAt) / 260f);
            float out = clamp01((gt - chipAt - 1500) / 300f);
            float sc = easeOutBack(t) * (1f - out * 0.1f);
            float cw = W * 0.74f, chh = H * 0.040f, cx = W / 2f, cy = H * 0.4745f;
            float al = 1f - out;
            c.save();
            c.translate(cx, cy);
            c.scale(sc, sc);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Theme.withAlpha(Color.argb(110, 20, 8, 0), al));
            tmp.set(-cw / 2f, -chh / 2f + 5 * u, cw / 2f, chh / 2f + 5 * u);
            c.drawRoundRect(tmp, chh / 2f, chh / 2f, fill);
            Gfx.shader(fill, new LinearGradient(0, -chh / 2f, 0, chh / 2f, Theme.shade(chipColor, 1.2f),
                    Theme.shade(chipColor, 0.85f), Shader.TileMode.CLAMP));
            fill.setAlpha((int) (255 * al));
            tmp.set(-cw / 2f, -chh / 2f, cw / 2f, chh / 2f);
            c.drawRoundRect(tmp, chh / 2f, chh / 2f, fill);
            fill.setShader(null);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4f * u);
            line.setColor(Theme.withAlpha(Color.WHITE, al));
            c.drawRoundRect(tmp, chh / 2f, chh / 2f, line);
            label(c, chipText, 0, Gfx.centeredBaseline(text, chipText, 0f) + 0f, size, Color.WHITE,
                    Theme.shade(chipColor, 0.45f), W * 0.010f, al);
            c.restore();
            return;
        }
        if (fade < 1f && !statusPrev.isEmpty()) {
            label(c, statusPrev, W / 2f, base - 8 * u * fade, size, Theme.CREAM, Theme.WOOD_INK, W * 0.011f, 1f - fade);
        }
        label(c, statusShown, W / 2f, base + 8 * u * (1f - fade), size, Theme.CREAM, Theme.WOOD_INK, W * 0.011f, fade);
    }

    // ---- effects -----------------------------------------------------------
    private void addRing(float x, float y, float maxR, int color) {
        Ring r = new Ring();
        r.x = x; r.y = y; r.maxR = maxR; r.at = gt; r.color = color;
        rings.add(r);
    }

    private void drawRings(Canvas c) {
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring r = rings.get(i);
            float t = (gt - r.at) / 420f;
            if (t >= 1f) {
                rings.remove(i);
                continue;
            }
            float e = easeOutCubic(t);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(2f, (1f - e) * 12f * u));
            line.setColor(Theme.withAlpha(r.color, (1f - t) * 0.85f));
            c.drawCircle(r.x, r.y, r.maxR * (0.35f + 0.65f * e), line);
        }
    }

    private void sparkle(float x, float y, int count, boolean colorful) {
        Card.Color[] cols = Card.Color.values();
        for (int i = 0; i < count && pn < MAX_P; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = (0.25f + rnd.nextFloat() * 0.55f) * W;
            px[pn] = x; py[pn] = y;
            pvx[pn] = (float) Math.cos(a) * sp;
            pvy[pn] = (float) Math.sin(a) * sp - W * 0.18f;
            pmax[pn] = plife[pn] = 520 + rnd.nextInt(380);
            psize[pn] = (7 + rnd.nextInt(9)) * u;
            prot[pn] = rnd.nextFloat() * 90f;
            pcol[pn] = colorful ? Theme.cardColor(cols[rnd.nextInt(4)]) : (rnd.nextBoolean() ? Theme.GOLD : Theme.CREAM);
            pn++;
        }
    }

    private void drawParticles(Canvas c) {
        float dt = frameDt / 1000f;
        fill.setStyle(Paint.Style.FILL);
        for (int i = pn - 1; i >= 0; i--) {
            plife[i] -= frameDt;
            if (plife[i] <= 0f) {
                pn--;
                px[i] = px[pn]; py[i] = py[pn]; pvx[i] = pvx[pn]; pvy[i] = pvy[pn];
                plife[i] = plife[pn]; pmax[i] = pmax[pn]; psize[i] = psize[pn]; prot[i] = prot[pn];
                pcol[i] = pcol[pn];
                continue;
            }
            pvy[i] += W * 1.5f * dt;
            pvx[i] *= 0.985f;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            float life = plife[i] / pmax[i];
            fill.setColor(Theme.withAlpha(pcol[i], Math.min(1f, life * 2f)));
            c.save();
            c.translate(px[i], py[i]);
            c.rotate(prot[i] + (1f - life) * 200f);
            float s = psize[i] * (0.4f + 0.6f * life);
            path.reset();
            path.moveTo(0, -s);
            path.lineTo(s * 0.35f, 0);
            path.lineTo(0, s);
            path.lineTo(-s * 0.35f, 0);
            path.close();
            c.drawPath(path, fill);
            c.rotate(90f);
            c.drawPath(path, fill);
            c.restore();
        }
    }

    private void popup(String s, float x, float y, int color) {
        Pop p = new Pop();
        p.text = s; p.x = x; p.y = y; p.at = gt; p.dur = 1150; p.color = color; p.size = W * 0.075f;
        pops.add(p);
    }

    private void drawPops(Canvas c) {
        for (int i = pops.size() - 1; i >= 0; i--) {
            Pop p = pops.get(i);
            float t = (gt - p.at) / (float) p.dur;
            if (t >= 1f) {
                pops.remove(i);
                continue;
            }
            float sc = easeOutBack(clamp01(t * 4f));
            float al = t > 0.68f ? 1f - (t - 0.68f) / 0.32f : 1f;
            float dy = -70f * u * easeOutCubic(t);
            c.save();
            c.translate(p.x, p.y + dy);
            c.scale(sc, sc);
            label(c, p.text, 0, 0, p.size, p.color, Theme.WOOD_INK, W * 0.016f, al);
            c.restore();
        }
    }

    private void drawTouchOneBanner(Canvas c) {
        long t = gt - flashAt;
        if (t < 0 || t > 1750) return;
        float in = clamp01(t / 300f);
        float sc = 0.5f + 0.5f * easeOutBack(in);
        if (t > 300) sc *= 1f + 0.018f * (float) Math.sin((t - 300) / 90.0);
        float al = t > 1450 ? 1f - (t - 1450) / 300f : 1f;
        float size = W * 0.15f;
        float cx = W / 2f, cy = H * 0.44f;
        c.save();
        c.translate(cx, cy);
        c.scale(sc, sc);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.CENTER);
        String s = "TOUCH ONE";
        float by = Gfx.centeredBaseline(text, s, 0f);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setStrokeWidth(size * 0.30f);
        text.setColor(Theme.withAlpha(Color.argb(150, 30, 10, 0), al));
        c.drawText(s, 0, by + size * 0.05f, text);
        text.setStrokeWidth(size * 0.24f);
        text.setColor(Theme.withAlpha(Theme.WOOD_INK, al));
        c.drawText(s, 0, by, text);
        text.setStyle(Paint.Style.FILL);
        text.setStrokeWidth(0f);
        text.setShader(new LinearGradient(0, -size * 0.5f, 0, size * 0.5f, Theme.GOLD, Theme.GOLD_DEEP,
                Shader.TileMode.CLAMP));
        text.setAlpha((int) (255 * al));
        c.drawText(s, 0, by, text);
        text.setShader(null);
        c.restore();
    }

    private void drawHoldRing(Canvas c) {
        if (!holding) return;
        float p = clamp01((gt - holdStart) / (float) CALL_HOLD_MS);
        float cx = discardRect.centerX(), cy = discardRect.centerY();
        float r = discardRect.width() * 0.86f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(Math.max(7f, W * 0.014f));
        line.setColor(Color.argb(70, 20, 8, 0));
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(tmp, 0, 360, false, line);
        line.setColor(p >= 1f ? Color.WHITE : Theme.GOLD);
        c.drawArc(tmp, -90, 360 * p, false, line);
        line.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- colour wheel ------------------------------------------------------
    private void drawWheel(Canvas c) {
        long el = gt - wheelAt;
        float open = clamp01(el / 260f);
        float pickT = wheelPicked >= 0 ? clamp01((gt - wheelPickAt) / 340f) : 0f;
        fill.setColor(Color.argb((int) (170 * open * (1f - pickT * 0.8f)), 14, 6, 2));
        c.drawRect(0, 0, W, H, fill);

        float cx = wheelCx(), cy = wheelCy(), r = wheelR();
        float st = clamp01(el / (float) WHEEL_SPIN_MS);
        float rot = (1f - easeOutQuart(st)) * 360f * wheelTurns;
        int step = (int) Math.floor((rot + 3600f) / 45f);
        if (el > 60 && step != lastTickStep && gt - lastTickAt >= 42 && st < 1f) {
            sfx.play("sfx_tick", 0.35f + 0.5f * (1f - (1f - st) * (1f - st)), 0.9f + 0.25f * st);
            if (st > 0.55f) haptic(0);
            lastTickAt = gt;
        }
        lastTickStep = step;

        float sc = easeOutBack(open) * (1f - 0.22f * easeInCubic(pickT));
        float al = 1f - clamp01((pickT - 0.45f) / 0.55f);
        label(c, "Choose a color", W / 2f, cy - r * 1.42f, W * 0.066f, Theme.CREAM, Theme.WOOD_INK, W * 0.016f,
                open * al);
        if (wheelPicked < 0 && st >= 1f) {
            label(c, "Tap outside to cancel", W / 2f, cy + r * 1.58f, W * 0.038f, Theme.CREAM, Theme.WOOD_INK,
                    W * 0.010f, 0.9f);
        }
        c.save();
        c.translate(cx, cy);
        c.scale(sc, sc);
        c.rotate(rot);
        bmp.setAlpha((int) (255 * al));
        c.drawBitmap(wheelBmp, -wheelBmp.getWidth() / 2f, -wheelBmp.getHeight() / 2f, bmp);
        bmp.setAlpha(255);
        if (wheelPicked >= 0) {
            tmp.set(-r, -r, r, r);
            fill.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                if (i == wheelPicked) continue;
                fill.setColor(Color.argb((int) (150 * al), 12, 6, 2));
                c.drawArc(tmp, -90 + i * 90, 90, true, fill);
            }
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(r * 0.05f);
            line.setColor(Theme.withAlpha(Color.WHITE, al));
            tmp.set(-r * 0.97f, -r * 0.97f, r * 0.97f, r * 0.97f);
            c.drawArc(tmp, -90 + wheelPicked * 90 + 2, 86, false, line);
        }
        c.restore();
    }

    private void wheelTap(float x, float y) {
        if (wheelPicked >= 0) return;
        float dx = x - wheelCx(), dy = y - wheelCy();
        float dist = (float) Math.hypot(dx, dy);
        float r = wheelR();
        long el = gt - wheelAt;
        if (dist > r * 1.38f) {
            if (el > 200) cancelWheel();
            return;
        }
        if (el < WHEEL_PICKABLE_MS || dist > r * 1.06f) return;
        float st = clamp01(el / (float) WHEEL_SPIN_MS);
        float rot = (1f - easeOutQuart(st)) * 360f * wheelTurns;
        float a = (float) Math.toDegrees(Math.atan2(dy, dx)) - rot;
        a = ((a + 90f) % 360f + 360f) % 360f;
        pickColor(Math.min(3, (int) (a / 90f)));
    }

    // =====================================================================
    // Input
    // =====================================================================
    private void select(int idx, boolean sound) {
        if (idx == selected) return;
        selected = idx;
        if (sound && gt - lastSelectSfx > 60) {
            sfx.play("sfx_select", 0.9f, 0.96f + rnd.nextFloat() * 0.08f);
            haptic(0);
            lastSelectSfx = gt;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (W == 0) return true;
        float x = e.getX(), y = e.getY();
        int action = e.getActionMasked();
        if (transition) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pressId = P_NONE;
            return true;
        }
        switch (screen) {
            case SPLASH: return touchSplash(action, x, y);
            case WIN:
            case LOSS: return touchEnd(action, x, y);
            case COLOR:
                if (action == MotionEvent.ACTION_UP) wheelTap(x, y);
                return true;
            default: return touchGame(action, x, y);
        }
    }

    private boolean touchSplash(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            pressId = P_NONE;
            for (int i = 0; i < 3; i++) {
                if (inflated(tileRect[i], 6 * u).contains(x, y) && gt - splashAt > 450 + i * 100) {
                    pressId = P_TILE;
                    pressTile = i;
                }
            }
        } else if (action == MotionEvent.ACTION_UP) {
            int t = pressTile;
            boolean was = pressId == P_TILE;
            pressId = P_NONE;
            if (was && t >= 0 && inflated(tileRect[t], 6 * u).contains(x, y)) startGame(t + 1);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            pressId = P_NONE;
        }
        return true;
    }

    private boolean touchEnd(int action, float x, float y) {
        boolean ready = gt - endAt > END_INPUT_GUARD_MS;
        if (action == MotionEvent.ACTION_DOWN) {
            pressId = (ready && againRect.contains(x, y)) ? P_AGAIN : P_NONE;
        } else if (action == MotionEvent.ACTION_UP) {
            boolean was = pressId == P_AGAIN;
            pressId = P_NONE;
            if (was && againRect.contains(x, y)) {
                sfx.play("sfx_click");
                fadeTo(new Runnable() {
                    @Override public void run() {
                        screen = Screen.SPLASH;
                        splashAt = gt;
                        trimScenes("bg_splash");
                    }
                });
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            pressId = P_NONE;
        }
        return true;
    }

    private RectF inflated(RectF r, float by) {
        tmp.set(r.left - by, r.top - by, r.right + by, r.bottom + by);
        return tmp;
    }

    private boolean touchGame(int action, float x, float y) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                pressId = P_NONE;
                scrubbed = false;
                dragUp = false;
                if (!humanCanAct()) return true;
                if (inflated(endRect, 10 * u).contains(x, y)) {
                    pressId = P_END;
                } else if (inflated(deckRect, 8 * u).contains(x, y)) {
                    pressId = P_DECK;
                } else if (inflated(discardRect, 8 * u).contains(x, y)) {
                    pressId = P_DISCARD;
                    if (engine.hands.get(0).size() == 2 && selectedLegal()) {
                        holding = true;
                        callArmed = false;
                        holdStart = gt;
                        holdStream = sfx.play("sfx_hold");
                    }
                } else if (y > H * 0.70f) {
                    pressId = P_HAND;
                    int idx = hitHand(x, y);
                    if (idx >= 0) select(idx, true);
                    dragStartSel = selected;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (holding) {
                    if (!inflated(discardRect, 24 * u).contains(x, y)) {
                        holding = false;
                        sfx.stop(holdStream);
                    } else {
                        checkHoldArmed();
                    }
                    return true;
                }
                if (pressId == P_HAND && humanCanAct()) {
                    float dx = x - downX, dy = y - downY;
                    int n = engine.hands.get(0).size();
                    if (!dragUp && !scrubbed && dy < -0.035f * H && Math.abs(dx) < 0.07f * W) {
                        dragUp = true;
                    }
                    if (dragUp) {
                        dragDy = Math.max(-0.30f * H, Math.min(0f, dy));
                    } else if (n > 0) {
                        float step = Math.max(16f, W * 0.048f);
                        int next = Math.max(0, Math.min(n - 1, dragStartSel + Math.round(dx / step)));
                        if (Math.abs(dx) > 16f * u) scrubbed = true;
                        select(next, true);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (holding) {
                    holding = false;
                    sfx.stop(holdStream);
                }
                pressId = P_NONE;
                dragUp = false;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int was = pressId;
                pressId = P_NONE;
                if (holding) {
                    checkHoldArmed();
                    holding = false;
                    sfx.stop(holdStream);
                    if (humanCanAct()) onDiscardTap(callArmed);
                    callArmed = false;
                    return true;
                }
                if (!humanCanAct()) {
                    dragUp = false;
                    return true;
                }
                if (was == P_HAND) {
                    boolean fling = dragUp && dragDy < -0.07f * H;
                    dragUp = false;
                    if (fling) onDiscardTap(false);
                } else if (was == P_END && inflated(endRect, 10 * u).contains(x, y)) {
                    humanEndTurn();
                } else if (was == P_DECK && inflated(deckRect, 8 * u).contains(x, y)) {
                    humanDraw();
                } else if (was == P_DISCARD && inflated(discardRect, 8 * u).contains(x, y)) {
                    onDiscardTap(false);
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void checkHoldArmed() {
        if (!holding || callArmed) return;
        if (gt - holdStart >= CALL_HOLD_MS) {
            callArmed = true;
            sfx.stop(holdStream);
            triggerTouchOne(0);
        }
    }

}
