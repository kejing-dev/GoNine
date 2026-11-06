package io.embodit.gonine.ui.control

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

private const val TAG = "GoNine"

/** D13: every one-line label logs `OVERFLOW <where>` instead of hiding behind an ellipsis. */
@Composable
internal fun Label(text: String, style: TextStyle, where: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = PicoTheme.colorScheme.labelPrimary, maxLines: Int = 1) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $where \"$text\"") },
    )
}

/** The main action of a page: 56 dp, the interaction colour. */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, where: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true, height: Dp = 56.dp) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = height, minWidth = 160.dp),
        shape = RoundedCornerShape(height / 2),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimaryLight)
                Spacer(Modifier.width(8.dp))
            }
            Label(text, PicoTheme.typography.titleMedium, "button/$where", color = PicoTheme.colorScheme.labelPrimaryLight)
        }
    }
}

/** A secondary action on glass: ≥ 48 dp, icon + text (B19: no bare icons). */
@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, where: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true, height: Dp = 52.dp) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = height, minWidth = 96.dp),
        shape = RoundedCornerShape(height / 2),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Spacer(Modifier.width(6.dp))
            }
            Label(text, PicoTheme.typography.labelLarge, "button/$where")
        }
    }
}

/** One option of a choice row: the selected one takes the interaction colour. 48 dp. */
@Composable
internal fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, where: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 72.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (selected) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        Label(text, PicoTheme.typography.labelLarge, "choice/$where", color = if (selected) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary)
    }
}

/** A labelled row of options (same-page option switch, not page navigation). */
@Composable
internal fun ChoiceRow(label: String, where: String, labelWidth: Dp = 168.dp, content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, PicoTheme.typography.labelLarge, "row/$where", modifier = Modifier.width(labelWidth), color = PicoTheme.colorScheme.labelSecondary)
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))
