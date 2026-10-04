package com.touchone.game;

public class Card {
    public enum Color { GREEN, PURPLE, PINK, ORANGE }

    public enum Kind { NUMBER, JUMP, SPIN, PULL2, PULL4, TOUCH }

    public final Color color; // null for wilds until played
    public final Kind kind;
    public final int number; // 0-9 or -1

    public Card(Color color, Kind kind, int number) {
        this.color = color;
        this.kind = kind;
        this.number = number;
    }

    public boolean isWild() {
        return kind == Kind.PULL4 || kind == Kind.TOUCH;
    }

    public boolean isDraw() {
        return kind == Kind.PULL2 || kind == Kind.PULL4;
    }

    public String label() {
        if (kind == Kind.NUMBER) return String.valueOf(number);
        if (kind == Kind.JUMP) return "JUMP";
        if (kind == Kind.SPIN) return "SPIN";
        if (kind == Kind.PULL2) return "+2";
        if (kind == Kind.PULL4) return "+4";
        return "T1";
    }
}
