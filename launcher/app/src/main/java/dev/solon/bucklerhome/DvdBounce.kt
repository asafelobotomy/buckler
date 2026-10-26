package dev.solon.bucklerhome

import kotlin.math.abs

/** Edge hits from one [DvdBounce.step]; both true means the block hit a corner. */
data class BounceHit(val x: Boolean, val y: Boolean) {
    val any: Boolean get() = x || y
    val corner: Boolean get() = x && y
}

/** The classic DVD-logo physics: constant velocity, mirror on every edge. Pure so it is testable. */
class DvdBounce(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
) {
    /** Advance by [dtSeconds] inside [0, maxX] x [0, maxY]. */
    fun step(dtSeconds: Float, maxX: Float, maxY: Float): BounceHit {
        var hitX = false
        var hitY = false
        x += vx * dtSeconds
        y += vy * dtSeconds
        if (x <= 0f) {
            x = 0f
            vx = abs(vx)
            hitX = true
        } else if (x >= maxX) {
            x = maxX.coerceAtLeast(0f)
            vx = -abs(vx)
            hitX = true
        }
        if (y <= 0f) {
            y = 0f
            vy = abs(vy)
            hitY = true
        } else if (y >= maxY) {
            y = maxY.coerceAtLeast(0f)
            vy = -abs(vy)
            hitY = true
        }
        return BounceHit(hitX, hitY)
    }
}
