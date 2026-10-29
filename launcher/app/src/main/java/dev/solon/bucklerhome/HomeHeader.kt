package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

class HeaderActions(
    val onNetwork: (LinkType) -> Unit,
    val onVpn: () -> Unit,
    val onSettings: () -> Unit,
    val onEditHome: () -> Unit,
    /** Null when the Play Store is not installed. */
    val onPlayStore: (() -> Unit)?,
)

/**
 * Header, kept to one quiet row plus an optional news line:
 *
 *   [logo] 21:30            [ now playing ]            wifi  vpn │ edit  store  settings
 *          Mon 26 Oct · ☀ 14°
 *   ───────────── news ticker (dim, only when a source is on) ─────────────
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeHeader(
    clock: String,
    date: String,
    config: DisplayConfig,
    weather: Weather?,
    headlines: List<Headline>,
    net: NetState,
    nowPlaying: NowPlayingModel?,
    actions: HeaderActions,
    below: FocusRequester?,
    modifier: Modifier = Modifier,
    /** Rightmost icon, where Down from the bottom of Home wraps to. */
    endFocus: FocusRequester? = null,
) {
    val metrics = HeaderMetrics(config.headerSize)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockBlock(clock, date, config, weather, metrics)
            Box(modifier = Modifier.weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                SideStage(config, nowPlaying, below, metrics)
            }
            StatusAndActions(net, config, actions, below, endFocus, metrics)
        }
        if (headlines.isNotEmpty()) {
            Spacer(modifier = Modifier.height(metrics.tickerGap))
            NewsTicker(headlines, metrics, config.tickerSpeed)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ClockBlock(
    clock: String,
    date: String,
    config: DisplayConfig,
    weather: Weather?,
    metrics: HeaderMetrics,
) {
    val line = infoLine(date, config, weather)
    val showClock = config.shows(HeaderElement.CLOCK)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (config.shows(HeaderElement.LOGO)) {
            Image(
                painter = painterResource(AppearanceStore.config.theme.markRes),
                contentDescription = "Buckler",
                modifier = Modifier.size(metrics.logo),
            )
            if (showClock || line.isNotEmpty()) Spacer(modifier = Modifier.width(metrics.clockGap))
        }
        Column {
            if (showClock) {
                Text(
                    text = clock,
                    color = ColorText,
                    fontSize = metrics.clock,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                )
            }
            if (line.isNotEmpty()) {
                Text(
                    text = line,
                    color = if (showClock) ColorMuted else ColorText,
                    fontSize = if (showClock) metrics.info else metrics.infoAlone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Mon 26 Oct · ☀ 14°"; the city stays out of the line to keep it short. Either half can be off. */
private fun infoLine(date: String, config: DisplayConfig, weather: Weather?): String {
    val parts = mutableListOf<String>()
    if (config.shows(HeaderElement.DATE)) parts += date
    if (config.weatherPlace != null) {
        parts += weather?.let {
            val (glyph, _) = WeatherClient.describe(it.code, it.isDay)
            "$glyph ${WeatherClient.formatTemperature(it.temperatureC, config.fahrenheit)}"
        } ?: "…"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun StatusAndActions(
    net: NetState,
    config: DisplayConfig,
    actions: HeaderActions,
    below: FocusRequester?,
    endFocus: FocusRequester?,
    metrics: HeaderMetrics,
) {
    val end = headerEndElement(config, hasStore = actions.onPlayStore != null)
    fun focusOf(element: HeaderElement) = if (element == end) endFocus else null
    val status = listOf(HeaderElement.NETWORK, HeaderElement.VPN).any(config::shows)
    val shortcuts = listOf(HeaderElement.EDIT, HeaderElement.STORE, HeaderElement.SETTINGS).any(config::shows)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (config.shows(HeaderElement.NETWORK)) {
            HeaderIconButton(
                icon = if (net.link == LinkType.ETHERNET) R.drawable.ic_ethernet else R.drawable.ic_wifi,
                description = networkDescription(net.link),
                dim = net.link == LinkType.NONE,
                size = metrics.icon,
                down = below,
                focusRequester = focusOf(HeaderElement.NETWORK),
            ) { actions.onNetwork(net.link) }
        }
        if (config.shows(HeaderElement.VPN)) {
            HeaderIconButton(
                icon = R.drawable.ic_vpn,
                description = vpnDescription(net),
                dim = !net.vpn,
                label = net.country?.takeIf { net.vpn },
                size = metrics.icon,
                down = below,
                focusRequester = focusOf(HeaderElement.VPN),
            ) { actions.onVpn() }
        }
        if (status && shortcuts) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .width(1.dp)
                    .height(metrics.dividerHeight)
                    .background(ColorHint.copy(alpha = 0.5f)),
            )
        }
        if (config.shows(HeaderElement.EDIT)) {
            HeaderIconButton(
                R.drawable.ic_edit, "Edit Home", size = metrics.icon, down = below,
                focusRequester = focusOf(HeaderElement.EDIT),
            ) { actions.onEditHome() }
        }
        if (config.shows(HeaderElement.STORE)) {
            actions.onPlayStore?.let { open ->
                HeaderIconButton(
                    R.drawable.ic_store, "Play Store", size = metrics.icon, down = below,
                    focusRequester = focusOf(HeaderElement.STORE),
                ) { open() }
            }
        }
        if (config.shows(HeaderElement.SETTINGS)) {
            HeaderIconButton(
                R.drawable.ic_settings, "Settings", size = metrics.icon, down = below,
                focusRequester = focusOf(HeaderElement.SETTINGS),
            ) { actions.onSettings() }
        }
    }
}

/** Rightmost icon the header shows, or null when it has none. */
fun headerEndElement(config: DisplayConfig, hasStore: Boolean): HeaderElement? =
    listOf(HeaderElement.NETWORK, HeaderElement.VPN, HeaderElement.EDIT, HeaderElement.STORE, HeaderElement.SETTINGS)
        .lastOrNull { config.shows(it) && (it != HeaderElement.STORE || hasStore) }

private fun vpnDescription(net: NetState): String = when {
    net.vpn && net.country != null -> "VPN connected, ${net.country}"
    net.vpn -> "VPN connected"
    else -> "VPN off"
}

private fun networkDescription(link: LinkType): String = when (link) {
    LinkType.WIFI -> "Wi-Fi connected"
    LinkType.ETHERNET -> "Ethernet connected"
    LinkType.OTHER -> "Network connected"
    LinkType.NONE -> "No network"
}

/**
 * The header's Side Stage: what is playing, with its controls; while nothing plays, the idle
 * choice (At a Glance or the companion) if one is set, else Now Playing's own hint as before.
 */
@Composable
private fun SideStage(config: DisplayConfig, nowPlaying: NowPlayingModel?, below: FocusRequester?, metrics: HeaderMetrics) {
    val playing = config.showsNowPlaying && nowPlaying?.info != null
    when {
        playing -> NowPlayingPanel(nowPlaying!!, below, metrics)
        config.sideIdle == SideIdle.GLANCE -> GlanceSide(config, refresh = null)
        config.sideIdle == SideIdle.COMPANION -> {
            val weather by rememberGlanceWeather(config.place)
            CompanionScene(
                kind = config.companion,
                weatherCode = weather?.now?.code,
                isDay = weather?.now?.isDay ?: true,
                // About as wide as the Now Playing panel it stands in for.
                modifier = Modifier.height(metrics.art + 24.dp).width(metrics.titleMax * 2),
            )
        }
        config.showsNowPlaying && nowPlaying != null -> NowPlayingPanel(nowPlaying, below, metrics)
    }
}
