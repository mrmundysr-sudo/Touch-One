// Card faces drawn once into cached canvases (port of CardArt.java). A frame is then just a few blits.
import {
  argb, rgb, css, cardColor, shade, CREAM, font, makeCanvas, scaleSmooth, rr, fillRR, strokeRR, oval,
  linear, radial, wedge, arcPath, centeredBaseline, outlinedText, redOf, greenOf, blueOf,
} from './gfx.js';

const MAX_FACES = 80;

export class CardArt {
  constructor(images, cardW, cardH) {
    this.images = images;
    this.w = cardW;
    this.h = cardH;
    this.shadowPad = Math.max(6, Math.round(cardW * 0.08));
    this.faces = new Map();
    this._back = null;
    this._shadow = null;
  }

  back() {
    if (!this._back) {
      const src = this.images.card_back;
      if (src) this._back = scaleSmooth(src, this.w, this.h);
      else {
        this._back = makeCanvas(this.w, this.h);
        const g = this._back.getContext('2d');
        fillRR(g, 0, 0, this.w, this.h, this.w * 0.12, css(rgb(16, 16, 22)));
      }
    }
    return this._back;
  }

  /** Soft blurred drop shadow sized for a full card plus padding on every side. */
  shadow() {
    if (!this._shadow) {
      const w = this.w, pad = this.shadowPad;
      const sw = w + pad * 2, sh = this.h + pad * 2;
      const c = makeCanvas(sw, sh);
      const g = c.getContext('2d');
      const layers = 14, r = w * 0.12;
      for (let i = 0; i < layers; i++) {
        const inset = pad * (i / layers);
        const rad = r + (pad - inset) * 0.6;
        fillRR(g, inset, inset, sw - 2 * inset, sh - 2 * inset, rad, css(argb(11, 10, 4, 0)));
      }
      this._shadow = c;
    }
    return this._shadow;
  }

  /** live: colour a wild takes once played; null shows the wild's own face. */
  face(card, live) {
    const liveKey = card.isWild() ? live : null;
    const key = `${card.kind}:${card.color}:${card.number}:${liveKey}`;
    let b = this.faces.get(key);
    if (!b) {
      b = makeCanvas(this.w, this.h);
      this.drawFace(b.getContext('2d'), card, liveKey);
      this.faces.set(key, b);
      if (this.faces.size > MAX_FACES) this.faces.delete(this.faces.keys().next().value);
    }
    return b;
  }

