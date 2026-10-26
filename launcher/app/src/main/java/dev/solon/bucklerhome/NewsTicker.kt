package dev.solon.bucklerhome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * One dim line under the header. Each story is preceded by its source mark. Scrolls at the chosen
 * speed, or steps story by story when reduced motion is on.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun NewsTicker(headlines: List<Headline>, metrics: HeaderMetrics, speed: TickerSpeed) {
    if (headlines.isEmpty()) return
    if (AppearanceStore.config.reduceMotion) {
        SteppingTicker(headlines, metrics, speed)
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                spacing = MarqueeSpacing(64.dp),
                velocity = speed.velocityDp.dp,
            ),
    ) {
        headlines.forEach { headline ->
            Story(headline, metrics)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SteppingTicker(headlines: List<Headline>, metrics: HeaderMetrics, speed: TickerSpeed) {
    var index by remember(headlines) { mutableIntStateOf(0) }
    LaunchedEffect(headlines, speed) {
        while (true) {
            delay(speed.rotateMs)
            index = (index + 1) % headlines.size
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Story(headlines[index % headlines.size], metrics, ellipsis = true)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Story(headline: Headline, metrics: HeaderMetrics, ellipsis: Boolean = false) {
    SourceMark(headline.source, metrics)
    Text(
        text = headline.title,
        color = ColorSection,
        fontSize = metrics.ticker,
        maxLines = 1,
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = Modifier.padding(start = 6.dp, end = 28.dp),
    )
}

/** Short source name on a brand-coloured chip, drawn in front of the story. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceMark(source: NewsSource, metrics: HeaderMetrics) {
    val height = metrics.markHeight
    val shape = RoundedCornerShape(height * 0.22f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(height)
            .clip(shape)
            .background(Color(source.markColor))
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .padding(horizontal = height * 0.28f),
    ) {
        Text(
            text = source.mark,
            color = Color(source.onMark),
            fontSize = (height.value * 0.62f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
