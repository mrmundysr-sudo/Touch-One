import com.touchone.game.Card;
import com.touchone.game.Engine;
import com.touchone.game.ModeState;
import com.touchone.game.Scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android scoring checks (mirror tests/scoring.test.mjs). No test framework needed:
 *   javac -d /tmp/t1 source/app/src/main/java/com/touchone/game/{Card,Engine,Scoring,ModeState}.java tests/ScoringTest.java
 *   java -cp /tmp/t1 ScoringTest
 */
public class ScoringTest {
    static int checks = 0;

    static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError(what + ": want " + want + " got " + got);
    }
    static void yes(boolean b, String what) { eq(true, b, what); }
    static void no(boolean b, String what) { eq(false, b, what); }

    static Card num(Card.Color c, int n) { return new Card(c, Card.Kind.NUMBER, n); }
    static Card act(Card.Color c, Card.Kind k) { return new Card(c, k, -1); }
    static Card wild(Card.Kind k) { return new Card(null, k, -1); }
    static List<Card> hand(Card... c) { return new ArrayList<>(Arrays.asList(c)); }
    static List<List<Card>> hands(List<Card>... h) { return new ArrayList<>(Arrays.asList(h)); }

    static void threw(Runnable r, String what) {
        checks++;
        try { r.run(); } catch (RuntimeException e) { return; }
        throw new AssertionError(what + ": expected an exception");
    }

    @SafeVarargs
    static List<List<Card>> H(List<Card>... h) { return hands(h); }

    public static void main(String[] args) {
        Card.Color G = Card.Color.GREEN, P = Card.Color.PINK, U = Card.Color.PURPLE;

        // card values
        for (int n = 0; n <= 9; n++) eq(n, Scoring.cardPoints(num(G, n)), "number " + n);
        for (Card.Kind k : new Card.Kind[]{Card.Kind.JUMP, Card.Kind.SPIN, Card.Kind.PULL2}) eq(20, Scoring.cardPoints(act(P, k)), "action " + k);
        eq(50, Scoring.cardPoints(wild(Card.Kind.TOUCH)), "Touch One wild");
        eq(50, Scoring.cardPoints(wild(Card.Kind.PULL4)), "Wild Draw Four");

        // whole deck: 4 colours x (2*(1..9)=90 + 6 actions*20=120) + 8 wilds * 50
        Engine e = new Engine();
        e.start(1);
        int deck = Scoring.handPoints(e.drawPile) + Scoring.handPoints(e.discard);
        for (List<Card> h : e.hands) deck += Scoring.handPoints(h);
        eq(840 + 400, deck, "deck total");

        // defaults
        Scoring.Match m = new Scoring.Match(3, "500");
        eq("500", m.target, "default target");
        eq("[0, 0, 0]", Arrays.toString(m.totals), "start at 0");
        eq(1, m.round(), "round 1");

        // round scoring
        Scoring.RoundResult r = m.scoreRound(0, H(hand(),
                hand(num(G, 7), act(P, Card.Kind.JUMP), wild(Card.Kind.TOUCH)),                       // 77
                hand(wild(Card.Kind.PULL4), act(G, Card.Kind.PULL2), act(U, Card.Kind.SPIN), num(P, 0)))); // 90
        eq(167, r.points, "round points");
        eq(77, r.seats.get(1).value, "seat1 value");
        eq(90, r.seats.get(2).value, "seat2 value");
        eq(4, r.seats.get(2).cards, "seat2 cards");
        eq("[167, 0, 0]", Arrays.toString(m.totals), "totals after round 1");
        no(r.matchOver, "not over");
        eq(2, m.round(), "round 2");

        // opponent wins a round
        m = new Scoring.Match(2, "500");
        m.scoreRound(1, H(hand(num(G, 9)), hand()));
        eq("[0, 9]", Arrays.toString(m.totals), "opponent total");

        // totals accumulate, target preserved
        m = new Scoring.Match(2, "300");
        m.scoreRound(0, H(hand(), hand(wild(Card.Kind.PULL4), wild(Card.Kind.TOUCH))));
        m.scoreRound(1, H(hand(act(G, Card.Kind.SPIN)), hand()));
        m.scoreRound(0, H(hand(), hand(num(P, 5), act(G, Card.Kind.PULL2))));
        eq("[125, 20]", Arrays.toString(m.totals), "accumulated");
        eq("300", m.target, "target preserved");
        no(m.over, "still playing");
        eq(3, m.rounds.size(), "three rounds");

        // reaching exactly / exceeding / one short
        m = new Scoring.Match(2, "100");
        r = m.scoreRound(0, H(hand(), hand(wild(Card.Kind.PULL4), wild(Card.Kind.TOUCH))));
        yes(r.matchOver && m.over && m.winner == 0 && r.matchWinner == 0, "exactly target ends match");
        final Scoring.Match done = m;
        threw(() -> done.scoreRound(0, H(hand(), hand(num(G, 1)))), "no rounds after match over");
        m = new Scoring.Match(2, "90");
        yes(m.scoreRound(1, H(hand(wild(Card.Kind.PULL4), wild(Card.Kind.TOUCH)), hand())).matchOver, "exceeding ends match");
        eq(1, m.winner, "opponent wins match");
        no(new Scoring.Match(2, "101").scoreRound(0, H(hand(), hand(wild(Card.Kind.PULL4), wild(Card.Kind.TOUCH)))).matchOver, "one short");

        // a 0-point round is fine
        r = new Scoring.Match(2, "1").scoreRound(0, H(hand(), hand(num(G, 0), num(P, 0))));
        eq(0, r.points, "0-point round");
        no(r.matchOver, "0 points cannot end the match");

        // target entry
        eq("500", Scoring.normalizeTarget("500"), "500");
        eq("1", Scoring.normalizeTarget("1"), "1");
        eq("42", Scoring.normalizeTarget("0042"), "leading zeros");
        eq("99999999999999999999999999", Scoring.normalizeTarget("99999999999999999999999999"), "no maximum");
        for (String bad : new String[]{"", "0", "000", "-5", "1.5", "1e3", " 5", "abc", "5 0", null}) {
            eq(null, Scoring.normalizeTarget(bad), "rejects " + bad);
        }
        threw(() -> new Scoring.Match(2, "0"), "zero target");
        threw(() -> new Scoring.Match(2, ""), "empty target");
        m = new Scoring.Match(2, "99999999999999999999999999");
        no(m.scoreRound(0, H(hand(), hand(wild(Card.Kind.PULL4)))).matchOver, "huge target never reached");
        no(Scoring.targetReached(50, "51"), "50 < 51");
        yes(Scoring.targetReached(50, "50"), "50 >= 50");

        // validation
        final Scoring.Match v = new Scoring.Match(2, "500");
        threw(() -> v.scoreRound(0, H(hand(num(G, 1)), hand(num(G, 2)))), "winner must be empty");
        threw(() -> v.scoreRound(0, H(hand())), "hand count");

        // ModeState: defaults, Quick Play untouched, keypad
        ModeState s = new ModeState();
        eq(ModeState.Mode.QUICK, s.mode, "starts in Quick Play");
        eq("500", s.targetText, "target prefilled");
        s.startMatch(3);
        eq(null, s.match, "Quick Play has no match");
        s.selectMode(ModeState.Mode.SCORING);
        s.startMatch(3);
        yes(s.match != null && s.match.target.equals("500"), "scoring match with default target");
        eq(2, ModeState.MODES.length, "two modes");

        s = new ModeState();
        s.selectMode(ModeState.Mode.SCORING);
        s.openEdit();
        yes(s.fresh && s.draft.equals("500"), "opens prefilled and selected");
        s.padKey("7");                      // first digit replaces
        eq("7", s.draft, "first key replaces");
        s.padKey("5"); s.padKey("0");
        eq("750", s.draft, "typing");
        eq(ModeState.KeyResult.COMMITTED, s.padKey("done"), "done commits");
        eq("750", s.targetText, "target 750");
        s.openEdit(); s.padKey("back");
        eq("75", s.draft, "backspace edits the existing value");
        s.padKey("back"); s.padKey("back"); s.padKey("back");
        eq("", s.draft, "backspace stops at empty");
        eq(ModeState.KeyResult.REJECTED, s.padKey("done"), "empty target rejected");
        eq("750", s.targetText, "target unchanged after rejection");
        s.padKey("0"); s.padKey("0");
        eq("0", s.draft, "no run of leading zeros");
        eq(ModeState.KeyResult.REJECTED, s.padKey("done"), "zero rejected");
        s.padKey("1"); s.padKey("2");
        eq("12", s.draft, "digit after zero replaces it");
        s.selectMode(ModeState.Mode.QUICK);   // leaving the field commits a valid value
        eq("12", s.targetText, "valid draft kept on mode change");
        s.selectMode(ModeState.Mode.SCORING);
        s.openEdit(); s.padKey("back"); s.padKey("back"); s.padKey("back");
        s.commitDraftOrRevert();
        eq("12", s.targetText, "invalid draft reverts");
        no(s.editing(), "edit closed");

        // new scoring game resets totals and defaults the target; custom target survives across rounds
        s = new ModeState();
        s.selectMode(ModeState.Mode.SCORING);
        s.openEdit(); s.padKey("1"); s.padKey("0"); s.padKey("0"); s.padKey("done");
        s.startMatch(2);
        eq("100", s.match.target, "custom target used");
        s.scoreRound(0, H(hand(), hand(num(G, 5))));
        eq("100", s.match.target, "target preserved across rounds");
        s.scoreRound(0, H(hand(), hand(wild(Card.Kind.PULL4), wild(Card.Kind.TOUCH))));
        yes(s.result.matchOver, "match over at 100+");
        s.abandonMatch();
        eq("500", s.targetText, "target back to 500 for the next game");
        s.startMatch(2);
        eq("[0, 0]", Arrays.toString(s.match.totals), "new game starts at 0");
        eq(ModeState.Mode.SCORING, s.mode, "mode kept");

        // a real engine game, scored from the actual leftover hands
        Engine g = new Engine();
        g.start(2);
        int guard = 0;
        while (!g.gameOver && guard++ < 5000) {
            int seat = g.current, pick = g.computerPick(seat);
            if (pick < 0) { g.drawOne(seat); g.passTo(g.nextSeat(seat)); continue; }
            Card c = g.hands.get(seat).get(pick);
            g.play(seat, pick, c.isWild() ? g.randomColor() : c.color);
        }
        yes(g.gameOver, "engine game finished");
        Scoring.Match gm = new Scoring.Match(g.seats, "500");
        r = gm.scoreRound(g.winnerSeat, g.hands);
        int expected = 0;
        for (List<Card> h : g.hands) expected += Scoring.handPoints(h);
        eq(expected, r.points, "engine round points");
        eq(expected, gm.totals[g.winnerSeat], "winner total");
        eq(g.playerWon, g.winnerSeat == 0, "winnerSeat agrees with playerWon");

        System.out.println("ScoringTest OK (" + checks + " checks)");
    }
}
