package dev.solon.bucklerhome

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverScenesTest {
    @Test
    fun mazeIsPerfectAndWalled() {
        val maze = MazeGrid.generate(9, Random(42))
        assertEquals(19, maze.size)
        var open = 0
        for (y in 0 until maze.size) {
            for (x in 0 until maze.size) {
                if (x == 0 || y == 0 || x == maze.size - 1 || y == maze.size - 1) assertTrue(maze.isWall(x, y))
                if (!maze.isWall(x, y)) open++
            }
        }
        // A spanning tree over 81 cells: 81 cells plus 80 knocked-out walls, so no loops.
        assertEquals(81 + 80, open)
        assertFalse(maze.isWall(maze.goalX, maze.goalY))
        assertTrue(maze.isWall(-1, 5))
    }

    @Test
    fun wallFollowerAlwaysReachesTheGoal() {
        repeat(20) { seed ->
            val maze = MazeGrid.generate(9, Random(seed))
            val walker = MazeWalker(maze)
            var guard = 0
            while (!walker.finished && guard++ < 100_000) walker.update(0.05f)
            assertTrue("seed $seed", walker.finished)
            assertEquals(maze.goalX + 0.5, walker.x, 1e-9)
            assertEquals(maze.goalY + 0.5, walker.y, 1e-9)
        }
    }

    @Test
    fun mediaKindFollowsAppThenMetadata() {
        assertEquals(MediaKind.YOUTUBE, MediaKinds.classify("com.google.android.youtube.tv", hasArtist = true, hasAlbum = false))
        assertEquals(MediaKind.TWITCH, MediaKinds.classify("tv.twitch.android.app", hasArtist = false, hasAlbum = false))
        assertEquals(MediaKind.LIVE_TV, MediaKinds.classify("ar.tvplayer.tv", hasArtist = false, hasAlbum = false))
        assertEquals(MediaKind.MUSIC, MediaKinds.classify("com.spotify.tv.android", hasArtist = false, hasAlbum = false))
        assertEquals(MediaKind.MUSIC, MediaKinds.classify("org.jellyfin.androidtv", hasArtist = true, hasAlbum = true))
        assertEquals(MediaKind.VIDEO, MediaKinds.classify("org.jellyfin.androidtv", hasArtist = false, hasAlbum = false))
        assertEquals(MediaKind.VIDEO, MediaKinds.classify("com.netflix.ninja", hasArtist = false, hasAlbum = false))
    }

    @Test
    fun headerElementsHideIndividuallyOrAllAtOnce() {
        val place = Place("Berlin", 52.5, 13.4)
        val cfg = DisplayConfig(place = place, hidden = setOf(HeaderElement.WEATHER, HeaderElement.VPN))
        assertTrue(cfg.shows(HeaderElement.CLOCK))
        assertFalse(cfg.shows(HeaderElement.VPN))
        assertEquals(null, cfg.weatherPlace)
        val off = DisplayConfig(place = place, headerVisible = false)
        HeaderElement.entries.forEach { assertFalse(off.shows(it)) }
        assertFalse(off.showsNews)
        assertFalse(off.showsNowPlaying)
        assertEquals(null, off.weatherPlace)
        assertEquals(place, DisplayConfig(place = place).weatherPlace)
    }

    @Test
    fun everySchemeHasAMatchingLauncherAlias() {
        assertEquals("Buckler", LauncherIcon.aliasSuffix(AppTheme.BUCKLER))
        assertEquals("SnesUk", LauncherIcon.aliasSuffix(AppTheme.SNES_UK))
        assertEquals("SnesUs", LauncherIcon.aliasSuffix(AppTheme.SNES_US))
        assertEquals(AppTheme.BUCKLER, AppearanceConfig().theme)
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.markRes }.toSet().size)
    }
}
