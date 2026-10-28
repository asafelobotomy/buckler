package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeTest {
    @Test
    fun enhancedLrcKeepsWordTimes() {
        val line = LyricsClient.parseLrc("[00:10.00] <00:10.00>Hello <00:10.50>bright <00:11.20>world").single()
        assertEquals("Hello bright world", line.text)
        assertEquals(listOf(10_000L, 10_500L, 11_200L), line.wordTimes)
        val plain = LyricsClient.parseLrc("[00:10.00] Hello   world").single()
        assertEquals("Hello world", plain.text)
        assertTrue(plain.wordTimes.isEmpty())
    }

    @Test
    fun syllablesAreRoughlyRight() {
        assertEquals(1, syllables("the"))
        assertEquals(2, syllables("baby"))
        assertEquals(3, syllables("Origami,") - 1)
        assertEquals(1, syllables("(Ah)"))
        assertEquals(1, syllables("strengths"))
        assertEquals(3, syllables("ADD"))
        assertEquals(4, syllables("bbno$,"))
    }

    @Test
    fun paceFollowsHowTightlyTheSongIsSung() {
        // Six-syllable lines every 1.2 s: about 190 ms a syllable, quicker than the 230 ms default.
        val fast = (0 until 8).map { LyricLine(it * 1_200L, "one two three four five six") }
        assertEquals(190L, songPaceMs(fast))
        // Too few packed lines to judge: the default pace.
        assertEquals(230L, songPaceMs(listOf(LyricLine(0, "one two three"), LyricLine(20_000, "four"))))
    }

    @Test
    fun estimatedWordsFollowSyllablesAndStopBeforeTheNextLine() {
        val (starts, end) = estimateWordStarts(listOf("Rich,", "sexy,", "ugly,", "baby"), 10_000, 12_000)
        assertEquals(10_000L, starts.first())
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
        assertTrue(end <= 12_000)
        // A short line before a long gap sings at a natural pace, not stretched over the gap.
        val (_, slowEnd) = estimateWordStarts(listOf("Ah"), 10_000, 20_000)
        assertTrue(slowEnd < 11_000)
    }

    @Test
    fun ballHopsBetweenWordsThenFinishes() {
        val lines = listOf(LyricLine(1_000, "one two"), LyricLine(9_000, "next"))
        val line = karaokeLine(lines, 0)!!
        val mid = (line.wordStarts[0] + line.wordStarts[1]) / 2
        val hop = ballAt(line, mid)
        assertEquals(0.5f, hop.word, 0.05f)
        assertEquals(1f, hop.lift, 0.05f)
        assertEquals(0f, ballAt(line, line.wordStarts[1]).lift, 0.001f)
        // Long gap after the line: the dot settles on the last word and fades out.
        assertEquals(1f, ballAt(line, line.wordStarts[1]).alpha, 0.001f)
        assertEquals(0f, ballAt(line, line.singEndMs + 1_000).alpha, 0.001f)
        assertEquals(0f, ballAt(line, mid, calm = true).lift, 0.001f)
        assertNull(karaokeLine(listOf(LyricLine(0, "  ")), 0))
    }

    @Test
    fun wipeFillsEachWordAcrossItsSlot() {
        val line = KaraokeLine("one two", listOf(0..2, 4..6), listOf(1_000L, 2_000L), 3_000L, 9_000L)
        assertEquals(-1 to 0f, wipeAt(line, 500))
        assertEquals(0, wipeAt(line, 1_425).first)
        assertEquals(0.5f, wipeAt(line, 1_425).second, 0.01f)
        assertEquals(1f, wipeAt(line, 1_950).second, 0.001f)
        assertEquals(1 to 1f, wipeAt(line, 5_000))
        assertEquals(0 to 1f, wipeAt(line, 1_100, calm = true))
    }

    @Test
    fun routesAndSongKeys() {
        assertEquals(AudioRoute.Kind.BLUETOOTH, AudioRoute.kindOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioRoute.Kind.DIRECT, AudioRoute.kindOf(android.media.AudioDeviceInfo.TYPE_HDMI))
        assertEquals(false, AudioRoute.isEndpoint(android.media.AudioDeviceInfo.TYPE_BUS))
        assertEquals(true, AudioRoute.isEndpoint(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(LyricsPrefs.songKey("Ghosts ", "Michael Jackson"), LyricsPrefs.songKey("ghosts", " michael jackson"))
        assertEquals("+0.3 s", LyricsPrefs.signed(300))
        assertEquals("−0.8 s", LyricsPrefs.signed(-817))
    }
}
