package dev.solon.bucklerhome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Where the panel is: its first list, a section's own list, or a page. */
private sealed interface PanelNode {
    data object Root : PanelNode
    data class Section(val section: SettingsPane) : PanelNode
    data class Page(val pane: SettingsPane) : PanelNode
}

private fun SettingsPane.hasPages() = SettingsPane.entries.any { it.parent == this }

/** The lists leading to [start], so Back steps out through them; just the first list without one. */
private fun pathTo(start: SettingsPane?): List<PanelNode> {
    val parent = start?.parent
    return when {
        start == null -> listOf(PanelNode.Root)
        parent != null -> listOf(PanelNode.Root, PanelNode.Section(parent), PanelNode.Page(start))
        start.hasPages() -> listOf(PanelNode.Root, PanelNode.Section(start), PanelNode.Page(start))
        else -> listOf(PanelNode.Root, PanelNode.Page(start))
    }
}

/** About a third of the screen, as SHIELD's panel; a little wider, as some pages hold pictures. */
private val PanelWidth = 440.dp
private const val SLIDE_MS = 220L

/**
 * Buckler Settings as SHIELD's own settings look: a dark panel sliding in from the right over
 * whatever is on screen, which stays visible behind it. One list at a time: OK goes into a row,
 * Back comes out (and closes it from the first list). Pages show their choices as list rows
 * ([LocalSettingsPanel]); Home behind redraws as they change.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BucklerSettingsPanel(start: SettingsPane?, opened: Int, onClose: () -> Unit) {
    val stack = remember(opened) { mutableStateListOf<PanelNode>().apply { addAll(pathTo(start)) } }
    // Which row each list was left from, so coming back puts focus on it again.
    val leftFrom = remember { mutableStateMapOf<PanelNode, String>() }
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val still = AppearanceStore.config.reduceMotion
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        if (!still) delay(SLIDE_MS)
        onClose()
    }
    fun open(from: PanelNode, row: String, next: PanelNode) {
        leftFrom[from] = row
        stack.add(next)
    }
    BackHandler {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        } else {
            shown = false
            closing = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Home, or the app, stays in view behind, dimmed.
        AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = if (still) fadeIn() else slideInHorizontally { it },
            exit = if (still) fadeOut() else slideOutHorizontally { it },
        ) {
            Column(
                Modifier
                    .width(PanelWidth)
                    .fillMaxHeight()
                    .background(ColorFooter)
                    .padding(start = 28.dp, end = 36.dp, top = 27.dp),
            ) {
                val depth = stack.size
                AnimatedContent(
                    targetState = stack.last() to depth,
                    transitionSpec = {
                        val forward = targetState.second > initialState.second
                        if (still) {
                            fadeIn() togetherWith fadeOut()
                        } else {
                            slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn() togetherWith fadeOut()
                        }
                    },
                    label = "panel",
                ) { (node, _) ->
                    PanelScreen(node, stack.getOrNull(stack.indexOf(node) - 1), leftFrom[node]) { row, next -> open(node, row, next) }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelScreen(node: PanelNode, above: PanelNode?, returnTo: String?, onOpen: (String, PanelNode) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        when (node) {
            PanelNode.Root -> {
                PanelTitle("Buckler Settings", "buckler-home ${BuildConfig.VERSION_NAME}")
                PanelList(
                    rows = SettingsPane.entries.filter { it.parent == null }.map { it.name to it.label },
                    returnTo = returnTo,
                ) { key ->
                    val pane = SettingsPane.valueOf(key)
                    onOpen(key, if (pane.hasPages()) PanelNode.Section(pane) else PanelNode.Page(pane))
                }
            }
            is PanelNode.Section -> {
                PanelTitle(node.section.label, null)
                val pages = listOf(node.section.name to "Global") +
                    SettingsPane.entries.filter { it.parent == node.section }.map { it.name to it.label }
                PanelList(pages, returnTo) { key -> onOpen(key, PanelNode.Page(SettingsPane.valueOf(key))) }
            }
            is PanelNode.Page -> {
                // The page carries its own title; above it, the section it sits in (not on the
                // section's own Global page, whose title is the section's).
                val trail = (above as? PanelNode.Section)?.section?.takeIf { it != node.pane }?.label
                if (trail != null) Text(trail, color = ColorMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                PanelPage(node.pane)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelTitle(title: String, subtitle: String?) {
    Text(title, color = ColorText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) Text(subtitle, color = ColorMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(18.dp))
}

/** A list of rows that each open something; focus starts on [returnTo] when coming back to it. */
@Composable
private fun PanelList(rows: List<Pair<String, String>>, returnTo: String?, onOpen: (String) -> Unit) {
    val requesters = remember(rows) { rows.associate { it.first to FocusRequester() } }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { requesters.getValue(returnTo?.takeIf { it in requesters } ?: rows.first().first).requestFocus() }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { (key, label) -> PanelLink(label, requesters.getValue(key)) { onOpen(key) } }
    }
}

/** One row that opens a list or a page: its name, and › on the right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelLink(label: String, focusRequester: FocusRequester, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    AnnounceOnFocus(focused, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) ColorBrand else Color.Transparent)
            .strongFocusRing(focused, shape)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interaction)
            .onSelectKeyUp(onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (focused) ColorOnBrand else ColorText, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("›", color = if (focused) ColorOnBrand else ColorMuted, fontSize = 20.sp)
    }
}

/** A Settings page in the panel: its choices as rows, scrolling, with focus on its first one. */
@Composable
private fun PanelPage(pane: SettingsPane) {
    val first = remember { FocusRequester() }
    LaunchedEffect(pane) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .focusRequester(first)
            .focusGroup()
            .padding(bottom = 27.dp),
    ) {
        CompositionLocalProvider(LocalSettingsPanel provides true) {
            SettingsPageContent(pane)
        }
    }
}
