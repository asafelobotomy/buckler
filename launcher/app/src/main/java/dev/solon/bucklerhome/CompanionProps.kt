package dev.solon.bucklerhome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.sin

/** Theme colours a scene draws with, read in composition so drawing never touches the theme. */
internal class SceneInk(
    val text: Color,
    val muted: Color,
    val brand: Color,
    val tile: Color,
    val section: Color,
    val hint: Color,
) {
    /** A light card (the paper themes): pale stars and snow would vanish, so they darken. */
    val light: Boolean = tile.luminance() > 0.5f
}

/**
 * Everything a frame reuses: one scratch [path], the stroke styles and the colours that need a blend.
 * It is built when the box size, phase or theme changes, never per frame. Strokes are in design units
 * because characters draw scaled; [height] sizes the pixel-space ones.
 */
internal class SceneKit(val ink: SceneInk, height: Float, phase: DayPhase) {
    val path = Path()
    val shield = Path().also { buildShieldPath(it) }
    val rim = Stroke(width = 3.2f, join = StrokeJoin.Round)
    val line = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val tail = Stroke(width = 7f, cap = StrokeCap.Round)
    val tailRim = Stroke(width = 10.2f, cap = StrokeCap.Round)
    val stem = Stroke(width = 2.6f, cap = StrokeCap.Round)
    val frame = Stroke(width = height * 0.035f)

    // Clouds are opaque blends with the card so overlapping puffs don't show darker seams.
    val cloud = lerp(ink.tile, ink.muted, 0.45f)
    val rainCloud = lerp(ink.tile, Color(0xFF6B7280), 0.6f)
    val stormCloud = lerp(ink.tile, Color(0xFF3F4452), 0.75f)
    val fog = ink.muted.copy(alpha = 0.22f)
    val drop = if (ink.light) Color(0xFF4F7FB0) else Color(0xFF8DB9E8)
    val snow = if (ink.light) Color(0xFF8FA3B8) else Color(0xFFEAF1FA)
    val star = if (ink.light) ink.muted else Color(0xFFFFF4D6)
    val brandDark = lerp(ink.brand, Color.Black, 0.35f)
    val brandLight = lerp(ink.brand, Color.White, 0.45f).copy(alpha = 0.6f)
    val skyBrush = Brush.verticalGradient(skyColors(phase), 0f, height)
}

/** "z"s that rise from ([x], [y]) and fade, the universal sign of a nap. */
internal fun DrawScope.drawZs(t: Float, x: Float, y: Float, color: Color) {
    for (i in 0 until 3) {
        val p = cycle(t, 3f, i / 3f)
        val s = 3f + 4f * p
        val zx = x + 7f * p + 1.5f * wave(t, 2f, i / 3f)
        val zy = y - 22f * p
        val c = color.copy(alpha = sin(p * PI.toFloat()) * 0.9f)
        drawLine(c, Offset(zx, zy), Offset(zx + s, zy), 1.4f, StrokeCap.Round)
        drawLine(c, Offset(zx + s, zy), Offset(zx, zy + s), 1.4f, StrokeCap.Round)
        drawLine(c, Offset(zx, zy + s), Offset(zx + s, zy + s), 1.4f, StrokeCap.Round)
    }
}

/** A closed eye: a soft "∪" for sleep, or "∩" when [happy] (contented, eyes squeezed). */
internal fun DrawScope.drawShutEye(x: Float, y: Float, width: Float, color: Color, happy: Boolean, kit: SceneKit) {
    drawArc(
        color = color,
        startAngle = if (happy) 180f else 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(x - width / 2f, y - width / 3f),
        size = Size(width, width * 2f / 3f),
        style = kit.line,
    )
}

/** A four-point sparkle, for stars and glints. */
internal fun DrawScope.drawTwinkle(x: Float, y: Float, r: Float, color: Color, width: Float) {
    drawLine(color, Offset(x - r, y), Offset(x + r, y), width, StrokeCap.Round)
    drawLine(color, Offset(x, y - r), Offset(x, y + r), width, StrokeCap.Round)
}

