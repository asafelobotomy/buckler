package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage

data class CoverSize(val width: Dp, val height: Dp, val icon: Dp)

fun coverSize(layout: CoverLayout): CoverSize = when (layout) {
    CoverLayout.ICON -> CoverSize(132.dp, 148.dp, 64.dp)
    CoverLayout.POSTER -> CoverSize(120.dp, 190.dp, 72.dp)
    CoverLayout.BANNER -> CoverSize(220.dp, 124.dp, 48.dp)
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
) {
    val size = coverSize(layout)
    val bmp = remember(item.id, item.icon) {
        item.icon?.toBitmap(128, 128)?.asImageBitmap()
    }
    var tileModifier = modifier
        .width(size.width)
        .height(size.height)
        .pointerInput(item.id) {
            detectTapGestures(onLongPress = { onLongPress() })
        }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp &&
                (event.key == Key.Menu || event.key == Key.Info)
            ) {
                onLongPress()
                true
            } else {
                false
            }
        }
    if (focusRequester != null) {
        tileModifier = tileModifier.focusRequester(focusRequester)
    }

    Surface(
        onClick = onLaunch,
        modifier = tileModifier,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorTile,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val model = item.imageUrl ?: item.imageUri
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = item.title,
                        modifier = Modifier.size(size.icon),
                    )
                }
            }
            Text(
                text = item.title,
                fontSize = 12.sp,
                maxLines = if (layout == CoverLayout.BANNER) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroBanner(
    item: ShelfItem,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp = remember(item.id, item.icon) {
        item.icon?.toBitmap(192, 192)?.asImageBitmap()
    }
    Surface(
        onClick = onLaunch,
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorTile,
            focusedContainerColor = Color(0xFF2A3548),
            contentColor = ColorText,
            focusedContentColor = ColorText,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val model = item.imageUrl ?: item.imageUri
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = item.title,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(24.dp)
                        .size(96.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xCC0B0E14), Color(0x660B0E14), Color.Transparent),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
            ) {
                Text(text = item.title, fontSize = 26.sp, color = ColorText, maxLines = 1)
                item.subtitle?.let {
                    Text(text = it, fontSize = 14.sp, color = ColorMuted, maxLines = 1)
                }
            }
        }
    }
}
