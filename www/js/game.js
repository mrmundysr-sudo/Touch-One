// The whole game surface: a canvas port of the Android TableView (1.4.0).
//
// Timing model: everything runs off one game clock (gt) that advances only while frames are drawn and is
// clamped per frame. Animations, opponent "thinking" delays and sound cues are all scheduled on that clock, so a
// hitch slows the game down instead of skipping it, sound can never drift from the picture, and the game pauses by
// itself when the app leaves the screen.
//
// The rules Engine resolves a move instantly; this view then plays the move out as a short timeline (card flight,
// landing, effect, hand-over) while input is locked. "Shown" fields are what the player currently sees, which
// lags the engine by exactly the animation in flight.
import {
  argb, rgb, css, alphaOf, cardColor, colorName, shade, withAlpha, CREAM, GOLD, GOLD_DEEP, WOOD_INK, ALERT, WHITE,
  font, makeCanvas, scaleSmooth, rr, fillRR, strokeRR, oval, linear, radial, wedge, arcPath, centeredBaseline,
  outlinedText, clamp01, lerp, easeOutCubic, easeOutQuart, easeInCubic, easeOutBack,
} from './gfx.js';
import { CardArt } from './art.js';
import { COLORS } from './engine.js';
import { Match, MODE_SCORING } from './scoring.js';
import { screenMethods } from './screens.js';

const HATS1 = [[0.24, 0.125]];
const HATS2 = [[0.22, 0.135], [0.78, 0.135]];
const HATS3 = [[0.16, 0.225], [0.50, 0.205], [0.84, 0.210]];
const STYLE1 = [1];
const STYLE2 = [0, 1];
const STYLE3 = [0, 2, 1];
const GLOW1 = [[0.5582, 0.2302]];
const GLOW2 = [[0.2519, 0.3776], [0.7788, 0.3992]];
const GLOW3 = [[0.1483, 0.4617], [0.5021, 0.4530], [0.8425, 0.4524]];

const CALL_HOLD_MS = 750;
const WHEEL_SPIN_MS = 1150;
const WHEEL_PICKABLE_MS = 380;
const FADE_OUT_MS = 190;
const FADE_IN_MS = 330;
const END_INPUT_GUARD_MS = 750;
const MAX_P = 120;

const P_NONE = 0, P_TILE = 1, P_AGAIN = 2, P_END = 3, P_DECK = 4, P_DISCARD = 5, P_HAND = 6, P_BACK = 7;

const rect = () => ({ l: 0, t: 0, r: 0, b: 0 });
const setRect = (o, l, t, r, b) => { o.l = l; o.t = t; o.r = r; o.b = b; };
const rw = (o) => o.r - o.l;
const rh = (o) => o.b - o.t;
const rcx = (o) => (o.l + o.r) / 2;
const rcy = (o) => (o.t + o.b) / 2;
const hit = (o, x, y, by = 0) => x >= o.l - by && x <= o.r + by && y >= o.t - by && y <= o.b + by;

export class TableView {
  constructor(canvas, images, sfx, engine, haptics, winGif) {
    this.canvas = canvas;
    this.g = canvas.getContext('2d');
    this.images = images;
    this.sfx = sfx;
    this.engine = engine;
    this.haptics = haptics;
    this.winGif = winGif;
    this.gifOK = false;
    if (winGif) {
      winGif.addEventListener('load', () => { this.gifOK = true; });
      winGif.addEventListener('error', () => { this.gifOK = false; });
    }
    this.rnd = Math.random;

    this._screen = 'SPLASH';
    this.W = 0; this.H = 0; this.u = 0;
    this.gt = 0; this.lastReal = 0; this.frameDt = 16; this.resumed = true;
    this.tasks = [];

    this.scenes = new Map();
    this.sheet = images.scoobert_dance_sheet || null;
    this.art = null;
    this.cardW = 0; this.cardH = 0;
    this.tileBmp = [null, null, null];
    this.bubble = [null, null, null];
    this.bubblePlayer = null;
    this.bubblesFor = -1;

    this.deckRect = rect(); this.discardRect = rect(); this.endRect = rect(); this.againRect = rect();
    this.backRect = rect();
    this.tileRect = [rect(), rect(), rect()];
    this.setupRect = rect(); // the orange Setup button above the 1 / 2 / 3 tiles

    this.shownCurrent = -1;
    this.shownCount = [0, 0, 0, 0];
    this.countPopAt = [-1e5, -1e5, -1e5, -1e5];
    this.lowFlashUntil = [0, 0, 0, 0];
    this.lastShown = [0, 0, 0, 0];
    this.shownTop = null; this.prevTop = null; this.shownLive = null;
    this.arrowFrom = 1; this.arrowTo = 1; this.arrowFlipAt = -1e5;
    this.discardBumpAt = -1e5;
    this.busy = false; this.dealing = false; this.selected = 0;

    this.slots = new Map();
    this.hidden = new Set();
    this.hx = []; this.hy = []; this.hw = []; this.hr = [];
    this.tgt = [0, 0, 0, 0];
    this.dimAmt = 0;
    this.shakeCard = null; this.shakeAt = 0;
    this.dragDy = 0; this.dragUp = false;

    this.flies = [];
    this.pops = [];
    this.px = new Float32Array(MAX_P); this.py = new Float32Array(MAX_P);
    this.pvx = new Float32Array(MAX_P); this.pvy = new Float32Array(MAX_P);
    this.plife = new Float32Array(MAX_P); this.pmax = new Float32Array(MAX_P);
    this.psize = new Float32Array(MAX_P); this.prot = new Float32Array(MAX_P);
    this.pcol = new Uint32Array(MAX_P);
    this.pn = 0;
    this.rings = [];

    this.flashAt = -1e5;
    this.chipText = null; this.chipColor = 0; this.chipAt = -1e5;
    this.flashMsg = null; this.flashMsgUntil = 0;
    this.statusShown = ''; this.statusPrev = ''; this.statusChangedAt = 0;
    this.humanTurnAt = 0;

    this.holding = false; this.callArmed = false; this.holdStart = 0; this.holdStream = null;

    this.colorPickFor = -1; this.wheelAt = 0; this.wheelTurns = 0; this.wheelPicked = -1; this.wheelPickAt = 0;
    this.lastTickStep = 0; this.lastTickAt = 0;

    this.pressId = P_NONE; this.pressTile = -1;
    this.downX = 0; this.downY = 0; this.dragStartSel = 0; this.scrubbed = false; this.lastSelectSfx = 0;

    this.splashAt = 0; this.endAt = 0;
    this.transition = false; this.fadePhase = 0; this.fadePhaseAt = 0;

    this.raf = 0;
    this.initModeState();
    this.attachInput();
  }

  get screen() { return this._screen; }
  set screen(v) {
    this._screen = v;
    if (!this.winGif) return;
    if (v === 'WIN') {
      this.winGif.style.display = 'block';
      this.winGif.src = 'img/win_scoobert.gif?' + Date.now(); // new src restarts the animation
    } else {
      this.winGif.style.display = 'none';
      this.winGif.removeAttribute('src');
      this.gifOK = false;
    }
  }

  wheelCx() { return this.W / 2; }
  wheelCy() { return this.H * 0.48; }
  wheelR() { return this.W * 0.28; }

  // =====================================================================
  // Lifecycle
  // =====================================================================
  start() {
    this.resize();
    window.addEventListener('resize', () => this.resize());
    document.addEventListener('visibilitychange', () => (document.hidden ? this.onHostPause() : this.onHostResume()));
    window.addEventListener('pagehide', () => this.onHostPause());
    window.addEventListener('pageshow', () => this.onHostResume());
    this.splashAt = this.gt;
    this.loop = this.loop.bind(this);
    this.raf = requestAnimationFrame(this.loop);
  }

