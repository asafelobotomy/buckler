package dev.solon.bucklerhome

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CURIOSITY_RETRY_MS = 5 * 60_000L

/** ◀ ▶ as -1 / +1 on key down (and swallowed on key up), else null for keys the stage passes on. */
private fun Modifier.stepKeys(onStep: (Int) -> Unit): Modifier = onPreviewKeyEvent { event ->
    val step = when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        else -> return@onPreviewKeyEvent false
    }
    if (event.type == KeyEventType.KeyDown) onStep(step)
    true
}

/**
 * Main Stage → Comics: public-domain newspaper strips from Wikimedia Commons ([ComicStrips]), a
 * new one every [StagePrefs.comicTurn]. ◀ ▶ and OK step through them.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ComicsStage(stageFocus: FocusRequester, onDown: () -> Unit, onHold: () -> Unit, metrics: StageMetrics) {
    val context = LocalContext.current
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    val turn = remember(rememberPrefsChanges()) { StagePrefs.comicTurn(context) }
    val strips by produceState(emptyList<CommonsImage>(), visible) {
        if (!visible || value.isNotEmpty()) return@produceState
        while (value.isEmpty()) {
            value = withContext(Dispatchers.IO) { ComicStrips.index(context) }
            if (value.isEmpty()) delay(CURIOSITY_RETRY_MS)
        }
    }
    var index by remember { mutableIntStateOf(0) }
    // Bumped by ◀ ▶ so the timer restarts and a picked strip stays its full time.
    var stepped by remember { mutableIntStateOf(0) }
    LaunchedEffect(strips, turn, stepped, visible) {
        if (!visible || strips.size < 2) return@LaunchedEffect
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
    // Downloaded with Buckler's user agent (Wikimedia asks for one) into the photo cache, then shown
    // from disk; one that fails is skipped. The next is fetched ahead so it shows at once.
    val file by produceState<File?>(null, strip) {
        value = null
        strip ?: return@produceState
        value = withContext(Dispatchers.IO) { ScreensaverImages.download(context, strip) }
        if (value == null) {
            delay(2_000)
            step(1)
            return@produceState
        }
        strips.getOrNull((index + 1).mod(strips.size))?.let { next ->
            withContext(Dispatchers.IO) { ScreensaverImages.download(context, next) }
        }
    }
    // The card takes the strip's paper colour, so the strip sits on it without a visible edge.
    val paper by produceState<Color?>(null, file) {
        val shown = file ?: return@produceState
        value = withContext(Dispatchers.IO) { ComicStrips.paperColour(shown) }?.let(::Color)
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
                .stageTarget(stageFocus, onDown, strip?.credit ?: "Comics", onLongPress = onHold) { step(1) }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(cardColour),
        ) {
            val shown = file
            when {
                shown != null -> AsyncImage(
                    model = shown,
                    contentDescription = strip?.credit,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
                strips.isEmpty() && !ScreensaverImages.online(context) ->
                    Text("Comics need the internet.", color = ColorMuted, fontSize = 15.sp)
                else -> Text("Loading a strip…", color = ColorMuted, fontSize = 15.sp)
            }
        }
    }
}

/**
 * Main Stage → Spurious correlations: two unrelated things that happen to move together, from
 * Tyler Vigen's collection ([SpuriousCorrelations]), drawn as two lines. A new one comes every
 * [StagePrefs.spuriousTurn]; ◀ ▶ step back through the last few, and ▶ (or OK) on the newest
 * fetches another.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SpuriousStage(stageFocus: FocusRequester, onDown: () -> Unit, onHold: () -> Unit, metrics: StageMetrics) {
    val context = LocalContext.current
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    val turn = remember(rememberPrefsChanges()) { StagePrefs.spuriousTurn(context) }
    var history by remember { mutableStateOf<List<Correlation>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    // Bumped to fetch one now (▶ on the newest, or OK).
    var wantNew by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, turn, wantNew) {
        if (!visible) return@LaunchedEffect
        val kept = history ?: withContext(Dispatchers.IO) { SpuriousCorrelations.saved(context) }.also {
            history = it
            index = it.lastIndex.coerceAtLeast(0)
        }
        // Waits out what is left of the turn since the last fetch, so going in and out of an app
        // does not fetch a new one each time.
        val age = System.currentTimeMillis() - SpuriousCorrelations.savedAt(context)
        var wait = if (wantNew > 0 || kept.isEmpty()) 0L else (turn.millis - age).coerceAtLeast(0)
        while (true) {
            delay(wait)
            val fresh = withContext(Dispatchers.IO) { SpuriousCorrelations.fetchRandom() }
            if (fresh == null) {
                wait = CURIOSITY_RETRY_MS
                continue
            }
            val next = (history.orEmpty().filter { it.first != fresh.first || it.second != fresh.second } + fresh)
                .takeLast(SpuriousCorrelations.KEPT)
            history = next
            index = next.lastIndex
            withContext(Dispatchers.IO) { SpuriousCorrelations.save(context, next) }
            wait = turn.millis
        }
    }
    val list = history.orEmpty()
    val shown = list.getOrNull(index)
    fun step(by: Int) {
        if (by > 0 && index >= list.lastIndex) wantNew++ else index = (index + by).coerceIn(0, list.lastIndex.coerceAtLeast(0))
    }
    // The credit lives in the heading's note, which is always there, so the stage never changes height.
    val note = if (list.size > 1) "$SPURIOUS_CREDIT  ·  ${index + 1} of ${list.size}  ◀ ▶" else SPURIOUS_CREDIT
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Spurious correlations", note)
        Box(
            modifier = Modifier
                .stepKeys(::step)
                .stageTarget(stageFocus, onDown, shown?.let { "${it.first} correlates with ${it.second}" } ?: "Spurious correlations", onLongPress = onHold) {
                    index = list.lastIndex.coerceAtLeast(0)
                    wantNew++
                }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile),
        ) {
            if (shown == null) {
                Text(
                    text = if (history == null || ScreensaverImages.online(context)) "Finding a correlation…" else "Spurious correlations need the internet.",
                    color = ColorMuted,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // Bigger names on a taller stage: 18 sp on Small, 20 on Medium, 24 on Large.
                val largest = when {
                    metrics.cardHeight >= 160.dp -> 24
                    metrics.cardHeight >= 130.dp -> 20
                    else -> 18
                }
                CorrelationCard(shown, compact = metrics.cardHeight < 130.dp, largest = largest)
            }
        }
    }
}

// Fixed blue and red (the weather map's H and L), so the two lines differ in every colour scheme,
// even one whose accent is itself red.
private val FirstLine = Color(0xFF4F8EF7)
private val SecondLine = Color(0xFFE5534B)

/**
 * The two names on the left, the chart on the right. The names share one size, the largest from
 * [largest] down to 11 sp at which each fits whole in its half of the column (wrapping as it needs
 * to), so a long name is never cut short, never pushes the other off the card, and the pair
 * always match.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CorrelationCard(c: Correlation, compact: Boolean, largest: Int) {
    val gap = if (compact) 2.dp else 4.dp
    val connector = if (compact) 11.sp else 13.sp
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current.merge(TextStyle(fontWeight = FontWeight.Medium, lineHeight = 1.15.em))
    val density = LocalDensity.current
    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = if (compact) 8.dp else 12.dp)) {
        BoxWithConstraints(modifier = Modifier.weight(0.47f).fillMaxHeight()) {
            val size = remember(c, constraints, base, largest) {
                val connectorPx = measurer.measure("correlates with", base.copy(fontSize = connector)).size.height +
                    with(density) { (gap * 2).roundToPx() }
                val half = (constraints.maxHeight - connectorPx) / 2
                fun fits(name: String, sp: Int) = measurer.measure(
                    name,
                    base.copy(fontSize = sp.sp),
                    constraints = Constraints(maxWidth = constraints.maxWidth),
                ).size.height <= half
                (largest downTo 11).firstOrNull { sp -> fits(c.first, sp) && fits(c.second, sp) } ?: 11
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                    Text(c.first, style = base, color = FirstLine, fontSize = size.sp, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = "correlates with",
                    color = ColorMuted,
                    fontSize = connector,
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = gap),
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    Text(c.second, style = base, color = SecondLine, fontSize = size.sp, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        CorrelationChart(c, Modifier.weight(0.53f).fillMaxHeight().padding(start = 20.dp))
    }
}

/**
 * Both series over the same years, each scaled to its own range, as the site draws them: a dot on
 * each year (while there are few enough to tell apart) and the line through them, inset so no
 * stroke or dot is cut at the edges. Under it, the first and last year with r between them.
 */
