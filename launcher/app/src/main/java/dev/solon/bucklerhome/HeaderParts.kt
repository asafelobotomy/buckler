package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * D-pad focusable header control. [dim] greys the glyph for an inactive state (for example VPN
 * off) while keeping it selectable. [label] sits beside the glyph when there is a short status.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeaderIconButton(
    icon: Int,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    dim: Boolean = false,
    /** Short status beside the glyph, e.g. the VPN exit country. */
    label: String? = null,
    /** Where D-pad Down goes; without it the spatial search can land on a neighbouring icon. */
    down: FocusRequester? = null,
    /** Down handled in code, so a shelf that is not composed yet cannot crash the activity. */
    onDown: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    /** Hold OK (or Menu); without it a hold is just a press. */
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val menu = rememberTvMenuInteraction(onOpen = onClick, onMenu = { onLongPress?.invoke() })
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    AnnounceOnFocus(focused, description)
    val tint = when {
        focused -> ColorOnBrand
        dim -> ColorHint
        else -> ColorText
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (label.isNullOrEmpty()) Modifier.size(size) else Modifier.height(size).padding(horizontal = 8.dp))
            .scale(focusZoom(focused, 1.1f))
            .clip(shape)
            .background(if (focused) ColorBrand else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) ColorText.copy(alpha = 0.55f) else Color.Transparent,
                shape = shape,
            )
            .strongFocusRing(focused, shape)
            .semantics { contentDescription = description }
            .then(
                when {
                    onDown != null -> Modifier.downTo(onDown)
                    down != null -> Modifier.focusProperties { this.down = down }
                    else -> Modifier
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interaction)
            .then(if (onLongPress != null) Modifier.tvMenuKeys(menu) else Modifier.onSelectKeyUp(onClick))
            .pointerInput(onClick, onLongPress) {
                detectTapGestures(onLongPress = onLongPress?.let { { _ -> menu.openMenu() } }, onTap = { menu.open() })
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(size * 0.6f),
            )
            if (!label.isNullOrEmpty()) {
                Text(
                    text = label,
                    color = tint,
                    fontSize = (14 * (size.value / 44f)).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}
