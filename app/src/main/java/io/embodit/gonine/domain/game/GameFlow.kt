package io.embodit.gonine.domain.game

import io.embodit.gonine.domain.engine.AiLevel
import io.embodit.gonine.domain.engine.Mcts
import io.embodit.gonine.domain.engine.SearchResult
import io.embodit.gonine.domain.rules.Go
import io.embodit.gonine.domain.rules.Position
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Where the game is saved (files/game.json in the app, memory in tests). */
interface GameStore {
    fun load(): String?
    fun save(text: String)
    fun clear()
}

class MemoryGameStore(var text: String? = null) : GameStore {
    override fun load() = text
    override fun save(text: String) { this.text = text }
    override fun clear() { text = null }
}

/** The AI seen by the flow; the real one is [Mcts], tests can swap in a quick one. */
interface Engine {
    fun choose(pos: Position, komi: Double, level: AiLevel, cancelled: () -> Boolean): SearchResult
    fun suggest(pos: Position, komi: Double, millis: Long, cancelled: () -> Boolean): SearchResult
    fun deadStones(pos: Position, komi: Double): Set<Int>
    fun reseed(seed: Long)
}

class MctsEngine(seed: Long = System.nanoTime()) : Engine {
    @Volatile private var mcts = Mcts(seed)
    override fun choose(pos: Position, komi: Double, level: AiLevel, cancelled: () -> Boolean) = mcts.chooseMove(pos, komi, level, cancelled)
    override fun suggest(pos: Position, komi: Double, millis: Long, cancelled: () -> Boolean) =
        mcts.search(pos, komi, AiLevel.CHALLENGE, cancelled, maxSimsOverride = 3000, maxMillisOverride = millis)
    override fun deadStones(pos: Position, komi: Double) = mcts.deadStones(pos, komi)
    override fun reseed(seed: Long) { mcts = Mcts(seed) }
}

/** Sounds the flow asks for (the board plays the in-flight ones itself). */
enum class FlowSound { ILLEGAL, PASS, WIN, LOSE, ALERT, PICK }

