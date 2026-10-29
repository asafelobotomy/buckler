package dev.solon.bucklerhome

import kotlin.math.ceil
import kotlin.math.floor

/** A row-major field of [rows] x [cols] samples; `values[row * cols + col]`. NaN marks a gap. */
class ScalarField(val rows: Int, val cols: Int, val values: FloatArray) {
    init {
        require(rows >= 2 && cols >= 2 && values.size == rows * cols) { "field must be at least 2x2" }
    }

    operator fun get(row: Int, col: Int): Float = values[row * cols + col]

    /** Bilinear sample at fractional grid position ([row], [col]), clamped to the field. */
    fun sample(row: Float, col: Float): Float {
        val r = row.coerceIn(0f, (rows - 1).toFloat())
        val c = col.coerceIn(0f, (cols - 1).toFloat())
        val r0 = floor(r).toInt().coerceAtMost(rows - 2)
        val c0 = floor(c).toInt().coerceAtMost(cols - 2)
        val fr = r - r0
        val fc = c - c0
        val top = this[r0, c0] * (1 - fc) + this[r0, c0 + 1] * fc
        val bottom = this[r0 + 1, c0] * (1 - fc) + this[r0 + 1, c0 + 1] * fc
        return top * (1 - fr) + bottom * fr
    }

    /**
     * The field resampled bilinearly with [factor] steps between original samples, so contours
     * bend smoothly instead of cutting straight across each coarse cell. Original sample (r, c)
     * lands at (r * factor, c * factor).
     */
    fun upsample(factor: Int): ScalarField {
        require(factor >= 1)
        if (factor == 1) return this
        val outRows = (rows - 1) * factor + 1
        val outCols = (cols - 1) * factor + 1
        val out = FloatArray(outRows * outCols)
        for (r in 0 until outRows) {
            for (c in 0 until outCols) {
                out[r * outCols + c] = sample(r.toFloat() / factor, c.toFloat() / factor)
            }
        }
        return ScalarField(outRows, outCols, out)
    }
}

/** A contour line in grid coordinates: x is the column, y the row, both fractional. */
class Polyline(val xs: FloatArray, val ys: FloatArray) {
    val size: Int get() = xs.size
    val closed: Boolean get() = size > 2 && xs[0] == xs[size - 1] && ys[0] == ys[size - 1]
}

/**
 * A pressure high or low found at grid sample ([row], [col]). [y] and [x] place it between samples
 * by fitting a parabola through each axis, so the letter sits where the centre most likely is
 * rather than snapping to the coarse grid.
 */
data class Extremum(val row: Int, val col: Int, val value: Float, val high: Boolean, val y: Float, val x: Float)

/** Marching squares contouring and pressure-centre detection for the weather map. */
object Isobars {
    /** Isobar spacing used on synoptic charts. */
    const val STEP_HPA = 4f

    /** Every multiple of [step] strictly inside the field's range, lowest first. */
    fun levels(field: ScalarField, step: Float = STEP_HPA): List<Float> {
        val finite = field.values.filter { !it.isNaN() }
        if (finite.isEmpty()) return emptyList()
        val lo = finite.min()
        val hi = finite.max()
        val first = ceil(lo / step).toInt()
        val last = floor(hi / step).toInt()
        return (first..last).map { it * step }.filter { it > lo && it < hi }
    }

    /**
     * The contour of [field] at [level], as joined polylines. Each cell's crossings are found by
     * marching squares; the two saddle cases are resolved by the cell's mean so lines never cross.
     * Segments are chained through the cell edge they share, which is exact, so no tolerance-based
     * point matching is needed. Cells touching a NaN are skipped.
     */
    fun contour(field: ScalarField, level: Float): List<Polyline> {
        val segments = ArrayList<IntArray>()
        for (r in 0 until field.rows - 1) {
            for (c in 0 until field.cols - 1) cellSegments(field, r, c, level, segments)
        }
        return chain(field, level, segments)
    }

