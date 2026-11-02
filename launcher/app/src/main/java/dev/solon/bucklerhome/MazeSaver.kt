package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private const val RENDER_W = 640
private const val RENDER_H = 360
private const val TEX = 128
private const val MAZE_CELLS = 9
private const val GEMS = 2
private const val ROLL_SECONDS = 1.1

/**
 * A first-person walk through a brick maze, after the Windows 95 "3D Maze" screensaver. A small
 * raycaster draws a 640×360 frame (a clean 3× on a 1080p TV, scaled without filtering so edges
 * stay sharp). Wall textures are generated here, so no third-party artwork is bundled.
 *
 * At the end of every maze the Buckler mark spins and bobs, in a different stock colour scheme
 * each time. On the way: gems that roll the view upside down (the next rolls it back), the mark
 * spray-painted on one wall, and now and then a night maze under the stars ([MazeEggs]).
 */
/** [cells] across, [walls] 0 = a different texture per maze, 1 brick, 2 stone, 3 wood; [speed] 1 = normal. */
class MazeScene(
    context: Context,
    private val cells: Int = MAZE_CELLS,
    private val walls: Int = 0,
    private val speed: Float = 1f,
    private val reduceMotion: Boolean = false,
) : View(context) {
    private val random = Random(System.nanoTime())
    private val frame = Bitmap.createBitmap(RENDER_W, RENDER_H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(RENDER_W * RENDER_H)
    private val zBuffer = DoubleArray(RENDER_W)
    private val textures = listOf(MazeTextures.brick(random), MazeTextures.stone(random), MazeTextures.wood(random))
    private val schemes = MazeEggs.SchemeCycle(random)
    private val ceiling = IntArray(RENDER_H) { shade(0xFF2B3A55.toInt(), 0.35f + 0.65f * (1f - it / (RENDER_H / 2f)).coerceIn(0f, 1f)) }
    private val floorRows = IntArray(RENDER_H) { shade(0xFF6B6355.toInt(), (((it - RENDER_H / 2f) / (RENDER_H / 2f)) * 1.1f).coerceIn(0.12f, 1f)) }
    private val nightSky = IntArray(RENDER_W * RENDER_H / 2)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()
    private var lastNanos = 0L
    private var time = 0.0

    // Per maze: see [newMaze].
    private lateinit var maze: MazeGrid
    private lateinit var walker: MazeWalker
    private lateinit var texture: IntArray
    private var graffiti: IntArray? = null
    private var graffitiAt: Pair<Int, Int>? = null
    private var night = false
    private var sprites: List<MazeSprite> = emptyList()

    // The view's roll, from gems: 0 upright, PI upside down, eased between over ROLL_SECONDS.
    private var rollFrom = 0.0
    private var rollTo = 0.0
    private var rollT = 1.0

    init {
        newMaze()
    }

    private fun pickTexture(): IntArray = if (walls in 1..textures.size) textures[walls - 1] else textures[random.nextInt(textures.size)]

    private fun newMaze() {
        maze = MazeGrid.generate(cells, random)
        walker = MazeWalker(maze)
        texture = pickTexture()
        val scheme = schemes.next()
        val accent = MazeEggs.accent(scheme)
        val route = MazeEggs.route(maze)
        graffitiAt = MazeEggs.graffitiWall(maze, route, random)
        graffiti = graffitiAt?.let { MazeEggs.graffiti(texture, accent, TEX, random) }
        night = MazeEggs.isNight(random)
        if (night) paintNightSky()
        val gem = MazeEggs.gemTexture(accent)
        sprites = listOf(
            MazeSprite(maze.goalX + 0.5, maze.goalY + 0.5, MazeEggs.markTexture(context, scheme), MazeEggs.SPRITE_PX, 0.62, MazeSprite.Kind.LOGO),
        ) + MazeEggs.gemCells(route, GEMS, random).map { (x, y) ->
            MazeSprite(x + 0.5, y + 0.5, gem, MazeEggs.SPRITE_PX, 0.28, MazeSprite.Kind.GEM)
        }
        // Each maze starts the right way up.
        rollFrom = 0.0
        rollTo = 0.0
        rollT = 1.0
    }

    private fun paintNightSky() {
        val half = RENDER_H / 2
        for (y in 0 until half) {
            val sky = shade(0xFF0B1230.toInt(), 0.45f + 0.55f * (1f - y / half.toFloat()))
            for (x in 0 until RENDER_W) nightSky[y * RENDER_W + x] = sky
        }
        repeat(RENDER_W * half / 180) {
            val i = random.nextInt(nightSky.size)
            nightSky[i] = shade(0xFFFFF6E0.toInt(), 0.5f + random.nextFloat() * 0.5f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now
        time += dt * speed
        walker.update(dt * speed)
        if (walker.finished) newMaze()
        collectGems()
        if (rollT < 1.0) rollT = (rollT + dt * speed / ROLL_SECONDS).coerceAtMost(1.0)
        sprites.forEach { it.spin = time * if (it.kind == MazeSprite.Kind.LOGO) 1.6 else 2.4 }
        render()
        frame.setPixels(pixels, 0, RENDER_W, 0, 0, RENDER_W, RENDER_H)
        dst.set(0, 0, width, height)
        val roll = rollFrom + (rollTo - rollFrom) * (0.5 - 0.5 * cos(rollT * PI))
        canvas.save()
        if (roll != 0.0) {
            val cover = MazeEggs.coverScale(roll)
            canvas.rotate(Math.toDegrees(roll).toFloat(), width / 2f, height / 2f)
            canvas.scale(cover, cover, width / 2f, height / 2f)
        }
        canvas.drawBitmap(frame, null, dst, paint)
        canvas.restore()
        postInvalidateOnAnimation()
    }

    /** Walking through a gem takes it and rolls the view over (unless motion is reduced). */
    private fun collectGems() {
        for (s in sprites) {
            if (s.kind != MazeSprite.Kind.GEM || s.gone) continue
            if (hypot(s.x - walker.x, s.y - walker.y) > 0.35) continue
            s.gone = true
            if (reduceMotion) continue
            rollFrom = rollTo
            rollTo = if (rollTo == 0.0) PI else 0.0
            rollT = 0.0
        }
    }

    private fun render() {
        val px = walker.x
        val py = walker.y
        val dirX = cos(walker.angle)
        val dirY = sin(walker.angle)
        val planeX = -dirY * 0.66
        val planeY = dirX * 0.66
        val dim = if (night) 0.62 else 1.0
        val falloff = if (night) 0.6 else 0.35
        val (gx, gy) = graffitiAt ?: (-1 to -1)
        for (col in 0 until RENDER_W) {
            val camX = 2.0 * col / RENDER_W - 1
            val rayX = dirX + planeX * camX
            val rayY = dirY + planeY * camX
            var mapX = floor(px).toInt()
            var mapY = floor(py).toInt()
            val deltaX = if (rayX == 0.0) 1e30 else abs(1 / rayX)
            val deltaY = if (rayY == 0.0) 1e30 else abs(1 / rayY)
            val stepX = if (rayX < 0) -1 else 1
            val stepY = if (rayY < 0) -1 else 1
            var sideX = if (rayX < 0) (px - mapX) * deltaX else (mapX + 1 - px) * deltaX
            var sideY = if (rayY < 0) (py - mapY) * deltaY else (mapY + 1 - py) * deltaY
            var side = 0
            var guard = 0
            while (guard++ < 64) {
                if (sideX < sideY) {
                    sideX += deltaX
                    mapX += stepX
                    side = 0
                } else {
                    sideY += deltaY
                    mapY += stepY
                    side = 1
                }
                if (maze.isWall(mapX, mapY)) break
            }
            val dist = (if (side == 0) sideX - deltaX else sideY - deltaY).coerceAtLeast(0.05)
            zBuffer[col] = dist
            val lineH = (RENDER_H / dist).toInt()
            val top = (RENDER_H - lineH) / 2
            val bottom = top + lineH
            var wallX = if (side == 0) py + dist * rayY else px + dist * rayX
            wallX -= floor(wallX)
            var texX = (wallX * TEX).toInt().coerceIn(0, TEX - 1)
            if ((side == 0 && rayX > 0) || (side == 1 && rayY < 0)) texX = TEX - texX - 1
            var light = ((if (side == 1) 0.72 else 1.0) * (1.6 / (1 + dist * falloff)) * dim).coerceIn(0.1, 1.0).toFloat()
            // A thin dark line where the wall really has an edge keeps the corners crisp; along a
            // straight wall, cell after cell, there is none (it would read as a seam).
            if (wallX < 0.012 || wallX > 0.988) {
                val fromX = if (side == 0) mapX - stepX else mapX
                val fromY = if (side == 1) mapY - stepY else mapY
                if (maze.faceEdge(mapX, mapY, fromX, fromY, if (wallX < 0.5) -1 else 1)) light *= 0.45f
            }
            val wall = if (mapX == gx && mapY == gy) graffiti ?: texture else texture
            val step = TEX.toDouble() / lineH
            var texPos = (maxOf(top, 0) - top) * step
            for (row in 0 until RENDER_H) {
                pixels[row * RENDER_W + col] = when {
                    row < top -> if (night) nightSky[row * RENDER_W + col] else ceiling[row]
                    row >= bottom -> if (night) shade(floorRows[row], 0.55f) else floorRows[row]
                    else -> {
                        val texY = texPos.toInt() and (TEX - 1)
                        texPos += step
                        shade(wall[texY * TEX + texX], light)
                    }
                }
            }
        }
        drawSprites(px, py, dirX, dirY, planeX, planeY, dim, falloff)
    }

    /** Each sprite as a billboard, far to near, hidden where a wall stands nearer ([zBuffer]). */
    private fun drawSprites(px: Double, py: Double, dirX: Double, dirY: Double, planeX: Double, planeY: Double, dim: Double, falloff: Double) {
        val invDet = 1.0 / (planeX * dirY - dirX * planeY)
        val visible = sprites.filterNot { it.gone }.sortedByDescending { (it.x - px) * (it.x - px) + (it.y - py) * (it.y - py) }
        for (s in visible) {
            val sx = s.x - px
            val sy = s.y - py
            val depth = invDet * (-planeY * sx + planeX * sy)
            if (depth <= 0.15) continue
            val across = invDet * (dirY * sx - dirX * sy)
            val centreX = (RENDER_W / 2 * (1 + across / depth)).toInt()
            val h = (RENDER_H / depth * s.scale).toInt().coerceAtLeast(1)
            val facing = cos(s.spin)
            val w = (h * abs(facing)).toInt().coerceAtLeast(1)
            val bob = (sin(time * 2.1 + s.x * 3) * RENDER_H * 0.05 / depth).toInt()
            val top = RENDER_H / 2 - h / 2 + bob
            val left = centreX - w / 2
            // The spin shows the edge as a darker side, the face full-lit.
            val light = ((1.6 / (1 + depth * falloff)) * dim * (0.55 + 0.45 * abs(facing))).coerceIn(0.2, 1.0).toFloat()
            for (col in maxOf(left, 0) until minOf(left + w, RENDER_W)) {
                if (depth >= zBuffer[col]) continue
                var u = (col - left) / w.toDouble()
                if (facing < 0) u = 1 - u
                val texX = (u * s.size).toInt().coerceIn(0, s.size - 1)
                for (row in maxOf(top, 0) until minOf(top + h, RENDER_H)) {
                    val texY = ((row - top) * s.size / h).coerceIn(0, s.size - 1)
                    val argb = s.texture[texY * s.size + texX]
                    if (argb ushr 24 < 128) continue
                    pixels[row * RENDER_W + col] = shade(argb, light)
                }
            }
        }
    }
}

private fun shade(argb: Int, light: Float): Int {
    val r = ((argb shr 16 and 0xFF) * light).toInt()
    val g = ((argb shr 8 and 0xFF) * light).toInt()
    val b = ((argb and 0xFF) * light).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** 128×128 procedural wall textures (packed ARGB). */
object MazeTextures {
    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun brick(random: Random): IntArray = blocks(random, blockW = 64, blockH = 32, base = intArrayOf(150, 58, 40), mortar = rgb(150, 146, 132))

    fun stone(random: Random): IntArray = blocks(random, blockW = 64, blockH = 64, base = intArrayOf(112, 116, 124), mortar = rgb(54, 56, 62))

    fun wood(random: Random): IntArray {
        val out = IntArray(TEX * TEX)
        for (y in 0 until TEX) {
            for (x in 0 until TEX) {
                val plank = x / 32
                val seam = x % 32 < 2
                val grain = (sin((y / 2 + plank * 13) * 0.45 + sin(x * 0.35) * 1.8) * 14).toInt()
                val n = random.nextInt(-6, 7)
                out[y * TEX + x] = if (seam) rgb(58, 36, 20) else rgb(132 + grain + n + plank * 4, 86 + grain / 2 + n, 46 + n)
            }
        }
        return out
    }

    private fun blocks(random: Random, blockW: Int, blockH: Int, base: IntArray, mortar: Int): IntArray {
        val out = IntArray(TEX * TEX)
        val tints = IntArray(64) { random.nextInt(-18, 19) }
        for (y in 0 until TEX) {
            val row = y / blockH
            val offset = if (row % 2 == 1) blockW / 2 else 0
            for (x in 0 until TEX) {
                val bx = (x + offset) % TEX
                val isMortar = y % blockH < 3 || bx % blockW < 3
                out[y * TEX + x] = if (isMortar) {
                    mortar
                } else {
                    val t = tints[(row * 7 + bx / blockW) % tints.size] + random.nextInt(-9, 10)
                    rgb(base[0] + t, base[1] + t / 2, base[2] + t / 2)
                }
            }
        }
        return out
    }
}
