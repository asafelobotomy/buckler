package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Text button used across Settings and dialogs. [selected] marks the current choice of a group
 * (a brand-coloured outline plus a dot) so it is readable without relying on colour alone.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsAction(
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    AnnounceOnFocus(focused, label)

    var actionModifier = modifier
        .scale(focusZoom(focused, 1.06f))
        .clip(shape)
        .background(if (focused) ColorBrand else ColorTile)
        .border(
            width = if (focused) 2.dp else if (selected) 2.dp else 1.dp,
            color = when {
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

    Box(modifier = actionModifier) {
        Text(
            text = if (selected) "● $label" else label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = 15.sp,
            color = if (focused) ColorOnBrand else ColorText,
        )
    }
}
