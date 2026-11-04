// Palette + small canvas helpers shared by the card renderer and the table. Colours are 0xAARRGGBB ints,
// exactly like the Android Theme/Gfx classes, so the maths (shade/mix/alpha) ports one-to-one.

export const argb = (a, r, g, b) => (((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255)) >>> 0;
export const rgb = (r, g, b) => argb(255, r, g, b);
export const alphaOf = (c) => c >>> 24;
export const redOf = (c) => (c >>> 16) & 255;
export const greenOf = (c) => (c >>> 8) & 255;
export const blueOf = (c) => c & 255;
export const css = (c) => `rgba(${redOf(c)},${greenOf(c)},${blueOf(c)},${(alphaOf(c) / 255).toFixed(4)})`;

export const WHITE = rgb(255, 255, 255);
export const CREAM = rgb(255, 244, 220);
export const GOLD = rgb(255, 214, 64);
export const GOLD_DEEP = rgb(255, 160, 24);
export const WOOD_INK = rgb(58, 30, 12);
export const ALERT = rgb(226, 38, 38);

const clampi = (v) => Math.min(255, Math.max(0, v | 0));

export function cardColor(c) {
  switch (c) {
    case 'GREEN': return rgb(34, 197, 74);
    case 'PURPLE': return rgb(147, 51, 234);
    case 'PINK': return rgb(236, 72, 153);
    case 'ORANGE': return rgb(249, 115, 22);
    case null: case undefined: return rgb(30, 41, 59);
    default: return rgb(128, 128, 128);
  }
}

export function colorName(c) {
  switch (c) {
    case 'GREEN': return 'Green';
    case 'PURPLE': return 'Purple';
    case 'PINK': return 'Pink';
    case 'ORANGE': return 'Orange';
    default: return '';
  }
}

export const shade = (c, mul) => rgb(clampi(redOf(c) * mul), clampi(greenOf(c) * mul), clampi(blueOf(c) * mul));

export function withAlpha(c, a) {
  const al = Math.min(255, Math.max(0, Math.round(alphaOf(c) * a)));
  return argb(al, redOf(c), greenOf(c), blueOf(c));
}

export function mix(a, b, t) {
  return argb(alphaOf(a) + (alphaOf(b) - alphaOf(a)) * t, redOf(a) + (redOf(b) - redOf(a)) * t,
    greenOf(a) + (greenOf(b) - greenOf(a)) * t, blueOf(a) + (blueOf(b) - blueOf(a)) * t);
}

export const FONT = 'Outfit, "Avenir Next Rounded", "Avenir Next", system-ui, sans-serif';
export const font = (size) => `700 ${size}px ${FONT}`;

export function makeCanvas(w, h) {
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w));
  c.height = Math.max(1, Math.round(h));
  return c;
}

/** High-quality resize: shrink in halves first (a single pass aliases when shrinking by more than 2x). */
export function scaleSmooth(src, tw, th) {
  tw = Math.max(1, Math.round(tw));
  th = Math.max(1, Math.round(th));
  let sw = src.naturalWidth || src.width, sh = src.naturalHeight || src.height;
  if (sw === tw && sh === th && !(src instanceof HTMLImageElement)) return src;
  let cur = src;
  while (sw > tw * 2 && sh > th * 2) {
    const next = makeCanvas(Math.floor(sw / 2), Math.floor(sh / 2));
    const g = next.getContext('2d');
    g.imageSmoothingEnabled = true;
    g.imageSmoothingQuality = 'high';
    g.drawImage(cur, 0, 0, next.width, next.height);
    cur = next; sw = next.width; sh = next.height;
  }
  const out = makeCanvas(tw, th);
  const g = out.getContext('2d');
  g.imageSmoothingEnabled = true;
  g.imageSmoothingQuality = 'high';
  g.drawImage(cur, 0, 0, tw, th);
  return out;
}

/** Rounded-rect path (own implementation: ctx.roundRect is missing on older WebKit). */
export function rr(g, x, y, w, h, r) {
  r = Math.max(0, Math.min(r, w / 2, h / 2));
  g.beginPath();
  g.moveTo(x + r, y);
  g.lineTo(x + w - r, y);
  g.arc(x + w - r, y + r, r, -Math.PI / 2, 0);
  g.lineTo(x + w, y + h - r);
  g.arc(x + w - r, y + h - r, r, 0, Math.PI / 2);
  g.lineTo(x + r, y + h);
  g.arc(x + r, y + h - r, r, Math.PI / 2, Math.PI);
  g.lineTo(x, y + r);
  g.arc(x + r, y + r, r, Math.PI, Math.PI * 1.5);
  g.closePath();
}

export const fillRR = (g, x, y, w, h, r, style) => { rr(g, x, y, w, h, r); g.fillStyle = style; g.fill(); };
export const strokeRR = (g, x, y, w, h, r, style, lw) => {
  rr(g, x, y, w, h, r); g.strokeStyle = style; g.lineWidth = lw; g.stroke();
};

export function oval(g, l, t, r, b) {
  g.beginPath();
  g.ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2, 0, 0, Math.PI * 2);
}

export const linear = (g, x0, y0, x1, y1, c0, c1) => {
  const gr = g.createLinearGradient(x0, y0, x1, y1);
  gr.addColorStop(0, css(c0));
  gr.addColorStop(1, css(c1));
  return gr;
};

export const radial = (g, cx, cy, r, c0, c1) => {
  const gr = g.createRadialGradient(cx, cy, 0, cx, cy, Math.max(0.001, r));
  gr.addColorStop(0, css(c0));
  gr.addColorStop(1, css(c1));
  return gr;
};

/** Pie wedge from the centre, angles in degrees clockwise from 3 o'clock (Android drawArc with useCenter). */
export function wedge(g, cx, cy, r, startDeg, sweepDeg) {
  g.beginPath();
  g.moveTo(cx, cy);
  g.arc(cx, cy, r, startDeg * Math.PI / 180, (startDeg + sweepDeg) * Math.PI / 180);
  g.closePath();
}

export function arcPath(g, cx, cy, r, startDeg, sweepDeg) {
  g.beginPath();
  g.arc(cx, cy, r, startDeg * Math.PI / 180, (startDeg + sweepDeg) * Math.PI / 180);
}

/** Baseline y that visually centres s on cy (ctx.font must already be set). */
export function centeredBaseline(g, s, cy) {
  g.textBaseline = 'alphabetic';
  const m = g.measureText(s);
  return cy + (m.actualBoundingBoxAscent - m.actualBoundingBoxDescent) / 2;
}

/** Outline + fill text centred on cx at the given baseline. */
export function outlinedText(g, s, cx, baseline, fill, outline, outlineWidth) {
  g.textAlign = 'center';
  g.textBaseline = 'alphabetic';
  if (outlineWidth > 0 && alphaOf(outline) > 0) {
    g.lineWidth = outlineWidth;
    g.lineJoin = 'round';
    g.strokeStyle = css(outline);
    g.strokeText(s, cx, baseline);
  }
  g.fillStyle = css(fill);
  g.fillText(s, cx, baseline);
}

export const clamp01 = (t) => (t < 0 ? 0 : t > 1 ? 1 : t);
export const lerp = (a, b, t) => a + (b - a) * t;
export const easeOutCubic = (t) => { const k = 1 - t; return 1 - k * k * k; };
export const easeOutQuart = (t) => { const k = 1 - t; return 1 - k * k * k * k; };
export const easeInCubic = (t) => t * t * t;
export const easeOutBack = (t) => { const c1 = 1.70158, c3 = c1 + 1, k = t - 1; return 1 + c3 * k * k * k + c1 * k * k; };
