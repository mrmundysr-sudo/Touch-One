#!/usr/bin/env python3
"""
Regenerates every Touch One sound effect into res/raw and cleans up the
character voice clips.

    python3 scripts/make_sounds.py source/app/src/main/res/raw

Design notes
------------
* Everything is 44.1 kHz / 16-bit / mono so SoundPool never resamples.
* Card sounds are built from physical parts (paper slap, wood body, air
  swish) rather than beeps, so they feel like real cards on a wooden table.
* Musical sounds use marimba and bell partials (warm, wooden, friendly),
  matching the garden-table artwork.
* Every effect has a real attack and decay, a fade-out to silence (no clicks),
  and a light room reverb so nothing sounds dry or "plasticky".
* Levels are set here (not in code) so the mix is consistent: impacts are
  loudest, UI ticks are quiet, the Touch One stinger and win fanfare lead.
"""
import os
import shutil
import sys
import wave

import numpy as np
from scipy import signal

SR = 44100
rng = np.random.default_rng(20260714)


# ---------------------------------------------------------------- primitives
def tt(dur):
    return np.arange(int(SR * dur)) / SR


def noise(dur):
    return rng.standard_normal(int(SR * dur))


def env(dur, attack=0.002, tau=0.05):
    t = tt(dur)
    a = np.minimum(1.0, t / max(attack, 1e-4))
    return a * np.exp(-t / tau)


