"""Renders 30 s of a typical game with the app's own sound files at the default volume step (High, gain 1.0),
the way SoundPlayer plays them, and reports peak / RMS (B20 evidence).

  python3 tools/loudness_mix.py <out.wav>

Timeline: you pick (沙) and place (啪), the AI answers, captures drop into the lid (叮), an illegal tap (咚),
an atari ding, two passes, the count (嗒嗒) and the result (木琴). Prints the whole-file RMS and the RMS of the
event segments (the 30 s RMS of a turn-based game is mostly the silence between moves).
"""
import math, os, struct, sys, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
SR = 44100
GAIN = 1.0  # SoundPlayer: High = 1.0 (default), Medium 0.7, Low 0.45
# SoundPlayer.KIND_GAIN: UI cues sit clearly under the stones (v0.1.8, user: 「预选声音太大太吵」); pick / hover are silent.
KIND_GAIN = {"alert": 0.45, "win": 0.5, "lose": 0.5, "illegal": 0.5, "pass": 0.6, "score": 0.6}


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        d = w.readframes(w.getnframes())
    return [s / 32768 for s in struct.unpack("<%dh" % (len(d) // 2), d)]


EVENTS = []
t = 0.4
for turn in range(9):
    EVENTS += [(t, "pick"), (t + 0.9, ["place", "place_2", "place_3"][turn % 3]), (t + 2.0, ["place_2_b", "place_b", "place_3_b"][turn % 3])]
    if turn in (3, 6):
        EVENTS += [(t + 2.35, "capture")]
    if turn == 4:
        EVENTS += [(t + 2.7, "illegal")]
    if turn == 5:
        EVENTS += [(t + 2.4, "alert")]
    t += 2.9
EVENTS += [(26.6, "pass"), (27.3, "pass"), (27.8, "score"), (28.5, "win")]


def main():
    out = sys.argv[1]
    n = SR * 30
    mix = [0.0] * n
    seg = [False] * n
    for at, name in EVENTS:
        if not os.path.exists(os.path.join(SND, name + ".wav")):
            continue  # silent by design (pick)
        x = [v * KIND_GAIN.get(name, 1.0) for v in load(name)]
        i0 = int(at * SR)
        for i, v in enumerate(x):
            if i0 + i < n:
                mix[i0 + i] += v * GAIN
                seg[i0 + i] = True
    # Overlapping events add up: keep the mix itself at or below -1 dBFS (QA #1 K1 found 2 clipped samples).
    peak = max(abs(v) for v in mix)
    limit = 10 ** (-1.0 / 20)
    if peak > limit:
        mix = [v * limit / peak for v in mix]
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in mix))
    # Measure what was written (16-bit), not the float mix.
    with wave.open(out) as w:
        d = w.readframes(w.getnframes())
    mix = [s / 32768 for s in struct.unpack("<%dh" % (len(d) // 2), d)]
    peak = max(abs(v) for v in mix)
    clipped = sum(1 for v in mix if abs(v) >= 32767 / 32768)
    db = lambda v: 20 * math.log10(max(v, 1e-9))
    rms_all = math.sqrt(sum(v * v for v in mix) / n)
    ev = [v for v, s in zip(mix, seg) if s]
    rms_ev = math.sqrt(sum(v * v for v in ev) / len(ev))
    print(f"{out}: 30.0 s, {len(EVENTS)} events, clipped samples {clipped}, peak {db(peak):.2f} dBFS, RMS whole file {db(rms_all):.1f} dBFS, RMS of event segments {db(rms_ev):.1f} dBFS")


if __name__ == "__main__":
    main()
