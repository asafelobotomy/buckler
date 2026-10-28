package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Widgets that appear in more than one part of Home (News: header ticker and News stage; Weather:
 * header and Weather stage) get the same section on each page. While "Sync across sections" is on
 * both pages edit one shared set of settings; off, each page edits its own.
 */
private fun HomeSection.other(widget: String) = when (this) {
    HomeSection.HEADER -> "the $widget stage"
    HomeSection.MAIN_STAGE -> "the header"
}

@Composable
private fun SyncToggle(synced: Boolean, section: HomeSection, widget: String, onToggle: () -> Unit) {
    SettingsRow(
        if (synced) {
            "Synced: changes here also change $widget in ${section.other(widget)}."
        } else {
            "Not synced: $widget here has its own settings. Syncing uses these in ${section.other(widget)} too."
        },
    )
    SettingsAction("Sync across sections", selected = synced, onClick = onToggle)
}

/** News sources and region for [section]'s News (ticker or stage), with the sync switch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewsSection(section: HomeSection, cfg: DisplayConfig, onChanged: () -> Unit) {
    val context = LocalContext.current
    val flow = Arrangement.spacedBy(12.dp)
    // The header always edits the shared set; the stage does too while synced.
    val shared = section == HomeSection.HEADER || cfg.newsSynced
    val sources = if (shared) cfg.newsSources else cfg.stageNewsSources
    val region = if (shared) cfg.newsRegion else cfg.stageNewsRegion

    fun setSources(next: List<NewsSource>) {
        val ordered = NewsSource.entries.filter { it in next }
        if (shared) DisplayPrefs.setNewsSources(context, ordered) else DisplayPrefs.setStageNewsSources(context, ordered)
        onChanged()
    }

    Column {
        SyncToggle(cfg.newsSynced, section, "News") {
            DisplayPrefs.setNewsSynced(context, !cfg.newsSynced, from = section)
            onChanged()
        }
        Spacer(modifier = Modifier.height(14.dp))
        SettingsRow(
            when {
                sources.isEmpty() -> "No sources: news is off here."
                sources.size <= 3 -> "Now: ${sources.joinToString(", ") { it.label }}."
                else -> "Now: ${sources.size} sources, one story from each in turn."
            },
        )
        PickerTitle("Region")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            NewsRegion.entries.forEach { option ->
                SettingsAction(option.label, selected = option == region) {
                    if (shared) DisplayPrefs.setNewsRegion(context, option) else DisplayPrefs.setStageNewsRegion(context, option)
                    onChanged()
                }
            }
        }
        NewsCategory.entries.forEach { category ->
            Spacer(modifier = Modifier.height(14.dp))
            PickerTitle(category.label)
            FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
                NewsSource.entries.filter { source ->
                    source.category == category &&
                        (category != NewsCategory.WORLD || region in source.regions || source in sources)
                }.forEach { source ->
                    SettingsAction(source.label, selected = source in sources) {
                        setSources(if (source in sources) sources - source else sources + source)
                    }
                }
            }
        }
        if (sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            SettingsAction("Turn news off here") { setSources(emptyList()) }
        }
    }
}

/** City and units for [section]'s weather (header element or Weather stage), with the sync switch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherSection(section: HomeSection, cfg: DisplayConfig, onChanged: () -> Unit) {
    val context = LocalContext.current
    val flow = Arrangement.spacedBy(12.dp)
    val shared = section == HomeSection.HEADER || cfg.weatherSynced
    val place = if (shared) cfg.place else cfg.stagePlace
    val fahrenheit = if (shared) cfg.fahrenheit else cfg.stageFahrenheit
    var pickCity by remember { mutableStateOf(false) }

    fun setPlace(next: Place?) {
        if (shared) DisplayPrefs.setPlace(context, next) else DisplayPrefs.setStagePlace(context, next)
        onChanged()
    }

    Column {
        SyncToggle(cfg.weatherSynced, section, "Weather") {
            DisplayPrefs.setWeatherSynced(context, !cfg.weatherSynced, from = section)
            onChanged()
        }
        Spacer(modifier = Modifier.height(14.dp))
        SettingsRow("City: ${place?.name ?: "not set — weather needs one"} (Open-Meteo)")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsAction(if (place == null) "Set city" else "Change city") { pickCity = true }
            if (place != null) {
                SettingsAction("Clear city") { setPlace(null) }
                SettingsAction("Units: ${if (fahrenheit) "°F" else "°C"}") {
                    if (shared) DisplayPrefs.setFahrenheit(context, !fahrenheit) else DisplayPrefs.setStageFahrenheit(context, !fahrenheit)
                    onChanged()
                }
            }
        }
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
