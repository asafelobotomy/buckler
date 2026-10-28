package dev.solon.bucklerhome

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** How far a focused tile grows past its edges (it zooms to 1.08), plus the focus ring. */
fun zoomRoom(size: CoverSize): Dp = size.height * 0.04f + 4.dp

/** A shelf title's line: 14 sp text with 10 dp under it. */
val SHELF_TITLE_SIZE: TextUnit = 14.sp

fun shelfTitleHeight(density: Density): Dp = with(density) { SHELF_TITLE_SIZE.toDp() } * 1.4f + 10.dp

/** One whole shelf as Home lays it out: title, tiles, and the zoom under the last row. */
fun shelfBlock(size: CoverSize, density: Density): Dp = shelfTitleHeight(density) + size.height + zoomRoom(size)

/**
 * Lays the content out [start] / [end] wider than its slot, reaching into the screen margin, so
 * a clip at its bounds (a scrolling row, a fade) still leaves room for a zoomed edge tile.
 * Give the content the same padding back so it lines up with what is above and below.
 */
fun Modifier.bleed(start: Dp, end: Dp = start): Modifier = layout { measurable, constraints ->
    val left = start.roundToPx()
    val extra = left + end.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-left, 0) }
}

/** Fades the bottom [height] to nothing, so a shelf peeking in below reads as "more" rather than cut off. */
fun Modifier.fadeBottom(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx()
        if (fade <= 0f) return@drawWithContent
        val top = size.height - fade
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = top, endY = size.height),
            topLeft = Offset(0f, top),
            blendMode = BlendMode.DstIn,
        )
    }
