package dev.solon.bucklerhome

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioTest {
    @Test
    fun directLinksWinOverTorrentsAndOnesNeedingHeaders() {
        val streams = JSONArray(
            """
            [
              {"infoHash": "abc", "fileIdx": 0},
              {"ytId": "xyz"},
              {"url": "https://cdn.example/needs-headers.mp4", "behaviorHints": {"proxyHeaders": {"request": {"Referer": "x"}}}},
              {"url": "https://cdn.example/film.mkv", "behaviorHints": {"notWebReady": true}}
            ]
            """,
        )
        assertEquals("https://cdn.example/film.mkv", StremioClient.pickDirect(streams))
        assertNull(StremioClient.pickDirect(JSONArray("""[{"infoHash": "abc"}]""")))
    }

    @Test
    fun addonsAreAskedOnlyForWhatTheyServe() {
        val plain = JSONObject("""{"resources": ["catalog", "stream"], "types": ["movie", "series"], "idPrefixes": ["tt"]}""")
        assertTrue(StremioClient.servesStreams(plain, "series", "tt0903747:1:2"))
        assertFalse(StremioClient.servesStreams(plain, "channel", "tt1"))
        assertFalse(StremioClient.servesStreams(plain, "movie", "kitsu:1"))
        val detailed = JSONObject("""{"resources": [{"name": "stream", "types": ["movie"], "idPrefixes": ["kitsu"]}], "types": ["series"]}""")
        assertTrue(StremioClient.servesStreams(detailed, "movie", "kitsu:1"))
        assertFalse(StremioClient.servesStreams(JSONObject("""{"resources": ["meta"], "types": ["movie"]}"""), "movie", "tt1"))
    }

    @Test
    fun episodesShowTheirSeasonAndNumber() {
        assertEquals("Breaking Bad · S1E2", StremioClient.title("Breaking Bad", "tt0903747:1:2"))
        assertEquals("Dune", StremioClient.title("Dune", "tt1160419"))
    }
}
