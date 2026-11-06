package io.embodit.gonine

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.gonine.domain.window.PanelPlacement
import io.embodit.gonine.platform.Containers
import io.embodit.gonine.platform.WindowCtl
import io.embodit.gonine.ui.AppRoot
import io.embodit.gonine.ui.board.BoardScreen
import io.embodit.gonine.ui.control.ControlScreen

/**
 * Two containers in Shared Space (design container / windowModel): the board volume (default container,
 * 0.80 x 0.36 x 0.52 m from the manifest) and the control window (Planar, 560 x 600 dp: the settings page fits without scrolling, critic B2) to its right.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            AppRoot { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 600.dp),
            // Launch, S10 reopen and [重开控制窗] all open beside the board (profile D44 corrected 2026-11-05: a Shared
            // Space app reopens its panel, it does not promise "in front of you").
            placement = {
                val board = containers.firstOrNull { it.id == Containers.BOARD }
                when (PanelPlacement.kindFor(WindowCtl.lastWhy, board != null)) {
                    PanelPlacement.Kind.RIGHT_OF_VOLUME -> Placement.right(board!!)
                    PanelPlacement.Kind.NONE -> Placement.none()
                }
            },
        ) {
            AppRoot {
                ControlScreen(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 320.dp, maxWidth = 900.dp, maxHeight = 640.dp))
            }
        }
    }
