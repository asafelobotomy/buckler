package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTest {
    @Test
    fun dayPhaseBoundaries() {
        val expected = mapOf(
            0 to DayPhase.NIGHT,
            5 to DayPhase.NIGHT,
            6 to DayPhase.DAWN,
            7 to DayPhase.DAWN,
            8 to DayPhase.MORNING,
            11 to DayPhase.MORNING,
            12 to DayPhase.MIDDAY,
            13 to DayPhase.MIDDAY,
            14 to DayPhase.AFTERNOON,
            17 to DayPhase.AFTERNOON,
            18 to DayPhase.EVENING,
            21 to DayPhase.EVENING,
            22 to DayPhase.NIGHT,
            23 to DayPhase.NIGHT,
        )
        expected.forEach { (hour, phase) -> assertEquals("hour $hour", phase, DayPhase.of(hour)) }
    }

    @Test
    fun dayPhaseWrapsOutOfRangeHours() {
        assertEquals(DayPhase.NIGHT, DayPhase.of(24))
        assertEquals(DayPhase.DAWN, DayPhase.of(30))
        assertEquals(DayPhase.NIGHT, DayPhase.of(-1))
    }

    @Test
    fun skyFromWmoCodes() {
        assertEquals(Sky.CLEAR, Sky.of(0))
        for (code in 1..2) assertEquals("code $code", Sky.CLOUDY, Sky.of(code))
        assertEquals(Sky.OVERCAST, Sky.of(3))
        assertEquals(Sky.FOG, Sky.of(45))
        assertEquals(Sky.FOG, Sky.of(48))
        for (code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)) {
            assertEquals("code $code", Sky.RAIN, Sky.of(code))
        }
        for (code in listOf(71, 73, 75, 77, 85, 86)) assertEquals("code $code", Sky.SNOW, Sky.of(code))
        for (code in listOf(95, 96, 99)) assertEquals("code $code", Sky.THUNDER, Sky.of(code))
    }

    @Test
    fun unknownOrMissingCodeIsClear() {
        assertEquals(Sky.CLEAR, Sky.of(null))
        for (code in listOf(-1, 4, 44, 50, 68, 70, 78, 83, 87, 94, 100)) {
            assertEquals("code $code", Sky.CLEAR, Sky.of(code))
        }
    }

    @Test
    fun onlyRainAndThunderAreWet() {
        assertTrue(Sky.RAIN.wet)
        assertTrue(Sky.THUNDER.wet)
        listOf(Sky.CLEAR, Sky.CLOUDY, Sky.OVERCAST, Sky.FOG, Sky.SNOW).forEach { assertFalse(it.name, it.wet) }
    }

    @Test
    fun companionWireRoundTrips() {
        CompanionKind.entries.forEach { assertEquals(it, CompanionKind.fromWire(it.wire())) }
        assertEquals(CompanionKind.FOX, CompanionKind.fromWire("FOX"))
        assertEquals(CompanionKind.CAT, CompanionKind.fromWire(null))
        assertEquals(CompanionKind.CAT, CompanionKind.fromWire("dragon"))
    }

    @Test
    fun loopsAreSeamlessAtTheWrap() {
        // A period that divides LOOP_SECONDS must land on the same point at 0 and at the wrap.
        for (period in listOf(0.75f, 1.5f, 3f, 4f, 6f, 8f, 10f, 120f, 150f, 200f, 300f)) {
            assertEquals("period $period", cycle(0f, period, 0.3f), cycle(LOOP_SECONDS, period, 0.3f), 1e-3f)
        }
    }
}
