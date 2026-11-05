# 九路围棋 Go Nine — project guidance

9 x 9 Go for PICO OS 6 Shared Space, PICO Spatial SDK 6.1.9, package `io.embodit.gonine`, v0.1.2.
Design contract: factory `apps/go-nine/design-package.json` (v1 = A12 main loop; `v2[]` — lessons, SGF, skin 2,
review, coordinates, one-tap — is NOT built).

Two containers:
- **Board volume** (default container `GoBoard`, Volumetric 0.80 x 0.36 x 0.52 m, fixed scale, gravity aligned):
  kaya board 0.46 m (5 cm pitch) tilted 0 / 15 / 25° about its near top edge, lens stones Ø 3.8 x 1.0 cm, two
  bowls, two lids turned over as capture trays, three cards in front (seat + two capture counts, no buttons).
- **Control window** (`GoControl`, Planar 560 x 600 dp so the settings page fits with Quit on screen; `Placement.right(GoBoard)` for every open: launch, S10 reopen and [重开控制窗] — a Shared Space app reopens its panel, it never promises "in front of you", D44 corrected): native TabBar pages
  对局 / 新局 / 规则 / 设置. Drag / close / minimise are the system's controls; no custom handle anywhere (D21).

## Rules that stay in force
- SpatialUI + PicoTheme only (`GoTheme`); no Material. Copy in `res/values` + `values-zh`; in-app language via a
  ContextWrapper (`GoTheme.localized`). Attachment cards wrap their content in `AppRoot` (separate composition).
- Rules only in `domain/rules/GoRules.kt` (positional superko, no suicide, area scoring). AI only in
  `domain/engine` (UCT MCTS, random playouts that never fill own eyes). Turn flow only in `domain/game/GameFlow.kt`:
  a move is committed and saved before the board animates; the board reports `moveFinished`, a watchdog finishes
  it otherwise. Per-frame motion lives in `BoardScene.onFrame` (ECS `BoardFrameSystem`), never in Compose.
- Every control-window open goes through `WindowCtl` (D9 / D29). In a Shared Space volume the HMD provider reports
  `NOT_IN_FULL_SPACE`, so it opens at once; with a head pose it waits for a level head at most 4 s (`LevelGate`).
  The head pitch is used for nothing else. [重开控制窗] has two entries: the settings button and a tap on the seat card.