  onHostPause() {
    this.resumed = false;
    if (this.holding) {
      this.holding = false;
      if (this.holdStream) this.holdStream.stop();
    }
    this.sfx.suspend();
    cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  onHostResume() {
    this.resumed = true;
    this.lastReal = 0;
    this.sfx.resume();
    if (!this.raf) this.raf = requestAnimationFrame(this.loop);
  }

  /** Back to the title screen. @returns true when consumed. */
  onBack() {
    if (this.transition) return true;
    if (this.screen === 'SPLASH') return false;
    if (this.screen === 'SETUP') { this.closeSetup(); return true; }
    this.sfx.play('sfx_click');
    this.fadeTo(() => {
      this.abandonMatch(); // leaving mid-match ends it: the next Scoring Game starts from 0 with the default target
      this.clearGameTasks();
      this.flies.length = 0;
      this.holding = false;
      this.screen = 'SPLASH';
      this.splashAt = this.gt;
      this.trimScenes('bg_splash');
    });
    return true;
  }

  // =====================================================================
  // Clock and timeline
  // =====================================================================
  advanceClock() {
    const real = performance.now();
    if (this.lastReal === 0) this.lastReal = real;
    const d = Math.max(0, Math.min(50, real - this.lastReal));
    this.lastReal = real;
    this.frameDt = d;
    this.gt += d;
  }

  after(delay, fn) { this.addTask(delay, fn, false); }
  afterUi(delay, fn) { this.addTask(delay, fn, true); }

  addTask(delay, fn, ui) {
    const t = { at: this.gt + Math.max(0, delay), run: fn, ui };
    let i = this.tasks.length;
    while (i > 0 && this.tasks[i - 1].at > t.at) i--;
    this.tasks.splice(i, 0, t);
  }

  clearGameTasks() { this.tasks = this.tasks.filter((t) => t.ui); }

  runTasks() {
    let guard = 0;
    while (this.tasks.length && this.tasks[0].at <= this.gt && guard++ < 300) this.tasks.shift().run();
  }

  smooth(tauMs) { return 1 - Math.exp(-this.frameDt / tauMs); }
  breath(periodMs) { return 0.5 + 0.5 * Math.sin(this.gt * 2 * Math.PI / periodMs); }

  // =====================================================================
  // Size + static assets
  // =====================================================================
  resize() {
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    const w = Math.round(window.innerWidth * dpr), h = Math.round(window.innerHeight * dpr);
    if (w <= 0 || h <= 0) return;
    this.dpr = dpr;
    this.canvas.width = w;
    this.canvas.height = h;
    this.W = w; this.H = h; this.u = w / 1080;
    this.scenes.clear();
    this.bubblesFor = -1;
    this.buildStatic();
    if (this.engine.seats > 0 && this.screen !== 'SPLASH') this.prepareBubbles(this.engine.seats - 1);
  }

  buildStatic() {
    const { W, H, u } = this;
    this.cardW = W * 0.28;
    this.cardH = this.cardW * 1.4;
    this.art = new CardArt(this.images, Math.round(this.cardW), Math.round(this.cardH));

    const cw = W * 0.22, chh = cw * 1.4;
    setRect(this.deckRect, W * 0.16, H * 0.50, W * 0.16 + cw, H * 0.50 + chh);
    setRect(this.discardRect, W * 0.58, H * 0.50, W * 0.58 + cw, H * 0.50 + chh);
    const es = W * 0.16, ecx = W * 0.0897, ecy = H * 0.6151;
    setRect(this.endRect, ecx - es / 2, ecy - es / 2, ecx + es / 2, ecy + es / 2);
    // Covers the "Play again" plate baked into the win animation (x .197-.803, y .79-.869).
    setRect(this.againRect, W * 0.178, H * 0.781, W * 0.822, H * 0.8785);
    setRect(this.backRect, W * 0.03, H * 0.022, W * 0.03 + W * 0.20, H * 0.022 + W * 0.095);

    const ts = W * 0.22, gap = W * 0.06, start = (W - (3 * ts + 2 * gap)) / 2, ty = H * 0.86;
    for (let i = 0; i < 3; i++) {
      const x = start + i * (ts + gap);
      setRect(this.tileRect[i], x, ty, x + ts, ty + ts);
    }
    // Setup: same size, shape and spacing as the opponent tiles, centred one tile-height above them.
    const sy = ty - ts - H * 0.03;
    setRect(this.setupRect, (W - ts) / 2, sy, (W + ts) / 2, sy + ts);
    const tc = [cardColor('GREEN'), cardColor('PURPLE'), cardColor('PINK')];
    for (let i = 0; i < 3; i++) this.tileBmp[i] = this.makeTile(i + 1, tc[i], Math.round(ts));
    this.setupBmp = this.makeTile('Setup', cardColor('ORANGE'), Math.round(ts), 0.27);
    const aw = Math.round(rw(this.againRect)), ah = Math.round(rh(this.againRect));
    this.againBmp = this.makeAgain(aw, ah);
    this.btnNextBmp = this.makeAgain(aw, ah, 'Next Round');
    this.btnNewBmp = this.makeAgain(aw, ah, 'New Game');
    this.endSignBmp = this.makeEndSign(Math.round(es));
    this.arrowBmp = this.makeArrow(Math.round(W * 0.055));
    this.wheelBmp = this.makeWheel();
    this.dashLen = [16 * u, 12 * u];
  }

  scene(name) {
    let b = this.scenes.get(name);
    if (b || this.W === 0) return b || null;
    const src = this.images[name];
    if (!src) return null;
    b = scaleSmooth(src, this.W, this.H);
    this.scenes.set(name, b);
    return b;
  }

  /** Big plates are ~10 MB each at screen size; keep only what's on screen. */
  trimScenes(...keep) {
    for (const k of [...this.scenes.keys()]) if (!keep.includes(k)) this.scenes.delete(k);
  }

  tableSceneName(opp) { return opp === 1 ? 'bg_table_1' : opp === 2 ? 'bg_table_2' : 'bg_table_3'; }

  prepareBubbles(opp) {
    if (this.bubblesFor === opp) return;
    this.bubblesFor = opp;
    this.bubble = [null, null, null];
    const { W } = this;
    const bw = W * (opp === 1 ? 0.345 : opp === 2 ? 0.30 : 0.27);
    const styles = opp === 1 ? STYLE1 : opp === 2 ? STYLE2 : STYLE3;
    const names = ['bubble_count', 'bubble_count_right', 'bubble_count_down'];
    for (const s of styles) {
      if (this.bubble[s]) continue;
      const src = this.images[names[s]] || this.images.bubble_count;
      if (!src) continue;
      this.bubble[s] = scaleSmooth(src, bw, bw * (s === 2 ? 0.80 : 0.72));
    }
    const src = this.images.bubble_count;
    if (src) {
      const pw = W * 0.26;
      this.bubblePlayer = scaleSmooth(src, pw, pw * 0.68);
    }
  }

  // ---- procedural chrome (all drawn in Outfit at full resolution) -------
  makeTile(n, color, s, labelScale = 0.62) {
    const m = Math.round(s * 0.14), B = s + m * 2;
    const c = makeCanvas(B, B), g = c.getContext('2d');
    const r = s * 0.27;
    for (let i = 0; i < 12; i++) {
      const k = m * (i / 12);
      fillRR(g, k, k + m * 0.35, B - 2 * k, B - 2 * k, r + (m - k) * 0.5, css(argb(12, 20, 8, 0)));
    }
    fillRR(g, m, m, s, s, r, css(WHITE));
    const rim = s * 0.05;
    fillRR(g, m + rim, m + rim, s - 2 * rim, s - 2 * rim, r - rim, linear(g, 0, m, 0, m + s, shade(color, 1.25), shade(color, 0.82)));
    fillRR(g, m + rim, m + rim, s - 2 * rim, s - 2 * rim, r - rim, linear(g, 0, m, 0, m + s * 0.55, argb(95, 255, 255, 255), argb(0, 255, 255, 255)));
    g.font = font(s * labelScale);
    g.textAlign = 'center';
    const num = String(n);
    const by = centeredBaseline(g, num, m + s * 0.5);
    g.fillStyle = css(argb(70, 0, 0, 0));
    g.fillText(num, B / 2, by + s * 0.03);
    outlinedText(g, num, B / 2, by, WHITE, shade(color, 0.55), s * 0.05);
    return c;
  }

  makeAgain(w, h, label = 'Play again') {
    const m = Math.round(h * 0.10);
    const c = makeCanvas(w, h), g = c.getContext('2d');
    const bw = w - m * 2, bh = h - m * 2, r = bh / 2;
    for (let i = 0; i < 10; i++) {
      const k = m * (i / 10);
      fillRR(g, k, k + m * 0.45, w - 2 * k, h - 2 * k - m * 0.1, r + (m - k), css(argb(14, 10, 25, 10)));
    }
    const green = rgb(36, 200, 92);
    fillRR(g, m, m, bw, bh, r, css(rgb(16, 84, 42)));
    const rim = bh * 0.07;
    fillRR(g, m + rim, m + rim, bw - 2 * rim, bh - 2 * rim, r - rim, linear(g, 0, m, 0, m + bh, shade(green, 1.22), shade(green, 0.78)));
    fillRR(g, m + rim * 1.6, m + rim * 1.2, bw - rim * 3.2, bh * 0.52 - rim * 1.2, r * 0.8,
      linear(g, 0, m, 0, m + bh * 0.5, argb(90, 255, 255, 255), argb(0, 255, 255, 255)));
    g.font = font(bh * 0.44);
    g.textAlign = 'center';
    const s = label;
    const by = centeredBaseline(g, s, m + bh / 2);
    g.fillStyle = css(argb(80, 0, 40, 10));
    g.fillText(s, w / 2, by + bh * 0.04);
    outlinedText(g, s, w / 2, by, WHITE, rgb(14, 92, 44), bh * 0.055);
    return c;
  }

  octagon(g, cx, cy, r) {
    g.beginPath();
    for (let i = 0; i < 8; i++) {
      const a = (22.5 + i * 45) * Math.PI / 180;
      const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
      if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
    }
    g.closePath();
  }

  makeEndSign(s) {
    const m = Math.round(s * 0.12), B = s + m * 2;
    const c = makeCanvas(B, B), g = c.getContext('2d');
    const cx = B / 2, cy = B / 2, R = s / 2;
    for (let i = 0; i < 10; i++) {
      const k = i / 10;
      this.octagon(g, cx, cy + m * 0.3, R + m * (1 - k) * 0.9);
      g.fillStyle = css(argb(13, 20, 6, 0));
      g.fill();
    }
    this.octagon(g, cx, cy, R);
    g.fillStyle = css(rgb(250, 244, 236));
    g.fill();
    this.octagon(g, cx, cy, R * 0.86);
    g.fillStyle = radial(g, cx - R * 0.2, cy - R * 0.3, R * 1.25, rgb(255, 104, 96), rgb(168, 18, 30));
    g.fill();
    this.octagon(g, cx, cy, R * 0.80);
    g.fillStyle = linear(g, 0, cy - R, 0, cy, argb(90, 255, 255, 255), argb(0, 255, 255, 255));
    g.fill();
    this.octagon(g, cx, cy, R * 0.86);
    g.strokeStyle = css(argb(150, 110, 8, 18));
    g.lineWidth = R * 0.05;
    g.stroke();
    g.font = font(R * 0.62);
    outlinedText(g, 'END', cx, cy + R * 0.12, WHITE, argb(190, 100, 6, 14), R * 0.07);
    g.font = font(R * 0.30);
    outlinedText(g, 'TURN', cx, cy + R * 0.50, CREAM, argb(190, 100, 6, 14), R * 0.05);
    return c;
  }

  makeArrow(s) {
    const B = Math.round(s * 2.4);
    const c = makeCanvas(B, B), g = c.getContext('2d');
    const cx = B / 2, cy = B / 2;
    const body = () => {
      g.beginPath();
      g.moveTo(cx - s * 0.55, cy - s * 0.28);
      g.lineTo(cx + s * 0.05, cy - s * 0.28);
      g.lineTo(cx + s * 0.05, cy - s * 0.55);
      g.lineTo(cx + s * 0.85, cy);
      g.lineTo(cx + s * 0.05, cy + s * 0.55);
      g.lineTo(cx + s * 0.05, cy + s * 0.28);
      g.lineTo(cx - s * 0.55, cy + s * 0.28);
      g.closePath();
    };
    for (let i = 0; i < 6; i++) {
      g.save();
      g.translate(0, s * 0.05 + i * s * 0.012);
      body();
      g.fillStyle = css(argb(22, 0, 0, 0));
      g.fill();
      g.restore();
    }
    body();
    g.fillStyle = linear(g, cx, cy - s, cx, cy + s, rgb(84, 84, 90), rgb(10, 10, 12));
    g.fill();
    body();
    g.strokeStyle = css(rgb(16, 16, 18));
    g.lineWidth = Math.max(2, s * 0.07);
    g.lineJoin = 'round';
    g.stroke();
    fillRR(g, cx - s * 0.5, cy - s * 0.18, s * 0.5, s * 0.10, s * 0.04, css(argb(80, 255, 255, 255)));
    return c;
  }

  makeWheel() {
    const r = this.wheelR();
    const pad = Math.round(r * 0.16);
    const B = Math.round(r * 2 + pad * 2);
    const c = makeCanvas(B, B), g = c.getContext('2d');
    const cx = B / 2, cy = B / 2;
    for (let i = 0; i < 12; i++) {
      const k = i / 12;
      g.beginPath();
      g.arc(cx + r * 0.03, cy + r * 0.07, r * 1.1 + pad * (1 - k) * 0.8, 0, Math.PI * 2);
      g.fillStyle = css(argb(12, 10, 4, 0));
      g.fill();
    }
    g.beginPath(); g.arc(cx, cy, r * 1.10, 0, Math.PI * 2);
    g.fillStyle = linear(g, cx - r, cy - r, cx + r, cy + r, rgb(104, 104, 112), rgb(14, 14, 18));
    g.fill();
    g.beginPath(); g.arc(cx, cy, r * 1.03, 0, Math.PI * 2);
    g.fillStyle = css(rgb(10, 10, 12));
    g.fill();
    for (let i = 0; i < 4; i++) {
      const body = cardColor(COLORS[i]);
      wedge(g, cx, cy, r, -90 + i * 90, 90);
      g.fillStyle = radial(g, cx - r * 0.18, cy - r * 0.22, r * 1.15, shade(body, 1.2), shade(body, 0.76));
      g.fill();
    }
    g.strokeStyle = css(argb(110, 0, 0, 0));
    g.lineWidth = Math.max(3, r * 0.02);
    for (let i = 0; i < 4; i++) {
      const a = (-90 + i * 90) * Math.PI / 180;
      g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r); g.stroke();
    }
    g.strokeStyle = css(argb(60, 255, 255, 255));
    g.lineWidth = r * 0.05;
    arcPath(g, cx, cy, r * 0.9, 200, 140);
    g.stroke();
    for (let i = 0; i < 8; i++) { // ratchet pegs: one tick per peg as the wheel slows
      const a = i * 45 * Math.PI / 180;
      const pxx = cx + Math.cos(a) * r * 1.065, pyy = cy + Math.sin(a) * r * 1.065;
      g.beginPath(); g.arc(pxx, pyy + r * 0.008, r * 0.04, 0, Math.PI * 2);
      g.fillStyle = css(rgb(60, 44, 20)); g.fill();
      g.beginPath(); g.arc(pxx, pyy, r * 0.036, 0, Math.PI * 2);
      g.fillStyle = radial(g, pxx - r * 0.01, pyy - r * 0.012, r * 0.045, rgb(255, 236, 150), rgb(214, 150, 30));
      g.fill();
    }
    const hub = r * 0.20;
    g.beginPath(); g.arc(cx + 3, cy + 5, hub, 0, Math.PI * 2);
    g.fillStyle = css(argb(90, 0, 0, 0)); g.fill();
    g.beginPath(); g.arc(cx, cy, hub, 0, Math.PI * 2);
    g.fillStyle = radial(g, cx - hub * 0.25, cy - hub * 0.3, hub * 1.1, rgb(255, 248, 230), rgb(210, 190, 150));
    g.fill();
    g.strokeStyle = css(rgb(40, 32, 24));
    g.lineWidth = Math.max(3, hub * 0.12);
    g.stroke();
    return c;
  }

