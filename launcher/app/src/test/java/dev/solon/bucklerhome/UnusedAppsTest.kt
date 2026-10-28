package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnusedAppsTest {
    private fun app(last: Long = 0L, runs: Int = 0, runMs: Long = 0L, bytes: Long = 0L) =
        AppUsage("p", "P", system = false, lastUsedAt = last, foregroundMs = 0L,
            backgroundRuns = runs, backgroundRunMs = runMs, backgroundBytes = bytes)

    @Test
    fun noticeNamesUpToThreeApps() {
        assertEquals("1 app unused for 30 days: Prime. See Settings → Clean up → Apps.", UnusedApps.noticeMessage(listOf("Prime"), 30))
        assertEquals(
            "5 apps unused for 14 days: A, B, C and 2 more. See Settings → Clean up → Apps.",
            UnusedApps.noticeMessage(listOf("A", "B", "C", "D", "E"), 14),
        )
    }

    @Test
    fun backgroundWorkIsDescribedOnlyWhenThere() {
        assertNull(UnusedApps.describeBackground(app(bytes = 500_000)))
        assertEquals("ran in the background once", UnusedApps.describeBackground(app(runs = 1, runMs = 30_000)))
        assertEquals(
            "ran in the background 12 times (40 min), 85 MB of data",
            UnusedApps.describeBackground(app(runs = 12, runMs = 2_400_000, bytes = 85_000_000)),
        )
    }

    @Test
    fun busierAppsRankHigher() {
        val quiet = app(runs = 1)
        val busy = app(runs = 3, runMs = 600_000, bytes = 50_000_000)
        assert(busy.backgroundScore > quiet.backgroundScore)
        assertEquals("never opened", UnusedApps.describeLastUse(app()))
        assertEquals("last opened 45 days ago", UnusedApps.describeLastUse(app(last = 1_000L), now = 1_000L + 45 * 86_400_000L))
    }
}
