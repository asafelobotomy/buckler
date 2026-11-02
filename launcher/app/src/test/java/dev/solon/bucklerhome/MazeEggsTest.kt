package dev.solon.bucklerhome

import kotlin.math.PI
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MazeEggsTest {
    @Test
    fun theRouteRunsFromStartToGoalThroughOpenCells() {
        repeat(10) { seed ->
            val maze = MazeGrid.generate(9, Random(seed))
            val route = MazeEggs.route(maze)
            assertEquals(1 to 1, route.first())
            assertEquals(maze.goalX to maze.goalY, route.last())
            route.zipWithNext().forEach { (a, b) ->
                assertFalse(maze.isWall(b.first, b.second))
                assertEquals("one step at a time", 1, kotlin.math.abs(a.first - b.first) + kotlin.math.abs(a.second - b.second))
            }
        }
    }

    @Test
    fun theWalkerPassesEveryGem() {
        repeat(10) { seed ->
            val random = Random(seed)
            val maze = MazeGrid.generate(9, random)
            val route = MazeEggs.route(maze)
            val gems = MazeEggs.gemCells(route, 2, random)
            assertTrue(gems.isNotEmpty())
            assertTrue("never on the start or the goal", gems.none { it == route.first() || it == route.last() })
            // The right-hand walker reaches the goal through every cell of the route, so through each gem.
            val visited = HashSet<Pair<Int, Int>>()
            val walker = MazeWalker(maze)
            var guard = 0
            while (!walker.finished && guard++ < 100_000) {
                walker.update(0.05f)
                visited += walker.x.toInt() to walker.y.toInt()
            }
            assertTrue("seed $seed", gems.all { it in visited })
        }
    }

    @Test
    fun theGraffitiIsOnAnInnerWallBesideTheRoute() {
        val random = Random(3)
        val maze = MazeGrid.generate(9, random)
        val route = MazeEggs.route(maze)
        val wall = MazeEggs.graffitiWall(maze, route, random)
        assertNotNull(wall)
        val (x, y) = wall!!
        assertTrue(maze.isWall(x, y))
        assertTrue(x in 1 until maze.size - 1 && y in 1 until maze.size - 1)
        assertTrue(route.any { (rx, ry) -> kotlin.math.abs(rx - x) + kotlin.math.abs(ry - y) == 1 })
    }

    @Test
    fun everyStockSchemeHasATurnBeforeAnyRepeats() {
        val cycle = MazeEggs.SchemeCycle(Random(7))
        val first = List(AppTheme.entries.size) { cycle.next() }
        assertEquals(AppTheme.entries.toSet(), first.toSet())
        // Into the next round, never the same colour twice running.
        var last = first.last()
        repeat(AppTheme.entries.size * 3) {
            val next = cycle.next()
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun aRolledFrameIsEnlargedToCoverTheScreen() {
        assertEquals(1f, MazeEggs.coverScale(0.0), 1e-6f)
        assertEquals(1f, MazeEggs.coverScale(PI), 1e-5f)
        assertEquals(16f / 9f, MazeEggs.coverScale(PI / 2), 1e-5f)
        // Part-way round the corners reach furthest: every corner of the screen must stay inside the frame.
        for (step in 1 until 32) {
            val roll = PI * step / 32
            val s = MazeEggs.coverScale(roll)
            for ((cx, cy) in listOf(16.0 to 9.0, 16.0 to -9.0)) {
                // The screen's corner, seen in the rolled and scaled frame's own axes.
                val x = (cx * kotlin.math.cos(roll) + cy * kotlin.math.sin(roll)) / s
                val y = (-cx * kotlin.math.sin(roll) + cy * kotlin.math.cos(roll)) / s
                assertTrue("roll $roll", kotlin.math.abs(x) <= 16 + 1e-6 && kotlin.math.abs(y) <= 9 + 1e-6)
            }
        }
    }

    @Test
    fun aGemIsSolidInsideAndClearOutside() {
        val gem = MazeEggs.gemTexture(0xFF336699.toInt())
        val px = MazeEggs.SPRITE_PX
        assertEquals(0, gem[0] ushr 24)
        assertEquals(0xFF, gem[(px / 2) * px + px / 2 + 10] ushr 24)
    }

    @Test
    fun onlyRealCornersGetAnEdgeLine() {
        // A 5×5 grid, walls everywhere but an L-shaped passage: (1,1) (2,1) (3,1) then down (3,2) (3,3).
        val open = setOf(1 to 1, 2 to 1, 3 to 1, 3 to 2, 3 to 3)
        val maze = MazeGrid(5, BooleanArray(25) { (it % 5 to it / 5) !in open })
        // Standing at (2,1) looking at the north wall (2,0): it runs straight on both ways.
        assertFalse(maze.faceEdge(2, 0, 2, 1, -1))
        assertFalse(maze.faceEdge(2, 0, 2, 1, 1))
        // The south wall (2,2) seen from (2,1): to the east the passage turns down, so the wall ends.
        assertTrue(maze.faceEdge(2, 2, 2, 1, 1))
        // The east wall (4,1) seen from (3,1): to the north the wall (4,0) runs on, but the north wall
        // (3,0) meets it there: an inner corner.
        assertTrue(maze.faceEdge(4, 1, 3, 1, -1))
    }
}
