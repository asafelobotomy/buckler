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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

fun Key.isTvSelectKey(): Boolean =
    this == Key.DirectionCenter ||
        this == Key.Enter ||
        this == Key.NumPadEnter ||
        this == Key.ButtonA

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsAction(
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    var actionModifier = modifier
        .scale(if (focused) 1.06f else 1f)
        .clip(shape)
        .background(if (focused) ColorBrand else ColorTile)
        .border(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) ColorText.copy(alpha = 0.55f) else ColorFooter,
            shape = shape,
        )
    if (focusRequester != null) {
        actionModifier = actionModifier.focusRequester(focusRequester)
    }
    actionModifier = actionModifier
        .focusable(interactionSource = interactionSource)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key.isTvSelectKey()) {
                onClick()
                true
            } else {
                false
            }
        }

    Box(modifier = actionModifier) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = 15.sp,
            color = if (focused) ColorOnBrand else ColorText,
        )
    }
}
