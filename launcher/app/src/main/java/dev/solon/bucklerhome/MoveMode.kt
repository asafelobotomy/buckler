package dev.solon.bucklerhome

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * "Move" from a menu: the dialog closes and the arrow keys slide the target until Select places
 * it (Back cancels). Apps move Left/Right along the Apps shelf; with [vertical] a whole shelf
 * moves Up/Down the Home screen. State lives here; HomeScreen applies the layout edits.
 */
@Stable
class MoveMode(private val vertical: Boolean = false) {
    /** Package name (apps) or shelf id (shelves) being moved. */
    var target by mutableStateOf<String?>(null)
        private set

    /** Layout to restore if the move is cancelled. */
    var original: HomeLayout? = null
        private set

    /** After placing with Select, the matching KeyUp must not reach the tile and launch the app. */
    private var swallowSelectUp = false

    val active: Boolean get() = target != null

    fun begin(id: String, layout: HomeLayout) {
        target = id
        original = layout
        swallowSelectUp = false
    }

    fun end(placedWithSelect: Boolean) {
        target = null
        original = null
        swallowSelectUp = placedWithSelect
    }

    /** Returns true when the event was consumed. */
    internal fun handle(event: KeyEvent, onStep: (Int) -> Unit, onPlace: () -> Unit): Boolean {
        val key = event.key
        if (!active) {
            if (swallowSelectUp && key.isTvSelectKey()) {
                if (event.type == KeyEventType.KeyUp) swallowSelectUp = false
                return true
            }
            return false
        }
        val down = event.type == KeyEventType.KeyDown
        val back = if (vertical) Key.DirectionUp else Key.DirectionLeft
        val forward = if (vertical) Key.DirectionDown else Key.DirectionRight
        return when {
            key == back -> { if (down) onStep(-1); true }
            key == forward -> { if (down) onStep(1); true }
            // Focus moves on the other axis and the menu key would drop the target out of move mode.
            key.isArrowKey() || key.isTvMenuKey() -> true
            key.isTvSelectKey() -> {
                // Place on a fresh press only; auto-repeat of the press that opened Move is ignored.
                if (down && !event.isKeyRepeat()) onPlace()
                true
            }
            else -> false
        }
    }
}

private fun Key.isArrowKey(): Boolean =
    this == Key.DirectionLeft || this == Key.DirectionRight || this == Key.DirectionUp || this == Key.DirectionDown

fun Modifier.moveModeKeys(mode: MoveMode, onStep: (Int) -> Unit, onPlace: () -> Unit): Modifier =
    onPreviewKeyEvent { mode.handle(it, onStep, onPlace) }

/** Reorders the Apps shelf to match [order] without waiting for a full shelf reload. */
fun List<ResolvedShelf>.withAppsOrder(order: List<String>): List<ResolvedShelf> = map { shelf ->
    if (shelf.config.type != ShelfType.APPS) {
        shelf
    } else {
        shelf.copy(items = shelf.items.sortedBy { item ->
            order.indexOf(item.packageName).let { if (it < 0) Int.MAX_VALUE else it }
        })
    }
}
