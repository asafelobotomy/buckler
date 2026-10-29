package dev.solon.bucklerhome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.min

// No character: a window onto the sky with a potted plant on the sill. The sky follows the time
// of day, the weather happens behind the glass, and the flower opens by day and closes at night.

private val Pot = Color(0xFFC8693F)
private val PotRim = Color(0xFFB25A34)
private val Soil = Color(0xFF5A3E2B)
private val StemGreen = Color(0xFF4E9A4B)
private val LeafGreen = Color(0xFF5FA85A)
private val FlowerHeart = Color(0xFF6B4A2A)
private val HillDay = Color(0xFF4F8A5A)
private val HillNight = Color(0xFF1E3326)
private val HillSnow = Color(0xFFE3EBF3)
private val Overcast = Color(0xFF7A8290)
private val StarPale = Color(0xFFFFF4D6)
private val CloudWhite = Color(0xFFF2F5FA)
private val CloudGrey = Color(0xFFAEB6C2)
private val CloudStorm = Color(0xFF6E7585)

/** The garden window for [phase] and [sky], sized from the box height and kept to the left. */
internal fun DrawScope.drawGarden(t: Float, phase: DayPhase, sky: Sky, isDay: Boolean, kit: SceneKit) {
    val w = size.width
    val h = size.height
    val wh = h * 0.74f
    val top = h * 0.06f
    val ww = min(w * 0.9f, wh * 3f)
    val left = min((w - ww) / 2f, h * 0.3f)
    val right = left + ww
    val bottom = top + wh

    clipRect(left, top, right, bottom) {
        drawRect(kit.skyBrush, Offset(left, top), Size(ww, wh))
        val overcast = when (sky) {
            Sky.CLEAR -> 0f
            Sky.CLOUDY -> 0.15f
            Sky.OVERCAST -> 0.3f
            Sky.SNOW, Sky.FOG -> 0.3f
            Sky.RAIN -> 0.4f
            Sky.THUNDER -> 0.55f
        }
        if (overcast > 0f) drawRect(Overcast.copy(alpha = overcast), Offset(left, top), Size(ww, wh))
        drawSkyBits(
            t, sky, isDay, phase, left, top, right, top + wh * 0.6f,
            left + ww * 0.72f, top + wh * 0.3f, wh * 0.11f, StarPale,
        )
        // Rolling hills on the horizon, snowy when it snows.
        val hill = when {
            sky == Sky.SNOW -> HillSnow
            phase == DayPhase.NIGHT || phase == DayPhase.EVENING -> HillNight
            else -> HillDay
        }
        drawOval(hill.copy(alpha = 0.8f), Offset(left - ww * 0.1f, bottom - wh * 0.2f), Size(ww * 0.7f, wh * 0.45f))
        drawOval(hill, Offset(left + ww * 0.4f, bottom - wh * 0.16f), Size(ww * 0.8f, wh * 0.45f))
        drawClouds(t, sky, left, top, right, top + wh * 0.5f, CloudWhite, CloudGrey, CloudStorm)
        drawWeather(t, sky, left, top, right, bottom, 1f, 0f, 0f, 0f, kit)
    }

    // Frame, cross bars and sill.
    val bar = h * 0.018f
    drawLine(kit.ink.muted, Offset((left + right) / 2f, top), Offset((left + right) / 2f, bottom), bar)
    drawLine(kit.ink.muted, Offset(left, top + wh * 0.45f), Offset(right, top + wh * 0.45f), bar)
    drawRect(kit.ink.muted, Offset(left, top), Size(ww, wh), style = kit.frame)
    drawRect(kit.ink.muted, Offset(left - h * 0.04f, bottom), Size(ww + h * 0.08f, h * 0.05f))

    val unit = h / DESIGN_HEIGHT
    withTransform({
        translate(left + ww * 0.3f, bottom)
        scale(unit, unit, Offset.Zero)
    }) {
        drawPlant(t, phase, kit)
    }
}

/** How open the flower is in [phase]: a bud at night, wide at midday. */
private fun bloom(phase: DayPhase): Float = when (phase) {
    DayPhase.NIGHT -> 0f
    DayPhase.DAWN -> 0.45f
    DayPhase.MORNING -> 0.9f
    DayPhase.MIDDAY -> 1f
    DayPhase.AFTERNOON -> 0.85f
    DayPhase.EVENING -> 0.35f
}

/** A terracotta pot with a swaying stem, two leaves and a flower in the theme's brand colour. */
private fun DrawScope.drawPlant(t: Float, phase: DayPhase, kit: SceneKit) {
    // A gentle breeze by day, near stillness at night.
    val sway = (if (phase == DayPhase.NIGHT) 1f else 3f) * wave(t, 6f)
    val p = kit.path
    p.reset()
    p.moveTo(0f, -18f)
    p.quadraticTo(-3f, -34f, sway, -50f)
    drawPath(p, StemGreen, style = kit.stem)
    drawLeaf(p, -1.2f, -28f, 12f + 3f * wave(t, 6f, 0.2f), mirror = false)
    drawLeaf(p, -0.8f + sway * 0.5f, -38f, 10f + 3f * wave(t, 6f, 0.5f), mirror = true)

    p.reset()
    p.moveTo(-11f, -16f)
    p.lineTo(11f, -16f)
    p.lineTo(8f, 0f)
    p.lineTo(-8f, 0f)
    p.close()
    drawPath(p, Pot)
    drawOval(Soil, Offset(-10f, -20.5f), Size(20f, 4f))
    drawRect(PotRim, Offset(-12.5f, -19f), Size(25f, 4.5f))

    val open = (bloom(phase) + 0.04f * wave(t, 6f)).coerceIn(0f, 1f)
    val cx = sway
    val cy = -50f
    val spread = mix(7f, 52f, open)
    val length = mix(10f, 13f, open)
    drawOval(StemGreen, Offset(cx - 3.5f, cy - 2f), Size(7f, 5f))
    // Darker petals behind, then the same petals a touch smaller on top: a soft edge to each one.
    for (pass in 0..1) {
        val back = pass == 0
        val pw = if (back) 6.4f else 5.2f
        val pl = if (back) length else length * 0.92f
        for (i in 0 until 7) {
            rotate((i - 3) * spread, Offset(cx, cy)) {
                drawOval(if (back) kit.brandDark else kit.ink.brand, Offset(cx - pw / 2f, cy - pl), Size(pw, pl))
            }
        }
    }
    if (open > 0.2f) drawCircle(FlowerHeart, 3.6f * open, Offset(cx, cy))
}

/** One leaf from the stem at ([x], [y]), turned [angle] degrees; [mirror] points it right. */
private fun DrawScope.drawLeaf(p: Path, x: Float, y: Float, angle: Float, mirror: Boolean) {
    scale(if (mirror) -1f else 1f, 1f, Offset(x, y)) {
        rotate(angle, Offset(x, y)) {
            p.reset()
            p.moveTo(x, y)
            p.quadraticTo(x - 7f, y - 6f, x - 14f, y)
            p.quadraticTo(x - 7f, y + 4f, x, y)
            p.close()
            drawPath(p, LeafGreen)
        }
    }
}
