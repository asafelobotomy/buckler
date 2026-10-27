package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** The band under the header. One of these is on screen at a time. */
enum class MainStage(val label: String) {
    NEWS("News"),
    NOW_PLAYING("Now Playing"),
    WEATHER("Weather"),
    SUGGESTED("Suggested"),
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): MainStage =
            entries.firstOrNull { it.wire() == raw?.lowercase() } ?: NEWS
    }
}

/** Ticker query, and the same feeds for the News stage even when the header is hidden. */
fun stageNewsQuery(config: DisplayConfig, stage: MainStage): NewsQuery? = when {
    config.newsSources.isEmpty() -> null
    stage == MainStage.NEWS || config.showsNews -> NewsQuery(config.newsSources)
    else -> null
}

@Composable
fun MainStage(
    mode: MainStage,
    headlines: List<Headline>,
    newsSources: List<NewsSource>,
    newsPace: NewsPace,
    forecast: Forecast?,
    place: Place?,
    fahrenheit: Boolean,
    use24h: Boolean,
    nowPlaying: NowPlayingModel,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onOpenStory: (Headline) -> Unit,
    metrics: StageMetrics,
) {
    when (mode) {
        MainStage.NEWS -> NewsStage(headlines, newsSources, newsPace, stageFocus, onDown, onOpenStory, metrics)
        MainStage.NOW_PLAYING -> NowPlayingStage(nowPlaying, stageFocus, onDown, metrics)
        MainStage.WEATHER -> WeatherStage(forecast, place, fahrenheit, use24h, stageFocus, onDown, metrics)
        MainStage.SUGGESTED -> SuggestedStage(nowPlaying, stageFocus, onDown, metrics)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StageHeading(title: String, note: String? = null) {
    Text(
        text = title,
        color = ColorSection,
        fontSize = 14.sp,
        modifier = Modifier.padding(bottom = if (note == null) 8.dp else 0.dp),
    )
    if (note != null) {
        Text(
            text = note,
            color = ColorHint,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NewsStage(
    headlines: List<Headline>,
    newsSources: List<NewsSource>,
    pace: NewsPace,
    stageFocus: FocusRequester,
    onDown: () -> Unit,
    onOpenStory: (Headline) -> Unit,
    metrics: StageMetrics,
) {
    val pool = remember(headlines) { NewsFeed.stagePool(headlines) }
    var index by remember(pool) { mutableIntStateOf(0) }
    LaunchedEffect(pool, pace) {
        if (pool.size < 2) return@LaunchedEffect
        while (true) {
            delay(pace.millis)
            index = (index + 1) % pool.size
        }
    }
    val story = pool.getOrNull(index.coerceIn(0, (pool.size - 1).coerceAtLeast(0)))
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("News")
        Row(
            modifier = Modifier
                .stageTarget(
                    focus = stageFocus,
                    onDown = onDown,
                    description = story?.title ?: "News",
                ) {
                    if (story != null) onOpenStory(story)
                }
                .fillMaxWidth()
                .height(metrics.cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 22.dp, vertical = if (metrics.cardHeight < 130.dp) 8.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (story == null) {
                    Text(
                        text = if (newsSources.isEmpty()) {
                            "Choose news sources in Settings → Header."
                        } else {
                            "No story yet."
                        },
                        color = ColorMuted,
                        fontSize = 16.sp,
                    )
                } else {
                    Text(
                        text = story.source.mark,
                        color = ColorSection,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = story.title,
                        color = ColorText,
                        fontSize = if (metrics.cardHeight < 130.dp) 18.sp else 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    story.summary?.let { summary ->
                        Text(
                            text = summary,
                            color = ColorMuted,
                            fontSize = 14.sp,
                            maxLines = if (metrics.cardHeight < 130.dp) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val image = story?.imageUrl
            if (image != null) {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(image)
                        .size(NewsFeed.NEWS_IMAGE_PX, NewsFeed.NEWS_IMAGE_PX)
                        .build(),
                    contentDescription = story.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(metrics.artWidth)
                        .height(metrics.cardHeight),
                )
            }
        }
    }
}