  // =====================================================================
  // Game flow (the "director")
  // =====================================================================
  startGame(opp) {
    if (this.transition) return;
    this.sfx.play('sfx_click');
    this.fadeTo(() => {
      // Quick Play leaves match null, so nothing below changes how it plays. A Scoring Game gets fresh totals.
      this.match = this.mode === MODE_SCORING ? new Match(opp + 1, this.targetText) : null;
      this.roundResult = null;
      this.beginRound(opp);
    });
  }

  /** Deals and shows a round. Used for the first round and, in a Scoring Game, every following round. */
  beginRound(opp) {
    this.clearGameTasks();
    this.engine.start(opp);
    this.roundScored = false;
    this.prepareBubbles(opp);
    this.resetVisuals();
    this.screen = 'GAME';
    this.trimScenes(this.tableSceneName(opp));
    this.dealIntro();
  }

  resetVisuals() {
    this.slots.clear();
    this.hidden.clear();
    this.flies.length = 0;
    this.pops.length = 0;
    this.rings.length = 0;
    this.pn = 0;
    this.shownTop = null; this.prevTop = null; this.shownLive = null;
    this.shownCurrent = -1;
    this.arrowFrom = this.arrowTo = 1;
    this.arrowFlipAt = -1e5; this.discardBumpAt = -1e5; this.flashAt = -1e5; this.chipAt = -1e5;
    this.flashMsg = null;
    this.statusShown = ''; this.statusPrev = '';
    this.selected = 0;
    this.dragDy = 0; this.dragUp = false;
    this.holding = false; this.callArmed = false;
    this.colorPickFor = -1; this.wheelPicked = -1;
    this.busy = true; this.dealing = true;
    this.dimAmt = 0;
    for (let i = 0; i < 4; i++) {
      this.shownCount[i] = 0; this.lastShown[i] = 0; this.lowFlashUntil[i] = 0; this.countPopAt[i] = -1e5;
    }
    for (const c of this.engine.hands[0]) this.hidden.add(c);
  }

  dealIntro() {
    const seats = this.engine.seats;
    const step = 38, t0 = 380;
    let k = 0;
    for (let r = 0; r < 7; r++) {
      for (let s = 0; s < seats; s++) {
        const seat = s, round = r;
        this.after(t0 + k * step, () => this.dealOne(seat, round));
        k++;
      }
    }
    const flipAt = t0 + k * step + 140;
    this.after(flipAt, () => this.flipFirstCard());
    this.after(flipAt + 330 + 420, () => { this.dealing = false; this.advanceTurn(); });
  }

  dealOne(seat, round) {
    const { H, W, deckRect, rnd } = this;
    this.sfx.play('sfx_deal', 0.55, 0.92 + rnd() * 0.16);
    const dx = rcx(deckRect), dy = rcy(deckRect);
    if (seat === 0) {
      const card = this.engine.hands[0][round];
      const tgt = this.tgt;
      this.handTarget(round, 7, 0, tgt);
      const f = this.newFly(card, false, true, dx, dy, rw(deckRect), rnd() * 10 - 5,
        tgt[0], tgt[1], tgt[2] * this.cardW, tgt[3], H * 0.035, 0, 340);
      f.onLand = () => this.hidden.delete(card);
      this.flies.push(f);
    } else {
      const sp = this.seatHand(seat);
      const f = this.newFly(null, true, false, dx, dy, rw(deckRect), 0,
        sp[0], sp[1], W * 0.10, rnd() * 30 - 15, H * 0.02, 0, 320);
      f.fadeOut = true;
      f.onLand = () => this.addShown(seat, 1);
      this.flies.push(f);
    }
  }

  flipFirstCard() {
    const top = this.engine.top();
    const { deckRect, discardRect, H } = this;
    const f = this.newFly(top, false, true, rcx(deckRect), rcy(deckRect), rw(deckRect), 0,
      rcx(discardRect), rcy(discardRect), rw(discardRect), this.restRot(top), H * 0.03, 0, 340);
    f.onLand = () => {
      this.shownTop = top;
      this.shownLive = this.engine.liveColor;
      this.discardBumpAt = this.gt;
      this.addRing(rcx(discardRect), rcy(discardRect), rw(discardRect) * 0.9, CREAM);
      this.sfx.play('sfx_play', 0.55, 1);
    };
    this.sfx.play('sfx_draw', 0.7, 1);
    this.flies.push(f);
  }

  advanceTurn() {
    if (this.screen !== 'GAME') return;
    if (this.engine.gameOver) { this.finishGame(); return; }
    this.shownCurrent = this.engine.current;
    if (this.engine.current === 0) this.beginHumanTurn();
    else this.opponentThink(this.engine.current);
  }

  beginHumanTurn() {
    this.busy = false;
    this.humanTurnAt = this.gt;
    this.sfx.play('sfx_turn', 0.9, 1);
  }

  opponentThink(seat) {
    this.busy = true;
    const think = 800 + Math.floor(this.rnd() * 400);
    this.after(think, () => this.opponentAct(seat));
  }

  opponentAct(seat) {
    const e = this.engine;
    if (this.screen !== 'GAME' || e.gameOver || e.current !== seat) return;
    const hand = e.hands[seat];
    const pick = e.computerPick(seat);
    if (pick < 0) { this.oppDraw(seat); return; }
    const c = hand[pick];
    const col = c.isWild() ? e.chooseColor(seat) : c.color;
    const sp = this.seatHand(seat);
    const r = e.play(seat, pick, col);
    let victimCards = null;
    if (!e.gameOver && c.isDraw()) victimCards = this.newestCards(e.lastDrawTarget, c.kind === 'PULL4' ? 4 : 2);
    this.launchPlay(seat, c, r, sp[0], sp[1], this.W * 0.10, this.rnd() * 30 - 15, 440, victimCards, null, true);
  }

  oppDraw(seat) {
    const { deckRect, W, H, rnd } = this;
    this.engine.drawOne(seat);
    this.sfx.play('sfx_draw', 0.8, 1);
    const sp = this.seatHand(seat);
    const f = this.newFly(null, true, false, rcx(deckRect), rcy(deckRect), rw(deckRect), 0,
      sp[0], sp[1], W * 0.10, rnd() * 30 - 15, H * 0.025, 0, 380);
    f.fadeOut = true;
    f.onLand = () => {
      this.addShown(seat, 1);
      if (rnd() * 100 < 45) this.voice(seat, false);
    };
    this.flies.push(f);
    this.after(380 + 380, () => {
      this.engine.passTo(this.engine.nextSeat(seat));
      this.advanceTurn();
    });
  }

  /** Human plays hand[idx]. Wilds pass the colour they chose. */
  humanPlay(idx, chosen, called) {
    const e = this.engine;
    const hand = e.hands[0];
    if (idx < 0 || idx >= hand.length) return;
    const card = hand[idx];
    const mustCall = hand.length === 2;
    const penalty = mustCall && !called;
    const sx = this.hx[idx], sy = this.hy[idx], sw = this.hw[idx], sr = this.hr[idx];
    const r = e.play(0, idx, chosen);
    if (r === 'illegal') { this.sfx.play('sfx_error'); return; }
    this.slots.delete(card);
    this.hidden.delete(card);
    if (this.selected >= hand.length) this.selected = Math.max(0, hand.length - 1);
    this.dragDy = 0; this.dragUp = false;
    this.busy = true;
    this.callArmed = false;
    let victimCards = null;
    if (!e.gameOver && card.isDraw()) victimCards = this.newestCards(e.lastDrawTarget, card.kind === 'PULL4' ? 4 : 2);
    let penaltyCards = null;
    if (penalty && !e.gameOver) {
      e.give(0, 2);
      penaltyCards = this.newestCards(0, 2);
    }
    this.launchPlay(0, card, r, sx, sy, sw, sr, 330, victimCards, penaltyCards, false);
  }

  /** Marks the newest n cards of a human hand as "in flight"; opponents just report null. */
  newestCards(seat, n) {
    if (seat !== 0) return null;
    const hand = this.engine.hands[0];
    const out = [];
    for (let i = Math.max(0, hand.length - n); i < hand.length; i++) {
      out.push(hand[i]);
      this.hidden.add(hand[i]);
    }
    return out;
  }

  launchPlay(seat, card, result, sx, sy, sw, sr, dur, victimCards, penaltyCards, opp) {
    const { H, discardRect, rnd } = this;
    if (seat !== 0) {
      this.shownCount[seat] = Math.max(0, this.shownCount[seat] - 1);
      this.countPopAt[seat] = this.gt;
    }
    this.sfx.play('sfx_whoosh', seat === 0 ? 0.35 : 0.5, 0.95 + rnd() * 0.1);
    const f = this.newFly(card, false, opp, sx, sy, sw, sr,
      rcx(discardRect), rcy(discardRect), rw(discardRect), this.restRot(card), H * (opp ? 0.05 : 0.035), 0, dur);
    f.onLand = () => this.landPlay(seat, card, result, victimCards, penaltyCards);
    this.flies.push(f);
  }

