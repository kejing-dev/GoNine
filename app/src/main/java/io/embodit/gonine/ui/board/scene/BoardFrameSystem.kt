package io.embodit.gonine.ui.board.scene

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System

/**
 * Drives the board once per rendered frame inside the ECS (building slices, flights, bobbing), never through
 * Compose. The SDK creates the system itself, so it finds the scene through [BoardSceneHolder].
 */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        BoardSceneHolder.scene?.onFrame(context.deltaTime)
        FrameHooks.onFrame?.invoke()
    }
}

/** Other per-frame work (the window level gate). */
object FrameHooks {
    @Volatile
    var onFrame: (() -> Unit)? = null
}
