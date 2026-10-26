package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

val PreviewCardWidth = 176.dp
val PreviewHeight = 92.dp

/**
 * A selectable card showing a picture of the option plus its name, so layouts, colour schemes and
 * fonts can be judged by eye. The current choice is outlined (and marked with a dot), focus fills.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PreviewCard(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    width: Dp = PreviewCardWidth,
    onClick: () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    AnnounceOnFocus(focused, if (selected) "$label, selected" else label)

    var card = modifier
        .width(width)
        .scale(focusZoom(focused, 1.05f))
        .clip(shape)
        .background(if (focused) ColorBrand else ColorFooter)
        .border(
            width = if (selected || focused) 3.dp else 1.dp,
            color = when {
                focused -> ColorText.copy(alpha = 0.6f)
                selected -> ColorBrand
                else -> ColorTile
            },
            shape = shape,
        )
        .strongFocusRing(focused, shape)
    if (focusRequester != null) card = card.focusRequester(focusRequester)
    card = card
        .semantics { contentDescription = if (selected) "$label, selected" else label }
        .focusable(interactionSource = interaction)
        .onSelectKeyUp(onClick)
        .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }

    Column(modifier = card.padding(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PreviewHeight)
                .clip(RoundedCornerShape(8.dp)),
            content = preview,
        )
        Text(
            text = if (selected) "● $label" else label,
            color = if (focused) ColorOnBrand else ColorText,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp).align(Alignment.Start),
        )
    }
}
