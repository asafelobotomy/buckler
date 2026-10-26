package dev.solon.bucklerhome

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

/**
 * A perfect maze on a (2n+1)² grid where `true` is wall: cells sit on odd coordinates and the
 * walls between them are knocked out by an iterative depth-first search. Start is the top-left
 * cell, the goal the bottom-right one.
 */
class MazeGrid(val size: Int, private val walls: BooleanArray) {
    val goalX: Int get() = size - 2
    val goalY: Int get() = size - 2

    fun isWall(x: Int, y: Int): Boolean = x !in 0 until size || y !in 0 until size || walls[y * size + x]

    companion object {
        /** East, south, west, north in screen coordinates (y grows downwards); index + 1 turns right. */
        val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(0, -1))

        fun generate(cells: Int, random: Random = Random.Default): MazeGrid {
            val size = cells * 2 + 1
            val walls = BooleanArray(size * size) { true }
            val visited = BooleanArray(cells * cells)
            val stack = ArrayDeque<Int>()
            fun open(x: Int, y: Int) {
                walls[y * size + x] = false
            }
            visited[0] = true
            open(1, 1)
            stack.addLast(0)
            while (stack.isNotEmpty()) {
                val cell = stack.last()
                val cx = cell % cells
                val cy = cell / cells
                val options = DIRS.filter { (dx, dy) ->
                    val nx = cx + dx
                    val ny = cy + dy
                    nx in 0 until cells && ny in 0 until cells && !visited[ny * cells + nx]
                }
                if (options.isEmpty()) {
                    stack.removeLast()
                    continue
                }
                val (dx, dy) = options[random.nextInt(options.size)]
                val nx = cx + dx
                val ny = cy + dy
                open(2 * cx + 1 + dx, 2 * cy + 1 + dy)
                open(2 * nx + 1, 2 * ny + 1)
                visited[ny * cells + nx] = true
                stack.addLast(ny * cells + nx)
            }
            return MazeGrid(size, walls)
        }
    }
}

/**
 * Walks a maze the way the old Windows screensaver did: keep a hand on the right-hand wall until
 * the goal. Each step is animated, so [x], [y] and [angle] glide between grid cells.
 */
class MazeWalker(private val maze: MazeGrid) {
    private sealed interface Step {
        data class Turn(val by: Int) : Step
        data object Forward : Step
    }

    private var cellX = 1
    private var cellY = 1
    private var dir = if (maze.isWall(2, 1)) 1 else 0
    private val queue = ArrayDeque<Step>()
    private var current: Step? = null
    private var progress = 0f

    var x = cellX + 0.5
        private set
    var y = cellY + 0.5
        private set
    var angle = dir * PI / 2
        private set
    var finished = false
        private set

    /** Grid cells visited, for tests. */
    var moves = 0
        private set

    fun update(dt: Float) {
        if (finished) return
        var remaining = dt
        while (remaining > 0f && !finished) {
            val step = current ?: next() ?: return
            current = step
            val duration = if (step is Step.Turn) TURN_SECONDS * abs(step.by) else MOVE_SECONDS
            val used = minOf(remaining, (1f - progress) * duration)
            progress += used / duration
            remaining -= used
            apply(step, progress.coerceAtMost(1f))
            if (progress >= 1f) complete(step)
        }
    }

    private fun next(): Step? {
        if (queue.isEmpty()) plan()
        return queue.removeFirstOrNull()
    }

    private fun open(direction: Int): Boolean {
        val (dx, dy) = MazeGrid.DIRS[Math.floorMod(direction, 4)]
        return !maze.isWall(cellX + dx, cellY + dy)
    }

    private fun plan() {
        if (cellX == maze.goalX && cellY == maze.goalY) {
            finished = true
            return
        }
        when {
            open(dir + 1) -> queue.addAll(listOf(Step.Turn(1), Step.Forward))
            open(dir) -> queue.add(Step.Forward)
            open(dir - 1) -> queue.addAll(listOf(Step.Turn(-1), Step.Forward))
            else -> queue.addAll(listOf(Step.Turn(2), Step.Forward))
        }
    }

    private fun apply(step: Step, t: Float) {
        val eased = (0.5 - 0.5 * cos(t * PI))
        when (step) {
            is Step.Turn -> angle = (dir + step.by * eased) * PI / 2
            Step.Forward -> {
                val (dx, dy) = MazeGrid.DIRS[dir]
                x = cellX + 0.5 + dx * eased
                y = cellY + 0.5 + dy * eased
            }
        }
    }

    private fun complete(step: Step) {
        when (step) {
            is Step.Turn -> dir = Math.floorMod(dir + step.by, 4)
            Step.Forward -> {
                val (dx, dy) = MazeGrid.DIRS[dir]
                cellX += dx
                cellY += dy
                moves++
            }
        }
        angle = dir * PI / 2
        x = cellX + 0.5
        y = cellY + 0.5
        current = null
        progress = 0f
    }

    private companion object {
        const val MOVE_SECONDS = 0.42f
        const val TURN_SECONDS = 0.38f
    }
}
