package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Test

class PackStyleTest {
    @Test
    fun packIconNamesComeFromTheWholeAppName() {
        assertEquals(listOf("netflix"), IconPacks.nameCandidates("Netflix"))
        assertEquals(listOf("live_channels", "livechannels"), IconPacks.nameCandidates("Live Channels"))
        assertEquals(listOf("kick_live_streaming", "kicklivestreaming"), IconPacks.nameCandidates("Kick: Live Streaming"))
        // Too short to be a name rather than a word in many.
        assertEquals(emptyList<String>(), IconPacks.nameCandidates("TV"))
    }

    @Test
    fun initialsTakeOneLetterPerWordOrTwoFromOne() {
        assertEquals("LC", PackStyleIcons.lettersOf("Live Channels"))
        assertEquals("LS", PackStyleIcons.lettersOf("LiveSubs"))
        assertEquals("Se", PackStyleIcons.lettersOf("Settings"))
        assertEquals("?", PackStyleIcons.lettersOf("  "))
    }
}
