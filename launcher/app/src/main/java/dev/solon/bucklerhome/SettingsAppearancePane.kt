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

/**
 * Settings → Appearance → Global: colour scheme and font, each shown as a picture. Each part of
 * Home (Header, Main Stage, Shelves) has its own page in Appearance's sidebar.
 */
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
    }
}
