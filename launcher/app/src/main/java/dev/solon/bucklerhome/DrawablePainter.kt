package dev.solon.bucklerhome

import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.roundToInt

/**
 * Draws an app's own icon or banner straight from its Drawable. Copying each one into a new
 * bitmap first cost every Home tile about half a megabyte on top of the copy Android already holds.
 */
class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size =
        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        } else {
            Size.Unspecified
        }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            // One Drawable can be on two shelves at once, so set its bounds on every draw.
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
