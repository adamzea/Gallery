package org.fossify.gallery.helpers.rating

/**
 * The Windows EXIF rating tags in IFD0: Rating (0x4746, 0-5) and RatingPercent (0x4749, 0-99).
 *
 * Windows Explorer shows these instead of the XMP rating whenever they exist, and many cameras write
 * them as 0. So when a file has them they are updated to match; they are never added, since Explorer
 * falls back to XMP without them. Both are single numbers stored inside the IFD entry, so they can be
 * changed in place without moving anything.
 */
internal object ExifRating {
    private const val TAG_RATING = 0x4746
    private const val TAG_RATING_PERCENT = 0x4749
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val ENTRY_SIZE = 12

    /**
     * Updates the rating tags among the [count] IFD entries starting at [entriesStart] in [bytes].
     * A null [stars] means unrated (0). Returns true if anything changed.
     */
    fun patchEntries(bytes: ByteArray, entriesStart: Int, count: Int, littleEndian: Boolean, stars: Int?): Boolean {
        var changed = false
        for (i in 0 until count) {
            val entry = entriesStart + i * ENTRY_SIZE
            if (entry + ENTRY_SIZE > bytes.size) {
                break
            }

            val tag = read(bytes, entry, 2, littleEndian)
            val value = when (tag.toInt()) {
                TAG_RATING -> stars ?: 0
                TAG_RATING_PERCENT -> stars?.let { XmpRating.starsToPercent(it) } ?: 0
                else -> continue
            }

            if (read(bytes, entry + 4, 4, littleEndian) != 1L) {
                continue
            }

            val size = when (read(bytes, entry + 2, 2, littleEndian).toInt()) {
                TYPE_SHORT -> 2
                TYPE_LONG -> 4
                else -> continue
            }

            if (read(bytes, entry + 8, size, littleEndian) != value.toLong()) {
                write(bytes, entry + 8, size, littleEndian, value.toLong())
                changed = true
            }
        }
        return changed
    }

    /** Updates the rating tags in a JPEG EXIF segment payload ("Exif\0\0" followed by a TIFF structure). */
    fun patchJpegExif(jpeg: ByteArray, payloadStart: Int, payloadEnd: Int, stars: Int?): Boolean {
        val tiff = payloadStart + 6
        if (tiff + 8 > payloadEnd) {
            return false
        }

        val littleEndian = when {
            jpeg.u8(tiff) == 'I'.code && jpeg.u8(tiff + 1) == 'I'.code -> true
            jpeg.u8(tiff) == 'M'.code && jpeg.u8(tiff + 1) == 'M'.code -> false
            else -> return false
        }

        val ifd = tiff + read(jpeg, tiff + 4, 4, littleEndian)
        if (ifd + 2 > payloadEnd) {
            return false
        }

        val count = read(jpeg, ifd.toInt(), 2, littleEndian).toInt()
        val entriesEnd = ifd + 2 + count * ENTRY_SIZE
        if (entriesEnd > payloadEnd) {
            return false
        }

        return patchEntries(jpeg, ifd.toInt() + 2, count, littleEndian, stars)
    }

    private fun read(bytes: ByteArray, index: Int, size: Int, littleEndian: Boolean): Long {
        var value = 0L
        for (i in 0 until size) {
            val b = bytes.u8(if (littleEndian) index + size - 1 - i else index + i).toLong()
            value = (value shl 8) or b
        }
        return value
    }

    private fun write(bytes: ByteArray, index: Int, size: Int, littleEndian: Boolean, value: Long) {
        for (i in 0 until size) {
            val shift = 8 * (if (littleEndian) i else size - 1 - i)
            bytes[index + i] = (value shr shift).toByte()
        }
    }
}
