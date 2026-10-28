package dev.solon.bucklerhome

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Finds how far the audio really is from the player's reported position, by lining up when the
 * lyrics say singing happens with when a voice detector hears it.
 *
 * Each [add] is one 32 ms frame of captured audio: the player's reported song position at the
 * moment it was mixed, and the voice probability. [estimate] tries every offset in ±[SEARCH_MS]
 * and scores how well the detected voice matches the lyrics there: voice while a line is sung,
 * and voice starting where lines start. A positive offset means the audio is ahead of the
 * reported position (so lyrics should show sooner).
 */
class SyncEstimator(private val frameMs: Long = FRAME_MS) {
    /** [prominence]: how many standard deviations the best offset stands above all the others. */
    data class Estimate(val offsetMs: Long, val score: Float, val margin: Float, val prominence: Float = 0f)

    private val positions = LongArray(CAPACITY)
    private val voice = FloatArray(CAPACITY)
    private var head = 0
    private var size = 0

    fun reset() {
        head = 0
        size = 0
    }

    fun add(reportedMs: Long, probability: Float) {
        val i = (head + size) % CAPACITY
        positions[i] = reportedMs
        voice[i] = probability
        if (size < CAPACITY) size++ else head = (head + 1) % CAPACITY
    }

    val frames: Int get() = size

    /** Last search, accepted or not, for the service's diagnostics log. */
    var lastBest: Estimate? = null
        private set

    /** Mean voice probability over the window, for diagnostics. */
    fun meanVoice(): Float = if (size == 0) 0f else (0 until size).map { voice[(head + it) % CAPACITY] }.average().toFloat()

    /** Best offset, or null while there is too little singing to judge or no clear winner. */
    fun estimate(lines: List<LyricLine>): Estimate? {
        lastBest = null
        if (size < MIN_FRAMES || lines.size < 2) return null
        val spans = lyricSpans(lines)
        val starts = spans.map { it.first }.toLongArray()
        val pos = LongArray(size) { positions[(head + it) % CAPACITY] }
        // Log-odds: sung vocals in a full mix only lift the detector from ~0.02 to ~0.1-0.3, which
        // a plain probability squashes; log-odds keeps those rises as large as a clear spoken word.
        val raw = FloatArray(size) { logit(voice[(head + it) % CAPACITY]) }
        // A light 5-frame (160 ms) average: the detector flickers on sung syllables.
        val level = FloatArray(size) { i ->
            var sum = 0f
            var n = 0
            for (j in i - SMOOTH..i + SMOOTH) if (j in 0 until size) {
                sum += raw[j]
                n++
            }
            sum / n
        }
        val rise = FloatArray(size) { i ->
            val back = (i - RISE_FRAMES).coerceAtLeast(0)
            (level[i] - level[back]).coerceAtLeast(0f)
        }
        val firstLine = starts.first()
        val lastLine = spans.last().second
        val onsetsInWindow = starts.count { it in pos.min() - SEARCH_MS..pos.max() + SEARCH_MS }
        if (onsetsInWindow < MIN_ONSETS) return null

        val steps = (2 * SEARCH_MS / frameMs).toInt()
        val scores = FloatArray(steps + 1)
        val active = FloatArray(size)
        val onset = FloatArray(size)
        for (k in 0..steps) {
            val offset = -SEARCH_MS + k * frameMs
            var inLyrics = 0
            for (i in 0 until size) {
                val song = pos[i] + offset
                if (song < firstLine - LEAD_MS || song > lastLine) {
                    active[i] = Float.NaN
                    continue
                }
                inLyrics++
                active[i] = if (inSpan(spans, starts, song)) 1f else 0f
                onset[i] = nearestOnset(starts, song)
            }
            scores[k] = if (inLyrics < MIN_FRAMES) -1f else {
                correlation(level, active) + correlation(rise, onset, mask = active)
            }
        }
        val best = scores.indices.maxBy { scores[it] }
        val bestScore = scores[best]
        val valid = scores.filter { it > -1f }
        val mean = valid.average()
        val spread = sqrt(valid.sumOf { (it - mean) * (it - mean) } / valid.size.coerceAtLeast(1))
        val prominence = if (spread > 0) ((bestScore - mean) / spread).toFloat() else 0f
        val guard = (PEAK_GUARD_MS / frameMs).toInt()
        val rival = scores.indices.filter { kotlin.math.abs(it - best) > guard }.maxOfOrNull { scores[it] } ?: -1f
        val estimate = Estimate(-SEARCH_MS + best * frameMs, bestScore, bestScore - rival, prominence)
        lastBest = estimate
        val clear = bestScore >= MIN_SCORE && estimate.margin >= MIN_MARGIN
        return estimate.takeIf { clear }
    }

