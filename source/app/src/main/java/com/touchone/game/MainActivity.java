package com.touchone.game;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    enum Screen { SPLASH, GAME, WIN, LOSS, COLOR }

    private TableView table;
    private final Engine engine = new Engine();
    private Screen screen = Screen.SPLASH;
    private int selected = 0;
    private int colorPickFor = -1;
    private long wheelSpinStart;
    private static final long WHEEL_SPIN_MS = 2550;
    private String statusLine = "Your turn";
    private long colorFlashUntil;
    private final int[] lastCount = new int[]{-1, -1, -1, -1};
    private final long[] lowFlashUntil = new long[4];
    private long titleFlashUntil;
    private boolean holdingCall;
    private long holdStart;
    private boolean pendingCallPenalty;
    private long lastSelectSfx;
    private static final long CALL_HOLD_MS = 750;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SoundPool pool;
    private final Map<String, Integer> sfx = new HashMap<>();
    private Bitmap splash, tableBg, table1, table2, table3, winBg, lossBg, boy, girl, dog, cardBack, playAgain, endTurnBmp, scoobertSheet;
    private Bitmap opp1, opp2, opp3, bubble, bubbleRight, bubbleDown;
    private Movie winMovie;
    private long winAnimationStart;
    private final int[] charOrder = new int[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        loadBitmaps();
        loadSounds();
        table = new TableView();
        setContentView(table);
        enterImmersive();
    }

    private Bitmap bmp(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        return BitmapFactory.decodeResource(getResources(), id);
    }

    private void loadBitmaps() {
        splash = bmp("bg_splash");
        tableBg = bmp("bg_table");
        table1 = bmp("bg_table_1");
        table2 = bmp("bg_table_2");
        table3 = bmp("bg_table_3");
        winBg = bmp("bg_win");
        // The win animation is a complete 720x1600 animated plate.
        lossBg = bmp("bg_loss");
        boy = bmp("char_boy");
        girl = bmp("char_girl");
        dog = bmp("char_dog");
        cardBack = bmp("card_back");
        playAgain = bmp("btn_play_again");
        endTurnBmp = bmp("btn_end_sign"); if (endTurnBmp == null) endTurnBmp = bmp("btn_end_turn");
        opp1 = bmp("btn_opp_1");
        opp2 = bmp("btn_opp_2");
        opp3 = bmp("btn_opp_3");
        bubble = bmp("bubble_count");
        bubbleRight = bmp("bubble_count_right");
        bubbleDown = bmp("bubble_count_down");
        scoobertSheet = bmp("scoobert_dance_sheet");
        try (InputStream input = getResources().openRawResource(R.raw.win_scoobert)) {
            winMovie = Movie.decodeStream(input);
        } catch (Exception ignored) {
            winMovie = null;
        }
    }

    private void loadSounds() {
        pool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        String[] names = {"sfx_draw","sfx_select","sfx_play","sfx_pull2","sfx_pull4","sfx_spin",
                "sfx_jump","sfx_touch","sfx_opp","sfx_win","sfx_loss","sfx_click","sfx_wheel","sfx_touchone",
                "sfx_boy_yes","sfx_boy_ohno","sfx_girl_yes","sfx_girl_ohno","sfx_dog_yes","sfx_dog_ohno"};
        for (String n : names) {
            int id = getResources().getIdentifier(n, "raw", getPackageName());
            if (id != 0) sfx.put(n, pool.load(this, id, 1));
        }
    }

    private void playSfx(String name) {
        Integer id = sfx.get(name);
        if (id != null) pool.play(id, 1f, 1f, 1, 0, 1f);
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

    private void playChar(int seat, boolean good) {
        String who = charVoice(seat);
        if (who == null) return;
        playSfx("sfx_" + who + (good ? "_yes" : "_ohno"));
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void startGame(int opponents) {
        playSfx("sfx_click");
        engine.start(opponents);
        winAnimationStart = 0;
        selected = 0;
        colorPickFor = -1;
        pendingCallPenalty = false;
        holdingCall = false;
        titleFlashUntil = 0;
        for (int i = 0; i < 4; i++) {
            lastCount[i] = -1;
            lowFlashUntil[i] = 0;
        }
        statusLine = "Your turn — play a card or draw one";
        colorFlashUntil = 0;
        int[] poolIdx = {0, 1, 2};
        for (int i = 0; i < 3; i++) {
            int j = engine.rng.nextInt(3);
            int t = poolIdx[i];
            poolIdx[i] = poolIdx[j];
            poolIdx[j] = t;
        }
        System.arraycopy(poolIdx, 0, charOrder, 0, 3);
        screen = Screen.GAME;
        noteCounts();
        table.invalidate();
    }

    private void noteCounts() {
        long now = System.currentTimeMillis();
        int seats = engine.seats;
        for (int s = 0; s < seats; s++) {
            int n = engine.hands.get(s).size();
            int prev = lastCount[s];
            if (prev >= 0 && prev > 3 && n > 0 && n <= 3) {
                lowFlashUntil[s] = now + 900;
            }
            if (prev >= 0 && prev != 1 && n == 1) {
                if (now >= titleFlashUntil) playSfx("sfx_touchone");
                titleFlashUntil = now + 1500;
            }
            lastCount[s] = n;
        }
        if (now < titleFlashUntil || now < lowFlashUntil[0] || now < lowFlashUntil[1]
                || now < lowFlashUntil[2] || now < lowFlashUntil[3]) {
            table.postInvalidateOnAnimation();
        }
    }

    private int countColor(int seat, int n) {
        if (n > 3 || n <= 0) return Color.BLACK;
        long now = System.currentTimeMillis();
        if (now < lowFlashUntil[seat]) {
            return ((now / 120) % 2 == 0) ? Color.rgb(220, 16, 16) : Color.BLACK;
        }
        return Color.rgb(220, 16, 16);
    }

    private void afterHumanPlay(String result) {
        if ("win".equals(result)) {
            screen = Screen.WIN;
            playSfx("sfx_win");
            table.invalidate();
            return;
        }
        if ("illegal".equals(result)) return;
        if (pendingCallPenalty) {
            engine.give(0, 2);
            pendingCallPenalty = false;
            statusLine = "Forgot to call Touch One — pull 2";
            playSfx("sfx_pull2");
        }
        playForResult(result);
        noteCounts();
        if ("pull2".equals(result) || "pull4".equals(result)) {
            playChar(engine.lastDrawTarget, false);
        }
        if (engine.announcedColor != null) {
            statusLine = engine.announcedKind + " → " + colorName(engine.announcedColor);
            colorFlashUntil = System.currentTimeMillis() + 1400;
        }
        if (engine.gameOver) {
            screen = engine.playerWon ? Screen.WIN : Screen.LOSS;
            playSfx(engine.playerWon ? "sfx_win" : "sfx_loss");
            table.invalidate();
            return;
        }
        if (engine.current != 0) scheduleComputers();
        table.invalidate();
    }

    private void playForResult(String result) {
        switch (result) {
            case "pull2": playSfx("sfx_pull2"); break;
            case "pull4": playSfx("sfx_pull4"); break;
            case "spin": playSfx("sfx_spin"); break;
            case "jump": playSfx("sfx_jump"); break;
            default: playSfx("sfx_play"); break;
        }
    }

    private void scheduleComputers() {
        long wait = engine.announcedColor != null ? 3200 : 2200;
        handler.postDelayed(this::computerStep, wait);
    }

    private void computerStep() {
        if (screen != Screen.GAME || engine.gameOver) return;
        if (engine.current == 0) {
            table.invalidate();
            return;
        }
        playSfx("sfx_opp");
        int seat = engine.current;
        int pick = engine.computerPick(seat);
        if (pick < 0) {
            engine.drawOne(seat);
            playSfx("sfx_draw");
            playChar(seat, false);
            noteCounts();
            engine.passTo(engine.nextSeat(seat));
        } else {
            Card c = engine.hands.get(seat).get(pick);
            Card.Color col = c.isWild() ? engine.randomColor() : c.color;
            String r = engine.play(seat, pick, col);
            playForResult(r);
            playChar(seat, true);
            if ("pull2".equals(r) || "pull4".equals(r)) playChar(engine.lastDrawTarget, false);
            noteCounts();
            if (c.isWild() && engine.announcedColor != null) {
                statusLine = engine.announcedKind + " → " + colorName(engine.announcedColor);
                colorFlashUntil = System.currentTimeMillis() + 1400;
            }
            if (engine.gameOver) {
                screen = engine.playerWon ? Screen.WIN : Screen.LOSS;
                playSfx(engine.playerWon ? "sfx_win" : "sfx_loss");
                table.invalidate();
                return;
            }
        }
        table.invalidate();
        if (!engine.gameOver && engine.current != 0) scheduleComputers();
    }

    private int colorInt(Card.Color c) {
        if (c == null) return Color.rgb(30, 41, 59);
        switch (c) {
            case GREEN: return Color.rgb(34, 197, 74);
            case PURPLE: return Color.rgb(147, 51, 234);
            case PINK: return Color.rgb(236, 72, 153);
            case ORANGE: return Color.rgb(249, 115, 22);
        }
        return Color.GRAY;
    }

    private String colorName(Card.Color c) {
        if (c == null) return "";
        switch (c) {
            case GREEN: return "GREEN";
            case PURPLE: return "PURPLE";
            case PINK: return "PINK";
            case ORANGE: return "ORANGE";
        }
        return "";
    }

    class TableView extends View {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF tmp = new RectF();
        float swipeStartX, swipeStartY;
        int dragStartSelected;
        boolean draggingHand;
        boolean handMoved;
        RectF discardRect = new RectF();
        RectF drawRect = new RectF();
        RectF endRect = new RectF();
        RectF againRect = new RectF();
        RectF[] oppHit = new RectF[]{new RectF(), new RectF(), new RectF()};

        TableView() {
            super(MainActivity.this);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (screen == Screen.SPLASH) drawSplash(c, w, h);
            else if (screen == Screen.WIN) drawEnd(c, w, h, true);
            else if (screen == Screen.LOSS) drawEnd(c, w, h, false);
            else drawGame(c, w, h);
            if (screen == Screen.COLOR) drawColorWheel(c, w, h);
        }

        void drawSplash(Canvas c, int w, int h) {
            if (splash != null) c.drawBitmap(splash, null, new Rect(0, 0, w, h), paint);
            float y = h * 0.86f;
            float bw = w * 0.22f;
            float gap = w * 0.06f;
            float start = (w - (3 * bw + 2 * gap)) / 2f;
            Bitmap[] btns = {opp1, opp2, opp3};
            for (int i = 0; i < 3; i++) {
                float x = start + i * (bw + gap);
                oppHit[i].set(x, y, x + bw, y + bw);
                if (btns[i] != null) c.drawBitmap(btns[i], null, oppHit[i], paint);
            }
        }

        void drawEnd(Canvas c, int w, int h, boolean win) {
            if (win && winMovie != null) {
                if (winAnimationStart == 0) winAnimationStart = System.currentTimeMillis();
                int duration = Math.max(1, winMovie.duration());
                int elapsed = (int) ((System.currentTimeMillis() - winAnimationStart) % duration);
                winMovie.setTime(elapsed);
                c.save();
                c.scale(w / (float) winMovie.width(), h / (float) winMovie.height());
                winMovie.draw(c, 0, 0);
                c.restore();
                // The animated plate already contains the Play Again art.
                againRect.set(w * 0.18f, h * 0.78f, w * 0.82f, h * 0.89f);
                postInvalidateDelayed(65L);
                return;
            }
            Bitmap bg = win ? winBg : lossBg;
            if (bg != null) c.drawBitmap(bg, null, new Rect(0, 0, w, h), paint);
            if (win && scoobertSheet != null) {
                long elapsed = System.currentTimeMillis() % 2000L;
                int frame = (int) (elapsed / 133L) % 15;
                int col = frame % 5;
                int row = frame / 5;
                int sw = scoobertSheet.getWidth();
                int sh = scoobertSheet.getHeight();
                Rect src = new Rect(col * sw / 5, row * sh / 3,
                        (col + 1) * sw / 5, (row + 1) * sh / 3);
                RectF dst = new RectF(w * 0.18f, h * 0.53f, w * 0.82f, h * 0.96f);
                c.drawBitmap(scoobertSheet, src, dst, paint);
                postInvalidateDelayed(133L);
            }
            againRect.set(w * 0.18f, h * 0.78f, w * 0.82f, h * 0.88f);
            if (playAgain != null) c.drawBitmap(playAgain, null, againRect, paint);
        }

        void drawGame(Canvas c, int w, int h) {
            int opp = engine.seats - 1;
            Bitmap scene = opp == 1 ? table1 : opp == 2 ? table2 : table3;
            if (scene == null) scene = tableBg;
            if (scene != null) c.drawBitmap(scene, null, new Rect(0, 0, w, h), paint);
            drawTurnGlow(c, w, h, opp);

            // Thought-bubble counts above each opponent. Last seat on the right uses a flipped trail.
            float[][] hats1 = {{0.24f, 0.125f}};
            float[][] hats2 = {{0.22f, 0.135f}, {0.78f, 0.135f}};
            float[][] hats3 = {{0.16f, 0.225f}, {0.50f, 0.205f}, {0.84f, 0.210f}};
            int[] style1 = {1}; // 0 left trail, 1 right trail, 2 down trail
            int[] style2 = {0, 1};
            int[] style3 = {0, 2, 1};
            float[][] hats = opp == 1 ? hats1 : opp == 2 ? hats2 : hats3;
            int[] style = opp == 1 ? style1 : opp == 2 ? style2 : style3;
            float bw0 = w * (opp == 1 ? 0.345f : opp == 2 ? 0.30f : 0.27f);
            float pulse = turnPulse();
            for (int i = 0; i < opp; i++) {
                float scale = (engine.current == i + 1) ? pulse : 1f;
                float bw = bw0 * scale;
                float bh = bw * (style[i] == 2 ? 0.80f : 0.72f);
                float cx = w * hats[i][0];
                float cy = h * hats[i][1];
                tmp.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
                Bitmap cloud = bubble;
                if (style[i] == 1 && bubbleRight != null) cloud = bubbleRight;
                if (style[i] == 2 && bubbleDown != null) cloud = bubbleDown;
                if (cloud != null) {
                    paint.setAlpha(255);
                    c.drawBitmap(cloud, null, tmp, paint);
                }
                String n = String.valueOf(engine.hands.get(i + 1).size());
                text.setTextAlign(Paint.Align.CENTER);
                text.setFakeBoldText(true);
                text.setStyle(Paint.Style.FILL);
                text.setStrokeWidth(0f);
                text.setColor(countColor(i + 1, engine.hands.get(i + 1).size()));
                text.setTextSize(bh * (style[i] == 2 ? 0.42f : 0.50f));
                Paint.FontMetrics fm = text.getFontMetrics();
                float cloudCy = style[i] == 2 ? cy - bh * 0.16f : cy - bh * 0.08f;
                float ny = cloudCy - (fm.ascent + fm.descent) / 2f;
                c.drawText(n, cx, ny, text);
                text.setFakeBoldText(false);
            }

            float cw = w * 0.22f;
            float chh = cw * 1.4f;
            drawRect.set(w * 0.16f, h * 0.50f, w * 0.16f + cw, h * 0.50f + chh);
            discardRect.set(w * 0.58f, h * 0.50f, w * 0.58f + cw, h * 0.50f + chh);
            if (cardBack != null) c.drawBitmap(cardBack, null, drawRect, paint);
            drawCardFace(c, engine.top(), discardRect, true);

            text.setTextSize(h * 0.022f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Color.WHITE);
            c.drawText(engine.canDraw() ? "DRAW" : "DRAWN", drawRect.centerX(), drawRect.bottom + 36, text);
            c.drawText("DISCARD", discardRect.centerX(), discardRect.bottom + 36, text);

            drawDirArrow(c, w, h);

            drawHand(c, w, h);
            drawPlayerCloud(c, w, h);
            drawTouchTitle(c, w, h);

            float es = w * 0.16f;
            float ecx = w * 0.0897f;
            float ecy = h * 0.6151f;
            endRect.set(ecx - es / 2f, ecy - es / 2f, ecx + es / 2f, ecy + es / 2f);
            boolean endOk = screen != Screen.COLOR && engine.canEndTurn();
            paint.setAlpha(endOk ? 255 : 120);
            if (endTurnBmp != null) c.drawBitmap(endTurnBmp, null, endRect, paint);
            paint.setAlpha(255);

            text.setTextSize(h * 0.02f);
            String turn;
            if (engine.current != 0) turn = "Opponent thinking…";
            else if (engine.pendingFollowup) turn = "Play another card or draw one";
            else if (engine.drewThisTurn) turn = "Play the card or End Turn";
            else turn = "Your turn — play or draw one";
            if (statusLine != null && statusLine.contains("→")) turn = statusLine;
            c.drawText(turn, w / 2f, h * 0.48f, text);

            boolean showColor = engine.announcedColor != null
                    && (colorFlashUntil == 0 || System.currentTimeMillis() < colorFlashUntil);
            if (showColor) {
                paint.setColor(colorInt(engine.announcedColor));
                tmp.set(w * 0.12f, h * 0.455f, w * 0.88f, h * 0.495f);
                c.drawRoundRect(tmp, 24, 24, paint);
                text.setColor(Color.WHITE);
                text.setTextSize(h * 0.022f);
                c.drawText(engine.announcedKind + "  ·  " + colorName(engine.announcedColor),
                        w / 2f, h * 0.482f, text);
            }
            postInvalidateOnAnimation();
        }

        float turnPulse() {
            double t = System.currentTimeMillis() / 420.0;
            return 1f + 0.045f * (float) Math.sin(t * Math.PI * 2.0);
        }

        void drawTurnGlow(Canvas c, int w, int h, int opp) {
            int seat = engine.current;
            if (seat <= 0 || engine.gameOver) return;
            float[][] glow1 = {{0.5582f, 0.2302f}};
            float[][] glow2 = {{0.2519f, 0.3776f}, {0.7788f, 0.3992f}};
            float[][] glow3 = {{0.1483f, 0.4617f}, {0.5021f, 0.4530f}, {0.8425f, 0.4524f}};
            float[][] g = opp == 1 ? glow1 : opp == 2 ? glow2 : glow3;
            int idx = seat - 1;
            if (idx < 0 || idx >= g.length) return;
            float cx = w * g[idx][0];
            float cy = h * g[idx][1];
            float base = w * (opp == 1 ? 0.38f : 0.28f);
            float p = turnPulse();
            float r = base * (0.92f + (p - 1f) * 4f);
            paint.setShader(new RadialGradient(cx, cy, r,
                    Color.argb(90, 255, 230, 80), Color.argb(0, 255, 200, 40), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(6f, w * 0.012f));
            int a = 80 + (int) (70 * ((p - 1f) / 0.045f * 0.5f + 0.5f));
            if (a < 40) a = 40;
            if (a > 180) a = 180;
            paint.setColor(Color.argb(a, 255, 220, 70));
            c.drawCircle(cx, cy, r * 0.62f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        Bitmap charBmp(int idx) {
            if (idx == 0) return boy;
            if (idx == 1) return girl;
            return dog;
        }

        void drawHand(Canvas c, int w, int h) {
            List<Card> hand = engine.hands.get(0);
            if (hand.isEmpty()) return;
            if (selected >= hand.size()) selected = hand.size() - 1;
            if (selected < 0) selected = 0;
            int n = hand.size();
            float cardW = w * 0.28f;
            float cardH = cardW * 1.4f;
            float y = h * 0.792f;
            float overlap = Math.min(cardW * 0.62f, (w * 0.86f) / Math.max(n, 1));
            float total = overlap * (n - 1) + cardW;
            float x0 = (w - total) / 2f;
            for (int i = 0; i < n; i++) {
                if (i == selected) continue;
                tmp.set(x0 + i * overlap, y + 28, x0 + i * overlap + cardW * 0.72f, y + 28 + cardH * 0.72f);
                drawCardFace(c, hand.get(i), tmp, false);
            }
            tmp.set(x0 + selected * overlap - 8, y - 12, x0 + selected * overlap + cardW, y - 12 + cardH);
            drawCardFace(c, hand.get(selected), tmp, false);
        }

        void drawDirArrow(Canvas c, int w, int h) {
            float cx = w * 0.50f;
            float cy = h * 0.635f;
            float s = w * 0.055f;
            boolean right = engine.dir >= 0;
            Path body = new Path();
            if (right) {
                body.moveTo(cx - s * 0.55f, cy - s * 0.28f);
                body.lineTo(cx + s * 0.05f, cy - s * 0.28f);
                body.lineTo(cx + s * 0.05f, cy - s * 0.55f);
                body.lineTo(cx + s * 0.85f, cy);
                body.lineTo(cx + s * 0.05f, cy + s * 0.55f);
                body.lineTo(cx + s * 0.05f, cy + s * 0.28f);
                body.lineTo(cx - s * 0.55f, cy + s * 0.28f);
            } else {
                body.moveTo(cx + s * 0.55f, cy - s * 0.28f);
                body.lineTo(cx - s * 0.05f, cy - s * 0.28f);
                body.lineTo(cx - s * 0.05f, cy - s * 0.55f);
                body.lineTo(cx - s * 0.85f, cy);
                body.lineTo(cx - s * 0.05f, cy + s * 0.55f);
                body.lineTo(cx - s * 0.05f, cy + s * 0.28f);
                body.lineTo(cx + s * 0.55f, cy + s * 0.28f);
            }
            body.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(90, 0, 0, 0));
            c.save();
            c.translate(0, Math.max(3f, w * 0.006f));
            c.drawPath(body, paint);
            c.restore();
            paint.setShader(new LinearGradient(cx, cy - s, cx, cy + s,
                    Color.rgb(70, 70, 70), Color.rgb(8, 8, 8), Shader.TileMode.CLAMP));
            c.drawPath(body, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, w * 0.007f));
            paint.setColor(Color.rgb(18, 18, 18));
            c.drawPath(body, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(70, 255, 255, 255));
            Path sheen = new Path();
            if (right) {
                sheen.moveTo(cx - s * 0.50f, cy - s * 0.18f);
                sheen.lineTo(cx + s * 0.02f, cy - s * 0.18f);
                sheen.lineTo(cx + s * 0.02f, cy - s * 0.08f);
                sheen.lineTo(cx - s * 0.50f, cy - s * 0.08f);
            } else {
                sheen.moveTo(cx + s * 0.50f, cy - s * 0.18f);
                sheen.lineTo(cx - s * 0.02f, cy - s * 0.18f);
                sheen.lineTo(cx - s * 0.02f, cy - s * 0.08f);
                sheen.lineTo(cx + s * 0.50f, cy - s * 0.08f);
            }
            sheen.close();
            c.drawPath(sheen, paint);
        }

        void drawPlayerCloud(Canvas c, int w, int h) {
            int n = engine.hands.get(0).size();
            float scale = engine.current == 0 ? turnPulse() : 1f;
            float bw = w * 0.26f * scale;
            float bh = bw * 0.68f;
            float cx = w * 0.4940f;
            float cy = h * 0.728f;
            tmp.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
            if (bubble != null) {
                paint.setAlpha(255);
                c.drawBitmap(bubble, null, tmp, paint);
            }
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            text.setStyle(Paint.Style.FILL);
            text.setColor(countColor(0, n));
            text.setTextSize(bh * 0.50f);
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(String.valueOf(n), cx + bw * 0.03f, cy - bh * 0.06f - (fm.ascent + fm.descent) / 2f, text);
            text.setFakeBoldText(false);
            text.setColor(Color.WHITE);
            if (engine.current == 0 && !engine.gameOver) {
                float p = turnPulse();
                float r = bw * 0.72f * (0.92f + (p - 1f) * 4f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(6f, w * 0.012f));
                int a = 80 + (int) (70 * ((p - 1f) / 0.045f * 0.5f + 0.5f));
                if (a < 40) a = 40;
                if (a > 180) a = 180;
                paint.setColor(Color.argb(a, 255, 220, 70));
                c.drawCircle(cx, cy, r * 0.62f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        void drawTouchTitle(Canvas c, int w, int h) {
            long now = System.currentTimeMillis();
            float a = 0f;
            if (holdingCall) {
                float p = (now - holdStart) / (float) CALL_HOLD_MS;
                if (p > 0.25f) a = Math.min(1f, (p - 0.25f) / 0.75f);
                postInvalidateOnAnimation();
            }
            if (now < titleFlashUntil) {
                a = ((now / 130) % 2 == 0) ? 1f : 0.45f;
                postInvalidateOnAnimation();
            }
            if (a <= 0.02f) return;
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            text.setTextSize(h * 0.058f);
            int alpha = (int) (255 * a);
            text.setStyle(Paint.Style.STROKE);
            text.setStrokeWidth(10f);
            text.setColor(Color.argb(alpha, 40, 0, 0));
            c.drawText("TOUCH ONE", w / 2f, h * 0.44f, text);
            text.setStyle(Paint.Style.FILL);
            text.setStrokeWidth(0f);
            text.setColor(Color.argb(alpha, 235, 28, 28));
            c.drawText("TOUCH ONE", w / 2f, h * 0.44f, text);
            text.setFakeBoldText(false);
            text.setColor(Color.WHITE);
        }

        int shade(int color, float mul) {
            int r = Math.min(255, Math.max(0, (int) (Color.red(color) * mul)));
            int g = Math.min(255, Math.max(0, (int) (Color.green(color) * mul)));
            int b = Math.min(255, Math.max(0, (int) (Color.blue(color) * mul)));
            return Color.rgb(r, g, b);
        }

        void drawCardFace(Canvas c, Card card, RectF box, boolean onDiscard) {
            float r = Math.min(box.width(), box.height()) * 0.12f;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(Color.argb(90, 0, 0, 0));
            c.drawRoundRect(box.left + 4, box.top + 7, box.right + 4, box.bottom + 9, r, r, paint);

            boolean coloredWild = card.isWild() && onDiscard && engine.liveColor != null;
            boolean wildFace = card.isWild() && !coloredWild;

            paint.setColor(Color.rgb(10, 10, 12));
            c.drawRoundRect(box, r, r, paint);

            float inset = Math.max(6f, box.width() * 0.07f);
            RectF plate = new RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset);
            float ir = r * 0.55f;
            int body = wildFace ? Color.rgb(28, 28, 34)
                    : (coloredWild ? colorInt(engine.liveColor) : colorInt(card.color));
            paint.setColor(body);
            c.drawRoundRect(plate, ir, ir, paint);
            // thin top edge only — no wash
            paint.setColor(Color.argb(36, 255, 255, 255));
            c.drawRoundRect(plate.left + 3, plate.top + 2, plate.right - 3, plate.top + plate.height() * 0.16f, ir, ir, paint);

            int cream = Color.rgb(255, 244, 220);
            float ox = box.width() * 0.22f, oy = box.height() * 0.27f;
            RectF oval = new RectF(box.left + ox, box.top + oy, box.right - ox, box.bottom - oy);
            paint.setColor(cream);
            c.drawOval(oval, paint);
            if (wildFace) {
                float wcx = oval.centerX(), wcy = oval.centerY() - oval.height() * 0.06f;
                float rr = Math.min(oval.width(), oval.height()) * 0.32f;
                Card.Color[] cols = Card.Color.values();
                for (int i = 0; i < 4; i++) {
                    paint.setColor(colorInt(cols[i]));
                    c.drawArc(wcx - rr, wcy - rr, wcx + rr, wcy + rr, -90 + i * 90, 90, true, paint);
                }
            }

            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            text.setStyle(Paint.Style.FILL);
            text.setStrokeWidth(0f);
            text.setColor(Color.BLACK);

            float cx = box.centerX();
            float cy = box.centerY();
            if (card.kind == Card.Kind.JUMP) {
                drawJumpMark(c, cx, cy, box.width() * 0.28f);
            } else if (card.kind == Card.Kind.SPIN) {
                drawSpinMark(c, cx, cy, box.width() * 0.28f);
            } else if (card.kind == Card.Kind.TOUCH && wildFace) {
                text.setTextSize(box.height() * 0.16f);
                c.drawText("T1", cx, cy + box.height() * 0.22f, text);
            } else if (card.kind == Card.Kind.PULL4 && wildFace) {
                text.setTextSize(box.height() * 0.18f);
                c.drawText("+4", cx, cy + box.height() * 0.24f, text);
            } else {
                String lab = card.label();
                float main = box.height() * (lab.length() > 2 ? 0.22f : 0.40f);
                text.setTextSize(main);
                c.drawText(lab, cx, cy + main * 0.34f, text);
            }

            String pip;
            if (card.kind == Card.Kind.NUMBER) pip = String.valueOf(card.number);
            else if (card.kind == Card.Kind.PULL2) pip = "+2";
            else if (card.kind == Card.Kind.PULL4) pip = "+4";
            else if (card.kind == Card.Kind.TOUCH) pip = "1";
            else if (card.kind == Card.Kind.JUMP) pip = "J";
            else pip = "S";
            text.setTextSize(Math.max(18f, box.height() * 0.13f));
            text.setColor(cream);
            c.drawText(pip, box.left + box.width() * 0.20f, box.top + box.height() * 0.16f, text);
            c.save();
            c.rotate(180, box.centerX(), box.centerY());
            c.drawText(pip, box.left + box.width() * 0.20f, box.top + box.height() * 0.16f, text);
            c.restore();
            text.setFakeBoldText(false);
        }

        void drawJumpMark(Canvas c, float cx, float cy, float r) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(6f, r * 0.18f));
            paint.setColor(Color.BLACK);
            c.drawCircle(cx, cy, r, paint);
            float d = r * 0.72f;
            c.drawLine(cx - d, cy + d, cx + d, cy - d, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        void drawSpinMark(Canvas c, float cx, float cy, float r) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(6f, r * 0.16f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.BLACK);
            RectF a = new RectF(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(a, 20, 140, false, paint);
            c.drawArc(a, 200, 140, false, paint);
            paint.setStyle(Paint.Style.FILL);
            Path p = new Path();
            p.moveTo(cx + r * 0.15f, cy - r);
            p.lineTo(cx + r * 0.55f, cy - r * 0.55f);
            p.lineTo(cx - r * 0.05f, cy - r * 0.55f);
            p.close();
            c.drawPath(p, paint);
            Path p2 = new Path();
            p2.moveTo(cx - r * 0.15f, cy + r);
            p2.lineTo(cx - r * 0.55f, cy + r * 0.55f);
            p2.lineTo(cx + r * 0.05f, cy + r * 0.55f);
            p2.close();
            c.drawPath(p2, paint);
        }

        void drawColorWheel(Canvas c, int w, int h) {
            float cx = w / 2f, cy = h * 0.48f, r = w * 0.28f;
            long elapsed = System.currentTimeMillis() - wheelSpinStart;
            float t = elapsed / (float) WHEEL_SPIN_MS;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            float rot = (1f - ease) * 360f * 18f;
            c.save();
            c.rotate(rot, cx, cy);
            Card.Color[] cols = Card.Color.values();
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(Color.argb(110, 0, 0, 0));
            c.drawCircle(cx + 8, cy + 14, r + 10, paint);

            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    Color.rgb(80, 80, 88), Color.rgb(12, 12, 16), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r + 14, paint);
            paint.setShader(null);
            paint.setColor(Color.rgb(8, 8, 10));
            c.drawCircle(cx, cy, r + 6, paint);

            RectF pie = new RectF(cx - r, cy - r, cx + r, cy + r);
            for (int i = 0; i < 4; i++) {
                int body = colorInt(cols[i]);
                float start = -90 + i * 90;
                paint.setShader(new RadialGradient(cx - r * 0.18f, cy - r * 0.22f, r * 1.15f,
                        shade(body, 1.18f), shade(body, 0.78f), Shader.TileMode.CLAMP));
                c.drawArc(pie, start, 90, true, paint);
                paint.setShader(null);
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, r * 0.018f));
            paint.setColor(Color.argb(90, 0, 0, 0));
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(-90 + i * 90);
                c.drawLine(cx, cy, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, paint);
            }
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(Color.argb(50, 255, 255, 255));
            c.drawArc(cx - r * 0.92f, cy - r * 0.92f, cx + r * 0.92f, cy + r * 0.92f,
                    200, 150, false, paint);

            float hub = r * 0.20f;
            paint.setColor(Color.argb(80, 0, 0, 0));
            c.drawCircle(cx + 3, cy + 5, hub, paint);
            paint.setShader(new RadialGradient(cx - hub * 0.25f, cy - hub * 0.30f, hub * 1.1f,
                    Color.rgb(255, 248, 230), Color.rgb(210, 190, 150), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, hub, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3f, hub * 0.12f));
            paint.setColor(Color.rgb(40, 32, 24));
            c.drawCircle(cx, cy, hub, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(120, 255, 255, 255));
            c.drawCircle(cx - hub * 0.28f, cy - hub * 0.30f, hub * 0.22f, paint);
            c.restore();
            if (t < 1f) postInvalidateOnAnimation();
        }

        int cardAtX(float x, int w, int n) {
            if (n <= 0) return 0;
            float cardW = w * 0.28f;
            float overlap = Math.min(cardW * 0.62f, (w * 0.86f) / Math.max(n, 1));
            float total = overlap * (n - 1) + cardW;
            float x0 = (w - total) / 2f;
            int idx = Math.round((x - x0 - cardW * 0.35f) / Math.max(1f, overlap));
            if (idx < 0) idx = 0;
            if (idx > n - 1) idx = n - 1;
            return idx;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            int w = getWidth(), h = getHeight();
            int action = e.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                swipeStartX = x;
                swipeStartY = y;
                dragStartSelected = selected;
                holdingCall = false;
                draggingHand = screen == Screen.GAME && y > h * 0.76f && !discardRect.contains(x, y) && !drawRect.contains(x, y) && !endRect.contains(x, y);
                handMoved = false;
                if (screen == Screen.GAME && engine.current == 0 && !engine.gameOver && discardRect.contains(x, y)) {
                    List<Card> hand = engine.hands.get(0);
                    if (hand.size() == 2 && selected >= 0 && selected < 2 && engine.legal(hand.get(selected), null)) {
                        holdingCall = true;
                        holdStart = System.currentTimeMillis();
                        invalidate();
                    }
                }
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (holdingCall) {
                    if (!discardRect.contains(x, y)) holdingCall = false;
                    invalidate();
                    return true;
                }
                if (draggingHand && screen == Screen.GAME && engine.hands.get(0).size() > 0) {
                    float dx = x - swipeStartX;
                    if (Math.abs(dx) > 16f) handMoved = true;
                    float step = Math.max(16f, w * 0.048f);
                    int n = engine.hands.get(0).size();
                    int next = dragStartSelected + Math.round(dx / step);
                    if (next < 0) next = 0;
                    if (next > n - 1) next = n - 1;
                    if (next != selected) {
                        selected = next;
                        long now = System.currentTimeMillis();
                        if (now - lastSelectSfx > 70) {
                            playSfx("sfx_select");
                            lastSelectSfx = now;
                        }
                        invalidate();
                    }
                }
                return true;
            }

            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return true;

            if (holdingCall && screen == Screen.GAME) {
                boolean called = System.currentTimeMillis() - holdStart >= CALL_HOLD_MS;
                holdingCall = false;
                playSelectedFromDiscard(called);
                return true;
            }

            if (endRect.contains(x, y) && screen == Screen.GAME) {
                draggingHand = false;
                if (engine.current != 0 || engine.gameOver || screen == Screen.COLOR || !engine.canEndTurn()) {
                    playSfx("sfx_click");
                    statusLine = engine.pendingFollowup
                            ? "Play another card or draw one first"
                            : "Play a card or draw one first";
                    invalidate();
                    return true;
                }
                playSfx("sfx_click");
                engine.passTo(engine.nextSeat(0));
                statusLine = "Opponent thinking…";
                scheduleComputers();
                invalidate();
                return true;
            }

            if (draggingHand && screen == Screen.GAME) {
                int n = engine.hands.get(0).size();
                if (n > 0) {
                    float dx = x - swipeStartX;
                    if (!handMoved && Math.abs(dx) < 24f && Math.abs(y - swipeStartY) < 36f) {
                        selected = cardAtX(x, w, n);
                        playSfx("sfx_select");
                    } else if (!handMoved && Math.abs(dx) >= 24f && Math.abs(dx) < w * 0.08f) {
                        if (dx > 0) selected = Math.min(n - 1, dragStartSelected + 1);
                        else selected = Math.max(0, dragStartSelected - 1);
                        playSfx("sfx_select");
                    }
                    invalidate();
                }
                draggingHand = false;
                return true;
            }

            if (screen == Screen.SPLASH) {
                for (int i = 0; i < 3; i++) if (oppHit[i].contains(x, y)) startGame(i + 1);
                return true;
            }
            if (screen == Screen.WIN || screen == Screen.LOSS) {
                if (againRect.contains(x, y)) {
                    playSfx("sfx_click");
                    screen = Screen.SPLASH;
                    invalidate();
                }
                return true;
            }
            if (screen == Screen.COLOR) {
                if (System.currentTimeMillis() - wheelSpinStart < WHEEL_SPIN_MS) return true;
                float cx = getWidth() / 2f, cy = getHeight() * 0.48f;
                float wr = getWidth() * 0.28f + 16f;
                float dist = (float) Math.hypot(x - cx, y - cy);
                if (dist > wr) return true;
                float a = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                a = (a + 360 + 90) % 360;
                int idx = (int) (a / 90f);
                if (idx < 0 || idx > 3) idx = 0;
                Card.Color chosen = Card.Color.values()[idx];
                screen = Screen.GAME;
                String r = engine.play(0, colorPickFor, chosen);
                colorPickFor = -1;
                if (engine.hands.get(0).size() > 0 && selected >= engine.hands.get(0).size()) {
                    selected = engine.hands.get(0).size() - 1;
                }
                afterHumanPlay(r);
                return true;
            }

            if (engine.current != 0 || engine.gameOver) return true;

            if (endRect.contains(x, y)) {
                if (screen == Screen.COLOR || !engine.canEndTurn()) {
                    playSfx("sfx_click");
                    statusLine = engine.pendingFollowup
                            ? "Play another card or draw one first"
                            : "Play a card or draw one first";
                    invalidate();
                    return true;
                }
                playSfx("sfx_click");
                engine.passTo(engine.nextSeat(0));
                statusLine = "Opponent thinking…";
                scheduleComputers();
                invalidate();
                return true;
            }
            if (drawRect.contains(x, y)) {
                if (!engine.canDraw()) {
                    playSfx("sfx_click");
                    statusLine = "Only one card from the deck each turn";
                    invalidate();
                    return true;
                }
                engine.drawOne(0);
                playSfx("sfx_draw");
                noteCounts();
                selected = engine.hands.get(0).size() - 1;
                statusLine = "Play it or End Turn";
                invalidate();
                return true;
            }
            if (discardRect.contains(x, y)) {
                playSelectedFromDiscard(engine.hands.get(0).size() != 2);
                return true;
            }
            return true;
        }

        void playSelectedFromDiscard(boolean calledTouchOne) {
            List<Card> hand = engine.hands.get(0);
            if (selected < 0 || selected >= hand.size()) return;
            Card card = hand.get(selected);
            if (!engine.legal(card, null)) {
                playSfx("sfx_click");
                return;
            }
            boolean mustCall = hand.size() == 2;
            pendingCallPenalty = mustCall && !calledTouchOne;
            if (mustCall && calledTouchOne) {
                titleFlashUntil = System.currentTimeMillis() + 1500;
                playSfx("sfx_touchone");
            }
            if (card.isWild()) {
                colorPickFor = selected;
                screen = Screen.COLOR;
                wheelSpinStart = System.currentTimeMillis();
                playSfx("sfx_wheel");
                invalidate();
                return;
            }
            String r = engine.play(0, selected, card.color);
            if (selected >= engine.hands.get(0).size()) {
                selected = Math.max(0, engine.hands.get(0).size() - 1);
            }
            afterHumanPlay(r);
        }
    }
}
