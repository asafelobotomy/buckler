package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Covers for media seen playing, including while another app is in front. Hold OK to pin. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SuggestedStage(
    nowPlaying: NowPlayingModel,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    metrics: StageMetrics,
) {
    val context = LocalContext.current
    var cards by remember { mutableStateOf(MediaCards.load(context)) }
    val info = nowPlaying.info
    LaunchedEffect(info?.packageName, info?.title, info?.artUri) {
        cards = withContext(Dispatchers.IO) {
            if (info != null) MediaCards.remember(context, info)
            MediaCards.load(context)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("Suggested", "Hold OK to pin. OK opens the app.")
        if (cards.isEmpty()) {
            Text(
                text = "Nothing here yet. Play something and it will stay as a card.",
                color = ColorMuted,
                fontSize = 15.sp,
                modifier = Modifier
                    .focusRequester(stageFocus)
                    .downTo(onDown)
                    .focusable(),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 16.dp),
            ) {
                itemsIndexed(cards, key = { _, card -> "${card.packageName}\n${card.title}" }) { index, card ->
                    MediaCover(
                        card = card,
                        focusRequester = if (index == 0) stageFocus else null,
                        onDown = onDown,
                        cardWidth = metrics.suggestedWidth,
                        cardHeight = metrics.suggestedHeight,
                        onOpen = { launchPackage(context, card.packageName, card.appLabel.ifBlank { card.title }) },
                        onPin = {
                            cards = MediaCards.togglePin(context, card)
                            val pinned = cards.firstOrNull { it.sameAs(card) }?.pinned == true
                            Toast.makeText(context, if (pinned) "Pinned" else "Unpinned", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MediaCover(
    card: MediaCard,
    focusRequester: FocusRequester?,
    onDown: () -> Unit,
    cardWidth: Dp,
    cardHeight: Dp,
    onOpen: () -> Unit,
    onPin: () -> Unit,
) {
    val context = LocalContext.current
    val menu = rememberTvMenuInteraction(onOpen = onOpen, onMenu = onPin)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val banner by produceState<ImageBitmap?>(null, card.packageName, card.artUri) {
        if (!card.artUri.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            CoverResolver.leanbackBanner(context, card.packageName)?.toBitmap(320, 180)?.asImageBitmap()
        }
    }
    var cardModifier = Modifier
        .width(cardWidth)
        .scale(focusZoom(focused, 1.06f))
        .downTo(onDown)
    if (focusRequester != null) cardModifier = cardModifier.focusRequester(focusRequester)
    cardModifier = cardModifier
        .focusable(interactionSource = interaction)
        .tvMenuKeys(menu)
        .pointerInput(card.packageName, card.title) {
            detectTapGestures(onLongPress = { menu.openMenu() }, onTap = { menu.open() })
        }
    Column(modifier = cardModifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) ColorBrand else ColorTile),
        ) {
            val fallback = banner
            if (!card.artUri.isNullOrBlank()) {
                AsyncImage(
                    model = card.artUri,
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallback != null) {
                Image(
                    bitmap = fallback,
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (card.pinned) {
                Text(
                    text = "Pinned",
                    color = ColorOnBrand,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ColorBrand)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = card.title,
            color = if (focused) ColorOnBrand else ColorText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Text(
            text = card.subtitle.ifBlank { card.appLabel },
            color = ColorMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
