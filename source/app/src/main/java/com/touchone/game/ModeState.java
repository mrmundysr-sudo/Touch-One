package com.touchone.game;

import java.util.List;

/**
 * Setup choices and the running Scoring Game (pure logic, no Android classes). Mirrors the mode state in
 * www/js/screens.js. Quick Play leaves {@link #match} null, so nothing here changes how it plays.
 */
public final class ModeState {
    public enum Mode { QUICK, SCORING }

    public enum KeyResult { NONE, REJECTED, COMMITTED }

    /** One Setup entry in the mode list. Add an entry here to add a mode; the Setup screen lays them out from this. */
    public static final class ModeInfo {
        public final Mode mode; public final String title; public final String blurb;
        ModeInfo(Mode mode, String title, String blurb) { this.mode = mode; this.title = title; this.blurb = blurb; }
    }

    public static final ModeInfo[] MODES = {
        new ModeInfo(Mode.QUICK, "Quick Play", "One round. First to empty their hand wins."),
        new ModeInfo(Mode.SCORING, "Scoring Game", "Play rounds and collect points. First to the target wins."),
    };

    public Mode mode = Mode.QUICK;
    public String targetText = Scoring.DEFAULT_TARGET; // committed points-to-win
    public String draft;                               // keypad text while editing, null otherwise
    public boolean fresh;                              // first keypress replaces the prefilled value
    public Scoring.Match match;                        // running Scoring Game, null in Quick Play
    public Scoring.RoundResult result;                 // last scored round

    public boolean editing() { return draft != null; }

    public void openEdit() {
        if (draft == null) { draft = targetText; fresh = true; }
    }

    /** Keeps a valid typed target. Returns false (draft untouched) when it is not a positive whole number. */
    public boolean commitDraft() {
        if (draft == null) return true;
        String n = Scoring.normalizeTarget(draft);
        if (n == null) return false;
        targetText = n;
        draft = null;
        fresh = false;
        return true;
    }

    public void commitDraftOrRevert() {
        if (!commitDraft()) { draft = null; fresh = false; }
    }

    public void selectMode(Mode m) {
        commitDraftOrRevert();
        mode = m;
    }

    /** Keypad key: "0".."9", "back" or "done". */
    public KeyResult padKey(String k) {
        if (draft == null) return KeyResult.NONE;
        if (k.equals("done")) return commitDraft() ? KeyResult.COMMITTED : KeyResult.REJECTED;
        if (k.equals("back")) {
            if (!draft.isEmpty()) draft = draft.substring(0, draft.length() - 1);
            fresh = false;
            return KeyResult.NONE;
        }
        if (fresh) { draft = ""; fresh = false; }
        if (draft.equals("0")) draft = "";
        draft = draft + k;
        return KeyResult.NONE;
    }

    /** Starts a new game: a fresh Scoring Game match when Scoring is selected, otherwise nothing (Quick Play). */
    public void startMatch(int seats) {
        result = null;
        match = mode == Mode.SCORING ? new Scoring.Match(seats, targetText) : null;
    }

    public Scoring.RoundResult scoreRound(int winnerSeat, List<List<Card>> hands) {
        result = match.scoreRound(winnerSeat, hands);
        return result;
    }

    /** Throws the running match away: the next Scoring Game starts from 0 with the default target. */
    public void abandonMatch() {
        match = null;
        result = null;
        targetText = Scoring.DEFAULT_TARGET;
        draft = null;
        fresh = false;
    }
}
