package dev.solon.bucklerhome

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetEaseLyricsTest {
    private val yrc = """
        {"t":0,"c":[{"tx":"credits: "},{"tx":"Someone"}]}
        [37000,1480](37000,211,0)There's (37211,211,0)a (37422,211,0)ghost (37633,211,0)down
        [39320,1120](39320,150,0)There's (39470,140,0)a (39610,310,0)ghoul (39920,230,0)upon (40150,90,0)the (40240,200,0)bed
        [41000,900](41000,300,0)(Put (41300,300,0)it (41600,300,0)on)
    """.trimIndent()

    @Test
    fun yrcLinesKeepEachWordsPlaceAndTiming() {
        val lines = NetEaseLyrics.parseYrc(yrc)
        assertEquals(3, lines.size)
        val ghoul = lines[1]
        assertEquals("There's a ghoul upon the bed", ghoul.text)
        assertEquals(40_440L, ghoul.endMs)
        assertEquals("ghoul", ghoul.text.substring(ghoul.tokens[2].range))
        assertEquals(39_610L to 39_920L, ghoul.tokens[2].atMs to ghoul.tokens[2].endMs)
        // Brackets in the lyrics are text, not timing marks.
        assertEquals("(Put it on)", lines[2].text)
        assertEquals("(Put", lines[2].text.substring(lines[2].tokens[0].range))
    }

    @Test
    fun evenSplitsAreToldApartFromRealTiming() {
        val lines = NetEaseLyrics.parseYrc(yrc)
        assertTrue(evenlySplit(lines[0].tokens))
        assertFalse(evenlySplit(lines[1].tokens))
        assertTrue(NetEaseLyrics.hasWordTiming(lines.take(2)))
        assertFalse(NetEaseLyrics.hasWordTiming(listOf(lines[0], lines[2])))
    }

    @Test
    fun realWordTimingDrivesTheDotAndFill() {
        val lines = NetEaseLyrics.parseYrc(yrc)
        val ghoul = karaokeLine(lines, 1)!!
        assertEquals(listOf(39_320L, 39_470L, 39_610L, 39_920L, 40_150L, 40_240L), ghoul.wordStarts)
        assertEquals(2 to 0.5f, wipeAt(ghoul, 39_765))
        // Evenly split line: only its start and end are trusted, words are estimated inside.
        val even = karaokeLine(lines, 0)!!
        assertNull(even.wordEnds)
        assertEquals(37_000L, even.wordStarts.first())
        assertTrue(even.singEndMs <= 38_480)
    }

    @Test
    fun songsMatchOnTitleArtistAndLength() {
        fun song(name: String, artist: String, duration: Long) = JSONObject()
            .put("name", name).put("duration", duration)
            .put("artists", JSONArray().put(JSONObject().put("name", artist)))
        assertTrue(NetEaseLyrics.matches(song("Ghosts", "Michael Jackson", 313_260), "Ghosts", "Michael Jackson", 312_000))
        assertTrue(NetEaseLyrics.matches(song("1-800 (feat. Ironmouse)", "bbno$", 150_000), "1-800", "bbno$, Ironmouse", 151_000))
        assertFalse(NetEaseLyrics.matches(song("Ghosts", "Michael Jackson", 264_000), "Ghosts", "Michael Jackson", 313_000))
        assertFalse(NetEaseLyrics.matches(song("Ghost", "Someone Else", 313_000), "Ghosts", "Michael Jackson", 313_000))
    }
}
