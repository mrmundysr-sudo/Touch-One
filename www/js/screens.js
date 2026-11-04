// Setup screen and Scoring Game scoreboard. Methods are mixed into TableView (see the bottom of game.js), so they share
// its canvas, game clock, input and fade handling. Everything is drawn as real, tappable UI over the art plates:
// no flattened mock-up is used for any control.
//
// Adding a Setup option: push a row in setupRows() (type 'choice' or 'number') and handle its key in
// setupChoose()/setupRowAction(). The screen lays rows out top to bottom, so nothing else needs redesigning.
import {
  argb, rgb, css, cardColor, shade, withAlpha, CREAM, GOLD, GOLD_DEEP, WOOD_INK, ALERT, WHITE,
  font, rr, fillRR, strokeRR, linear, centeredBaseline, outlinedText, clamp01, easeOutBack,
} from './gfx.js';
import { MODES, MODE_SCORING, DEFAULT_TARGET, normalizeTarget } from './scoring.js';

const PAD = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'back', '0', 'done'];

function fitSize(g, s, size, maxW, minSize) {
  g.font = font(size);
  const w = g.measureText(s).width;
  return w <= maxW ? size : Math.max(minSize, size * maxW / w);
}

/** Left-aligned text that shrinks to maxW. */
function leftText(g, s, x, base, size, maxW, fill) {
  g.font = font(fitSize(g, s, size, maxW, size * 0.6));
  g.textAlign = 'left';
  g.textBaseline = 'alphabetic';
  g.fillStyle = css(fill);
  g.fillText(s, x, base);
}

