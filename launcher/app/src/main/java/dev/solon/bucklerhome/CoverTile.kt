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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

data class CoverSize(val width: Dp, val height: Dp, val artHeight: Dp)

fun coverSize(
    layout: CoverLayout,
    tileSize: TileSize = TileSize.MEDIUM,
    showLabels: Boolean = false,
): CoverSize {
    val (width, art) = when (layout) {
        // Square icon, 2:3 poster, 16:9 TV banner. The old sizes included a title band.
        CoverLayout.ICON -> 132.dp to 132.dp
        CoverLayout.POSTER -> 120.dp to 180.dp
        CoverLayout.BANNER -> 220.dp to 124.dp
    }
    val k = tileSize.scale
    val label = if (!showLabels) 0.dp else if (layout == CoverLayout.BANNER) 32.dp else 44.dp
    return CoverSize(width * k, art * k + label * k, art * k)
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
    /** Shelf circle, to the left of the first tile. */
    left: FocusRequester? = null,
    /** Main Stage, above the first shelf. */
    up: FocusRequester? = null,
) {
    val size = coverSize(layout, tileSize, showLabels)
    val bmp = remember(item.id, item.icon) {
        item.icon?.toBitmap(256, 256)?.asImageBitmap()
    }
    val bannerBmp = remember(item.id, item.banner) {
        item.banner?.toBitmap(480, 270)?.asImageBitmap()
    }
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
    if (left != null || up != null) {
        boxModifier = boxModifier.focusProperties {
            if (left != null) this.left = left
            if (up != null) this.up = up
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
                } else if (bannerBmp != null) {
                    Image(
                        bitmap = bannerBmp,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (showLabels) {
                Text(
                    text = item.title,
                    fontSize = if (tileSize == TileSize.SMALL) 11.sp else 12.sp,
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
