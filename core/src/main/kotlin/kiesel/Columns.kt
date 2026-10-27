package kiesel

import java.io.File
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel

/**
 * Maps the arrays written by [writeColumns] read-only, one by one and in the same order:
 * their data stays in the page cache instead of the heap, and files may exceed 2 GB.
 */
internal fun <T> readColumns(file: File, read: Columns.() -> T): T =
    FileChannel.open(file.toPath()).use { Columns(it).read() }

internal class Columns(private val channel: FileChannel) {
    private var position = 0L

    fun bytes(): ByteBuffer {
        val header = allocate(HEADER_BYTES)
        channel.read(header, position)
        val size = header.getLong(0)
        val start = position + HEADER_BYTES
        position = start + aligned(size)
        return channel.map(FileChannel.MapMode.READ_ONLY, start, size).order(ORDER)
    }

    fun shorts(): ShortBuffer = bytes().asShortBuffer()

    fun ints(): IntBuffer = bytes().asIntBuffer()

    fun longs(): LongBuffer = bytes().asLongBuffer()

    fun floats(): FloatBuffer = bytes().asFloatBuffer()

    fun texts() = Texts(bytes(), ints())
}

/** Strings stored back to back as UTF-8; [starts] has one more entry than strings. */
class Texts internal constructor(
    private val bytes: ByteBuffer,
    private val starts: IntBuffer,
) {
    val size get() = starts.limit() - 1

    operator fun get(index: Int): String {
        val view = bytes.duplicate()
        view.limit(starts[index + 1])
        view.position(starts[index])
        return Charsets.UTF_8.decode(view).toString()
    }

    /** Indices of strings containing [needle], at most [limit]. */
    fun containing(needle: ByteArray, limit: Int): List<Int> {
        val found = ArrayList<Int>()
        var position = find(needle, 0)
        while (position >= 0 && found.size < limit) {
            val index = partition(starts.limit()) { starts[it] <= position } - 1
            val end = starts[index + 1]
            val inside = position + needle.size <= end
            if (inside) found += index
            position = find(needle, if (inside) end else position + 1)
        }
        return found
    }

    private fun find(needle: ByteArray, from: Int): Int {
        for (position in from..bytes.limit() - needle.size) {
            if (matches(needle, position)) return position
        }
        return -1
    }

    private fun matches(needle: ByteArray, position: Int): Boolean {
        for (i in needle.indices) if (bytes[position + i] != needle[i]) return false
        return true
    }

    internal fun columns() = arrayOf<Buffer>(bytes, starts)

    internal companion object {
        fun of(values: List<String>): Texts {
            val encoded = values.map { it.encodeToByteArray() }
            val starts = IntArray(values.size + 1)
            encoded.forEachIndexed { i, bytes -> starts[i + 1] = starts[i] + bytes.size }
            val bytes = ByteBuffer.allocate(starts.last())
            encoded.forEach(bytes::put)
            return Texts(bytes.flip() as ByteBuffer, IntBuffer.wrap(starts))
        }
    }
}

/** Each column as its byte count and bytes, padded so mapped views stay aligned. */
internal fun OutputStream.writeColumns(vararg columns: Buffer) {
    val channel = Channels.newChannel(this)
    for (column in columns) {
        val bytes = bytesOf(column)
        val size = bytes.remaining()
        channel.write(allocate(HEADER_BYTES).putLong(0, size.toLong()))
        channel.write(bytes)
        channel.write(allocate(aligned(size.toLong()).toInt() - size))
    }
}

private fun bytesOf(column: Buffer): ByteBuffer {
    if (column is ByteBuffer) return column.duplicate()
    val width = when (column) {
        is ShortBuffer -> Short.SIZE_BYTES
        is LongBuffer -> Long.SIZE_BYTES
        else -> Int.SIZE_BYTES
    }
    val bytes = allocate(column.limit() * width)
    when (column) {
        is ShortBuffer -> bytes.asShortBuffer().put(column.duplicate())
        is IntBuffer -> bytes.asIntBuffer().put(column.duplicate())
        is LongBuffer -> bytes.asLongBuffer().put(column.duplicate())
        is FloatBuffer -> bytes.asFloatBuffer().put(column.duplicate())
        else -> error("Unsupported column ${column.javaClass}")
    }
    return bytes
}

private fun allocate(size: Int) = ByteBuffer.allocate(size).order(ORDER)

private fun aligned(size: Long) = (size + HEADER_BYTES - 1) / HEADER_BYTES * HEADER_BYTES

private val ORDER = ByteOrder.LITTLE_ENDIAN
private const val HEADER_BYTES = 8