  landPlay(seat, card, result, victimCards, penaltyCards) {
    const e = this.engine;
    const { W, H, discardRect, rnd } = this;
    this.prevTop = this.shownTop;
    this.shownTop = card;
    this.shownLive = e.liveColor;
    this.discardBumpAt = this.gt;
    const ringColor = card.isWild() ? GOLD : cardColor(card.color);
    this.addRing(rcx(discardRect), rcy(discardRect), rw(discardRect) * 1.15, ringColor);
    this.sfx.play('sfx_play', 1, 0.96 + rnd() * 0.08);
    if (seat === 0) this.haptic(1);

    if (result === 'win' || e.gameOver) {
      this.after(560, () => this.finishGame());
      return;
    }

    let end = 150;
    const plainOpp = card.kind === 'NUMBER';
    switch (card.kind) {
      case 'JUMP': {
        const skipped = e.nextSeat(seat);
        this.sfx.play('sfx_jump');
        const pp = this.popPos(skipped);
        this.popup('Skip!', pp[0], pp[1], GOLD);
        if (seat > 0) this.voiceLater(60, seat, true);
        if (skipped > 0) this.voiceLater(300, skipped, false);
        end = 720;
        break;
      }
      case 'SPIN': {
        this.sfx.play('sfx_spin');
        this.arrowFrom = this.arrowTo;
        this.arrowTo = e.dir >= 0 ? 1 : -1;
        this.arrowFlipAt = this.gt;
        this.popup(e.seats === 2 ? 'Play again!' : 'Reverse!', W * 0.5, H * 0.585, GOLD);
        if (seat > 0) this.voiceLater(60, seat, true);
        end = 700;
        break;
      }
      case 'PULL2':
      case 'PULL4': {
        const n = card.kind === 'PULL2' ? 2 : 4;
        const v = e.lastDrawTarget;
        this.sfx.play(card.kind === 'PULL2' ? 'sfx_pull2' : 'sfx_pull4', 1, 1);
        const pp = this.popPos(v);
        this.popup('+' + n, pp[0], pp[1], GOLD);
        if (card.kind === 'PULL4') this.announceColor();
        if (seat > 0) this.voiceLater(60, seat, true);
        end = this.launchPullFlights(v, n, 230, victimCards, true);
        break;
      }
      case 'TOUCH': {
        this.sfx.play('sfx_touch');
        this.announceColor();
        this.sparkle(rcx(discardRect), rcy(discardRect), 26, true);
        if (seat > 0) this.voiceLater(60, seat, true);
        end = 620;
        break;
      }
      default:
        if (seat > 0 && rnd() * 100 < 22) this.voiceLater(70, seat, true);
        break;
    }

    // Opponent drops to one card: the call is automatic.
    if (seat > 0 && e.hands[seat].length === 1) {
      this.triggerTouchOne(seat);
      end = Math.max(end, plainOpp ? 760 : end);
    }

    if (penaltyCards) {
      this.popup('Forgot Touch One!', W * 0.5, H * 0.665, ALERT);
      const pe = this.launchPullFlights(0, 2, Math.max(420, end - 100), penaltyCards, false);
      this.sfx.play('sfx_error', 0.8, 1);
      end = Math.max(end, pe);
    }
    this.after(end + 140, () => this.advanceTurn());
  }

  /** Sends n cards from the deck to a victim, staggered. @returns ms until the last one lands. */
  launchPullFlights(victim, n, startDelay, cards, stinger) {
    const step = n === 2 ? 170 : 125;
    const dur = 340;
    const { deckRect, W, H, rnd } = this;
    for (let k = 0; k < n; k++) {
      const card = cards && k < cards.length ? cards[k] : null;
      this.after(startDelay + k * step, () => {
        this.sfx.play('sfx_deal', 0.6, 0.9 + k * 0.07);
        let f;
        if (victim === 0 && card) {
          const hand = this.engine.hands[0];
          const idx = hand.indexOf(card);
          const tgt = this.tgt;
          this.handTarget(Math.max(0, idx), hand.length, this.selected, tgt);
          f = this.newFly(card, false, true, rcx(deckRect), rcy(deckRect), rw(deckRect), 0,
            tgt[0], tgt[1], tgt[2] * this.cardW, tgt[3], H * 0.03, 0, 340);
          f.onLand = () => {
            this.hidden.delete(card);
            if (k === 0) this.haptic(3);
          };
        } else {
          const sp = this.seatHand(victim);
          f = this.newFly(null, true, false, rcx(deckRect), rcy(deckRect), rw(deckRect), 0,
            sp[0], sp[1], W * 0.10, rnd() * 30 - 15, H * 0.02, 0, 340);
          f.fadeOut = true;
          f.onLand = () => this.addShown(victim, 1);
        }
        this.flies.push(f);
      });
    }
    if (stinger) this.voiceLater(startDelay + 330, victim, false);
    return startDelay + (n - 1) * step + dur;
  }

  announceColor() {
    this.chipText = 'Color is ' + colorName(this.engine.liveColor);
    this.chipColor = cardColor(this.engine.liveColor);
    this.chipAt = this.gt;
  }

  triggerTouchOne(seat) {
    this.flashAt = this.gt;
    this.sfx.play('sfx_touchone');
    this.haptic(2);
    this.sparkle(this.W * 0.5, this.H * 0.44, 22, false);
    if (seat > 0) this.voiceLater(320, seat, true);
  }

  addShown(seat, delta) {
    this.shownCount[seat] = Math.max(0, this.shownCount[seat] + delta);
    this.countPopAt[seat] = this.gt;
    const n = this.shownCount[seat];
    if (this.lastShown[seat] > 3 && n <= 3 && n > 0) this.lowFlashUntil[seat] = this.gt + 900;
    this.lastShown[seat] = n;
  }

  finishGame() {
    if (this.match) { this.finishScoringRound(); return; } // Scoring Game: show the round breakdown instead
    const won = this.engine.playerWon;
    this.fadeTo(() => {
      this.clearGameTasks();
      this.flies.length = 0;
      this.screen = won ? 'WIN' : 'LOSS';
      this.endAt = this.gt;
      this.sfx.play(won ? 'sfx_win' : 'sfx_loss');
      this.trimScenes(won ? 'bg_win' : 'bg_loss');
    });
  }

  fadeTo(mid) {
    this.transition = true;
    this.fadePhase = 1;
    this.fadePhaseAt = this.gt;
    this.afterUi(FADE_OUT_MS, () => {
      mid();
      this.fadePhase = 2;
      this.fadePhaseAt = this.gt;
    });
    this.afterUi(FADE_OUT_MS + FADE_IN_MS, () => {
      this.fadePhase = 0;
      this.transition = false;
    });
  }

  // ---- human actions -----------------------------------------------------
  humanCanAct() {
    return this.screen === 'GAME' && !this.busy && !this.transition && !this.dealing
      && this.engine.current === 0 && this.shownCurrent === 0 && !this.engine.gameOver;
  }

  humanDraw() {
    const e = this.engine;
    if (!e.canDraw()) {
      this.sfx.play('sfx_error', 0.7, 1);
      this.flash('One card from the deck each turn');
      return;
    }
    const c = e.drawOne(0);
    const hand = e.hands[0];
    this.hidden.add(c);
    this.selected = hand.length - 1;
    this.busy = true;
    const tgt = this.tgt;
    this.handTarget(this.selected, hand.length, this.selected, tgt);
    this.sfx.play('sfx_draw');
    const { deckRect, H } = this;
    const f = this.newFly(c, false, true, rcx(deckRect), rcy(deckRect), rw(deckRect), 0,
      tgt[0], tgt[1], tgt[2] * this.cardW, 0, H * 0.04, 0, 400);
    f.onLand = () => {
      this.hidden.delete(c);
      this.busy = false;
      this.sfx.play('sfx_deal', 0.6, 1);
      this.haptic(0);
    };
    this.flies.push(f);
  }

  humanEndTurn() {
    const e = this.engine;
    if (!e.canEndTurn()) {
      this.sfx.play('sfx_error', 0.7, 1);
      this.flash(e.pendingFollowup ? 'Play another card or draw one first' : 'Play a card or draw one first');
      return;
    }
    this.sfx.play('sfx_click');
    e.passTo(e.nextSeat(0));
    this.busy = true;
    this.after(160, () => this.advanceTurn());
  }

  onDiscardTap(called) {
    const hand = this.engine.hands[0];
    if (this.selected < 0 || this.selected >= hand.length) return;
    const card = hand[this.selected];
    if (!this.engine.legal(card)) { this.rejectCard(card); return; }
    if (card.isWild()) { this.openWheel(called); return; }
    this.humanPlay(this.selected, card.color, called);
  }

  rejectCard(card) {
    this.shakeCard = card;
    this.shakeAt = this.gt;
    this.sfx.play('sfx_error');
    this.haptic(3);
    this.flash('Match the color, number or symbol');
  }

  openWheel(called) {
    this.colorPickFor = this.selected;
    this.callArmed = called;
    this.screen = 'COLOR';
    this.wheelAt = this.gt;
    this.wheelTurns = 3 + this.rnd() * 1.2;
    this.wheelPicked = -1;
    this.lastTickStep = Number.MIN_SAFE_INTEGER;
    this.lastTickAt = 0;
    this.sfx.play('sfx_wheel');
  }

  pickColor(idx) {
    this.wheelPicked = idx;
    this.wheelPickAt = this.gt;
    this.sfx.play('sfx_confirm');
    this.haptic(2);
    const chosen = COLORS[idx];
    this.after(340, () => {
      this.screen = 'GAME';
      const sel = this.colorPickFor;
      this.colorPickFor = -1;
      this.wheelPicked = -1;
      this.humanPlay(sel, chosen, this.callArmed);
    });
  }

  cancelWheel() {
    this.screen = 'GAME';
    this.colorPickFor = -1;
    this.callArmed = false;
    this.sfx.play('sfx_click', 0.7, 0.9);
  }

  flash(msg) { this.flashMsg = msg; this.flashMsgUntil = this.gt + 1700; }

  voice(seat, good) {
    const who = this.charVoice(seat);
    if (who) this.sfx.play('sfx_' + who + (good ? '_yes' : '_ohno'), 0.95, 1);
  }

  voiceLater(delay, seat, good) { this.after(delay, () => this.voice(seat, good)); }

  charVoice(seat) {
    if (seat <= 0) return null;
    const opp = this.engine.seats - 1;
    if (seat === 1) return 'boy';
    if (opp === 2 && seat === 2) return 'girl';
    if (opp === 3 && seat === 2) return 'dog';
    if (opp === 3 && seat === 3) return 'girl';
    return null;
  }

