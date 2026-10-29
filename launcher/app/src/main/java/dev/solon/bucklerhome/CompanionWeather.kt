package dev.solon.bucklerhome

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Shared sky and weather, drawn in pixels inside a box (left, top, right, bottom). Particles are
// placed from their index and the clock alone, so a frame keeps no state and allocates nothing.

private val SunGold = Color(0xFFFFC94A)
private val SunLow = Color(0xFFFF9E5A)
private val Moon = Color(0xFFF1E8C8)
private val MoonCrater = Color(0xFFDCD2B0)
private val Bolt = Color(0xFFFFF3A0)

/** The garden window's sky, top colour then horizon colour, for each part of the day. */
internal fun skyColors(phase: DayPhase): List<Color> = when (phase) {
    DayPhase.NIGHT -> listOf(Color(0xFF0E1630), Color(0xFF243057))
    DayPhase.DAWN -> listOf(Color(0xFF5B6FA8), Color(0xFFF2A97E))
    DayPhase.MORNING -> listOf(Color(0xFF6FB2E8), Color(0xFFCBE8F8))
    DayPhase.MIDDAY -> listOf(Color(0xFF4D9DE8), Color(0xFFA9D6F5))
    DayPhase.AFTERNOON -> listOf(Color(0xFF6AA6DC), Color(0xFFF3D9A4))
    DayPhase.EVENING -> listOf(Color(0xFF2E3566), Color(0xFFE0876A))
}

/**
 * Sun, moon and stars when the sky is clear enough to see them: the sun (low and orange at dawn
 * and evening) by day, the moon and twinkling [star]s by night. The body sits at ([cx], [cy]).
 */
internal fun DrawScope.drawSkyBits(
    t: Float,
    sky: Sky,
    isDay: Boolean,
    phase: DayPhase,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cx: Float,
    cy: Float,
    r: Float,
    star: Color,
) {
    if (sky != Sky.CLEAR && sky != Sky.CLOUDY) return
    if (isDay) {
        val sun = if (phase == DayPhase.DAWN || phase == DayPhase.EVENING) SunLow else SunGold
        drawCircle(sun.copy(alpha = 0.12f + 0.04f * wave(t, 4f)), r * 1.7f, Offset(cx, cy))
        val spin = cycle(t, 60f) * TAU_F
        for (i in 0 until 8) {
            val a = spin + i * TAU_F / 8f
            val c = cos(a)
            val s = sin(a)
            val from = Offset(cx + c * r * 1.3f, cy + s * r * 1.3f)
            drawLine(sun, from, Offset(cx + c * r * 1.6f, cy + s * r * 1.6f), r * 0.12f, StrokeCap.Round)
        }
        drawCircle(sun, r, Offset(cx, cy))
        return
    }
    val stars = (((right - left) / (bottom - top)) * 5f).toInt().coerceIn(5, 28) / (if (sky == Sky.CLOUDY) 2 else 1)
    for (i in 0 until stars) {
        val x = left + hash01(i, 11) * (right - left)
        val y = top + hash01(i, 12) * (bottom - top) * 0.8f
        val glow = 0.45f + 0.4f * wave(t, 3f + i % 3, hash01(i, 13))
        drawTwinkle(x, y, (bottom - top) * 0.018f, star.copy(alpha = glow), (bottom - top) * 0.006f)
    }
    drawCircle(Moon.copy(alpha = 0.12f), r * 1.6f, Offset(cx, cy))
    drawCircle(Moon, r, Offset(cx, cy))
    drawCircle(MoonCrater, r * 0.22f, Offset(cx - r * 0.3f, cy - r * 0.2f))
    drawCircle(MoonCrater, r * 0.14f, Offset(cx + r * 0.35f, cy + r * 0.3f))
}

/** Clouds drifting left to right across the top band, darker as the weather turns. */
internal fun DrawScope.drawClouds(
    t: Float,
    sky: Sky,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    light: Color,
    rain: Color,
    storm: Color,
) {
    val color = when (sky) {
        Sky.CLEAR -> return
        Sky.CLOUDY, Sky.OVERCAST, Sky.SNOW, Sky.FOG -> light
        Sky.RAIN -> rain
        Sky.THUNDER -> storm
    }
    val count = if (sky == Sky.CLOUDY || sky == Sky.FOG) 3 else 4
    val band = bottom - top
    for (i in 0 until count) {
        val cw = band * (0.8f + 0.4f * hash01(i, 21))
        val ch = cw * 0.3f
        val x = left - cw + cycle(t, CLOUD_PERIODS[i], hash01(i, 22)) * (right - left + cw * 2f)
        val y = top + band * (0.3f + 0.4f * hash01(i, 23))
        drawRoundRect(color, Offset(x, y), Size(cw, ch), CornerRadius(ch / 2f))
        drawCircle(color, ch * 0.75f, Offset(x + cw * 0.32f, y + ch * 0.1f))
        drawCircle(color, ch * 0.95f, Offset(x + cw * 0.6f, y))
    }
}

