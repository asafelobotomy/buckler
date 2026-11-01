package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * True inside the Buckler Settings panel: [SettingsAction]s there are full-width list rows (one
 * per line, the current choice marked on the right) rather than buttons in a row, as in SHIELD's
 * own settings panel. Cards share a narrower width too ([CardColumns]).
 */
val LocalSettingsPanel = compositionLocalOf { false }

/** What a [SettingsAction] row shows on its right in the panel. */
enum class RowMark { NONE, DOT, CHECK }

/**
 * Text button used across Settings and dialogs. [selected] marks the current choice of a group
 * (a brand-coloured outline plus a dot) so it is readable without relying on colour alone. In the
 * panel a row can instead show a [value] on its right (On / Off, or the current choice of a list
 * it opens), and [mark] CHECK marks one of several chosen, as SHIELD's lists do.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsAction(
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    value: String? = null,
    mark: RowMark = RowMark.DOT,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    AnnounceOnFocus(focused, if (value != null) "$label, $value" else label)

    val panel = LocalSettingsPanel.current
    var actionModifier = modifier
        // A full-width row would grow out of the panel.
        .scale(if (panel) 1f else focusZoom(focused, 1.06f))
        .clip(shape)
        // A panel row is plain until focused, as in SHIELD's lists; the dot marks the current choice.
        .background(if (focused) ColorBrand else if (panel) Color.Transparent else ColorTile)
        .border(
            width = if (panel) 0.dp else if (focused) 2.dp else if (selected) 2.dp else 1.dp,
            color = when {
                panel -> Color.Transparent
                focused -> ColorText.copy(alpha = 0.55f)
                selected -> ColorBrand
                else -> ColorFooter
            },
            shape = shape,
        )
        .strongFocusRing(focused, shape)
    if (focusRequester != null) {
        actionModifier = actionModifier.focusRequester(focusRequester)
    }
    actionModifier = actionModifier
        .focusable(interactionSource = interactionSource)
        .onSelectKeyUp(onClick)

    val sign = when {
        !selected -> ""
        mark == RowMark.CHECK -> "✓"
        mark == RowMark.DOT -> "●"
        else -> ""
    }
    if (panel) {
        Row(modifier = actionModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                fontSize = 15.sp,
                color = if (focused) ColorOnBrand else ColorText,
            )
            if (value != null) {
                Text(
                    text = value,
                    modifier = Modifier.padding(start = 12.dp, end = if (sign.isEmpty()) 16.dp else 0.dp),
                    fontSize = 14.sp,
                    color = if (focused) ColorOnBrand else ColorMuted,
                )
            }
            Text(
                text = sign,
                modifier = Modifier.padding(horizontal = if (sign.isEmpty() && value != null) 0.dp else 16.dp),
                fontSize = if (mark == RowMark.CHECK) 15.sp else 13.sp,
                color = if (focused) ColorOnBrand else ColorBrand,
            )
        }
        return
    }
    Box(modifier = actionModifier) {
        Text(
            text = listOfNotNull(sign.ifEmpty { null }, label + (value?.let { ": $it" } ?: "")).joinToString(" "),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = 15.sp,
            color = if (focused) ColorOnBrand else ColorText,
        )
    }
}
