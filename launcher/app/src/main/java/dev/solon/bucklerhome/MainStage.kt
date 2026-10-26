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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage

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

/** Header ticker query, or a story for the stage when the ticker itself is off. */
fun stageNewsQuery(config: DisplayConfig, stage: MainStage): NewsQuery? {
    config.newsQuery()?.let { return it }
    if (stage != MainStage.NEWS) return null
    return NewsQuery(config.news ?: NewsSource.BBC_WORLD, false)
}

@Composable
fun MainStage(
    mode: MainStage,
    headlines: List<Headline>,
    forecast: Forecast?,
    place: Place?,
    fahrenheit: Boolean,
    use24h: Boolean,
    nowPlaying: NowPlayingModel,
    stageFocus: FocusRequester,
    below: FocusRequester,
) {
    when (mode) {
        MainStage.NEWS -> NewsStage(headlines)
        MainStage.NOW_PLAYING -> NowPlayingStage(nowPlaying, stageFocus, below)
        MainStage.WEATHER -> WeatherStage(forecast, place, fahrenheit, use24h)
        MainStage.SUGGESTED -> SuggestedStage(nowPlaying, stageFocus, below)
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
private fun NewsStage(headlines: List<Headline>) {
    val story = NewsFeed.featured(headlines)
    Column(modifier = Modifier.fillMaxWidth()) {
        StageHeading("News")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorTile),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (story == null) {
                    Text(text = "No story yet.", color = ColorMuted, fontSize = 16.sp)
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
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    story.summary?.let { summary ->
                        Text(
                            text = summary,
                            color = ColorMuted,
                            fontSize = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val image = story?.imageUrl
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = story.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(300.dp)
                        .height(176.dp),
                )
            }
        }
    }
}
