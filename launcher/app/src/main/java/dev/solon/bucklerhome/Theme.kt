package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val HomeBgBrush = Brush.verticalGradient(
    colors = listOf(Color(0xFF0B0E14), Color(0xFF161A12), Color(0xFF0B0E14)),
)

/** Mustard gold from the Buckler shield mark. */
val ColorBrand = Color(0xFFD4A84B)
val ColorOnBrand = Color(0xFF1A1408)
val ColorText = Color(0xFFE8ECF2)
val ColorMuted = Color(0xFF9AA6B8)
val ColorHint = Color(0xFF6B778A)
/** Forest rim accent from the shield. */
val ColorSection = Color(0xFF8FAE7A)
val ColorTile = Color(0xFF1C2433)
val ColorFooter = Color(0xFF243044)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BucklerHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ColorBrand,
            onPrimary = ColorOnBrand,
            surface = Color(0xFF12151C),
            onSurface = ColorText,
            border = Color(0xFF3A4558),
        ),
        content = content,
    )
}
