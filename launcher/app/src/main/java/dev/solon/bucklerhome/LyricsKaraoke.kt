package dev.solon.bucklerhome

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlin.math.min

/**
 * One sung line: where each word sits in [text] and when it starts. [singEndMs] is when the last
 * word is done; after it the dot rests, and fades if the next line is a while off.
 */
data class KaraokeLine(
    val text: String,
    val wordRanges: List<IntRange>,
    val wordStarts: List<Long>,
    val singEndMs: Long,
    val nextMs: Long?,
    /** Each word's own end, when the lyrics carry it (NetEase YRC); otherwise the next word's start. */
    val wordEnds: List<Long>? = null,
)

/** Where the dot is: a fractional word index, how high it is in its hop (0..1), and its opacity. */
data class BallPosition(val word: Float, val lift: Float, val alpha: Float)

/**
 * Word timing for line [index]. Enhanced LRC word stamps are used as they are; otherwise the line
 * is spread over its words by syllable count, at a natural pace but never past the next line.
 */
internal fun karaokeLine(lines: List<LyricLine>, index: Int, paceMs: Long = SYLLABLE_MS): KaraokeLine? {
    val line = lines.getOrNull(index) ?: return null
    val next = lines.getOrNull(index + 1)?.atMs?.takeIf { it > line.atMs }
    if (line.tokens.isNotEmpty()) return tokenLine(line, next, paceMs)
    val ranges = Regex("""\S+""").findAll(line.text).map { it.range }.toList()
    if (ranges.isEmpty()) return null
    val words = ranges.map { line.text.substring(it) }
    if (line.wordTimes.size == ranges.size) {
        val lastLength = syllables(words.last()) * paceMs
        val end = line.wordTimes.last() + lastLength
        return KaraokeLine(line.text, ranges, line.wordTimes, next?.let { min(it, end) } ?: end, next)
    }
    val (starts, end) = estimateWordStarts(words, line.atMs, next, paceMs)
    return KaraokeLine(line.text, ranges, starts, end, next)
}

/**
 * A YRC line. Real word timing is used as it is, each word filling over its own length. A line cut
 * into equal parts only has a real start and end, so the words are estimated inside that span.
 */
private fun tokenLine(line: LyricLine, next: Long?, paceMs: Long): KaraokeLine {
    val tokens = line.tokens
    val ranges = tokens.map { it.range }
    val end = (line.endMs ?: tokens.last().endMs).let { e -> next?.let { min(it, e) } ?: e }
    if (!evenlySplit(tokens)) {
        return KaraokeLine(line.text, ranges, tokens.map { it.atMs }, end, next, tokens.map { it.endMs })
    }
    val bound = line.atMs + ((end - line.atMs) / SING_SHARE).toLong()
    val (starts, singEnd) = estimateWordStarts(ranges.map { line.text.substring(it) }, line.atMs, bound, paceMs)
    return KaraokeLine(line.text, ranges, starts, min(singEnd, end), next)
}

/**
 * How fast this song is sung, in ms per syllable: the median over lines that run straight into
 * the next one (a rap verse gets ~150 ms, a ballad ~400 ms). Falls back to an average pace.
 */
internal fun songPaceMs(lines: List<LyricLine>): Long {
    val samples = lines.zipWithNext().mapNotNull { (line, next) ->
        val window = next.atMs - line.atMs
        val weight = line.text.split(' ').filter { it.isNotBlank() }.sumOf { syllables(it) }
        if (weight < 3 || window !in PACKED_WINDOW_MS) null else (window * SING_SHARE / weight).toLong()
    }.sorted()
    if (samples.size < 3) return SYLLABLE_MS
    return samples[samples.size / 2].coerceIn(MIN_PACE_MS, MAX_PACE_MS)
}

/** Starts by syllable share of the sung span; returns the starts and when singing ends. */
internal fun estimateWordStarts(
    words: List<String>,
    startMs: Long,
    nextMs: Long?,
    paceMs: Long = SYLLABLE_MS,
): Pair<List<Long>, Long> {
    val weights = words.map { syllables(it) }
    val total = weights.sum().coerceAtLeast(1)
    val natural = total * paceMs
    val window = (nextMs ?: (startMs + natural + LAST_LINE_SLACK_MS)) - startMs
    val span = min(natural, (window * SING_SHARE).toLong()).coerceAtLeast(min(MIN_SPAN_MS, window))
    var before = 0
    val starts = weights.map { w ->
        val at = startMs + span * before / total
        before += w
        at
    }
    return starts to startMs + span
}

