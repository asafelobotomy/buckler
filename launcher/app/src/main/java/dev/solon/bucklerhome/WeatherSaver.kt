package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WEATHER_EVERY_MS = 30 * 60_000L
private const val WEATHER_RETRY_MS = 5 * 60_000L
private const val SLIDE_FADE_MS = 900L

/** What the rolling weather screensaver last fetched; any part may be missing. */
private class SaverWeather(val glance: GlanceWeather?, val forecast: Forecast?, val grid: WeatherGrid?)

/**
 * Screensaver that rolls through the weather at the city chosen for Home: now, the next hours,
 * the next days, the pressure map and the temperature heat map, one at a time
 * ([SaverOption.WEATHER_SLIDES] picks which). Plain views like the rest of the screensaver; the
 * maps are drawn into a bitmap by [weatherMapBitmap]. Refetched every half hour while it runs.
 */
class WeatherSaver(context: Context, private val config: ScreensaverConfig) : FrameLayout(context) {
    private enum class Slide { NOW, HOURS, DAYS, PRESSURE, HEAT }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density = resources.displayMetrics.density
    private val palette = Palettes.of(config.theme)
    private val display = DisplayPrefs.load(context)
    private val place = display.place ?: display.stagePlace
    private val fahrenheit = if (display.place != null) display.fahrenheit else display.stageFahrenheit
    private var weather: SaverWeather? = null
    private var shown: View? = null
    private var mapBitmap: Bitmap? = null

    init {
        setBackgroundColor(palette.bgTop.toArgb())
    }

