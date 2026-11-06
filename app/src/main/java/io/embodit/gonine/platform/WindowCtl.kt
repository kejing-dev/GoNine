package io.embodit.gonine.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.math.Quat
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.gonine.domain.window.LevelGate
import io.embodit.gonine.domain.window.PanelPlacement
import kotlin.math.asin

/** Ids of the two containers. The board volume is the default container (manifest). */
object Containers {
    const val BOARD = "GoBoard"
    const val CONTROL = "GoControl"
    const val CONTROL_TAG = "control"
}

/**
 * The control window's opener (native-window-move.md §3.5 / §7.9): every open — first open, re-open after the
 * system ×, [叫回面前] — goes through a [LevelGate] fed with the head pitch each frame, so a window never opens
 * pitched. The volume's composition hands over its navigator; the window reports its lifecycle into
 * [panelState]. Process-wide, so both containers see it.
 */
object WindowCtl {
    private const val TAG = "GoNine"
    private val main = Handler(Looper.getMainLooper())
    // D29: the head has to hold level for 0.5 s before the window opens.
    private val gate = LevelGate(maxPitchDeg = 10f, dwellMs = 500L, noHeadMs = 1500L, maxWaitMs = 4000L)


    /** Lifecycle of the control window as it reported it ("never", "ON_CREATE" … "ON_DESTROY"). */
    var panelState by mutableStateOf("never")
        private set

    /** The volume shows「平视前方，面板就出来」on its seat card while this is true. */
    var levelHint by mutableStateOf(false)
        private set

    /** True from a request until the window opens (the settings button hides itself meanwhile, D29). */
    var opening by mutableStateOf(false)
        private set

    var navigator: com.pico.spatial.ui.platform.containers.SpatialNavigator? = null
    private var recallPending = false
    private var recallWhy = PanelPlacement.Entry.SETTINGS_BUTTON.why
    private var lastStepMs = 0L

    val isClosed: Boolean get() = panelState == "ON_DESTROY" || panelState == "never"

    fun reportLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "PANEL $event")
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            openWindow(recallWhy)
        }
    }

    /** Asks for the window; it opens once the head is level (or the head pose is unavailable). */
    /** Reason of the latest open request; Main.kt's placement reads it through [PanelPlacement]. */
    @Volatile
    var lastWhy: String = PanelPlacement.Entry.LAUNCH.why
        private set

    fun openWindow(why: String) {
        lastWhy = why
        // A Shared Space volume gets no head pose (NOT_IN_FULL_SPACE): nothing to wait for, open now (critic R3 / R10).
        HeadPitch.read()
        if (HeadPitch.unsupported) {
            gate.cancel()
            levelHint = false
            openNow("$why/${HeadPitch.support}")
            return
        }
        gate.ask(why, SystemClock.elapsedRealtime())
        opening = true
        Log.i(TAG, "WINDOW ask why=$why")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /** [重开控制窗]: close, wait for ON_DESTROY (an open straight after a close is swallowed), then open beside the board. */
    fun recall(entry: PanelPlacement.Entry = PanelPlacement.Entry.SETTINGS_BUTTON) {
        recallWhy = PanelPlacement.why(entry)
        if (isClosed) { openWindow(recallWhy); return }
        recallPending = true
        opening = true
        Log.i(TAG, "WINDOW recall close")
        runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
    }

    /**
     * D44 exit: close BOTH containers (control window and the board volume) before the task ends; closing only the
     * control window left a recents task that relaunched the board at its old world position (QA #1 q1-1).
     */
    fun closeAll() {
        cancel()
        val nav = navigator ?: return
        runCatching { nav.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { nav.closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close board: $it") }
        Log.i(TAG, "EXIT closed control + board containers")
    }

    /** Debug: the system × (S10). */
    fun close() {
        runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
        Log.i(TAG, "WINDOW close (debug)")
    }

    /** Called every frame from the board's ECS system. */
    fun stepGate() {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        val pitch = HeadPitch.read()
        val hint = gate.hintWanted(pitch)
        if (hint != levelHint) levelHint = hint
        gate.step(now, pitch)?.let { levelHint = false; openNow(it) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) {
                val why = gate.pending ?: return
                gate.cancel()
                levelHint = false
                openNow("$why/noframes")
            } else main.postDelayed(this, 3000)
        }
    }

    private fun openNow(why: String) {
        opening = false
        val nav = navigator
        if (nav == null) { Log.w(TAG, "WINDOW open why=$why but no navigator"); return }
        Log.i(TAG, "WINDOW open why=$why pitch=${HeadPitch.last}")
        runCatching { nav.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    fun cancel() {
        gate.cancel()
        opening = false
        levelHint = false
    }
}

/**
 * Head pitch for the level gate only (degrees, + looking up); null when there is no valid pose. In a Shared
 * Space volume the HMD provider usually reports NOT_IN_FULL_SPACE, so the gate falls back to opening after
 * its no-head timeout (documented in ui-audit.md / headpose-audit.md). Never used to place or aim anything.
 */
object HeadPitch {
    private var provider: HMDTrackingProvider? = null
    private var started = false
    var last: Float? = null
        private set
    var support: String = "unstarted"
        private set

    fun read(): Float? {
        val p = provider ?: runCatching { HMDTrackingProvider() }.getOrNull()?.also { provider = it } ?: return null
        if (!started) {
            started = true
            support = runCatching { p.start().name }.getOrElse { "start failed" }
        }
        val s = runCatching { p.supportState }.getOrNull()
        support = s?.name ?: support
        if (s != DataProvider.SupportState.SUPPORTED) { last = null; return null }
        val q: Quat = runCatching { p.latestData?.hmdPose?.rotation }.getOrNull() ?: run { last = null; return null }
        // The tracker's first frames are all zero / identity: not a pose (sign-hands lesson).
        if (q.x == 0f && q.y == 0f && q.z == 0f) { last = null; return null }
        // forward = q · (0, 0, -1); its y component is sin(pitch).
        val fy = -(2f * (q.y * q.z - q.w * q.x))
        val pitch = Math.toDegrees(asin(fy.coerceIn(-1f, 1f).toDouble())).toFloat()
        last = pitch
        return pitch
    }

    /** The provider answered and said it cannot give a pose here (NOT_IN_FULL_SPACE, DEVICE_NOT_SUPPORTED, …). */
    val unsupported: Boolean get() = support.startsWith("NOT") || support.startsWith("DEVICE_NOT") || support.startsWith("WITHOUT")

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        started = false
    }
}
