package dev.solon.bucklerhome

import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAndPhotosTest {
    @Test
    fun lrcLinesAreTimedAndSorted() {
        val lines = LyricsClient.parseLrc(
            """
            [ar: someone]
            [00:12.50] second
            [00:03.2] first
            [01:00.123][01:30.00] chorus
            [00:20.00]
            """.trimIndent(),
        )
        assertEquals(listOf(3_200L, 12_500L, 20_000L, 60_123L, 90_000L), lines.map { it.atMs })
        assertEquals(listOf("first", "second", "", "chorus", "chorus"), lines.map { it.text })
        assertEquals(-1, LyricsClient.lineAt(lines, 1_000))
        assertEquals(1, LyricsClient.lineAt(lines, 12_500))
        assertEquals(4, LyricsClient.lineAt(lines, 200_000))
    }

    @Test
    fun searchPicksSyncedClosestLength() {
        val records = JSONArray(
            listOf(
                JSONObject().put("duration", 139.0).put("plainLyrics", "plain only"),
                JSONObject().put("duration", 301.0).put("syncedLyrics", "[00:01.00] extended"),
                JSONObject().put("duration", 141.0).put("syncedLyrics", "[00:01.00] radio edit"),
            ),
        )
        val picked = LyricsClient.pick(records, 139)
        assertEquals("[00:01.00] radio edit", picked?.optString("syncedLyrics"))
        assertNull(LyricsClient.pick(JSONArray(listOf(JSONObject().put("duration", 400.0))), 139))
    }

    @Test
    fun recordsBecomeSyncedPlainOrInstrumental() {
        assertEquals(Lyrics.Instrumental, LyricsClient.fromRecord(JSONObject().put("instrumental", true)))
        val plain = LyricsClient.fromRecord(JSONObject().put("plainLyrics", "one\n\ntwo\n"))
        assertEquals(Lyrics.Plain(listOf("one", "two")), plain)
        assertTrue(LyricsClient.fromRecord(JSONObject().put("syncedLyrics", "[00:01.00] hi")) is Lyrics.Synced)
        assertNull(LyricsClient.fromRecord(JSONObject()))
    }

    @Test
    fun plainLyricsMoveWithTheTrack() {
        assertEquals(0, plainLineAt(10, 0, 100_000))
        assertEquals(5, plainLineAt(10, 50_000, 100_000))
        assertEquals(9, plainLineAt(10, 200_000, 100_000))
        assertEquals(0, plainLineAt(10, 5_000, 0))
    }

    @Test
    fun lyricWindowGrowsWithTheCard() {
        assertEquals(0 to 1, lyricWindow(100.dp))
        assertEquals(1 to 1, lyricWindow(140.dp))
        assertEquals(1 to 2, lyricWindow(190.dp))
    }

    @Test
    fun photoCategoriesTakeTurns() {
        val mixed = ScreensaverImages.takeTurns(listOf(listOf("n1", "n2", "n3"), listOf("c1"), emptyList()))
        assertEquals(listOf("n1", "c1", "n2", "n3"), mixed)
    }
}
