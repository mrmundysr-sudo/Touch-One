package com.touchone.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class Engine {
    public final List<Card> drawPile = new ArrayList<>();
    public final List<Card> discard = new ArrayList<>();
    public final List<List<Card>> hands = new ArrayList<>();
    public int seats;
    public int current; // 0 = human
    public int dir = 1;
    public Card.Color liveColor;
    public boolean waitingColor;
    public boolean pendingFollowup; // two-player after pull, human must play or draw
    public int lastDrawTarget = -1;
    public boolean gameOver;
    public boolean playerWon;
    public boolean drewThisTurn;
    public boolean playedThisTurn;
    public Card.Color announcedColor;
    public String announcedKind;
    public final Random rng = new Random();

    public void start(int opponentCount) {
        seats = opponentCount + 1;
        current = 0;
        dir = 1;
        waitingColor = false;
        pendingFollowup = false;
        gameOver = false;
        playerWon = false;
        drewThisTurn = false;
        playedThisTurn = false;
        announcedColor = null;
        announcedKind = null;
        drawPile.clear();
        discard.clear();
        hands.clear();
        buildDeck();
        Collections.shuffle(drawPile, rng);
        for (int i = 0; i < seats; i++) {
            List<Card> h = new ArrayList<>();
            for (int n = 0; n < 7; n++) h.add(take());
            hands.add(h);
        }
        Card first = take();
        while (first.isWild()) {
            drawPile.add(first);
            Collections.shuffle(drawPile, rng);
            first = take();
        }
        discard.add(first);
        liveColor = first.color;
    }

    private void buildDeck() {
        for (Card.Color c : Card.Color.values()) {
            drawPile.add(new Card(c, Card.Kind.NUMBER, 0));
            for (int n = 1; n <= 9; n++) {
                drawPile.add(new Card(c, Card.Kind.NUMBER, n));
                drawPile.add(new Card(c, Card.Kind.NUMBER, n));
            }
            for (int i = 0; i < 2; i++) {
                drawPile.add(new Card(c, Card.Kind.JUMP, -1));
                drawPile.add(new Card(c, Card.Kind.SPIN, -1));
                drawPile.add(new Card(c, Card.Kind.PULL2, -1));
            }
        }
        for (int i = 0; i < 4; i++) {
            drawPile.add(new Card(null, Card.Kind.PULL4, -1));
            drawPile.add(new Card(null, Card.Kind.TOUCH, -1));
        }
    }

    public Card take() {
        if (drawPile.isEmpty()) reshuffle();
        if (drawPile.isEmpty()) return new Card(Card.Color.GREEN, Card.Kind.NUMBER, 0);
        return drawPile.remove(drawPile.size() - 1);
    }

    private void reshuffle() {
        if (discard.size() <= 1) return;
        Card top = discard.remove(discard.size() - 1);
        drawPile.addAll(discard);
        discard.clear();
        discard.add(top);
        Collections.shuffle(drawPile, rng);
    }

    public Card top() {
        return discard.get(discard.size() - 1);
    }

    public boolean legal(Card card, Card.Color chosenIfWild) {
        Card t = top();
        if (card.kind == Card.Kind.PULL4 || card.kind == Card.Kind.TOUCH) return true;
        if (card.kind == Card.Kind.PULL2) {
            return t.kind == Card.Kind.PULL2 || card.color == liveColor;
        }
        if (card.kind == Card.Kind.JUMP) {
            return t.kind == Card.Kind.JUMP || card.color == liveColor;
        }
        if (card.kind == Card.Kind.SPIN) {
            return t.kind == Card.Kind.SPIN || card.color == liveColor;
        }
        return card.color == liveColor || (t.kind == Card.Kind.NUMBER && card.number == t.number);
    }

    public int nextSeat(int from) {
        return (from + dir + seats * 8) % seats;
    }

    public int twoPlayer() {
        return seats == 2 ? 1 : 0;
    }

    /** Play card from seat. For wilds, color must already be chosen except we set waiting. */
    public String play(int seat, int handIndex, Card.Color chosenColor) {
        List<Card> hand = hands.get(seat);
        Card card = hand.get(handIndex);
        if (!legal(card, chosenColor)) return "illegal";
        hand.remove(handIndex);
        discard.add(card);
        playedThisTurn = true;

        if (card.kind == Card.Kind.TOUCH || card.kind == Card.Kind.PULL4) {
            liveColor = chosenColor != null ? chosenColor : randomColor();
            announcedColor = liveColor;
            announcedKind = card.kind == Card.Kind.PULL4 ? "PULL 4" : "TOUCH ONE";
        } else if (card.color != null) {
            liveColor = card.color;
            announcedColor = null;
            announcedKind = null;
        }

        if (hand.isEmpty()) {
            gameOver = true;
            playerWon = seat == 0;
            return "win";
        }

        if (card.kind == Card.Kind.SPIN) {
            dir = -dir;
        }

        int victim = nextSeat(seat);
        if (card.kind == Card.Kind.PULL2) {
            give(victim, 2);
            lastDrawTarget = victim;
            if (seats == 2) {
                pendingFollowup = true;
                current = seat;
                drewThisTurn = false;
                return "pull2";
            }
            passTo(nextSeat(victim));
            return "pull2";
        }
        if (card.kind == Card.Kind.PULL4) {
            give(victim, 4);
            lastDrawTarget = victim;
            if (seats == 2) {
                pendingFollowup = true;
                current = seat;
                drewThisTurn = false;
                return "pull4";
            }
            passTo(nextSeat(victim));
            return "pull4";
        }
        if (card.kind == Card.Kind.JUMP) {
            passTo(nextSeat(nextSeat(seat)));
            return "jump";
        }
        if (card.kind == Card.Kind.SPIN && seats == 2) {
            current = seat;
            pendingFollowup = false;
            drewThisTurn = false;
            playedThisTurn = false;
            return "spin";
        }
        passTo(nextSeat(seat));
        pendingFollowup = false;
        return "ok";
    }

    public void passTo(int seat) {
        current = seat;
        pendingFollowup = false;
        drewThisTurn = false;
        playedThisTurn = false;
    }

    public void give(int seat, int n) {
        for (int i = 0; i < n; i++) hands.get(seat).add(take());
    }

    public Card drawOne(int seat) {
        Card c = take();
        hands.get(seat).add(c);
        if (seat == current) drewThisTurn = true;
        return c;
    }

    public boolean canDraw() {
        return current == 0 && !gameOver && !drewThisTurn;
    }

    public boolean canEndTurn() {
        if (current != 0 || gameOver) return false;
        if (pendingFollowup) return drewThisTurn;
        return drewThisTurn || playedThisTurn;
    }

    public Card.Color randomColor() {
        Card.Color[] cs = Card.Color.values();
        return cs[rng.nextInt(cs.length)];
    }

    public int computerPick(int seat) {
        List<Card> hand = hands.get(seat);
        int best = -1;
        int scoreBest = -1;
        for (int i = 0; i < hand.size(); i++) {
            if (!legal(hand.get(i), null)) continue;
            int s = 1;
            KindBoost: {
                Card c = hand.get(i);
                if (c.kind == Card.Kind.PULL4) s = 6;
                else if (c.kind == Card.Kind.TOUCH) s = 5;
                else if (c.kind == Card.Kind.PULL2) s = 4;
                else if (c.kind == Card.Kind.JUMP || c.kind == Card.Kind.SPIN) s = 3;
                else s = 2;
            }
            if (s > scoreBest) { scoreBest = s; best = i; }
        }
        return best;
    }
}
