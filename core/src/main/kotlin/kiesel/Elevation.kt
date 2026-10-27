package kiesel

import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt

/** SRTM heights (1 arc-second .hgt tiles), downloaded into [directory] on first use. */
private const val TILE_SERVER = "https://elevation-tiles-prod.s3.amazonaws.com/skadi"

class Elevation(private val directory: File) {
    private val tiles = HashMap<String, ShortBuffer?>()

    /** Meters above sea level, bilinear; 0 where no tile exists (sea). */
    fun at(latitude: Double, longitude: Double): Double {
        val south = floor(latitude).toInt()
        val west = floor(longitude).toInt()
        val tile = tiles.getOrPut(name(south, west)) { open(south, west) } ?: return 0.0
        val side = sqrt(tile.capacity().toDouble()).toInt() - 1
        val column = (longitude - west) * side
        val row = (south + 1 - latitude) * side
        val left = column.toInt().coerceAtMost(side - 1)
        val top = row.toInt().coerceAtMost(side - 1)
        fun sample(r: Int, c: Int) =
            tile[r * (side + 1) + c].toDouble().coerceAtLeast(0.0)
        val across = column - left
        val down = row - top
        val upper = sample(top, left) * (1 - across) + sample(top, left + 1) * across
        val lower = sample(top + 1, left) * (1 - across) +
            sample(top + 1, left + 1) * across
        return upper * (1 - down) + lower * down
    }

    private fun open(south: Int, west: Int): ShortBuffer? {
        val file = File(directory, "${name(south, west)}.hgt")
        if (!file.exists() && !download(south, west, file)) return null
        return FileChannel.open(file.toPath(), StandardOpenOption.READ).use {
            it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()).asShortBuffer()
        }
    }

    private fun download(south: Int, west: Int, file: File): Boolean {
        val tile = name(south, west)
        val url = "$TILE_SERVER/${tile.take(3)}/$tile.hgt.gz"
        directory.mkdirs()
        return try {
            GZIPInputStream(URI(url).toURL().openStream()).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            true
        } catch (missing: FileNotFoundException) {
            false
        }
    }

    private fun name(south: Int, west: Int) = "%s%02d%s%03d".format(
        if (south >= 0) "N" else "S", Math.abs(south),
        if (west >= 0) "E" else "W", Math.abs(west),
    )
}
