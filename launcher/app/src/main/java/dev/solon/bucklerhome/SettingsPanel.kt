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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Where the panel is: its first list, a section's own list, a page, or a list a page opened. */
private sealed interface PanelNode {
    data object Root : PanelNode
    data class Section(val section: SettingsPane) : PanelNode
    data class Page(val pane: SettingsPane) : PanelNode

    /** Compared by [id] alone: each opening is its own level. */
    class Sub(val id: Int, val title: String, val content: @Composable () -> Unit) : PanelNode {
        override fun equals(other: Any?) = other is Sub && other.id == id
        override fun hashCode() = id
    }
}

private const val ALL_SETTINGS_ROW = "android:all"

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
    // Whether the row a page was left from has taken focus back, so the page need not.
    val refocused = remember { mutableStateMapOf<PanelNode, Boolean>() }
    var nextSub by remember { mutableIntStateOf(0) }
    fun open(from: PanelNode, row: String, next: PanelNode) {
        leftFrom[from] = row
        refocused[from] = false
        stack.add(next)
    }
    // A level left by Back starts afresh next time; the one it returns to keeps its row.
    fun up() {
        if (stack.size > 1) leftFrom.remove(stack.removeAt(stack.lastIndex))
    }
    fun nav(node: PanelNode) = PanelNav(
        open = { row, title, content -> open(node, row, PanelNode.Sub(nextSub++, title, content)) },
        openPane = { row, pane -> open(node, row, PanelNode.Page(pane)) },
        back = ::up,
        returnTo = leftFrom[node],
        claimFocus = { refocused[node] = true },
        focusClaimed = { refocused[node] == true },
    )
    BackHandler {
        if (stack.size > 1) {
            up()
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
                    CompositionLocalProvider(LocalPanelNav provides nav(node)) {
                        PanelScreen(node, stack.getOrNull(stack.indexOf(node) - 1), leftFrom[node]) { row, next -> open(node, row, next) }
                    }
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
                val context = LocalContext.current
                PanelTitle("Buckler Settings", "buckler-home ${BuildConfig.VERSION_NAME}")
                val rows = SettingsPane.entries.filter { it.parent == null }.map { PanelRow(it.name, it.label) } +
                    PanelRow(ALL_SETTINGS_ROW, "All Android settings", external = true)
                PanelList(rows, returnTo) { key ->
                    when (key) {
                        ALL_SETTINGS_ROW -> SystemScreen.ALL.open(context)
                        else -> {
                            val pane = SettingsPane.valueOf(key)
                            onOpen(key, if (pane.hasPages()) PanelNode.Section(pane) else PanelNode.Page(pane))
                        }
                    }
                }
            }
            is PanelNode.Section -> {
                PanelTitle(node.section.label, null)
                val pages = listOf(node.section.name to node.section.ownPage) +
                    SettingsPane.entries.filter { it.parent == node.section }.map { it.name to it.label }
                PanelList(pages.map { (key, label) -> PanelRow(key, label) }, returnTo) { key ->
                    onOpen(key, PanelNode.Page(SettingsPane.valueOf(key)))
                }
            }
            is PanelNode.Page -> {
                // The page carries its own title; above it, the section it sits in (not on the
                // section's own Global page, whose title is the section's).
                val trail = (above as? PanelNode.Section)?.section?.takeIf { it != node.pane }?.label
                if (trail != null) Text(trail, color = ColorMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                PanelPage(node.pane)
            }
            is PanelNode.Sub -> {
                PanelTitle(node.title, null)
                PanelPage(node.id) { node.content() }
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

/** A row in a [PanelList]; [external] ones open one of Android's own screens. */
private data class PanelRow(val key: String, val label: String, val external: Boolean = false)

/** A list of rows that each open something; focus starts on [returnTo] when coming back to it. */
@Composable
private fun PanelList(rows: List<PanelRow>, returnTo: String?, onOpen: (String) -> Unit) {
    val requesters = remember(rows) { rows.associate { it.key to FocusRequester() } }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { requesters.getValue(returnTo?.takeIf { it in requesters } ?: rows.first().key).requestFocus() }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { row -> PanelLink(row.label, requesters.getValue(row.key), row.external) { onOpen(row.key) } }
    }
}

/** One row that opens a list or a page: its name, and › on the right (↗ for one of Android's screens). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelLink(label: String, focusRequester: FocusRequester, external: Boolean, onClick: () -> Unit) {
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
        Text(if (external) "↗" else "›", color = if (focused) ColorOnBrand else ColorMuted, fontSize = if (external) 16.sp else 20.sp)
    }
}

/** A Settings page in the panel: its choices as rows, scrolling, with focus on its first one. */
@Composable
private fun PanelPage(pane: SettingsPane) = PanelPage(pane) { SettingsPageContent(pane) }

/**
 * A page's rows, scrolling, with focus on the first. A row that reopens a level (a [SettingsLink])
 * takes focus back itself when the page is returned to.
 */
@Composable
private fun PanelPage(page: Any, content: @Composable () -> Unit) {
    val first = remember { FocusRequester() }
    val nav = LocalPanelNav.current
    LaunchedEffect(page) {
        // Coming back: the row it was left from takes focus; the first row only if that one has gone.
        delay(if (nav?.returnTo != null) 160 else 60)
        if (nav?.focusClaimed?.invoke() == true) return@LaunchedEffect
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
            content()
        }
    }
}
