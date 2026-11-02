package dev.solon.bucklerhome

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One stop in the reader: a region of one of the strip's inked images. */
class ReaderPanel(val image: ImageBitmap, val offset: IntOffset, val size: IntSize)

/**
 * The reader's stops for [cells]: each panel as it is, or, for a strip shown whole (one cell), its
 * panels found from the gutters between their frames ([gutterCuts]), else even slices about a
 * panel wide. A gutter missed only makes one stop wider, which the reader shows whole anyway.
 */
fun readerPanels(cells: List<Bitmap>, panelsKnown: Boolean): List<ReaderPanel> {
    val images = cells.map { it.asImageBitmap() }
    if (panelsKnown || cells.size != 1) return images.map { ReaderPanel(it, IntOffset.Zero, IntSize(it.width, it.height)) }
    val whole = cells.single()
    val w = whole.width
    val h = whole.height
    val cuts = gutterCuts(inkRuns(whole), h).ifEmpty {
        val slices = readerSlices(w, h)
        (1 until slices).map { w * it / slices }
    }
    val edges = listOf(0) + cuts + w
    return edges.zipWithNext { left, right -> ReaderPanel(images.single(), IntOffset(left, 0), IntSize(right - left, h)) }
}

/** For each column of [ink], its longest run of ink (opaque pixels) from top to bottom, bridging specks. */
fun inkRuns(ink: Bitmap): IntArray {
    val w = ink.width
    val h = ink.height
    val px = IntArray(w * h)
    ink.getPixels(px, 0, w, 0, 0, w, h)
    return IntArray(w) { x ->
        var best = 0
        var start = -1
        var last = -1
        for (y in 0 until h) {
            if ((px[y * w + x] ushr 24) < 128) continue
            if (start < 0 || y - last > 6) start = y
            last = y
            best = maxOf(best, last - start + 1)
        }
        best
    }
}

/**
 * Where to cut a strip [height] tall into panels, from each column's longest ink run [runs]: the
 * middle of every gutter, a narrow gap between two frame lines (columns inked nearly top to
 * bottom), and every frame line two panels share. Empty when there are none, or the panels they
 * make are implausibly thin.
 */
fun gutterCuts(runs: IntArray, height: Int): List<Int> {
    val w = runs.size
    val frames = ArrayList<IntArray>()
    for (x in 0 until w) {
        if (runs[x] < height * 3 / 4) continue
        val last = frames.lastOrNull()
        if (last != null && x - last[1] <= 2) last[1] = x else frames += intArrayOf(x, x)
    }
    val gutters = frames.zipWithNext().filter { (a, b) -> b[0] - a[1] in 2..(w * 3 / 100) }
    val paired = gutters.flatMap { (a, b) -> listOf(a, b) }.toSet()
    // Two panels can share one frame line: a thin line as tall as the frames themselves (a drawn
    // tree or post stops short of the frame's top or bottom).
    val tallest = runs.maxOrNull() ?: 0
    val shared = frames.drop(1).dropLast(1)
        .filter { it !in paired && it[1] - it[0] <= 6 && (it[0]..it[1]).maxOf { x -> runs[x] } >= tallest * 97 / 100 }
        .map { (it[0] + it[1]) / 2 }
    val cuts = (gutters.map { (a, b) -> (a[1] + b[0]) / 2 } + shared).sorted()
    val edges = listOf(0) + cuts + w
    return if (cuts.isNotEmpty() && edges.zipWithNext().all { (l, r) -> r - l >= w / 12 }) cuts else emptyList()
}

/** How many even slices a whole strip of [width] x [height] gets without gutters: one per square-ish panel, 1 to 6. */
fun readerSlices(width: Int, height: Int): Int = (width.toFloat() / height.coerceAtLeast(1) / 1.1f).toInt().coerceIn(1, 6)

