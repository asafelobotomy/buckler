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

    @Test
    fun aStripsPaperIsTheMedianOfItsEdge() {
        val paper = 0xFFE8DCC0.toInt()
        val ink = 0xFF000000.toInt()
        // 8 x 4 of paper, with a black panel border along part of the top edge and ink inside.
        val pixels = IntArray(32) { paper }
        for (x in 2..5) pixels[x] = ink
        for (i in listOf(10, 11, 12, 13, 18, 19, 20, 21)) pixels[i] = ink
        assertEquals(paper, ComicStrips.edgeColour(pixels, 8, 4, ring = 1))
        // Greyed blocks along the edge (ink averaged in) fall below the paper, not into it.
        val grey = 0xFFB0B0B0.toInt()
        for (i in listOf(0, 1, 6, 7, 8, 15, 16, 23)) pixels[i] = grey
        assertEquals(paper, ComicStrips.edgeColour(pixels, 8, 4, ring = 1))
        // A strip photographed on black cloth: its edge is dark, and so is the card.
        assertEquals(ink, ComicStrips.edgeColour(IntArray(32) { ink }, 8, 4, ring = 1))
    }

    @Test
    fun inkKeepsLinesAndDropsThePaper() {
        val paper = 230
        val ink = 20
        assertEquals(0, ComicInk.inkAlpha(paper, paper, ink))
        assertEquals("paper grain just off white is dropped", 0, ComicInk.inkAlpha(paper - 10, paper, ink))
        assertEquals(255, ComicInk.inkAlpha(ink, paper, ink))
        assertEquals(255, ComicInk.inkAlpha(0, paper, ink))
        // A faint pencil line a third of the way to black still shows clearly.
        assertTrue(ComicInk.inkAlpha(160, paper, ink) > 110)
    }

    @Test
    fun otsuSplitsInkFromPaper() {
        val lum = IntArray(1000) { if (it % 5 == 0) 30 else 225 }
        val t = ComicInk.otsu(lum)
        assertTrue("threshold $t lies between ink and paper", t in 30 until 225)
        assertEquals(225, ComicInk.percentile(lum, 0.9))
    }

    @Test
    fun inkBoundsTrimTheMargins() {
        // 10 x 6, paper everywhere but a 4 x 2 block of ink at (3, 2).
        val lum = IntArray(60) { 230 }
        for (y in 2..3) for (x in 3..6) lum[y * 10 + x] = 10
        assertEquals(listOf(3, 2, 7, 4), ComicInk.inkBounds(lum, 10, 6, 120).toList())
    }

    @Test
    fun curatedStripsCarryTheirPanels() {
        val json = """{"credit":"Mickey Mouse, 1930","strips":[
            {"item":"mickey-mouse-comic-strip-1930-05","file":"1930-05-05 Mickey Mouse comic.jpg","date":"1930-05-05",
             "panels":[[0.01,0.02,0.3,0.98],[0.32,0.02,0.6,0.98]]},
            {"item":"mickey-mouse-comic-strip-1930-05","file":"1930-05-06 Mickey Mouse comic.jpg","date":"1930-05-06","panels":[]}]}"""
        val strips = CuratedComics.parse(json)
        assertEquals(2, strips.size)
        assertEquals(2, strips[0].panels.size)
        assertEquals(0.32f, strips[0].panels[1].left, 1e-6f)
        assertTrue(strips[1].panels.isEmpty())
        assertEquals("1930-05-05 · Mickey Mouse, 1930", strips[0].credit)
        assertEquals(
            "https://iiif.archive.org/image/iiif/3/mickey-mouse-comic-strip-1930-05%2F1930-05-05%20Mickey%20Mouse%20comic.jpg/full/1600,/0/default.jpg",
            strips[0].url,
        )
    }

    @Test
    fun theReaderCutsAWholeStripAtItsGutters() {
        // 300 wide, 100 tall: frames at 0, 98, 102, 198, 202 and 299 make three panels.
        val runs = IntArray(300) { 10 }
        for (x in listOf(0, 98, 102, 198, 202, 299)) runs[x] = 100
        assertEquals(listOf(100, 200), gutterCuts(runs, 100))
        // A tall drawn line inside a panel that stops short of the frames is not a cut...
        runs[150] = 90
        assertEquals(listOf(100, 200), gutterCuts(runs, 100))
        // ...but a frame line two panels share, as tall as the frames, is.
        runs[150] = 100
        assertEquals(listOf(100, 150, 200), gutterCuts(runs, 100))
        // No gutters at all: nothing to cut at, and the reader slices evenly instead.
        assertTrue(gutterCuts(IntArray(300) { 10 }, 100).isEmpty())
        assertEquals(3, readerSlices(3300, 1000))
        assertEquals(1, readerSlices(900, 1000))
    }
}
