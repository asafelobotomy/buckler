package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoreTest {
    private val bundled = StoreIndex.parse(File("src/main/assets/store_index.json").readText())

    @Test
    fun bundledIndexParsesWhole() {
        assertTrue(bundled.widgets.isNotEmpty())
        assertTrue(bundled.themes.isNotEmpty())
        assertTrue(bundled.fonts.isNotEmpty())
        assertTrue(bundled.iconPacks.isNotEmpty())
    }

    @Test
    fun everyMainStageIsAStoreWidget() {
        // A widget the store does not list could never be removed, nor re-added after.
        assertEquals(MainStage.entries.map { it.wire() }.toSet(), bundled.widgets.mapNotNull { it.stage?.wire() }.toSet())
    }

    @Test
    fun storeThemesMeetTheBuiltInContrastRules() {
        bundled.themes.forEach { theme ->
            val p = theme.palette
            assertTrue("${theme.id} text/bg", Palettes.contrast(p.text, p.bgTop) >= 7f)
            assertTrue("${theme.id} text/tile", Palettes.contrast(p.text, p.tile) >= 4.5f)
            assertTrue("${theme.id} muted/bg", Palettes.contrast(p.muted, p.bgTop) >= 4.5f)
            assertTrue("${theme.id} onBrand/brand", Palettes.contrast(p.onBrand, p.brand) >= 4.5f)
        }
    }

    @Test
    fun themesRoundTripThroughTheirSavedForm() {
        val theme = bundled.themes.first()
        assertEquals(theme, StoreIndex.theme(StoreLibrary.themeJson(theme)))
    }

    @Test
    fun unknownSchemaOffersNothing() {
        val catalog = StoreIndex.parse("""{"schema": 2, "themes": []}""")
        assertTrue(catalog.themes.isEmpty() && catalog.widgets.isEmpty())
    }

    @Test
    fun badEntriesAreLeftOut() {
        val raw = """{"schema": 1, "fonts": [
            {"id": "ok", "label": "Ok", "files": {
              "regular": {"url": "https://x/r.ttf", "sha256": "${"a".repeat(64)}"},
              "bold": {"url": "https://x/b.ttf", "sha256": "${"b".repeat(64)}"}}},
            {"id": "plain-http", "label": "No", "files": {
              "regular": {"url": "http://x/r.ttf", "sha256": "${"a".repeat(64)}"},
              "bold": {"url": "https://x/b.ttf", "sha256": "${"b".repeat(64)}"}}},
            {"id": "../escape", "label": "No", "files": {}}
        ]}"""
        assertEquals(listOf("ok"), StoreIndex.parse(raw).fonts.map { it.id })
    }

    @Test
    fun coloursMustBeSixDigitHex() {
        assertNotNull(StoreIndex.parseColor("#1A2B3C"))
        assertNull(StoreIndex.parseColor("1A2B3C"))
        assertNull(StoreIndex.parseColor("#FFF"))
    }

    @Test
    fun unlistedWidgetsStayAdded() {
        assertTrue(StoreLibrary.widgetAdded(MainStage.NEWS, StoreCatalog()))
        val optIn = StoreCatalog(widgets = listOf(StoreWidget("lyrics", "Lyrics", "", default = false, since = "0.1.0")))
        assertFalse(StoreLibrary.widgetAdded(MainStage.LYRICS, optIn))
    }

    @Test
    fun onlyTheFourStandardWidgetsComeAdded() {
        val standard = bundled.widgets.filter { it.default }.mapNotNull { it.stage }.toSet()
        assertEquals(setOf(MainStage.NEWS, MainStage.NOW_PLAYING, MainStage.WEATHER, MainStage.SUGGESTED), standard)
    }

    @Test
    fun everyScreensaverIsInTheStore() {
        // A screensaver the store does not list could never be removed, nor re-added after.
        assertEquals(ScreensaverMode.entries.toSet(), bundled.screensavers.mapNotNull { it.mode }.toSet())
    }

    @Test
    fun theStandardScreensaversComeAdded() {
        val standard = bundled.screensavers.filter { it.default }.mapNotNull { it.mode }.toSet()
        assertEquals(
            setOf(ScreensaverMode.CLOCK, ScreensaverMode.PHOTOS, ScreensaverMode.UPLOADED, ScreensaverMode.NOW_PLAYING),
            standard,
        )
        assertTrue(StoreLibrary.saverAdded(ScreensaverMode.CLOCK, bundled))
        assertFalse(StoreLibrary.saverAdded(ScreensaverMode.MAZE, bundled))
        // Ids carry a prefix so a screensaver never shares one with a widget (Weather and Rolling weather).
        assertEquals("saver_weather", ScreensaverMode.WEATHER.wire())
        assertTrue(bundled.widgets.none { w -> bundled.screensavers.any { it.id == w.id } })
    }

    @Test
    fun widgetsOnScreenCountAsInUse() {
        assertEquals(setOf("lyrics", "companion"), StoreLibrary.inUse(MainStage.LYRICS, SideIdle.COMPANION))
        assertEquals(setOf("news"), StoreLibrary.inUse(MainStage.NEWS, SideIdle.NOTHING))
        assertEquals(MainStage.GLANCE, SideIdle.GLANCE.widget)
    }

    @Test
    fun everyBuiltInSchemeIsInTheStoreAndFourComeAdded() {
        assertEquals(AppTheme.entries.toSet(), bundled.schemes.mapNotNull { it.theme }.toSet())
        val standard = bundled.schemes.filter { it.default }.mapNotNull { it.theme }.toSet()
        assertEquals(setOf(AppTheme.BUCKLER, AppTheme.LIGHT, AppTheme.DARK, AppTheme.SHIELD), standard)
    }

    @Test
    fun unlistedSchemesStayAdded() {
        assertTrue(StoreLibrary.schemeAdded(AppTheme.GRUVBOX, StoreCatalog()))
        val optIn = StoreCatalog(schemes = listOf(StoreScheme("scheme_gruvbox", "Gruvbox", "", default = false, since = "0.1.0")))
        assertFalse(StoreLibrary.schemeAdded(AppTheme.GRUVBOX, optIn))
    }
}