/**
 * Turn sequencing for one game of 9 x 9 (design states S01–S09, S13, S16):
 * a human tap puts a ghost stone (S04), a second tap on the same point commits (S05); the move is written
 * to the state and saved before anything moves; the board reports [moveFinished] and a watchdog finishes
 * the turn if it never does. AI turns (S06) search on [aiDispatcher], cancellable, and wait at least
 * [MIN_THINK_MS] so the player sees whose turn it is. Two passes in a row open scoring (S07).
 *
 * Debug commands drive the same functions as the panel and the board (design verification.debugHooks).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val store: GameStore,
    private val engine: Engine = MctsEngine(),
    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
    private val sound: (FlowSound) -> Unit = {},
    private val onResult: (GameResult, GameState) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()
    val current: GameState get() = _state.value

    private var aiJob: Job? = null
    private var hintJob: Job? = null
    private var deadJob: Job? = null
    private var watchdog: Job? = null
    private var nextAnimId = 1
    private var nextFlashId = 1
    private var nextGameId = 1
    private var random = Random(System.nanoTime())

    /** Test hook: when false, the flow does not start AI turns by itself. */
    var autoAi = true

    /** Soak test: human seats are played by the engine too. */
    var autoPilot = false
        set(value) { field = value; if (value) kick() }

    fun reseed(seed: Long) {
        random = Random(seed)
        engine.reseed(seed)
        log("SEED $seed")
    }

    // ---- start ----

    /** Launch: first run → rules page (S01); a save → restored game (S16); otherwise the new-game page (S02). */
    fun start(firstRunDone: Boolean) {
        val saved = store.load()?.let { SaveCodec.decode(it) }
        val positions = saved?.let { SaveCodec.replay(it.config, it.record) }
        if (saved != null && positions != null && saved.phase != Phase.SETUP) {
            val phase = when (saved.phase) {
                Phase.THINKING -> Phase.THINKING
                Phase.SCORING -> Phase.SCORING
                Phase.RESULT -> Phase.RESULT
                else -> Phase.IDLE
            }
            _state.value = GameState(
                gameId = nextGameId++, phase = phase, config = saved.config, humanColor = saved.humanColor,
                record = saved.record, positions = positions, redo = saved.redo, dead = saved.dead,
                firstGame = saved.firstGame, result = saved.result, hasSave = true, savedMoves = saved.record.size,
            )
            log("GAME restored phase=$phase moves=${saved.record.size} toPlay=${positions.last().toPlay} legal=${positions.last().legalPoints().size}")
            if (!firstRunDone) _state.value = _state.value.copy(phase = Phase.WELCOME)
            else afterRestore()
            return
        }
        if (saved != null && positions == null) log("SAVE corrupt, ignored")
        _state.value = GameState(gameId = nextGameId++, phase = if (firstRunDone) Phase.SETUP else Phase.WELCOME)
        log("GAME none phase=${_state.value.phase}")
    }

    private fun afterRestore() {
        val s = current
        when (s.phase) {
            Phase.THINKING -> { _state.value = s.copy(phase = Phase.IDLE); nextTurn() }
            Phase.SCORING -> enterScoring(keepDead = s.dead.isNotEmpty())
            Phase.IDLE -> nextTurn()
            else -> Unit
        }
    }

    /** S01 [下第一盘]: beginner, two stones for you, the engine lights 1–3 points. */
    fun startFirstGame() = newGame(GameConfig.FIRST, firstGame = true)

    fun newGame(config: GameConfig, firstGame: Boolean = false) {
        cancelJobs()
        val human = SaveCodec.humanOf(config) { random.nextBoolean() }
        val start = Position.start(config.handicap)
        _state.value = GameState(
            gameId = nextGameId++, phase = Phase.IDLE, config = config, humanColor = human,
            positions = listOf(start), firstGame = firstGame, hintsOn = current.hintsOn,
        )
        log("GAME new mode=${config.mode} level=${config.level} handicap=${config.handicap} komi=${config.effectiveKomi} human=${colorName(human)}")
        persist()
        nextTurn()
    }

    /** Back to the new-game page with no game running (S09 [主菜单]). */
    fun toSetup() {
        cancelJobs()
        _state.value = current.copy(phase = Phase.SETUP, ghost = null, confirm = null, anim = null)
        persist()
    }

    // ---- input from the board ----

    /** A pinch / trigger on intersection [p] (or on the stone standing there). */
    fun tapPoint(p: Int) {
        val s = current
        when (s.phase) {
            Phase.SCORING -> { toggleDead(p); return }
            Phase.IDLE, Phase.GHOST -> Unit
            else -> { log("TAP ignored phase=${s.phase} point=${Go.name(p)}"); return }
        }
        if (s.isAiTurn) { log("TAP ignored ai turn"); return }
        if (s.confirm != null) _state.value = s.copy(confirm = null)
        val reason = s.position.check(p)
        if (reason != null) {
            sound(FlowSound.ILLEGAL)
            _state.value = current.copy(phase = Phase.IDLE, ghost = null, hint = Hint.of(reason), flash = IllegalFlash(nextFlashId++, p))
            log("ILLEGAL ${Go.name(p)} $reason")
            return
        }
        if (s.ghost == p) {
            commit(p)
        } else {
            sound(FlowSound.PICK)
            _state.value = current.copy(phase = Phase.GHOST, ghost = p, hint = Hint.TAP_AGAIN)
            log("GHOST ${Go.name(p)}")
        }
    }

    /** A pinch on a bowl or a lid: drops the ghost stone (S04 → S03). */
    fun tapBowl() {
        val s = current
        if (s.phase == Phase.GHOST) {
            _state.value = s.copy(phase = Phase.IDLE, ghost = null, hint = Hint.NONE)
            log("GHOST cancelled")
        }
    }

    // ---- moves ----

    private fun commit(p: Int) {
        val s = current
        val r = s.position.play(p)
        if (r !is Position.Result.Ok) return
        cancelHints()
        val capturedBefore = s.position.capturedBy(s.toPlay)
        val anim = if (p == Go.PASS) null else MoveAnim(nextAnimId++, r.move, capturedBefore)
        val next = s.copy(
            phase = if (anim != null) Phase.ANIMATING else s.phase,
            record = s.record + p,
            positions = s.positions + r.position,
            redo = emptyList(),
            ghost = null,
            hint = Hint.NONE,
            flash = null,
            anim = anim,
            suggestions = emptyList(),
            confirm = null,
            lastAiPassed = false,
        )
        _state.value = next
        persist()
        log("MOVE ${colorName(r.move.color)} ${Go.name(p)} captured=${r.move.captured.size} n=${next.moveCount}")
        if (anim != null) {
            watchdog?.cancel()
            val id = anim.id
            watchdog = scope.launch {
                delay(WATCHDOG_MS + r.move.captured.size * 60L)
                if (current.anim?.id == id) { log("MOVE watchdog id=$id"); moveFinished(id) }
            }
        }
    }

    /** The board finished flying move [id] (or the watchdog did). */
    fun moveFinished(id: Int) {
        val s = current
        if (s.anim?.id != id) return
        watchdog?.cancel()
        _state.value = s.copy(anim = null, phase = Phase.IDLE)
        log("MOVE done id=$id")
        nextTurn()
    }

    /** After a move or pass settles: scoring, the AI's turn, or the human's (with hints). */
    private fun nextTurn() {
        val s = current
        if (s.phase != Phase.IDLE) return
        if (s.position.passes >= 2) { enterScoring(); return }
        if (s.isAiTurn) { if (autoAi) startAi() else _state.value = s.copy(phase = Phase.THINKING); return }
        // The human's turn.
        val atari = s.atariLiberties
        val hint = when {
            s.lastAiPassed -> Hint.AI_PASSED
            s.config.mode == Mode.LOCAL && s.record.isNotEmpty() -> Hint.PASS_HANDOFF
            atari.isNotEmpty() -> Hint.ATARI
            else -> Hint.NONE
        }
        if (atari.isNotEmpty() && s.hintsOn) sound(FlowSound.ALERT)
        _state.value = s.copy(hint = hint)
        if (autoPilot) { startAi(forHuman = true); return }
        if (s.hintsOn || s.firstGame) startHints()
    }

    private fun startAi(forHuman: Boolean = false) {
        val s = current
        aiJob?.cancel()
        _state.value = s.copy(phase = Phase.THINKING)
        val pos = s.position
        val level = if (forHuman) AiLevel.BEGINNER else s.config.level
        val komi = s.config.effectiveKomi
        val gameId = s.gameId
        val moveIndex = s.record.size
        log("AI think level=$level color=${colorName(pos.toPlay)} n=${s.moveCount}")
        aiJob = scope.launch {
            val t0 = System.currentTimeMillis()
            val job = coroutineContext[Job]
            val result = withContext(aiDispatcher) { engine.choose(pos, komi, level) { job?.isActive == false } }
            val elapsed = System.currentTimeMillis() - t0
            if (elapsed < MIN_THINK_MS) delay(MIN_THINK_MS - elapsed)
            val now = current
            if (now.gameId != gameId || now.record.size != moveIndex || now.phase != Phase.THINKING) return@launch
            log("AI move=${Go.name(result.move)} sims=${result.sims} ms=${result.millis} winrate=${"%.2f".format(result.winrate)} pass=${result.passReason}")
            _state.value = now.copy(phase = Phase.IDLE)
            if (result.move == Go.PASS) pass(byAi = !forHuman) else commit(result.move)
        }
    }

    private fun startHints() {
        val s = current
        hintJob?.cancel()
        val pos = s.position
        val komi = s.config.effectiveKomi
        val take = if (s.firstGame && !s.hintsOn) 3 else 1
        val key = s.record.size to s.gameId
        hintJob = scope.launch {
            val job = coroutineContext[Job]
            val r = withContext(aiDispatcher) { engine.suggest(pos, komi, HINT_MS) { job?.isActive == false } }
            val now = current
            if ((now.record.size to now.gameId) != key || now.phase !in setOf(Phase.IDLE, Phase.GHOST)) return@launch
            val pts = r.ranked.filter { it.winrate >= (r.winrate - 0.15) }.take(take).map { it.point }
            _state.value = now.copy(suggestions = pts)
            log("HINT ${pts.joinToString(" ") { Go.name(it) }} sims=${r.sims}")
        }
    }

    private fun cancelHints() {
        hintJob?.cancel()
        hintJob = null
    }

    // ---- pass, resign, confirmations ----

    /** [虚手] asks first (S13 「这手不下了？」). */
    fun requestPass() = ask(Confirm.PASS)

    fun requestResign() = ask(Confirm.RESIGN)

    fun requestNewGame(config: GameConfig) {
        pendingConfig = config
        if (current.inGame && current.moveCount > 0) ask(Confirm.NEW_GAME) else newGame(config)
    }

    private var pendingConfig: GameConfig? = null

    private fun ask(c: Confirm) {
        val s = current
        if (c != Confirm.NEW_GAME && (s.phase !in setOf(Phase.IDLE, Phase.GHOST) || s.isAiTurn)) return
        _state.value = s.copy(confirm = c, ghost = null, phase = if (s.phase == Phase.GHOST) Phase.IDLE else s.phase)
        log("CONFIRM $c")
    }

    fun confirm() {
        val c = current.confirm ?: return
        _state.value = current.copy(confirm = null)
        when (c) {
            Confirm.PASS -> pass(byAi = false)
            Confirm.RESIGN -> resign(current.humanColorForTurn())
            Confirm.NEW_GAME -> newGame(pendingConfig ?: current.config)
        }
    }

    fun cancelConfirm() {
        if (current.confirm != null) _state.value = current.copy(confirm = null)
    }

    /** Pass for the side to move. Two in a row open scoring. */
    fun pass(byAi: Boolean) {
        val s = current
        if (s.phase !in setOf(Phase.IDLE, Phase.GHOST)) return
        sound(FlowSound.PASS)
        commit(Go.PASS)
        val n = current
        val passes = n.position.passes
        _state.value = n.copy(
            phase = Phase.IDLE,
            lastAiPassed = byAi && s.isVsAi,
            hint = if (passes >= 2) Hint.BOTH_PASSED else if (byAi) Hint.AI_PASSED else Hint.YOUR_PASS_SEEN,
        )
        log("PASS by=${colorName(s.toPlay)} ai=$byAi passes=$passes")
        nextTurn()
    }

    private fun GameState.humanColorForTurn(): Byte = if (isVsAi) humanColor else toPlay

    fun resign(loser: Byte) {
        val s = current
        if (!s.inGame) return
        cancelJobs()
        val sc = Go.score(s.cells)
        val result = GameResult(Go.other(loser), EndReason.RESIGNED, 0.0, sc.black, sc.white, s.config.effectiveKomi, s.moveCount)
        finish(result)
    }

    // ---- scoring ----

    private fun enterScoring(keepDead: Boolean = false) {
        val s = current
        cancelHints()
        _state.value = s.copy(phase = Phase.SCORING, hint = Hint.BOTH_PASSED, ghost = null, dead = if (keepDead) s.dead else emptySet())
        persist()
        log("SCORING enter")
        if (keepDead) return
        deadJob?.cancel()
        val pos = s.position
        val komi = s.config.effectiveKomi
        val id = s.gameId
        deadJob = scope.launch {
            val dead = withContext(aiDispatcher) { engine.deadStones(pos, komi) }
            val now = current
            if (now.gameId != id || now.phase != Phase.SCORING) return@launch
            _state.value = now.copy(dead = dead)
            persist()
            log("SCORING dead=${dead.size} ${dead.sorted().joinToString(" ") { Go.name(it) }}")
        }
    }

    /** Scoring: a tap on a stone flips its whole block between alive and dead. */
    fun toggleDead(p: Int) {
        val s = current
        if (s.phase != Phase.SCORING || s.cells[p] == Go.EMPTY) return
        val block = Go.group(s.cells, p).stones.toSet()
        val dead = if (p in s.dead) s.dead - block else s.dead + block
        _state.value = s.copy(dead = dead)
        persist()
        log("MARK ${Go.name(p)} dead=${p in dead} total=${dead.size}")
    }

    fun score(s: GameState = current) = Go.score(s.cells, s.dead)

    /** [就这样算]. */
    fun acceptScore() {
        val s = current
        if (s.phase != Phase.SCORING) return
        deadJob?.cancel()
        val sc = score(s)
        val komi = s.config.effectiveKomi
        val margin = sc.margin(komi)
        val result = GameResult(if (margin > 0) Go.BLACK else Go.WHITE, EndReason.COUNTED, kotlin.math.abs(margin), sc.black, sc.white, komi, s.moveCount)
        finish(result)
    }

    /** [不对，继续下]: passes clear, the superko history carries on. */
    fun continuePlay() {
        val s = current
        if (s.phase != Phase.SCORING) return
        deadJob?.cancel()
        _state.value = s.copy(
            phase = Phase.IDLE, dead = emptySet(), record = s.record + Record.RESUME,
            positions = s.positions + s.position.withPassesReset(), hint = Hint.NONE,
        )
        persist()
        log("SCORING refused, play on toPlay=${colorName(current.toPlay)}")
        nextTurn()
    }

    private fun finish(result: GameResult) {
        val s = current
        _state.value = s.copy(phase = Phase.RESULT, result = result, confirm = null, ghost = null, anim = null)
        persist()
        val humanWon = s.isVsAi && result.winner == s.humanColor
        if (s.isVsAi) sound(if (humanWon) FlowSound.WIN else FlowSound.LOSE) else sound(FlowSound.WIN)
        log("RESULT winner=${colorName(result.winner)} reason=${result.reason} margin=${result.margin} B=${result.black} W=${result.white} komi=${result.komi}")
        onResult(result, current)
    }

    /** [再来一局]: same settings, a fresh board. */
    fun again() = newGame(current.config, firstGame = false)

    // ---- undo / redo ----

    /**
     * [悔一步]: a whole round (vs AI the AI's reply goes too). During the AI's search it cancels the search and
     * takes back only the human's move (S06).
     */
    fun undo() {
        val s = current
        if (s.phase !in setOf(Phase.IDLE, Phase.GHOST, Phase.THINKING) || s.record.isEmpty()) return
        aiJob?.cancel()
        cancelHints()
        var record = s.record
        var positions = s.positions
        var redo = s.redo
        fun popOne() {
            redo = listOf(record.last()) + redo
            record = record.dropLast(1)
            positions = positions.dropLast(1)
        }
        popOne()
        while (record.isNotEmpty() && record.last() == Record.RESUME) popOne()
        if (s.isVsAi) {
            // Keep going back until it is the human's turn again (and never to before the start).
            while (record.isNotEmpty() && positions.last().toPlay != s.humanColor) popOne()
        }
        _state.value = s.copy(phase = Phase.IDLE, record = record, positions = positions, redo = redo, ghost = null, anim = null, suggestions = emptyList(), hint = Hint.NONE, confirm = null, lastAiPassed = false)
        persist()
        log("UNDO to n=${current.moveCount} toPlay=${colorName(current.toPlay)} redo=${redo.size}")
        nextTurn()
    }

    /** [重做]: replays the round [undo] took back. */
    fun redo() {
        val s = current
        if (s.phase !in setOf(Phase.IDLE, Phase.GHOST) || s.redo.isEmpty() || s.isAiTurn) return
        cancelHints()
        var record = s.record
        var positions = s.positions
        var redo = s.redo
        fun pushOne(): Boolean {
            val m = redo.first()
            val next = if (m == Record.RESUME) positions.last().withPassesReset() else (positions.last().play(m) as? Position.Result.Ok)?.position ?: return false
            record = record + m
            positions = positions + next
            redo = redo.drop(1)
            return true
        }
        if (!pushOne()) { redo = emptyList() }
        if (s.isVsAi) while (redo.isNotEmpty() && positions.last().toPlay != s.humanColor) if (!pushOne()) break
        _state.value = s.copy(phase = Phase.IDLE, record = record, positions = positions, redo = redo, ghost = null, suggestions = emptyList(), hint = Hint.NONE)
        persist()
        log("REDO to n=${current.moveCount}")
        nextTurn()
    }

    // ---- hints ----

    /** [提示：开 / 关] (D19: the state is written on the button). */
    fun setHints(on: Boolean) {
        val s = current
        _state.value = s.copy(hintsOn = on, suggestions = if (on) s.suggestions else if (s.firstGame) s.suggestions else emptyList())
        log("HINTS $on")
        if (on && s.phase in setOf(Phase.IDLE, Phase.GHOST) && !s.isAiTurn) startHints()
        if (!on && !s.firstGame) cancelHints()
    }

    // ---- lifecycle ----

    /** App to background: stop thinking, save a stable state. */
    fun onBackground() {
        aiJob?.cancel()
        cancelHints()
        persist()
        log("GAME background saved phase=${current.phase}")
    }

    /** Back from background while it was the AI's turn: think again. */
    fun onForeground() {
        val s = current
        if (s.phase == Phase.THINKING && aiJob?.isActive != true) { _state.value = s.copy(phase = Phase.IDLE); nextTurn() }
    }

    /** Debug / test: start the AI turn now (when [autoAi] is off) or re-run [nextTurn]. */
    fun kick() {
        val s = current
        if (s.phase == Phase.THINKING && aiJob?.isActive != true) { _state.value = s.copy(phase = Phase.IDLE); if (s.isAiTurn) startAi() else nextTurn() }
        else if (s.phase == Phase.IDLE) nextTurn()
    }

    /** Debug: load a position (scoring presets and S07 captures). The record restarts from it. */
    fun loadPreset(pos: Position, config: GameConfig = current.config.copy(handicap = 0)) {
        cancelJobs()
        presetBase = pos
        _state.value = GameState(gameId = nextGameId++, phase = Phase.IDLE, config = config, humanColor = Go.BLACK, positions = listOf(pos))
        log("PRESET loaded toPlay=${colorName(pos.toPlay)}")
        nextTurn()
    }

    /** Presets are not saved (the save replays from an empty board); the debug flow only. */
    private var presetBase: Position? = null

    private fun cancelJobs() {
        aiJob?.cancel(); aiJob = null
        cancelHints()
        deadJob?.cancel(); deadJob = null
        watchdog?.cancel(); watchdog = null
    }

    private fun persist() {
        val s = current
        if (presetBase != null && s.positions.first() === presetBase) return
        if (s.phase == Phase.SETUP || s.phase == Phase.WELCOME || s.phase == Phase.LOADING) {
            if (s.record.isEmpty() && s.result == null) { store.clear(); return }
        }
        runCatching { store.save(SaveCodec.encode(s)) }.onFailure { log("SAVE failed $it") }
    }

    companion object {
        const val MIN_THINK_MS = 400L
        const val WATCHDOG_MS = 1500L
        const val HINT_MS = 1500L

        fun colorName(c: Byte) = if (c == Go.BLACK) "B" else "W"
    }
}