- Exit closes BOTH containers (`WindowCtl.closeAll`: control window, then the board volume) before `finishAndRemoveTask`;
  closing only the control window left a recents task (QA #1 q1-1). The TabBar is its own system window: its labels
  are resolved in ControlScreen and passed as plain text, or they stay in the system language (q1-3).
- With the window closed (system ×), the first pinch in the volume only reopens it (S10, `onBoardEvent`).
- Visible pieces are procedural primitives on purpose (design B12 deviation); each call has `// gate:allow geometry`.

## Layout
| Path | What |
| --- | --- |
| `domain/rules` | `Go` (points, groups, place, area score, Zobrist), `Position` (immutable, superko history) |
| `domain/engine` | `FastBoard` (playout board), `Mcts` (levels, pass policy, dead-stone suggestion, benchmark) |
| `domain/game` | `GameState` / models, `GameFlow`, `SaveCodec` (key=value save, replays the record) |
| `domain/window` | `LevelGate` |
| `data` | `FileGameStore` (atomic + .bak), `SettingsRepository`, `StatsRepository` |
| `platform` | `SpatialApplication`, `GoGraph`, `SoundPlayer`, `WindowCtl` / `HeadPitch`; debug: `DebugCommandReceiver` |
| `ui/board` | `BoardScreen` (SpatialView, taps, cards), `scene/` (`BoardLayout` metres, `BoardScene`, `SceneResources`, `Motion`) |
| `ui/control` | `ControlScreen` (TabBar, settings, exit), `GamePages` (play / scoring / result / new / rules), `Widgets` |

Scene space: origin on the volume floor centre, +Z toward the player. Point (row r, col c): row 0 = line 1 (near),
col 0 = A (left); `BoardLayout.pointLocal` / `pointScene`. The human's bowl is on the right (local game: black right).

## Build, test, run (macOS, kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests '*StrengthTest*' -Pstrength=100   # AI strength, slow
python3 tools/make_sounds.py --check                                       # regenerate sounds (peak -1, RMS ≥ -20)
python3 tools/loudness_mix.py <factory>/apps/go-nine/qa/loudness-vX.wav  # B20 evidence
SERIAL=emulator-5564 tools/harness/shoot_all.sh <outdir>                   # every QA state, after each build
java -Djava.awt.headless=true tools/BuildIcon.java design/icon/icon-512.png 512
adb -s emulator-5564 install -r app/build/outputs/apk/debug/app-debug.apk
```
Emulators are shared: borrow with the factory's `bin/emu_borrow.py borrow go-nine`, return when done.

## Debug broadcast (debug builds) — `tools/harness/gn.sh cmd NAME [ARG]`
`adb shell am broadcast -a io.embodit.gonine.DEBUG -p io.embodit.gonine --es cmd <cmd> --es a <arg>`
Board taps go through the board's own input path (`BoardInput`), so S10 consumption applies.
- input: `tap|ghost <A1..J9>` (one tap) · `place <pt>` (two taps) · `bowl` · `pass` (ask + confirm) · `askpass` ·
  `resign` · `confirm` · `cancel` · `undo` · `redo` · `hint on|off` · `mark <pt>` · `accept` · `playon` · `again` · `menu`
- games: `first` (S01 button) · `new ai|local,b|w|r,1|2|3,<handicap>,<komi>` · `preset clean|dead|seki|ko|atari|suicide` · `seed N` · `auto on|off`
- states: `goto S01|S02|S03|S04|S06|S07|S09|S10|S11|S12|S13` · `page play|new|rules|settings`
- settings: `lang system|zh|en` · `tilt 0|15|25` · `sound on|off` · `volume 0|1|2` · `reduced on|off`
- window: `closepanel` (S10) · `reopen` · `recall`
- capture aids: `flash hold|off` (red cross stays 3 s) · `icons` (all ic_* into files/icons.png; `adb exec-out run-as io.embodit.gonine cat files/icons.png`)
- info: `dump` (board + phase + panel + head support) · `evidence` · `bench` (playouts / s + per-level search time)

## Screenshot checklist (QA)
S00 cold start (control window spinner「在摆棋盘…」while the board builds, < 1 s on the emulator) · S01 `goto S01` ·
S02 `page new` · S03 `new ai,b,1,2` (+ `hint on` for the ring and liberty lights; first game shows 1–3 rings) ·
S04 `ghost E5` · S05 `place E5` (mid-flight) / `preset atari` + `place E6` (capture into the right lid) ·
S06 `new ai,b,1,0` + `place E5` · S07 `goto S07` and `preset clean|seki` + `pass` x2 · S08 `preset suicide` + `tap A1`,
`preset ko` + `place E4` + (white) `tap D4` · S09 `accept` · S10 `closepanel` in S03 / S06 / S09, then `tap E5`
(reopens, no stone) · S11 `page settings` (tilt 0/15/25) · S12 `page rules` · S13 `askpass` / `resign` ·
S16 play two moves, `am force-stop`, relaunch. Modifiers: `lang en`, `reduced on`, `sound off`.
Log tag `GoNine`: `GAME`, `MOVE`, `AI`, `HINT`, `PASS`, `SCORING`, `RESULT`, `TAP`, `GHOST`, `ILLEGAL`, `FLASH`,
`WINDOW`, `PANEL`, `SCENE`, `OVERFLOW`, `CMD`, `DUMP`, `BENCH`.
