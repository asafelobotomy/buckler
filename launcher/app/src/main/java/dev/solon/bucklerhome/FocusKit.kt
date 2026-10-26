package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Shared focus plumbing so every window starts with something focused and lit. */

/** Short pause so the window is attached (and any opening key release has passed) before focusing. */
private const val FOCUS_SETTLE_MS = 60L

/**
 * Moves focus to the first focusable descendant once the window is up, and again whenever
 * [key] changes (e.g. async content finished loading, or a wizard step changed).
 */
@Composable
fun Modifier.autoFocus(key: Any? = Unit): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        delay(FOCUS_SETTLE_MS)
        runCatching { requester.requestFocus() }
    }
    return focusRequester(requester).focusGroup()
}

/**
 * Keeps D-pad Up/Down inside this column instead of jumping to unrelated controls that merely sit
 * lower/higher on screen (e.g. from the last Settings button into the side navigation).
 * [onLeft], when given, picks the exact target for a Left exit.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trapVerticalFocus(onLeft: (() -> FocusRequester)? = null): Modifier = focusProperties {
    exit = { direction ->
        when (direction) {
            FocusDirection.Up, FocusDirection.Down -> FocusRequester.Cancel
            FocusDirection.Left -> onLeft?.invoke() ?: FocusRequester.Default
            else -> FocusRequester.Default
        }
    }
}.focusGroup()

/**
 * Dialog body. Deliberately *not* clickable/focusable itself: a clickable container
 * (`Surface(onClick = {})`) steals the initial focus and shows nothing for D-pad users.
 */
@Composable
fun DialogPanel(
    modifier: Modifier = Modifier,
    minWidth: Dp = 420.dp,
    maxWidth: Dp = 640.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .widthIn(min = minWidth, max = maxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(ColorFooter)
            .padding(28.dp),
    ) {
        content()
    }
}

/** Text input with a visible focus ring, so D-pad users can tell it is selected. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = interaction,
        textStyle = TextStyle(color = ColorText, fontSize = 16.sp),
        cursorBrush = SolidColor(ColorBrand),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .widthIn(min = 280.dp)
            .clip(shape)
            .background(if (focused) ColorTile else ColorFooter)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ColorBrand else ColorHint,
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(text = hint, fontSize = 16.sp, color = ColorHint)
            }
            inner()
        },
    )
}
