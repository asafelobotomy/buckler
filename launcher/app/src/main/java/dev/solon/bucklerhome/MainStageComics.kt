package dev.solon.bucklerhome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the Comics card shows for the current strip. */
private sealed interface StripView {
    data object Loading : StripView

    /** The strip's ink, panel by panel (or whole), on its paper colour. */
    class Ink(val strip: InkStrip) : StripView

    /** A strip whose ink could not be separated (a photo on a dark cloth, say): shown as it is. */
    class Plain(val file: File, val paper: Color?) : StripView
}

/**
 * Main Stage → Comics: public-domain newspaper strips, a new one every [StagePrefs.comicTurn].
 * ◀ ▶ step through them and OK opens the strip in the reader ([ComicReaderDialog]). Each strip's
 * line drawing is separated from its paper and, for the curated set ([CuratedComics]), cut into
 * its panels, laid out as cells across the card.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ComicsStage(stageFocus: FocusRequester, onDown: () -> Unit, onHold: () -> Unit, metrics: StageMetrics) {
    val context = LocalContext.current
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    val prefsTick = rememberPrefsChanges()
    val turn = remember(prefsTick) { StagePrefs.comicTurn(context) }
    val source = remember(prefsTick) { StagePrefs.comicSource(context) }
    val strips by produceState(emptyList<ComicStrip>(), visible, source) {
        if (!visible || value.isNotEmpty() && value.first().fromSource() == source) return@produceState
        value = emptyList()
        while (value.isEmpty()) {
            value = withContext(Dispatchers.IO) {
                when (source) {
                    ComicSource.MICKEY_1930 -> CuratedComics.strips(context).shuffled()
                    ComicSource.ASSORTED -> ComicStrips.index(context).map { ComicStrip(it.url, it.credit) }
                }
            }
            if (value.isEmpty()) delay(CURIOSITY_RETRY_MS)
        }
    }
    var index by remember { mutableIntStateOf(0) }
    // The strip open in the reader (OK); the stage holds still while it is.
    var reading by remember { mutableStateOf<ComicStrip?>(null) }
    // Bumped by ◀ ▶ so the timer restarts and a picked strip stays its full time.
    var stepped by remember { mutableIntStateOf(0) }
    LaunchedEffect(strips, turn, stepped, visible, reading == null) {
        if (!visible || strips.size < 2 || reading != null) return@LaunchedEffect
        while (true) {
            delay(turn.millis)
            index = (index + 1) % strips.size
        }
    }
    fun step(by: Int) {
        if (strips.size < 2) return
        index = (index + by).mod(strips.size)
        stepped++
    }
    val strip = strips.getOrNull(index.mod(strips.size.coerceAtLeast(1)))
    // The cells are drawn at the card's own height in pixels, so nothing is scaled up on screen.
    val cellPx = with(LocalDensity.current) { (metrics.cardHeight - 8.dp).roundToPx() }
    // Downloaded with Buckler's user agent (Wikimedia asks for one) into the photo cache, then
    // inked on a background thread; one that fails is skipped. The next is fetched ahead.
    val view by produceState<StripView>(StripView.Loading, strip, cellPx) {
        value = StripView.Loading
        strip ?: return@produceState
        val file = withContext(Dispatchers.IO) { ScreensaverImages.download(context, CommonsImage(strip.url, strip.credit)) }
        if (file == null) {
            delay(2_000)
            step(1)
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            ComicInk.prepare(file, strip.panels, cellPx)?.let { StripView.Ink(it) }
                ?: StripView.Plain(file, ComicStrips.paperColour(file)?.let(::Color))
        }
        strips.getOrNull((index + 1).mod(strips.size))?.let { next ->
            withContext(Dispatchers.IO) { ScreensaverImages.download(context, CommonsImage(next.url, next.credit)) }
        }
    }
    val paper = when (val v = view) {
        is StripView.Ink -> Color(v.strip.paper)
        is StripView.Plain -> v.paper
        StripView.Loading -> null
    }
    val cardColour by animateColorAsState(paper ?: ColorTile, animationSpec = tween(600), label = "paper")
    // Always a note line, so the stage keeps its height while the first strip loads.
    val note = strip?.credit?.let { if (strips.size > 1) "$it  ◀ ▶" else it } ?: "Public-domain newspaper strips"
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Comics", note)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .stepKeys(::step)
                .stageTarget(stageFocus, onDown, strip?.credit ?: "Comics", onLongPress = onHold) { reading = strip }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(cardColour),
        ) {
            when (val v = view) {
                is StripView.Ink -> InkCells(v.strip, Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp))
                is StripView.Plain -> AsyncImage(
                    model = v.file,
                    contentDescription = strip?.credit,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
                StripView.Loading -> Text(
                    text = if (strips.isEmpty() && !ScreensaverImages.online(context)) "Comics need the internet." else "Loading a strip…",
                    color = ColorMuted,
                    fontSize = 15.sp,
                )
            }
        }
    }
    reading?.let { open -> ComicReaderDialog(open) { reading = null } }
}

private fun ComicStrip.fromSource(): ComicSource =
    if (url.startsWith("https://iiif.archive.org/")) ComicSource.MICKEY_1930 else ComicSource.ASSORTED

/**
 * The strip's cells side by side, all the same height: the card's full height when they fit
 * across it, else smaller so they do, spread evenly with the space left over.
 */
@Composable
private fun InkCells(strip: InkStrip, modifier: Modifier) {
    val images = remember(strip) { strip.cells.map { it.asImageBitmap() } }
    BoxWithConstraints(modifier) {
        val gap = 14.dp
        val ratios = images.map { it.width.toFloat() / it.height }
        val across = maxHeight * ratios.sum() + gap * (images.size - 1)
        val height = maxHeight * (maxWidth / across).coerceAtMost(1f)
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            images.forEachIndexed { i, image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.size(width = height * ratios[i], height = height),
                )
            }
        }
    }
}
