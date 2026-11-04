// Touch One rules engine. Pure logic, no DOM: a straight port of the Android Engine.java / Card.java (1.4.0).

export const COLORS = ['GREEN', 'PURPLE', 'PINK', 'ORANGE'];

export class Card {
  constructor(color, kind, number) {
    this.color = color;   // null for wilds until played
    this.kind = kind;     // NUMBER | JUMP | SPIN | PULL2 | PULL4 | TOUCH
    this.number = number; // 0-9 or -1
  }
  isWild() { return this.kind === 'PULL4' || this.kind === 'TOUCH'; }
  isDraw() { return this.kind === 'PULL2' || this.kind === 'PULL4'; }
  label() {
    switch (this.kind) {
      case 'NUMBER': return String(this.number);
      case 'JUMP': return 'JUMP';
      case 'SPIN': return 'SPIN';
      case 'PULL2': return '+2';
      case 'PULL4': return '+4';
      default: return 'T1';
    }
  }
}

export function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export class Engine {
  constructor(rng = Math.random) {
    this.rng = rng;
    this.drawPile = [];
    this.discard = [];
    this.hands = [];
    this.seats = 0;
    this.current = 0;       // 0 = human
    this.dir = 1;
    this.liveColor = null;
    this.waitingColor = false;
    this.pendingFollowup = false; // two-player after a pull: the puller plays again
    this.lastDrawTarget = -1;
    this.gameOver = false;
    this.playerWon = false;
    this.drewThisTurn = false;
    this.playedThisTurn = false;
    this.announcedColor = null;
    this.announcedKind = null;
  }

  randInt(n) { return Math.floor(this.rng() * n); }

  shuffle(list) {
    for (let i = list.length - 1; i > 0; i--) {
      const j = this.randInt(i + 1);
      [list[i], list[j]] = [list[j], list[i]];
    }
  }

  start(opponentCount) {
    this.seats = opponentCount + 1;
    this.current = 0;
    this.dir = 1;
    this.waitingColor = false;
    this.pendingFollowup = false;
    this.gameOver = false;
    this.playerWon = false;
    this.drewThisTurn = false;
    this.playedThisTurn = false;
    this.announcedColor = null;
    this.announcedKind = null;
    this.drawPile = [];
    this.discard = [];
    this.hands = [];
    this.buildDeck();
    this.shuffle(this.drawPile);
    for (let i = 0; i < this.seats; i++) {
      const h = [];
      for (let n = 0; n < 7; n++) h.push(this.take());
      this.hands.push(h);
    }
    let first = this.take();
    while (first.isWild()) {
      this.drawPile.push(first);
      this.shuffle(this.drawPile);
      first = this.take();
    }
    this.discard.push(first);
    this.liveColor = first.color;
  }

  buildDeck() {
    for (const c of COLORS) {
      this.drawPile.push(new Card(c, 'NUMBER', 0));
      for (let n = 1; n <= 9; n++) {
        this.drawPile.push(new Card(c, 'NUMBER', n));
        this.drawPile.push(new Card(c, 'NUMBER', n));
      }
      for (let i = 0; i < 2; i++) {
        this.drawPile.push(new Card(c, 'JUMP', -1));
        this.drawPile.push(new Card(c, 'SPIN', -1));
        this.drawPile.push(new Card(c, 'PULL2', -1));
      }
    }
    for (let i = 0; i < 4; i++) {
      this.drawPile.push(new Card(null, 'PULL4', -1));
      this.drawPile.push(new Card(null, 'TOUCH', -1));
    }
  }

  take() {
    if (this.drawPile.length === 0) this.reshuffle();
    if (this.drawPile.length === 0) return new Card('GREEN', 'NUMBER', 0);
    return this.drawPile.pop();
  }

  reshuffle() {
    if (this.discard.length <= 1) return;
    const top = this.discard.pop();
    this.drawPile.push(...this.discard);
    this.discard = [top];
    this.shuffle(this.drawPile);
  }

  top() { return this.discard[this.discard.length - 1]; }

  legal(card) {
    const t = this.top();
    if (card.kind === 'PULL4' || card.kind === 'TOUCH') return true;
    if (card.kind === 'PULL2') return t.kind === 'PULL2' || card.color === this.liveColor;
    if (card.kind === 'JUMP') return t.kind === 'JUMP' || card.color === this.liveColor;
    if (card.kind === 'SPIN') return t.kind === 'SPIN' || card.color === this.liveColor;
    return card.color === this.liveColor || (t.kind === 'NUMBER' && card.number === t.number);
  }

  nextSeat(from) { return (from + this.dir + this.seats * 8) % this.seats; }

  /** Plays hand[handIndex] from seat. Returns 'illegal' | 'win' | 'pull2' | 'pull4' | 'jump' | 'spin' | 'ok'. */
  play(seat, handIndex, chosenColor) {
    const hand = this.hands[seat];
    const card = hand[handIndex];
    if (!card || !this.legal(card)) return 'illegal';
    hand.splice(handIndex, 1);
    this.discard.push(card);
    this.playedThisTurn = true;

    if (card.kind === 'TOUCH' || card.kind === 'PULL4') {
      this.liveColor = chosenColor || this.randomColor();
      this.announcedColor = this.liveColor;
      this.announcedKind = card.kind === 'PULL4' ? 'PULL 4' : 'TOUCH ONE';
    } else if (card.color) {
      this.liveColor = card.color;
      this.announcedColor = null;
      this.announcedKind = null;
    }

    if (hand.length === 0) {
      this.gameOver = true;
      this.playerWon = seat === 0;
      return 'win';
    }

    if (card.kind === 'SPIN') this.dir = -this.dir;

    const victim = this.nextSeat(seat);
    if (card.kind === 'PULL2' || card.kind === 'PULL4') {
      const n = card.kind === 'PULL2' ? 2 : 4;
      this.give(victim, n);
      this.lastDrawTarget = victim;
      if (this.seats === 2) {
        this.pendingFollowup = true;
        this.current = seat;
        this.drewThisTurn = false;
        return card.kind === 'PULL2' ? 'pull2' : 'pull4';
      }
      this.passTo(this.nextSeat(victim));
      return card.kind === 'PULL2' ? 'pull2' : 'pull4';
    }
    if (card.kind === 'JUMP') {
      this.passTo(this.nextSeat(this.nextSeat(seat)));
      return 'jump';
    }
    if (card.kind === 'SPIN' && this.seats === 2) {
      this.current = seat;
      this.pendingFollowup = false;
      this.drewThisTurn = false;
      this.playedThisTurn = false;
      return 'spin';
    }
    this.passTo(this.nextSeat(seat));
    this.pendingFollowup = false;
    return 'ok';
  }

  passTo(seat) {
    this.current = seat;
    this.pendingFollowup = false;
    this.drewThisTurn = false;
    this.playedThisTurn = false;
  }

  give(seat, n) {
    for (let i = 0; i < n; i++) this.hands[seat].push(this.take());
  }

  drawOne(seat) {
    const c = this.take();
    this.hands[seat].push(c);
    if (seat === this.current) this.drewThisTurn = true;
    return c;
  }

  canDraw() { return this.current === 0 && !this.gameOver && !this.drewThisTurn; }

  canEndTurn() {
    if (this.current !== 0 || this.gameOver) return false;
    if (this.pendingFollowup) return this.drewThisTurn;
    return this.drewThisTurn || this.playedThisTurn;
  }

  randomColor() { return COLORS[this.randInt(COLORS.length)]; }

  hasLegal(seat) { return this.hands[seat].some((c) => this.legal(c)); }

  /** Opponents name the colour they hold most of (random on a tie / empty hand). */
  chooseColor(seat) {
    const n = COLORS.map(() => 0);
    for (const c of this.hands[seat]) if (c.color) n[COLORS.indexOf(c.color)]++;
    const top = Math.max(...n);
    if (top === 0) return this.randomColor();
    const tied = [];
    n.forEach((v, i) => { if (v === top) tied.push(i); });
    return COLORS[tied.length === 1 ? tied[0] : tied[this.randInt(tied.length)]];
  }

  /** Index of the card the computer wants to play, or -1 to draw. */
  computerPick(seat) {
    const hand = this.hands[seat];
    let best = -1, scoreBest = -1;
    for (let i = 0; i < hand.length; i++) {
      const c = hand[i];
      if (!this.legal(c)) continue;
      let s;
      if (c.kind === 'PULL4') s = 6;
      else if (c.kind === 'TOUCH') s = 5;
      else if (c.kind === 'PULL2') s = 4;
      else if (c.kind === 'JUMP' || c.kind === 'SPIN') s = 3;
      else s = 2;
      if (s > scoreBest) { scoreBest = s; best = i; }
    }
    return best;
  }
}
