package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Scale from real tile size to the picture; keeps relative proportions between layouts and sizes. */
private const val MINI_SCALE = 0.24f

private fun Modifier.rounded(color: Color, radius: Dp = 3.dp) =
    clip(RoundedCornerShape(radius)).background(color)

/** A tiny Home screen: header strip, main stage, shelf title and a row of real-proportion tiles. */
@Composable
fun BoxScope.LayoutMiniature(style: CoverLayout, tileSize: TileSize, palette: Palette = LocalPalette.current) {
    val tile = coverSize(style, tileSize)
    Column(
        modifier = Modifier.fillMaxSize().background(palette.bgTop).padding(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 22.dp, height = 6.dp).rounded(palette.text.copy(alpha = 0.85f)))
            Spacer(Modifier.weight(1f))
            repeat(3) {
                Box(Modifier.padding(start = 3.dp).size(6.dp).clip(CircleShape).background(palette.muted))
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).rounded(palette.brand.copy(alpha = 0.4f), 3.dp))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(width = 30.dp, height = 3.dp).rounded(palette.section))
        Spacer(Modifier.height(4.dp))
        // Unbounded width: the row runs off the edge like a real shelf instead of squeezing tiles.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
        ) {
            repeat(6) {
                MiniTile(style, tile.width * MINI_SCALE, tile.height * MINI_SCALE, palette)
            }
        }
    }
}

@Composable
private fun MiniTile(style: CoverLayout, width: Dp, height: Dp, palette: Palette) {
    Box(
        modifier = Modifier.size(width, height).rounded(palette.footer, 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (style == CoverLayout.ICON) {
            Box(Modifier.size(width * 0.5f).clip(CircleShape).background(palette.brand))
        } else {
            Box(
                Modifier.fillMaxSize().padding(2.dp).rounded(palette.brand.copy(alpha = 0.45f), 3.dp),
            )
        }
    }
}

/** The same mini Home screen painted in another theme's colours. */
@Composable
fun BoxScope.ThemeMiniature(theme: AppTheme, highContrast: Boolean) {
    val palette = Palettes.resolve(AppearanceConfig(theme = theme, highContrast = highContrast))
    Column(modifier = Modifier.fillMaxSize().background(palette.background).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource(theme.markRes), contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(width = 26.dp, height = 7.dp).rounded(palette.text))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(width = 18.dp, height = 4.dp).rounded(palette.muted))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(width = 34.dp, height = 3.dp).rounded(palette.section))
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            // The first tile is drawn "focused" to show the accent colour.
            Box(Modifier.size(width = 36.dp, height = 30.dp).rounded(palette.brand, 5.dp))
            repeat(3) { Box(Modifier.size(width = 36.dp, height = 30.dp).rounded(palette.tile, 5.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).rounded(palette.footer, 3.dp))
    }
}

/** A sample of the font, drawn in itself on the current theme. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.FontMiniature(font: FontChoice) {
    Column(
        modifier = Modifier.fillMaxSize().background(LocalPalette.current.bgTop),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Aa 21:30", fontFamily = font.family(), fontSize = 24.sp, fontWeight = FontWeight.Medium, color = ColorText)
        Text(text = "Netflix · YouTube", fontFamily = font.family(), fontSize = 12.sp, color = ColorMuted)
    }
}