def bandpass(x, lo, hi, order=2):
    hi = min(hi, SR * 0.45)
    sos = signal.butter(order, [lo, hi], "band", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def lowpass(x, fc, order=2):
    sos = signal.butter(order, min(fc, SR * 0.45), "low", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def highpass(x, fc, order=2):
    sos = signal.butter(order, fc, "high", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def fade(x, fin=0.002, fout=0.02):
    x = x.copy()
    n_in, n_out = int(SR * fin), int(SR * fout)
    if n_in:
        x[:n_in] *= np.linspace(0, 1, n_in)
    if n_out:
        x[-n_out:] *= np.linspace(1, 0, n_out)
    return x


def sweep_noise(dur, f0, f1, width=1.2, shape=None, fmid=None):
    """Noise whose spectral centre glides f0 -> f1 (optionally via fmid).
    Done in the STFT domain so the motion is smooth, not stepped."""
    n = int(SR * dur)
    x = rng.standard_normal(n)
    nper = 1024
    f, tm, Z = signal.stft(x, SR, nperseg=nper, noverlap=nper * 3 // 4)
    u = np.linspace(0, 1, len(tm))
    if fmid is None:
        centre = f0 * (f1 / f0) ** u
    else:
        centre = np.where(u < 0.5,
                          f0 * (fmid / f0) ** (u * 2),
                          fmid * (f1 / fmid) ** ((u - 0.5) * 2))
    ff = np.maximum(f, 20)[:, None]
    mask = np.exp(-0.5 * (np.log2(ff / centre[None, :]) / (width / 2)) ** 2)
    _, y = signal.istft(Z * mask, SR, nperseg=nper, noverlap=nper * 3 // 4)
    y = y[:n] if len(y) >= n else np.pad(y, (0, n - len(y)))
    if shape is None:
        shape = np.sin(np.pi * np.linspace(0, 1, n)) ** 1.5
    return y * shape / (np.abs(y).max() + 1e-9)


def reverb(x, rt60=0.35, wet=0.15, predelay=0.010, damp=6000):
    n = int(SR * rt60 * 1.3)
    t = np.arange(n) / SR
    ir = rng.standard_normal(n) * np.exp(-6.9 * t / rt60)
    ir = lowpass(ir, damp)
    ir[: int(SR * predelay)] = 0
    ir /= np.sqrt((ir ** 2).sum()) + 1e-9
    wetsig = signal.fftconvolve(x, ir)
    out = np.zeros(len(wetsig))
    out[: len(x)] += x * (1 - wet * 0.5)
    out += wetsig * wet * 1.6
    return out


def place(parts, total=None):
    """parts: list of (start_seconds, signal, gain)."""
    end = max(int(SR * s) + len(x) for s, x, _ in parts)
    out = np.zeros(max(end, int(SR * total) if total else 0))
    for s, x, g in parts:
        i = int(SR * s)
        out[i:i + len(x)] += x * g
    return out


def normalise(x, peak):
    x = x - x.mean()
    m = np.abs(x).max()
    return x * (peak / m) if m > 0 else x


def save(path, x, peak):
    x = fade(normalise(x, peak), 0.001, 0.015)
    pcm = np.clip(x * 32767, -32768, 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def tail(x, frac=0.35):
    """Half-cosine fade over the last `frac` of a note so a note that is
    truncated before it has fully decayed never ends in a click."""
    n = len(x)
    m = max(2, int(n * frac))
    w = np.ones(n)
    w[-m:] = 0.5 * (1 + np.cos(np.pi * np.linspace(0, 1, m)))
    return x * w


# --------------------------------------------------------------- instruments
def marimba(f, dur=0.7, vel=1.0):
    t = tt(dur)
    base = 0.55 * (440.0 / f) ** 0.5
    tau = [base, base * 0.28, base * 0.10]
    amp = [1.0, 0.32, 0.10]
    ratio = [1.0, 3.99, 9.8]
    y = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t / ta)
            for a, r, ta in zip(amp, ratio, tau))
    k = lowpass(noise(0.03), 2200) * env(0.03, 0.0005, 0.004)
    y[: len(k)] += k * 0.5
    return tail(y) * vel


def bell(f, dur=1.4, vel=1.0):
    t = tt(dur)
    ratios = [1.0, 2.0, 2.76, 5.4, 8.93]
    amps = [1.0, 0.55, 0.40, 0.22, 0.12]
    taus = [0.9, 0.6, 0.45, 0.25, 0.15]
    y = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t / ta)
            for r, a, ta in zip(ratios, amps, taus))
    return tail(y * np.minimum(1, t / 0.001)) * vel


def slap(dur=0.09, lo=1800, hi=9000, tau=0.008):
    return bandpass(noise(dur), lo, hi) * env(dur, 0.0005, tau)


def thud(f0=170, f1=100, dur=0.18, tau=0.05):
    t = tt(dur)
    f = f1 + (f0 - f1) * np.exp(-t / 0.03)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return tail((np.sin(ph) + 0.3 * np.sin(2 * ph * 1.83)) * env(dur, 0.001, tau), 0.3)


# -------------------------------------------------------------------- sounds
def s_select():
    t = tt(0.08)
    y = np.sin(2 * np.pi * 1250 * t) * np.exp(-t / 0.012)
    y += 0.3 * np.sin(2 * np.pi * 2480 * t) * np.exp(-t / 0.006)
    y += 0.25 * slap(0.08, 2500, 8000, 0.003)
    return y


def s_click():
    t = tt(0.11)
    f = 420 + 380 * np.exp(-t / 0.018)
    y = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.03)
    y += 0.3 * slap(0.11, 1500, 6000, 0.004)
    return y


def s_tick():
    t = tt(0.05)
    y = lowpass(noise(0.05), 3500) * env(0.05, 0.0004, 0.0035)
    y = highpass(y, 1000)
    y += 0.6 * np.sin(2 * np.pi * 880 * t) * np.exp(-t / 0.009)
    return y


def s_deal():
    return place([
        (0.0, slap(0.12, 1400, 8500, 0.016), 1.0),
        (0.0, thud(240, 190, 0.12, 0.018), 0.25),
        (0.03, slap(0.02, 3000, 9000, 0.002), 0.2),
    ])


def s_draw():
    dur = 0.24
    sw = sweep_noise(dur, 900, 4200, 1.4,
                     shape=np.sin(np.pi * np.linspace(0, 1, int(SR * dur))) ** 1.2)
    flick = slap(0.05, 2500, 9000, 0.008)
    return place([(0, sw, 0.7), (dur - 0.045, flick, 0.55)])


def s_whoosh():
    return sweep_noise(0.26, 500, 2600, 1.6)


def s_play():
    y = place([
        (0.0, slap(0.10, 1800, 9500, 0.007), 1.0),
        (0.0, bandpass(noise(0.12), 400, 1800) * env(0.12, 0.001, 0.02), 0.5),
        (0.0, thud(175, 105, 0.22, 0.05), 0.9),
    ])
    return reverb(y, 0.25, 0.12)


def s_error():
    a = thud(165, 140, 0.16, 0.05)
    y = place([(0.0, a, 1.0), (0.095, a, 0.7)])
    return lowpass(y, 900)


def s_turn():
    y = place([(0.0, marimba(659, 0.6), 0.9), (0.11, marimba(880, 0.7), 0.8)])
    return reverb(y, 0.4, 0.2)


def s_pull2():
    rise = sweep_noise(0.12, 400, 2200, 1.4)
    y = place([
        (0.0, rise, 0.5),
        (0.10, slap(0.1, 1800, 8000, 0.008), 0.8),
        (0.10, marimba(466, 0.5), 0.9),
        (0.10, thud(160, 100, 0.2, 0.05), 0.7),
        (0.27, marimba(349, 0.55), 0.9),
        (0.27, thud(140, 90, 0.2, 0.05), 0.6),
    ])
    return reverb(y, 0.3, 0.14)


def s_pull4():
    rise = sweep_noise(0.2, 300, 2800, 1.5)
    y = place([
        (0.0, rise, 0.55),
        (0.18, slap(0.12, 1500, 8000, 0.01), 0.9),
        (0.18, marimba(523, 0.55), 0.9),
        (0.18, thud(150, 85, 0.3, 0.07), 0.9),
        (0.32, marimba(415, 0.55), 0.9),
        (0.46, marimba(311, 0.7), 1.0),
        (0.46, thud(120, 60, 0.4, 0.12), 0.9),
    ])
    return reverb(y, 0.4, 0.18)


def s_spin():
    dur = 0.48
    sw = sweep_noise(dur, 450, 700, 1.3, fmid=3000)
    t = tt(dur)
    f = 600 + 800 * np.sin(np.pi * t / dur) ** 1.2
    vib = 1 + 0.02 * np.sin(2 * np.pi * 9 * t)
    gl = np.sin(2 * np.pi * np.cumsum(f * vib) / SR) * np.sin(np.pi * t / dur) ** 1.5
    y = place([(0, sw, 0.8), (0, gl, 0.22), (dur - 0.05, slap(0.05, 2500, 8000, 0.006), 0.35)])
    return reverb(y, 0.25, 0.1)


def s_jump():
    dur = 0.42
    t = tt(dur)
    f = 240 + 520 * np.exp(-t / 0.09) + 45 * np.sin(2 * np.pi * 21 * t) * np.exp(-t / 0.16)
    y = np.sin(2 * np.pi * np.cumsum(f) / SR)
    y += 0.25 * np.sin(2 * np.pi * np.cumsum(f * 2) / SR)
    y *= np.exp(-t / 0.17) * np.minimum(1, t / 0.003)
    y = place([(0, y, 1.0), (0, slap(0.04, 1500, 6000, 0.005), 0.4)])
    return reverb(y, 0.2, 0.08)


def s_touch():
    parts = []
    for i, f in enumerate([1047, 1319, 1568, 2093]):
        parts.append((0.045 * i, bell(f, 1.2), 0.8 - 0.1 * i))
    shimmer = highpass(noise(0.9), 5000) * env(0.9, 0.01, 0.18)
    parts.append((0.0, shimmer, 0.12))
    parts.append((0.0, thud(200, 120, 0.3, 0.08), 0.35))
    return reverb(place(parts), 0.8, 0.28)


def s_wheel():
    rise = sweep_noise(0.22, 300, 2000, 1.4)
    y = place([(0.0, rise, 0.55), (0.12, marimba(523, 0.5), 0.6),
               (0.19, marimba(784, 0.5), 0.6)])
    return reverb(y, 0.35, 0.18)


def s_confirm():
    parts = [(0.0, marimba(784, 0.7), 0.9), (0.07, marimba(1047, 0.7), 0.9),
             (0.14, marimba(1319, 0.9), 1.0), (0.14, bell(2637, 0.9), 0.25),
             (0.0, slap(0.06, 2000, 8000, 0.006), 0.4)]
    return reverb(place(parts), 0.45, 0.2)


def s_hold():
    dur = 0.75
    t = tt(dur)
    f = 250 * (1000 / 250) ** (t / dur)
    trem = 1 + 0.35 * np.sin(2 * np.pi * (10 + 14 * t / dur) * t)
    y = np.sin(2 * np.pi * np.cumsum(f) / SR) * trem
    y += 0.3 * np.sin(2 * np.pi * np.cumsum(f * 2) / SR)
    y *= 0.15 + 0.85 * (t / dur) ** 1.4
    sp = highpass(noise(dur), 4000) * (t / dur) ** 2 * 0.08
    return y + sp


def s_touchone():
    rise = sweep_noise(0.14, 500, 3000, 1.4)
    parts = [
        (0.0, rise, 0.4),
        (0.0, marimba(784, 0.4), 0.75),
        (0.14, marimba(1047, 1.0), 1.0),
        (0.14, marimba(1319, 1.0), 0.7),
        (0.14, bell(1047, 1.3), 0.5),
        (0.14, bell(2093, 1.3), 0.3),
        (0.14, slap(0.1, 1500, 9000, 0.01), 0.7),
        (0.14, thud(190, 90, 0.3, 0.09), 0.8),
    ]
    return reverb(place(parts), 0.7, 0.25)


def s_win():
    parts = []
    notes = [523, 659, 784, 1047, 1319, 1568]
    for i, f in enumerate(notes):
        parts.append((0.09 * i, marimba(f, 0.9), 0.85))
        parts.append((0.09 * i, bell(f, 1.1), 0.18))
    t0 = 0.09 * len(notes) + 0.04
    for f, g in [(1047, 0.8), (1319, 0.7), (1568, 0.7), (2093, 0.5)]:
        parts.append((t0, bell(f, 2.0), g))
        parts.append((t0, marimba(f, 1.0), 0.5))
    pad_t = tt(2.2)
    pad = sum(np.sin(2 * np.pi * f * pad_t) for f in (262, 330, 392, 523))
    pad *= np.sin(np.pi * np.minimum(1, pad_t / 2.2)) ** 2 * np.exp(-pad_t / 1.2) * 0.1
    parts.append((t0 - 0.05, pad, 1.0))
    for _ in range(14):
        f = float(rng.choice([2093, 2349, 2637, 3136, 3520]))
        parts.append((t0 + 0.15 + float(rng.uniform(0, 1.4)), bell(f, 0.8), float(rng.uniform(0.05, 0.14))))
    parts.append((t0, thud(180, 80, 0.4, 0.1), 0.5))
    return reverb(place(parts), 1.1, 0.28)


def s_loss():
    def voice(f0, f1, dur, vib=5.5):
        t = tt(dur)
        f = f0 + (f1 - f0) * (t / dur) ** 2
        f *= 1 + 0.012 * np.sin(2 * np.pi * vib * t) * np.minimum(1, t / 0.15)
        ph = 2 * np.pi * np.cumsum(f) / SR
        saw = signal.sawtooth(ph) * 0.6 + np.sin(ph) * 0.5
        saw = lowpass(saw, 1300)
        return saw * np.minimum(1, t / 0.04) * np.minimum(1, (dur - t) / 0.06)
    parts = [
        (0.00, voice(392, 380, 0.28), 0.8),
        (0.30, voice(370, 350, 0.28), 0.8),
        (0.60, voice(349, 335, 0.28), 0.8),
        (0.90, voice(330, 262, 0.75, 6.5), 0.9),
    ]
    return reverb(place(parts), 0.5, 0.16)


# ------------------------------------------------------------------ voices
def clean_voice(src, dst):
    with wave.open(src) as w:
        sr, n = w.getframerate(), w.getnframes()
        d = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float64) / 32768
    assert sr == SR, (src, sr)
    gate = 0.02
    i0 = max(0, int(np.argmax(np.abs(d) > gate)) - int(SR * 0.012))
    nz = np.nonzero(np.abs(d) > gate)[0]
    i1 = min(len(d), int(nz[-1]) + int(SR * 0.05))
    d = d[i0:i1]
    d = highpass(d, 70, 1)
    # gentle soft-knee limiter so loud syllables don't spike past the mix
    d = np.tanh(d * 1.6) / np.tanh(1.6)
    save(dst, d, 0.82)
    return i0 / SR * 1000


