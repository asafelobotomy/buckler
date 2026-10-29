package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiviewTest {
    @Test
    fun playlistGivesNameGroupAndAddress() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="one" tvg-logo="https://x/logo.png" group-title="News",Channel One HD
            http://example.com/live/one.m3u8
            #EXTINF:-1,Plain Two
            #EXTGRP:Sport
            http://example.com/live/two.ts

            #EXTINF:-1 tvg-name="Named Three" group-title="Kids",
            rtsp://example.com/three
        """.trimIndent()
        val channels = IptvPlaylist.parse(m3u.lineSequence())
        assertEquals(
            listOf(
                IptvChannel("Channel One HD", "News", "http://example.com/live/one.m3u8"),
                IptvChannel("Plain Two", "Sport", "http://example.com/live/two.ts"),
                IptvChannel("Named Three", "Kids", "rtsp://example.com/three"),
            ),
            channels,
        )
    }

    @Test
    fun serverAddressGetsASchemeAndLosesItsSlash() {
        assertEquals("http://192.168.1.20:8096", JellyfinClient.normalise(" 192.168.1.20:8096/ "))
        assertEquals("https://jf.example.org", JellyfinClient.normalise("https://jf.example.org/"))
    }
}
