package io.embodit.gonine.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.gonine.domain.game.Language
import java.util.Locale

/** Design tokens of Go Nine (design package mustHave.customIcon / assets / entities3d). */
object GoColors {
    // ---- ColorScheme role overrides (panel) ----
    val Interaction = Color(0xFF2F5763) // design-style: fixed-figma-color slate teal (icon #4F6E78, darkened for white text ≥ 4.5:1)
    val OnInteraction = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFF9A3524) // design-style: fixed-figma-color vermilion ✕
    val Alert = Color(0xFF9A6418) // design-style: fixed-figma-color warm amber
    val Passable = Color(0xFF3F7A5A) // design-style: fixed-figma-color passable
    val FillTertiary = Color(0xFFE3E9E6) // design-style: fixed-figma-color fillTertiary
    val DividerLine = Color(0xFFC9D3D0) // design-style: fixed-figma-color dividerLine

    // ---- board and stones ----
    val Kaya = Color(0xFFE2C48E) // design-style: fixed-figma-color kaya face (warm yellow)
    val KayaEdge = Color(0xFFC9A46A) // design-style: fixed-figma-color kaya edge
    val GridInk = Color(0xFF4A3A22) // design-style: fixed-figma-color grid lines
    val BlackStone = Color(0xFF1E2A26) // design-style: fixed-figma-color 墨绿黑云子
    val WhiteStone = Color(0xFFF2EEE4) // design-style: fixed-figma-color 乳白云子
    val BowlWood = Color(0xFF6B4A2E) // design-style: fixed-figma-color deep wood bowls
    val LidWood = Color(0xFF7A5636) // design-style: fixed-figma-color lids
    val LibertyGlow = Color(0xFFB04E00) // design-style: fixed-figma-color 气光点 (design #FFD08A was 1.17:1 on kaya; deep amber 3.18:1)
    val HintGreen = Color(0xFF17663A) // design-style: fixed-figma-color 推荐绿圈 (4.18:1 on kaya)
    val LastMove = Color(0xFF9A3524) // design-style: fixed-figma-color last-move dot
    val Brand = Color(0xFF4F6E78) // design-style: fixed-figma-color icon background

    /** Card ink on the board's glass cards: solid, not Vibrant grey (D13). */
    val CardInk = Color(0xFF1A2226) // design-style: fixed-figma-color card ink
    val CardBack = Color(0xF2F6F1E6) // design-style: fixed-figma-color card back (cream, 95 %)
}

/**
 * The app's PicoTheme: the system scheme with the design's semantic colours, Noto Sans. [language] localises
 * resources below it through a ContextWrapper (Cloud Air: a bare configuration context breaks SpatialView).
 */
@Composable
fun GoTheme(language: Language = Language.SYSTEM, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base, language) { base.localized(language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillTertiary = GoColors.FillTertiary,
        labelPrimaryLight = GoColors.OnInteraction,
        error = GoColors.Error,
        alert = GoColors.Alert,
        passable = GoColors.Passable,
        interaction = GoColors.Interaction,
        dividerLine = GoColors.DividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val localizedResources = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = localizedResources
    }
}
