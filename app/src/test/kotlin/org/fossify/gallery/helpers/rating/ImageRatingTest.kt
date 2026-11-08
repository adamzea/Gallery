package org.fossify.gallery.helpers.rating

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import java.util.zip.Deflater

/** JPEG, PNG and WebP, the formats that are rewritten in memory. */
class ImageRatingTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(value: String) = value.toByteArray(Charsets.ISO_8859_1)

    private fun write(data: ByteArray, format: RatingFormat, stars: Int?): Pair<RatingResult, ByteArray> {
        val store = ByteArrayData(data)
        return MediaRating.write(store, format, stars) to store.bytes
    }

    // region JPEG
    private val xmpHeader = ascii("http://ns.adobe.com/xap/1.0/\u0000")
    private val jpegScan = segment(0xDA, bytes(1, 2, 3)) + bytes(0x12, 0x34, 0xFF, 0x00, 0x56, 0xFF, 0xD9)
    private val jfif = segment(0xE0, ascii("JFIF\u0000\u0001\u0002"))
    private val exif = segment(0xE1, ascii("Exif\u0000\u0000MM\u0000*"))
    private val dqt = segment(0xDB, ByteArray(5) { it.toByte() })

    private fun segment(marker: Int, payload: ByteArray) =
        bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    private fun jpeg(vararg parts: ByteArray) = parts.fold(bytes(0xFF, 0xD8)) { acc, part -> acc + part } + jpegScan

    private fun jpegXmp(jpeg: ByteArray): String? {
        var pos = 2
        while (jpeg.u8(pos + 1) != 0xDA) {
            val length = jpeg.u16be(pos + 2)
            if (jpeg.u8(pos + 1) == 0xE1 && jpeg.startsWithAt(pos + 4, xmpHeader)) {
                return String(jpeg, pos + 4 + xmpHeader.size, length - 2 - xmpHeader.size, Charsets.UTF_8)
            }
            pos += 2 + length
        }
        return null
    }

    @Test
    fun jpegAddsXmpAfterJfifAndExif() {
        val original = jpeg(jfif, exif, dqt)
        val (result, updated) = write(original, RatingFormat.JPEG, 4)
        assertEquals(RatingResult.CHANGED, result)
        assertTrue(jpegXmp(updated)!!.contains("xmp:Rating=\"4\""))
        val xmpStart = 2 + jfif.size + exif.size
        assertArrayEquals(jfif + exif, updated.copyOfRange(2, xmpStart))
        assertEquals(0xE1, updated.u8(xmpStart + 1))
        assertArrayEquals(jpegScan, updated.copyOfRange(updated.size - jpegScan.size, updated.size))
    }

    @Test
    fun jpegReplacesThenClearsRating() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2" xmp:CreatorTool="Cam"/></rdf:RDF></x:xmpmeta>"""
        val original = jpeg(jfif, segment(0xE1, xmpHeader + ascii(xmp)), dqt)

        val (_, rated) = write(original, RatingFormat.JPEG, 4)
        assertTrue(jpegXmp(rated)!!.contains("xmp:Rating=\"4\""))
        assertEquals(original.size, rated.size)

        val (result, cleared) = write(rated, RatingFormat.JPEG, null)
        assertEquals(RatingResult.CHANGED, result)
        assertFalse(jpegXmp(cleared)!!.contains("Rating"))
        assertTrue(jpegXmp(cleared)!!.contains("xmp:CreatorTool=\"Cam\""))
        assertArrayEquals(jpegScan, cleared.copyOfRange(cleared.size - jpegScan.size, cleared.size))
    }

    @Test
    fun jpegUnchangedCases() {
        val plain = jpeg(jfif, dqt)
        assertEquals(RatingResult.UNCHANGED, write(plain, RatingFormat.JPEG, null).first)
        val (_, rated) = write(plain, RatingFormat.JPEG, 4)
        assertEquals(RatingResult.UNCHANGED, write(rated, RatingFormat.JPEG, 4).first)
    }

    /** EXIF segment whose IFD0 has Rating and RatingPercent set to 0, as many cameras write them. */
    private fun exifWithRating(littleEndian: Boolean): ByteArray {
        val w = ByteWriter().bytes(ascii("Exif\u0000\u0000"))
        fun u16(v: Int) = if (littleEndian) w.u16le(v) else w.u16be(v)
        fun u32(v: Long) = if (littleEndian) w.u32le(v) else w.u32be(v)
        w.bytes(ascii(if (littleEndian) "II" else "MM")); u16(42); u32(8)
        u16(3)
        u16(0x010F); u16(2); u32(4); w.bytes(ascii("Pix\u0000")) // Make, inline
        u16(0x4746); u16(3); u32(1); u16(0); u16(0) // Rating
        u16(0x4749); u16(3); u32(1); u16(0); u16(0) // RatingPercent
        u32(0)
        return segment(0xE1, w.toByteArray())
    }

    private fun exifRatings(jpeg: ByteArray, littleEndian: Boolean): Pair<Int, Int> {
        val entries = 2 + 4 + 6 + 8 + 2 // SOI, segment header, "Exif\0\0", TIFF header, entry count
        fun u16(i: Int) = if (littleEndian) jpeg.u16le(i) else jpeg.u16be(i)
        assertEquals(0x4746, u16(entries + 12))
        assertEquals(0x4749, u16(entries + 24))
        return u16(entries + 12 + 8) to u16(entries + 24 + 8)
    }

    @Test
    fun jpegUpdatesExistingExifRatingTags() {
        for (littleEndian in listOf(true, false)) {
            val original = jpeg(exifWithRating(littleEndian), dqt)
            val (_, rated) = write(original, RatingFormat.JPEG, 4)
            assertEquals(4 to 75, exifRatings(rated, littleEndian))
            assertTrue(jpegXmp(rated)!!.contains("xmp:Rating=\"4\""))

            val (_, cleared) = write(rated, RatingFormat.JPEG, null)
            assertEquals(0 to 0, exifRatings(cleared, littleEndian))
        }
    }

    @Test
    fun jpegRejectsCorruptData() {
        assertEquals(RatingResult.FAILED, write(ascii("not a jpeg"), RatingFormat.JPEG, 4).first)
        assertEquals(RatingResult.FAILED, write(jpeg(jfif, dqt).copyOf(2 + jfif.size + 3), RatingFormat.JPEG, 4).first)
        assertEquals(RatingResult.FAILED, write(jpeg(segment(0xE1, xmpHeader + ascii("<not-xmp/>"))), RatingFormat.JPEG, 4).first)
    }
    // endregion

    // region PNG
    private val pngSignature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32().apply { update(ascii(type)); update(data) }.value
        return ByteWriter().u32be(data.size.toLong()).fourCC(type).bytes(data).u32be(crc).toByteArray()
    }

    private val ihdr = pngChunk("IHDR", ByteArray(13) { 1 })
    private val idat = pngChunk("IDAT", bytes(1, 2, 3, 4))
    private val iend = pngChunk("IEND", ByteArray(0))

    /** Returns (type, data) of each chunk after checking every CRC. */
    private fun pngChunks(png: ByteArray): List<Pair<String, ByteArray>> {
        assertArrayEquals(pngSignature, png.copyOf(8))
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var pos = 8
        while (pos < png.size) {
            val length = png.u32be(pos).toInt()
            val type = png.fourCC(pos + 4)
            val data = png.copyOfRange(pos + 8, pos + 8 + length)
            val crc = CRC32().apply { update(ascii(type)); update(data) }.value
            assertEquals("CRC of $type", crc, png.u32be(pos + 8 + length))
            chunks.add(type to data)
            pos += 12 + length
        }
        return chunks
    }

    private fun pngXmp(png: ByteArray): String? {
        val keyword = ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000")
        val data = pngChunks(png).firstOrNull { it.first == "iTXt" }?.second ?: return null
        assertTrue(data.startsWithAt(0, keyword))
        return String(data, keyword.size, data.size - keyword.size, Charsets.UTF_8)
    }

    @Test
    fun pngAddsXmpAfterHeader() {
        val (result, updated) = write(pngSignature + ihdr + idat + iend, RatingFormat.PNG, 4)
        assertEquals(RatingResult.CHANGED, result)
        assertEquals(listOf("IHDR", "iTXt", "IDAT", "IEND"), pngChunks(updated).map { it.first })
        assertTrue(pngXmp(updated)!!.contains("xmp:Rating=\"4\""))

        val (cleared, clearedBytes) = write(updated, RatingFormat.PNG, null)
        assertEquals(RatingResult.CHANGED, cleared)
        assertFalse(pngXmp(clearedBytes)!!.contains("Rating"))
        assertEquals(RatingResult.UNCHANGED, write(pngSignature + ihdr + idat + iend, RatingFormat.PNG, null).first)
    }

    @Test
    fun pngReadsCompressedXmp() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="1" xmp:Label="Red"/></rdf:RDF></x:xmpmeta>"""
        val deflater = Deflater().apply { setInput(ascii(xmp)); finish() }
        val buffer = ByteArray(4096)
        val compressed = buffer.copyOf(deflater.deflate(buffer))
        val itxt = pngChunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0001\u0000\u0000\u0000") + compressed)

        val (_, updated) = write(pngSignature + ihdr + itxt + idat + iend, RatingFormat.PNG, 4)
        val newXmp = pngXmp(updated)!!
        assertTrue(newXmp.contains("xmp:Rating=\"4\""))
        assertTrue(newXmp.contains("xmp:Label=\"Red\""))
        assertEquals(1, pngChunks(updated).count { it.first == "iTXt" })
    }
    // endregion

    // region WebP
    private fun riffChunk(fourCC: String, data: ByteArray): ByteArray {
        val out = ByteWriter().fourCC(fourCC).u32le(data.size.toLong()).bytes(data)
        if (data.size % 2 == 1) out.u8(0)
        return out.toByteArray()
    }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ascii("WEBP")) { acc, chunk -> acc + chunk }
        return ByteWriter().fourCC("RIFF").u32le(body.size.toLong()).bytes(body).toByteArray()
    }

    private fun webpChunks(webp: ByteArray): List<Pair<String, ByteArray>> {
        assertEquals("RIFF", webp.fourCC(0))
        assertEquals(webp.size - 8L, webp.u32le(4))
        val chunks = ArrayList<Pair<String, ByteArray>>()
        var pos = 12
        while (pos < webp.size) {
            val length = webp.u32le(pos + 4).toInt()
            chunks.add(webp.fourCC(pos) to webp.copyOfRange(pos + 8, pos + 8 + length))
            pos += 8 + length + (length and 1)
        }
        assertEquals(webp.size, pos)
        return chunks
    }

    @Test
    fun webpLosslessGetsExtendedHeader() {
        // 300x200 with alpha: width-1 and height-1 in 14 bits each, then the alpha bit
        val bits = 299L or (199L shl 14) or (1L shl 28)
        val vp8l = riffChunk("VP8L", ByteWriter().u8(0x2F).u32le(bits).bytes(bytes(9, 9, 9)).toByteArray())
        val (result, updated) = write(webp(vp8l), RatingFormat.WEBP, 4)
        assertEquals(RatingResult.CHANGED, result)

        val chunks = webpChunks(updated)
        assertEquals(listOf("VP8X", "VP8L", "XMP "), chunks.map { it.first })
        val vp8x = chunks[0].second
        assertEquals(0x04 or 0x10, vp8x.u8(0))
        assertEquals(299, vp8x.u24le(4))
        assertEquals(199, vp8x.u24le(7))
        assertTrue(String(chunks[2].second, Charsets.UTF_8).contains("xmp:Rating=\"4\""))
    }

    @Test
    fun webpLossyGetsExtendedHeader() {
        val frame = bytes(0x10, 0x02, 0x00, 0x9D, 0x01, 0x2A) + ByteWriter().u16le(640).u16le(480).toByteArray() + bytes(7)
        val (_, updated) = write(webp(riffChunk("VP8 ", frame)), RatingFormat.WEBP, 4)
        val vp8x = webpChunks(updated)[0].second
        assertEquals(0x04, vp8x.u8(0))
        assertEquals(639, vp8x.u24le(4))
        assertEquals(479, vp8x.u24le(7))
    }

    @Test
    fun webpExtendedReplacesXmpAndKeepsFlags() {
        val vp8x = riffChunk("VP8X", bytes(0x08, 0, 0, 0, 9, 0, 0, 9, 0, 0)) // EXIF flag set
        val anim = riffChunk("ANIM", ByteArray(6))
        val exifChunk = riffChunk("EXIF", bytes(1, 2, 3))
        val oldXmp = riffChunk("XMP ", ascii(XmpRating.newPacket(2)))
        val (_, updated) = write(webp(vp8x, anim, oldXmp, exifChunk), RatingFormat.WEBP, 4)

        val chunks = webpChunks(updated)
        assertEquals(listOf("VP8X", "ANIM", "EXIF", "XMP "), chunks.map { it.first })
        assertEquals(0x08 or 0x04, chunks[0].second.u8(0))
        assertTrue(String(chunks[3].second, Charsets.UTF_8).contains("xmp:Rating=\"4\""))

        val (_, cleared) = write(updated, RatingFormat.WEBP, null)
        assertFalse(String(webpChunks(cleared)[3].second, Charsets.UTF_8).contains("Rating"))
    }

    @Test
    fun webpRejectsUnknownImage() {
        assertEquals(RatingResult.FAILED, write(webp(riffChunk("ALPH", bytes(1))), RatingFormat.WEBP, 4).first)
        assertNotNull(WebpRating.transform(webp(riffChunk("ALPH", bytes(1))), null))
        assertNull(WebpRating.transform(ascii("RIFF....AVI "), 4))
    }
    // endregion
}