  drawFace(g, card, live) {
    const W = this.w, H = this.h;
    const wild = card.isWild();
    const wildFace = wild && !live;
    const base = wildFace ? rgb(36, 36, 46) : cardColor(wild ? live : card.color);
    const deep = wildFace ? rgb(20, 20, 26) : shade(base, 0.42);
    const r = W * 0.12;

    // Black card border with a faint rim light.
    fillRR(g, 0, 0, W, H, r, linear(g, 0, 0, 0, H, rgb(34, 34, 42), rgb(8, 8, 12)));
    strokeRR(g, W * 0.006, W * 0.006, W - W * 0.012, H - W * 0.012, r, css(argb(60, 255, 255, 255)),
      Math.max(1.5, W * 0.010));

    // Colour plate: diagonal gradient, soft vignette, top gloss, inner keyline.
    const inset = W * 0.065;
    const px = inset, py = inset, pw = W - inset * 2, ph = H - inset * 2;
    const ir = r * 0.62;
    fillRR(g, px, py, pw, ph, ir, linear(g, px, py, px + pw, py + ph, shade(base, 1.24), shade(base, 0.80)));
    fillRR(g, px, py, pw, ph, ir, radial(g, W / 2, H * 0.46, H * 0.66, argb(0, 0, 0, 0), argb(80, 0, 0, 0)));
    fillRR(g, px, py, pw, ph, ir, linear(g, 0, py, 0, py + ph * 0.55, argb(70, 255, 255, 255), argb(0, 255, 255, 255)));
    strokeRR(g, px + 1.5, py + 1.5, pw - 3, ph - 3, ir,
      css(argb(140, redOf(deep), greenOf(deep), blueOf(deep))), Math.max(1.5, W * 0.012));

    // Cream oval with a soft inner ring and a hint of depth.
    const ox = W * 0.20, oy = H * 0.255;
    const ol = ox, ot = oy, orr = W - ox, ob = H - oy;
    const ow = orr - ol, oh = ob - ot, ocx = (ol + orr) / 2, ocy = (ot + ob) / 2;
    oval(g, ol, ot + H * 0.012, orr, ob + H * 0.012);
    g.fillStyle = css(argb(70, 0, 0, 0));
    g.fill();
    oval(g, ol, ot, orr, ob);
    g.fillStyle = radial(g, ocx - ow * 0.12, ocy - oh * 0.18, oh * 0.75, rgb(255, 252, 242), rgb(250, 228, 190));
    g.fill();
    oval(g, ol, ot, orr, ob);
    g.strokeStyle = css(argb(190, redOf(deep), greenOf(deep), blueOf(deep)));
    g.lineWidth = Math.max(2, W * 0.016);
    g.stroke();

    const cx = W / 2, cy = H / 2;
    switch (card.kind) {
      case 'JUMP': this.drawJump(g, cx, cy, W * 0.19, W * 0.062, deep); break;
      case 'SPIN': this.drawSpin(g, cx, cy, W * 0.19, W * 0.055, deep); break;
      case 'TOUCH':
      case 'PULL4':
        if (wildFace) this.drawWildWheel(g, ocx, ocy, ow, oh, card.kind === 'TOUCH' ? 'T1' : '+4');
        else this.glyph(g, card.label(), cx, cy, H * 0.25, deep);
        break;
      case 'PULL2': this.glyph(g, '+2', cx, cy, H * 0.25, deep); break;
      default:
        this.glyph(g, card.label(), cx, cy, H * 0.40, deep);
        if (card.number === 6 || card.number === 9) {
          this.underline(g, cx, ob - oh * 0.17, W * 0.15, W * 0.025, deep);
        }
        break;
    }

    // Corner marks, identical top-left and (rotated) bottom-right.
    this.drawPip(g, card, W * 0.205, H * 0.14, deep);
    g.save();
    g.translate(W / 2, H / 2);
    g.rotate(Math.PI);
    g.translate(-W / 2, -H / 2);
    this.drawPip(g, card, W * 0.205, H * 0.14, deep);
    g.restore();
  }

  glyph(g, s, cx, cy, size, color) {
    g.font = font(size);
    g.textAlign = 'center';
    const by = centeredBaseline(g, s, cy);
    g.fillStyle = css(argb(46, 0, 0, 0));
    g.fillText(s, cx, by + size * 0.035);
    g.fillStyle = css(color);
    g.fillText(s, cx, by);
  }

  drawPip(g, card, cx, cy, deep) {
    const W = this.w, H = this.h;
    if (card.kind === 'JUMP') {
      this.drawJump(g, cx, cy, W * 0.062, W * 0.036, argb(200, 20, 10, 5));
      this.drawJump(g, cx, cy, W * 0.062, W * 0.022, CREAM);
      return;
    }
    if (card.kind === 'SPIN') {
      this.drawSpin(g, cx, cy, W * 0.062, W * 0.032, argb(200, 20, 10, 5));
      this.drawSpin(g, cx, cy, W * 0.062, W * 0.019, CREAM);
      return;
    }
    let pip;
    if (card.kind === 'NUMBER') pip = String(card.number);
    else if (card.kind === 'PULL2') pip = '+2';
    else if (card.kind === 'PULL4') pip = '+4';
    else pip = '1';
    if (card.kind === 'NUMBER' && (card.number === 6 || card.number === 9)) {
      this.underline(g, cx, cy + H * 0.075, W * 0.075, W * 0.016, CREAM);
    }
    const size = H * (pip.length > 1 ? 0.105 : 0.125);
    g.font = font(size);
    const by = centeredBaseline(g, pip, cy);
    outlinedText(g, pip, cx, by, CREAM, argb(210, redOf(deep), greenOf(deep), blueOf(deep)), W * 0.030);
  }

