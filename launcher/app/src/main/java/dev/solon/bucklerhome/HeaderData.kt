package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val REFRESH_OK_MS = 30 * 60_000L
private const val REFRESH_RETRY_MS = 2 * 60_000L

/** Process-wide last results so returning to Home shows content immediately instead of a blank. */
private object HeaderCache {
    var weather: Pair<Place, Weather>? = null
    var forecast: Pair<Place, Forecast>? = null
    /** By query key: the ticker and the News stage can follow different sources. */
    val news = HashMap<String, List<Headline>>()
    /** When each feed (by [poll] key) last succeeded. */
    val fetchedAt = HashMap<String, Long>()
}

/**
 * Fetches every [everyMs] (or [REFRESH_RETRY_MS] after a failure) while [visible], i.e. Home is on
 * screen. Hidden behind an app or the screensaver it stops; back on screen it fetches at once only
 * if the last success is older than [everyMs], otherwise when that runs out. With nothing [shown]
 * for this key yet (a new city, or a stage switched back to) it fetches at once, whatever the age.
 */
private suspend fun <T : Any> poll(
    key: String,
    visible: Boolean,
    everyMs: Long,
    shown: Boolean,
    fetch: () -> T?,
    onFresh: suspend (T) -> Unit,
) {
    if (!visible) return
    val age = System.currentTimeMillis() - (HeaderCache.fetchedAt[key] ?: 0L)
    if (shown && age < everyMs) delay(everyMs - age)
    while (true) {
        val fresh = withContext(Dispatchers.IO) { fetch() }
        if (fresh != null) {
            HeaderCache.fetchedAt[key] = System.currentTimeMillis()
            onFresh(fresh)
        }
        delay(if (fresh != null) everyMs else REFRESH_RETRY_MS)
    }
}

@Composable
fun rememberWeather(place: Place?): State<Weather?> {
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    return produceState(
        initialValue = HeaderCache.weather?.takeIf { it.first == place }?.second,
        place,
        visible,
    ) {
        // produceState keeps the last key's value: show this place's own, or nothing.
        value = HeaderCache.weather?.takeIf { it.first == place }?.second
        if (place == null) return@produceState
        poll("weather:$place", visible, REFRESH_OK_MS, value != null, { WeatherClient.fetch(place) }) { fresh ->
            HeaderCache.weather = place to fresh
            value = fresh
        }
    }
}

@Composable
fun rememberForecast(place: Place?): State<Forecast?> {
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    return produceState(
        initialValue = HeaderCache.forecast?.takeIf { it.first == place }?.second,
        place,
        visible,
    ) {
        value = HeaderCache.forecast?.takeIf { it.first == place }?.second
        if (place == null) return@produceState
        val fetch = { WeatherClient.fetchForecast(place)?.takeIf { it.hours.isNotEmpty() || it.days.isNotEmpty() } }
        poll("forecast:$place", visible, REFRESH_OK_MS, value != null, fetch) { fresh ->
            HeaderCache.forecast = place to fresh
            value = fresh
        }
    }
}

@Composable
fun rememberHeadlines(query: NewsQuery?, everyMs: Long): State<List<Headline>> {
    val context = LocalContext.current
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    return produceState(
        initialValue = query?.cacheKey?.let { HeaderCache.news[it] }.orEmpty(),
        query?.cacheKey,
        visible,
        everyMs,
    ) {
        val key = query?.cacheKey
        val pollKey = "news:$key"
        // produceState keeps the last key's headlines: start from this key's own.
        value = key?.let { HeaderCache.news[it] }.orEmpty()
        if (query == null || key == null) return@produceState
        if (value.isEmpty()) {
            // After Android closed Home: the saved stories at once, and their age, so fresh ones are not fetched again.
            val saved = withContext(Dispatchers.IO) { HeadlineStore.load(context, key) }
            value = saved.headlines
            if (saved.fetchedAt > 0) HeaderCache.fetchedAt.putIfAbsent(pollKey, saved.fetchedAt)
        }
        // A feed that fails this time keeps the stories it had.
        poll(pollKey, visible, everyMs, value.isNotEmpty(), { NewsFeed.load(query, value).ifEmpty { null } }) { fresh ->
            HeaderCache.news[key] = fresh
            value = fresh
            val at = HeaderCache.fetchedAt[pollKey] ?: System.currentTimeMillis()
            withContext(Dispatchers.IO) { HeadlineStore.save(context, key, fresh, at) }
        }
    }
}

/**
 * Headlines for the header ticker and for the News stage. While News is synced (or both follow the
 * same sources) they are one fetch; otherwise the stage fetches its own.
 */
@Composable
fun rememberHomeHeadlines(config: DisplayConfig, stage: MainStage): Pair<List<Headline>, List<Headline>> {
    val headerQuery = config.newsQuery()
    val stageQuery = stageNewsQuery(config, stage)
    val shared = stageQuery == null || stageQuery.cacheKey == headerQuery?.cacheKey
    val every = config.newsRefresh.millis
    val header by rememberHeadlines(if (shared) headerQuery ?: stageQuery else headerQuery, every)
    val own by rememberHeadlines(if (shared) null else stageQuery, every)
    return header to if (shared) header else own
}

/**
 * Bumped whenever Buckler's settings change, so Home redraws behind the Settings panel as you
 * change things there (Home is paused, not stopped, while the panel is over it).
 */
@Composable
fun rememberPrefsChanges(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences("bucklerhome", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return tick
}

/** Clock and date strings for the header, refreshed every 15 seconds. */
@Composable
fun rememberClockText(config: DisplayConfig): State<Pair<String, String>> =
    produceState(initialValue = "" to "", config.use24h, config.dateStyle) {
        while (true) {
            value = DisplayFormat.time(LocalTime.now(), config.use24h) to
                DisplayFormat.date(LocalDate.now(), config.dateStyle)
            delay(15_000)
        }
    }

@Composable
fun rememberHeaderActions(onOpenEditHome: () -> Unit): HeaderActions {
    val context = LocalContext.current
    return remember(onOpenEditHome) {
        HeaderActions(
            onNetwork = { link -> openNetworkSettings(context, link) },
            onVpn = { openVpn(context) },
            onSettings = {
                safeLaunch(context, Intent(context, BucklerSettingsActivity::class.java), "Buckler Settings")
            },
            onEditHome = onOpenEditHome,
            onPlayStore = playStoreLaunchIntent(context.packageManager)?.let { play ->
                { safeLaunch(context, play, "Play Store") }
            },
        )
    }
}

/** The newest release once per resume, when the periodic update check says it is time. */
@Composable
fun rememberAvailableUpdate(resumeTick: Int): State<GitHubRelease?> {
    val context = LocalContext.current
    return produceState<GitHubRelease?>(initialValue = null, resumeTick) {
        if (!UpdateChecker.shouldAutoCheck(context)) return@produceState
        val result = withContext(Dispatchers.IO) { UpdateChecker.check(context, force = false) }
        if (result is UpdateCheckResult.Available) value = result.release
    }
}
