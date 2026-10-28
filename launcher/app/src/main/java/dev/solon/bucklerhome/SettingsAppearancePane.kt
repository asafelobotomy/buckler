package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Settings → Appearance: colour scheme and font, each shown as a picture; tile layout is in Edit Home. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearancePane() {
    val context = LocalContext.current
    val appearance = AppearanceStore.config
    val flow = Arrangement.spacedBy(12.dp)

    Column {
        SectionTitle("Appearance")

        PickerTitle("Colour scheme")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            AppTheme.entries.forEach { theme ->
                PreviewCard(
                    label = theme.label,
                    selected = theme == appearance.theme,
                    onClick = { AppearanceStore.update(context) { it.copy(theme = theme) } },
                ) { ThemeMiniature(theme, appearance.highContrast) }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        PickerTitle("Font")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            FontChoice.entries.forEach { font ->
                PreviewCard(
                    label = font.label,
                    selected = font == appearance.font,
                    onClick = { AppearanceStore.update(context) { it.copy(font = font) } },
                ) { FontMiniature(font) }
            }
        }

        // Tile layout, app size and names live in Edit Home; the stage has its own pane.
        Spacer(modifier = Modifier.height(20.dp))
        PickerTitle("Home layout")
        SettingsRow("Icon, poster or banner tiles, app size and names are in Edit Home.")
        SettingsAction("Edit Home") { openEditHome(context) }
    }
}
