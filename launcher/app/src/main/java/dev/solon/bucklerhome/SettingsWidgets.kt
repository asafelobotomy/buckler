package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings → Widgets: each widget's own page, wherever on Home it shows (the header, its Side
 * Stage, the Main Stage). Header and Main Stage, under Appearance, only choose which widget and
 * how big. News and Weather can keep one set of settings for both places or one each.
 */
@Composable
fun WidgetsPane() {
    val context = LocalContext.current
    val cfg = remember { DisplayPrefs.load(context) }
    val stage = remember { HomeLayout.stored(context).mainStage }
    SectionTitle("Widgets")
    SettingsRow(
        "What each widget shows, wherever it is on Home. Appearance → Header and Main Stage choose which " +
            "widgets are where.",
    )
    val header = HeaderElement.entries.filter { cfg.shows(it) }.map { it.label } +
        listOfNotNull("News ticker".takeIf { cfg.showsNews }, "Now Playing".takeIf { cfg.headerVisible && cfg.nowPlaying })
    SettingsRow(if (cfg.headerVisible) "Header: ${header.joinToString(", ")}." else "Header: off.")
    SettingsRow("Main Stage: ${stage.label}.")
    Spacer(modifier = Modifier.height(12.dp))
    MediaAccessSection()
}

/** Settings → Widgets → Weather: the city and units, for the header and each weather stage. */
@Composable
fun WeatherPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    SectionTitle("Weather")
    SettingsRow("For the header's weather and the Weather, Weather map, At a Glance and Companion stages (Open-Meteo).")
    SettingsToggle("Same city in header and Main Stage", cfg.weatherSynced) {
        DisplayPrefs.setWeatherSynced(context, !cfg.weatherSynced, from = HomeSection.HEADER)
        tick++
    }
    if (cfg.weatherSynced) {
        WeatherFields(HomeSection.HEADER, cfg) { tick++ }
    } else {
        Spacer(modifier = Modifier.height(12.dp))
        PickerTitle("Header")
        WeatherFields(HomeSection.HEADER, cfg) { tick++ }
        Spacer(modifier = Modifier.height(12.dp))
        PickerTitle("Main Stage")
        WeatherFields(HomeSection.MAIN_STAGE, cfg) { tick++ }
    }
    Spacer(modifier = Modifier.height(12.dp))
    SettingsChoice("Weather stage shows", WeatherSpan.entries, cfg.weatherSpan, { it.label }) {
        DisplayPrefs.setWeatherSpan(context, it)
        tick++
    }
}

/** City and units for [section]'s weather; the header's are the shared ones while synced. */
@Composable
private fun WeatherFields(section: HomeSection, cfg: DisplayConfig, onChanged: () -> Unit) {
    val context = LocalContext.current
    val shared = section == HomeSection.HEADER || cfg.weatherSynced
    val place = if (shared) cfg.place else cfg.stagePlace
    val fahrenheit = if (shared) cfg.fahrenheit else cfg.stageFahrenheit
    var pickCity by remember { mutableStateOf(false) }

    fun setPlace(next: Place?) {
        if (shared) DisplayPrefs.setPlace(context, next) else DisplayPrefs.setStagePlace(context, next)
        onChanged()
    }

    SettingsAction("City", value = place?.name ?: "Not set") { pickCity = true }
    if (place == null) {
        SettingsRow("Weather needs a city.")
    } else {
        SettingsChoice("Units", listOf(false, true), fahrenheit, { if (it) "°F" else "°C" }) {
            if (shared) DisplayPrefs.setFahrenheit(context, it) else DisplayPrefs.setStageFahrenheit(context, it)
            onChanged()
        }
        SettingsAction("Clear city") { setPlace(null) }
    }
    if (pickCity) {
        WeatherLocationDialog(
            onDismiss = { pickCity = false },
            onPicked = { picked ->
                pickCity = false
                setPlace(picked)
            },
        )
    }
}

/** Settings → Widgets → News: pace and sources for the header's ticker and the News stage. */
@Composable
fun NewsPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    SectionTitle("News")
    SettingsRow("Headlines for the header's ticker (a dim line under it) and the News stage. No sources turns one off.")
    SettingsChoice("Ticker speed", TickerSpeed.entries, cfg.tickerSpeed, { it.label }) {
        DisplayPrefs.setTickerSpeed(context, it)
        tick++
    }
    SettingsChoice(
        "News stage changes story",
        NewsPace.entries,
        cfg.newsPace,
        { it.label },
        note = "◀ ▶ on the stage step through stories too.",
    ) {
        DisplayPrefs.setNewsPace(context, it)
        tick++
    }
    SettingsChoice(
        "Check for new stories",
        NewsRefresh.entries,
        cfg.newsRefresh,
        { it.label },
        note = "Only while Home is on screen; an unchanged feed costs a short reply, not a download.",
    ) {
        DisplayPrefs.setNewsRefresh(context, it)
        tick++
    }
    SettingsToggle("Same sources in ticker and stage", cfg.newsSynced) {
        DisplayPrefs.setNewsSynced(context, !cfg.newsSynced, from = HomeSection.HEADER)
        tick++
    }
    if (cfg.newsSynced) {
        Spacer(modifier = Modifier.height(12.dp))
        NewsSources(HomeSection.HEADER, cfg) { tick++ }
    } else {
        Spacer(modifier = Modifier.height(12.dp))
        PickerTitle("Ticker")
        NewsSources(HomeSection.HEADER, cfg) { tick++ }
        Spacer(modifier = Modifier.height(12.dp))
        PickerTitle("News stage")
        NewsSources(HomeSection.MAIN_STAGE, cfg) { tick++ }
    }
}

