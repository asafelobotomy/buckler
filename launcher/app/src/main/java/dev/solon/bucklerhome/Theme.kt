package dev.solon.bucklerhome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

val LocalPalette = staticCompositionLocalOf { Palettes.of(AppTheme.BUCKLER) }

// The accessors below keep their original names, so screens stay theme-agnostic: they read the
// palette of the enclosing BucklerHomeTheme.
val HomeBgBrush: Brush
    @Composable @ReadOnlyComposable get() = LocalPalette.current.background

/** Accent colour (gold in the default theme). */
val ColorBrand: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.brand
val ColorOnBrand: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.onBrand
val ColorText: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.text
val ColorMuted: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val ColorHint: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.hint
val ColorSection: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.section
val ColorTile: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.tile
val ColorFooter: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.footer
val ColorRing: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.ring

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BucklerHomeTheme(content: @Composable () -> Unit) {
    val appearance = AppearanceStore.config
    val palette = remember(appearance.theme, appearance.highContrast) { Palettes.resolve(appearance) }
    val family = appearance.font.family()
    val system = LocalDensity.current
    // Scale text on top of the system font scale; layout (dp) sizes stay put.
    val density = remember(system.density, system.fontScale, appearance.textSize) {
        Density(system.density, system.fontScale * appearance.textSize.scale)
    }
    val scheme = if (palette.dark) {
        darkColorScheme(
            primary = palette.brand,
            onPrimary = palette.onBrand,
            surface = palette.footer,
            onSurface = palette.text,
            border = palette.border,
        )
    } else {
        lightColorScheme(
            primary = palette.brand,
            onPrimary = palette.onBrand,
            surface = palette.footer,
            onSurface = palette.text,
            border = palette.border,
        )
    }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalDensity provides density,
        LocalBringIntoViewSpec provides ScrollOnlyAsNeeded,
    ) {
        MaterialTheme(colorScheme = scheme) {
            ProvideTextStyle(TextStyle(fontFamily = family)) {
                content()
            }
        }
    }
}

/** For a list that places its focused item itself (Home's shelves): focus never scrolls it. */
@OptIn(ExperimentalFoundationApi::class)
internal object NoFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Scroll a list only as far as it takes to show the focused item, as Compose did before 1.7.
 * On TV devices Compose now moves the focused item toward a fixed pivot, which slid Home's
 * shelves up under the stage and hid the heading of the shelf above.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object ScrollOnlyAsNeeded : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f // already fully shown
            offset < 0 && trailing > containerSize -> 0f // larger than the list: leave it
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}
