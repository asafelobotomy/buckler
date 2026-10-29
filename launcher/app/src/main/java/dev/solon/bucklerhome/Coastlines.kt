package dev.solon.bucklerhome

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream

/**
 * One land or lake outline, in hundredths of a degree, with its bounding box so a reader can skip
 * it without touching the points. The closing point is not repeated.
 */
class CoastRing(
    val lats: ShortArray,
    val lons: ShortArray,
    val minLat: Short,
    val maxLat: Short,
    val minLon: Short,
    val maxLon: Short,
)

/** A ring placed for one map view: degrees, longitude shifted by whole turns to sit in the view. */
class VisibleRing(val lats: FloatArray, val lons: FloatArray)

/**
 * The Natural Earth land outline bundled as `assets/coastlines.bin` (public domain; built by
 * `scripts/gen_coastlines.py`, whose docstring documents the format).
 */
object Coastlines {
    private const val ASSET = "coastlines.bin"
    private const val MAGIC = 0x42434C31 // "BCL1"

    @Volatile private var rings: List<CoastRing>? = null

    /**
     * Every ring, read from the asset on first use and kept afterwards. Blocking file I/O: call
     * off the main thread. An unreadable asset gives an empty list, and the map draws no land.
     */
    fun rings(context: Context): List<CoastRing> {
        rings?.let { return it }
        synchronized(this) {
            rings?.let { return it }
            val loaded = runCatching { context.assets.open(ASSET).use(::read) }.getOrDefault(emptyList())
            rings = loaded
            return loaded
        }
    }

    /** Parses the asset's bytes; see the generator's docstring for the layout. */
    fun read(input: InputStream): List<CoastRing> {
        val data = DataInputStream(input.buffered())
        require(data.readInt() == MAGIC) { "not a coastline file" }
        val count = data.readInt()
        return List(count) {
            val n = data.readUnsignedShort()
            val minLat = data.readShort()
            val maxLat = data.readShort()
            val minLon = data.readShort()
            val maxLon = data.readShort()
            val lats = ShortArray(n)
            val lons = ShortArray(n)
            for (i in 0 until n) {
                lats[i] = data.readShort()
                lons[i] = data.readShort()
            }
            CoastRing(lats, lons, minLat, maxLat, minLon, maxLon)
        }
    }

    /**
     * The rings that touch the box [south]..[north], [west]..[east] (degrees; [west] and [east]
     * may run past ±180). Each is shifted by a whole turn of longitude when that brings it into the
     * box, so a view across the antimeridian still gets the land on both sides. Rings are kept
     * whole; the canvas clips them, which keeps filled polygons closed.
     */
    fun visible(all: List<CoastRing>, south: Double, north: Double, west: Double, east: Double): List<VisibleRing> {
        val out = ArrayList<VisibleRing>()
        for (ring in all) {
            if (ring.maxLat / 100.0 < south || ring.minLat / 100.0 > north) continue
            for (turn in -1..1) {
                val shift = turn * 360.0
                if (ring.maxLon / 100.0 + shift < west || ring.minLon / 100.0 + shift > east) continue
                val lats = FloatArray(ring.lats.size) { ring.lats[it] / 100f }
                val lons = FloatArray(ring.lons.size) { (ring.lons[it] / 100.0 + shift).toFloat() }
                out += VisibleRing(lats, lons)
            }
        }
        return out
    }
}
