package dev.solon.bucklerhome

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * One main item of a wizard page. [badge] is a short note on the right (the current choice, an
 * app count); [on] makes it an on/off item, with OK switching it.
 */
data class SplitItem(val key: String, val label: String, val badge: String? = null, val on: Boolean? = null)

private val NavWidth = 250.dp

/** The current page's Next button: Down out of a page's options goes there, not to a pill on the left. */
val LocalSetupNext = androidx.compose.runtime.compositionLocalOf<FocusRequester?> { null }

/**
 * A wizard page laid out like Settings → Appearance: the main items down the left, the focused
 * one's options on the right. Focus picks the item (so its options show as you move); Right goes
 * into them and Left comes back to the item they belong to. OK on an on/off item switches it, on
 * any other item it goes into its options.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SetupSplit(
    items: List<SplitItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onToggle: (String) -> Unit = {},
    content: @Composable (String) -> Unit,
) {
    val requesters = remember(items.map { it.key }) { items.associate { it.key to FocusRequester() } }
    val panel = remember { FocusRequester() }
    val next = LocalSetupNext.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(modifier = Modifier.width(NavWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                SplitPill(
                    item = item,
                    selected = item.key == selected,
                    focusRequester = requesters.getValue(item.key),
                    onFocused = { onSelect(item.key) },
                ) {
                    if (item.on != null) onToggle(item.key) else runCatching { panel.requestFocus() }
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(panel)
                // Left from the options returns to their own item, not the nearest pill.
                .focusProperties {
                    exit = { direction ->
                        when (direction) {
                            FocusDirection.Left -> requesters[selected] ?: FocusRequester.Default
                            FocusDirection.Down -> next ?: FocusRequester.Default
                            else -> FocusRequester.Default
                        }
                    }
                }
                .focusGroup(),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
                content(selected)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SplitPill(
    item: SplitItem,
    selected: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ColorBrand.copy(alpha = 0.35f) else ColorTile,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.on != null) {
                Text(if (item.on) "●" else "○", fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
            }
            Text(item.label, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (item.badge != null) {
                Spacer(Modifier.width(8.dp))
                Text(item.badge, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
