package dev.solon.bucklerhome

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val REFRESH_OK_MS = 30 * 60_000L
private const val REFRESH_NEWS_MS = 20 * 60_000L
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
fun rememberHeadlines(query: NewsQuery?): State<List<Headline>> {
    val context = LocalContext.current
    val visible = rememberLifecycleAtLeast(Lifecycle.State.STARTED)
    return produceState(
        initialValue = query?.cacheKey?.let { HeaderCache.news[it] }.orEmpty(),
        query?.cacheKey,
        visible,
    ) {
        val key = query?.cacheKey
        // produceState keeps the last key's headlines: start from this key's own.
        value = key?.let { HeaderCache.news[it] }.orEmpty()
        if (query == null || key == null) return@produceState
        if (value.isEmpty()) value = withContext(Dispatchers.IO) { HeadlineStore.load(context, key) }
        poll("news:$key", visible, REFRESH_NEWS_MS, value.isNotEmpty(), { NewsFeed.load(query).ifEmpty { null } }) { fresh ->
            HeaderCache.news[key] = fresh
            value = fresh
            withContext(Dispatchers.IO) { HeadlineStore.save(context, key, fresh) }
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
    val header by rememberHeadlines(if (shared) headerQuery ?: stageQuery else headerQuery)
    val own by rememberHeadlines(if (shared) null else stageQuery)
    return header to if (shared) header else own
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
