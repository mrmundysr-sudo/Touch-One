import test from 'node:test';
import assert from 'node:assert/strict';
import { Engine, Card, mulberry32 } from '../www/js/engine.js';

const mk = (seats, hands, top, live, extra = {}) => {
  const e = new Engine(mulberry32(1));
  e.seats = seats;
  e.hands = hands;
  e.discard = [top];
  e.drawPile = Array.from({ length: 30 }, () => new Card('GREEN', 'NUMBER', 1));
  e.liveColor = live ?? top.color;
  e.current = 0;
  Object.assign(e, extra);
  return e;
};
const num = (c, n) => new Card(c, 'NUMBER', n);

test('deck has 108 cards and opening hands are 7', () => {
  const e = new Engine(mulberry32(7));
  e.start(3);
  const total = e.drawPile.length + e.discard.length + e.hands.reduce((a, h) => a + h.length, 0);
  assert.equal(total, 108);
  assert.ok(e.hands.every((h) => h.length === 7));
  assert.equal(e.discard.length, 1);
  assert.ok(!e.top().isWild());
});

test('legal: colour, number, or same action; wilds always', () => {
  const e = mk(2, [[], []], num('GREEN', 5));
  assert.ok(e.legal(num('GREEN', 2)));
  assert.ok(e.legal(num('PINK', 5)));
  assert.ok(!e.legal(num('PINK', 4)));
  assert.ok(e.legal(new Card(null, 'TOUCH', -1)));
  assert.ok(!e.legal(new Card('PINK', 'JUMP', -1)));
  e.discard = [new Card('PINK', 'JUMP', -1)]; e.liveColor = 'PINK';
  assert.ok(e.legal(new Card('GREEN', 'JUMP', -1)));
});

test('illegal play leaves state untouched', () => {
  const e = mk(2, [[num('PINK', 4)], [num('GREEN', 1)]], num('GREEN', 5));
  assert.equal(e.play(0, 0, null), 'illegal');
  assert.equal(e.hands[0].length, 1);
});

test('emptying a hand wins', () => {
  const e = mk(2, [[num('GREEN', 2)], [num('GREEN', 1), num('GREEN', 3)]], num('GREEN', 5));
  assert.equal(e.play(0, 0, null), 'win');
  assert.ok(e.gameOver && e.playerWon);
  const e2 = mk(2, [[num('GREEN', 2), num('GREEN', 9)], [num('GREEN', 1)]], num('GREEN', 5), undefined, { current: 1 });
  assert.equal(e2.play(1, 0, null), 'win');
  assert.ok(e2.gameOver && !e2.playerWon);
});

test('number play passes the turn', () => {
  const e = mk(3, [[num('GREEN', 2), num('PINK', 2)], [num('GREEN', 1)], [num('GREEN', 3)]], num('GREEN', 5));
  assert.equal(e.play(0, 0, null), 'ok');
  assert.equal(e.current, 1);
});

test('jump skips next seat (3+ players)', () => {
  const e = mk(3, [[new Card('GREEN', 'JUMP', -1), num('PINK', 2)], [num('GREEN', 1)], [num('GREEN', 3)]], num('GREEN', 5));
  assert.equal(e.play(0, 0, null), 'jump');
  assert.equal(e.current, 2);
});

test('spin reverses; with two players the spinner plays again', () => {
  const e3 = mk(3, [[new Card('GREEN', 'SPIN', -1), num('PINK', 2)], [num('GREEN', 1)], [num('GREEN', 3)]], num('GREEN', 5));
  e3.play(0, 0, null);
  assert.equal(e3.dir, -1);
  assert.equal(e3.current, 2);
  const e2 = mk(2, [[new Card('GREEN', 'SPIN', -1), num('PINK', 2)], [num('GREEN', 1)]], num('GREEN', 5));
  assert.equal(e2.play(0, 0, null), 'spin');
  assert.equal(e2.current, 0);
  assert.ok(!e2.playedThisTurn);
});

