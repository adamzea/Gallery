package org.fossify.gallery.helpers.rating

import java.io.ByteArrayOutputStream

internal fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

internal fun ByteArray.u16be(index: Int) = (u8(index) shl 8) or u8(index + 1)

internal fun ByteArray.u16le(index: Int) = u8(index) or (u8(index + 1) shl 8)

internal fun ByteArray.u24le(index: Int) = u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16)

internal fun ByteArray.u32be(index: Int): Long =
    (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

internal fun ByteArray.u32le(index: Int): Long =
    u8(index).toLong() or (u8(index + 1).toLong() shl 8) or (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)

internal fun ByteArray.u64be(index: Int): Long = (u32be(index) shl 32) or u32be(index + 4)

/** Reads a big-endian unsigned number of [size] bytes (0, 2, 4 or 8). */
internal fun ByteArray.uintBe(index: Int, size: Int): Long = when (size) {
    0 -> 0L
    2 -> u16be(index).toLong()
    4 -> u32be(index)
    8 -> u64be(index)
    else -> throw IllegalArgumentException("Unsupported field size $size")
}

internal fun ByteArray.fourCC(index: Int) = String(this, index, 4, Charsets.ISO_8859_1)

internal fun ByteArray.startsWithAt(index: Int, prefix: ByteArray, limit: Int = size): Boolean {
    if (index < 0 || index + prefix.size > limit) {
        return false
    }

    return prefix.indices.all { this[index + it] == prefix[it] }
}

/** Index of the first zero byte at or after [from], or -1. */
internal fun ByteArray.indexOfZero(from: Int, until: Int = size): Int {
    for (i in from until until) {
        if (this[i] == 0.toByte()) {
            return i
        }
    }
    return -1
}

internal class ByteWriter(initialSize: Int = 256) {
    private val out = ByteArrayOutputStream(initialSize)

    val size get() = out.size()

    fun u8(value: Int) = apply { out.write(value and 0xFF) }

    fun u16be(value: Int) = apply { u8(value shr 8).u8(value) }

    fun u16le(value: Int) = apply { u8(value).u8(value shr 8) }

    fun u24le(value: Int) = apply { u8(value).u8(value shr 8).u8(value shr 16) }

    fun u32be(value: Long) = apply { u8((value shr 24).toInt()).u8((value shr 16).toInt()).u8((value shr 8).toInt()).u8(value.toInt()) }

    fun u32le(value: Long) = apply { u8(value.toInt()).u8((value shr 8).toInt()).u8((value shr 16).toInt()).u8((value shr 24).toInt()) }

    fun u64be(value: Long) = apply { u32be(value ushr 32).u32be(value and 0xFFFFFFFFL) }

    fun u64le(value: Long) = apply { u32le(value and 0xFFFFFFFFL).u32le(value ushr 32) }

    /** Writes a big-endian unsigned number of [size] bytes (0, 2, 4 or 8), failing if it doesn't fit. */
    fun uintBe(value: Long, size: Int) = apply {
        require(fitsIn(value, size)) { "Value $value doesn't fit in $size bytes" }
        when (size) {
            0 -> Unit
            2 -> u16be(value.toInt())
            4 -> u32be(value)
            8 -> u64be(value)
            else -> throw IllegalArgumentException("Unsupported field size $size")
        }
    }

    fun fourCC(value: String) = apply { bytes(value.toByteArray(Charsets.ISO_8859_1).also { require(it.size == 4) }) }

    fun bytes(value: ByteArray, offset: Int = 0, length: Int = value.size - offset) = apply { out.write(value, offset, length) }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun fitsIn(value: Long, size: Int) = when (size) {
            0 -> value == 0L
            2 -> value in 0..0xFFFF
            4 -> value in 0..0xFFFFFFFFL
            8 -> value >= 0
            else -> false
        }
    }
}