/**
 * Rain, snow, fog and lightning over the box. Drops stop on the shelter's canopy, a line from
 * ([shelterLeft], [shelterTopLeft]) to ([shelterRight], [shelterTopRight]); pass a left past the
 * right for no shelter.
 */
internal fun DrawScope.drawWeather(
    t: Float,
    sky: Sky,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    shelterLeft: Float,
    shelterRight: Float,
    shelterTopLeft: Float,
    shelterTopRight: Float,
    kit: SceneKit,
) {
    val w = right - left
    val h = bottom - top
    when (sky) {
        Sky.CLEAR, Sky.CLOUDY, Sky.OVERCAST -> Unit
        Sky.FOG -> for (i in 0 until 3) {
            val x = left + w * (if (i % 2 == 0) 0.05f else 0.3f) + wave(t, 20f, i * 0.3f) * w * 0.08f
            val y = top + h * (0.3f + 0.22f * i)
            drawRoundRect(kit.fog, Offset(x, y), Size(w * 0.65f, h * 0.08f), CornerRadius(h * 0.04f))
        }
        Sky.SNOW -> {
            val count = ((w / h) * 8f).toInt().coerceIn(8, 70)
            for (i in 0 until count) {
                val radius = h * (0.008f + 0.008f * hash01(i, 31))
                val y = top - radius + cycle(t, 6f + 2f * (i % 3), hash01(i, 32)) * (h + radius * 2f)
                val x = left + hash01(i, 33) * w + wave(t, 4f + i % 3, hash01(i, 34)) * h * 0.03f
                drawCircle(kit.snow, radius, Offset(x, y))
            }
        }
        Sky.RAIN, Sky.THUNDER -> {
            val length = h * 0.07f
            val slant = 0.25f
            val count = ((w / h) * if (sky == Sky.THUNDER) 18f else 14f).toInt().coerceIn(10, 120)
            for (i in 0 until count) {
                val y = top - length + cycle(t, 0.75f + 0.25f * (i % 3), hash01(i, 41)) * (h + length)
                val x = left - h * slant + hash01(i, 42) * (w + h * slant) + (y - top) * slant
                if (x in shelterLeft..shelterRight) {
                    val canopy = mix(shelterTopLeft, shelterTopRight, (x - shelterLeft) / (shelterRight - shelterLeft))
                    if (y + length > canopy) continue
                }
                drawLine(kit.drop, Offset(x, y), Offset(x + length * slant, y + length), h * 0.008f, StrokeCap.Round)
            }
            if (sky == Sky.THUNDER) drawLightning(t, left, top, w, h)
        }
    }
}

/** Every ten seconds a double flicker over the box, with a bolt somewhere new each time. */
private fun DrawScope.drawLightning(t: Float, left: Float, top: Float, w: Float, h: Float) {
    val f = cycle(t, 10f)
    val alpha = when {
        f < 0.02f -> 0.3f
        f in 0.04f..0.06f -> 0.2f
        else -> return
    }
    drawRect(Color.White.copy(alpha = alpha), Offset(left, top), Size(w, h))
    val bx = left + w * (0.35f + 0.5f * hash01(floor(t / 10f).toInt(), 51))
    val stroke = h * 0.014f
    drawLine(Bolt, Offset(bx, top + h * 0.06f), Offset(bx - h * 0.05f, top + h * 0.24f), stroke, StrokeCap.Round)
    drawLine(Bolt, Offset(bx - h * 0.05f, top + h * 0.24f), Offset(bx + h * 0.02f, top + h * 0.24f), stroke, StrokeCap.Round)
    drawLine(Bolt, Offset(bx + h * 0.02f, top + h * 0.24f), Offset(bx - h * 0.04f, top + h * 0.46f), stroke, StrokeCap.Round)
}

// Each cloud drifts at its own pace, so they pass one another; every period divides LOOP_SECONDS.
private val CLOUD_PERIODS = floatArrayOf(120f, 150f, 200f, 300f)
private const val TAU_F = (2 * PI).toFloat()