    private fun logit(p: Float): Float {
        val q = p.coerceIn(1e-3f, 1f - 1e-3f)
        return kotlin.math.ln(q / (1f - q))
    }

    private fun nearestOnset(starts: LongArray, song: Long): Float {
        val i = starts.binarySearch(song).let { if (it >= 0) it else -it - 1 }
        var d = Long.MAX_VALUE
        if (i < starts.size) d = minOf(d, starts[i] - song)
        if (i > 0) d = minOf(d, song - starts[i - 1])
        val x = d / ONSET_WIDTH_MS.toFloat()
        return exp(-0.5f * x * x)
    }

    private fun inSpan(spans: List<Pair<Long, Long>>, starts: LongArray, song: Long): Boolean {
        val i = starts.binarySearch(song).let { if (it >= 0) it else -it - 2 }
        return i >= 0 && song <= spans[i].second
    }

    companion object {
        const val FRAME_MS = 32L
        private const val WINDOW_MS = 45_000L
        private val CAPACITY = (WINDOW_MS / FRAME_MS).toInt()
        // Spotify's buffer and lyric records' own errors stay well inside ±2 s; searching wider
        // let repeated choruses (a line every ~2.5 s) pass for the answer.
        private const val SEARCH_MS = 2_000L
        private const val SMOOTH = 2
        private const val MIN_FRAMES = 400 // ~13 s
        private const val MIN_ONSETS = 5
        private const val RISE_FRAMES = 3
        private const val ONSET_WIDTH_MS = 120L
        private const val LEAD_MS = 2_000L
        // Sung vocals score lower than speech. What separates a real match from chance is the
        // margin over the best rival offset (simulated noise stays under 0.05), and the service
        // only applies an offset that three estimates agree on.
        private const val MIN_SCORE = 0.12f
        private const val MIN_MARGIN = 0.05f
        private const val PEAK_GUARD_MS = 400L

        /** When each line is sung: its own end, else most of the way to the next line. */
        internal fun lyricSpans(lines: List<LyricLine>): List<Pair<Long, Long>> =
            lines.filter { it.text.isNotBlank() }.let { sung ->
                sung.mapIndexed { i, line ->
                    val next = sung.getOrNull(i + 1)?.atMs
                    val end = line.endMs ?: line.tokens.lastOrNull()?.endMs
                        ?: next?.let { line.atMs + (it - line.atMs) * 9 / 10 }
                        ?: (line.atMs + 3_000)
                    line.atMs to maxOf(end, line.atMs + 200)
                }
            }

        /** Pearson correlation over frames where [mask] (or [b] itself) is not NaN. */
        internal fun correlation(a: FloatArray, b: FloatArray, mask: FloatArray = b): Float {
            var n = 0
            var sa = 0.0
            var sb = 0.0
            for (i in a.indices) if (!mask[i].isNaN()) {
                n++
                sa += a[i]
                sb += b[i]
            }
            if (n < 2) return 0f
            val ma = sa / n
            val mb = sb / n
            var cov = 0.0
            var va = 0.0
            var vb = 0.0
            for (i in a.indices) if (!mask[i].isNaN()) {
                val da = a[i] - ma
                val db = b[i] - mb
                cov += da * db
                va += da * da
                vb += db * db
            }
            if (va <= 0 || vb <= 0) return 0f
            return (cov / sqrt(va * vb)).toFloat()
        }
    }
}
