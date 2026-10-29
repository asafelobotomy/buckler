package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.lifecycle.Lifecycle
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Who keeps the stage company: a little animal, Buckler's own mascot, or a window onto the sky. */
enum class CompanionKind(val label: String) {
    CAT("Black cat"),
    FOX("Fox"),
    SHIELD("Shield"),
    GARDEN("Garden"),
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): CompanionKind =
            entries.firstOrNull { it.wire() == raw?.lowercase() } ?: CAT
    }
}

/** The part of the day a companion acts out, from the local hour. */
enum class DayPhase {
    NIGHT,
    DAWN,
    MORNING,
    MIDDAY,
    AFTERNOON,
    EVENING,
    ;

    companion object {
        /** [hour] is 0–23; anything else wraps round the clock. */
        fun of(hour: Int): DayPhase = when (hour.mod(24)) {
            in 6..7 -> DAWN
            in 8..11 -> MORNING
            in 12..13 -> MIDDAY
            in 14..17 -> AFTERNOON
            in 18..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** The weather a scene shows, folded down from Open-Meteo's WMO weather codes. */
enum class Sky {
    CLEAR,
    CLOUDY,
    /** Fully overcast (code 3): clouds and no sun or moon. */
    OVERCAST,
    FOG,
    RAIN,
    SNOW,
    THUNDER,
    ;

    /** Rain falls, so the companion shelters. */
    val wet: Boolean get() = this == RAIN || this == THUNDER

    companion object {
        /** A missing or unknown code shows a clear sky rather than guessing at weather. */
        fun of(code: Int?): Sky = when (code) {
            0 -> CLEAR
            1, 2 -> CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            in 51..67, in 80..82 -> RAIN
            in 71..77, in 85..86 -> SNOW
            in 95..99 -> THUNDER
            else -> CLEAR
        }
    }
}

/**
 * A small looping scene of [kind] for the current time of day and weather. [weatherCode] is a WMO
 * code (null when there is no forecast) and [isDay] says whether the sun is up there. The scene
 * scales from its box height, so it fits a full Main Stage card as well as the header's Side Stage.
 */
@Composable
fun CompanionScene(kind: CompanionKind, weatherCode: Int?, isDay: Boolean, modifier: Modifier = Modifier) {
    val phase = rememberDayPhase()
    val sky = Sky.of(weatherCode)
    val clock = rememberSceneClock()
    val text = ColorText
    val muted = ColorMuted
    val brand = ColorBrand
    val tile = ColorTile
    val section = ColorSection
    val hint = ColorHint
    // Once per scheme, so the scene's draw cache isn't rebuilt on every recomposition.
    val ink = remember(text, muted, brand, tile, section, hint) { SceneInk(text, muted, brand, tile, section, hint) }
    Spacer(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .drawWithCache {
                // Paths, strokes and the sky gradient are made once per size, phase or theme; each
                // frame then only reads the clock, so it redraws without recomposing.
                val kit = SceneKit(ink, size.height, phase)
                onDrawBehind { drawCompanion(kind, clock.floatValue, phase, sky, isDay, kit) }
            },
    )
}

/** The current [DayPhase], checked again at the top of every minute. */
@Composable
private fun rememberDayPhase(): DayPhase {
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
            hour = LocalTime.now().hour
        }
    }
    return DayPhase.of(hour)
}

/**
 * Seconds since the scene started, wrapped at [LOOP_SECONDS] and stepped at about 30 fps. It only
 * ticks while Home is on screen, so a hidden launcher spends nothing on its companion.
 */
@Composable
private fun rememberSceneClock(): FloatState {
    val clock = remember { mutableFloatStateOf(0f) }
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val resumeAt = clock.floatValue
        var start = -1L
        var last = Long.MIN_VALUE / 2
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                if (now - last >= FRAME_NANOS) {
                    last = now
                    // Wrapped in Long nanos, so the float never grows large enough to lose precision.
                    val elapsed = (now - start) % LOOP_NANOS
                    clock.floatValue = (resumeAt + elapsed / 1e9f) % LOOP_SECONDS
                }
            }
        }
    }
    return clock
}