/**
 * Vowel groups, a fair stand-in for syllables in sung English; at least one per word. Tokens that
 * are spelled out when sung (initials like "ADD" or "DJ", names like "bbno$") count one per letter.
 */
internal fun syllables(word: String): Int {
    val raw = word.filter { it.isLetterOrDigit() || it == '$' }
    val letters = word.lowercase().filter { it.isLetter() }
    if (letters.isEmpty()) return 1
    val spelled = raw.any { it == '$' || it.isDigit() } ||
        (raw.length in 2..5 && raw.all { it.isUpperCase() })
    if (spelled) return letters.length.coerceIn(1, 6)
    var groups = 0
    var inVowel = false
    letters.forEach { c ->
        val vowel = c in "aeiouy"
        if (vowel && !inVowel) groups++
        inVowel = vowel
    }
    if (letters.length > 2 && letters.endsWith("e") && !letters.endsWith("le") && groups > 1) groups--
    return groups.coerceAtLeast(1)
}

/**
 * The dot lands on each word as it starts and arcs to the next. After the last word it settles,
 * then fades out when a gap follows ([calm] drops the arc for Reduce motion).
 */
internal fun ballAt(line: KaraokeLine, positionMs: Long, calm: Boolean = false): BallPosition {
    val starts = line.wordStarts
    val i = starts.indexOfLast { it <= positionMs }
    if (i < 0) return BallPosition(0f, 0f, 1f)
    if (i < starts.lastIndex) {
        val hop = (starts[i + 1] - starts[i]).coerceAtLeast(1)
        val p = ((positionMs - starts[i]).toFloat() / hop).coerceIn(0f, 1f)
        if (calm) return BallPosition(i.toFloat(), 0f, 1f)
        return BallPosition(i + p, 4f * p * (1f - p), 1f)
    }
    val gapFollows = line.nextMs == null || line.nextMs - line.singEndMs > FINISH_GAP_MS
    val fade = if (gapFollows && positionMs > line.singEndMs) {
        1f - ((positionMs - line.singEndMs).toFloat() / FADE_OUT_MS).coerceIn(0f, 1f)
    } else {
        1f
    }
    return BallPosition(starts.lastIndex.toFloat(), 0f, fade)
}

/**
 * Where the karaoke fill is: the word being sung and how far through it (0..1). A word fills over
 * most of its slot, so the short breath before the next word reads as finished. -1 before singing.
 */
internal fun wipeAt(line: KaraokeLine, positionMs: Long, calm: Boolean = false): Pair<Int, Float> {
    val starts = line.wordStarts
    val i = starts.indexOfLast { it <= positionMs }
    if (i < 0) return -1 to 0f
    if (calm) return i to 1f
    val own = line.wordEnds?.getOrNull(i)
    val end = own ?: if (i < starts.lastIndex) starts[i + 1] else line.singEndMs
    val share = if (own != null) 1.0 else WORD_FILL_SHARE
    val slot = ((end - starts[i]) * share).toLong().coerceAtLeast(1)
    return i to ((positionMs - starts[i]).toFloat() / slot).coerceIn(0f, 1f)
}