  /** 0 tick, 1 tap, 2 confirm, 3 reject. */
  haptic(kind) {
    const h = this.haptics;
    if (!h) return;
    try {
      let p;
      if (kind === 0) p = h.impact({ style: 'LIGHT' });
      else if (kind === 1) p = h.impact({ style: 'MEDIUM' });
      else if (kind === 2) p = h.notification({ type: 'SUCCESS' });
      else p = h.notification({ type: 'ERROR' });
      if (p && p.catch) p.catch(() => {});
    } catch (e) { /* haptics are a nicety */ }
  }

  // =====================================================================
  // Geometry helpers
  // =====================================================================
  glowTable() { const o = this.engine.seats - 1; return o === 1 ? GLOW1 : o === 2 ? GLOW2 : GLOW3; }
  hatTable() { const o = this.engine.seats - 1; return o === 1 ? HATS1 : o === 2 ? HATS2 : HATS3; }

  /** Where an opponent's cards come from / go to (just under their face). */
  seatHand(seat) {
    const g = this.glowTable();
    const i = Math.max(0, Math.min(g.length - 1, seat - 1));
    return [this.W * g[i][0], this.H * (g[i][1] + 0.06)];
  }

  popPos(seat) {
    if (seat <= 0) return [this.W * 0.5, this.H * 0.69];
    const g = this.glowTable();
    const i = Math.max(0, Math.min(g.length - 1, seat - 1));
    return [this.W * g[i][0], this.H * (g[i][1] - 0.02)];
  }

  restRot(c) {
    if (c._rest === undefined) c._rest = (Math.floor(this.rnd() * 16) - 7.5) * 0.9;
    return c._rest;
  }

  /** Target centre / scale / rotation for hand slot i of n with sel selected. out = x, y, scale, rot. */
  handTarget(i, n, sel, out) {
    const { W, H, u, cardW, cardH } = this;
    n = Math.max(1, n);
    const overlap = Math.min(cardW * 0.62, (W * 0.86) / n);
    const total = overlap * (n - 1) + cardW;
    const x0 = (W - total) / 2;
    const y0 = H * 0.792;
    const mid = (n - 1) / 2, di = i - mid;
    if (i === sel) {
      out[0] = x0 + i * overlap + cardW / 2 - 4 * u;
      out[1] = y0 - 12 * u + cardH / 2;
      out[2] = 1; out[3] = 0;
    } else {
      const sc = 0.72;
      out[0] = x0 + i * overlap + cardW * sc / 2;
      out[1] = y0 + 28 * u + cardH * sc / 2 + di * di * cardH * 0.0035;
      out[2] = sc;
      out[3] = di * Math.min(2.6, 12 / n);
    }
  }

  updateHand() {
    const hand = this.engine.hands[0];
    const n = hand.length;
    if (this.selected >= n) this.selected = n - 1;
    if (this.selected < 0) this.selected = 0;
    const a = this.smooth(85);
    const tgt = this.tgt, u = this.u;
    for (let i = 0; i < n; i++) {
      const cd = hand[i];
      this.handTarget(i, n, this.selected, tgt);
      let sl = this.slots.get(cd);
      if (!sl) {
        sl = { x: tgt[0], y: tgt[1], s: tgt[2], rot: tgt[3] };
        this.slots.set(cd, sl);
      } else {
        sl.x += (tgt[0] - sl.x) * a;
        sl.y += (tgt[1] - sl.y) * a;
        sl.s += (tgt[2] - sl.s) * a;
        sl.rot += (tgt[3] - sl.rot) * a;
      }
      let shake = 0;
      if (cd === this.shakeCard) {
        const t = (this.gt - this.shakeAt) / 340;
        if (t < 1) shake = Math.sin(t * 38) * 14 * u * (1 - t);
      }
      this.hx[i] = sl.x + shake;
      this.hy[i] = sl.y + (i === this.selected ? this.dragDy : 0);
      this.hw[i] = sl.s * this.cardW;
      this.hr[i] = sl.rot + (i === this.selected ? this.dragDy * 0.02 : 0);
    }
    if (!this.dragUp) this.dragDy += (0 - this.dragDy) * this.smooth(70);
    const want = this.humanCanAct() ? 1 : 0;
    this.dimAmt += (want - this.dimAmt) * this.smooth(110);
  }

  /**
   * Which card a touch means: chosen by horizontal position (nearest fanned slot), not by the selected card's big
   * rectangle: the selected card covers most of its neighbours, and a tap on the sliver beside it should still pick
   * that neighbour.
   */
  hitHand(x) {
    const n = this.engine.hands[0].length;
    if (n === 0) return -1;
    const { W, cardW } = this;
    const overlap = Math.min(cardW * 0.62, (W * 0.86) / n);
    const x0 = (W - (overlap * (n - 1) + cardW)) / 2;
    let best = 0, bd = Infinity;
    for (let i = 0; i < n; i++) {
      const d = Math.abs(x0 + i * overlap + cardW * 0.36 - x);
      if (d < bd) { bd = d; best = i; }
    }
    return best;
  }

  newFly(card, back, flip, sx, sy, sw, sr, ex, ey, ew, er, arc, delay, dur) {
    return { card, back, flip, sx, sy, sw, sr, ex, ey, ew, er, arc, fadeOut: false,
      start: this.gt + delay, dur, onLand: null };
  }

  // =====================================================================
  // Drawing
  // =====================================================================
  loop() {
    this.raf = 0;
    if (!this.resumed) return;
    this.draw();
    this.raf = requestAnimationFrame(this.loop);
  }

  draw() {
    const g = this.g;
    if (this.W === 0) return;
    this.advanceClock();
    this.runTasks();
    this.checkHoldArmed(); // advances even while the finger is perfectly still
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.globalAlpha = 1;
    g.clearRect(0, 0, this.W, this.H);
    switch (this.screen) {
      case 'SPLASH': this.drawSplash(g); break;
      case 'WIN': this.drawEnd(g, true); break;
      case 'LOSS': this.drawEnd(g, false); break;
      case 'SETUP': this.drawSetup(g); break;
      case 'SCORES': this.drawScores(g); break;
      default:
        this.updateHand();
        this.drawGame(g);
        if (this.screen === 'COLOR') this.drawWheel(g);
        break;
    }
    this.drawFade(g);
  }

  drawScene(g, name) {
    const b = this.scene(name);
    if (b) g.drawImage(b, 0, 0);
    else {
      g.fillStyle = linear(g, 0, 0, 0, this.H, rgb(74, 40, 70), rgb(120, 70, 36));
      g.fillRect(0, 0, this.W, this.H);
    }
  }

  drawFade(g) {
    let a = 0;
    if (this.fadePhase === 1) a = clamp01((this.gt - this.fadePhaseAt) / FADE_OUT_MS);
    else if (this.fadePhase === 2) a = 1 - clamp01((this.gt - this.fadePhaseAt) / FADE_IN_MS);
    if (a <= 0.003) return;
    g.fillStyle = css(argb(Math.round(255 * a), 22, 10, 4));
    g.fillRect(0, 0, this.W, this.H);
  }

  label(g, s, cx, baseline, size, fillC, outline, outlineW, alpha) {
    if (alpha <= 0.01) return;
    g.font = font(size);
    g.textAlign = 'center';
    g.fillStyle = css(withAlpha(argb(120, 20, 8, 0), alpha));
    g.fillText(s, cx, baseline + size * 0.07);
    outlinedText(g, s, cx, baseline, withAlpha(fillC, alpha), withAlpha(outline, alpha), outlineW);
  }

  // ---- splash ------------------------------------------------------------
  drawSplash(g) {
    const { W, H, u, gt } = this;
    this.drawScene(g, 'bg_splash');
    const top = this.setupRect.t;
    const pt = clamp01((gt - this.splashAt - 200) / 400);
    this.label(g, 'How many opponents?', W / 2, top - 0.026 * H, W * 0.056, CREAM, WOOD_INK, W * 0.014, pt);
    // i = 3 is the Setup button; it pops in first, then the 1 / 2 / 3 tiles as before.
    for (let i = 0; i < 4; i++) {
      const b = i === 3 ? this.setupBmp : this.tileBmp[i];
      if (!b) continue;
      const t = clamp01((gt - this.splashAt - (i === 3 ? 190 : 300 + i * 110)) / 460);
      if (t <= 0) continue;
      const sc = easeOutBack(t);
      const bob = Math.sin(gt / 760 + i * 1.3) * 3 * u * t;
      const press = (this.pressId === P_TILE && this.pressTile === i) ? 0.92 : 1;
      const r = i === 3 ? this.setupRect : this.tileRect[i];
      this.drawBitmapScaled(g, b, rcx(r), rcy(r) + bob, sc * press);
    }
  }

  drawBitmapScaled(g, b, cx, cy, s) {
    const w = b.width * s, h = b.height * s;
    g.drawImage(b, cx - w / 2, cy - h / 2, w, h);
  }

  // ---- end screens ---------------------------------------------------------
  drawEnd(g, win) {
    if (win && this.gifOK) {
      // The animated win scene is a DOM <img> underneath this transparent canvas.
    } else {
      this.drawScene(g, win ? 'bg_win' : 'bg_loss');
      if (win) this.drawDanceSheet(g);
    }
    const t = clamp01((this.gt - this.endAt - 520) / 420);
    if (t > 0 && this.againBmp) {
      const pulse = 1 + 0.022 * Math.sin(this.gt / 330);
      const press = this.pressId === P_AGAIN ? 0.94 : 1;
      this.drawBitmapScaled(g, this.againBmp, rcx(this.againRect), rcy(this.againRect), easeOutBack(t) * pulse * press);
    }
  }

  drawDanceSheet(g) {
    const sh = this.sheet;
    if (!sh) return;
    const elapsed = (this.gt - this.endAt) % 2000;
    const frame = Math.floor(elapsed / 133) % 15;
    const col = frame % 5, row = Math.floor(frame / 5);
    const sw = sh.width, shh = sh.height;
    g.drawImage(sh, col * sw / 5, row * shh / 3, sw / 5, shh / 3,
      this.W * 0.18, this.H * 0.53, this.W * 0.64, this.H * 0.43);
  }

  // ---- game --------------------------------------------------------------
  drawGame(g) {
    const opp = this.engine.seats - 1;
    this.drawScene(g, this.tableSceneName(opp));
    this.drawTurnGlow(g, opp);
    this.drawOpponentBubbles(g, opp);
    this.drawPiles(g);
    this.drawDirArrow(g);
    this.drawHand(g);
    this.drawPlayerCloud(g);
    this.drawFlies(g);
    this.drawEndSign(g);
    this.drawStatus(g);
    this.drawRings(g);
    this.drawParticles(g);
    this.drawPops(g);
    this.drawTouchOneBanner(g);
    this.drawHoldRing(g);
    this.drawBack(g);
  }

