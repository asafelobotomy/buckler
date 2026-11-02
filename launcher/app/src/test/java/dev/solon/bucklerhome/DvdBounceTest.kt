package dev.solon.bucklerhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvdBounceTest {
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
}
