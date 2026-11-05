package io.embodit.gonine.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.gonine.R
import io.embodit.gonine.domain.game.Language
import io.embodit.gonine.domain.game.Phase
import io.embodit.gonine.platform.Containers
import io.embodit.gonine.platform.GoGraph
import io.embodit.gonine.platform.HeadPitch
import io.embodit.gonine.platform.WindowCtl
import io.embodit.gonine.ui.board.scene.SceneStatus
import kotlin.system.exitProcess

/** The page the control window shows; process-wide so the flow and debug commands can switch it. */
enum class Page { PLAY, NEW, RULES, SETTINGS }

object ControlNav {
    var page by mutableStateOf(Page.PLAY)
}

/**
 * The control window (Planar, one window for all 2D content, D8): four pages switched by the SpatialUI native
 * TabBar (对局 / 新局 / 规则 / 设置). Dragging, closing and minimising are the system's controls under the
 * window; nothing here draws a handle (D21).
 */
@Composable
fun ControlScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { GoGraph.from(context) }
    val game by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val stats by graph.stats.stats.collectAsStateWithLifecycle()

    // Report this window's lifecycle so the volume knows when it is closed (S10) and recall can reopen it.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.reportLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // First run opens on the rules page (S01).
    DisposableEffect(game.phase) {
        if (game.phase == Phase.WELCOME) ControlNav.page = Page.RULES
        if (game.phase == Phase.SETUP && ControlNav.page == Page.PLAY) ControlNav.page = Page.NEW
        onDispose { }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
            if (!SceneStatus.ready) {
                // S00: the board is still being set out.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Label(stringResource(R.string.loading), PicoTheme.typography.bodyLarge, "loading", modifier = Modifier.padding(start = 12.dp))
                }
            }
            when (ControlNav.page) {
                Page.PLAY -> PlayPage(game, settings, graph)
                Page.NEW -> NewPage(game, graph)
                Page.RULES -> RulesPage(first = game.phase == Phase.WELCOME, graph = graph)
                Page.SETTINGS -> SettingsPage(settings, graph) { exitApp(context, graph) }
            }
        }

        // The system TabBar: a small window of its own outside this one (native-window-move.md §3.6).
        TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
            tab(Page.PLAY, R.drawable.ic_sports_esports, R.string.tab_play)
            tab(Page.NEW, R.drawable.ic_add_circle, R.string.tab_new)
            tab(Page.RULES, R.drawable.ic_menu_book, R.string.tab_rules)
            tab(Page.SETTINGS, R.drawable.ic_settings, R.string.tab_settings)
        }
    }
}

private fun com.pico.spatial.ui.design.windows.TabBarScope.tab(page: Page, icon: Int, label: Int) {
    item(
        selected = ControlNav.page == page,
        onClick = { ControlNav.page = page; Log.i(GoGraph.TAG, "PAGE $page") },
        mainContent = { Icon(painter = painterResource(icon), contentDescription = stringResource(label)) },
        supportContent = { Label(stringResource(label), PicoTheme.typography.labelMedium, "tab/$page") },
    )
}

// ---- settings (S11) ----

@Composable
private fun SettingsPage(settings: io.embodit.gonine.domain.game.Settings, graph: GoGraph, onExit: () -> Unit) {
    var confirmExit by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(stringResource(R.string.tab_settings), PicoTheme.typography.headlineSmall, "title/settings")
        Gap(4.dp)
        ChoiceRow(stringResource(R.string.set_language), "language") {
            listOf(Language.SYSTEM to R.string.lang_system, Language.ZH to R.string.lang_zh, Language.EN to R.string.lang_en).forEach { (l, res) ->
                ChoiceButton(stringResource(res), settings.language == l, { graph.settings.update { it.copy(language = l) } }, "lang/$l", Modifier.weight(1f))
            }
        }
        ChoiceRow(stringResource(R.string.set_sound), "sound") {
            Switch(checked = settings.sound, onCheckedChange = { on -> graph.settings.update { it.copy(sound = on) }; graph.sounds.apply(graph.settings.value) })
        }
        ChoiceRow(stringResource(R.string.set_volume), "volume") {
            // Three steps: the slider snaps to low / medium / high (D15).
            Slider(
                value = settings.volume.toFloat(),
                onValueChange = { v -> val step = Math.round(v).coerceIn(0, 2); if (step != graph.settings.value.volume) { graph.settings.update { it.copy(volume = step) }; graph.sounds.apply(graph.settings.value) } },
                valueRange = 0f..2f,
                modifier = Modifier.weight(1f),
            )
            Label(stringResource(listOf(R.string.vol_low, R.string.vol_mid, R.string.vol_high)[settings.volume]), PicoTheme.typography.labelLarge, "volume/value", modifier = Modifier.padding(start = 8.dp))
        }
        ChoiceRow(stringResource(R.string.set_reduced), "reduced") {
            Switch(checked = settings.reducedMotion, onCheckedChange = { on -> graph.settings.update { it.copy(reducedMotion = on) } })
        }
        ChoiceRow(stringResource(R.string.set_tilt), "tilt") {
            for (t in listOf(0, 15, 25)) ChoiceButton("$t°", settings.tilt == t, { graph.settings.update { it.copy(tilt = t) } }, "tilt/$t", Modifier.weight(1f))
        }
        ChoiceRow(stringResource(R.string.set_recall_q), "recall") {
            // The button hides while the window closes and reopens straight ahead (Placement.none(), D44).
            if (WindowCtl.opening) Label(stringResource(R.string.recall_wait), PicoTheme.typography.bodyMedium, "recall/wait")
            else SecondaryButton(stringResource(R.string.btn_recall), { WindowCtl.recall() }, "recall", icon = R.drawable.ic_center_focus_strong)
        }
        ChoiceRow(stringResource(R.string.set_about), "about") {
            Label(stringResource(R.string.about_text, graph.versionName), PicoTheme.typography.bodySmall, "about", color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
        Gap(8.dp)
        // D26: quitting is the last row; the confirmation stays on this page.
        if (!confirmExit) {
            SecondaryButton(stringResource(R.string.btn_exit), { confirmExit = true }, "exit", Modifier.fillMaxWidth(), icon = R.drawable.ic_logout)
        } else {
            Label(stringResource(R.string.exit_q), PicoTheme.typography.titleMedium, "exit/q")
            Label(stringResource(R.string.exit_body), PicoTheme.typography.bodyMedium, "exit/body", color = PicoTheme.colorScheme.labelSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(stringResource(R.string.btn_cancel), { confirmExit = false }, "exit/cancel", Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.btn_exit_yes), onExit, "exit/yes", Modifier.weight(1f))
            }
        }
        Gap(24.dp)
    }
}

/** D26 exit: save, stop the head tracker, close the window, remove every task, end the process. */
internal fun exitApp(context: Context, graph: GoGraph) {
    Log.i(GoGraph.TAG, "EXIT save + release + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { HeadPitch.stop() }
    runCatching { WindowCtl.cancel(); WindowCtl.navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}
