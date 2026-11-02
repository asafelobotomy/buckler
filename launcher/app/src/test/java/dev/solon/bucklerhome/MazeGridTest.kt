package dev.solon.bucklerhome

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MazeGridTest {
    @Test
    fun mazeIsPerfectAndWalled() {
        val maze = MazeGrid.generate(9, Random(42))
        assertEquals(19, maze.size)
        var open = 0
        for (y in 0 until maze.size) {
            for (x in 0 until maze.size) {
                if (x == 0 || y == 0 || x == maze.size - 1 || y == maze.size - 1) assertTrue(maze.isWall(x, y))
                if (!maze.isWall(x, y)) open++
            }
        }
        // A spanning tree over 81 cells: 81 cells plus 80 knocked-out walls, so no loops.
        assertEquals(81 + 80, open)
        assertFalse(maze.isWall(maze.goalX, maze.goalY))
        assertTrue(maze.isWall(-1, 5))
    }

    @Test
    fun wallFollowerAlwaysReachesTheGoal() {
        repeat(20) { seed ->
            val maze = MazeGrid.generate(9, Random(seed))
            val walker = MazeWalker(maze)
            var guard = 0
            while (!walker.finished && guard++ < 100_000) walker.update(0.05f)
            assertTrue("seed $seed", walker.finished)
            assertEquals(maze.goalX + 0.5, walker.x, 1e-9)
            assertEquals(maze.goalY + 0.5, walker.y, 1e-9)
        }
    }
}
