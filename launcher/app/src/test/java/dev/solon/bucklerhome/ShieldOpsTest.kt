package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShieldOpsTest {
    @Test
    fun onlyPackageNamesReachTheShell() {
        assertTrue(ShieldOps.isPackageName("com.google.android.katniss"))
        assertTrue(ShieldOps.isPackageName("tv.twitch.android.app"))
        assertFalse(ShieldOps.isPackageName("com.evil; reboot"))
        assertFalse(ShieldOps.isPackageName("com.x && rm -rf /"))
        assertFalse(ShieldOps.isPackageName("katniss"))
        assertFalse(ShieldOps.isPackageName("com.\$(id)"))
        assertFalse(ShieldOps.isPackageName(""))
    }

    @Test
    fun bundledProfilesKeepTheNeverListApart() {
        val text = File("src/main/assets/shield_profiles.json").readText()
        val data = ShieldData.parse(text)
        assertEquals(setOf("extras", "minimal", "privacy-core", "speech"), data.profiles.keys)
        assertTrue("com.google.android.gms" in data.never)
        assertTrue("com.google.android.apps.mediashell" in data.never)
        // No profile may list a package the never list protects.
        for (profile in data.profiles.values) {
            assertTrue(profile.name, profile.packages.none { it in data.never })
        }
        assertTrue(data.profiles.values.flatMap { it.packages }.all(ShieldOps::isPackageName))
    }
}
