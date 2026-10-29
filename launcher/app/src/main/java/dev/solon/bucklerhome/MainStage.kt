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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** The band under the header. One of these is on screen at a time. */
enum class MainStage(val label: String) {
    NEWS("News"),
    NOW_PLAYING("Now Playing"),
    LYRICS("Lyrics"),
    WEATHER("Weather"),
    SUGGESTED("Suggested"),
    GLANCE("At a Glance"),
    CALENDAR("Calendar"),
    WEATHER_MAP("Weather map"),
    COMPANION("Companion"),
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): MainStage =
            entries.firstOrNull { it.wire() == raw?.lowercase() } ?: NEWS
    }
}

/** The News stage's feeds (the ticker's while News is synced), fetched even when the header is hidden. */
fun stageNewsQuery(config: DisplayConfig, stage: MainStage): NewsQuery? =
    if (stage == MainStage.NEWS && config.stageNews.isNotEmpty()) NewsQuery(config.stageNews) else null

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
    onHold: () -> Unit,
    onPickStage: (MainStage) -> Unit,
    onOptionsChanged: () -> Unit,
    metrics: StageMetrics,
    weatherSpan: WeatherSpan,
    companion: CompanionKind,
) {
    when (mode) {
        MainStage.NEWS -> NewsStage(headlines, newsSources, newsPace, stageFocus, onDown, onOpenStory, onHold, metrics)
        MainStage.NOW_PLAYING -> NowPlayingStage(nowPlaying, stageFocus, onDown, onHold, metrics)
        MainStage.LYRICS -> LyricsStage(nowPlaying, stageFocus, onDown, onHold, metrics)
        MainStage.WEATHER -> WeatherStage(forecast, place, fahrenheit, use24h, stageFocus, onDown, weatherSpan, onHold)
        MainStage.SUGGESTED -> SuggestedStage(
            nowPlaying, stageFocus, onDown, onPickStage, onOptionsChanged, metrics,
        )
        MainStage.GLANCE -> GlanceStage(place, fahrenheit, use24h, stageFocus, onDown, onHold, metrics)
        MainStage.CALENDAR -> CalendarStage(use24h, stageFocus, onDown, onHold, metrics)
        MainStage.WEATHER_MAP -> WeatherMapStage(place, stageFocus, onDown, onHold, metrics)
        MainStage.COMPANION -> CompanionStage(companion, place, stageFocus, onDown, onHold, metrics)
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
    onHold: () -> Unit,
    metrics: StageMetrics,
) {
    val context = LocalContext.current
    val pool = remember(headlines) { NewsFeed.stagePool(headlines) }
    var index by remember { mutableIntStateOf(0) }
    // New headlines keep the story on screen (wherever it now sits) rather than jumping to the first.
    var shownTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pool) {
        index = pool.indexOfFirst { it.title == shownTitle }.coerceAtLeast(0)
    }
    // Bumped by ◀ ▶ so the rotation timer restarts and a picked story stays its full time.
    var stepped by remember { mutableIntStateOf(0) }
    LaunchedEffect(pool, pace, stepped) {
        if (pool.size < 2) return@LaunchedEffect
        while (true) {
            delay(pace.millis)
            index = (index + 1) % pool.size
        }
    }
    val story = pool.getOrNull(index.coerceIn(0, (pool.size - 1).coerceAtLeast(0)))
    LaunchedEffect(story?.title) {
        shownTitle = story?.title
        // The next story's picture, loaded now so it shows the moment the story turns.
        val next = pool.getOrNull((index + 1) % pool.size.coerceAtLeast(1))?.imageUrl ?: return@LaunchedEffect
        context.imageLoader.enqueue(ImageRequest.Builder(context).data(next).size(NewsFeed.NEWS_IMAGE_PX, NewsFeed.NEWS_IMAGE_PX).build())
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("News")
        Row(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val step = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown && pool.size > 1) {
                        index = (index + step).mod(pool.size)
                        stepped++
                    }
                    true
                }
                .stageTarget(
                    focus = stageFocus,
                    onDown = onDown,
                    description = story?.title ?: "News",
                    onLongPress = onHold,
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
                            "Choose news sources in Settings → Appearance → Main Stage."
                        } else {
                            "No story yet."
                        },
                        color = ColorMuted,
                        fontSize = 16.sp,
                    )
                } else {
                    Text(
                        text = if (pool.size > 1) {
                            "${story.source.mark}  ·  ${index % pool.size + 1} of ${pool.size}  ◀ ▶"
                        } else {
                            story.source.mark
                        },
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
