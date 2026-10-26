package dev.solon.bucklerhome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val IconSlot = 44.dp
private const val HEADLINE_ROTATE_MS = 9_000L

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
    headlines: List<String>,
    net: NetState,
    nowPlaying: NowPlayingModel?,
    actions: HeaderActions,
    below: FocusRequester?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockBlock(clock, date, config, weather)
            Box(modifier = Modifier.weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                if (config.showsNowPlaying && nowPlaying != null) NowPlayingPanel(nowPlaying, below)
            }
            StatusAndActions(net, config, actions, below)
        }
        if (headlines.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            NewsTicker(headlines)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ClockBlock(clock: String, date: String, config: DisplayConfig, weather: Weather?) {
    val line = infoLine(date, config, weather)
    val showClock = config.shows(HeaderElement.CLOCK)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (config.shows(HeaderElement.LOGO)) {
            Image(
                painter = painterResource(AppearanceStore.config.theme.markRes),
                contentDescription = "Buckler",
                modifier = Modifier.size(40.dp),
            )
            if (showClock || line.isNotEmpty()) Spacer(modifier = Modifier.width(14.dp))
        }
        Column {
            if (showClock) {
                Text(
                    text = clock,
                    color = ColorText,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                )
            }
            if (line.isNotEmpty()) {
                Text(
                    text = line,
                    color = if (showClock) ColorMuted else ColorText,
                    fontSize = if (showClock) 14.sp else 18.sp,
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
private fun StatusAndActions(net: NetState, config: DisplayConfig, actions: HeaderActions, below: FocusRequester?) {
    val status = listOf(HeaderElement.NETWORK, HeaderElement.VPN).any(config::shows)
    val shortcuts = listOf(HeaderElement.EDIT, HeaderElement.STORE, HeaderElement.SETTINGS).any(config::shows)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (config.shows(HeaderElement.NETWORK)) {
            HeaderIconButton(
                icon = if (net.link == LinkType.ETHERNET) R.drawable.ic_ethernet else R.drawable.ic_wifi,
                description = networkDescription(net.link),
                dim = net.link == LinkType.NONE,
                size = IconSlot,
                down = below,
            ) { actions.onNetwork(net.link) }
        }
        if (config.shows(HeaderElement.VPN)) {
            HeaderIconButton(
                icon = R.drawable.ic_vpn,
                description = if (net.vpn) "VPN connected" else "VPN off",
                dim = !net.vpn,
                size = IconSlot,
                down = below,
            ) { actions.onVpn() }
        }
        if (status && shortcuts) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .width(1.dp)
                    .height(22.dp)
                    .background(ColorHint.copy(alpha = 0.5f)),
            )
        }
        if (config.shows(HeaderElement.EDIT)) {
            HeaderIconButton(R.drawable.ic_edit, "Edit Home", size = IconSlot, down = below) { actions.onEditHome() }
        }
        if (config.shows(HeaderElement.STORE)) {
            actions.onPlayStore?.let { open ->
                HeaderIconButton(R.drawable.ic_store, "Play Store", size = IconSlot, down = below) { open() }
            }
        }
        if (config.shows(HeaderElement.SETTINGS)) {
            HeaderIconButton(R.drawable.ic_settings, "Settings", size = IconSlot, down = below) { actions.onSettings() }
        }
    }
}

private fun networkDescription(link: LinkType): String = when (link) {
    LinkType.WIFI -> "Wi-Fi connected"
    LinkType.ETHERNET -> "Ethernet connected"
    LinkType.OTHER -> "Network connected"
    LinkType.NONE -> "No network"
}

/** One dim line under the header. Scrolls, or rotates headline by headline with reduced motion. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun NewsTicker(headlines: List<String>) {
    if (AppearanceStore.config.reduceMotion) {
        var index by remember(headlines) { mutableIntStateOf(0) }
        LaunchedEffect(headlines) {
            while (true) {
                delay(HEADLINE_ROTATE_MS)
                index = (index + 1) % headlines.size
            }
        }
        Text(
            text = headlines[index % headlines.size],
            color = ColorSection,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Text(
        text = headlines.joinToString("   •   "),
        color = ColorSection,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(iterations = Int.MAX_VALUE, spacing = MarqueeSpacing(64.dp), velocity = 42.dp),
    )
}
