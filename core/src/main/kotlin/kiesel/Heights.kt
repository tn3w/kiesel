package kiesel

import java.io.File
import java.io.OutputStream
import java.nio.IntBuffer
import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Ground heights over a region in whole meters, [SAMPLES_PER_DEGREE] per degree;
 * [south] and [west] count samples.
 */
class Heights internal constructor(
    private val south: Int,
    private val west: Int,
    private val rows: Int,
    private val columns: Int,
    private val meters: ShortBuffer,
) {
    /** Meters above sea level, bilinear; 0 outside the region. */
    fun at(point: LatLon): Double {
        val row = point.latitude * SAMPLES_PER_DEGREE - south
        val column = point.longitude * SAMPLES_PER_DEGREE - west
        if (row < 0 || column < 0 || row >= rows - 1 || column >= columns - 1) return 0.0
        val top = floor(row).toInt()
        val left = floor(column).toInt()
        fun sample(r: Int, c: Int) = meters[r * columns + c].toDouble()
        val down = row - top
        val across = column - left
        val lower = lerp(sample(top, left), sample(top, left + 1), across)
        val upper = lerp(sample(top + 1, left), sample(top + 1, left + 1), across)
        return lerp(lower, upper, down)
    }

    /** Leaves [output] open. */
    fun write(output: OutputStream) {
        val header = IntBuffer.wrap(intArrayOf(south, west, rows, columns))
        output.writeColumns(header, meters)
    }

    companion object {
        fun read(file: File) = with(Columns(mapFile(file))) {
            val header = ints()
            Heights(header[0], header[1], header[2], header[3], shorts())
        }

        /** Samples [height] over the area the graph covers. */
        fun of(graph: Graph, height: (Double, Double) -> Double): Heights {
            val (southWest, northEast) = graph.bounds()
            val south = floor(southWest.latitude * SAMPLES_PER_DEGREE).toInt()
            val west = floor(southWest.longitude * SAMPLES_PER_DEGREE).toInt()
            val north = ceil(northEast.latitude * SAMPLES_PER_DEGREE).toInt()
            val east = ceil(northEast.longitude * SAMPLES_PER_DEGREE).toInt()
            val rows = north - south + 1
            val columns = east - west + 1
            val meters = ShortArray(rows * columns) {
                val latitude = (south + it / columns).toDouble() / SAMPLES_PER_DEGREE
                val longitude = (west + it % columns).toDouble() / SAMPLES_PER_DEGREE
                height(latitude, longitude).toInt().toShort()
            }
            return Heights(south, west, rows, columns, ShortBuffer.wrap(meters))
        }
    }
}

/** Three arc-seconds, about 90 m. */
private const val SAMPLES_PER_DEGREE = 1200
