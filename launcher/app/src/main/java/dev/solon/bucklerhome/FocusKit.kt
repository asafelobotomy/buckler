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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

/**
 * Text input with a visible focus ring, so D-pad users can tell it is selected. A stop like any
 * other: focus passing over it leaves the keyboard closed and Up / Down move on; OK starts typing.
 * Until then it is read-only, as the TV keyboard opens itself for any editable field in focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** Shown as dots, with the keyboard's password layout. */
    password: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (!focused) typing = false }
    LaunchedEffect(typing) { if (typing) keyboard?.show() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        readOnly = !typing,
        interactionSource = interaction,
        textStyle = TextStyle(color = ColorText, fontSize = 16.sp),
        cursorBrush = SolidColor(ColorBrand),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            showKeyboardOnFocus = false,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardActions = KeyboardActions(onDone = {
            typing = false
            keyboard?.hide()
            onDone()
        }),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown) {
                            focusManager.moveFocus(if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                        }
                        true
                    }
                    !typing && event.key.isTvSelectKey() -> {
                        if (event.type == KeyEventType.KeyUp) typing = true
                        true
                    }
                    else -> false
                }
            }
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
