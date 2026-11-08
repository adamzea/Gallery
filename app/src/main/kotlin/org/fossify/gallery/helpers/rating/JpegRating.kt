package org.fossify.gallery.helpers.rating

/** XMP lives in an APP1 segment starting with the XMP namespace. */
internal object JpegRating {
    private val XMP_HEADER = "${XmpRating.XMP_NAMESPACE}\u0000".toByteArray(Charsets.US_ASCII)
    private val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)

    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1
    private const val MAX_SEGMENT_LENGTH = 0xFFFF

    private class Layout(val xmpStart: Int, val xmpEnd: Int, val insertPosition: Int, val exifPayloadStart: Int, val exifEnd: Int)

    fun transform(original: ByteArray, stars: Int?): ByteArray? {
        val layout = parse(original) ?: return null
        // EXIF rating tags are fixed size and updated in place, the XMP segment may change size
        val jpeg = original.copyOf()
        if (layout.exifPayloadStart != -1) {
            ExifRating.patchJpegExif(jpeg, layout.exifPayloadStart, layout.exifEnd, stars)
        }

        val hasXmp = layout.xmpStart != -1
        val oldXmp = if (hasXmp) {
            val payloadStart = layout.xmpStart + 4 + XMP_HEADER.size
            String(jpeg, payloadStart, layout.xmpEnd - payloadStart, Charsets.UTF_8)
        } else {
            null
        }

        val newXmp = XmpRating.updateOrCreate(oldXmp, stars)
        if (newXmp == oldXmp || newXmp == null) {
            return jpeg
        }

        val payload = XMP_HEADER + newXmp.toByteArray(Charsets.UTF_8)
        val length = payload.size + 2
        if (length > MAX_SEGMENT_LENGTH) {
            return null
        }

        val segment = ByteWriter(length + 2).u8(0xFF).u8(MARKER_APP1).u16be(length).bytes(payload).toByteArray()
        val start = if (hasXmp) layout.xmpStart else layout.insertPosition
        val end = if (hasXmp) layout.xmpEnd else layout.insertPosition
        return ByteWriter(jpeg.size + segment.size)
            .bytes(jpeg, 0, start)
            .bytes(segment)
            .bytes(jpeg, end, jpeg.size - end)
            .toByteArray()
    }

    /** Walks the header segments up to the start of the image data. */
    private fun parse(data: ByteArray): Layout? {
        if (data.size < 4 || data.u8(0) != 0xFF || data.u8(1) != MARKER_SOI) {
            return null
        }

        var xmpStart = -1
        var xmpEnd = -1
        var exifPayloadStart = -1
        var exifEnd = -1
        // A new XMP segment goes after the leading JFIF (APP0) and EXIF (APP1) segments
        var insertPosition = 2
        var inLeadingAppSegments = true
        var pos = 2
        while (pos < data.size) {
            if (data.u8(pos) != 0xFF) {
                return null
            }

            val segmentStart = pos
            while (pos < data.size && data.u8(pos) == 0xFF) {
                pos++ // fill bytes
            }

            if (pos >= data.size) {
                return null
            }

            val marker = data.u8(pos)
            pos++
            if (marker == MARKER_SOS || marker == MARKER_EOI) {
                break
            }

            if (marker == 0x01 || marker in 0xD0..0xD7) {
                continue // standalone markers carry no length
            }

            if (pos + 2 > data.size) {
                return null
            }

            val length = data.u16be(pos)
            val segmentEnd = pos + length
            if (length < 2 || segmentEnd > data.size) {
                return null
            }

            val payloadStart = pos + 2
            if (marker == MARKER_APP1 && xmpStart == -1 && data.startsWithAt(payloadStart, XMP_HEADER, segmentEnd)) {
                xmpStart = segmentStart
                xmpEnd = segmentEnd
            }

            val isExif = marker == MARKER_APP1 && data.startsWithAt(payloadStart, EXIF_HEADER, segmentEnd)
            if (isExif && exifPayloadStart == -1) {
                exifPayloadStart = payloadStart
                exifEnd = segmentEnd
            }

            val isLeadingApp = marker == MARKER_APP0 || isExif
            if (inLeadingAppSegments && isLeadingApp) {
                insertPosition = segmentEnd
            } else {
                inLeadingAppSegments = false
            }

            pos = segmentEnd
        }

        return Layout(xmpStart, xmpEnd, insertPosition, exifPayloadStart, exifEnd)
    }
}
