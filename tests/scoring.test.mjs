import test from 'node:test';
import assert from 'node:assert/strict';
import { Engine, Card, mulberry32 } from '../www/js/engine.js';
import {
  cardPoints, handPoints, normalizeTarget, targetReached, Match, DEFAULT_TARGET, MODES,
} from '../www/js/scoring.js';

const num = (c, n) => new Card(c, 'NUMBER', n);
const act = (c, k) => new Card(c, k, -1);
const wild = (k) => new Card(null, k, -1);

test('card values: numbers face value, JUMP/SPIN/PULL2 20, wilds 50', () => {
  for (let n = 0; n <= 9; n++) assert.equal(cardPoints(num('GREEN', n)), n);
  for (const k of ['JUMP', 'SPIN', 'PULL2']) assert.equal(cardPoints(act('PINK', k)), 20);
  assert.equal(cardPoints(wild('TOUCH')), 50); // the Touch One wild
  assert.equal(cardPoints(wild('PULL4')), 50);
});

test('a full deck is worth the expected total', () => {
  const e = new Engine(mulberry32(3));
  e.start(1);
  const all = [...e.drawPile, ...e.discard, ...e.hands.flat()];
  // per colour: 2*(1..9)=90, 6 actions*20=120 -> 210 ; 4 colours = 840 ; 8 wilds * 50 = 400
  assert.equal(handPoints(all), 840 + 400);
});

test('scoring game defaults', () => {
  assert.equal(DEFAULT_TARGET, '500');
  const m = new Match(3);
  assert.equal(m.target, '500');
  assert.deepEqual(m.totals, [0, 0, 0]);
  assert.equal(m.round, 1);
  assert.deepEqual(MODES.map((x) => x.id), ['quick', 'scoring']);
});

test('round scoring: winner collects everything left in the other hands', () => {
  const m = new Match(3, 500);
  const r = m.scoreRound(0, [
    [],
    [num('GREEN', 7), act('PINK', 'JUMP'), wild('TOUCH')],        // 7 + 20 + 50 = 77
    [wild('PULL4'), act('GREEN', 'PULL2'), act('PURPLE', 'SPIN'), num('PINK', 0)], // 50+20+20+0 = 90
  ]);
  assert.equal(r.points, 167);
  assert.deepEqual(r.seats.map((s) => s.value), [0, 77, 90]);
  assert.deepEqual(r.seats.map((s) => s.cards), [0, 3, 4]);
  assert.deepEqual(m.totals, [167, 0, 0]);
  assert.equal(r.matchOver, false);
  assert.equal(m.round, 2);
});

test('an opponent can win a round and keep their own total', () => {
  const m = new Match(2, 500);
  m.scoreRound(1, [[num('GREEN', 9)], []]);
  assert.deepEqual(m.totals, [0, 9]);
});

test('totals accumulate across rounds and the target is preserved', () => {
  const m = new Match(2, 300);
  m.scoreRound(0, [[], [wild('PULL4'), wild('TOUCH')]]);        // +100
  m.scoreRound(1, [[act('GREEN', 'SPIN')], []]);                 // opp +20
  m.scoreRound(0, [[], [num('PINK', 5), act('GREEN', 'PULL2')]]); // +25
  assert.deepEqual(m.totals, [125, 20]);
  assert.equal(m.target, '300');
  assert.equal(m.over, false);
  assert.equal(m.rounds.length, 3);
  assert.deepEqual(m.rounds.map((r) => r.round), [1, 2, 3]);
});

test('match ends on reaching the target exactly', () => {
  const m = new Match(2, 100);
  const r = m.scoreRound(0, [[], [wild('PULL4'), wild('TOUCH')]]); // exactly 100
  assert.equal(r.matchOver, true);
  assert.equal(m.over, true);
  assert.equal(m.winner, 0);
  assert.equal(r.matchWinner, 0);
});

test('match ends on exceeding the target and then refuses more rounds', () => {
  const m = new Match(2, 90);
  const r = m.scoreRound(1, [[wild('PULL4'), wild('TOUCH')], []]);
  assert.equal(r.matchOver, true);
  assert.equal(m.winner, 1);
  assert.throws(() => m.scoreRound(0, [[], [num('GREEN', 1)]]));
});

test('one point short does not end the match', () => {
  const m = new Match(2, 101);
  assert.equal(m.scoreRound(0, [[], [wild('PULL4'), wild('TOUCH')]]).matchOver, false);
});

test('a round worth 0 points is allowed and cannot end the match', () => {
  const m = new Match(2, 1);
  const r = m.scoreRound(0, [[], [num('GREEN', 0), num('PINK', 0)]]);
  assert.equal(r.points, 0);
  assert.equal(r.matchOver, false);
});

test('target entry: any positive whole number, no maximum', () => {
  assert.equal(normalizeTarget('500'), '500');
  assert.equal(normalizeTarget('1'), '1');
  assert.equal(normalizeTarget('0042'), '42');
  assert.equal(normalizeTarget(750), '750');
  assert.equal(normalizeTarget('99999999999999999999999999'), '99999999999999999999999999');
  for (const bad of ['', '0', '000', '-5', '1.5', '1e3', ' 5', 'abc', '5 0', null, undefined, 0, -3, 2.5, NaN]) {
    assert.equal(normalizeTarget(bad), null, `rejects ${String(bad)}`);
  }
  assert.throws(() => new Match(2, '0'));
  assert.throws(() => new Match(2, ''));
});

test('a huge target is never reached by normal play', () => {
  const m = new Match(2, '99999999999999999999999999');
  m.scoreRound(0, [[], [wild('PULL4')]]);
  assert.equal(m.over, false);
  assert.equal(targetReached(50, '99999999999999999999999999'), false);
  assert.equal(targetReached(50, '50'), true);
  assert.equal(targetReached(50, '51'), false);
});

test('a new match starts clean and defaults back to 500', () => {
  const a = new Match(2, 100);
  a.scoreRound(0, [[], [wild('PULL4'), wild('TOUCH')]]);
  const b = new Match(2);
  assert.deepEqual(b.totals, [0, 0]);
  assert.equal(b.target, '500');
  assert.equal(b.over, false);
  assert.equal(b.round, 1);
});

test('input validation on scoreRound', () => {
  const m = new Match(2);
  assert.throws(() => m.scoreRound(0, [[num('GREEN', 1)], [num('GREEN', 2)]]), /empty hand/);
  assert.throws(() => m.scoreRound(0, [[]]), /hand count/);
});

test('plays a real engine game to the end and scores its leftover hands', () => {
  const e = new Engine(mulberry32(11));
  e.start(2);
  let guard = 0;
  while (!e.gameOver && guard++ < 5000) {
    const seat = e.current;
    const pick = e.computerPick(seat);
    if (pick < 0) { e.drawOne(seat); e.passTo(e.nextSeat(seat)); continue; }
    const c = e.hands[seat][pick];
    e.play(seat, pick, c.isWild() ? e.chooseColor(seat) : c.color);
  }
  assert.ok(e.gameOver);
  const winner = e.hands.findIndex((h) => h.length === 0);
  const m = new Match(e.seats, 500);
  const r = m.scoreRound(winner, e.hands);
  const expected = e.hands.reduce((a, h) => a + handPoints(h), 0);
  assert.equal(r.points, expected);
  assert.equal(m.totals[winner], expected);
});