test('+2 gives the victim two and skips them (3+ players)', () => {
  const e = mk(3, [[new Card('GREEN', 'PULL2', -1), num('PINK', 2)], [num('GREEN', 1)], [num('GREEN', 3)]], num('GREEN', 5));
  assert.equal(e.play(0, 0, null), 'pull2');
  assert.equal(e.hands[1].length, 3);
  assert.equal(e.lastDrawTarget, 1);
  assert.equal(e.current, 2);
});

test('two-player +4: opponent draws four, pusher keeps the turn as a follow-up', () => {
  const e = mk(2, [[new Card(null, 'PULL4', -1), num('PINK', 2)], [num('GREEN', 1)]], num('GREEN', 5));
  assert.equal(e.play(0, 0, 'PINK'), 'pull4');
  assert.equal(e.hands[1].length, 5);
  assert.equal(e.liveColor, 'PINK');
  assert.ok(e.pendingFollowup);
  assert.equal(e.current, 0);
  assert.ok(!e.canEndTurn());
  e.drawOne(0);
  assert.ok(e.canEndTurn());
});

test('wild takes the chosen colour and announces it', () => {
  const e = mk(2, [[new Card(null, 'TOUCH', -1), num('PINK', 2)], [num('GREEN', 1)]], num('GREEN', 5));
  e.play(0, 0, 'ORANGE');
  assert.equal(e.liveColor, 'ORANGE');
  assert.equal(e.announcedKind, 'TOUCH ONE');
});

test('draw once per turn; end turn needs a play or a draw', () => {
  const e = mk(2, [[num('PINK', 2)], [num('GREEN', 1)]], num('GREEN', 5));
  assert.ok(e.canDraw() && !e.canEndTurn());
  e.drawOne(0);
  assert.ok(!e.canDraw() && e.canEndTurn());
});

test('draw pile reshuffles from the discard, keeping the top card', () => {
  const e = mk(2, [[num('PINK', 2)], [num('GREEN', 1)]], num('GREEN', 5));
  e.discard = [num('PINK', 1), num('PINK', 2), num('PINK', 3), num('GREEN', 5)];
  e.drawPile = [];
  const c = e.take();
  assert.ok(c);
  assert.equal(e.discard.length, 1);
  assert.equal(e.top().number, 5);
  assert.equal(e.drawPile.length, 2);
});

test('computerPick prefers action cards and returns -1 with no legal card', () => {
  const e = mk(2, [[], [num('GREEN', 2), new Card('GREEN', 'JUMP', -1), new Card(null, 'PULL4', -1)]], num('GREEN', 5), undefined, { current: 1 });
  assert.equal(e.computerPick(1), 2);
  const e2 = mk(2, [[], [num('PINK', 2)]], num('GREEN', 5), undefined, { current: 1 });
  assert.equal(e2.computerPick(1), -1);
});

test('chooseColor picks the colour held most', () => {
  const e = mk(2, [[], [num('PINK', 2), num('PINK', 3), num('GREEN', 1)]], num('GREEN', 5), undefined, { current: 1 });
  assert.equal(e.chooseColor(1), 'PINK');
});

test('a full AI-vs-AI game terminates', () => {
  for (let seed = 1; seed <= 20; seed++) {
    const e = new Engine(mulberry32(seed));
    e.start(1 + (seed % 3));
    let guard = 0;
    while (!e.gameOver && guard++ < 5000) {
      const s = e.current;
      const pick = e.computerPick(s);
      if (pick < 0) { e.drawOne(s); e.passTo(e.nextSeat(s)); continue; }
      const c = e.hands[s][pick];
      e.play(s, pick, c.isWild() ? e.chooseColor(s) : c.color);
      if (e.pendingFollowup && !e.gameOver) e.passTo(e.nextSeat(s));
    }
    assert.ok(e.gameOver, 'seed ' + seed + ' did not finish');
  }
});
