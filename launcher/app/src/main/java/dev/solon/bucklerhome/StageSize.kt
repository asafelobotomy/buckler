package dev.solon.bucklerhome

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How much of the screen the band under the header may use. Shelves keep a row beneath it. */
enum class StageSize(val label: String, val fraction: Float) {
    SMALL("Small", 0.28f),
    MEDIUM("Medium", 0.36f),
    LARGE("Large", 0.46f),
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(raw: String?): StageSize =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MEDIUM
    }
}

/** Heights for a 16:9 TV. The band stops before it covers the first shelf's title and tiles. */
data class StageMetrics(
    val band: Dp,
    val cardHeight: Dp,
    val artWidth: Dp,
    val chipWidth: Dp,
    val hours: Int,
    val days: Int,
    val suggestedWidth: Dp,
    val suggestedHeight: Dp,
    val compact: Boolean,
)

fun stageMetrics(viewport: Dp, shelfArt: Dp, size: StageSize, headerVisible: Boolean): StageMetrics {
    val headerReserve = if (headerVisible) 110.dp else 8.dp
    val cap = (viewport - shelfArt - 72.dp - headerReserve).coerceAtLeast(128.dp)
    val band = (viewport * size.fraction).coerceIn(128.dp, cap)
    val room = (band - 40.dp).coerceAtLeast(96.dp)
    val card = when (size) {
        StageSize.SMALL -> 112.dp
        StageSize.MEDIUM -> 140.dp
        StageSize.LARGE -> 168.dp
    }.coerceAtMost(room)
    val days = when {
        size == StageSize.SMALL -> 0
        band >= 268.dp && size == StageSize.LARGE -> 5
        band >= 248.dp -> 4
        else -> 0
    }
    return StageMetrics(
        band = band,
        cardHeight = card,
        artWidth = (card * 1.7f).coerceAtMost(280.dp),
        chipWidth = when (size) {
            StageSize.SMALL -> 76.dp
            StageSize.MEDIUM -> 86.dp
            StageSize.LARGE -> 96.dp
        },
        hours = when (size) {
            StageSize.SMALL -> 5
            StageSize.MEDIUM -> 6
            StageSize.LARGE -> 8
        },
        days = days,
        suggestedWidth = when (size) {
            StageSize.SMALL -> 168.dp
            StageSize.MEDIUM -> 188.dp
            StageSize.LARGE -> 210.dp
        },
        suggestedHeight = when (size) {
            StageSize.SMALL -> 90.dp
            StageSize.MEDIUM -> 106.dp
            StageSize.LARGE -> 118.dp
        }.coerceAtMost(room),
        compact = size == StageSize.SMALL || band < 190.dp,
    )
}
