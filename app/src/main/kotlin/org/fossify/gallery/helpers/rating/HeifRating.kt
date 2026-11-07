package org.fossify.gallery.helpers.rating

/**
 * HEIC/HEIF: XMP is a metadata item (type "mime", content type "application/rdf+xml") described in the
 * top-level "meta" box and linked to the primary image with a "cdsc" reference.
 *
 * The new XMP data goes into an "mdat" box at the end of the file. Since "meta" changes size, every item
 * location (iloc) pointing past it is shifted to match. If the old XMP sits in such a trailing box
 * (from an earlier edit), that box is dropped instead of piling up.
 */
internal object HeifRating {
    private const val XMP_CONTENT_TYPE = "application/rdf+xml"

    private class ItemInfo(val id: Long, val isXmp: Boolean)

    private class Extent(val index: Long, var offset: Long, val length: Long)

    private class ItemLocation(val id: Long, val constructionMethod: Int, val dataReferenceIndex: Int, val baseOffset: Long, val extents: List<Extent>)

    private class Iloc(
        val version: Int,
        val flags: ByteArray,
        var offsetSize: Int,
        var lengthSize: Int,
        val baseOffsetSize: Int,
        val indexSize: Int,
        val items: MutableList<ItemLocation>
    )

    fun transform(file: ByteArray, stars: Int?): ByteArray? {
        val (top, topEnd) = parseIsoBoxes(file, 0, file.size) ?: return null
        if (top.firstOrNull()?.type != "ftyp" || topEnd != file.size) {
            return null
        }

        val meta = top.singleOrNull { it.type == "meta" } ?: return null
        val (children, childrenEnd) = parseIsoBoxes(file, meta.contentStart + 4, meta.end) ?: return null
        val iinfBox = children.firstOrNull { it.type == "iinf" } ?: return null
        val ilocBox = children.firstOrNull { it.type == "iloc" } ?: return null
        val irefBox = children.firstOrNull { it.type == "iref" }
        val pitmBox = children.firstOrNull { it.type == "pitm" }
        val idatBox = children.firstOrNull { it.type == "idat" }

        val itemInfos = parseIinf(file, iinfBox) ?: return null
        val iloc = parseIloc(file, ilocBox) ?: return null

        val xmpInfo = itemInfos.firstOrNull { it.isXmp }
        val xmpLocation = xmpInfo?.let { info -> iloc.items.firstOrNull { it.id == info.id } ?: return null }
        val oldXmp = xmpLocation?.let { readItem(file, it, idatBox) ?: return null }?.let { String(it, Charsets.UTF_8).trimEnd('\u0000') }
        val newXmp = XmpRating.updateOrCreate(oldXmp, stars)
        if (newXmp == oldXmp || newXmp == null) {
            return file
        }
        val xmpBytes = newXmp.toByteArray(Charsets.UTF_8)

        // If the old XMP is the only thing in the last box (our own trailing mdat), drop that box
        var keptEnd = file.size
        val last = top.last()
        if (xmpLocation != null && last !== meta && last.type == "mdat" && xmpLocation.constructionMethod == 0 &&
            xmpLocation.dataReferenceIndex == 0 && xmpLocation.extents.size == 1
        ) {
            val extent = xmpLocation.extents[0]
            if (xmpLocation.baseOffset + extent.offset == last.contentStart.toLong() && extent.length == (last.end - last.contentStart).toLong()) {
                keptEnd = last.start
            }
        }

        val xmpId = xmpInfo?.id ?: ((itemInfos.map { it.id } + iloc.items.map { it.id }).maxOrNull() ?: 0L) + 1
        if (xmpId > 0xFFFF) {
            return null
        }

        val newIinf = if (xmpInfo == null) addItemInfo(file, iinfBox, xmpId) ?: return null else null
        val primaryId = pitmBox?.let { readPrimaryItem(file, it) }
        val newIref = if (xmpInfo == null && primaryId != null) addDescribesReference(file, irefBox, xmpId, primaryId) ?: return null else null

        // Extents need explicit offsets and lengths so the XMP extent can be described
        if (iloc.offsetSize == 0) iloc.offsetSize = 4
        if (iloc.lengthSize == 0) iloc.lengthSize = 4

        val oldItems = iloc.items.filter { it.id != xmpId }
        fun buildMeta(delta: Long, xmpOffset: Long): ByteArray? {
            val items = ArrayList<ItemLocation>()
            for (item in oldItems) {
                items.add(shift(item, meta, delta) ?: return null)
            }
            items.add(ItemLocation(xmpId, 0, 0, 0, listOf(Extent(0, xmpOffset, xmpBytes.size.toLong()))))
            items.sortBy { it.id }
            val newIloc = serializeIloc(iloc, items) ?: return null

            val parts = ArrayList<ByteArray>()
            parts.add(file.copyOfRange(meta.contentStart, meta.contentStart + 4)) // version and flags
            for (child in children) {
                parts.add(
                    when {
                        child === ilocBox -> newIloc
                        child === iinfBox && newIinf != null -> newIinf
                        child === irefBox && newIref != null -> newIref
                        else -> file.copyOfRange(child.start, child.end)
                    }
                )
            }
            if (irefBox == null && newIref != null) {
                parts.add(newIref)
            }
            parts.add(file.copyOfRange(childrenEnd, meta.end))
            return buildIsoBox("meta", *parts.toTypedArray())
        }

        val oldMetaSize = (meta.end - meta.start).toLong()
        val draft = buildMeta(0, 0) ?: return null
        val delta = draft.size - oldMetaSize
        val xmpOffset = keptEnd + delta + 8 // after the new mdat header
        val newMeta = buildMeta(delta, xmpOffset) ?: return null
        if (newMeta.size != draft.size) {
            return null
        }

        return ByteWriter(file.size + newMeta.size + xmpBytes.size)
            .bytes(file, 0, meta.start)
            .bytes(newMeta)
            .bytes(file, meta.end, keptEnd - meta.end)
            .bytes(buildIsoBox("mdat", xmpBytes))
            .toByteArray()
    }

