package dev.solon.bucklerhome

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncEstimatorTest {
    /**
     * Feeds [seconds] of frames where the voice is heard [audioAheadMs] ahead of the reported
     * position (audio song time = reported + offset), with noisy detector output.
     */
    private fun run(lines: List<LyricLine>, audioAheadMs: Long, seconds: Int, noise: Float, seed: Int = 1): SyncEstimator.Estimate? {
        val est = SyncEstimator()
        val spans = SyncEstimator.lyricSpans(lines)
        val rnd = Random(seed)
        var reported = 0L
        while (reported < seconds * 1000L) {
            val song = reported + audioAheadMs
            val singing = spans.any { song in it.first..it.second }
            val p = ((if (singing) 0.85f else 0.08f) + (rnd.nextFloat() - 0.5f) * 2 * noise).coerceIn(0f, 1f)
            est.add(reported, p)
            reported += SyncEstimator.FRAME_MS
        }
        return est.estimate(lines)
    }

    private val gappy = (0 until 20).map { LyricLine(5_000L + it * 3_300L + (it % 3) * 400L, "line number $it here") }
        .map { it.copy(endMs = it.atMs + 2_100) }

    private val packed = (0 until 30).map { LyricLine(4_000L + it * 1_700L + (it % 4) * 150L, "rap bar $it goes on") }

    @Test
    fun findsTheOffsetInASongWithGaps() {
        for (ahead in listOf(-1_200L, -300L, 0L, 450L, 1_500L)) {
            val e = run(gappy, ahead, 40, noise = 0.25f)
            assertNotNull("ahead $ahead", e)
            assertEquals("ahead $ahead", ahead.toFloat(), e!!.offsetMs.toFloat(), 100f)
        }
    }

    @Test
    fun findsTheOffsetInPackedLinesFromWhereLinesStart() {
        // Line-to-line gaps are short, so it rests on the dips between lines.
        val lines = packed.mapIndexed { i, l -> l.copy(endMs = l.atMs + 1_350 + (i % 3) * 80L) }
        val e = run(lines, -700, 40, noise = 0.2f)
        assertNotNull(e)
        assertEquals(-700f, e!!.offsetMs.toFloat(), 100f)
    }

    @Test
    fun saysNothingWithoutSingingOrEnoughData() {
        val est = SyncEstimator()
        repeat(1_000) { est.add(it * SyncEstimator.FRAME_MS, 0.05f) }
        assertNull(est.estimate(gappy))
        assertNull(run(gappy, 300, 8, noise = 0.2f))
    }

    @Test
    fun pureNoiseDoesNotProduceAConfidentAnswer() {
        val est = SyncEstimator()
        val rnd = Random(7)
        repeat(1_300) { est.add(it * SyncEstimator.FRAME_MS, rnd.nextFloat()) }
        assertNull(est.estimate(gappy))
    }
}
