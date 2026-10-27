package kiesel

import java.io.InputStream
import java.io.ObjectInputStream
import java.io.Serializable
import kotlin.math.ceil
import kotlin.math.floor

/** Ground heights over a region in whole meters, [SAMPLES_PER_DEGREE] per degree. */
class Heights(
    private val south: Double,
    private val west: Double,
    private val rows: Int,
    private val columns: Int,
    private val meters: ShortArray,
) : Serializable {
    /** Meters above sea level, bilinear; 0 outside the region. */
    fun at(point: LatLon): Double {
        val row = (point.latitude - south) * SAMPLES_PER_DEGREE
        val column = (point.longitude - west) * SAMPLES_PER_DEGREE
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

    companion object {
        private const val serialVersionUID = 1L

        /** Samples [height] over the area the graph covers. */
        fun of(graph: Graph, height: (Double, Double) -> Double): Heights {
            val south = floor(graph.latitudes.min().toDouble() * SAMPLES_PER_DEGREE)
            val west = floor(graph.longitudes.min().toDouble() * SAMPLES_PER_DEGREE)
            val north = ceil(graph.latitudes.max().toDouble() * SAMPLES_PER_DEGREE)
            val east = ceil(graph.longitudes.max().toDouble() * SAMPLES_PER_DEGREE)
            val rows = (north - south).toInt() + 1
            val columns = (east - west).toInt() + 1
            val meters = ShortArray(rows * columns) {
                val latitude = (south + it / columns) / SAMPLES_PER_DEGREE
                val longitude = (west + it % columns) / SAMPLES_PER_DEGREE
                height(latitude, longitude).toInt().toShort()
            }
            val degrees = SAMPLES_PER_DEGREE.toDouble()
            return Heights(south / degrees, west / degrees, rows, columns, meters)
        }

        fun read(input: InputStream) =
            ObjectInputStream(input.buffered()).use { it.readObject() as Heights }
    }
}

/** Three arc-seconds, about 90 m. */
private const val SAMPLES_PER_DEGREE = 1200