/**
 * The Comics reader: the strip large across the screen, every panel a stop (◀ ▶). OK zooms the
 * focused panel to the full height of the screen, where ◀ ▶ go to the previous and next panel;
 * Back comes out of the zoom, and Back again closes the reader. The strip is fetched again at
 * twice the stage's size ([ComicStrip.largeUrl]) so a zoomed panel stays sharp.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ComicReaderDialog(strip: ComicStrip, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ready by produceState<Pair<List<ReaderPanel>, Color>?>(null, strip) {
        val file = withContext(Dispatchers.IO) {
            ScreensaverImages.download(context, CommonsImage(strip.largeUrl, strip.credit))
                ?: ScreensaverImages.download(context, CommonsImage(strip.url, strip.credit))
        } ?: return@produceState
        value = withContext(Dispatchers.Default) {
            ComicInk.prepare(file, strip.panels, READER_MAX_PX)?.let { ink ->
                readerPanels(ink.cells, strip.panels.isNotEmpty()) to Color(ink.paper)
            }
        }
    }
    var zoomed by remember(strip) { mutableStateOf<Int?>(null) }
    var focused by remember(strip) { mutableIntStateOf(0) }
    Dialog(
        // Back out of a zoom first; from the whole strip, close.
        onDismissRequest = { if (zoomed != null) zoomed = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val paper = ready?.second ?: ColorFooter
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            val panels = ready?.first
            val zoom = zoomed
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(strip.credit, color = ColorMuted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        panels == null -> ""
                        zoom == null -> "◀ ▶ choose a panel · OK zoom in · Back close"
                        else -> "${zoom + 1} of ${panels.size}  ·  ◀ ▶ panels · Back whole strip"
                    },
                    color = ColorSection,
                    fontSize = 13.sp,
                )
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(paper, RoundedCornerShape(12.dp)).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    panels == null -> Text("Loading the strip…", color = ColorHint, fontSize = 16.sp)
                    zoom != null -> ZoomedPanel(panels, zoom) {
                        zoomed = it
                        focused = it
                    }
                    else -> PanelOverview(panels, focused, { focused = it }) { zoomed = it }
                }
            }
        }
    }
}

/** The reader's cells are drawn from a copy at most this tall, about the screen's height. */
private const val READER_MAX_PX = 1100

/** Every panel side by side at the largest size that fits, each a focus stop. */
@Composable
private fun PanelOverview(panels: List<ReaderPanel>, focused: Int, onFocus: (Int) -> Unit, onZoom: (Int) -> Unit) {
    val requesters = remember(panels) { panels.map { FocusRequester() } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val gap = 12.dp
        val ratios = panels.map { it.size.width.toFloat() / it.size.height }
        val height = minOf(maxHeight, (maxWidth - gap * (panels.size - 1)) / ratios.sum())
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            panels.forEachIndexed { i, panel ->
                val interaction = remember { MutableInteractionSource() }
                val isFocused by interaction.collectIsFocusedAsState()
                LaunchedEffect(isFocused) { if (isFocused) onFocus(i) }
                PanelImage(
                    panel,
                    Modifier
                        .size(width = height * ratios[i], height = height)
                        .border(3.dp, if (isFocused) ColorBrand else Color.Transparent, RoundedCornerShape(4.dp))
                        .focusRequester(requesters[i])
                        .onKeyEvent { event ->
                            val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (ok && event.type == KeyEventType.KeyUp) onZoom(i)
                            ok
                        }
                        .focusable(interactionSource = interaction),
                )
            }
        }
    }
    // Back from a zoom puts focus on the panel just read.
    LaunchedEffect(panels) {
        delay(50)
        runCatching { requesters[focused.coerceIn(0, requesters.lastIndex)].requestFocus() }
    }
}

/** One panel filling the reader, ◀ ▶ to the previous and next. */
@Composable
private fun ZoomedPanel(panels: List<ReaderPanel>, index: Int, onIndex: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    PanelImage(
        panels[index],
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onIndex((index - 1).coerceAtLeast(0))
                    Key.DirectionRight -> onIndex((index + 1).coerceAtMost(panels.lastIndex))
                    // Up and down have nowhere to go; swallowed so focus stays on the panel.
                    Key.DirectionUp, Key.DirectionDown -> Unit
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable(),
    )
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
}

/** [panel]'s region of its image, fitted whole in the space given and centred. */
@Composable
private fun PanelImage(panel: ReaderPanel, modifier: Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / panel.size.width, size.height / panel.size.height)
        val w = (panel.size.width * scale).toInt()
        val h = (panel.size.height * scale).toInt()
        drawImage(
            image = panel.image,
            srcOffset = panel.offset,
            srcSize = panel.size,
            dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.High,
        )
    }
}