export const screenMethods = {
  initModeState() {
    this.mode = 'quick';          // 'quick' | 'scoring'
    this.targetText = DEFAULT_TARGET; // committed points-to-win (digit string, no upper bound)
    this.draft = null;            // keypad text while the target is being edited
    this.fresh = false;           // true until the first keypress: that key replaces the prefilled value
    this.match = null;            // Match while a Scoring Game is in progress
    this.roundResult = null;
    this.roundScored = false;
    this.setupAt = 0;
    this.errAt = -1e5;
    this.su = null;
    this.pressKey = -1;
    this.btnNextBmp = null; this.btnNewBmp = null;
  },

  playerName(seat) {
    if (seat === 0) return 'You';
    const who = this.charVoice(seat);
    return who === 'dog' ? 'Scoobert' : who === 'girl' ? 'Girl' : 'Boy';
  },

  // ---- flow -----------------------------------------------------------------------------------------------------
  openSetup() {
    if (this.transition) return;
    this.sfx.play('sfx_click');
    this.fadeTo(() => {
      this.draft = null; this.fresh = false;
      this.screen = 'SETUP';
      this.setupAt = this.gt;
      this.trimScenes('bg_setup');
    });
  },

  /** Leaves Setup for the opponent screen. A valid typed target is kept; an invalid one is dropped. */
  closeSetup() {
    if (this.transition) return;
    this.commitDraft();
    this.draft = null;
    this.sfx.play('sfx_click');
    this.fadeTo(() => {
      this.screen = 'SPLASH';
      this.splashAt = this.gt;
      this.trimScenes('bg_splash');
    });
  },

  /** Throws away the running Scoring Game: the next one starts from 0 with the default target. */
  abandonMatch() {
    this.match = null;
    this.roundResult = null;
    this.roundScored = false;
    this.targetText = DEFAULT_TARGET;
    this.draft = null;
  },

  commitDraft() {
    if (this.draft === null) return true;
    const n = normalizeTarget(this.draft);
    if (n === null) return false;
    this.targetText = n;
    this.draft = null;
    this.fresh = false;
    return true;
  },

  /** Called once when the engine reports the round over in a Scoring Game. */
  finishScoringRound() {
    if (this.roundScored || !this.match) return;
    this.roundScored = true;
    const e = this.engine;
    const winner = e.winnerSeat >= 0 ? e.winnerSeat : e.hands.findIndex((h) => h.length === 0);
    const res = this.match.scoreRound(winner, e.hands);
    this.roundResult = res;
    this.fadeTo(() => {
      this.clearGameTasks();
      this.flies.length = 0;
      this.screen = 'SCORES';
      this.endAt = this.gt;
      this.sfx.play(res.matchOver ? (res.matchWinner === 0 ? 'sfx_win' : 'sfx_loss') : (winner === 0 ? 'sfx_win' : 'sfx_loss'));
      this.trimScenes('bg_scoring');
    });
  },

  scoresButton() {
    const over = this.roundResult && this.roundResult.matchOver;
    if (this.transition) return;
    this.sfx.play('sfx_click');
    if (over) {
      this.fadeTo(() => {
        this.abandonMatch();
        this.screen = 'SPLASH';
        this.splashAt = this.gt;
        this.trimScenes('bg_splash');
      });
    } else {
      this.fadeTo(() => this.beginRound(this.engine.seats - 1));
    }
  },

  // ---- Setup screen -----------------------------------------------------------------------------------------------
  setupRows() {
    const rows = [{ type: 'choice', key: 'mode', label: 'Game mode', options: MODES }];
    if (this.mode === MODE_SCORING) rows.push({ type: 'number', key: 'target', label: 'Points to win' });
    return rows;
  },

  setupChoose(rowKey, id) {
    if (rowKey !== 'mode') return;
    if (this.mode !== id) this.sfx.play('sfx_select');
    this.commitDraftOrRevert();
    this.mode = id;
  },

  commitDraftOrRevert() {
    if (!this.commitDraft()) this.draft = null;
  },

  setupLayout() {
    const { W, H } = this;
    const q = Math.min(W, H * 0.5);
    const left = W * 0.06, cw = W * 0.88;
    const L = { q, left, cw, title: H * 0.125, cards: [], field: null, keys: [], pad: null, labels: [], hints: [] };
    let y = H * 0.20;
    for (const row of this.setupRows()) {
      L.labels.push({ text: row.label, base: y + q * 0.05 });
      y += q * 0.085;
      if (row.type === 'choice') {
        const oh = q * 0.21, gap = q * 0.03;
        for (const opt of row.options) {
          L.cards.push({ row: row.key, id: opt.id, opt, l: left, t: y, r: left + cw, b: y + oh });
          y += oh + gap;
        }
        y += q * 0.02;
      } else if (row.type === 'number') {
        const fh = q * 0.17, fw = cw * 0.62;
        L.field = { l: W / 2 - fw / 2, t: y, r: W / 2 + fw / 2, b: y + fh };
        y += fh;
        L.hints.push({ base: y + q * 0.072 });
        y += q * 0.1;
      }
    }
    if (this.draft !== null) {
      const kh = q * 0.125, kg = q * 0.022, kw = Math.min(cw * 0.3, q * 0.3);
      const total = 4 * kh + 3 * kg, bottom = H - Math.max(H * 0.03, q * 0.04);
      const top = bottom - total, pl = W / 2 - (3 * kw + 2 * kg) / 2;
      L.pad = { l: pl - kg * 1.4, t: top - kg * 1.4, r: pl + 3 * kw + 2 * kg + kg * 1.4, b: bottom + kg * 1.4 };
      PAD.forEach((k, i) => {
        const c = i % 3, r = Math.floor(i / 3);
        L.keys.push({ k, l: pl + c * (kw + kg), t: top + r * (kh + kg), r: pl + c * (kw + kg) + kw, b: top + r * (kh + kg) + kh });
      });
    }
    this.su = L;
    return L;
  },

  hitSetup(x, y) {
    const u = this.u, L = this.su || this.setupLayout();
    const inside = (o, by = 0) => x >= o.l - by && x <= o.r + by && y >= o.t - by && y <= o.b + by;
    const b = this.backRect;
    if (inside(b, 6 * u)) return { t: 'back', i: 0 };
    for (let i = 0; i < L.keys.length; i++) if (inside(L.keys[i], 3 * u)) return { t: 'key', i };
    if (L.pad && inside(L.pad)) return { t: 'none', i: 0 };
    for (let i = 0; i < L.cards.length; i++) if (inside(L.cards[i])) return { t: 'card', i };
    if (L.field && inside(L.field, 8 * u)) return { t: 'field', i: 0 };
    return { t: 'none', i: 0 };
  },

  touchSetup(action, x, y) {
    if (action === 'down') {
      if (this.gt - this.setupAt < 350) { this.pressId = 0; return; }
      const h = this.hitSetup(x, y);
      this.pressTile = h.i;
      this.pressSetup = h.t;
      this.pressId = h.t === 'none' ? 0 : (h.t === 'back' ? 7 : 8);
      this.pressKey = h.t === 'key' ? h.i : -1;
    } else if (action === 'up') {
      const was = this.pressId !== 0;
      const kind = this.pressSetup, idx = this.pressTile;
      this.pressId = 0; this.pressKey = -1;
      const h = this.hitSetup(x, y);
      if (!was) {
        // tapping empty space while typing finishes the edit (keeps a valid value, drops an invalid one)
        if (this.draft !== null && h.t === 'none' && this.su && !(this.su.pad && x >= this.su.pad.l && x <= this.su.pad.r && y >= this.su.pad.t && y <= this.su.pad.b)) {
          this.commitDraftOrRevert();
        }
        return;
      }
      if (h.t !== kind || h.i !== idx) return;
      const L = this.su;
      if (kind === 'back') this.closeSetup();
      else if (kind === 'card') { const c = L.cards[idx]; this.setupChoose(c.row, c.id); }
      else if (kind === 'field') {
        if (this.draft === null) { this.draft = this.targetText; this.fresh = true; this.sfx.play('sfx_click'); }
      } else if (kind === 'key') this.padKey(L.keys[idx].k);
    } else if (action === 'cancel') {
      this.pressId = 0; this.pressKey = -1;
    }
  },

  padKey(k) {
    if (this.draft === null) return;
    if (k === 'done') {
      if (normalizeTarget(this.draft) === null) { this.errAt = this.gt; this.sfx.play('sfx_error'); return; }
      this.commitDraft();
      this.sfx.play('sfx_confirm');
      return;
    }
    this.sfx.play('sfx_click', 0.7, 1);
    if (k === 'back') { this.draft = this.draft.slice(0, -1); this.fresh = false; return; }
    if (this.fresh) { this.draft = ''; this.fresh = false; }
    if (this.draft === '0') this.draft = '';
    this.draft += k;
  },

  drawSetup(g) {
    const { W, H, u, gt } = this;
    this.drawScene(g, 'bg_setup');
    const L = this.setupLayout();
    const t = clamp01((gt - this.setupAt) / 420);
    const lift = (1 - easeOutBack(clamp01((gt - this.setupAt) / 520))) * H * 0.03;
    g.save();
    g.globalAlpha = t;
    g.translate(0, lift);
    this.label(g, 'Setup', W / 2, L.title, L.q * 0.15, GOLD, WOOD_INK, L.q * 0.035, 1);
    for (const lb of L.labels) this.label(g, lb.text, W / 2, lb.base, L.q * 0.052, CREAM, WOOD_INK, L.q * 0.013, 1);

    for (let i = 0; i < L.cards.length; i++) {
      const c = L.cards[i], sel = this.mode === c.id, oh = c.b - c.t, w = c.r - c.l;
      const press = this.pressId === 8 && this.pressSetup === 'card' && this.pressTile === i ? 0.97 : 1;
      g.save();
      g.translate((c.l + c.r) / 2, (c.t + c.b) / 2);
      g.scale(press, press);
      const x = -w / 2, y = -oh / 2, rad = oh * 0.26;
      fillRR(g, x, y + oh * 0.05, w, oh, rad, css(argb(70, 20, 8, 0)));
      fillRR(g, x, y, w, oh, rad, sel ? linear(g, 0, y, 0, y + oh, argb(235, 74, 34, 82), argb(235, 48, 20, 58))
        : css(argb(190, 36, 16, 42)));
      strokeRR(g, x, y, w, oh, rad, sel ? css(GOLD) : css(argb(120, 255, 244, 220)), sel ? Math.max(3, u * 5) : Math.max(2, u * 2.5));
      if (sel) fillRR(g, x + oh * 0.03, y + oh * 0.03, w - oh * 0.06, oh * 0.4, rad * 0.8, linear(g, 0, y, 0, y + oh * 0.4, argb(40, 255, 255, 255), argb(0, 255, 255, 255)));
      // radio dot
      const rcx = x + oh * 0.5, rcy = 0, rrad = oh * 0.19;
      g.beginPath(); g.arc(rcx, rcy, rrad, 0, Math.PI * 2);
      g.fillStyle = css(argb(120, 0, 0, 0)); g.fill();
      g.lineWidth = Math.max(2, u * 3); g.strokeStyle = css(sel ? GOLD : CREAM); g.stroke();
      if (sel) { g.beginPath(); g.arc(rcx, rcy, rrad * 0.55, 0, Math.PI * 2); g.fillStyle = css(GOLD); g.fill(); }
      const tx = x + oh * 0.95, tw = w - oh * 1.15;
      leftText(g, c.opt.title, tx, -oh * 0.04, L.q * 0.066, tw, sel ? GOLD : WHITE);
      leftText(g, c.opt.blurb, tx, oh * 0.30, L.q * 0.038, tw, withAlpha(CREAM, sel ? 1 : 0.85));
      g.restore();
    }

    if (L.field) this.drawTargetField(g, L);
    g.restore();
    if (L.pad) this.drawKeypad(g, L);
    this.drawBack(g);
  },

  drawTargetField(g, L) {
    const { u, gt } = this;
    const f = L.field, w = f.r - f.l, h = f.b - f.t;
    const editing = this.draft !== null;
    const shown = editing ? this.draft : this.targetText;
    const valid = normalizeTarget(shown) !== null;
    const err = editing && (!valid || gt - this.errAt < 900);
    const press = this.pressId === 8 && this.pressSetup === 'field' ? 0.97 : 1;
    g.save();
    g.translate((f.l + f.r) / 2, (f.t + f.b) / 2);
    g.scale(press, press);
    const x = -w / 2, y = -h / 2, rad = h * 0.3;
    fillRR(g, x, y + h * 0.06, w, h, rad, css(argb(70, 20, 8, 0)));
    fillRR(g, x, y, w, h, rad, css(WHITE));
    const rim = h * 0.06;
    fillRR(g, x + rim, y + rim, w - 2 * rim, h - 2 * rim, rad - rim, editing ? css(rgb(255, 250, 235)) : linear(g, 0, y, 0, y + h, rgb(255, 244, 220), rgb(240, 222, 190)));
    if (editing) strokeRR(g, x + rim, y + rim, w - 2 * rim, h - 2 * rim, rad - rim, css(err ? ALERT : GOLD_DEEP), Math.max(3, u * 4));
    // value, shrunk to fit; very long targets keep their last digits
    let text = shown === '' ? '0' : shown;
    const maxW = w - h * 0.7;
    let size = h * 0.56;
    g.font = font(size);
    let tw = g.measureText(text).width;
    if (tw > maxW) { size = Math.max(h * 0.26, size * maxW / tw); g.font = font(size); tw = g.measureText(text).width; }
    if (tw > maxW) {
      while (text.length > 2 && g.measureText('…' + text).width > maxW) text = text.slice(1);
      text = '…' + text; tw = g.measureText(text).width;
    }
    const base = centeredBaseline(g, '0', 0);
    if (editing && this.fresh) fillRR(g, -tw / 2 - h * 0.08, -size * 0.55, tw + h * 0.16, size * 1.1, h * 0.12, css(argb(90, 255, 190, 60)));
    g.textAlign = 'center';
    g.fillStyle = css(shown === '' ? argb(110, 58, 30, 12) : WOOD_INK);
    g.fillText(shown === '' ? '' : text, 0, base);
    if (editing && !this.fresh && Math.floor(gt / 480) % 2 === 0) {
      g.fillStyle = css(GOLD_DEEP);
      g.fillRect(tw / 2 + size * 0.06, -size * 0.5, Math.max(2, u * 3), size);
    }
    g.restore();
    const hint = !editing ? 'Tap the box to change it'
      : (!valid ? 'Enter a whole number above 0' : 'Tap Done when finished');
    this.label(g, hint, this.W / 2, L.hints[0].base, L.q * 0.04, err ? rgb(255, 150, 140) : CREAM, WOOD_INK, L.q * 0.011, 1);
  },

  drawKeypad(g, L) {
    const { u, gt } = this;
    const p = L.pad;
    fillRR(g, p.l, p.t, p.r - p.l, p.b - p.t, L.q * 0.06, css(argb(185, 28, 12, 34)));
    strokeRR(g, p.l, p.t, p.r - p.l, p.b - p.t, L.q * 0.06, css(argb(110, 255, 244, 220)), Math.max(2, u * 2.5));
    const valid = this.draft !== null && normalizeTarget(this.draft) !== null;
    L.keys.forEach((k, i) => {
      const w = k.r - k.l, h = k.b - k.t, press = this.pressKey === i ? 0.93 : 1;
      const isDone = k.k === 'done', isBack = k.k === 'back';
      const base = isDone ? rgb(36, 200, 92) : isBack ? cardColor('PINK') : cardColor('PURPLE');
      const dim = isDone && !valid;
      g.save();
      g.translate((k.l + k.r) / 2, (k.t + k.b) / 2);
      g.scale(press, press);
      g.globalAlpha = dim ? 0.55 : 1;
      const x = -w / 2, y = -h / 2, rad = h * 0.27;
      fillRR(g, x, y + h * 0.06, w, h, rad, css(argb(80, 10, 4, 0)));
      fillRR(g, x, y, w, h, rad, css(WHITE));
      const rim = h * 0.06;
      fillRR(g, x + rim, y + rim, w - 2 * rim, h - 2 * rim, rad - rim, linear(g, 0, y, 0, y + h, shade(base, 1.25), shade(base, 0.82)));
      fillRR(g, x + rim, y + rim, w - 2 * rim, (h - 2 * rim) * 0.5, rad - rim, linear(g, 0, y, 0, y + h * 0.5, argb(80, 255, 255, 255), argb(0, 255, 255, 255)));
      if (isBack) this.drawBackspaceGlyph(g, h);
      else {
        const s = isDone ? 'Done' : k.k;
        g.font = font(h * (isDone ? 0.4 : 0.56));
        g.textAlign = 'center';
        const by = centeredBaseline(g, s === 'Done' ? 'D' : '0', 0);
        outlinedText(g, s, 0, by, WHITE, shade(base, 0.55), h * 0.05);
      }
      g.restore();
    });
  },

  drawBackspaceGlyph(g, h) {
    const w = h * 0.62, hh = h * 0.36;
    g.beginPath();
    g.moveTo(-w / 2, 0); g.lineTo(-w / 2 + hh * 0.7, -hh); g.lineTo(w / 2, -hh); g.lineTo(w / 2, hh); g.lineTo(-w / 2 + hh * 0.7, hh);
    g.closePath();
    g.lineJoin = 'round';
    g.fillStyle = css(WHITE); g.fill();
    g.lineWidth = h * 0.05; g.strokeStyle = css(shade(cardColor('PINK'), 0.55)); g.stroke();
    g.lineWidth = h * 0.07; g.lineCap = 'round'; g.strokeStyle = css(shade(cardColor('PINK'), 0.7));
    const cx = w * 0.12, d = hh * 0.42;
    g.beginPath(); g.moveTo(cx - d, -d); g.lineTo(cx + d, d); g.moveTo(cx + d, -d); g.lineTo(cx - d, d); g.stroke();
  },

  // ---- scoreboard ---------------------------------------------------------------------------------------------------
  drawScores(g) {
    const { W, H, u, gt } = this;
    this.drawScene(g, 'bg_scoring');
    const res = this.roundResult, m = this.match;
    if (!res || !m) return;
    const q = Math.min(W, H * 0.5);
    const t0 = clamp01((gt - this.endAt) / 420);
    const over = res.matchOver;
    const you = res.winner === 0;
    const head1 = over ? 'Match Over' : 'Round ' + res.round;
    const hs = fitSize(g, head1, q * 0.14, W * 0.5, q * 0.07); // keeps clear of the Menu pill
    this.label(g, head1, W / 2, H * 0.098, hs, GOLD, WOOD_INK, hs * 0.25, t0);
    const wn = this.playerName(over ? res.matchWinner : res.winner);
    const line = over ? (res.matchWinner === 0 ? 'You win the match!' : wn + ' wins the match!')
      : (you ? 'You won the round!' : wn + ' won the round');
    this.label(g, line, W / 2, H * 0.142, q * 0.062, CREAM, WOOD_INK, q * 0.014, t0);

    const seats = m.seats, rh = q * 0.17, head = q * 0.09, foot = q * 0.1;
    const pl = W * 0.05, pw = W * 0.9, pt = H * 0.17, ph = head + seats * rh + foot;
    const pa = clamp01((gt - this.endAt - 120) / 420);
    g.save();
    g.globalAlpha = pa;
    g.translate(0, (1 - easeOutBack(pa)) * H * 0.03);
    fillRR(g, pl, pt + q * 0.01, pw, ph, q * 0.05, css(argb(70, 10, 4, 0)));
    fillRR(g, pl, pt, pw, ph, q * 0.05, css(argb(205, 30, 12, 38)));
    strokeRR(g, pl, pt, pw, ph, q * 0.05, css(argb(120, 255, 244, 220)), Math.max(2, u * 2.5));
    const colRound = pl + pw * 0.62, colTotal = pl + pw * 0.86;
    g.font = font(q * 0.036);
    g.textAlign = 'center'; g.fillStyle = css(withAlpha(CREAM, 0.75));
    g.fillText('Round', colRound, pt + head * 0.68);
    g.fillText('Total', colTotal, pt + head * 0.68);
    g.textAlign = 'left';
    g.fillText('Player', pl + pw * 0.05, pt + head * 0.68);
    for (let s = 0; s < seats; s++) {
      const y = pt + head + s * rh, r = res.seats[s], win = s === res.winner;
      if (win) {
        fillRR(g, pl + pw * 0.015, y + rh * 0.06, pw * 0.97, rh * 0.88, q * 0.035, css(argb(60, 255, 214, 64)));
        strokeRR(g, pl + pw * 0.015, y + rh * 0.06, pw * 0.97, rh * 0.88, q * 0.035, css(GOLD), Math.max(2, u * 3));
      } else if (s > 0) {
        g.fillStyle = css(argb(40, 255, 244, 220)); g.fillRect(pl + pw * 0.05, y, pw * 0.9, Math.max(1, u * 1.5));
      }
      const nameW = pw * 0.5;
      leftText(g, this.playerName(s), pl + pw * 0.05, y + rh * 0.45, q * 0.06, nameW, win ? GOLD : WHITE);
      const sub = win ? 'Went out' : (r.cards + (r.cards === 1 ? ' card' : ' cards') + ' left = ' + r.value + ' pts');
      leftText(g, sub, pl + pw * 0.05, y + rh * 0.8, q * 0.036, nameW, withAlpha(CREAM, 0.85));
      g.textAlign = 'center';
      g.font = font(q * 0.07);
      const by = centeredBaseline(g, '0', y + rh * 0.5);
      g.fillStyle = css(win ? GOLD : argb(120, 255, 244, 220));
      g.fillText(win ? '+' + res.points : '–', colRound, by);
      const tot = String(res.totals[s]);
      g.font = font(fitSize(g, tot, q * 0.08, pw * 0.2, q * 0.04));
      g.fillStyle = css(WHITE);
      g.fillText(tot, colTotal, centeredBaseline(g, '0', y + rh * 0.5));
    }
    const fy = pt + head + seats * rh + foot * 0.62;
    g.textAlign = 'center';
    g.font = font(fitSize(g, 'First to ' + m.target + ' points wins', q * 0.04, pw * 0.9, q * 0.025));
    g.fillStyle = css(withAlpha(CREAM, 0.9));
    g.fillText('First to ' + m.target + ' points wins', W / 2, fy);
    g.restore();

    const bt = clamp01((gt - this.endAt - 520) / 420);
    const bmp = over ? this.btnNewBmp : this.btnNextBmp;
    if (bt > 0 && bmp) {
      const pulse = 1 + 0.022 * Math.sin(gt / 330);
      const press = this.pressId === 2 ? 0.94 : 1;
      this.drawBitmapScaled(g, bmp, (this.againRect.l + this.againRect.r) / 2, (this.againRect.t + this.againRect.b) / 2, easeOutBack(bt) * pulse * press);
    }
    this.drawBack(g);
  },

  touchScores(action, x, y) {
    const u = this.u;
    const ready = this.gt - this.endAt > 750;
    const inBtn = (px, py) => px >= this.againRect.l && px <= this.againRect.r && py >= this.againRect.t && py <= this.againRect.b;
    const inBack = (px, py) => px >= this.backRect.l - 6 * u && px <= this.backRect.r + 6 * u && py >= this.backRect.t - 6 * u && py <= this.backRect.b + 6 * u;
    if (action === 'down') {
      this.pressId = inBack(x, y) ? 7 : (ready && inBtn(x, y)) ? 2 : 0;
    } else if (action === 'up') {
      const was = this.pressId;
      this.pressId = 0;
      if (was === 2 && inBtn(x, y)) this.scoresButton();
      else if (was === 7 && inBack(x, y)) this.onBack();
    } else if (action === 'cancel') {
      this.pressId = 0;
    }
  },
};
