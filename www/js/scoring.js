// Touch One scoring: pure logic, no DOM. Mirrors Scoring.java on Android.
//
// Quick Play never touches this file: one round, the round winner ends the game.
// Scoring Game uses it: every player starts at 0, a round winner collects the value of every card left in the
// other players' hands, and the match ends when someone reaches or passes the target.

export const DEFAULT_TARGET = '500';

/** Game modes offered on the Setup screen. Add an entry here to add a mode (the screen lays them out from this list). */
export const MODES = [
  { id: 'quick', title: 'Quick Play', blurb: 'One round. First to empty their hand wins.' },
  { id: 'scoring', title: 'Scoring Game', blurb: 'Play rounds and collect points. First to the target wins.' },
];
export const MODE_QUICK = 'quick';
export const MODE_SCORING = 'scoring';

/** 0-9 face value, JUMP / SPIN / PULL2 = 20, TOUCH (wild) / PULL4 (wild draw four) = 50. */
export function cardPoints(card) {
  switch (card.kind) {
    case 'NUMBER': return card.number;
    case 'JUMP': case 'SPIN': case 'PULL2': return 20;
    case 'TOUCH': case 'PULL4': return 50;
    default: return 0;
  }
}

export function handPoints(hand) {
  let n = 0;
  for (const c of hand) n += cardPoints(c);
  return n;
}

/**
 * Turns what the player typed (or a number) into a canonical positive whole-number string, or null when it is not one.
 * There is deliberately no upper bound, so the target stays a digit string and is compared as a BigInt.
 */
export function normalizeTarget(input) {
  if (typeof input === 'number') {
    if (!Number.isSafeInteger(input)) return null;
    input = String(input);
  }
  if (typeof input !== 'string' || !/^[0-9]+$/.test(input)) return null;
  const s = input.replace(/^0+/, '');
  return s === '' ? null : s;
}

export function targetReached(total, target) {
  const t = normalizeTarget(target);
  if (t === null) return false;
  return BigInt(total) >= BigInt(t);
}

export class Match {
  /** seats: number of players (seat 0 is the human). target: positive whole number, string or number. */
  constructor(seats, target = DEFAULT_TARGET) {
    const t = normalizeTarget(target);
    if (t === null) throw new Error('target must be a positive whole number');
    if (!(seats >= 2)) throw new Error('need at least two players');
    this.seats = seats;
    this.target = t;
    this.totals = new Array(seats).fill(0);
    this.rounds = [];
    this.over = false;
    this.winner = -1; // seat that reached the target
  }

  /** The round about to be played (1-based). */
  get round() { return this.rounds.length + 1; }

  /**
   * Scores a finished round. hands[seat] are the cards still held; the winner's hand must be empty.
   * Returns the round breakdown and updates the cumulative totals.
   */
  scoreRound(winnerSeat, hands) {
    if (this.over) throw new Error('match is already over');
    if (hands.length !== this.seats) throw new Error('hand count does not match player count');
    if (!(winnerSeat >= 0 && winnerSeat < this.seats) || hands[winnerSeat].length !== 0) {
      throw new Error('round winner must have an empty hand');
    }
    const seatsOut = hands.map((h, seat) => ({ seat, cards: h.length, value: handPoints(h) }));
    const points = seatsOut.reduce((a, s) => a + s.value, 0); // the winner's own hand is empty, so it adds nothing
    this.totals[winnerSeat] += points;
    const matchOver = targetReached(this.totals[winnerSeat], this.target);
    if (matchOver) { this.over = true; this.winner = winnerSeat; }
    const result = {
      round: this.round, winner: winnerSeat, points,
      seats: seatsOut, totals: this.totals.slice(), matchOver, matchWinner: this.winner,
    };
    this.rounds.push(result);
    return result;
  }
}