/** [section]'s region and sources; each category is a list of its own, its sources ticked. */
@Composable
private fun NewsSources(section: HomeSection, cfg: DisplayConfig, onChanged: () -> Unit) {
    val context = LocalContext.current
    val shared = section == HomeSection.HEADER || cfg.newsSynced
    val sources = if (shared) cfg.newsSources else cfg.stageNewsSources
    val region = if (shared) cfg.newsRegion else cfg.stageNewsRegion
    SettingsRow(
        when {
            sources.isEmpty() -> "No sources: news is off here."
            sources.size <= 3 -> "Now: ${sources.joinToString(", ") { it.label }}."
            else -> "Now: ${sources.size} sources, one story from each in turn."
        },
    )
    SettingsChoice("Region", NewsRegion.entries, region, { it.label }) {
        if (shared) DisplayPrefs.setNewsRegion(context, it) else DisplayPrefs.setStageNewsRegion(context, it)
        onChanged()
    }
    NewsCategory.entries.forEach { category ->
        val chosen = sources.count { it.category == category }
        SettingsLink(category.label, value = if (chosen == 0) "None" else "$chosen on", title = "${category.label} news") {
            NewsCategoryList(section, category)
        }
    }
    if (sources.isNotEmpty()) {
        SettingsAction("Turn news off here") {
            if (shared) DisplayPrefs.setNewsSources(context, emptyList()) else DisplayPrefs.setStageNewsSources(context, emptyList())
            onChanged()
        }
    }
}

/** One category's sources, ticked when on; World lists those for the region (and any already on). */
@Composable
private fun NewsCategoryList(section: HomeSection, category: NewsCategory) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    val shared = section == HomeSection.HEADER || cfg.newsSynced
    val sources = if (shared) cfg.newsSources else cfg.stageNewsSources
    val region = if (shared) cfg.newsRegion else cfg.stageNewsRegion
    if (category == NewsCategory.WORLD) SettingsRow("For ${region.label}; Region changes which are listed.")
    NewsSource.entries.filter { source ->
        source.category == category && (category != NewsCategory.WORLD || region in source.regions || source in sources)
    }.forEach { source ->
        SettingsCheck(source.label, source in sources) {
            val next = NewsSource.entries.filter { if (it == source) it !in sources else it in sources }
            if (shared) DisplayPrefs.setNewsSources(context, next) else DisplayPrefs.setStageNewsSources(context, next)
            tick++
        }
    }
}

/**
 * Settings → Widgets → Now Playing & Lyrics: for the header's Side Stage and the Now Playing,
 * Lyrics and Suggested stages, which follow what is playing.
 */
@Composable
fun NowPlayingPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    SectionTitle("Now Playing & Lyrics")
    SettingsRow("What is playing, in the header's Side Stage and on the Now Playing, Lyrics and Suggested stages.")
    MediaAccessSection()
    ScrollTitlesToggle(cfg.scrollTitles) { tick++ }
    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Lyrics")
    SettingsRow(
        "Filled as they are sung, with a bouncing dot. Word timing comes from NetEase when it has the song, " +
            "otherwise LRCLIB lines with estimated words.",
    )
    val route = rememberAudioRoute()
    val offset = remember(tick, route.kind) { LyricsPrefs.offset(context, route.kind) }
    val songs = remember(tick) { LyricsPrefs.songOffsetCount(context) }
    SettingsRow(
        "Audio is going to ${route.kind.label}. Lyrics allow ${"%.1f".format(route.delayMs / 1000.0)} s for its " +
            "delay automatically; ◀ ▶ on the Lyrics stage nudge just the song playing.",
    )
    SettingsRow("${route.kind.label} fine-tune: ${LyricsPrefs.describe(offset)}.")
    SettingsAction("Earlier") {
        LyricsPrefs.setOffset(context, route.kind, offset + LyricsPrefs.STEP_MS)
        tick++
    }
    SettingsAction("Later") {
        LyricsPrefs.setOffset(context, route.kind, offset - LyricsPrefs.STEP_MS)
        tick++
    }
    if (offset != 0L) {
        SettingsAction("Reset") {
            LyricsPrefs.setOffset(context, route.kind, 0)
            tick++
        }
    }
    if (songs > 0) {
        SettingsAction("Clear $songs song nudge${if (songs == 1) "" else "s"}") {
            LyricsPrefs.clearSongOffsets(context)
            tick++
        }
    }
}

/** Settings → Widgets → Companion: which one the stages draw. */
@Composable
fun CompanionPane() {
    var tick by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    CompanionPicker(cfg.companion) { tick++ }
    Spacer(modifier = Modifier.height(12.dp))
    SettingsPaneLink("Weather", SettingsPane.WEATHER, value = cfg.place?.name)
}