  drawBack(g) {
    if (this.transition) return;
    const r = this.backRect, h = rh(r);
    const press = this.pressId === P_BACK ? 0.92 : 1;
    g.save();
    g.translate(rcx(r), rcy(r));
    g.scale(press, press);
    fillRR(g, -rw(r) / 2, -h / 2, rw(r), h, h / 2, css(argb(120, 20, 8, 0)));
    strokeRR(g, -rw(r) / 2, -h / 2, rw(r), h, h / 2, css(argb(90, 255, 244, 220)), Math.max(2, this.u * 3));
    g.font = font(this.W * 0.038);
    g.textAlign = 'center';
    g.fillStyle = css(CREAM);
    g.fillText('‹ Menu', 0, centeredBaseline(g, 'Menu', 0));
    g.restore();
  }

  drawTurnGlow(g, opp) {
    const seat = this.shownCurrent;
    if (seat <= 0 || this.engine.gameOver) return;
    const gl = this.glowTable();
    const idx = seat - 1;
    if (idx >= gl.length) return;
    const { W, H } = this;
    const cx = W * gl[idx][0], cy = H * gl[idx][1];
    const base = W * (opp === 1 ? 0.38 : 0.28);
    const b = this.breath(1300);
    const r = base * (0.94 + 0.08 * b);
    g.save();
    g.translate(cx, cy);
    g.scale(r / 100, r / 100);
    g.fillStyle = radial(g, 0, 0, 100, argb(105, 255, 232, 90), argb(0, 255, 200, 40));
    g.beginPath(); g.arc(0, 0, 100, 0, Math.PI * 2); g.fill();
    g.restore();
    g.strokeStyle = css(argb(70 + Math.round(80 * b), 255, 224, 80));
    g.lineWidth = Math.max(5, W * 0.010);
    g.beginPath(); g.arc(cx, cy, r * 0.62, 0, Math.PI * 2); g.stroke();
  }

  countColor(seat, n) {
    if (n > 3 || n <= 0) return WOOD_INK;
    if (this.gt < this.lowFlashUntil[seat]) return (Math.floor(this.gt / 120) % 2 === 0) ? ALERT : WOOD_INK;
    return ALERT;
  }

  popScale(seat) {
    const t = (this.gt - this.countPopAt[seat]) / 320;
    if (t < 0 || t >= 1) return 1;
    return 1 + 0.20 * Math.sin(t * Math.PI);
  }

  drawOpponentBubbles(g, opp) {
    const { W, H } = this;
    const hats = this.hatTable();
    const style = opp === 1 ? STYLE1 : opp === 2 ? STYLE2 : STYLE3;
    for (let i = 0; i < opp; i++) {
      const seat = i + 1;
      const cloud = this.bubble[style[i]];
      const cx = W * hats[i][0], cy = H * hats[i][1];
      const breathe = this.shownCurrent === seat ? 1 + 0.035 * this.breath(1300) : 1;
      const sc = breathe * this.popScale(seat);
      g.save();
      g.translate(cx, cy);
      g.scale(sc, sc);
      let bh = 0;
      if (cloud) {
        bh = cloud.height;
        g.drawImage(cloud, -cloud.width / 2, -cloud.height / 2);
      }
      if (bh === 0) bh = W * 0.2;
      const n = String(this.shownCount[seat]);
      const size = bh * (style[i] === 2 ? 0.44 : 0.52);
      g.font = font(size);
      g.textAlign = 'center';
      g.fillStyle = css(this.countColor(seat, this.shownCount[seat]));
      const cloudCy = style[i] === 2 ? -bh * 0.16 : -bh * 0.08;
      g.fillText(n, 0, centeredBaseline(g, n, cloudCy));
      g.restore();
    }
  }

  drawPlayerCloud(g) {
    const { W, H } = this;
    let n = this.engine.hands[0].length - this.hidden.size;
    if (n < 0) n = 0;
    if (n > 0 && this.shownCount[0] !== n) {
      if (this.lastShown[0] > 3 && n <= 3) this.lowFlashUntil[0] = this.gt + 900;
      this.countPopAt[0] = this.gt;
      this.shownCount[0] = n;
      this.lastShown[0] = n;
    } else if (n === 0) {
      this.shownCount[0] = 0;
    }
    const cx = W * 0.4940, cy = H * 0.728;
    const breathe = (this.shownCurrent === 0 && this.humanCanAct()) ? 1 + 0.03 * this.breath(1300) : 1;
    const sc = breathe * this.popScale(0);
    let bw = W * 0.26, bh = bw * 0.68;
    g.save();
    g.translate(cx, cy);
    g.scale(sc, sc);
    if (this.bubblePlayer) {
      bw = this.bubblePlayer.width;
      bh = this.bubblePlayer.height;
      g.drawImage(this.bubblePlayer, -bw / 2, -bh / 2);
    }
    const s = String(this.shownCount[0]);
    g.font = font(bh * 0.52);
    g.textAlign = 'center';
    g.fillStyle = css(this.countColor(0, this.shownCount[0]));
    g.fillText(s, bw * 0.03, centeredBaseline(g, s, -bh * 0.06));
    g.restore();
  }

  /** alpha multiplies both shadow and card (used by fading opponent-bound cards). */
  drawCardAt(g, b, cx, cy, w, rot, scaleX, lift, overlayAlpha, alpha = 1) {
    const k = w / this.cardW;
    const h = w * 1.4;
    g.save();
    g.translate(cx, cy);
    if (rot !== 0) g.rotate(rot * Math.PI / 180);
    const sh = this.art.shadow();
    const pad = this.art.shadowPad * k;
    const off = h * 0.028 + lift;
    const sx = Math.abs(scaleX);
    g.globalAlpha = (190 / 255) * alpha;
    g.drawImage(sh, -w / 2 * sx - pad, -h / 2 - pad + off, w * sx + pad * 2, h + pad * 2);
    g.globalAlpha = alpha;
    g.drawImage(b, -w / 2 * scaleX, -h / 2, w * scaleX, h);
    if (overlayAlpha > 0) {
      fillRR(g, -w / 2 * scaleX, -h / 2, w * scaleX, h, w * 0.12, css(argb(overlayAlpha, 14, 6, 2)));
    }
    g.restore();
  }

  drawPiles(g) {
    const { W, u, deckRect, discardRect, engine } = this;
    const canDraw = this.humanCanAct() && engine.canDraw();
    const needDraw = canDraw && !engine.hasLegal(0);
    const press = this.pressId === P_DECK ? 0.95 : 1;
    const hint = needDraw ? 1 + 0.035 * this.breath(700) : 1;
    const dw = rw(deckRect) * press * hint;
    const dcx = rcx(deckRect), dcy = rcy(deckRect);
    const back = this.art.back();
    if (needDraw) {
      strokeRR(g, dcx - dw / 2 - 8 * u, dcy - dw * 0.7 - 8 * u, dw + 16 * u, dw * 1.4 + 16 * u, dw * 0.14,
        css(argb(90 + Math.round(110 * this.breath(700)), 255, 224, 80)), Math.max(5, W * 0.009));
    }
    for (let k = 3; k >= 1; k--) this.drawCardAt(g, back, dcx + k * 2.2 * u, dcy - k * 2.2 * u, dw, 0, 1, 0, 70);
    this.drawCardAt(g, back, dcx, dcy, dw, 0, 1, 0, 0);

    // Discard: empty-slot outline, the card underneath, then the top card with a landing bump.
    const cx = rcx(discardRect), cy = rcy(discardRect), dwid = rw(discardRect);
    g.save();
    g.setLineDash(this.dashLen);
    strokeRR(g, cx - dwid / 2, cy - dwid * 0.7, dwid, dwid * 1.4, dwid * 0.12, css(argb(90, 255, 244, 220)), 3 * u);
    g.restore();
    const bt = (this.gt - this.discardBumpAt) / 260;
    const bump = (bt >= 0 && bt < 1) ? 1 + 0.07 * Math.sin(bt * Math.PI) * (1 - bt * 0.4) : 1;
    const hold = this.holding ? 1 + 0.04 * clamp01((this.gt - this.holdStart) / CALL_HOLD_MS) : 1;
    const pr = (this.pressId === P_DISCARD && !this.holding) ? 0.96 : 1;
    if (this.prevTop && this.shownTop) {
      this.drawCardAt(g, this.art.face(this.prevTop, this.shownLive), cx - 3 * u, cy + 2 * u, dwid * 0.98,
        this.restRot(this.prevTop) * -1.4, 1, 0, 60);
    }
    if (this.shownTop) {
      this.drawCardAt(g, this.art.face(this.shownTop, this.shownLive), cx, cy, dwid * bump * hold * pr,
        this.restRot(this.shownTop), 1, 0, 0);
    }
    const hintCall = this.humanCanAct() && engine.hands[0].length === 2 && this.selectedLegal();
    if (hintCall && !this.holding) {
      strokeRR(g, cx - dwid / 2 - 8 * u, cy - dwid * 0.7 - 8 * u, dwid + 16 * u, dwid * 1.4 + 16 * u, dwid * 0.15,
        css(argb(60 + Math.round(110 * this.breath(900)), 255, 224, 80)), Math.max(4, W * 0.007));
    }
    const ls = W * 0.036;
    const dl = engine.drewThisTurn && this.shownCurrent === 0 ? 'Drawn' : 'Draw';
    this.label(g, dl, rcx(deckRect), deckRect.b + 36 * u + ls * 0.4, ls, CREAM, WOOD_INK, W * 0.010, 1);
    this.label(g, 'Discard', rcx(discardRect), discardRect.b + 36 * u + ls * 0.4, ls, CREAM, WOOD_INK, W * 0.010, 1);
  }

  selectedLegal() {
    const hand = this.engine.hands[0];
    return this.selected >= 0 && this.selected < hand.length && this.engine.legal(hand[this.selected]);
  }

  drawDirArrow(g) {
    const b = this.arrowBmp;
    if (!b) return;
    const cx = this.W * 0.50, cy = this.H * 0.635;
    const t = clamp01((this.gt - this.arrowFlipAt) / 520);
    const sx = lerp(this.arrowFrom, this.arrowTo, easeOutBack(t));
    const sy = 1 + 0.18 * Math.sin(t * Math.PI);
    g.save();
    g.translate(cx, cy);
    g.scale(sx, sy);
    g.drawImage(b, -b.width / 2, -b.height / 2);
    g.restore();
  }

  drawHand(g) {
    const hand = this.engine.hands[0];
    const n = hand.length;
    for (let i = 0; i < n; i++) if (i !== this.selected) this.drawHandCard(g, hand, i);
    if (this.selected < n) this.drawHandCard(g, hand, this.selected);
  }

  drawHandCard(g, hand, i) {
    const cd = hand[i];
    if (this.hidden.has(cd)) return;
    let overlay = 0;
    if (this.dimAmt > 0.01 && !this.engine.legal(cd)) overlay = Math.floor(112 * this.dimAmt);
    const lift = i === this.selected ? 6 * this.u : 0;
    this.drawCardAt(g, this.art.face(cd, null), this.hx[i], this.hy[i], this.hw[i], this.hr[i], 1, lift, overlay);
  }

