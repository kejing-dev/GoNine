"""Imports Go Nine's sounds from the Kenney CC0 packs (~/Downloads/cc0-sounds, License: CC0 1.0) — real recordings
for every physical sound (profile D51), interface sounds for UI cues. Each file is decoded with macOS afconvert,
mixed to mono, low-passed at its cutoff (4th-order, nothing harsh above ~4 kHz), trimmed, normalised to a -1 dBFS
peak, and lifted with a soft saturation (low-passed again) until its RMS is about -15 dBFS (B20, sparse game).

  python3 tools/import_cc0.py [--check]     # writes app/src/main/assets/sounds/<kind>.wav

The table SOURCES below is also what build/sound-sources.md lists.
"""
import math, os, struct, subprocess, sys, tempfile, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
LIB = os.path.expanduser("~/Downloads/cc0-sounds")
SR = 44100

# kind: ([(pack, file, gap_after_s)], low-pass Hz, note)
SOURCES = {
    "place":   ([("kenney_impact-sounds", "impactWood_light_000.ogg", 0)], 4000, "落子：木头轻击（无邻子，最亮的一档）"),
    "place_2": ([("kenney_impact-sounds", "impactWood_light_002.ogg", 0)], 3200, "落子：1–2 颗邻子，稍闷"),
    "place_3": ([("kenney_impact-sounds", "impactWood_light_004.ogg", 0)], 2500, "落子：≥ 3 颗邻子，最闷"),
    # Second take of each placement level: SoundPlayer alternates them (never the same take twice) + ±5 % pitch.
    "place_b":   ([("kenney_impact-sounds", "impactWood_light_001.ogg", 0)], 4000, "落子第二个变体（无邻子）"),
    "place_2_b": ([("kenney_impact-sounds", "impactWood_light_003.ogg", 0)], 3200, "落子第二个变体（1–2 颗邻子）"),
    "place_3_b": ([("kenney_impact-sounds", "impactWood_medium_000.ogg", 0)], 2500, "落子第二个变体（≥ 3 颗邻子）"),
    "capture": ([("kenney_impact-sounds", "impactPlate_light_001.ogg", 0)], 3600, "提子落进罐盖：瓷盘轻碰"),
    "pick":    ([("kenney_casino-audio", "chips-handle-1.ogg", 0)], 3000, "罐里取子：筹码翻动的「沙」"),
    "illegal": ([("kenney_impact-sounds", "impactWood_medium_003.ogg", 0)], 1200, "非法点：闷「咚」"),
    "pass":    ([("kenney_impact-sounds", "impactWood_light_003.ogg", 0.09), ("kenney_impact-sounds", "impactWood_light_001.ogg", 0)], 3000, "虚手：轻敲盘边两下"),
    "score":   ([("kenney_casino-audio", "chip-lay-1.ogg", 0.05), ("kenney_casino-audio", "chip-lay-2.ogg", 0.05), ("kenney_casino-audio", "chip-lay-3.ogg", 0)], 3600, "数目铺方块：细碎「嗒嗒」"),
    "alert":   ([("kenney_interface-sounds", "confirmation_001.ogg", 0)], 4000, "UI：打吃提醒"),
    "win":     ([("kenney_interface-sounds", "confirmation_002.ogg", 0)], 4000, "UI：你赢了"),
    "lose":    ([("kenney_interface-sounds", "pluck_002.ogg", 0)], 4000, "UI：这盘它赢了"),
}


def decode(path):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "x.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", f"LEI16@{SR}", "-c", "1", path, out], check=True)
        data = open(out, "rb").read()
    # Minimal RIFF reader (afconvert may write WAVE_FORMAT_EXTENSIBLE, which the wave module rejects).
    i, raw = 12, b""
    while i + 8 <= len(data):
        cid, size = data[i:i + 4], struct.unpack("<I", data[i + 4:i + 8])[0]
        if cid == b"data":
            raw = data[i + 8:i + 8 + size]
            break
        i += 8 + size + (size & 1)
    return [s / 32768 for s in struct.unpack("<%dh" % (len(raw) // 2), raw[: len(raw) // 2 * 2])]


def lowpass(x, fc):
    # RBJ biquad, Q = 0.7071, applied twice (4th order).
    w0 = 2 * math.pi * fc / SR
    a = math.sin(w0) / (2 * 0.7071)
    c = math.cos(w0)
    b0, b1, b2 = (1 - c) / 2, 1 - c, (1 - c) / 2
    a0, a1, a2 = 1 + a, -2 * c, 1 - a
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    for _ in range(2):
        y, x1, x2, y1, y2 = [], 0.0, 0.0, 0.0, 0.0
        for v in x:
            o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2, x1, y2, y1 = x1, v, y1, o
            y.append(o)
        x = y
    return x


def db(v):
    return 20 * math.log10(max(v, 1e-9))


def rms(x):
    return math.sqrt(sum(v * v for v in x) / max(1, len(x)))


def finish(x, fc):
    floor = 10 ** (-55 / 20)
    idx = [i for i, v in enumerate(x) if abs(v) > floor]
    x = x[max(0, idx[0] - 40):idx[-1] + 1] if idx else x
    fade = min(len(x), int(0.01 * SR))
    for i in range(fade):
        x[-1 - i] *= i / fade
    peak = 10 ** (-1 / 20)

    def norm(y):
        k = peak / max(abs(v) for v in y)
        return [v * k for v in y]

    x = norm(x)
    # Short wooden clicks carry little energy: lift the body with a soft saturation until the clip's RMS reaches
    # about -15 dBFS (B20: loud enough in a sparse game), then low-pass again so the lift adds nothing harsh.
    drive = 1.0
    while db(rms(x)) < -15 and drive < 4.0:
        drive += 0.5
        x = norm(lowpass([math.tanh(drive * v / peak) for v in x], fc))
    return x


def main():
    os.makedirs(OUT, exist_ok=True)
    for kind, (parts, fc, _note) in SOURCES.items():
        mix = []
        for pack, name, gap in parts:
            mix += decode(os.path.join(LIB, pack, "Audio", name)) + [0.0] * int(gap * SR)
        x = finish(lowpass(mix, fc), fc)
        with wave.open(os.path.join(OUT, kind + ".wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in x))
        if "--check" in sys.argv:
            print(f"{kind:8s} peak {db(max(abs(v) for v in x)):6.1f} dBFS  rms {db(rms(x)):6.1f} dBFS  {len(x) / SR:.2f} s  lp {fc} Hz  <- {', '.join(p[1] for p in parts)}")


if __name__ == "__main__":
    main()
