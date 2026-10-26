package dev.solon.bucklerhome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverLogicTest {
    @Test
    fun bounceReflectsOffEveryEdge() {
        val b = DvdBounce(x = 90f, y = 40f, vx = 100f, vy = -250f)
        val hit = b.step(0.2f, maxX = 100f, maxY = 100f)
        assertTrue(hit.x && hit.y && hit.corner)
        assertEquals(100f, b.x, 0.001f)
        assertEquals(0f, b.y, 0.001f)
        assertTrue(b.vx < 0 && b.vy > 0)
    }

    @Test
    fun bounceIgnoresInteriorMoves() {
        val b = DvdBounce(x = 10f, y = 10f, vx = 10f, vy = 10f)
        assertFalse(b.step(0.1f, 100f, 100f).any)
        assertEquals(11f, b.x, 0.001f)
    }

    @Test
    fun commonsResultsKeepOnlyLandscapePhotos() {
        val body = """{"query":{"pages":{
          "1":{"title":"File:Lake view.jpg","imageinfo":[{"thumburl":"https://x/a.jpg","thumbwidth":1920,"thumbheight":1280,
            "extmetadata":{"Artist":{"value":"<a href='u'>Jane</a>"},"LicenseShortName":{"value":"CC BY-SA 4.0"}}}]},
          "2":{"title":"File:Tall.jpg","imageinfo":[{"thumburl":"https://x/b.jpg","thumbwidth":1000,"thumbheight":1500}]},
          "3":{"title":"File:Pano.jpg","imageinfo":[{"thumburl":"https://x/c.jpg","thumbwidth":1920,"thumbheight":400}]}
        }}}"""
        val images = ScreensaverImages.parseCommons(body)
        assertEquals(1, images.size)
        assertEquals("https://x/a.jpg", images[0].url)
        assertEquals("Lake view · Jane · CC BY-SA 4.0", images[0].credit)
    }

    @Test
    fun uploadNamesAreSanitisedAndUnique() {
        assertEquals("a_b.jpg", UploadServer.sanitize("a b.jpg"))
        assertEquals("passwd.png", UploadServer.sanitize("../../etc/passwd.png"))
        assertEquals("hidden.jpg", UploadServer.sanitize(".hidden.jpg"))
        assertTrue(ScreensaverImages.isImageName("x.JPG"))
        assertFalse(ScreensaverImages.isImageName("x.sh"))

        val dir = File(System.getProperty("java.io.tmpdir"), "bucklerhome-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "p.jpg").writeText("x")
            assertEquals("p-1.jpg", UploadServer.uniqueFile(dir, "p.jpg").name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun appsShelfIsReorderedForMoveMode() {
        fun item(pkg: String) = ShelfItem(id = "app-$pkg", title = pkg, packageName = pkg)
        val apps = ResolvedShelf(
            ShelfConfig(id = "apps", type = ShelfType.APPS, title = "Apps", enabled = true),
            listOf(item("a"), item("b"), item("c")),
        )
        val other = ResolvedShelf(
            ShelfConfig(id = "lib", type = ShelfType.LIBRARY, title = "Lib", enabled = true),
            listOf(item("c"), item("a")),
        )
        val out = listOf(apps, other).withAppsOrder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), out[0].items.map { it.packageName })
        assertEquals(listOf("c", "a"), out[1].items.map { it.packageName })
    }
}