  drawFlies(g) {
    const landed = [];
    for (const f of this.flies) {
      const t = (this.gt - f.start) / f.dur;
      if (t < 0) continue;
      if (t >= 1) { landed.push(f); continue; }
      this.drawFly(g, f, t);
    }
    for (const f of landed) {
      const i = this.flies.indexOf(f);
      if (i >= 0) this.flies.splice(i, 1);
      if (f.onLand) f.onLand();
    }
  }

  drawFly(g, f, t) {
    const e = easeOutCubic(t);
    const arcT = 4 * e * (1 - e);
    const x = lerp(f.sx, f.ex, e);
    const y = lerp(f.sy, f.ey, e) - f.arc * arcT;
    const w = lerp(f.sw, f.ew, e);
    const rot = lerp(f.sr, f.er, e);
    const lift = f.arc * arcT * 0.6;
    let b, scaleX = 1;
    if (f.back || !f.card) b = this.art.back();
    else if (f.flip) {
      const k = Math.cos(Math.PI * t);
      scaleX = Math.max(0.02, Math.abs(k));
      b = t < 0.5 ? this.art.back() : this.art.face(f.card, null);
    } else b = this.art.face(f.card, null);
    const alpha = (f.fadeOut && t > 0.7) ? 1 - (t - 0.7) / 0.3 : 1;
    this.drawCardAt(g, b, x, y, w, rot, scaleX, lift, 0, alpha);
  }

  drawEndSign(g) {
    const b = this.endSignBmp;
    if (!b) return;
    const can = this.humanCanAct() && this.engine.canEndTurn();
    const urge = can && !this.engine.hasLegal(0);
    const press = this.pressId === P_END ? 0.92 : 1;
    const pulse = urge ? 1 + 0.07 * this.breath(650) : 1;
    g.globalAlpha = can ? 1 : 110 / 255;
    this.drawBitmapScaled(g, b, rcx(this.endRect), rcy(this.endRect), press * pulse);
    g.globalAlpha = 1;
  }

  statusTarget() {
    const e = this.engine;
    if (this.flashMsg && this.gt < this.flashMsgUntil) return this.flashMsg;
    if (this.dealing) return 'Dealing';
    if (this.screen === 'COLOR') return '';
    if (this.shownCurrent > 0) {
      const d = Math.floor(this.gt / 340) % 4;
      return 'Opponent is thinking' + '.'.repeat(d);
    }
    if (!this.humanCanAct()) return ''; // a sequence is playing: say nothing rather than something stale
    if (!e.hasLegal(0)) {
      if (e.canDraw()) return 'No match. Draw a card';
      if (e.canEndTurn()) return 'No match. End your turn';
    }
    if (e.hands[0].length === 2 && this.selectedLegal()) return 'Hold the pile to call Touch One';
    if (e.pendingFollowup && !e.drewThisTurn) return 'Play again or draw one';
    if (e.drewThisTurn) return 'Play it or end your turn';
    return 'Your turn';
  }

  drawStatus(g) {
    const { W, H, u, gt } = this;
    const want = this.statusTarget();
    if (want !== this.statusShown) {
      this.statusPrev = this.statusShown;
      this.statusShown = want;
      this.statusChangedAt = gt;
    }
    if (gt - this.flashAt < 1500) return; // the TOUCH ONE banner owns this space
    const fade = clamp01((gt - this.statusChangedAt) / 170);
    const size = W * 0.044;
    const base = H * 0.4875;
    const chipOn = this.chipText && gt - this.chipAt < 1800;
    if (chipOn) {
      const t = clamp01((gt - this.chipAt) / 260);
      const out = clamp01((gt - this.chipAt - 1500) / 300);
      const sc = easeOutBack(t) * (1 - out * 0.1);
      const cw = W * 0.74, chh = H * 0.040, cx = W / 2, cy = H * 0.4745;
      const al = 1 - out;
      g.save();
      g.translate(cx, cy);
      g.scale(sc, sc);
      fillRR(g, -cw / 2, -chh / 2 + 5 * u, cw, chh, chh / 2, css(withAlpha(argb(110, 20, 8, 0), al)));
      g.globalAlpha = al;
      fillRR(g, -cw / 2, -chh / 2, cw, chh, chh / 2,
        linear(g, 0, -chh / 2, 0, chh / 2, shade(this.chipColor, 1.2), shade(this.chipColor, 0.85)));
      g.globalAlpha = 1;
      strokeRR(g, -cw / 2, -chh / 2, cw, chh, chh / 2, css(withAlpha(WHITE, al)), 4 * u);
      g.font = font(size);
      this.label(g, this.chipText, 0, centeredBaseline(g, this.chipText, 0), size, WHITE,
        shade(this.chipColor, 0.45), W * 0.010, al);
      g.restore();
      return;
    }
    if (fade < 1 && this.statusPrev) {
      this.label(g, this.statusPrev, W / 2, base - 8 * u * fade, size, CREAM, WOOD_INK, W * 0.011, 1 - fade);
    }
    this.label(g, this.statusShown, W / 2, base + 8 * u * (1 - fade), size, CREAM, WOOD_INK, W * 0.011, fade);
  }

  // ---- effects -----------------------------------------------------------
  addRing(x, y, maxR, color) { this.rings.push({ x, y, maxR, at: this.gt, color }); }

  drawRings(g) {
    for (let i = this.rings.length - 1; i >= 0; i--) {
      const r = this.rings[i];
      const t = (this.gt - r.at) / 420;
      if (t >= 1) { this.rings.splice(i, 1); continue; }
      const e = easeOutCubic(t);
      g.strokeStyle = css(withAlpha(r.color, (1 - t) * 0.85));
      g.lineWidth = Math.max(2, (1 - e) * 12 * this.u);
      g.beginPath();
      g.arc(r.x, r.y, r.maxR * (0.35 + 0.65 * e), 0, Math.PI * 2);
      g.stroke();
    }
  }

  sparkle(x, y, count, colorful) {
    const { W, u, rnd } = this;
    for (let i = 0; i < count && this.pn < MAX_P; i++) {
      const n = this.pn;
      const a = rnd() * Math.PI * 2;
      const sp = (0.25 + rnd() * 0.55) * W;
      this.px[n] = x; this.py[n] = y;
      this.pvx[n] = Math.cos(a) * sp;
      this.pvy[n] = Math.sin(a) * sp - W * 0.18;
      this.pmax[n] = this.plife[n] = 520 + Math.floor(rnd() * 380);
      this.psize[n] = (7 + Math.floor(rnd() * 9)) * u;
      this.prot[n] = rnd() * 90;
      this.pcol[n] = colorful ? cardColor(COLORS[Math.floor(rnd() * 4)]) : (rnd() < 0.5 ? GOLD : CREAM);
      this.pn++;
    }
  }

  drawParticles(g) {
    const dt = this.frameDt / 1000;
    const W = this.W;
    for (let i = this.pn - 1; i >= 0; i--) {
      this.plife[i] -= this.frameDt;
      if (this.plife[i] <= 0) {
        const last = --this.pn;
        this.px[i] = this.px[last]; this.py[i] = this.py[last];
        this.pvx[i] = this.pvx[last]; this.pvy[i] = this.pvy[last];
        this.plife[i] = this.plife[last]; this.pmax[i] = this.pmax[last];
        this.psize[i] = this.psize[last]; this.prot[i] = this.prot[last]; this.pcol[i] = this.pcol[last];
        continue;
      }
      this.pvy[i] += W * 1.5 * dt;
      this.pvx[i] *= 0.985;
      this.px[i] += this.pvx[i] * dt;
      this.py[i] += this.pvy[i] * dt;
      const life = this.plife[i] / this.pmax[i];
      g.fillStyle = css(withAlpha(this.pcol[i], Math.min(1, life * 2)));
      g.save();
      g.translate(this.px[i], this.py[i]);
      g.rotate((this.prot[i] + (1 - life) * 200) * Math.PI / 180);
      const s = this.psize[i] * (0.4 + 0.6 * life);
      const star = () => {
        g.beginPath();
        g.moveTo(0, -s); g.lineTo(s * 0.35, 0); g.lineTo(0, s); g.lineTo(-s * 0.35, 0);
        g.closePath();
        g.fill();
      };
      star();
      g.rotate(Math.PI / 2);
      star();
      g.restore();
    }
  }

  popup(s, x, y, color) {
    this.pops.push({ text: s, x, y, at: this.gt, dur: 1150, color, size: this.W * 0.075 });
  }

  drawPops(g) {
    for (let i = this.pops.length - 1; i >= 0; i--) {
      const p = this.pops[i];
      const t = (this.gt - p.at) / p.dur;
      if (t >= 1) { this.pops.splice(i, 1); continue; }
      const sc = easeOutBack(clamp01(t * 4));
      const al = t > 0.68 ? 1 - (t - 0.68) / 0.32 : 1;
      const dy = -70 * this.u * easeOutCubic(t);
      g.save();
      g.translate(p.x, p.y + dy);
      g.scale(sc, sc);
      this.label(g, p.text, 0, 0, p.size, p.color, WOOD_INK, this.W * 0.016, al);
      g.restore();
    }
  }

  drawTouchOneBanner(g) {
    const t = this.gt - this.flashAt;
    if (t < 0 || t > 1750) return;
    const { W, H } = this;
    const inn = clamp01(t / 300);
    let sc = 0.5 + 0.5 * easeOutBack(inn);
    if (t > 300) sc *= 1 + 0.018 * Math.sin((t - 300) / 90);
    const al = t > 1450 ? 1 - (t - 1450) / 300 : 1;
    const size = W * 0.15;
    g.save();
    g.translate(W / 2, H * 0.44);
    g.scale(sc, sc);
    g.font = font(size);
    g.textAlign = 'center';
    const s = 'TOUCH ONE';
    const by = centeredBaseline(g, s, 0);
    g.lineJoin = 'round';
    g.lineWidth = size * 0.30;
    g.strokeStyle = css(withAlpha(argb(150, 30, 10, 0), al));
    g.strokeText(s, 0, by + size * 0.05);
    g.lineWidth = size * 0.24;
    g.strokeStyle = css(withAlpha(WOOD_INK, al));
    g.strokeText(s, 0, by);
    g.globalAlpha = al;
    g.fillStyle = linear(g, 0, -size * 0.5, 0, size * 0.5, GOLD, GOLD_DEEP);
    g.fillText(s, 0, by);
    g.globalAlpha = 1;
    g.restore();
  }

  drawHoldRing(g) {
    if (!this.holding) return;
    const p = clamp01((this.gt - this.holdStart) / CALL_HOLD_MS);
    const cx = rcx(this.discardRect), cy = rcy(this.discardRect);
    const r = rw(this.discardRect) * 0.86;
    g.lineCap = 'round';
    g.lineWidth = Math.max(7, this.W * 0.014);
    g.strokeStyle = css(argb(70, 20, 8, 0));
    g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.stroke();
    g.strokeStyle = css(p >= 1 ? WHITE : GOLD);
    arcPath(g, cx, cy, r, -90, 360 * p);
    g.stroke();
    g.lineCap = 'butt';
  }

