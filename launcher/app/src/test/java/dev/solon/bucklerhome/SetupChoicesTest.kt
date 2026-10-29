package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupChoicesTest {
    private val data = ShieldData.parse(File("src/main/assets/shield_profiles.json").readText())

    private fun sample() = SetupChoices(
        mode = SetupMode.ADVANCED,
        theme = AppTheme.entries.last(),
        font = FontChoice.entries.last(),
        layout = CoverLayout.POSTER,
        tileSize = TileSize.entries.first(),
        shelves = mapOf(ShelfType.WATCH_NEXT to true, ShelfType.RECENTS to false),
        saver = ScreensaverMode.MYSTIFY,
        groups = setOf(CleanGroup.PRIVACY),
        offApps = setOf("com.nvidia.stats"),
        animation = 0.5f,
        dns = DnsChoice("hostname", "dns.quad9.net"),
        systemSaver = true,
        mediaAccess = true,
        apps = setOf("smarttube", "google-tv"),
        iconPack = "app.lawnchair.lawnicons",
    )

    @Test
    fun choicesSurviveTheRestartRoundTrip() {
        val choices = sample()
        assertEquals(choices, SetupChoices.fromJson(choices.toJson()))
    }

    @Test
    fun damagedSavedChoicesAreDropped() {
        assertEquals(null, SetupChoices.fromJson(sample().toJson().put("theme", "NO_SUCH_THEME")))
    }

    @Test
    fun voiceKeepsTheAssistantOutOfPrivacy() {
        assertFalse(CleanGroup.ASSISTANT in CleanGroup.PRIVACY.packages(data))
        assertTrue(CleanGroup.ASSISTANT in CleanGroup.VOICE.packages(data))
        // Only Privacy and Extras are on by default: Simple never removes the Assistant unasked.
        assertEquals(setOf(CleanGroup.PRIVACY, CleanGroup.EXTRAS), CleanGroup.entries.filter { it.onByDefault }.toSet())
    }

    @Test
    fun groupsNeverReachTheNeverList() {
        CleanGroup.entries.forEach { group -> assertTrue(group.name, group.packages(data).none { it in data.never }) }
    }

    @Test
    fun aGroupSwitchTurnsItsAppsOffAndOn() {
        val off = sample().withGroup(CleanGroup.EXTRAS, true, data)
        assertTrue(off.offApps.containsAll(CleanGroup.EXTRAS.packages(data)))
        val on = off.withGroup(CleanGroup.EXTRAS, false, data)
        assertTrue(on.offApps.none { it in CleanGroup.EXTRAS.packages(data) })
        assertFalse(CleanGroup.EXTRAS in on.groups)
    }

    @Test
    fun everyBundledAppIsPinnedAndHttps() {
        assertTrue(data.apps.size >= 3)
        data.apps.filter { it.url.isNotBlank() }.forEach { app ->
            assertTrue(app.id, app.installable)
            assertTrue(app.id, app.packageName !in data.never)
        }
        assertEquals(2, data.apps.count { it.group == CatalogApp.ICON_PACK })
    }

    @Test
    fun dockRulesMatchTheCli() {
        val byId = data.apps.associateBy { it.id }
        val dock = listOf("com.google.android.youtube.tv", "com.google.android.videos", "org.jellyfin.androidtv")
        val next = SetupApps.applyDock(dock, listOfNotNull(byId["smarttube"], byId["google-tv"], byId["moonlight"]))
        // SmartTube takes YouTube's place, Google TV is unpinned, Moonlight goes last.
        assertEquals(listOf("org.smarttube.stable", "org.jellyfin.androidtv", "com.limelight"), next)
    }
}
