package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Settings → Appearance → Global: colour scheme and font, each shown as a picture, then the clock
 * and date (the header's, the screensaver clock's and weather times). Each part of Home (Header,
 * Main Stage, Shelves) has its own page in Appearance's list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearancePane() {
    val context = LocalContext.current
    val appearance = AppearanceStore.config
    var tick by remember { mutableIntStateOf(0) }
    val cfg = remember(tick) { DisplayPrefs.load(context) }
    // Store choices count only while installed; a removed one leaves the built-in choice in use.
    val storeTheme = appearance.storeTheme?.takeIf { StoreLibrary.hasTheme(it) }
    val storeFont = appearance.storeFont?.takeIf { StoreLibrary.hasFont(it) }

    Column {
        SectionTitle("Appearance")

        PickerTitle("Colour scheme")
        CardColumns { width ->
            AppTheme.entries.forEach { theme ->
                PreviewCard(
                    label = theme.label,
                    selected = theme == appearance.theme && storeTheme == null,
                    width = width,
                    onClick = { AppearanceStore.update(context) { it.copy(theme = theme, storeTheme = null) } },
                ) { ThemeMiniature(theme, appearance.highContrast) }
            }
            StoreLibrary.installedThemes().forEach { theme ->
                PreviewCard(
                    label = theme.label,
                    selected = theme.id == storeTheme,
                    width = width,
                    onClick = { AppearanceStore.update(context) { it.copy(storeTheme = theme.id) } },
                ) { PaletteMiniature(Palettes.resolve(appearance.copy(storeTheme = theme.id))) }
            }
        }
        SettingsPaneLink("More colour schemes in bucklerStore", SettingsPane.STORE_THEMES)

        Spacer(modifier = Modifier.height(20.dp))
        PickerTitle("Font")
        CardColumns { width ->
            FontChoice.entries.forEach { font ->
                PreviewCard(
                    label = font.label,
                    selected = font == appearance.font && storeFont == null,
                    width = width,
                    onClick = { AppearanceStore.update(context) { it.copy(font = font, storeFont = null) } },
                ) { FontMiniature(font) }
            }
            StoreLibrary.installedFonts().forEach { font ->
                val family = StoreLibrary.fontFamily(font.id) ?: return@forEach
                PreviewCard(
                    label = font.label,
                    selected = font.id == storeFont,
                    width = width,
                    onClick = { AppearanceStore.update(context) { it.copy(storeFont = font.id) } },
                ) { FontMiniature(family) }
            }
        }
        SettingsPaneLink("More fonts in bucklerStore", SettingsPane.STORE_FONTS)

        Spacer(modifier = Modifier.height(20.dp))
        PickerTitle("Clock and date")
        ClockChoice()
        SettingsChoice("Date", DateStyle.entries, cfg.dateStyle, { DisplayFormat.date(LocalDate.now(), it) }) {
            DisplayPrefs.setDateStyle(context, it)
            tick++
        }
        SettingsRow("Also the screensaver clock and weather times.")
    }
}

private val CardGap = 12.dp

/** Cards [columns] to a row, sized to share the page's width, so no empty band is left on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardColumns(columns: Int = 3, content: @Composable (width: Dp) -> Unit) {
    // Two to a row at most in the narrow Settings panel.
    val perRow = if (LocalSettingsPanel.current) minOf(columns, 2) else columns
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A little under a share each: room for the focus zoom of an end card.
        val width = (maxWidth - CardGap * (perRow - 1)) / perRow - 2.dp
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CardGap), verticalArrangement = Arrangement.spacedBy(CardGap)) {
            content(width)
        }
    }
}
