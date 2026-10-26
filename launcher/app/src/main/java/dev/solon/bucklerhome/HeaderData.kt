package dev.solon.bucklerhome

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
    var news: Pair<NewsSource, List<String>>? = null
}

@Composable
fun rememberWeather(place: Place?): State<Weather?> =
    produceState<Weather?>(
        initialValue = HeaderCache.weather?.takeIf { it.first == place }?.second,
        place,
    ) {
        if (place == null) {
            value = null
            return@produceState
        }
        while (true) {
            val fresh = withContext(Dispatchers.IO) { WeatherClient.fetch(place) }
            if (fresh != null) {
                HeaderCache.weather = place to fresh
                value = fresh
            }
            delay(if (fresh != null) REFRESH_OK_MS else REFRESH_RETRY_MS)
        }
    }

@Composable
fun rememberHeadlines(source: NewsSource?): State<List<String>> =
    produceState(
        initialValue = HeaderCache.news?.takeIf { it.first == source }?.second.orEmpty(),
        source,
    ) {
        if (source == null) {
            value = emptyList()
            return@produceState
        }
        while (true) {
            val fresh = withContext(Dispatchers.IO) { NewsFeed.fetch(source) }
            if (fresh.isNotEmpty()) {
                HeaderCache.news = source to fresh
                value = fresh
            }
            delay(if (fresh.isNotEmpty()) REFRESH_NEWS_MS else REFRESH_RETRY_MS)
        }
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