  underline(g, cx, y, len, thick, color) {
    fillRR(g, cx - len / 2, y - thick / 2, len, thick, thick / 2, css(color));
  }

  drawJump(g, cx, cy, r, stroke, color) {
    g.strokeStyle = css(color);
    g.lineWidth = stroke;
    g.lineCap = 'round';
    g.beginPath();
    g.arc(cx, cy, r, 0, Math.PI * 2);
    g.stroke();
    const d = r * 0.70;
    g.beginPath();
    g.moveTo(cx - d, cy + d);
    g.lineTo(cx + d, cy - d);
    g.stroke();
    g.lineCap = 'butt';
  }

  drawSpin(g, cx, cy, r, stroke, color) {
    this.arcArrow(g, cx, cy, r, 200, 125, stroke, color);
    this.arcArrow(g, cx, cy, r, 20, 125, stroke, color);
  }

  /** A clockwise arc ending in a solid arrowhead aligned to the arc's tangent. */
  arcArrow(g, cx, cy, r, startDeg, sweep, stroke, color) {
    g.strokeStyle = css(color);
    g.lineWidth = stroke;
    g.lineCap = 'round';
    arcPath(g, cx, cy, r, startDeg, sweep);
    g.stroke();
    const e = (startDeg + sweep) * Math.PI / 180;
    const px = cx + Math.cos(e) * r, py = cy + Math.sin(e) * r;
    const tx = -Math.sin(e), ty = Math.cos(e);
    const nx = Math.cos(e), ny = Math.sin(e);
    const hl = stroke * 2.1, hw = stroke * 1.55;
    g.fillStyle = css(color);
    g.beginPath();
    g.moveTo(px + tx * hl, py + ty * hl);
    g.lineTo(px + nx * hw, py + ny * hw);
    g.lineTo(px - nx * hw, py - ny * hw);
    g.closePath();
    g.fill();
    g.lineCap = 'butt';
  }

  drawWildWheel(g, cx, cy, ow, oh, label) {
    const rr_ = Math.min(ow, oh) * 0.41;
    const cols = ['GREEN', 'PURPLE', 'PINK', 'ORANGE'];
    for (let i = 0; i < 4; i++) {
      const col = cardColor(cols[i]);
      wedge(g, cx, cy, rr_, -90 + i * 90, 90);
      g.fillStyle = radial(g, cx - rr_ * 0.3, cy - rr_ * 0.35, rr_ * 1.5, shade(col, 1.18), shade(col, 0.82));
      g.fill();
    }
    g.strokeStyle = css(argb(150, 20, 10, 5));
    g.lineWidth = Math.max(2, rr_ * 0.05);
    for (let i = 0; i < 4; i++) {
      const a = (-90 + i * 90) * Math.PI / 180;
      g.beginPath();
      g.moveTo(cx, cy);
      g.lineTo(cx + Math.cos(a) * rr_, cy + Math.sin(a) * rr_);
      g.stroke();
    }
    g.beginPath();
    g.arc(cx, cy, rr_, 0, Math.PI * 2);
    g.stroke();
    const hub = rr_ * 0.56;
    g.beginPath();
    g.arc(cx, cy, hub, 0, Math.PI * 2);
    g.fillStyle = css(CREAM);
    g.fill();
    g.strokeStyle = css(argb(200, 20, 10, 5));
    g.lineWidth = Math.max(2, rr_ * 0.06);
    g.stroke();
    this.glyph(g, label, cx, cy, hub * 1.05, rgb(24, 20, 28));
  }
}
