package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A flat picture standing in the maze at ([x], [y]), always facing the walker as it spins. */
class MazeSprite(val x: Double, val y: Double, val texture: IntArray, val size: Int, val scale: Double, val kind: Kind) {
    enum class Kind { LOGO, GEM }

    /** Turned around its upright axis (radians), for the spin. */
    var spin = 0.0

    /** Collected gems vanish. */
    var gone = false
}

/**
 * The 3D Maze's surprises, worked out per maze: where the gems lie on the route (walking through
 * one rolls the view upside down, the next rolls it back), which wall carries the spray-painted
 * Buckler mark, and whether this maze is a rare night one. Pure apart from the textures.
 */
object MazeEggs {
    const val SPRITE_PX = 128

    /** The open cells from start to goal, in order: the walker's right hand reaches the goal through all of them. */
    fun route(maze: MazeGrid): List<Pair<Int, Int>> {
        val size = maze.size
        val from = IntArray(size * size) { -1 }
        val start = 1 * size + 1
        val goal = maze.goalY * size + maze.goalX
        val queue = ArrayDeque(listOf(start))
        from[start] = start
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            if (cell == goal) break
            for ((dx, dy) in MazeGrid.DIRS) {
                val nx = cell % size + dx
                val ny = cell / size + dy
                if (maze.isWall(nx, ny) || from[ny * size + nx] >= 0) continue
                from[ny * size + nx] = cell
                queue.addLast(ny * size + nx)
            }
        }
        if (from[goal] < 0) return emptyList()
        val path = ArrayList<Pair<Int, Int>>()
        var cell = goal
        while (cell != start) {
            path += (cell % size) to (cell / size)
            cell = from[cell]
        }
        path += 1 to 1
        return path.reversed()
    }

    /** [count] gem cells spread along the middle of [route], never the start or the goal. */
    fun gemCells(route: List<Pair<Int, Int>>, count: Int, random: Random): List<Pair<Int, Int>> {
        if (route.size < 6) return emptyList()
        return (1..count).map { i ->
            val centre = route.size * i / (count + 1)
            val jitter = random.nextInt(-route.size / 10, route.size / 10 + 1)
            route[(centre + jitter).coerceIn(2, route.size - 3)]
        }.distinct()
    }

    /** A wall beside the middle of [route], for the graffiti; null in a maze too small to have one. */
    fun graffitiWall(maze: MazeGrid, route: List<Pair<Int, Int>>, random: Random): Pair<Int, Int>? {
        val middle = route.drop(route.size / 3).take(maxOf(1, route.size / 3))
        return middle.shuffled(random).firstNotNullOfOrNull { (x, y) ->
            MazeGrid.DIRS.map { (dx, dy) -> (x + dx) to (y + dy) }
                .filter { (wx, wy) -> wx in 1 until maze.size - 1 && wy in 1 until maze.size - 1 && maze.isWall(wx, wy) }
                .randomOrNull(random)
        }
    }

    /** About one maze in six is walked at night, under the stars. */
    fun isNight(random: Random): Boolean = random.nextInt(6) == 0

    /**
     * How much to enlarge the frame while it is rolled by [roll] radians, so its corners never show:
     * the rolled screen's height must still span the frame's, |cos| + aspect·|sin| (16/9 at a
     * quarter turn, about 1.96 at an eighth).
     */
    fun coverScale(roll: Double, aspect: Double = 16.0 / 9.0): Float = (abs(cos(roll)) + aspect * abs(sin(roll))).toFloat()

    /** [theme]'s Buckler mark drawn [SPRITE_PX] square, transparent around it. */
    fun markTexture(context: Context, theme: AppTheme): IntArray {
        val bitmap = Bitmap.createBitmap(SPRITE_PX, SPRITE_PX, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, theme.markRes)?.apply {
            setBounds(0, 0, SPRITE_PX, SPRITE_PX)
            draw(Canvas(bitmap))
        }
        return IntArray(SPRITE_PX * SPRITE_PX).also { bitmap.getPixels(it, 0, SPRITE_PX, 0, 0, SPRITE_PX, SPRITE_PX); bitmap.recycle() }
    }

    /** A cut gem in [colour]: a diamond of four facets, each lit differently, the way it catches light as it turns. */
    fun gemTexture(colour: Int): IntArray {
        val out = IntArray(SPRITE_PX * SPRITE_PX)
        val c = SPRITE_PX / 2f
        val r = SPRITE_PX * 0.42f
        val facets = floatArrayOf(1.25f, 0.95f, 0.7f, 0.5f)
        for (y in 0 until SPRITE_PX) for (x in 0 until SPRITE_PX) {
            val dx = x - c
            val dy = (y - c) * 0.8f
            val reach = abs(dx) + abs(dy)
            if (reach > r) continue
            val facet = (if (dx < 0) 0 else 1) + (if (dy < 0) 0 else 2)
            val edge = r - reach < 2.5f || abs(dx) < 1f
            out[y * SPRITE_PX + x] = if (edge) 0xFFFFFFFF.toInt() else lit(colour, facets[facet])
        }
        return out
    }

    /** [wall] with the Buckler mark sprayed on it in [colour]: a soft-edged disc and ring, as if from a can. */
    fun graffiti(wall: IntArray, colour: Int, size: Int, random: Random): IntArray {
        val out = wall.copyOf()
        val c = size / 2f
        val outer = size * 0.34f
        val inner = size * 0.1f
        val ringW = size * 0.035f
        for (y in 0 until size) for (x in 0 until size) {
            val d = kotlin.math.hypot(x - c, y - c)
            // Over-spray: the disc's edge is speckled rather than clean.
            val disc = d < outer - 1.5f || (d < outer + 2.5f && random.nextFloat() < 0.45f)
            val ring = abs(d - inner) < ringW
            if (disc && !ring) out[y * size + x] = blend(wall[y * size + x], colour, 0.85f)
            if (ring) out[y * size + x] = blend(wall[y * size + x], 0xFFF4F0E6.toInt(), 0.9f)
        }
        return out
    }

    /** One stock colour scheme after another, each maze its own, until all have had a turn. */
    class SchemeCycle(private val random: Random) {
        private val queue = ArrayDeque<AppTheme>()
        private var last: AppTheme? = null

        fun next(): AppTheme {
            if (queue.isEmpty()) queue.addAll(AppTheme.entries.shuffled(random).let { order -> if (order.first() == last) order.drop(1) + order.first() else order })
            return queue.removeFirst().also { last = it }
        }
    }

    /** [theme]'s accent, for the gems and the graffiti. */
    fun accent(theme: AppTheme): Int = Palettes.of(theme).brand.toArgb()

    private fun lit(argb: Int, light: Float): Int {
        fun ch(shift: Int) = ((argb shr shift and 0xFF) * light).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun blend(under: Int, over: Int, amount: Float): Int {
        fun ch(shift: Int) = ((under shr shift and 0xFF) * (1 - amount) + (over shr shift and 0xFF) * amount).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
