"""Cuts single Go-stone-on-wood "pa" hits out of two freesound CC0 recordings (~/Downloads/cc0-sounds/freesound-go-stones):
588317 billhails2 GoStoneSounds and 840425 Hongsungbock baduk2 (both Creative Commons 0, pages in LICENSE.txt).

  python3 tools/import_stones.py --scan          # list candidate hits (onset, peak, cleanliness)
  python3 tools/import_stones.py                 # write the chosen hits into app/src/main/assets/sounds

Each hit: from 4 ms before the onset, 260 ms long, 120 Hz high-pass (rumble), noise gate on the tail, 40 ms fade,
peak normalised to -1.5 dBFS (profile D51 / B20).
"""
import math, os, struct, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
LIB = os.path.expanduser("~/Downloads/cc0-sounds/freesound-go-stones")
FILES = {"A": "588317_billhails2_GoStoneSounds.mp3", "B": "840425_Hongsungbock_baduk2.mp3"}
SR = 44100
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from import_cc0 import decode, highpass, lowpass, db, rms, soft_room  # noqa: E402


def onsets(x):
    hop = 220
    env = [max(abs(v) for v in x[i:i + hop]) for i in range(0, len(x) - hop, hop)]
    out, last = [], -10 ** 9
    for i in range(3, len(env)):
        prev = max(env[i - 3:i])
        if env[i] > 0.08 and env[i] > 4 * (prev + 1e-4) and i * hop - last > int(0.15 * SR):
            out.append(i * hop)
            last = i * hop
    return out


def cut(x, at, ms=260):
    s = max(0, at - int(0.004 * SR))
    seg = x[s:s + int(ms / 1000 * SR)]
    pk = max(abs(v) for v in seg) or 1
    head = max(abs(v) for v in seg[: int(0.03 * SR)]) / pk
    tail = rms(seg[int(0.15 * SR):]) / pk  # lower = cleaner (no second hit, little noise)
    return seg, head, tail


def finish(seg):
    y = highpass(seg, 120.0)
    y = lowpass(y, 7000)
    pk = max(abs(v) for v in y) or 1
    # trim the tail where the hit has decayed under -42 dB of its peak (keep at least 60 ms)
    floor = pk * 10 ** (-42 / 20)
    end = max([i for i, v in enumerate(y) if abs(v) > floor] + [int(0.06 * SR)])
    y = y[: end + int(0.01 * SR)]
    n = len(y)
    # gentle gate on the tail: fade anything after the hit's decay falls under -40 dB of the peak
    fade = min(n // 3, int(0.02 * SR))
    for i in range(fade):
        y[n - 1 - i] *= i / fade
    k = 10 ** (-1.5 / 20) / pk
    return [v * k for v in y]


def compress(y, drive=float(os.environ.get('STONE_DRIVE', '6'))):
    """Lifts the body of the hit for B20: a soft limiter on the first sharp peak (tanh, then low-passed at 7 kHz so it
    adds nothing harsh), then peak back to -1.5 dBFS. A stone "pa" is broadband anyway; the click keeps its shape."""
    pk = max(abs(v) for v in y) or 1
    z = lowpass([math.tanh(drive * v / pk) for v in y], 7000)
    k = 10 ** (-1.5 / 20) / (max(abs(v) for v in z) or 1)
    return [v * k for v in z]


def write(name, y):
    with __import__("wave").open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in y))


def candidates():
    out = []
    for key, f in FILES.items():
        x = decode(os.path.join(LIB, f))
        for at in onsets(x):
            seg, head, tail = cut(x, at)
            if len(seg) < int(0.2 * SR):
                continue
            out.append((key, at / SR, max(abs(v) for v in seg), head, tail, seg))
    return out


if __name__ == "__main__":
    c = candidates()
    if "--scan" in sys.argv:
        for k, t, pk, head, tail, _ in sorted(c, key=lambda r: r[4]):
            print(f"{k} t={t:6.3f}s peak {db(pk):6.1f} dBFS head {head:.2f} tailRMS/peak {tail:.3f}")


# The cleanest hits (tail RMS / peak ≈ 0: one hit, no second stone, no hiss), chosen from --scan.
CHOSEN = {
    "place": ("B", 20.044), "place_b": ("B", 12.716),
    "place_2": ("B", 7.278), "place_2_b": ("B", 16.922),
    "place_3": ("B", 15.280), "place_3_b": ("B", 5.338),
    "capture": ("A", 5.054), "capture_b": ("A", 2.759),
}


def main():
    decoded = {k: decode(os.path.join(LIB, f)) for k, f in FILES.items()}
    for name, (key, t) in CHOSEN.items():
        x = decoded[key]
        at = int(t * SR)
        # re-find the exact onset within ±20 ms
        win = x[max(0, at - 900): at + 900]
        j = max(range(len(win)), key=lambda i: abs(win[i]))
        onset = max(0, at - 900) + j
        onset = next((i for i in range(onset - int(0.01 * SR), onset) if abs(x[i]) > 0.1 * abs(x[onset])), onset)
        seg, _, _ = cut(x, onset)
        y = compress(finish(seg))
        if os.environ.get("STONE_ROOM", "1") == "1":  # a short wooden-board resonance (18–90 ms) so a sparse game passes B20
            y = compress(soft_room(y, taps=((0.018, 0.30), (0.037, 0.20), (0.061, 0.12), (0.090, 0.07))))
        write(name, y)
        print(f"{name:10s} <- {key} {FILES[key]} @ {t:.3f}s  peak {db(max(abs(v) for v in y)):.1f} dBFS  rms {db(rms(y)):.1f} dBFS")


if __name__ == "__main__" and "--scan" not in sys.argv:
    main()