    fun start() {
        val slides = when (config.value(SaverOption.WEATHER_SLIDES)) {
            1 -> listOf(Slide.NOW, Slide.HOURS, Slide.DAYS)
            2 -> listOf(Slide.PRESSURE, Slide.HEAT)
            else -> Slide.entries
        }
        if (place == null) {
            show(message("Set a city in Settings → Widgets → Weather"))
            return
        }
        scope.launch {
            while (true) {
                val fresh = withContext(Dispatchers.IO) {
                    val glance = async { GlanceWeatherClient.fetch(place) }
                    val forecast = async { WeatherClient.fetchForecast(place, hourCount = 8, dayCount = 7) }
                    val grid = async { WeatherMapClient.fetch(place) }
                    SaverWeather(glance.await(), forecast.await(), grid.await())
                }
                val any = fresh.glance != null || fresh.forecast != null || fresh.grid != null
                if (any) weather = fresh
                delay(if (any) WEATHER_EVERY_MS else WEATHER_RETRY_MS)
            }
        }
        scope.launch {
            var index = 0
            var misses = 0
            while (true) {
                val current = weather
                if (current == null) {
                    if (shown == null) show(message("Loading the weather for ${place.name}…"))
                    delay(1_000)
                    continue
                }
                val slide = slides[index++ % slides.size]
                val view = build(slide, current)
                if (view == null) {
                    // Nothing for this slide (a map without its grid, say): on to the next at once,
                    // unless no slide has anything, which says so instead of spinning.
                    if (++misses >= slides.size) {
                        show(message(if (ScreensaverImages.online(context)) "No weather right now" else "Weather unavailable offline"))
                        misses = 0
                        delay(WEATHER_RETRY_MS)
                    }
                    continue
                }
                misses = 0
                show(view)
                delay(config.intervalSeconds * 1_000L)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun build(slide: Slide, w: SaverWeather): View? = when (slide) {
        Slide.NOW -> w.glance?.let(::nowSlide)
        Slide.HOURS -> w.forecast?.hours?.let { all ->
            // Hours gone by since the half-hourly fetch drop off, as on the Weather stage.
            val hour = java.time.LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
            all.filter { it.at?.isBefore(hour) != true }.take(8)
        }?.takeIf { it.isNotEmpty() }?.let { hours ->
            strip("Next hours", hours.map { slot ->
                val time = runCatching { LocalTime.parse(slot.label) }.getOrNull()
                    ?.let { DisplayFormat.time(it, config.use24h) } ?: slot.label
                Triple(time, WeatherClient.describe(slot.code, slot.isDay).first, temp(slot.temperatureC))
            })
        }
        Slide.DAYS -> w.forecast?.days?.takeIf { it.isNotEmpty() }?.let { days ->
            strip("Next ${days.size} days", days.mapIndexed { i, slot ->
                val day = runCatching { LocalDate.parse(slot.label) }.getOrNull()
                val name = when {
                    i == 0 -> "Today"
                    day != null -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    else -> slot.label
                }
                val range = slot.lowC?.let { "${temp(slot.temperatureC)} / ${temp(it)}" } ?: temp(slot.temperatureC)
                Triple(name, WeatherClient.describe(slot.code).first, range)
            })
        }
        Slide.PRESSURE -> w.grid?.let { map(it, WeatherMapStyle.PRESSURE, "Pressure, wind and rain") }
        Slide.HEAT -> w.grid?.takeIf { g -> g.temperature.firstOrNull()?.any { !it.isNaN() } == true }
            ?.let { map(it, WeatherMapStyle.HEAT, "Temperature") }
    }

    private fun nowSlide(g: GlanceWeather): View {
        val (glyph, summary) = WeatherClient.describe(g.now.code, g.now.isDay)
        val details = listOfNotNull(
            summary,
            g.highC?.let { "High ${temp(it)}" },
            g.lowC?.let { "Low ${temp(it)}" },
        ).joinToString("  ·  ")
        val rain = g.hours.take(12).firstOrNull { it.rainChance >= 50 }
        val extra = listOfNotNull(
            rain?.let { "Rain likely from ${DisplayFormat.time(it.at.toLocalTime(), config.use24h)} (${it.rainChance}%)" },
            g.sunrise?.let { "Sunrise ${DisplayFormat.time(it, config.use24h)}" },
            g.sunset?.let { "Sunset ${DisplayFormat.time(it, config.use24h)}" },
        ).joinToString("  ·  ")
        return column(
            text(place!!.name, 26f, palette.section.toArgb()),
            text("$glyph  ${temp(g.now.temperatureC)}", 120f, palette.text.toArgb(), light = true),
            text(details, 30f, palette.text.toArgb()),
            text(extra, 22f, palette.muted.toArgb()).takeIf { extra.isNotEmpty() },
        )
    }

    /** A heading over a row of columns, each a label, a glyph and a value. */
    private fun strip(title: String, cells: List<Triple<String, String, String>>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        cells.forEach { (label, glyph, value) ->
            row.addView(
                column(
                    text(label, 24f, palette.muted.toArgb()).apply { maxLines = 1 },
                    text(glyph, 54f, palette.text.toArgb()),
                    text(value, 28f, palette.text.toArgb()).apply { maxLines = 1 },
                ),
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        // The row spans the slide, so each cell gets an equal share rather than only its own width.
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text("${place!!.name}  ·  $title", 26f, palette.section.toArgb()))
            addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        }
    }

    private suspend fun map(grid: WeatherGrid, style: WeatherMapStyle, title: String): View? {
        val w = (width - dp(160)).coerceAtLeast(dp(160))
        val h = (height - dp(220)).coerceAtLeast(dp(90))
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { weatherMapBitmap(context, place!!, grid, 0, style, palette, fahrenheit, w, h) }.getOrNull()
        } ?: return null
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
        }
        val previous = mapBitmap
        mapBitmap = bitmap
        // The map before last is off screen by now (the fade is long over); drop it at once.
        postDelayed({ if (previous !== mapBitmap) previous?.recycle() }, SLIDE_FADE_MS * 2)
        val hourNote = grid.hours.firstOrNull()?.substringAfter('T')?.take(5)
            ?.let { runCatching { DisplayFormat.time(LocalTime.parse(it), config.use24h) }.getOrNull() }
        val heading = text(listOfNotNull(place!!.name, title, hourNote).joinToString("  ·  "), 26f, palette.section.toArgb())
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(heading)
            addView(image, LinearLayout.LayoutParams(w, h).apply { topMargin = dp(14) })
            addView(text(WEATHER_MAP_CREDIT, 15f, palette.hint.toArgb()).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(w, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    /** Cross-fades to [view], nudged a little each time so nothing stays put long enough to burn in. */
    private fun show(view: View) {
        val old = shown
        shown = view
        view.alpha = 0f
        if (!config.reduceMotion) {
            view.translationX = Random.nextInt(-20, 21) * density
            view.translationY = Random.nextInt(-14, 15) * density
        }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { setMargins(dp(80), dp(60), dp(80), dp(80)) })
        view.animate().alpha(1f).setDuration(SLIDE_FADE_MS).start()
        old?.animate()?.alpha(0f)?.setDuration(SLIDE_FADE_MS)?.withEndAction { removeView(old) }?.start()
    }

    private fun message(text: String): View = text(text, 26f, palette.muted.toArgb())

    private fun temp(celsius: Double): String = WeatherClient.formatTemperature(celsius, fahrenheit).dropLast(1)

    private fun column(vararg views: View?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        views.filterNotNull().forEach { child ->
            addView(child, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            })
        }
    }

    private fun text(value: String, sizeSp: Float, colour: Int, light: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = config.font.typeface(context, light)
        gravity = Gravity.CENTER
        if (!palette.dark) return@apply
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
