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

/** Settings → Appearance: colour scheme, font, Home layout and app size, each shown as a picture. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearancePane() {
    val context = LocalContext.current
    val appearance = AppearanceStore.config
    var layout by remember { mutableStateOf(HomeLayout.load(context)) }
    val flow = Arrangement.spacedBy(12.dp)

    fun persist(next: HomeLayout) {
        layout = next
        HomeLayout.save(context, next)
    }

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

        Spacer(modifier = Modifier.height(20.dp))
        LayoutAndSizePickers(
            style = layout.layout,
            tileSize = layout.tileSize,
            onStyle = { persist(layout.copy(layout = it)) },
            onSize = { persist(layout.copy(tileSize = it)) },
        )
    }
}
