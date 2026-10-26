package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

private const val RENDER_W = 320
private const val RENDER_H = 180
private const val TEX = 64
private const val MAZE_CELLS = 9

/**
 * A first-person walk through a brick maze, after the Windows 95 "3D Maze" screensaver. A small
 * raycaster draws into a 320×180 frame that is scaled up without filtering for the period look.
 * Wall textures are generated here, so no third-party artwork is bundled.
 */
class MazeScene(context: Context) : View(context) {
    private val random = Random(System.nanoTime())
    private val frame = Bitmap.createBitmap(RENDER_W, RENDER_H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(RENDER_W * RENDER_H)
    private val textures = listOf(MazeTextures.brick(random), MazeTextures.stone(random), MazeTextures.wood(random))
    private var texture = textures[0]
    private var maze = MazeGrid.generate(MAZE_CELLS, random)
    private var walker = MazeWalker(maze)
    private val ceiling = IntArray(RENDER_H) { shade(0xFF2B3A55.toInt(), 0.35f + 0.65f * (1f - it / (RENDER_H / 2f)).coerceIn(0f, 1f)) }
    private val floorRows = IntArray(RENDER_H) { shade(0xFF6B6355.toInt(), (((it - RENDER_H / 2f) / (RENDER_H / 2f)) * 1.1f).coerceIn(0.12f, 1f)) }
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()
    private var lastNanos = 0L

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now
        walker.update(dt)
        if (walker.finished) {
            maze = MazeGrid.generate(MAZE_CELLS, random)
            walker = MazeWalker(maze)
            texture = textures[random.nextInt(textures.size)]
        }
        render()
        frame.setPixels(pixels, 0, RENDER_W, 0, 0, RENDER_W, RENDER_H)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(frame, null, dst, paint)
        postInvalidateOnAnimation()
    }

    private fun render() {
        val px = walker.x
        val py = walker.y
        val dirX = cos(walker.angle)
        val dirY = sin(walker.angle)
        val planeX = -dirY * 0.66
        val planeY = dirX * 0.66
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
            val lineH = (RENDER_H / dist).toInt()
            val top = (RENDER_H - lineH) / 2
            val bottom = top + lineH
            var wallX = if (side == 0) py + dist * rayY else px + dist * rayX
            wallX -= floor(wallX)
            var texX = (wallX * TEX).toInt().coerceIn(0, TEX - 1)
            if ((side == 0 && rayX > 0) || (side == 1 && rayY < 0)) texX = TEX - texX - 1
            val light = ((if (side == 1) 0.72 else 1.0) * (1.6 / (1 + dist * 0.35))).coerceIn(0.15, 1.0).toFloat()
            val step = TEX.toDouble() / lineH
            var texPos = (maxOf(top, 0) - top) * step
            for (row in 0 until RENDER_H) {
                pixels[row * RENDER_W + col] = when {
                    row < top -> ceiling[row]
                    row >= bottom -> floorRows[row]
                    else -> {
                        val texY = texPos.toInt() and (TEX - 1)
                        texPos += step
                        shade(texture[texY * TEX + texX], light)
                    }
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

/** 64×64 procedural wall textures (packed ARGB). */
object MazeTextures {
    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun brick(random: Random): IntArray = blocks(random, blockW = 32, blockH = 16, base = intArrayOf(150, 58, 40), mortar = rgb(150, 146, 132))

    fun stone(random: Random): IntArray = blocks(random, blockW = 32, blockH = 32, base = intArrayOf(112, 116, 124), mortar = rgb(54, 56, 62))

    fun wood(random: Random): IntArray {
        val out = IntArray(TEX * TEX)
        for (y in 0 until TEX) {
            for (x in 0 until TEX) {
                val plank = x / 16
                val seam = x % 16 == 0
                val grain = (sin((y + plank * 13) * 0.45 + sin(x * 0.7) * 1.8) * 14).toInt()
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
                val isMortar = y % blockH < 2 || bx % blockW < 2
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
