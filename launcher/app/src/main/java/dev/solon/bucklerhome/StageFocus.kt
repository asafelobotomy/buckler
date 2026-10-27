package dev.solon.bucklerhome

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One D-pad target for a Main Stage. OK runs [onClick]; Down moves to the shelves. */
@Composable
fun Modifier.stageTarget(
    focus: FocusRequester,
    below: FocusRequester,
    description: String,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    AnnounceOnFocus(focused, description)
    return this
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) ColorBrand else Color.Transparent,
            shape = shape,
        )
        .strongFocusRing(focused, shape)
        .semantics { contentDescription = description }
        .focusRequester(focus)
        .focusProperties { down = below }
        .focusable(interactionSource = interaction)
        .onSelectKeyUp(onClick)
        .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
}
