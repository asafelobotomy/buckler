package dev.solon.bucklerhome

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun normalizeStripsPrefixAndBuildMetadata() {
        assertEquals("1.2.3", UpdateChecker.normalizeVersion("v1.2.3+build5"))
        assertEquals("1.2.3-rc1", UpdateChecker.normalizeVersion(" V1.2.3-rc1 "))
    }

    @Test
    fun newerNumericVersionWins() {
        assertTrue(UpdateChecker.compareSemver("0.10.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareSemver("1.0", "1.0.0") == 0)
        assertTrue(UpdateChecker.compareSemver("0.5.0", "0.5.1") < 0)
    }

    @Test
    fun releaseOutranksItsPrerelease() {
        assertTrue(UpdateChecker.compareSemver("1.0.0", "1.0.0-rc1") > 0)
        assertTrue(UpdateChecker.compareSemver("1.0.0-rc1", "1.0.0") < 0)
        assertTrue(UpdateChecker.compareSemver("1.0.0-rc2", "1.0.0-rc1") > 0)
    }

    @Test
    fun parseReleasePrefersBucklerApk() {
        val json = JSONObject(
            """
            {"tag_name":"v0.6.0","name":"0.6.0","body":"notes","assets":[
              {"name":"other.zip","browser_download_url":"https://x/other.zip","id":1},
              {"name":"app-release.apk","browser_download_url":"https://x/app.apk","id":2},
              {"name":"buckler-home-0.6.0.apk","browser_download_url":"https://x/bh.apk","id":3}
            ]}
            """.trimIndent(),
        )
        val release = UpdateChecker.parseRelease(json)
        assertNotNull(release)
        assertEquals("buckler-home-0.6.0.apk", release!!.apkName)
        assertEquals("https://x/bh.apk", release.apkUrl)
        assertTrue(release.apkApiUrl!!.endsWith("/releases/assets/3"))
    }

    @Test
    fun parseReleaseWithoutApkHasNoUrl() {
        val release = UpdateChecker.parseRelease(JSONObject("""{"tag_name":"v1","assets":[]}"""))
        assertNotNull(release)
        assertNull(release!!.apkUrl)
    }
}
