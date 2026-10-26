package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV-friendly open + context menu for leanback tiles.
 *
 * Touch long-press alone is unreliable on SHIELD remotes — we also handle:
 * - Hold Select / Enter / DPAD_CENTER (~550ms timed hold)
 * - Android long-press flag (adb `input keyevent --longpress`, some remotes)
 * - Menu / Info key up
 */
class TvMenuInteraction internal constructor(
    private val onOpen: () -> Unit,
    private val onMenu: () -> Unit,
) {
    fun open() = onOpen()

    fun openMenu() = onMenu()
}

@Composable
fun rememberTvMenuInteraction(
    onOpen: () -> Unit,
    onMenu: () -> Unit,
): TvMenuInteraction {
    val openLatest by rememberUpdatedState(onOpen)
    val menuLatest by rememberUpdatedState(onMenu)
    return remember {
        TvMenuInteraction(
            onOpen = { openLatest() },
            onMenu = { menuLatest() },
        )
    }
}


@Composable
fun Modifier.tvMenuKeys(
    interaction: TvMenuInteraction,
    longPressMs: Long = 550L,
): Modifier {
    val scope = rememberCoroutineScope()
    var longJob by remember { mutableStateOf<Job?>(null) }
    var longFired by remember { mutableStateOf(false) }
    var selectDown by remember { mutableStateOf(false) }

    fun cancelLongJob() {
        longJob?.cancel()
        longJob = null
    }

    fun fireMenu() {
        longFired = true
        interaction.openMenu()
    }

    return onPreviewKeyEvent { event ->
        val key = event.key
        when {
            event.type == KeyEventType.KeyUp && key.isTvMenuKey() -> {
                cancelLongJob()
                selectDown = false
                fireMenu()
                true
            }
            event.type == KeyEventType.KeyDown && key.isTvSelectKey() -> {
                // adb --longpress and some remotes deliver a single long-press KeyDown.
                if (event.nativeKeyEvent.isLongPress) {
                    cancelLongJob()
                    selectDown = true
                    fireMenu()
                    return@onPreviewKeyEvent true
                }
                // Auto-repeats of a held key must not re-arm the hold timer. A fresh press
                // always resets state (the previous KeyUp may have gone to the menu dialog).
                if (!event.isKeyRepeat()) {
                    selectDown = true
                    longFired = false
                    cancelLongJob()
                    longJob = scope.launch {
                        delay(longPressMs)
                        if (selectDown) {
                            fireMenu()
                        }
                    }
                }
                // Consume so TV Surface does not treat hold as a normal click.
                true
            }
            event.type == KeyEventType.KeyUp && key.isTvSelectKey() -> {
                selectDown = false
                cancelLongJob()
                if (longFired) {
                    longFired = false
                    true
                } else {
                    interaction.open()
                    true
                }
            }
            else -> false
        }
    }
}