@Composable
private fun CorrelationChart(c: Correlation, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val axis = ColorHint
    val section = ColorSection
    val font = LocalTextStyle.current
    Canvas(modifier) {
        val labelStyle = font.merge(TextStyle(color = axis, fontSize = 11.sp))
        val startLabel = measurer.measure("${c.years.first()}", labelStyle)
        val endLabel = measurer.measure("${c.years.last()}", labelStyle)
        val rLabel = measurer.measure(
            "r = ${"%.3f".format(c.r)}",
            font.merge(TextStyle(color = section, fontSize = 11.sp, fontWeight = FontWeight.Bold)),
        )
        val dots = c.years.size <= 24
        val dot = 3.dp.toPx()
        val inset = dot + 1.5.dp.toPx()
        val labelTop = size.height - startLabel.size.height
        val axisY = labelTop - 4.dp.toPx()
        val top = inset
        val bottom = axisY - inset
        val left = inset
        val width = size.width - 2 * inset
        fun points(values: List<Double>): List<Offset> {
            val lo = values.min()
            val span = (values.max() - lo).takeIf { it > 0 } ?: 1.0
            return values.mapIndexed { i, v ->
                Offset(left + width * i / (values.size - 1), (bottom - (bottom - top) * ((v - lo) / span)).toFloat())
            }
        }
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(axis.copy(alpha = 0.5f), Offset(0f, axisY), Offset(size.width, axisY), strokeWidth = 1.dp.toPx())
        // The second first, so the first (named on top) is drawn over it where they cross.
        listOf(points(c.secondValues) to SecondLine, points(c.firstValues) to FirstLine).forEach { (pts, colour) ->
            drawPath(Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }, colour, style = stroke)
            if (dots) pts.forEach { drawCircle(colour, radius = dot, center = it) }
        }
        drawText(startLabel, topLeft = Offset(0f, labelTop))
        drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, labelTop))
        drawText(rLabel, topLeft = Offset((size.width - rLabel.size.width) / 2f, labelTop))
    }
}
