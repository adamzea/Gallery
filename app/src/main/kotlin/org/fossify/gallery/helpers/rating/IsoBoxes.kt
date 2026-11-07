package org.fossify.gallery.helpers.rating

/** A box (atom) of an ISO base media file (MP4, MOV, 3GP, HEIF) held in memory. */
internal class IsoBox(val type: String, val start: Int, val contentStart: Int, val end: Int)

/**
 * Parses the boxes in [start, end). With [allowTerminator], a trailing run of fewer than 8 bytes or a
 * box with size 0 ends the list (QuickTime user data may end with a 32-bit zero). Returns null on
 * malformed data. The second value is where the parsed boxes end.
 */
internal fun parseIsoBoxes(bytes: ByteArray, start: Int, end: Int, allowTerminator: Boolean = false): Pair<List<IsoBox>, Int>? {
    val boxes = ArrayList<IsoBox>()
    var pos = start
    while (pos < end) {
        if (end - pos < 8) {
            return if (allowTerminator) boxes to pos else null
        }

        var size = bytes.u32be(pos)
        val type = bytes.fourCC(pos + 4)
        var headerSize = 8
        when (size) {
            0L -> {
                if (allowTerminator) {
                    return boxes to pos
                }
                size = (end - pos).toLong()
            }

            1L -> {
                if (end - pos < 16) {
                    return null
                }
                size = bytes.u64be(pos + 8)
                headerSize = 16
            }
        }

        if (size < headerSize || pos + size > end) {
            return null
        }

        boxes.add(IsoBox(type, pos, pos + headerSize, (pos + size).toInt()))
        pos += size.toInt()
    }
    return boxes to pos
}

/** A box with a 32-bit size header (or 64-bit if it doesn't fit). */
internal fun buildIsoBox(type: String, vararg content: ByteArray): ByteArray {
    val contentSize = content.sumOf { it.size.toLong() }
    val out = ByteWriter((contentSize + 16).toInt())
    if (contentSize + 8 <= 0xFFFFFFFFL) {
        out.u32be(contentSize + 8).fourCC(type)
    } else {
        out.u32be(1).fourCC(type).u64be(contentSize + 16)
    }
    content.forEach { out.bytes(it) }
    return out.toByteArray()
}
