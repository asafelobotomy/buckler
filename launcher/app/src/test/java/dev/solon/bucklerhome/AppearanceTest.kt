package dev.solon.bucklerhome

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun everyThemeIsReadable() {
        for (theme in AppTheme.entries) {
            for (hc in listOf(false, true)) {
                val p = Palettes.resolve(AppearanceConfig(theme = theme, highContrast = hc))
                val tag = "$theme hc=$hc"
                assertTrue("$tag text/bg", Palettes.contrast(p.text, p.bgTop) >= 7f)
                assertTrue("$tag text/tile", Palettes.contrast(p.text, p.tile) >= 4.5f)
                assertTrue("$tag text/footer", Palettes.contrast(p.text, p.footer) >= 4.5f)
                assertTrue("$tag muted/bg", Palettes.contrast(p.muted, p.bgTop) >= 4.5f)
                assertTrue("$tag onBrand/brand", Palettes.contrast(p.onBrand, p.brand) >= 4.5f)
                assertTrue("$tag brand/bg", Palettes.contrast(p.brand, p.bgTop) >= 3f)
                assertTrue("$tag section/bg", Palettes.contrast(p.section, p.bgTop) >= 3f)
                assertTrue("$tag hint/bg", Palettes.contrast(p.hint, p.bgTop) >= 3f)
            }
        }
    }

    @Test
    fun highContrastKeepsLightOrDark() {
        val dark = Palettes.resolve(AppearanceConfig(theme = AppTheme.GRUVBOX, highContrast = true))
        val light = Palettes.resolve(AppearanceConfig(theme = AppTheme.LIGHT, highContrast = true))
        assertTrue(dark.dark)
        assertFalse(light.dark)
        assertSame(Palettes.of(AppTheme.SHIELD), Palettes.resolve(AppearanceConfig(theme = AppTheme.SHIELD)))
    }

    @Test
    fun tileSizeScalesEveryLayout() {
        for (layout in CoverLayout.entries) {
            val thin = coverSize(layout, TileSize.THIN)
            val medium = coverSize(layout, TileSize.MEDIUM)
            val small = coverSize(layout, TileSize.SMALL)
            val large = coverSize(layout, TileSize.LARGE)
            assertTrue(thin.width < small.width && small.width < medium.width && medium.width < large.width)
            assertTrue(thin.height < small.height && small.height < medium.height && medium.height < large.height)
            assertTrue(thin.artHeight < small.artHeight)
            assertEquals(medium.width * 1.25f, large.width)
        }
        assertEquals(220.dp, coverSize(CoverLayout.BANNER).width)
        val icon = coverSize(CoverLayout.ICON)
        assertEquals(icon.width, icon.height)
        assertEquals(icon.artHeight, icon.height)
        val labeled = coverSize(CoverLayout.BANNER, showLabels = true)
        assertEquals(220.dp, labeled.width)
        assertTrue(labeled.height > labeled.artHeight)
        assertEquals(labeled.artHeight, coverSize(CoverLayout.BANNER).height)
    }

    @Test
    fun tileSizeSurvivesJsonAndDefaultsToMedium() {
        val saved = HomeLayout.default().copy(tileSize = TileSize.LARGE)
        assertEquals(TileSize.LARGE, HomeLayout.fromJson(saved.toJson().toString()).tileSize)
        val legacy = """{"schema":1,"layout":"banner","shelves":[]}"""
        assertEquals(TileSize.MEDIUM, HomeLayout.fromJson(legacy).tileSize)
        assertEquals(TileSize.THIN, TileSize.fromWire("thin"))
        assertEquals(TileSize.SMALL, TileSize.fromWire("Small"))
        assertEquals(TileSize.MEDIUM, TileSize.fromWire("tiny"))
    }

    @Test
    fun movingAShelfSwapsPastNeighbours() {
        val layout = HomeLayout.default()
        val ids = layout.shelves.map { it.id }
        val down = layout.withMovedShelf("watch_next", 1)
        assertEquals(listOf("apps", "watch_next") + ids.drop(2), down.shelves.map { it.id })
        val up = down.withMovedShelf("watch_next", -1)
        assertEquals(ids, up.shelves.map { it.id })
        // Already first / last: unchanged.
        assertSame(layout, layout.withMovedShelf("watch_next", -1))
        assertSame(layout, layout.withMovedShelf(ids.last(), 1))
        assertSame(layout, layout.withMovedShelf("missing", 1))
    }

    @Test
    fun movingAShelfSkipsHiddenAndEmptyShelves() {
        val layout = HomeLayout.default()
        // "recents" is disabled in the defaults, and "suggested" is enabled but empty (not visible).
        val visible = setOf("watch_next", "apps", "library")
        val moved = layout.withMovedShelf("apps", 1, visibleIds = visible)
        assertEquals(listOf("watch_next", "suggested", "recents", "library", "apps"), moved.shelves.map { it.id })
    }

    @Test
    fun screensaverDelaysAreWrittenForPeople() {
        assertEquals("60 sec", ScreensaverTimeout.label(60_000))
        assertEquals("90 sec", ScreensaverTimeout.label(90_000))
        assertEquals("120 sec", ScreensaverTimeout.label(120_000))
        assertEquals("5 min", ScreensaverTimeout.label(300_000))
        assertEquals("15 min", ScreensaverTimeout.label(900_000))
        assertEquals("1 hour", ScreensaverTimeout.label(3_600_000))
        assertEquals("2 hours", ScreensaverTimeout.label(7_200_000))
        assertEquals("7 min 30 s", ScreensaverTimeout.label(450_000))
        assertEquals("45 sec", ScreensaverTimeout.label(45_000))
        assertTrue(ScreensaverTimeout.isPreset(600_000))
        assertFalse(ScreensaverTimeout.isPreset(450_000))
        assertEquals(ScreensaverTimeout.MIN_MS, ScreensaverTimeout.clamp(1_000))
        assertEquals(ScreensaverTimeout.MAX_MS, ScreensaverTimeout.clamp(Int.MAX_VALUE))
    }

    @Test
    fun unknownChoicesFallBackToDefaults() {
        assertEquals(AppTheme.BUCKLER, AppTheme.fromName("nope"))
        assertEquals(FontChoice.SYSTEM, FontChoice.fromName(null))
        assertEquals(TextSize.NORMAL, TextSize.fromName("giant"))
        assertEquals(AppTheme.GRUVBOX, AppTheme.fromName("GRUVBOX"))
    }
}
