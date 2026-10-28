package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Home's way back to Settings when the shelves are gone or the header has neither Edit Home nor
 * Settings. Without it, Home could be left with nothing that leads to the screens that undo it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeOptionsCard(
    onReset: () -> Unit,
    onSettings: () -> Unit,
    /** Down from the Main Stage, when this card is the only thing under it. */
    fromStage: FocusRequester?,
    up: FocusRequester?,
    bottom: FocusRequester?,
) {
    Column {
        Text(
            text = "Home options",
            color = ColorSection,
            fontSize = 14.sp,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .semantics {
                    contentDescription = "Home options"
                    heading()
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Nothing on Home leads to Settings. Reset it, or open Settings to choose what shows.",
                color = ColorMuted,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            val first = Modifier
                .then(if (fromStage != null) Modifier.focusRequester(fromStage) else Modifier)
                .then(if (bottom != null) Modifier.focusRequester(bottom) else Modifier)
            SettingsAction("Reset Home to Default", modifier = first.upTo(up), onClick = onReset)
            SettingsAction("Open Settings", modifier = Modifier.upTo(up), onClick = onSettings)
        }
    }
}

/** True when Home must show [HomeOptionsCard]. */
fun needsHomeOptions(config: DisplayConfig, shelves: List<ResolvedShelf>): Boolean =
    shelves.isEmpty() || !config.reachesSettings

/** Requesters on a container: focus enters its first composed child. Empty: no group at all. */
fun Modifier.focusGroupTarget(requesters: List<FocusRequester>): Modifier =
    if (requesters.isEmpty()) this else requesters.fold(this) { m, r -> m.focusRequester(r) }.focusGroup()

private fun Modifier.upTo(up: FocusRequester?): Modifier =
    if (up != null) focusProperties { this.up = up } else this

/**
 * Wrap-around at the top and bottom of Home: when [direction] has no focusable left on screen,
 * [onWrap] moves focus to the other end. Runs after children have had the key, so their own
 * Up / Down handling still wins.
 */
fun Modifier.wrapFocus(direction: FocusDirection, focusManager: FocusManager, onWrap: () -> Unit): Modifier =
    onKeyEvent { event ->
        val key = if (direction == FocusDirection.Up) Key.DirectionUp else Key.DirectionDown
        if (event.key != key || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (!focusManager.moveFocus(direction) && !event.isKeyRepeat()) onWrap()
        true
    }