    /** Moves file offsets that point past the meta box by [delta]. */
    private fun shift(item: ItemLocation, meta: IsoBox, delta: Long): ItemLocation? {
        if (item.constructionMethod != 0 || item.dataReferenceIndex != 0) {
            return item // relative to idat or another item, or in another file
        }

        val extents = item.extents.map { extent ->
            val absolute = item.baseOffset + extent.offset
            when {
                absolute >= meta.end -> Extent(extent.index, extent.offset + delta, extent.length)
                absolute >= meta.start -> return null // pointing into meta itself, can't be moved safely
                else -> extent
            }
        }
        return ItemLocation(item.id, item.constructionMethod, item.dataReferenceIndex, item.baseOffset, extents)
    }

    private fun readItem(file: ByteArray, item: ItemLocation, idat: IsoBox?): ByteArray? {
        if (item.dataReferenceIndex != 0) {
            return null
        }

        val (start, end) = when (item.constructionMethod) {
            0 -> 0L to file.size.toLong()
            1 -> (idat ?: return null).contentStart.toLong() to idat.end.toLong()
            else -> return null
        }

        val out = ByteWriter()
        for (extent in item.extents) {
            val from = start + item.baseOffset + extent.offset
            if (extent.length <= 0 || from + extent.length > end) {
                return null
            }
            out.bytes(file, from.toInt(), extent.length.toInt())
        }
        return out.toByteArray()
    }

    private fun parseIinf(file: ByteArray, box: IsoBox): List<ItemInfo>? {
        val version = file.u8(box.contentStart)
        val entriesStart = box.contentStart + 4 + if (version == 0) 2 else 4
        val (entries, _) = parseIsoBoxes(file, entriesStart, box.end) ?: return null
        return entries.filter { it.type == "infe" }.map { infe ->
            val infeVersion = file.u8(infe.contentStart)
            var pos = infe.contentStart + 4
            val id: Long
            val itemType: String
            if (infeVersion >= 2) {
                id = if (infeVersion == 2) file.u16be(pos).toLong() else file.u32be(pos)
                pos += if (infeVersion == 2) 2 else 4
                pos += 2 // protection index
                itemType = file.fourCC(pos)
                pos += 4
            } else {
                id = file.u16be(pos).toLong()
                pos += 4 // id and protection index
                itemType = "mime"
            }

            val nameEnd = file.indexOfZero(pos, infe.end)
            val contentType = if (itemType == "mime" && nameEnd != -1) {
                val typeEnd = file.indexOfZero(nameEnd + 1, infe.end).let { if (it == -1) infe.end else it }
                String(file, nameEnd + 1, typeEnd - nameEnd - 1, Charsets.UTF_8)
            } else {
                ""
            }
            ItemInfo(id, itemType == "mime" && contentType == XMP_CONTENT_TYPE)
        }
    }

    private fun addItemInfo(file: ByteArray, box: IsoBox, id: Long): ByteArray? {
        val version = file.u8(box.contentStart)
        val countSize = if (version == 0) 2 else 4
        val count = file.uintBe(box.contentStart + 4, countSize) + 1
        if (!ByteWriter.fitsIn(count, countSize)) {
            return null
        }

        val infe = ByteWriter()
            .u32be(0x02000000) // version 2, no flags
            .u16be(id.toInt())
            .u16be(0) // not protected
            .fourCC("mime")
            .u8(0) // empty name
            .bytes(XMP_CONTENT_TYPE.toByteArray(Charsets.US_ASCII)).u8(0)
            .toByteArray()

        val header = ByteWriter().bytes(file, box.contentStart, 4).uintBe(count, countSize).toByteArray()
        val entriesStart = box.contentStart + 4 + countSize
        return buildIsoBox("iinf", header, file.copyOfRange(entriesStart, box.end), buildIsoBox("infe", infe))
    }

