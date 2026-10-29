package dev.solon.bucklerhome

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherMapTest {
    private fun point(pressure: String, speed: String = "[10, 12]", dir: String = "[270, 280]", rain: String = "[0, 0.4]") =
        """{"latitude": 50.0, "longitude": 0.0, "hourly": {"time": ["2026-10-29T13:00", "2026-10-29T14:00"],
        "pressure_msl": $pressure, "wind_speed_10m": $speed, "wind_direction_10m": $dir, "precipitation": $rain}}"""

    @Test
    fun parsesAMultiPointReplyInRequestOrder() {
        val body = "[" + listOf(
            point("[1000, 1001]"), point("[1004, 1005]"),
            point("[1008, 1009]"), point("[null, 1013]", rain = "[null, 2.5]"),
        ).joinToString(",") + "]"
        val grid = WeatherMapClient.parseGrid(body, listOf(49.0, 51.0), listOf(-1.0, 1.0))
        assertNotNull(grid)
        grid!!
        assertEquals(listOf("2026-10-29T13:00", "2026-10-29T14:00"), grid.hours)
        assertEquals(1004f, grid.pressure[0][1], 0f)
        assertEquals(1013f, grid.pressure[1][3], 0f)
        assertTrue("a null pressure is a gap", grid.pressure[0][3].isNaN())
        assertEquals("a null rain reading is dry", 0f, grid.rain[0][3], 0f)
        assertEquals(2.5f, grid.rain[1][3], 0f)
        assertEquals(280f, grid.windDir[1][0], 0f)
    }

    @Test
    fun rejectsErrorsAndWrongShapes() {
        val lats = listOf(49.0, 51.0)
        val lons = listOf(-1.0, 1.0)
        assertNull(WeatherMapClient.parseGrid("""{"error": true, "reason": "bad"}""", lats, lons))
        assertNull(WeatherMapClient.parseGrid("[" + point("[1000, 1001]") + "]", lats, lons))
        assertNull(WeatherMapClient.parseGrid("not json", lats, lons))
    }

    @Test
    fun gridIsCentredOnThePlaceAndFitsOneRequest() {
        val (lats, lons) = WeatherMapClient.gridFor(Place("Leeds", 53.8, -1.55))
        assertTrue(lats.size * lons.size <= 100)
        assertEquals(53.8, (lats.first() + lats.last()) / 2, 1e-9)
        assertEquals(-1.55, (lons.first() + lons.last()) / 2, 1e-9)
        assertTrue("longitude span is wider than latitude", lons.last() - lons.first() > lats.last() - lats.first())
    }

    @Test
    fun windFromTheWestBlowsEast() {
        val grid = WeatherGrid(
            lats = listOf(0.0, 1.0), lons = listOf(0.0, 1.0), hours = listOf("h"),
            pressure = arrayOf(FloatArray(4) { 1010f }),
            windSpeed = arrayOf(FloatArray(4) { 20f }),
            windDir = arrayOf(FloatArray(4) { 270f }),
            rain = arrayOf(FloatArray(4)),
        )
        val (east, north) = grid.windFields(0)
        assertEquals(20f, east.sample(0.5f, 0.5f), 1e-3f)
        assertEquals(0f, north.sample(0.5f, 0.5f), 1e-3f)
    }

    /** A bowl: pressure rises with distance from the middle of a 7 x 7 grid. */
    private fun bowl(centre: Float, perStep: Float) = ScalarField(
        7, 7, FloatArray(49) { i -> centre + perStep * hypot((i / 7 - 3).toFloat(), (i % 7 - 3).toFloat()) },
    )

    @Test
    fun upsamplingKeepsSamplesAndInterpolatesBetween() {
        val f = ScalarField(2, 2, floatArrayOf(0f, 4f, 8f, 12f))
        val fine = f.upsample(4)
        assertEquals(5, fine.rows)
        assertEquals(5, fine.cols)
        assertEquals(12f, fine[4, 4], 0f)
        assertEquals(6f, fine[2, 2], 1e-5f)
    }

    @Test
    fun contoursOfABowlAreClosedRingsAtTheRightRadius() {
        val field = bowl(996f, 4f).upsample(4)
        assertEquals(listOf(1000f, 1004f, 1008f), Isobars.levels(field).take(3))
        val rings = Isobars.contour(field, 1004f)
        assertEquals(1, rings.size)
        val ring = rings.single()
        assertTrue(ring.closed)
        // 1004 hPa is two grid steps from the centre (12, 12 after upsampling by 4).
        for (i in 0 until ring.size) {
            val r = hypot(ring.xs[i] - 12f, ring.ys[i] - 12f) / 4f
            assertEquals(2f, r, 0.15f)
        }
    }

    @Test
    fun aStraightGradientGivesOneOpenLineAcrossTheField() {
        val field = ScalarField(3, 4, FloatArray(12) { i -> 1000f + 4f * (i % 4) })
        val lines = Isobars.contour(field, 1006f)
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(!line.closed)
        assertEquals(3, line.size)
        for (i in 0 until line.size) assertEquals(1.5f, line.xs[i], 1e-5f)
    }

    @Test
    fun findsHighsAndLowsInTheInteriorOnly() {
        val low = Isobars.extrema(bowl(990f, 3f))
        assertEquals(listOf(Extremum(3, 3, 990f, high = false, y = 3f, x = 3f)), low)
        val high = Isobars.extrema(bowl(1030f, -3f))
        assertEquals(listOf(Extremum(3, 3, 1030f, high = true, y = 3f, x = 3f)), high)
        // A high halfway between two samples is placed between them.
        val ridge = ScalarField(3, 4, floatArrayOf(0f, 0f, 0f, 0f, 0f, 9f, 9f, 0f, 0f, 0f, 0f, 0f))
        assertTrue(Isobars.extrema(ridge).isEmpty())
        val offCentre = ScalarField(3, 4, floatArrayOf(0f, 0f, 0f, 0f, 0f, 9f, 8f, 0f, 0f, 0f, 0f, 0f))
        val peak = Isobars.extrema(offCentre).single()
        assertTrue(peak.high && peak.x > 1f && peak.x < 1.5f && peak.y == 1f)
        // A slope peaks on its edge, which is not a centre.
        assertTrue(Isobars.extrema(ScalarField(3, 4, FloatArray(12) { it.toFloat() })).isEmpty())
    }

    @Test
    fun coastlineFileRoundTripsAndWrapsAcrossTheAntimeridian() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x42434C31)
            writeInt(1)
            writeShort(3)
            // A small island just west of the antimeridian: 10..11 N, 179..179.5 E.
            listOf(1000, 1100, 17900, 17950).forEach { writeShort(it) }
            listOf(1000 to 17900, 1100 to 17900, 1050 to 17950).forEach { (lat, lon) -> writeShort(lat); writeShort(lon) }
        }
        val rings = Coastlines.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1, rings.size)
        assertEquals(17950.toShort(), rings[0].lons[2])
        // Seen from a view east of the antimeridian, the island comes back shifted by -360.
        val shown = Coastlines.visible(rings, 5.0, 15.0, -185.0, -170.0)
        assertEquals(1, shown.size)
        assertEquals(-181f, shown[0].lons[0], 1e-4f)
        assertTrue(Coastlines.visible(rings, 20.0, 30.0, 170.0, 185.0).isEmpty())
    }

    @Test
    fun bundledCoastlinesLoadAndCoverBritain() {
        val rings = java.io.File("src/main/assets/coastlines.bin").inputStream().use(Coastlines::read)
        assertTrue(rings.size > 500)
        assertTrue(Coastlines.visible(rings, 50.0, 58.0, -6.0, 2.0).isNotEmpty())
    }
}
