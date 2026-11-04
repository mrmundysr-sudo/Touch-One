// Sound effects via Web Audio (port of Sfx.java). A sound only plays once its buffer has decoded, and every play
// returns a handle so a looping/charging sound (the hold-to-call charge) can be stopped early.

const NAMES = [
  'sfx_select', 'sfx_click', 'sfx_tick', 'sfx_deal', 'sfx_draw', 'sfx_whoosh',
  'sfx_play', 'sfx_error', 'sfx_turn', 'sfx_pull2', 'sfx_pull4', 'sfx_spin',
  'sfx_jump', 'sfx_touch', 'sfx_wheel', 'sfx_confirm', 'sfx_hold',
  'sfx_touchone', 'sfx_win', 'sfx_loss',
  'sfx_boy_yes', 'sfx_boy_ohno', 'sfx_girl_yes', 'sfx_girl_ohno',
  'sfx_dog_yes', 'sfx_dog_ohno',
];

export class Sfx {
  constructor(base = 'snd/') {
    const AC = window.AudioContext || window.webkitAudioContext;
    this.ctx = AC ? new AC() : null;
    this.buffers = new Map();
    this.base = base;
    this.master = null;
    if (this.ctx) {
      this.master = this.ctx.createGain();
      this.master.connect(this.ctx.destination);
    }
    // iOS: let game audio play even with the ringer switch on silent (Safari 16.4+).
    try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch (e) { /* optional */ }
  }

  /** Call from a user gesture: browsers (and WKWebView) keep the context suspended until then. */
  unlock() {
    if (this.ctx && this.ctx.state !== 'running') this.ctx.resume().catch(() => {});
  }

  suspend() { if (this.ctx && this.ctx.state === 'running') this.ctx.suspend().catch(() => {}); }
  resume() { if (this.ctx && this.ctx.state === 'suspended') this.ctx.resume().catch(() => {}); }

  async load() {
    if (!this.ctx) return;
    await Promise.all(NAMES.map(async (n) => {
      try {
        const res = await fetch(`${this.base}${n}.wav`);
        const data = await res.arrayBuffer();
        // Callback form: older WebKit has no promise-returning decodeAudioData.
        const buf = await new Promise((ok, fail) => this.ctx.decodeAudioData(data, ok, fail));
        this.buffers.set(n, buf);
      } catch (e) { /* a missing sound is silent, never fatal */ }
    }));
  }

  /** @returns a handle with stop(), or null if the sound wasn't ready. */
  play(name, volume = 1, rate = 1) {
    const buf = this.buffers.get(name);
    if (!this.ctx || !buf || this.ctx.state !== 'running') return null;
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.playbackRate.value = Math.max(0.5, Math.min(2, rate));
    const gain = this.ctx.createGain();
    gain.gain.value = Math.max(0, Math.min(1, volume));
    src.connect(gain);
    gain.connect(this.master);
    src.start();
    return { stop: () => { try { src.stop(); } catch (e) { /* already ended */ } } };
  }
}