    // Edge ids: horizontal edge (r,c)-(r,c+1) is 2*(r*cols+c); vertical (r,c)-(r+1,c) is that + 1.
    private fun cellSegments(f: ScalarField, r: Int, c: Int, level: Float, out: MutableList<IntArray>) {
        val bl = f[r, c]
        val br = f[r, c + 1]
        val tr = f[r + 1, c + 1]
        val tl = f[r + 1, c]
        if (bl.isNaN() || br.isNaN() || tr.isNaN() || tl.isNaN()) return
        val bottom = 2 * (r * f.cols + c)
        val top = 2 * ((r + 1) * f.cols + c)
        val left = 2 * (r * f.cols + c) + 1
        val right = 2 * (r * f.cols + c + 1) + 1
        val code = (if (bl > level) 1 else 0) or (if (br > level) 2 else 0) or
            (if (tr > level) 4 else 0) or (if (tl > level) 8 else 0)
        when (code) {
            0, 15 -> Unit
            1, 14 -> out += intArrayOf(left, bottom)
            2, 13 -> out += intArrayOf(bottom, right)
            3, 12 -> out += intArrayOf(left, right)
            4, 11 -> out += intArrayOf(right, top)
            6, 9 -> out += intArrayOf(bottom, top)
            7, 8 -> out += intArrayOf(left, top)
            5, 10 -> {
                // Saddle: whether the high corners connect through the middle decides the pairing.
                val centreHigh = (bl + br + tr + tl) / 4 > level
                if ((code == 5) == centreHigh) {
                    out += intArrayOf(left, top)
                    out += intArrayOf(bottom, right)
                } else {
                    out += intArrayOf(left, bottom)
                    out += intArrayOf(right, top)
                }
            }
        }
    }

    private fun chain(f: ScalarField, level: Float, segments: List<IntArray>): List<Polyline> {
        val byEdge = HashMap<Int, MutableList<Int>>()
        segments.forEachIndexed { i, s -> s.forEach { byEdge.getOrPut(it) { ArrayList(2) } += i } }
        val used = BooleanArray(segments.size)
        val lines = ArrayList<Polyline>()
        // Open lines start at a boundary edge (only one segment uses it); closed loops come after.
        val starts = segments.indices.sortedBy { i -> if (segments[i].any { byEdge[it]!!.size == 1 }) 0 else 1 }
        for (start in starts) {
            if (used[start]) continue
            val seg = segments[start]
            val first = if (byEdge[seg[1]]!!.size == 1 && byEdge[seg[0]]!!.size > 1) seg[1] else seg[0]
            val edges = arrayListOf(first)
            var current = start
            var edge = first
            while (true) {
                used[current] = true
                val s = segments[current]
                edge = if (s[0] == edge) s[1] else s[0]
                edges += edge
                current = byEdge[edge]!!.firstOrNull { !used[it] } ?: break
            }
            val xs = FloatArray(edges.size)
            val ys = FloatArray(edges.size)
            edges.forEachIndexed { i, e -> crossing(f, e, level, xs, ys, i) }
            lines += Polyline(xs, ys)
        }
        return lines
    }

    private fun crossing(f: ScalarField, edge: Int, level: Float, xs: FloatArray, ys: FloatArray, i: Int) {
        val node = edge / 2
        val r = node / f.cols
        val c = node % f.cols
        val vertical = edge % 2 == 1
        val a = f[r, c]
        val b = if (vertical) f[r + 1, c] else f[r, c + 1]
        val t = if (b == a) 0.5f else ((level - a) / (b - a)).coerceIn(0f, 1f)
        xs[i] = if (vertical) c.toFloat() else c + t
        ys[i] = if (vertical) r + t else r.toFloat()
    }

    /**
     * Highs and lows: interior samples strictly above (or below) all eight neighbours. Edge
     * samples are left out because a value rising off the edge of the data is not a centre.
     */
    fun extrema(field: ScalarField): List<Extremum> = buildList {
        for (r in 1 until field.rows - 1) {
            for (c in 1 until field.cols - 1) {
                val v = field[r, c]
                if (v.isNaN()) continue
                var high = true
                var low = true
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val n = field[r + dr, c + dc]
                    if (n.isNaN() || n >= v) high = false
                    if (n.isNaN() || n <= v) low = false
                }
                if (high || low) {
                    val dy = vertex(field[r - 1, c], v, field[r + 1, c])
                    val dx = vertex(field[r, c - 1], v, field[r, c + 1])
                    add(Extremum(r, c, v, high, r + dy, c + dx))
                }
            }
        }
    }

    /** Offset, within half a step, of the turning point of the parabola through [a], [b], [c]. */
    private fun vertex(a: Float, b: Float, c: Float): Float {
        val curve = a - 2 * b + c
        return if (curve == 0f) 0f else (0.5f * (a - c) / curve).coerceIn(-0.5f, 0.5f)
    }
}
