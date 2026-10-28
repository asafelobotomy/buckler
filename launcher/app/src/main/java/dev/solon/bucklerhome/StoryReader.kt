package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reading overlay for one story: the article text only, with no page ads or extra windows. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StoryReaderDialog(story: Headline, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var paragraphs by remember(story.link) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(story.link) {
        val link = story.link
        paragraphs = if (link == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { ArticleReader.fetch(link) }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ColorFooter)
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = story.source.mark,
                        color = ColorSection,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = story.title,
                        color = ColorText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsAction(
                        "Close",
                        modifier = Modifier.focusProperties { down = bodyFocus },
                        focusRequester = closeFocus,
                        onClick = onDismiss,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .focusRequester(bodyFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val delta = when (event.key) {
                                Key.DirectionDown -> if (scroll.value < scroll.maxValue) 320f else return@onPreviewKeyEvent false
                                Key.DirectionUp -> if (scroll.value > 0) -320f else return@onPreviewKeyEvent false
                                else -> return@onPreviewKeyEvent false
                            }
                            scope.launch { scroll.animateScrollBy(delta) }
                            true
                        }
                        .focusable(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val image = story.imageUrl
                    // A failed image would otherwise leave a 280dp blank above the text.
                    var imageFailed by remember(image) { mutableStateOf(false) }
                    if (image != null && !imageFailed) {
                        val context = LocalContext.current
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(image)
                                .size(NewsFeed.NEWS_IMAGE_PX, NewsFeed.NEWS_IMAGE_PX)
                                .build(),
                            contentDescription = story.title,
                            contentScale = ContentScale.Crop,
                            onError = { imageFailed = true },
                            modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    val body = paragraphs
                    when {
                        body == null -> Text(text = "…", color = ColorMuted, fontSize = 18.sp)
                        body.isEmpty() -> Text(
                            text = story.summary ?: "This story has no article text.",
                            color = ColorText,
                            fontSize = 18.sp,
                        )
                        else -> body.forEach { paragraph ->
                            Text(text = paragraph, color = ColorText, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { closeFocus.requestFocus() }
        }
    }
}
