package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverOptionsTest {
    @Test
    fun everyDefaultIsOneOfItsChoices() {
        SaverOption.entries.forEach { option ->
            assertTrue("${option.name} default", option.choices.any { it.first == option.default })
        }
        ScreensaverMode.entries.forEach { mode ->
            assertTrue("${mode.name} has options", mode.options.isNotEmpty())
            assertEquals("${mode.name} lists each once", mode.options.size, mode.options.toSet().size)
        }
    }

    @Test
    fun eachScreensaverKeepsItsOwnValues() {
        val options = mapOf(
            ScreensaverMode.DVD to mapOf(SaverOption.SPEED to 180, SaverOption.CLOCK to 0),
            ScreensaverMode.MAZE to mapOf(SaverOption.SPEED to 50),
        )
        val dvd = ScreensaverConfig(mode = ScreensaverMode.DVD, options = options)
        assertEquals(1.8f, dvd.speed)
        assertFalse(dvd.showClock)
        val maze = dvd.copy(mode = ScreensaverMode.MAZE)
        assertEquals(0.5f, maze.speed)
        assertTrue("unset falls back to the default", maze.showClock)
        assertEquals(20, ScreensaverConfig(mode = ScreensaverMode.PHOTOS).intervalSeconds)
        assertEquals("Fast", SaverOption.SPEED.label(180))
    }
}