/** Lays out one frame: sky behind, the companion on the left or centre, weather in front. */
private fun DrawScope.drawCompanion(
    kind: CompanionKind,
    t: Float,
    phase: DayPhase,
    sky: Sky,
    isDay: Boolean,
    kit: SceneKit,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    if (kind == CompanionKind.GARDEN) {
        drawGarden(t, phase, sky, isDay, kit)
        return
    }
    val unit = h / DESIGN_HEIGHT
    val cx = min(w * 0.5f, h * 1.05f)
    val ground = h * 0.92f
    val sunX = min(w - h * 0.3f, cx + h * 1.3f)
    drawSkyBits(t, sky, isDay, phase, 0f, 0f, w, h * 0.6f, sunX, h * 0.24f, h * 0.1f, kit.star)
    drawClouds(t, sky, 0f, 0f, w, h * 0.42f, kit.cloud, kit.rainCloud, kit.stormCloud)
    drawOval(
        color = kit.ink.muted.copy(alpha = 0.18f),
        topLeft = Offset(cx - 30f * unit, ground - 3f * unit),
        size = Size(60f * unit, 6f * unit),
    )
    // Breathing: a slow rise and fall anchored at the feet, shared by every character.
    val breath = 1f + 0.018f * wave(t, 4f)
    withTransform({
        translate(cx, ground)
        scale(unit, unit * breath, Offset.Zero)
    }) {
        when (kind) {
            CompanionKind.CAT -> drawCat(t, phase, sky, kit)
            CompanionKind.FOX -> drawFox(t, phase, sky, kit)
            else -> drawShieldMascot(t, phase, sky, kit)
        }
    }
    // Drops stop at the umbrella or leaf instead of falling through it.
    drawWeather(
        t, sky, 0f, 0f, w, h,
        shelterLeft = cx + SHELTER_LEFT * unit,
        shelterRight = cx + SHELTER_RIGHT * unit,
        shelterTopLeft = ground + SHELTER_TOP_LEFT * unit,
        shelterTopRight = ground + SHELTER_TOP_RIGHT * unit,
        kit = kit,
    )
}

/** Characters are drawn in a box this many units tall, feet at 0 and up negative. */
internal const val DESIGN_HEIGHT = 100f

/** Where the umbrella or leaf keeps the rain off, in design units round the character. */
private const val SHELTER_LEFT = -23f
private const val SHELTER_RIGHT = 38f
private const val SHELTER_TOP_LEFT = -62f
private const val SHELTER_TOP_RIGHT = -75f

/**
 * The clock wraps here. Every loop's period must divide it evenly (0.25 s up to 300 s in use), so
 * nothing jumps when the clock starts over.
 */
internal const val LOOP_SECONDS = 600f
private const val LOOP_NANOS = 600_000_000_000L
private const val FRAME_NANOS = 30_000_000L
private const val MINUTE_MILLIS = 60_000L
private const val TAU = (2 * PI).toFloat()

/** A sine wave in -1..1 with [period] seconds, shifted by [offset] of a cycle. */
internal fun wave(t: Float, period: Float, offset: Float = 0f): Float = sin((t / period + offset) * TAU)

/** How far through a [period]-second loop the clock is, 0 until 1. */
internal fun cycle(t: Float, period: Float, offset: Float = 0f): Float {
    val x = t / period + offset
    return x - floor(x)
}

/** Eyes shut for a moment once every [period] seconds. */
internal fun blinking(t: Float, period: Float = 5f, offset: Float = 0f): Boolean = cycle(t, period, offset) < 0.035f

/** A smooth 0 → 1 → 0 hump while [x] runs from [from] to [to], and 0 outside it. */
internal fun hump(x: Float, from: Float, to: Float): Float {
    if (x <= from || x >= to) return 0f
    val s = sin((x - from) / (to - from) * PI.toFloat())
    return s * s
}

/** A stable pseudo-random 0 until 1 for particle [i], so particles need no stored state. */
internal fun hash01(i: Int, salt: Int = 0): Float {
    var x = i * 374_761_393 + salt * 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16_777_216f
}

/** Linear blend from [a] to [b]. */
internal fun mix(a: Float, b: Float, f: Float): Float = a + (b - a) * f
