package org.fossify.gallery.helpers.rating

/**
 * MP4 / MOV / 3GP videos. Writes two ratings:
 * - the Microsoft "Xtra" rating in moov/udta/Xtra, which is what Windows Explorer shows for videos;
 * - XMP `xmp:Rating`, in a top-level "uuid" box for MP4 or in moov/udta/XMP_ for QuickTime MOV.
 *
 * Videos can be gigabytes, so only the boxes that change are rewritten. A box that grows is rewritten in
 * place when it is at the end of the file or followed by enough free space; otherwise the new copy is
 * appended and the old one is turned into a "free" box. The media data never moves, so the sample
 * offsets inside moov stay valid.
 */
internal object IsoMediaRating {
    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte()
    )
    private val FREE_TYPES = setOf("free", "skip")
    private const val MAX_MOOV_SIZE = 64 * 1024 * 1024
    private const val MAX_XMP_SIZE = 16 * 1024 * 1024

    /** A top-level box in the file, headers only. */
    private class TopBox(val type: String, val offset: Long, val size: Long, val headerSize: Int, val extendsToEnd: Boolean) {
        val end get() = offset + size
    }

    fun write(data: RandomAccessData, stars: Int?): RatingResult {
        val boxes = scanTopLevel(data) ?: return RatingResult.FAILED
        val ftyp = boxes.firstOrNull { it.type == "ftyp" }
        val isQuickTime = ftyp == null || String(data.read(ftyp.offset + ftyp.headerSize, 4), Charsets.ISO_8859_1) == "qt  "
        val moov = boxes.singleOrNull { it.type == "moov" } ?: return RatingResult.FAILED
        if (moov.size > MAX_MOOV_SIZE) {
            return RatingResult.FAILED
        }

        var changed = false
        val moovBytes = data.read(moov.offset, moov.size.toInt())
        val newMoov = updateMoov(moovBytes, isQuickTime, stars) ?: return RatingResult.FAILED
        if (!newMoov.contentEquals(moovBytes)) {
            placeBox(data, moov.offset, newMoov)
            changed = true
        }

        if (!isQuickTime) {
            val current = scanTopLevel(data) ?: return RatingResult.FAILED
            val uuid = current.firstOrNull { it.type == "uuid" && it.size >= it.headerSize + 16 && data.read(it.offset + it.headerSize, 16).contentEquals(XMP_UUID) }
            val oldXmp = uuid?.let {
                val length = it.size - it.headerSize - 16
                if (length > MAX_XMP_SIZE) {
                    return RatingResult.FAILED
                }
                String(data.read(it.offset + it.headerSize + 16, length.toInt()), Charsets.UTF_8)
            }

            val newXmp = XmpRating.updateOrCreate(oldXmp, stars)
            if (newXmp != oldXmp && newXmp != null) {
                placeBox(data, uuid?.offset, buildIsoBox("uuid", XMP_UUID, newXmp.toByteArray(Charsets.UTF_8)))
                changed = true
            }
        }

        return if (changed) RatingResult.CHANGED else RatingResult.UNCHANGED
    }

    private fun scanTopLevel(data: RandomAccessData): List<TopBox>? {
        val length = data.length()
        val boxes = ArrayList<TopBox>()
        var pos = 0L
        while (pos + 8 <= length) {
            val header = data.read(pos, 8)
            var size = header.u32be(0)
            val type = header.fourCC(4)
            var headerSize = 8
            var extendsToEnd = false
            when (size) {
                0L -> {
                    size = length - pos
                    extendsToEnd = true
                }

                1L -> {
                    if (pos + 16 > length) {
                        return null
                    }
                    size = data.read(pos + 8, 8).u64be(0)
                    headerSize = 16
                }
            }

            if (size < headerSize || pos + size > length) {
                return null
            }

            boxes.add(TopBox(type, pos, size, headerSize, extendsToEnd))
            pos += size
        }
        return boxes.takeIf { it.isNotEmpty() }
    }

    /**
     * Writes [newBox] in place of the top-level box at [oldOffset], or appends it if [oldOffset] is null.
     */
    private fun placeBox(data: RandomAccessData, oldOffset: Long?, newBox: ByteArray) {
        val boxes = scanTopLevel(data) ?: throw IllegalStateException("Unreadable file")
        val index = boxes.indexOfFirst { it.offset == oldOffset }
        if (oldOffset == null || index == -1) {
            append(data, boxes, newBox)
            return
        }

        val old = boxes[index]
        if (old.size == newBox.size.toLong()) {
            data.write(old.offset, newBox)
            return
        }

        var next = index + 1
        var available = old.size
        while (next < boxes.size && boxes[next].type in FREE_TYPES) {
            available += boxes[next].size
            next++
        }

        val isLast = next == boxes.size && boxes.last().end == data.length()
        if (isLast) {
            data.write(old.offset, newBox)
            data.truncate(old.offset + newBox.size)
            return
        }

        val leftover = available - newBox.size
        if (leftover == 0L || leftover in 8..0xFFFFFFFFL) {
            data.write(old.offset, newBox)
            if (leftover > 0) {
                data.write(old.offset + newBox.size, ByteWriter(8).u32be(leftover).fourCC("free").toByteArray())
            }
            return
        }

        // Doesn't fit: add the new copy at the end first, then retire the old one, so the file always has a valid box
        append(data, boxes, newBox)
        data.write(old.offset + 4, "free".toByteArray(Charsets.ISO_8859_1))
    }

    private fun append(data: RandomAccessData, boxes: List<TopBox>, newBox: ByteArray) {
        var remaining = boxes
        // reuse trailing free space
        while (remaining.size > 1 && remaining.last().type in FREE_TYPES) {
            remaining = remaining.dropLast(1)
        }

        val last = remaining.last()
        if (last.extendsToEnd) {
            // a box with size 0 runs to the end of the file, give it an explicit size before adding anything after it
            if (last.headerSize != 8 || last.size > 0xFFFFFFFFL) {
                throw IllegalStateException("Can't append after an open-ended box")
            }
            data.write(last.offset, ByteWriter(4).u32be(last.size).toByteArray())
        }

        if (last.end < data.length()) {
            data.truncate(last.end)
        }
        data.write(last.end, newBox)
    }

    private fun updateMoov(moov: ByteArray, isQuickTime: Boolean, stars: Int?): ByteArray? {
        val headerSize = if (moov.u32be(0) == 1L) 16 else 8
        val (children, childrenEnd) = parseIsoBoxes(moov, headerSize, moov.size) ?: return null
        val udta = children.firstOrNull { it.type == "udta" }
        val oldUdta = udta?.let { moov.copyOfRange(it.contentStart, it.end) } ?: ByteArray(0)
        val newUdta = updateUserData(oldUdta, isQuickTime, stars) ?: return null
        if (newUdta.contentEquals(oldUdta)) {
            return moov
        }

        val parts = ArrayList<ByteArray>()
        for (child in children) {
            if (child === udta) {
                if (newUdta.isNotEmpty()) {
                    parts.add(buildIsoBox("udta", newUdta))
                }
            } else {
                parts.add(moov.copyOfRange(child.start, child.end))
            }
        }

        if (udta == null) {
            parts.add(buildIsoBox("udta", newUdta))
        }
        parts.add(moov.copyOfRange(childrenEnd, moov.size))
        return buildIsoBox("moov", *parts.toTypedArray())
    }

    private fun updateUserData(udta: ByteArray, isQuickTime: Boolean, stars: Int?): ByteArray? {
        val (boxes, boxesEnd) = parseIsoBoxes(udta, 0, udta.size, allowTerminator = true) ?: return null
        val xtra = boxes.firstOrNull { it.type == "Xtra" }
        val oldXtra = xtra?.let { udta.copyOfRange(it.contentStart, it.end) } ?: ByteArray(0)
        val newXtra = XtraRating.update(oldXtra, stars) ?: return null

        val xmpBox = if (isQuickTime) boxes.firstOrNull { it.type == "XMP_" } else null
        val oldXmp = xmpBox?.let { String(udta, it.contentStart, it.end - it.contentStart, Charsets.UTF_8) }
        val newXmp = if (isQuickTime) XmpRating.updateOrCreate(oldXmp, stars) else null

        if (newXtra.contentEquals(oldXtra) && newXmp == oldXmp) {
            return udta
        }

        val out = ByteWriter(udta.size + 512)
        for (box in boxes) {
            when {
                box === xtra -> if (newXtra.isNotEmpty()) out.bytes(buildIsoBox("Xtra", newXtra))
                box === xmpBox -> out.bytes(buildIsoBox("XMP_", newXmp!!.toByteArray(Charsets.UTF_8)))
                else -> out.bytes(udta, box.start, box.end - box.start)
            }
        }

        if (xtra == null && newXtra.isNotEmpty()) {
            out.bytes(buildIsoBox("Xtra", newXtra))
        }

        if (xmpBox == null && newXmp != null) {
            out.bytes(buildIsoBox("XMP_", newXmp.toByteArray(Charsets.UTF_8)))
        }

        // keep a QuickTime style zero terminator, if there was one
        out.bytes(udta, boxesEnd, udta.size - boxesEnd)
        return out.toByteArray()
    }
}

