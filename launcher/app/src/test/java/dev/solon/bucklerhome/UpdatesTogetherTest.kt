package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Buckler's own updates and bucklerStore's, taking turns rather than talking over each other. */
class UpdatesTogetherTest {
    @Test
    fun homeShowsOneNoticeAReturnTheMostImportant() {
        var shown = ""
        HomeNotices.offer(1, HomeNotices.Priority.UNUSED_APPS, "unused") { shown += "u" }
        HomeNotices.offer(1, HomeNotices.Priority.STORE_UPDATES, "store") { shown += "s" }
        HomeNotices.offer(1, HomeNotices.Priority.REMOTE_BUTTONS, "remote") { shown += "r" }
        val pick = HomeNotices.take(1)
        assertEquals("remote", pick?.text)
        pick?.onShown?.invoke()
        assertEquals("only the one shown is marked; the others come again next time", "r", shown)
        assertNull("one per return", HomeNotices.take(1))
    }

    @Test
    fun anOfferFromAnEarlierReturnIsNotShownLater() {
        HomeNotices.offer(5, HomeNotices.Priority.STORE_UPDATES, "old")
        HomeNotices.offer(6, HomeNotices.Priority.UNUSED_APPS, "new")
        assertEquals("new", HomeNotices.take(6)?.text)
        assertNull(HomeNotices.take(5))
    }

    @Test
    fun oneDownloadAndInstallAtATime() {
        assertNull(InstallGate.tryEnter("the Buckler update"))
        assertEquals("the Buckler update", InstallGate.tryEnter("Lawnicons"))
        assertNull("busy: the icon pack does not start", InstallGate.withGate("Lawnicons") { "installed" })
        InstallGate.leave("Lawnicons") // not the holder: no effect
        assertEquals("the Buckler update", InstallGate.holder)
        InstallGate.leave("the Buckler update")
        assertNull(InstallGate.holder)
        // Released even when the work fails.
        val failed = InstallGate.withGate("Lexend") { error("no network") }
        assertEquals(true, failed?.isFailure)
        assertNull(InstallGate.holder)
    }

    @Test
    fun theUpdateDialogNamesWhatItBringsFromTheStore() {
        val maze = StoreScreensaver("saver_maze", "3D Maze", "", default = false, since = "0.1.0", requires = "0.24.0", revision = 3)
        val clock = StoreScreensaver("saver_clock", "Drifting clock", "", default = true, since = "0.1.0")
        val later = StoreScreensaver("saver_weather", "Rolling weather", "", default = false, since = "0.1.0", requires = "0.25.0", revision = 2)
        val added = listOf(maze, clock, later)
        assertEquals(listOf(maze), StoreUpdates.broughtBy(added, appVersion = "0.23.0", release = "v0.24.0"))
        assertEquals(listOf(maze, later), StoreUpdates.broughtBy(added, appVersion = "0.23.0", release = "v0.25.0"))
        assertEquals(emptyList<StoreBuiltIn>(), StoreUpdates.broughtBy(added, appVersion = "0.24.0", release = "v0.24.0"))
    }
}
