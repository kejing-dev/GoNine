package io.embodit.gonine

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
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
            placement = {
                // Recall (and reopening from the volume) puts the window straight ahead (profile D44); launch puts it beside the board.
                if (WindowCtl.placeInFront) Placement.none()
                else containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none()
            },
        ) {
            AppRoot {
                ControlScreen(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 320.dp, maxWidth = 900.dp, maxHeight = 640.dp))
            }
        }
    }
