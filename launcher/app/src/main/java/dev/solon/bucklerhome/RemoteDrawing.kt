package dev.solon.bucklerhome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private val BODY_X = 16.dp
private val LABEL_X = 220.dp
private val ROW_H = 32.dp
private val ROW_W = 440.dp
private val ROW_TOP = 8.dp
private const val DOT = 12f
private const val DPAD_RING = 40f

/**
 * [model]'s remote, drawn in the theme, with a line from each button to what it does (in the
 * manner of a game-controller mapping screen), and a small side view: the 2019 remote's back comes
 * to a corner edge, the others are flat. Focus moves down the labels; the focused button lights up.
 * A remote Buckler does not draw ([RemoteModel.drawn]) gets the labels alone.
 * [describe] gives each button's label text; [remapped] marks buttons that differ from default.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RemoteMapper(
    model: RemoteModel,
    describe: (RemoteButton) -> String,
    remapped: (RemoteButton) -> Boolean,
    requesters: Map<RemoteButton, FocusRequester>,
    onPick: (RemoteButton) -> Unit,
) {
    val rows = remember(model) { model.layout.sortedWith(compareBy({ it.y }, { it.x })) }
    var focused by remember { mutableStateOf<RemoteButton?>(null) }
    val brand = ColorBrand
    val tile = ColorTile
    val border = ColorFooter
    val muted = ColorMuted
    val labelX = if (model.drawn) LABEL_X else 0.dp
    Box(modifier = Modifier.height(ROW_TOP + ROW_H * rows.size).width(labelX + ROW_W)) {
        if (model.drawn) {
            Canvas(modifier = Modifier.size(LABEL_X, model.bodyHeight.dp)) {
                val bodyX = BODY_X.toPx()
                val bodySize = Size(model.bodyWidth.dp.toPx(), model.bodyHeight.dp.toPx())
                val corner = CornerRadius(model.bodyWidth.dp.toPx() * if (model.triangular) 0.46f else 0.3f)
                drawRoundRect(tile, Offset(bodyX, 0f), bodySize, corner)
                drawRoundRect(border, Offset(bodyX, 0f), bodySize, corner, style = Stroke(2f))
                drawSideView(model, Offset(bodyX + bodySize.width + 22.dp.toPx(), 10.dp.toPx()), muted)
                rows.forEachIndexed { index, placed ->
                    val button = placed.button
                    val center = Offset(bodyX + placed.x.dp.toPx(), placed.y.dp.toPx())
                    val lit = button == focused
                    val colour = when {
                        lit -> brand
                        remapped(button) -> brand.copy(alpha = 0.7f)
                        else -> muted
                    }
                    val rowY = (ROW_TOP + ROW_H * index + ROW_H / 2).toPx()
                    if (lit || remapped(button)) {
                        drawLine(colour, center, Offset(LABEL_X.toPx() - 6.dp.toPx(), rowY), strokeWidth = if (lit) 3f else 1.5f)
                    }
                    when (button) {
                        RemoteButton.DPAD -> {
                            val ring = DPAD_RING.dp.toPx() * model.bodyWidth / 120f
                            drawCircle(colour, ring, center, style = Stroke(if (lit) 4f else 2f))
                            drawCircle(colour, 14.dp.toPx(), center, style = Stroke(2f))
                        }
                        RemoteButton.VOLUME -> {
                            // The 2015 remote's volume is a touch slider down the middle.
                            val slider = model == RemoteModel.SHIELD_2015
                            val size = if (slider) Size(DOT.dp.toPx() * 2, 70.dp.toPx()) else Size(60.dp.toPx(), DOT.dp.toPx() * 2)
                            drawRoundRect(
                                colour, Offset(center.x - size.width / 2, center.y - size.height / 2), size,
                                CornerRadius(DOT.dp.toPx()),
                                style = if (lit) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2f),
                            )
                        }
                        else -> if (lit || remapped(button)) {
                            drawCircle(colour, DOT.dp.toPx(), center)
                        } else {
                            drawCircle(colour, DOT.dp.toPx(), center, style = Stroke(2f))
                        }
                    }
                }
            }
        }
        rows.forEachIndexed { index, placed ->
            val button = placed.button
            MapperRow(
                text = "${button.label}  ·  ${describe(button)}",
                strong = remapped(button),
                modifier = Modifier
                    .offset(x = labelX, y = ROW_TOP + ROW_H * index)
                    .focusRequester(requesters.getValue(button)),
                onFocus = { if (it) focused = button else if (focused == button) focused = null },
                onClick = { onPick(button) },
            )
        }
    }
}

/** The remote seen end-on: a triangle for the 2019 remote, a thin slab for the flat ones. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSideView(model: RemoteModel, at: Offset, colour: Color) {
    val w = 26.dp.toPx()
    val outline = Stroke(2f)
    if (model.triangular) {
        val path = Path().apply {
            moveTo(at.x, at.y + w)
            lineTo(at.x + w, at.y + w)
            lineTo(at.x + w / 2, at.y)
            close()
        }
        drawPath(path, colour, style = outline)
    } else {
        drawRoundRect(colour, Offset(at.x, at.y + w * 0.65f), Size(w, w * 0.3f), CornerRadius(4f), style = outline)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MapperRow(
    text: String,
    strong: Boolean,
    modifier: Modifier,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .height(ROW_H - 4.dp)
            .width(ROW_W)
            .clip(shape)
            .background(if (focused) ColorBrand else ColorTile.copy(alpha = 0f))
            .border(1.dp, if (focused) ColorBrand else ColorTile.copy(alpha = 0f), shape)
            .onFocusChanged { onFocus(it.isFocused) }
            .focusable(interactionSource = interaction)
            .onSelectKeyUp(onClick)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = when {
                focused -> ColorOnBrand
                strong -> ColorText
                else -> ColorMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