  // ---- colour wheel ------------------------------------------------------
  drawWheel(g) {
    const { W, H } = this;
    const el = this.gt - this.wheelAt;
    const open = clamp01(el / 260);
    const pickT = this.wheelPicked >= 0 ? clamp01((this.gt - this.wheelPickAt) / 340) : 0;
    g.fillStyle = css(argb(Math.round(170 * open * (1 - pickT * 0.8)), 14, 6, 2));
    g.fillRect(0, 0, W, H);

    const cx = this.wheelCx(), cy = this.wheelCy(), r = this.wheelR();
    const st = clamp01(el / WHEEL_SPIN_MS);
    const rot = (1 - easeOutQuart(st)) * 360 * this.wheelTurns;
    const step = Math.floor((rot + 3600) / 45);
    if (el > 60 && step !== this.lastTickStep && this.gt - this.lastTickAt >= 42 && st < 1) {
      this.sfx.play('sfx_tick', 0.35 + 0.5 * (1 - (1 - st) * (1 - st)), 0.9 + 0.25 * st);
      if (st > 0.55) this.haptic(0);
      this.lastTickAt = this.gt;
    }
    this.lastTickStep = step;

    const sc = easeOutBack(open) * (1 - 0.22 * easeInCubic(pickT));
    const al = 1 - clamp01((pickT - 0.45) / 0.55);
    this.label(g, 'Choose a color', W / 2, cy - r * 1.42, W * 0.066, CREAM, WOOD_INK, W * 0.016, open * al);
    if (this.wheelPicked < 0 && st >= 1) {
      this.label(g, 'Tap outside to cancel', W / 2, cy + r * 1.58, W * 0.038, CREAM, WOOD_INK, W * 0.010, 0.9);
    }
    g.save();
    g.translate(cx, cy);
    g.scale(sc, sc);
    g.rotate(rot * Math.PI / 180);
    g.globalAlpha = al;
    g.drawImage(this.wheelBmp, -this.wheelBmp.width / 2, -this.wheelBmp.height / 2);
    g.globalAlpha = 1;
    if (this.wheelPicked >= 0) {
      for (let i = 0; i < 4; i++) {
        if (i === this.wheelPicked) continue;
        wedge(g, 0, 0, r, -90 + i * 90, 90);
        g.fillStyle = css(argb(Math.round(150 * al), 12, 6, 2));
        g.fill();
      }
      g.lineWidth = r * 0.05;
      g.strokeStyle = css(withAlpha(WHITE, al));
      arcPath(g, 0, 0, r * 0.97, -90 + this.wheelPicked * 90 + 2, 86);
      g.stroke();
    }
    g.restore();
  }

  wheelTap(x, y) {
    if (this.wheelPicked >= 0) return;
    const dx = x - this.wheelCx(), dy = y - this.wheelCy();
    const dist = Math.hypot(dx, dy);
    const r = this.wheelR();
    const el = this.gt - this.wheelAt;
    if (dist > r * 1.38) {
      if (el > 200) this.cancelWheel();
      return;
    }
    if (el < WHEEL_PICKABLE_MS || dist > r * 1.06) return;
    const st = clamp01(el / WHEEL_SPIN_MS);
    const rot = (1 - easeOutQuart(st)) * 360 * this.wheelTurns;
    let a = Math.atan2(dy, dx) * 180 / Math.PI - rot;
    a = (((a + 90) % 360) + 360) % 360;
    this.pickColor(Math.min(3, Math.floor(a / 90)));
  }

  // =====================================================================
  // Input
  // =====================================================================
  select(idx, sound) {
    if (idx === this.selected) return;
    this.selected = idx;
    if (sound && this.gt - this.lastSelectSfx > 60) {
      this.sfx.play('sfx_select', 0.9, 0.96 + this.rnd() * 0.08);
      this.haptic(0);
      this.lastSelectSfx = this.gt;
    }
  }

  attachInput() {
    const c = this.canvas;
    const pos = (e) => {
      const r = c.getBoundingClientRect();
      return [(e.clientX - r.left) * (this.W / r.width), (e.clientY - r.top) * (this.H / r.height)];
    };
    const fwd = (action) => (e) => {
      if (!e.isPrimary || this.W === 0) return;
      e.preventDefault();
      this.sfx.unlock();
      if (action === 'down') { try { c.setPointerCapture(e.pointerId); } catch (err) { /* optional */ } }
      const [x, y] = pos(e);
      this.onTouch(action, x, y);
    };
    c.addEventListener('pointerdown', fwd('down'));
    c.addEventListener('pointermove', fwd('move'));
    c.addEventListener('pointerup', fwd('up'));
    c.addEventListener('pointercancel', fwd('cancel'));
    c.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  onTouch(action, x, y) {
    if (this.transition) {
      if (action === 'up' || action === 'cancel') this.pressId = P_NONE;
      return;
    }
    switch (this.screen) {
      case 'SPLASH': this.touchSplash(action, x, y); break;
      case 'WIN':
      case 'LOSS': this.touchEnd(action, x, y); break;
      case 'SETUP': this.touchSetup(action, x, y); break;
      case 'SCORES': this.touchScores(action, x, y); break;
      case 'COLOR': if (action === 'up') this.wheelTap(x, y); break;
      default: this.touchGame(action, x, y); break;
    }
  }

  touchSplash(action, x, y) {
    const u = this.u;
    if (action === 'down') {
      this.pressId = P_NONE;
      for (let i = 0; i < 4; i++) {
        const r = i === 3 ? this.setupRect : this.tileRect[i];
        if (hit(r, x, y, 6 * u) && this.gt - this.splashAt > (i === 3 ? 350 : 450 + i * 100)) {
          this.pressId = P_TILE;
          this.pressTile = i;
        }
      }
    } else if (action === 'up') {
      const t = this.pressTile;
      const was = this.pressId === P_TILE;
      this.pressId = P_NONE;
      const r = t === 3 ? this.setupRect : t >= 0 ? this.tileRect[t] : null;
      if (was && r && hit(r, x, y, 6 * u)) {
        if (t === 3) this.openSetup(); else this.startGame(t + 1);
      }
    } else if (action === 'cancel') {
      this.pressId = P_NONE;
    }
  }

  touchEnd(action, x, y) {
    const ready = this.gt - this.endAt > END_INPUT_GUARD_MS;
    if (action === 'down') {
      this.pressId = (ready && hit(this.againRect, x, y)) ? P_AGAIN : P_NONE;
    } else if (action === 'up') {
      const was = this.pressId === P_AGAIN;
      this.pressId = P_NONE;
      if (was && hit(this.againRect, x, y)) {
        this.sfx.play('sfx_click');
        this.fadeTo(() => {
          this.screen = 'SPLASH';
          this.splashAt = this.gt;
          this.trimScenes('bg_splash');
        });
      }
    } else if (action === 'cancel') {
      this.pressId = P_NONE;
    }
  }

  touchGame(action, x, y) {
    const { u, H, W } = this;
    const e = this.engine;
    switch (action) {
      case 'down': {
        this.downX = x; this.downY = y;
        this.pressId = P_NONE;
        this.scrubbed = false;
        this.dragUp = false;
        if (hit(this.backRect, x, y, 6 * u)) { this.pressId = P_BACK; return; }
        if (!this.humanCanAct()) return;
        if (hit(this.endRect, x, y, 10 * u)) {
          this.pressId = P_END;
        } else if (hit(this.deckRect, x, y, 8 * u)) {
          this.pressId = P_DECK;
        } else if (hit(this.discardRect, x, y, 8 * u)) {
          this.pressId = P_DISCARD;
          if (e.hands[0].length === 2 && this.selectedLegal()) {
            this.holding = true;
            this.callArmed = false;
            this.holdStart = this.gt;
            this.holdStream = this.sfx.play('sfx_hold');
          }
        } else if (y > H * 0.70) {
          this.pressId = P_HAND;
          const idx = this.hitHand(x);
          if (idx >= 0) this.select(idx, true);
          this.dragStartSel = this.selected;
        }
        return;
      }
      case 'move': {
        if (this.holding) {
          if (!hit(this.discardRect, x, y, 24 * u)) {
            this.holding = false;
            if (this.holdStream) this.holdStream.stop();
          } else this.checkHoldArmed();
          return;
        }
        if (this.pressId === P_HAND && this.humanCanAct()) {
          const dx = x - this.downX, dy = y - this.downY;
          const n = e.hands[0].length;
          if (!this.dragUp && !this.scrubbed && dy < -0.035 * H && Math.abs(dx) < 0.07 * W) this.dragUp = true;
          if (this.dragUp) {
            this.dragDy = Math.max(-0.30 * H, Math.min(0, dy));
          } else if (n > 0) {
            const step = Math.max(16, W * 0.048);
            const next = Math.max(0, Math.min(n - 1, this.dragStartSel + Math.round(dx / step)));
            if (Math.abs(dx) > 16 * u) this.scrubbed = true;
            this.select(next, true);
          }
        }
        return;
      }
      case 'cancel': {
        if (this.holding) {
          this.holding = false;
          if (this.holdStream) this.holdStream.stop();
        }
        this.pressId = P_NONE;
        this.dragUp = false;
        return;
      }
      case 'up': {
        const was = this.pressId;
        this.pressId = P_NONE;
        if (was === P_BACK) {
          if (hit(this.backRect, x, y, 6 * u)) this.onBack();
          return;
        }
        if (this.holding) {
          this.checkHoldArmed();
          this.holding = false;
          if (this.holdStream) this.holdStream.stop();
          if (this.humanCanAct()) this.onDiscardTap(this.callArmed);
          this.callArmed = false;
          return;
        }
        if (!this.humanCanAct()) { this.dragUp = false; return; }
        if (was === P_HAND) {
          const fling = this.dragUp && this.dragDy < -0.07 * H;
          this.dragUp = false;
          if (fling) this.onDiscardTap(false);
        } else if (was === P_END && hit(this.endRect, x, y, 10 * u)) {
          this.humanEndTurn();
        } else if (was === P_DECK && hit(this.deckRect, x, y, 8 * u)) {
          this.humanDraw();
        } else if (was === P_DISCARD && hit(this.discardRect, x, y, 8 * u)) {
          this.onDiscardTap(false);
        }
        return;
      }
      default:
    }
  }

  checkHoldArmed() {
    if (!this.holding || this.callArmed) return;
    if (this.gt - this.holdStart >= CALL_HOLD_MS) {
      this.callArmed = true;
      if (this.holdStream) this.holdStream.stop();
      this.triggerTouchOne(0);
    }
  }
}

// Setup screen + Scoring Game scoreboard (kept in screens.js so this file stays focused on the table).
Object.assign(TableView.prototype, screenMethods);
