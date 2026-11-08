package org.fossify.gallery.helpers.rating

/**
 * DNG (TIFF based): XMP lives in IFD0 tag 700. Raw files are large, so this only touches small
 * pieces of the file: the XMP is rewritten in place when it fits, otherwise appended at the end
 * (with padding for later edits) and IFD0 is updated to point at it.
 */
internal object TiffRating {
    private const val TAG_XMP = 700
    private const val TYPE_BYTE = 1
    private const val TYPE_UNDEFINED = 7
    private const val ENTRY_SIZE = 12
    private const val XMP_PADDING = 2048
    private const val MAX_XMP_SIZE = 16 * 1024 * 1024

    private class Order(val littleEndian: Boolean) {
        fun u16(b: ByteArray, i: Int) = if (littleEndian) b.u16le(i) else b.u16be(i)
        fun u32(b: ByteArray, i: Int) = if (littleEndian) b.u32le(i) else b.u32be(i)

        fun u16(w: ByteWriter, value: Int): ByteWriter = if (littleEndian) w.u16le(value) else w.u16be(value)
        fun u32(w: ByteWriter, value: Long): ByteWriter = if (littleEndian) w.u32le(value) else w.u32be(value)
    }

    fun write(data: RandomAccessData, stars: Int?): RatingResult {
        val header = data.read(0, 8)
        val order = when {
            header.fourCC(0) == "II*\u0000" -> Order(true)
            header.fourCC(0) == "MM\u0000*" -> Order(false)
            else -> return RatingResult.FAILED // also rejects BigTIFF
        }

        val ifdOffset = order.u32(header, 4)
        val count = order.u16(data.read(ifdOffset, 2), 0)
        val entries = data.read(ifdOffset + 2, count * ENTRY_SIZE)
        val nextIfd = data.read(ifdOffset + 2 + count * ENTRY_SIZE, 4)

        val exifChanged = ExifRating.patchEntries(entries, 0, count, order.littleEndian, stars)
        if (exifChanged) {
            data.write(ifdOffset + 2, entries)
        }

        val xmpIndex = (0 until count).firstOrNull { order.u16(entries, it * ENTRY_SIZE) == TAG_XMP }
        var oldXmpOffset = 0L
        var oldXmpLength = 0
        val oldXmp = xmpIndex?.let {
            val type = order.u16(entries, it * ENTRY_SIZE + 2)
            val length = order.u32(entries, it * ENTRY_SIZE + 4)
            if ((type != TYPE_BYTE && type != TYPE_UNDEFINED) || length > MAX_XMP_SIZE) {
                return RatingResult.FAILED
            }

            oldXmpLength = length.toInt()
            val bytes = if (oldXmpLength <= 4) {
                entries.copyOfRange(it * ENTRY_SIZE + 8, it * ENTRY_SIZE + 8 + oldXmpLength)
            } else {
                oldXmpOffset = order.u32(entries, it * ENTRY_SIZE + 8)
                data.read(oldXmpOffset, oldXmpLength)
            }
            // trailing whitespace is padding reserved for later edits, not part of the packet
            String(bytes, Charsets.UTF_8).trimEnd { it == '\u0000' || it.isWhitespace() }
        }

        val newXmp = XmpRating.updateOrCreate(oldXmp, stars)
        if (newXmp == oldXmp || newXmp == null) {
            return if (exifChanged) RatingResult.CHANGED else RatingResult.UNCHANGED
        }

        val newBytes = newXmp.toByteArray(Charsets.UTF_8)
        if (xmpIndex != null && oldXmpLength > 4 && newBytes.size <= oldXmpLength) {
            // fits into the old spot, pad with whitespace (allowed after the end of the packet)
            data.write(oldXmpOffset, newBytes + ByteArray(oldXmpLength - newBytes.size) { ' '.code.toByte() })
            return RatingResult.CHANGED
        }

        val padded = newBytes + ByteArray(XMP_PADDING) { ' '.code.toByte() }
        val xmpOffset = appendAligned(data, padded)
        if (xmpIndex != null) {
            // the IFD entry has a fixed size, so it can be updated in place
            val entry = ByteWriter(8)
            order.u32(entry, padded.size.toLong())
            order.u32(entry, xmpOffset)
            data.write(ifdOffset + 2 + xmpIndex * ENTRY_SIZE + 4, entry.toByteArray())
            return RatingResult.CHANGED
        }

        // IFD0 has to grow, write a copy with the XMP tag added at the end and point the header at it.
        // Values stored outside the IFD keep their offsets, so they don't need to move.
        val newEntry = ByteWriter(ENTRY_SIZE)
        order.u16(newEntry, TAG_XMP)
        order.u16(newEntry, TYPE_BYTE)
        order.u32(newEntry, padded.size.toLong())
        order.u32(newEntry, xmpOffset)

        val allEntries = (0 until count).map { entries.copyOfRange(it * ENTRY_SIZE, (it + 1) * ENTRY_SIZE) } + newEntry.toByteArray()
        val ifd = ByteWriter(2 + allEntries.size * ENTRY_SIZE + 4)
        order.u16(ifd, allEntries.size)
        allEntries.sortedBy { order.u16(it, 0) }.forEach { ifd.bytes(it) }
        ifd.bytes(nextIfd)

        val newIfdOffset = appendAligned(data, ifd.toByteArray())
        val headerPatch = ByteWriter(4)
        order.u32(headerPatch, newIfdOffset)
        data.write(4, headerPatch.toByteArray())
        return RatingResult.CHANGED
    }

    /** Appends at the end of the file on a word boundary, as TIFF requires. Returns the offset. */
    private fun appendAligned(data: RandomAccessData, bytes: ByteArray): Long {
        var position = data.length()
        if (position % 2 == 1L) {
            data.write(position, byteArrayOf(0))
            position++
        }

        require(position + bytes.size <= 0xFFFFFFFFL) { "File too large for TIFF offsets" }
        data.write(position, bytes)
        return position
    }
}