/**
 * The undocumented Microsoft "Xtra" box written by Windows. A list of entries:
 * size (u32), name length (u32), name, value count (u32), then per value: size incl. this header (u32),
 * type (u16) and the value. Headers are big-endian, values little-endian. The rating is
 * "WM/SharedUserRating", an int64 (type 19) on the same 1-99 scale as MicrosoftPhoto:Rating.
 */
internal object XtraRating {
    private const val RATING_NAME = "WM/SharedUserRating"
    private const val TYPE_INT64 = 19

    /** Returns the new box content, empty if no entries are left, or null if [content] is malformed. */
    fun update(content: ByteArray, stars: Int?): ByteArray? {
        val newEntry = stars?.let { ratingEntry(it) }
        var entryWritten = false
        val out = ByteWriter(content.size + 64)
        var pos = 0
        while (pos + 4 <= content.size) {
            val size = content.u32be(pos)
            if (size < 8 || pos + size > content.size) {
                return null
            }

            val nameLength = content.u32be(pos + 4)
            if (nameLength + 18 > size) {
                return null
            }

            val name = String(content, pos + 8, nameLength.toInt(), Charsets.ISO_8859_1)
            when {
                name != RATING_NAME -> out.bytes(content, pos, size.toInt())
                newEntry != null && !entryWritten -> {
                    // replace in place, so writing the same rating again leaves the file untouched
                    out.bytes(newEntry)
                    entryWritten = true
                }
            }
            pos += size.toInt()
        }

        if (newEntry != null && !entryWritten) {
            out.bytes(newEntry)
        }
        return out.toByteArray()
    }

    private fun ratingEntry(stars: Int): ByteArray {
        val name = RATING_NAME.toByteArray(Charsets.ISO_8859_1)
        val entrySize = 4 + 4 + name.size + 4 + 4 + 2 + 8
        return ByteWriter(entrySize)
            .u32be(entrySize.toLong())
            .u32be(name.size.toLong())
            .bytes(name)
            .u32be(1) // one value
            .u32be(4 + 2 + 8)
            .u16be(TYPE_INT64)
            .u64le(XmpRating.starsToPercent(stars).toLong())
            .toByteArray()
    }
}
