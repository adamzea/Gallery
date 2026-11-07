package org.fossify.gallery.helpers.rating

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DNG, MP4/MOV and HEIF. */
class ContainerRatingTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(value: String) = value.toByteArray(Charsets.ISO_8859_1)
    private fun box(type: String, vararg content: ByteArray) = buildIsoBox(type, *content)
    private fun fullBoxHeader(version: Int = 0) = bytes(version, 0, 0, 0)

    // region DNG
    private class Tiff(val littleEndian: Boolean) {
        fun u16(b: ByteArray, i: Int) = if (littleEndian) b.u16le(i) else b.u16be(i)
        fun u32(b: ByteArray, i: Int) = if (littleEndian) b.u32le(i) else b.u32be(i)
        fun w16(w: ByteWriter, v: Int) = if (littleEndian) w.u16le(v) else w.u16be(v)
        fun w32(w: ByteWriter, v: Long) = if (littleEndian) w.u32le(v) else w.u32be(v)

        /** Header, IFD0 with ImageWidth (inline) and Make (stored after the IFD), then some image data. */
        fun build(): ByteArray {
            val w = ByteWriter()
            w.bytes(if (littleEndian) "II".toByteArray() else "MM".toByteArray())
            w16(w, 42)
            w32(w, 8)
            w16(w, 2)
            w16(w, 256); w16(w, 4); w32(w, 1); w32(w, 4000) // ImageWidth
            w16(w, 271); w16(w, 2); w32(w, 6); w32(w, 8 + 2 + 24 + 4) // Make -> right after the IFD
            w32(w, 0) // no next IFD
            w.bytes("Canon\u0000".toByteArray())
            w.bytes(ByteArray(64) { it.toByte() }) // image data
            return w.toByteArray()
        }

        /** Returns tag -> (count, value or offset) of IFD0. */
        fun ifd0(file: ByteArray): Map<Int, Pair<Long, Long>> {
            val offset = u32(file, 4).toInt()
            val count = u16(file, offset)
            return (0 until count).associate {
                val entry = offset + 2 + it * 12
                u16(file, entry) to (u32(file, entry + 4) to u32(file, entry + 8))
            }
        }

        fun xmp(file: ByteArray): String? {
            val (count, offset) = ifd0(file)[700] ?: return null
            return String(file, offset.toInt(), count.toInt(), Charsets.UTF_8)
        }
    }

    private fun dngRoundTrip(littleEndian: Boolean) {
        val tiff = Tiff(littleEndian)
        val original = tiff.build()
        val data = ByteArrayData(original)

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.DNG, 4))
        val rated = data.bytes
        val ifd = tiff.ifd0(rated)
        assertEquals(listOf(256, 271, 700), ifd.keys.toList())
        assertEquals(4000L, ifd[256]!!.second)
        assertEquals("Canon", String(rated, ifd[271]!!.second.toInt(), 5))
        assertEquals(0L, ifd[700]!!.second % 2)
        assertTrue(tiff.xmp(rated)!!.contains("xmp:Rating=\"4\""))
        assertArrayEquals(original.copyOfRange(8, original.size), rated.copyOfRange(8, original.size))

        // later edits reuse the padded space instead of growing the file
        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.DNG, null))
        assertEquals(rated.size, data.bytes.size)
        assertFalse(tiff.xmp(data.bytes)!!.contains("Rating"))
        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.DNG, 4))
        assertEquals(rated.size, data.bytes.size)
        assertEquals(RatingResult.UNCHANGED, MediaRating.write(data, RatingFormat.DNG, 4))
    }

    @Test
    fun dngLittleEndian() = dngRoundTrip(true)

    @Test
    fun dngBigEndian() = dngRoundTrip(false)

    @Test
    fun dngWithoutXmpUnchangedWhenClearing() {
        assertEquals(RatingResult.UNCHANGED, MediaRating.write(ByteArrayData(Tiff(true).build()), RatingFormat.DNG, null))
        assertEquals(RatingResult.FAILED, MediaRating.write(ByteArrayData(ascii("not a tiff file")), RatingFormat.DNG, 4))
    }
    // endregion

    // region MP4 / MOV
    private val xmpUuid = bytes(0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8, 0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC)
    private val mediaData = ByteArray(5000) { (it * 7).toByte() }

    private fun ftyp(brand: String) = box("ftyp", ascii(brand), ByteArray(4), ascii(brand))
    private fun moov(vararg extra: ByteArray) = box("moov", box("mvhd", ByteArray(100) { 1 }), box("trak", box("stco", ByteArray(8))), *extra)

    private fun topLevel(file: ByteArray) = parseIsoBoxes(file, 0, file.size)!!.first

    private fun child(file: ByteArray, parent: IsoBox, type: String, allowTerminator: Boolean = false) =
        parseIsoBoxes(file, parent.contentStart, parent.end, allowTerminator)!!.first.firstOrNull { it.type == type }

    private fun content(file: ByteArray, box: IsoBox) = file.copyOfRange(box.contentStart, box.end)

    /** The value of WM/SharedUserRating in moov/udta/Xtra, or null. */
    private fun xtraRating(file: ByteArray): Long? {
        val moov = topLevel(file).single { it.type == "moov" }
        val udta = child(file, moov, "udta") ?: return null
        val xtra = child(file, udta, "Xtra", true) ?: return null
        val data = content(file, xtra)
        var pos = 0
        while (pos < data.size) {
            val size = data.u32be(pos).toInt()
            val nameLength = data.u32be(pos + 4).toInt()
            if (String(data, pos + 8, nameLength, Charsets.ISO_8859_1) == "WM/SharedUserRating") {
                val value = pos + 8 + nameLength
                assertEquals(1L, data.u32be(value))
                assertEquals(14L, data.u32be(value + 4))
                assertEquals(19, data.u16be(value + 8))
                return data.u32le(value + 10) or (data.u32le(value + 14) shl 32)
            }
            pos += size
        }
        return null
    }

    private fun mp4Xmp(file: ByteArray): String? {
        val uuid = topLevel(file).firstOrNull { it.type == "uuid" && file.startsWithAt(it.contentStart, xmpUuid) } ?: return null
        return String(file, uuid.contentStart + 16, uuid.end - uuid.contentStart - 16, Charsets.UTF_8)
    }

    private fun mdatIntact(file: ByteArray, offset: Int) {
        val mdat = topLevel(file).single { it.type == "mdat" }
        assertEquals(offset, mdat.start)
        assertArrayEquals(mediaData, content(file, mdat))
    }

    @Test
    fun mp4WithMoovFirstKeepsMediaDataInPlace() {
        val ftyp = ftyp("isom")
        val moov = moov()
        val original = ftyp + moov + box("mdat", mediaData)
        val data = ByteArrayData(original)

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.ISO_MEDIA, 4))
        val rated = data.bytes
        assertEquals(75L, xtraRating(rated))
        assertTrue(mp4Xmp(rated)!!.contains("xmp:Rating=\"4\""))
        mdatIntact(rated, ftyp.size + moov.size)
        // the old moov became free space, the new one was added after mdat
        assertEquals(listOf("ftyp", "free", "mdat", "moov", "uuid"), topLevel(rated).map { it.type })

        assertEquals(RatingResult.UNCHANGED, MediaRating.write(data, RatingFormat.ISO_MEDIA, 4))

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.ISO_MEDIA, null))
        val cleared = data.bytes
        assertNull(xtraRating(cleared))
        assertFalse(mp4Xmp(cleared)!!.contains("Rating"))
        mdatIntact(cleared, ftyp.size + moov.size)
        // edits at the end of the file are done in place, so the file doesn't keep growing
        assertTrue(cleared.size <= rated.size)
        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.ISO_MEDIA, 4))
        assertEquals(rated.size, data.bytes.size)
    }

    @Test
    fun mp4UsesFollowingFreeSpace() {
        val ftyp = ftyp("mp42")
        val moov = moov()
        val free = box("free", ByteArray(1000))
        val data = ByteArrayData(ftyp + moov + free + box("mdat", mediaData))

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.ISO_MEDIA, 4))
        assertEquals(listOf("ftyp", "moov", "free", "mdat", "uuid"), topLevel(data.bytes).map { it.type })
        assertEquals(75L, xtraRating(data.bytes))
        mdatIntact(data.bytes, ftyp.size + moov.size + free.size)
    }

    @Test
    fun mp4KeepsOtherXtraEntriesAndUserData() {
        val name = ascii("WM/Category")
        val value = ByteWriter().u32be(1).u32be(6 + 4).u16be(8).bytes(bytes(0x41, 0, 0, 0)).toByteArray()
        val entry = ByteWriter().u32be(8L + name.size + value.size).u32be(name.size.toLong()).bytes(name).bytes(value).toByteArray()
        val udta = box("udta", box("Xtra", entry), box("©nam", ascii("Holiday")))
        val data = ByteArrayData(ftyp("isom") + box("mdat", mediaData) + moov(udta))

        MediaRating.write(data, RatingFormat.ISO_MEDIA, 4)
        MediaRating.write(data, RatingFormat.ISO_MEDIA, null)
        val file = data.bytes
        val moov = topLevel(file).single { it.type == "moov" }
        val newUdta = child(file, moov, "udta")!!
        assertArrayEquals(entry, content(file, child(file, newUdta, "Xtra")!!))
        assertNotNull(child(file, newUdta, "©nam"))
    }

    @Test
    fun movStoresXmpInUserData() {
        val terminatedUdta = box("udta", box("©day", ascii("2024")), ByteArray(4))
        val data = ByteArrayData(ftyp("qt  ") + box("wide") + box("mdat", mediaData) + moov(terminatedUdta))

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.ISO_MEDIA, 4))
        val file = data.bytes
        assertTrue(topLevel(file).none { it.type == "uuid" })
        val moov = topLevel(file).single { it.type == "moov" }
        val udta = child(file, moov, "udta", true)!!
        val xmp = child(file, udta, "XMP_", true)!!
        assertTrue(String(content(file, xmp), Charsets.UTF_8).contains("xmp:Rating=\"4\""))
        assertNotNull(child(file, udta, "©day", true))
        assertEquals(75L, xtraRating(file))
        // the zero terminator stays at the end
        assertArrayEquals(ByteArray(4), file.copyOfRange(udta.end - 4, udta.end))
    }

    @Test
    fun mp4WithOpenEndedMediaData() {
        val ftyp = ftyp("isom")
        val moov = moov()
        val openMdat = ByteWriter().u32be(0).fourCC("mdat").bytes(mediaData).toByteArray()
        val data = ByteArrayData(ftyp + moov + openMdat)

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.ISO_MEDIA, 4))
        mdatIntact(data.bytes, ftyp.size + moov.size)
        assertEquals(75L, xtraRating(data.bytes))
    }

    @Test
    fun mp4Failures() {
        assertEquals(RatingResult.FAILED, MediaRating.write(ByteArrayData(ftyp("isom") + box("mdat", mediaData)), RatingFormat.ISO_MEDIA, 4))
        assertEquals(RatingResult.FAILED, MediaRating.write(ByteArrayData(ascii("garbage data!!")), RatingFormat.ISO_MEDIA, 4))
    }
    // endregion

    // region HEIF
    private val primaryImage = ByteArray(300) { (it * 3).toByte() }
    private val thumbnail = ByteArray(40) { (it + 100).toByte() }

    private fun infe(id: Int, type: String) = box("infe", fullBoxHeader(2), ByteWriter().u16be(id).u16be(0).fourCC(type).u8(0).toByteArray())

    /** ftyp, meta (hdlr, pitm, iinf, iloc v0 with 4 byte fields), mdat with the primary image and thumbnail. */
    private fun heic(metaFirst: Boolean = true): ByteArray {
        val ftyp = box("ftyp", ascii("heic"), ByteArray(4), ascii("mif1heic"))
        fun meta(mdatContentOffset: Long) = box(
            "meta", fullBoxHeader(),
            box("hdlr", fullBoxHeader(), ByteArray(4), ascii("pict"), ByteArray(13)),
            box("pitm", fullBoxHeader(), ByteWriter().u16be(1).toByteArray()),
            box("iinf", fullBoxHeader(), ByteWriter().u16be(2).toByteArray(), infe(1, "hvc1"), infe(2, "hvc1")),
            box(
                "iloc", fullBoxHeader(), bytes(0x44, 0x00),
                ByteWriter().u16be(2)
                    .u16be(1).u16be(0).u16be(1).u32be(mdatContentOffset).u32be(primaryImage.size.toLong())
                    .u16be(2).u16be(0).u16be(1).u32be(mdatContentOffset + primaryImage.size).u32be(thumbnail.size.toLong())
                    .toByteArray()
            )
        )

        val mdat = box("mdat", primaryImage, thumbnail)
        return if (metaFirst) {
            val metaSize = meta(0).size
            ftyp + meta(ftyp.size + metaSize + 8L) + mdat
        } else {
            ftyp + mdat + meta(ftyp.size + 8L)
        }
    }

    private class HeifItems(val types: Map<Long, Pair<String, String>>, val data: Map<Long, ByteArray>, val references: List<Triple<String, Long, Long>>)

    /** Independent reader for the subset of HEIF written here and by the helper. */
    private fun readHeif(file: ByteArray): HeifItems {
        val meta = topLevel(file).single { it.type == "meta" }
        val children = parseIsoBoxes(file, meta.contentStart + 4, meta.end)!!.first

        val iinf = children.single { it.type == "iinf" }
        val infes = parseIsoBoxes(file, iinf.contentStart + 6, iinf.end)!!.first
        assertEquals(infes.size, file.u16be(iinf.contentStart + 4))
        val types = infes.associate { infe ->
            val p = infe.contentStart + 4
            val type = file.fourCC(p + 4)
            val nameEnd = file.indexOfZero(p + 8, infe.end)
            val contentType = if (type == "mime") String(file, nameEnd + 1, file.indexOfZero(nameEnd + 1, infe.end) - nameEnd - 1) else ""
            file.u16be(p).toLong() to (type to contentType)
        }

        val iloc = children.single { it.type == "iloc" }
        val sizes = file.u8(iloc.contentStart + 4)
        val offsetSize = sizes shr 4
        val lengthSize = sizes and 0xF
        val baseSize = file.u8(iloc.contentStart + 5) shr 4
        var pos = iloc.contentStart + 6
        val count = file.u16be(pos)
        pos += 2
        val data = HashMap<Long, ByteArray>()
        repeat(count) {
            val id = file.u16be(pos).toLong()
            pos += 4 // id, data reference index
            val base = file.uintBe(pos, baseSize)
            pos += baseSize
            val extents = file.u16be(pos)
            pos += 2
            val out = ByteWriter()
            repeat(extents) {
                val offset = base + file.uintBe(pos, offsetSize)
                pos += offsetSize
                val length = file.uintBe(pos, lengthSize)
                pos += lengthSize
                out.bytes(file, offset.toInt(), length.toInt())
            }
            data[id] = out.toByteArray()
        }

        val references = children.firstOrNull { it.type == "iref" }?.let { iref ->
            parseIsoBoxes(file, iref.contentStart + 4, iref.end)!!.first.map {
                Triple(it.type, file.u16be(it.contentStart).toLong(), file.u16be(it.contentStart + 4).toLong())
            }
        } ?: emptyList()
        return HeifItems(types, data, references)
    }

    private fun heicRoundTrip(original: ByteArray) {
        val data = ByteArrayData(original)
        assertArrayEquals(primaryImage, readHeif(original).data[1])

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.HEIF, 4))
        val rated = readHeif(data.bytes)
        assertEquals("mime" to "application/rdf+xml", rated.types[3])
        assertTrue(String(rated.data[3]!!, Charsets.UTF_8).contains("xmp:Rating=\"4\""))
        assertArrayEquals(primaryImage, rated.data[1])
        assertArrayEquals(thumbnail, rated.data[2])
        assertEquals(listOf(Triple("cdsc", 3L, 1L)), rated.references)
        val ratedSize = data.bytes.size

        assertEquals(RatingResult.UNCHANGED, MediaRating.write(data, RatingFormat.HEIF, 4))

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.HEIF, null))
        val cleared = readHeif(data.bytes)
        assertFalse(String(cleared.data[3]!!, Charsets.UTF_8).contains("Rating"))
        assertArrayEquals(primaryImage, cleared.data[1])
        assertArrayEquals(thumbnail, cleared.data[2])
        assertEquals(1, cleared.references.size)
        // the previous XMP box at the end gets replaced, not piled up
        assertTrue(data.bytes.size <= ratedSize)

        assertEquals(RatingResult.CHANGED, MediaRating.write(data, RatingFormat.HEIF, 4))
        assertEquals(ratedSize, data.bytes.size)
        assertEquals(1, topLevel(data.bytes).count { it.type == "meta" })
    }

    @Test
    fun heicWithMetaBeforeMediaData() = heicRoundTrip(heic(metaFirst = true))

    @Test
    fun heicWithMetaAfterMediaData() = heicRoundTrip(heic(metaFirst = false))

    @Test
    fun heicFailures() {
        assertEquals(RatingResult.UNCHANGED, MediaRating.write(ByteArrayData(heic()), RatingFormat.HEIF, null))
        assertEquals(RatingResult.FAILED, MediaRating.write(ByteArrayData(box("ftyp", ascii("heic")) + box("mdat", primaryImage)), RatingFormat.HEIF, 4))
    }
    // endregion

    @Test
    fun formatDetection() {
        assertEquals(RatingFormat.JPEG, MediaRating.formatOf("/a/b.JPEG"))
        assertEquals(RatingFormat.HEIF, MediaRating.formatOf("/a/b.heic"))
        assertEquals(RatingFormat.DNG, MediaRating.formatOf("/a/b.dng"))
        assertEquals(RatingFormat.ISO_MEDIA, MediaRating.formatOf("/a/b.MOV"))
        assertNull(MediaRating.formatOf("/a/b.mkv"))
        assertNull(MediaRating.formatOf("/a/b.cr2"))
        assertNull(MediaRating.formatOf("/a/noextension"))
    }
}
