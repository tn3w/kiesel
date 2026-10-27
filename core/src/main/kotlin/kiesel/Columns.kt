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

/** Maps [file] read-only: its data stays in the page cache instead of the heap. */
fun mapFile(file: File): ByteBuffer = FileChannel.open(file.toPath()).use {
    it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
}

/** Views on the arrays written by [writeColumns], in the same order, without copying. */
internal class Columns(buffer: ByteBuffer) {
    private val buffer = buffer.order(ORDER)

    fun bytes(): ByteBuffer {
        val size = buffer.getLong().toInt()
        val column = buffer.slice().order(ORDER)
        column.limit(size)
        buffer.position(buffer.position() + aligned(size))
        return column
    }

    fun shorts(): ShortBuffer = bytes().asShortBuffer()

    fun ints(): IntBuffer = bytes().asIntBuffer()

    fun longs(): LongBuffer = bytes().asLongBuffer()

    fun floats(): FloatBuffer = bytes().asFloatBuffer()

    fun text() = Charsets.UTF_8.decode(bytes()).toString()
}

internal fun text(value: String): ByteBuffer = Charsets.UTF_8.encode(value)

/** Each column as its byte count and bytes, padded so mapped views stay aligned. */
internal fun OutputStream.writeColumns(vararg columns: Buffer) {
    val channel = Channels.newChannel(this)
    for (column in columns) {
        val bytes = bytesOf(column)
        val size = bytes.remaining()
        channel.write(allocate(HEADER_BYTES).putLong(0, size.toLong()))
        channel.write(bytes)
        channel.write(allocate(aligned(size) - size))
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

private fun aligned(size: Int) = (size + HEADER_BYTES - 1) / HEADER_BYTES * HEADER_BYTES

private val ORDER = ByteOrder.LITTLE_ENDIAN
private const val HEADER_BYTES = 8