/**
 * The current line, centred, filled letter by letter in the accent colour as it is sung (an exact
 * copy of the line, clipped to the sung width), with the dot bouncing above. Fill and dot are drawn
 * from the position each frame, so the line itself never re-lays out while it plays.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KaraokeText(line: KaraokeLine, positionMs: () -> Long, fontSize: TextUnit, calm: Boolean) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val accent = ColorBrand
    val lineModifier = Modifier.fillMaxWidth().padding(top = BOUNCE_ROOM)

    fun edges(laid: TextLayoutResult, word: Int): Pair<Float, Float> {
        val range = line.wordRanges[word.coerceIn(0, line.wordRanges.lastIndex)]
        val limit = laid.layoutInput.text.length - 1
        return laid.getBoundingBox(range.first.coerceAtMost(limit)).left to
            laid.getBoundingBox(range.last.coerceAtMost(limit)).right
    }

    Box {
        Text(
            text = line.text,
            color = ColorMuted,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = lineModifier,
        )
        Text(
            text = line.text,
            color = accent,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = lineModifier.drawWithContent {
                val laid = layout ?: return@drawWithContent
                val position = positionMs()
                val (word, progress) = wipeAt(line, position, calm)
                if (word >= 0) {
                    val (left, right) = edges(laid, word)
                    clipRect(right = left + (right - left) * progress) { this@drawWithContent.drawContent() }
                }
                val ball = ballAt(line, position, calm)
                if (ball.alpha <= 0f) return@drawWithContent
                fun centre(i: Int) = edges(laid, i).let { (l, r) -> (l + r) / 2f }
                val from = ball.word.toInt()
                val x = centre(from) + (centre(from + 1) - centre(from)) * (ball.word - from)
                val radius = BALL_RADIUS.toPx()
                val y = laid.getLineTop(0) - radius - 2.dp.toPx() - ball.lift * BOUNCE_HEIGHT.toPx()
                drawCircle(accent, radius, Offset(x, y), alpha = ball.alpha)
            },
        )
    }
}

/**
 * Lyrics timing, three parts added together (positive shows lines and words sooner):
 * - the route's audio delay, measured and subtracted automatically ([AudioRoute]);
 * - a fine-tune per route for the device itself (Settings), so Bluetooth and HDMI keep their own;
 * - a nudge per song (◀ ▶ on the stage), for lyrics whose timestamps run early or late.
 */
object LyricsPrefs {
    private const val PREFS = "bucklerhome"
    private const val KEY_OFFSET = "lyrics_offset_ms"
    // Renamed when the Bluetooth default was recalibrated, so an old nudge is not added twice.
    private const val KEY_OFFSET_BT = "lyrics_offset_bluetooth_ms"
    const val STEP_MS = 100L
    private const val LIMIT_MS = 3_000L

    private fun key(route: AudioRoute.Kind) = if (route == AudioRoute.Kind.BLUETOOTH) KEY_OFFSET_BT else KEY_OFFSET

    fun offset(context: Context, route: AudioRoute.Kind): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key(route), 0L)

    fun setOffset(context: Context, route: AudioRoute.Kind, ms: Long): Long {
        val clamped = ms.coerceIn(-LIMIT_MS, LIMIT_MS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(key(route), clamped).apply()
        return clamped
    }

    private const val SONG_PREFS = "bucklerhome_lyrics_songs"

    /** One key per song, whatever album or edit it was played from. */
    fun songKey(title: String, artist: String): String = "${title.trim()}\n${artist.trim()}".lowercase()

    fun songOffset(context: Context, key: String): Long =
        context.getSharedPreferences(SONG_PREFS, Context.MODE_PRIVATE).getLong(key, 0L)

    /** Stores the nudge for [key]; zero removes it, so the list holds only songs that needed one. */
    fun setSongOffset(context: Context, key: String, ms: Long): Long {
        val clamped = ms.coerceIn(-LIMIT_MS, LIMIT_MS)
        val edit = context.getSharedPreferences(SONG_PREFS, Context.MODE_PRIVATE).edit()
        if (clamped == 0L) edit.remove(key) else edit.putLong(key, clamped)
        edit.apply()
        return clamped
    }

    fun songOffsetCount(context: Context): Int =
        context.getSharedPreferences(SONG_PREFS, Context.MODE_PRIVATE).all.size

    fun clearSongOffsets(context: Context) =
        context.getSharedPreferences(SONG_PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    /** "+0.3 s" style, for the stage's title line. */
    fun signed(ms: Long): String = "${if (ms < 0) "−" else "+"}${"%.1f".format(kotlin.math.abs(ms) / 1000.0)} s"

    /** "in step", "0.3 s earlier", "0.2 s later". */
    fun describe(ms: Long): String = when {
        ms == 0L -> "in step"
        ms > 0 -> "${"%.1f".format(ms / 1000.0)} s earlier"
        else -> "${"%.1f".format(-ms / 1000.0)} s later"
    }
}

private const val SYLLABLE_MS = 230L
private const val MIN_PACE_MS = 110L
private const val MAX_PACE_MS = 450L
private val PACKED_WINDOW_MS = 800L..6_000L
private const val SING_SHARE = 0.95
private const val WORD_FILL_SHARE = 0.85
private const val MIN_SPAN_MS = 300L
private const val LAST_LINE_SLACK_MS = 1_000L
private const val FINISH_GAP_MS = 700L
private const val FADE_OUT_MS = 300L
private val BALL_RADIUS = 5.dp
private val BOUNCE_HEIGHT = 10.dp
private val BOUNCE_ROOM = 18.dp
