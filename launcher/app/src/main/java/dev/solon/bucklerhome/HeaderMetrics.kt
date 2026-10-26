package dev.solon.bucklerhome

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sizes for one header row. Thin / medium / large scale every widget by the same factor. */
class HeaderMetrics(size: HeaderSize) {
    private val scale = size.scale
    private fun d(base: Int): Dp = (base * scale).dp
    private fun t(base: Int): TextUnit = (base * scale).sp

    val logo = d(40)
    val clock = t(34)
    val info = t(14)
    val infoAlone = t(18)
    val icon = d(44)
    val dividerHeight = d(22)
    val ticker = t(14)
    val markHeight = d(18)
    val tickerGap = d(6)
    val art = d(46)
    val artGlyph = d(24)
    val playingTitle = t(15)
    val playingSubtitle = t(12)
    val control = d(38)
    val titleMax = d(200)
    val clockGap = d(14)
}
