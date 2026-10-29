package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

data class CoverSize(val width: Dp, val height: Dp, val artHeight: Dp)

/** Tile name size: a little smaller on the small tiles. */
fun labelFontSize(tileSize: TileSize): TextUnit =
    if (tileSize == TileSize.SMALL || tileSize == TileSize.THIN) 11.sp else 12.sp

/**
 * Room for a tile's name: two lines (one on a banner) at the current text size, plus padding.
 * It follows the text, not the tile, so a Thin tile or Extra large text never cuts a name off.
 */
fun labelHeight(layout: CoverLayout, tileSize: TileSize, density: Density): Dp {
    val lines = if (layout == CoverLayout.BANNER) 1 else 2
    return with(density) { labelFontSize(tileSize).toDp() } * 1.3f * lines + 8.dp
}

/** [density] sizes the name under the tile; pass Compose's own (it carries the text size) when [showLabels]. */
fun coverSize(
    layout: CoverLayout,
    tileSize: TileSize = TileSize.MEDIUM,
    showLabels: Boolean = false,
    density: Density = Density(1f),
): CoverSize {
    val (width, art) = when (layout) {
        // Square icon, 2:3 poster, 16:9 TV banner.
        CoverLayout.ICON -> 132.dp to 132.dp
        // Large (210 dp tall) still fits a whole shelf under the Main Stage on a 1080p TV.
        CoverLayout.POSTER -> 112.dp to 168.dp
        CoverLayout.BANNER -> 220.dp to 124.dp
    }
    val k = tileSize.scale
    // Thin slims banners into a strip; a square icon or a poster squashed the same way loses its edges.
    val h = if (layout == CoverLayout.BANNER) tileSize.heightScale else k
    val label = if (showLabels) labelHeight(layout, tileSize, density) else 0.dp
    return CoverSize(width * k, art * h + label, art * h)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CoverTile(
    item: ShelfItem,
    layout: CoverLayout,
    onLaunch: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    moving: Boolean = false,
    tileSize: TileSize = TileSize.MEDIUM,
    showLabels: Boolean = false,
    /** Left at the first tile reveals the shelf circle; it is not on screen until then. */
    onLeftEdge: (() -> Unit)? = null,
    /** Main Stage, above the first shelf. */
    up: FocusRequester? = null,
) {
    val size = coverSize(layout, tileSize, showLabels, LocalDensity.current)
    val menu = rememberTvMenuInteraction(onOpen = onLaunch, onMenu = onLongPress)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    AnnounceOnFocus(focused, item.title)

    var boxModifier = modifier
        .width(size.width)
        .height(size.height)
        .scale(focusZoom(focused, 1.08f))
        .clip(shape)
        .background(if (focused || moving) ColorBrand else ColorTile)
        .strongFocusRing(focused, shape)
    if (moving) {
        boxModifier = boxModifier.border(3.dp, ColorText, RoundedCornerShape(8.dp))
    }
    if (focusRequester != null) {
        boxModifier = boxModifier.focusRequester(focusRequester)
    }
    if (up != null) {
        boxModifier = boxModifier.focusProperties { this.up = up }
    }
    if (onLeftEdge != null) {
        boxModifier = boxModifier.onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown && !event.isKeyRepeat()) onLeftEdge()
            true
        }
    }
    boxModifier = boxModifier
        .focusable(interactionSource = interactionSource)
        .tvMenuKeys(menu)
        .pointerInput(item.id) {
            detectTapGestures(
                onLongPress = { menu.openMenu() },
                onTap = { menu.open() },
            )
        }

    Box(modifier = boxModifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size.artHeight),
                contentAlignment = Alignment.Center,
            ) {
                val model = item.imageUrl ?: item.imageUri
                var remoteFailed by remember(item.id, model) { mutableStateOf(false) }
                val scale = if (item.coverFit) ContentScale.Fit else ContentScale.Crop
                if (model != null && !remoteFailed) {
                    AsyncImage(
                        model = model,
                        contentDescription = item.title,
                        contentScale = scale,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) {
                                remoteFailed = true
                            }
                        },
                    )
                } else {
                    // The app's own banner, else its icon, drawn without a bitmap copy.
                    val art = remember(item.id, item.banner, item.icon) {
                        (item.banner ?: item.icon)?.let(::DrawablePainter)
                    }
                    // Icon-pack icons are whole symbols: fit them, and keep themed ones readable on focus.
                    val pack = (item.icon as? PackIconDrawable)?.takeIf { item.banner == null }
                    if (art != null) {
                        Image(
                            painter = art,
                            contentDescription = item.title,
                            contentScale = if (pack != null) ContentScale.Fit else ContentScale.Crop,
                            colorFilter = if (pack?.themed == true) {
                                ColorFilter.tint(if (focused || moving) ColorOnBrand else ColorText)
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (showLabels) {
                Text(
                    text = item.title,
                    fontSize = labelFontSize(tileSize),
                    color = if (focused || moving) ColorOnBrand else ColorText,
                    maxLines = if (layout == CoverLayout.BANNER) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (moving) {
            Text(
                text = "◀  ▶",
                fontSize = 14.sp,
                color = ColorOnBrand,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            )
        }
    }
}
