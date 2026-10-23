package com.touchone.game;

import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;

/** Typeface and palette shared by every screen so the whole app speaks in one voice. */
final class Theme {
    private Theme() {}

    /** Outfit Bold: rounded geometric forms that echo the logo. Falls back to system bold. */
    static Typeface font = Typeface.DEFAULT_BOLD;

    static final int CREAM = Color.rgb(255, 244, 220);
    static final int GOLD = Color.rgb(255, 214, 64);
    static final int GOLD_DEEP = Color.rgb(255, 160, 24);
    static final int WOOD_INK = Color.rgb(58, 30, 12);
    static final int ALERT = Color.rgb(226, 38, 38);

    static void init(AssetManager assets) {
        try {
            font = Typeface.createFromAsset(assets, "fonts/Outfit-Bold.ttf");
        } catch (Exception e) {
            font = Typeface.DEFAULT_BOLD;
        }
    }

    static int cardColor(Card.Color c) {
        if (c == null) return Color.rgb(30, 41, 59);
        switch (c) {
            case GREEN: return Color.rgb(34, 197, 74);
            case PURPLE: return Color.rgb(147, 51, 234);
            case PINK: return Color.rgb(236, 72, 153);
            case ORANGE: return Color.rgb(249, 115, 22);
        }
        return Color.GRAY;
    }

    static String colorName(Card.Color c) {
        if (c == null) return "";
        switch (c) {
            case GREEN: return "Green";
            case PURPLE: return "Purple";
            case PINK: return "Pink";
            case ORANGE: return "Orange";
        }
        return "";
    }

    static int shade(int color, float mul) {
        int r = Math.min(255, Math.max(0, (int) (Color.red(color) * mul)));
        int g = Math.min(255, Math.max(0, (int) (Color.green(color) * mul)));
        int b = Math.min(255, Math.max(0, (int) (Color.blue(color) * mul)));
        return Color.rgb(r, g, b);
    }

    static int withAlpha(int color, float a) {
        int al = Math.min(255, Math.max(0, Math.round(Color.alpha(color) * a)));
        return Color.argb(al, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int mix(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
