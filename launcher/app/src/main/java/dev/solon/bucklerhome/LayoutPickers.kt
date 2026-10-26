package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private val CardGap = Arrangement.spacedBy(12.dp)

fun CoverLayout.label(): String = when (this) {
    CoverLayout.ICON -> "Icon"
    CoverLayout.POSTER -> "Poster"
    CoverLayout.BANNER -> "Banner"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PickerTitle(text: String) {
    Text(
        text = text,
        color = ColorSection,
        fontSize = 14.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/** Cover layout as three pictures of a Home screen, drawn at the chosen app size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutPicker(style: CoverLayout, tileSize: TileSize, onStyle: (CoverLayout) -> Unit) {
    Column {
        PickerTitle("Layout")
        FlowRow(horizontalArrangement = CardGap, verticalArrangement = CardGap) {
            CoverLayout.entries.forEach { option ->
                PreviewCard(
                    label = option.label(),
                    selected = option == style,
                    onClick = { onStyle(option) },
                ) { LayoutMiniature(option, tileSize) }
            }
        }
    }
}

/** App size (Small / Medium / Large) shown on the current layout, so the effect is visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TileSizePicker(style: CoverLayout, tileSize: TileSize, onSize: (TileSize) -> Unit) {
    Column {
        PickerTitle("App size")
        FlowRow(horizontalArrangement = CardGap, verticalArrangement = CardGap) {
            TileSize.entries.forEach { option ->
                PreviewCard(
                    label = option.label,
                    selected = option == tileSize,
                    onClick = { onSize(option) },
                ) { LayoutMiniature(style, option) }
            }
        }
    }
}

/** Both pickers, one under the other, plus the optional name under each tile. */
@Composable
fun LayoutAndSizePickers(
    style: CoverLayout,
    tileSize: TileSize,
    showLabels: Boolean,
    mainStage: MainStage,
    onStyle: (CoverLayout) -> Unit,
    onSize: (TileSize) -> Unit,
    onLabels: (Boolean) -> Unit,
    onStage: (MainStage) -> Unit,
) {
    Column {
        LayoutPicker(style, tileSize, onStyle)
        Spacer(modifier = Modifier.height(18.dp))
        TileSizePicker(style, tileSize, onSize)
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("App names")
        SettingsAction(
            label = if (showLabels) "Show under the artwork" else "Hidden",
            selected = showLabels,
            onClick = { onLabels(!showLabels) },
        )
        Spacer(modifier = Modifier.height(18.dp))
        MainStagePicker(mainStage, onStage)
    }
}

/** News, Now Playing, weather or suggested media in the band under the header. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainStagePicker(mode: MainStage, onMode: (MainStage) -> Unit) {
    Column {
        PickerTitle("Main stage")
        FlowRow(horizontalArrangement = CardGap, verticalArrangement = CardGap) {
            MainStage.entries.forEach { option ->
                SettingsAction(option.label, selected = option == mode) { onMode(option) }
            }
        }
    }
}
