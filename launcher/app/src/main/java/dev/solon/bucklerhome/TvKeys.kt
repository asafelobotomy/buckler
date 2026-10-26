package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Single home for remote-control key semantics shared by tiles, dialogs and settings rows. */

fun Key.isTvSelectKey(): Boolean =
    this == Key.DirectionCenter ||
        this == Key.Enter ||
        this == Key.NumPadEnter ||
        this == Key.ButtonA

fun Key.isTvMenuKey(): Boolean = this == Key.Menu || this == Key.Info

/** True for held-key auto-repeat KeyDown events (not a fresh press). */
fun KeyEvent.isKeyRepeat(): Boolean = nativeKeyEvent.repeatCount > 0

/** Activate on Select *release*, so a press that started elsewhere never triggers it. */
@Composable
fun Modifier.onSelectKeyUp(onSelect: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyUp && event.key.isTvSelectKey()) {
        onSelect()
        true
    } else {
        false
    }
}

/**
 * For dialogs opened by a long-press while Select is still held.
 *
 * Auto-repeats and the eventual release belong to the press that opened the dialog, so they are
 * swallowed. Only a KeyUp that follows a fresh KeyDown (repeatCount == 0) inside the dialog
 * reaches child buttons.
 */
@Composable
fun Modifier.swallowOrphanSelect(): Modifier {
    var freshDown by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        if (!event.key.isTvSelectKey()) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.isKeyRepeat()) {
                    true
                } else {
                    freshDown = true
                    false
                }
            }
            KeyEventType.KeyUp -> {
                val orphan = !freshDown
                freshDown = false
                orphan
            }
            else -> false
        }
    }
}