    private fun readPrimaryItem(file: ByteArray, box: IsoBox): Long {
        return if (file.u8(box.contentStart) == 0) file.u16be(box.contentStart + 4).toLong() else file.u32be(box.contentStart + 4)
    }

    /** Adds "the XMP item describes the primary image", which is how readers find image metadata. */
    private fun addDescribesReference(file: ByteArray, box: IsoBox?, fromId: Long, toId: Long): ByteArray? {
        val version = box?.let { file.u8(it.contentStart) } ?: 0
        val idSize = if (version == 0) 2 else 4
        if (!ByteWriter.fitsIn(toId, idSize)) {
            return null
        }

        val reference = ByteWriter().uintBe(fromId, idSize).u16be(1).uintBe(toId, idSize).toByteArray()
        val cdsc = buildIsoBox("cdsc", reference)
        return if (box == null) {
            buildIsoBox("iref", ByteArray(4), cdsc)
        } else {
            buildIsoBox("iref", file.copyOfRange(box.contentStart, box.end), cdsc)
        }
    }

    private fun parseIloc(file: ByteArray, box: IsoBox): Iloc? {
        var pos = box.contentStart
        val version = file.u8(pos)
        if (version > 2) {
            return null
        }

        val flags = file.copyOfRange(pos + 1, pos + 4)
        pos += 4
        val offsetSize = file.u8(pos) shr 4
        val lengthSize = file.u8(pos) and 0xF
        val baseOffsetSize = file.u8(pos + 1) shr 4
        val indexSize = if (version >= 1) file.u8(pos + 1) and 0xF else 0
        pos += 2
        if (listOf(offsetSize, lengthSize, baseOffsetSize, indexSize).any { it !in setOf(0, 4, 8) }) {
            return null
        }

        val idSize = if (version < 2) 2 else 4
        val itemCount = file.uintBe(pos, idSize)
        pos += idSize
        val items = ArrayList<ItemLocation>()
        repeat(itemCount.toInt()) {
            val id = file.uintBe(pos, idSize)
            pos += idSize
            val constructionMethod = if (version >= 1) file.u16be(pos) and 0xF else 0
            if (version >= 1) pos += 2
            val dataReferenceIndex = file.u16be(pos)
            pos += 2
            val baseOffset = file.uintBe(pos, baseOffsetSize)
            pos += baseOffsetSize
            val extentCount = file.u16be(pos)
            pos += 2
            val extents = ArrayList<Extent>()
            repeat(extentCount) {
                val index = if (indexSize > 0) file.uintBe(pos, indexSize) else 0
                pos += indexSize
                val offset = file.uintBe(pos, offsetSize)
                pos += offsetSize
                val length = file.uintBe(pos, lengthSize)
                pos += lengthSize
                extents.add(Extent(index, offset, length))
            }
            if (pos > box.end) {
                return null
            }
            items.add(ItemLocation(id, constructionMethod, dataReferenceIndex, baseOffset, extents))
        }
        return Iloc(version, flags, offsetSize, lengthSize, baseOffsetSize, indexSize, items)
    }

    private fun serializeIloc(iloc: Iloc, items: List<ItemLocation>): ByteArray? {
        val idSize = if (iloc.version < 2) 2 else 4
        if (!ByteWriter.fitsIn(items.size.toLong(), idSize)) {
            return null
        }

        return try {
            val out = ByteWriter()
                .u8(iloc.version)
                .bytes(iloc.flags)
                .u8((iloc.offsetSize shl 4) or iloc.lengthSize)
                .u8((iloc.baseOffsetSize shl 4) or iloc.indexSize)
                .uintBe(items.size.toLong(), idSize)

            for (item in items) {
                out.uintBe(item.id, idSize)
                if (iloc.version >= 1) {
                    out.u16be(item.constructionMethod)
                } else if (item.constructionMethod != 0) {
                    return null
                }
                out.u16be(item.dataReferenceIndex)
                out.uintBe(item.baseOffset, iloc.baseOffsetSize)
                out.u16be(item.extents.size)
                for (extent in item.extents) {
                    if (iloc.indexSize > 0) {
                        out.uintBe(extent.index, iloc.indexSize)
                    }
                    out.uintBe(extent.offset, iloc.offsetSize)
                    out.uintBe(extent.length, iloc.lengthSize)
                }
            }
            buildIsoBox("iloc", out.toByteArray())
        } catch (e: IllegalArgumentException) {
            null // a value doesn't fit its field
        }
    }
}
