package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuriositiesTest {
    /** The shape of a tylervigen.com correlation page, cut down to what the parser reads. */
    private val page = """
        <html><head><title>Votes for the Democratic candidate in Texas correlates with Recalls issued by BMW &amp; Co (r=0.949)</title></head>
        <body><p>…</p>
        <!--Start Table of Data-->
        <div style='overflow: auto'><table border='1' cellpadding='2' width='100%'><tr><td style='min-width:185px'></td><td><b>1976</b></td><td><b>1980</b></td><td><b>1984</b></td></tr><tr><td width='150'><b>Votes for the Democratic candidate in Texas (Total votes)</b></td><td>2082320</td><td>1881150</td><td>1949280</td></tr><tr><td><b>Recalls issued by BMW (Recalls)</b></td><td>3</td><td>1</td><td>3</td></tr></table></div>
        </body></html>
    """.trimIndent()

    @Test
    fun readsTheTitleAndTheDataTable() {
        val c = SpuriousCorrelations.parse(page)
        assertNotNull(c)
        c!!
        assertEquals("Votes for the Democratic candidate in Texas", c.first)
        assertEquals("Recalls issued by BMW & Co", c.second)
        assertEquals("Total votes", c.firstUnit)
        assertEquals("Recalls", c.secondUnit)
        assertEquals(0.949, c.r, 1e-9)
        assertEquals(listOf(1976, 1980, 1984), c.years)
        assertEquals(listOf(2082320.0, 1881150.0, 1949280.0), c.firstValues)
        assertEquals(listOf(3.0, 1.0, 3.0), c.secondValues)
    }

    @Test
    fun aPageOfAnotherShapeIsNotACorrelation() {
        assertNull(SpuriousCorrelations.parse("<html><title>Spurious Correlations</title></html>"))
        assertNull(SpuriousCorrelations.parse(page.replace("<td>1</td>", "<td>n/a</td>")))
        assertNull(SpuriousCorrelations.parse(page.replace("<!--Start Table of Data-->", "")))
    }

    @Test
    fun comicsKeepOnlyDailyStrips() {
        fun file(title: String, w: Int, h: Int) =
            """"$title": {"title": "File:$title.jpg", "imageinfo": [{"thumburl": "https://upload.wikimedia.org/$title.jpg",
            "thumbwidth": $w, "thumbheight": $h, "extmetadata": {"LicenseShortName": {"value": "Public domain"}}}]}"""
        val body = """{"query": {"pages": {${listOf(
            file("strip", 1600, 500),
            file("sunday", 1600, 2100),
            file("panel", 1600, 1200),
            file("small", 600, 180),
        ).joinToString(",")}}}}"""
        val strips = ScreensaverImages.parseCommons(body, minWidth = 900, aspect = ComicStrips.STRIP_SHAPE)
        assertEquals(listOf("https://upload.wikimedia.org/strip.jpg"), strips.map { it.url })
        assertTrue(strips.single().credit.contains("Public domain"))
    }

    @Test
    fun heatColoursRunColdToHot() {
        assertEquals(heatColour(-40f), heatColour(-25f))
        assertEquals(heatColour(60f), heatColour(40f))
        // Warmer reads redder: the red channel climbs from freezing to hot.
        assertTrue(heatColour(0f).red < heatColour(30f).red)
        assertTrue(heatColour(-10f).blue > heatColour(30f).blue)
    }

    @Test
    fun theTickerScrollsUnlessToldOtherwise() {
        assertEquals(TickerStyle.SCROLL, TickerStyle.fromName(null))
        assertEquals(TickerStyle.SINGLE, TickerStyle.fromName("SINGLE"))
        assertEquals(TickerStyle.SCROLL, DisplayConfig().tickerStyle)
    }
}
