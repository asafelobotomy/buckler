package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomeLayoutTest {
    private fun layoutJson(shelves: String) =
        """{"schema":1,"layout":"poster","shelves":$shelves}"""

    @Test
    fun duplicateShelfIdsAndPackagesAreDropped() {
        val layout = HomeLayout.fromJson(
            layoutJson(
                """[
                  {"id":"apps","type":"apps","title":"A","enabled":true,"packages":["a.b","a.b","c.d"]},
                  {"id":"apps","type":"library","title":"Dup","enabled":true}
                ]""",
            ),
        )
        assertEquals(1, layout.shelves.size)
        assertEquals(listOf("a.b", "c.d"), layout.shelves[0].packages)
        assertEquals(CoverLayout.POSTER, layout.layout)
    }

    @Test
    fun emptyShelvesFallBackToDefaults() {
        val layout = HomeLayout.fromJson(layoutJson("[]"))
        assertEquals(HomeLayout.defaultShelves().map { it.id }, layout.shelves.map { it.id })
    }

    @Test
    fun emptyAppsShelfReportsDefaultPins() {
        val layout = HomeLayout.default(emptyList())
        assertEquals(DEFAULT_DOCK_PACKAGES, layout.appsPackages())
    }

    @Test
    fun pinUnpinAndMove() {
        val base = HomeLayout.default(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c", "d"), base.withPinned("d").appsPackages())
        assertEquals(listOf("a", "b", "c"), base.withPinned("a").appsPackages())
        assertEquals(listOf("a", "c"), base.withoutPinned("b").appsPackages())
        assertEquals(listOf("b", "a", "c"), base.withMovedPinned("a", 1).appsPackages())
        assertEquals(listOf("a", "c", "b"), base.withMovedPinned("c", -1).appsPackages())
        assertSame(base, base.withMovedPinned("a", -1))
        assertSame(base, base.withMovedPinned("c", 1))
    }

    @Test
    fun roundTripsThroughJson() {
        val original = HomeLayout.default(listOf("a", "b")).copy(layout = CoverLayout.ICON)
        val parsed = HomeLayout.fromJson(original.toJson().toString())
        assertEquals(original, parsed)
    }
}
