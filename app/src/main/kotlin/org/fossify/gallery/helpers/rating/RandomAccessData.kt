package org.fossify.gallery.helpers.rating

import java.io.Closeable
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Minimal random access to a file's bytes, so large files (videos, raw photos) can be edited in place. */
interface RandomAccessData {
    fun length(): Long

    /** Reads exactly [size] bytes at [position], throwing [EOFException] if the data ends earlier. */
    fun read(position: Long, size: Int): ByteArray

    fun write(position: Long, bytes: ByteArray)

    fun truncate(size: Long)
}

class ByteArrayData(initial: ByteArray) : RandomAccessData {
    var bytes: ByteArray = initial
        private set

    override fun length() = bytes.size.toLong()

    override fun read(position: Long, size: Int): ByteArray {
        if (position < 0 || position + size > bytes.size) {
            throw EOFException()
        }

        return bytes.copyOfRange(position.toInt(), position.toInt() + size)
    }

    override fun write(position: Long, bytes: ByteArray) {
        val end = position.toInt() + bytes.size
        if (end > this.bytes.size) {
            this.bytes = this.bytes.copyOf(end)
        }
        bytes.copyInto(this.bytes, position.toInt())
    }

    override fun truncate(size: Long) {
        if (size < bytes.size) {
            bytes = bytes.copyOf(size.toInt())
        }
    }
}

/**
 * Backed by file channels. [readChannel] and [writeChannel] may be the same channel (a RandomAccessFile)
 * or two channels over one file descriptor (a storage access framework document).
 */
class FileChannelData(
    private val readChannel: FileChannel,
    private val writeChannel: FileChannel,
    private val closeables: List<Closeable>
) : RandomAccessData, Closeable {
    override fun length() = readChannel.size()

    override fun read(position: Long, size: Int): ByteArray {
        val buffer = ByteBuffer.allocate(size)
        var pos = position
        while (buffer.hasRemaining()) {
            val read = readChannel.read(buffer, pos)
            if (read < 0) {
                throw EOFException()
            }
            pos += read
        }
        return buffer.array()
    }

    override fun write(position: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        var pos = position
        while (buffer.hasRemaining()) {
            pos += writeChannel.write(buffer, pos)
        }
    }

    override fun truncate(size: Long) {
        writeChannel.truncate(size)
    }

    override fun close() {
        try {
            writeChannel.force(false)
        } catch (ignored: Exception) {
        }
        closeables.forEach {
            try {
                it.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
