"""Synthesises Go Nine's sound effects (stdlib only): wood and stone, nothing electronic, no ambient loop (B24).

  python3 tools/make_sounds.py [--check]

Writes app/src/main/assets/sounds/<kind>.wav (44.1 kHz mono 16-bit). Peaks are normalised to -1 dBFS after a soft saturation (every event RMS ≥ -20 dBFS, B20); the
three volume steps in the app scale from there. --check prints peak / RMS per file.
"""
import math, os, random, struct, sys, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
SR = 44100
PEAK_DB = -1.0  # B20: peak ≤ -1 dBFS; each event RMS ≥ -20 dBFS (design package used to say -11…-16, wrong way round)
DRIVE = 3.0     # soft saturation before normalising: lifts the body of short clicks


def env(t, attack, decay):
    return (t / attack if t < attack else math.exp(-(t - attack) / decay))


def lowpass(x, cutoff):
    a = 1 - math.exp(-2 * math.pi * cutoff / SR)
    y, out = 0.0, []
    for v in x:
        y += a * (v - y)
        out.append(y)
    return out


def tone(freqs, dur, decay, attack=0.002, noise=0.0, seed=1):
    rnd = random.Random(seed)
    n = int(dur * SR)
    out = []
    for i in range(n):
        t = i / SR
        s = sum(a * math.sin(2 * math.pi * f * t) for f, a in freqs)
        s += noise * (rnd.random() * 2 - 1) * math.exp(-t / 0.006)
        out.append(s * env(t, attack, decay))
    return out


def click(cutoff, body, dur=0.09, seed=1):
    """A stone on wood: a short noise snap through a low-pass plus a woody body."""
    snap = lowpass(tone([], dur, 0.012, noise=1.0, seed=seed), cutoff)
    wood = tone([(body, 0.6), (body * 2.7, 0.25)], dur, 0.025)
    return [a * 1.4 + b for a, b in zip(snap, wood)]


def seq(parts, gap):
    out = []
    for p in parts:
        out += p + [0.0] * int(gap * SR)
    return out


def norm(x):
    peak = max(1e-9, max(abs(v) for v in x))
    x = [math.tanh(DRIVE * v / peak) / math.tanh(DRIVE) for v in x]
    # Trim the silent tail (below -50 dBFS) so the file RMS measures the sound, not the silence after it.
    floor = 10 ** (-50 / 20)
    end = max(i for i, v in enumerate(x) if abs(v) > floor) + 1
    x = x[:end]
    k = 10 ** (PEAK_DB / 20) / max(abs(v) for v in x)
    return [v * k for v in x]


def write(name, x):
    x = norm(x)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in x))


def xylo(f, dur=0.35):
    return tone([(f, 1.0), (f * 3.9, 0.18)], dur, 0.12)


SOUNDS = {
    # 啪: the more neighbours, the more muffled (design sound.list).
    "place": lambda: click(2800, 420, seed=1),
    "place_2": lambda: click(1900, 360, seed=2),
    "place_3": lambda: click(1200, 300, seed=3),
    # 沙: a few stones shifting in the bowl.
    "pick": lambda: seq([lowpass(tone([], 0.03, 0.008, noise=0.6, seed=s), 2200) for s in range(4, 9)], 0.012),
    # 叮: a stone dropping into the lid.
    "capture": lambda: [a + b for a, b in zip(click(2400, 520, dur=0.32, seed=9), tone([(1180, 0.5), (2360, 0.15)], 0.32, 0.09))],
    # 咚: a short dull knock.
    "illegal": lambda: tone([(180, 1.0), (360, 0.3)], 0.16, 0.04),
    # a light ding when a group falls into atari (hints on).
    "alert": lambda: tone([(1560, 0.6), (2340, 0.12)], 0.25, 0.07),
    # two taps on the board's edge.
    "pass": lambda: seq([click(1500, 260, dur=0.07, seed=11), click(1500, 250, dur=0.07, seed=12)], 0.09),
    # 嗒嗒: small ticks while the cubes go down.
    "score": lambda: seq([click(2200, 600, dur=0.03, seed=20 + k) for k in range(6)], 0.06),
    "win": lambda: seq([xylo(523), xylo(659), xylo(784, 0.6)], 0.02),
    "lose": lambda: seq([xylo(392), xylo(330), xylo(262, 0.6)], 0.02),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, f in SOUNDS.items():
        write(name, f())
    if "--check" in sys.argv:
        for name in SOUNDS:
            with wave.open(os.path.join(OUT, name + ".wav")) as w:
                d = w.readframes(w.getnframes())
            v = [s / 32768 for s in struct.unpack("<%dh" % (len(d) // 2), d)]
            peak = 20 * math.log10(max(abs(s) for s in v))
            rms = 20 * math.log10(math.sqrt(sum(s * s for s in v) / len(v)))
            print(f"{name:8s} peak {peak:6.1f} dBFS  rms {rms:6.1f} dBFS  {len(v) / SR:.2f} s")


if __name__ == "__main__":
    main()
