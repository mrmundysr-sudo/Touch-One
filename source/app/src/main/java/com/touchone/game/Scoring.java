package com.touchone.game;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Touch One scoring (pure logic, no Android classes). Mirrors www/js/scoring.js on iOS.
 * Quick Play never uses this. A Scoring Game does: everyone starts at 0, the round winner collects the value of every
 * card left in the other hands, and the match ends when a player reaches or passes the target.
 */
public final class Scoring {
    public static final String DEFAULT_TARGET = "500";

    private Scoring() { }

    /** 0-9 face value; JUMP, SPIN, PULL2 = 20; TOUCH (wild) and PULL4 (wild draw four) = 50. */
    public static int cardPoints(Card c) {
        switch (c.kind) {
            case NUMBER: return c.number;
            case JUMP:
            case SPIN:
            case PULL2: return 20;
            case TOUCH:
            case PULL4: return 50;
            default: return 0;
        }
    }

    public static int handPoints(List<Card> hand) {
        int n = 0;
        for (Card c : hand) n += cardPoints(c);
        return n;
    }

    /**
     * Canonical positive whole-number string (leading zeros dropped), or null when the text is not one.
     * There is deliberately no upper bound, so the target stays a digit string and is compared as a BigInteger.
     */
    public static String normalizeTarget(String input) {
        if (input == null || input.isEmpty()) return null;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch < '0' || ch > '9') return null;
        }
        int i = 0;
        while (i < input.length() && input.charAt(i) == '0') i++;
        return i == input.length() ? null : input.substring(i);
    }

    public static boolean targetReached(long total, String target) {
        String t = normalizeTarget(target);
        if (t == null) return false;
        return BigInteger.valueOf(total).compareTo(new BigInteger(t)) >= 0;
    }

    public static final class SeatResult {
        public final int seat;
        public final int cards;  // cards still held when the round ended
        public final int value;  // their total point value
        SeatResult(int seat, int cards, int value) { this.seat = seat; this.cards = cards; this.value = value; }
    }

    public static final class RoundResult {
        public final int round;
        public final int winner;
        public final int points;
        public final List<SeatResult> seats;
        public final long[] totals;
        public final boolean matchOver;
        public final int matchWinner; // -1 until someone reaches the target
        RoundResult(int round, int winner, int points, List<SeatResult> seats, long[] totals, boolean matchOver, int matchWinner) {
            this.round = round; this.winner = winner; this.points = points; this.seats = seats;
            this.totals = totals; this.matchOver = matchOver; this.matchWinner = matchWinner;
        }
    }

    public static final class Match {
        public final int seats;
        public final String target;
        public final long[] totals;
        public final List<RoundResult> rounds = new ArrayList<>();
        public boolean over;
        public int winner = -1;

        public Match(int seats, String target) {
            String t = normalizeTarget(target);
            if (t == null) throw new IllegalArgumentException("target must be a positive whole number");
            if (seats < 2) throw new IllegalArgumentException("need at least two players");
            this.seats = seats;
            this.target = t;
            this.totals = new long[seats];
        }

        /** The round about to be played (1-based). */
        public int round() { return rounds.size() + 1; }

        /** Scores a finished round; the winner's hand must be empty. Updates the cumulative totals. */
        public RoundResult scoreRound(int winnerSeat, List<List<Card>> hands) {
            if (over) throw new IllegalStateException("match is already over");
            if (hands.size() != seats) throw new IllegalArgumentException("hand count does not match player count");
            if (winnerSeat < 0 || winnerSeat >= seats || !hands.get(winnerSeat).isEmpty()) {
                throw new IllegalArgumentException("round winner must have an empty hand");
            }
            List<SeatResult> out = new ArrayList<>();
            int points = 0;
            for (int s = 0; s < seats; s++) {
                int v = handPoints(hands.get(s));
                out.add(new SeatResult(s, hands.get(s).size(), v));
                points += v;
            }
            totals[winnerSeat] += points;
            boolean matchOver = targetReached(totals[winnerSeat], target);
            if (matchOver) { over = true; winner = winnerSeat; }
            RoundResult r = new RoundResult(round(), winnerSeat, points, out, totals.clone(), matchOver, winner);
            rounds.add(r);
            return r;
        }
    }
}
