package io.embodit.gonine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.embodit.gonine.platform.GoGraph
import io.embodit.gonine.ui.theme.GoTheme

/** The theme of every window, following the in-app language setting. */
@Composable
fun AppRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { GoGraph.from(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    GoTheme(language = settings.language, content = content)
}
