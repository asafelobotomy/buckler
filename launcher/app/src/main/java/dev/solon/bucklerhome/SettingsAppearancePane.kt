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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
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

    Column {
        SectionTitle("Appearance")

        PickerTitle("Colour scheme")
        CardColumns { width ->
            AppTheme.entries.forEach { theme ->
                PreviewCard(
                    label = theme.label,
                    selected = theme == appearance.theme,
                    width = width,
                    onClick = { AppearanceStore.update(context) { it.copy(theme = theme) } },
                ) { ThemeMiniature(theme, appearance.highContrast) }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        PickerTitle("Font")
        CardColumns { width ->
            FontChoice.entries.forEach { font ->
                PreviewCard(
                    label = font.label,
                    selected = font == appearance.font,
                    width = width,
                    onClick = { AppearanceStore.update(context) { it.copy(font = font) } },
                ) { FontMiniature(font) }
            }
        }
    }
}

private val CardGap = 12.dp

/** Cards [columns] to a row, sized to share the page's width, so no empty band is left on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardColumns(columns: Int = 3, content: @Composable (width: Dp) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A little under a share each: room for the focus zoom of an end card.
        val width = (maxWidth - CardGap * (columns - 1)) / columns - 2.dp
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CardGap), verticalArrangement = Arrangement.spacedBy(CardGap)) {
            content(width)
        }
    }
}