/**
 * A little umbrella planted beside the character, leaning over it, in design units; [rib] is the
 * darker shade for its ribs. Its canopy matches the SHELTER_ constants, where the rain stops.
 */
internal fun DrawScope.drawUmbrella(t: Float, color: Color, rib: Color) {
    rotate(UMBRELLA_TILT + 1.5f * wave(t, 6f), pivot = Offset(UMBRELLA_X, -2f)) {
        drawLine(Color(0xFF6B5442), Offset(UMBRELLA_X, -62f), Offset(UMBRELLA_X, -1f), 2f, StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(UMBRELLA_X - 31f, -80f),
            size = Size(62f, 36f),
        )
        drawLine(rib, Offset(UMBRELLA_X, -80f), Offset(UMBRELLA_X - 13f, -62f), 1.2f)
        drawLine(rib, Offset(UMBRELLA_X, -80f), Offset(UMBRELLA_X + 13f, -62f), 1.2f)
        drawLine(rib, Offset(UMBRELLA_X - 31f, -62f), Offset(UMBRELLA_X + 31f, -62f), 1.6f, StrokeCap.Round)
        drawLine(rib, Offset(UMBRELLA_X, -80f), Offset(UMBRELLA_X, -84f), 1.6f, StrokeCap.Round)
    }
}

/** A big leaf stuck in the ground as a shelter, the same size and lean as [drawUmbrella]. */
internal fun DrawScope.drawLeafShelter(t: Float, kit: SceneKit) {
    val green = Color(0xFF5FA85A)
    rotate(UMBRELLA_TILT + 2f * wave(t, 5f), pivot = Offset(UMBRELLA_X, -2f)) {
        drawLine(Color(0xFF3F7A3C), Offset(UMBRELLA_X, -66f), Offset(UMBRELLA_X, -1f), 2.2f, StrokeCap.Round)
        val p = kit.path
        p.reset()
        p.moveTo(UMBRELLA_X - 33f, -64f)
        p.quadraticTo(UMBRELLA_X - 8f, -92f, UMBRELLA_X + 33f, -68f)
        p.quadraticTo(UMBRELLA_X + 4f, -56f, UMBRELLA_X - 33f, -64f)
        p.close()
        drawPath(p, green)
        drawLine(Color(0xFF3F7A3C), Offset(UMBRELLA_X - 30f, -64f), Offset(UMBRELLA_X + 30f, -68f), 1.2f)
    }
}

/**
 * A small standing lantern whose foot is at ([x], [y]) in design units, glowing with a soft flicker.
 * Its handle's top is 24 units above the foot, for a character to carry it by.
 */
internal fun DrawScope.drawLantern(t: Float, x: Float, y: Float) {
    val glow = 0.16f + 0.05f * wave(t, 1.5f) + 0.03f * wave(t, 0.75f, 0.3f)
    drawCircle(LanternLight.copy(alpha = glow), 15f, Offset(x, y - 9f))
    drawCircle(LanternLight.copy(alpha = glow * 1.5f), 8f, Offset(x, y - 9f))
    drawRect(LanternLight, Offset(x - 3.8f, y - 15f), Size(7.6f, 12.5f))
    drawRect(LanternFrame, Offset(x - 5f, y - 2.6f), Size(10f, 2.6f))
    drawLine(LanternFrame, Offset(x - 4f, y - 15f), Offset(x - 4f, y - 2.5f), 1.2f)
    drawLine(LanternFrame, Offset(x + 4f, y - 15f), Offset(x + 4f, y - 2.5f), 1.2f)
    drawRect(LanternFrame, Offset(x - 5.5f, y - 17.5f), Size(11f, 2.6f))
    drawArc(LanternFrame, 180f, 180f, false, Offset(x - 3.5f, y - 24f), Size(7f, 13f), style = HandleStroke)
}

private val LanternLight = Color(0xFFFFD27A)
private val LanternFrame = Color(0xFF5A4A3A)
private val HandleStroke = Stroke(width = 1.2f)
private const val UMBRELLA_X = 20f
private const val UMBRELLA_TILT = -12f