SFX = {
    "sfx_select": (s_select, 0.34),
    "sfx_click": (s_click, 0.50),
    "sfx_tick": (s_tick, 0.30),
    "sfx_deal": (s_deal, 0.46),
    "sfx_draw": (s_draw, 0.58),
    "sfx_whoosh": (s_whoosh, 0.34),
    "sfx_play": (s_play, 0.86),
    "sfx_error": (s_error, 0.50),
    "sfx_turn": (s_turn, 0.42),
    "sfx_pull2": (s_pull2, 0.80),
    "sfx_pull4": (s_pull4, 0.90),
    "sfx_spin": (s_spin, 0.70),
    "sfx_jump": (s_jump, 0.68),
    "sfx_touch": (s_touch, 0.78),
    "sfx_wheel": (s_wheel, 0.58),
    "sfx_confirm": (s_confirm, 0.72),
    "sfx_hold": (s_hold, 0.42),
    "sfx_touchone": (s_touchone, 0.95),
    "sfx_win": (s_win, 0.90),
    "sfx_loss": (s_loss, 0.66),
}

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "source/app/src/main/res/raw"
    os.makedirs(out, exist_ok=True)
    for name, (fn, peak) in SFX.items():
        y = fn()
        save(os.path.join(out, name + ".wav"), y, peak)
        print(f"{name:14s} {len(y) / SR:5.2f}s  peak {peak:.2f}")
    # character voices: trim dead air at the head so they land on the beat.
    # Originals are kept outside res/ (a dotted name in res/raw breaks aapt).
    orig = os.path.join(os.path.dirname(os.path.abspath(__file__)), "voice_originals")
    os.makedirs(orig, exist_ok=True)
    for who in ("boy", "girl", "dog"):
        for kind in ("yes", "ohno"):
            fn = f"sfx_{who}_{kind}.wav"
            dst = os.path.join(out, fn)
            src = os.path.join(orig, fn)
            if not os.path.exists(src):
                shutil.copyfile(dst, src)
            trimmed = clean_voice(src, dst)
            print(f"{who}_{kind:5s} trimmed {trimmed:.0f} ms of lead-in")
