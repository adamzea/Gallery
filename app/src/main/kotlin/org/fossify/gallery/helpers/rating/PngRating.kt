package org.fossify.gallery.helpers.rating

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/** XMP lives in an iTXt chunk with the keyword "XML:com.adobe.xmp", placed right after IHDR. */
internal object PngRating {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val XMP_KEYWORD = "XML:com.adobe.xmp".toByteArray(Charsets.ISO_8859_1)

    private class Chunk(val type: String, val start: Int, val dataStart: Int, val dataLength: Int) {
        val end get() = dataStart + dataLength + 4
    }

    fun transform(png: ByteArray, stars: Int?): ByteArray? {
        if (!png.startsWithAt(0, SIGNATURE)) {
            return null
        }

        val chunks = parseChunks(png) ?: return null
        if (chunks.firstOrNull()?.type != "IHDR") {
            return null
        }

        val xmpChunk = chunks.firstOrNull { it.type == "iTXt" && png.startsWithAt(it.dataStart, XMP_KEYWORD + 0.toByte()) }
        val oldXmp = xmpChunk?.let { readXmp(png, it) ?: return null }
        val newXmp = XmpRating.updateOrCreate(oldXmp, stars)
        if (newXmp == oldXmp || newXmp == null) {
            return png
        }

        val chunk = buildXmpChunk(newXmp)
        val start = xmpChunk?.start ?: chunks.first().end
        val end = xmpChunk?.end ?: chunks.first().end
        return ByteWriter(png.size + chunk.size)
            .bytes(png, 0, start)
            .bytes(chunk)
            .bytes(png, end, png.size - end)
            .toByteArray()
    }

    private fun parseChunks(png: ByteArray): List<Chunk>? {
        val chunks = ArrayList<Chunk>()
        var pos = SIGNATURE.size
        while (pos + 12 <= png.size) {
            val length = png.u32be(pos)
            if (length > png.size) {
                return null
            }

            val chunk = Chunk(png.fourCC(pos + 4), pos, pos + 8, length.toInt())
            if (chunk.end > png.size) {
                return null
            }

            chunks.add(chunk)
            pos = chunk.end
            if (chunk.type == "IEND") {
                return chunks
            }
        }
        return null
    }

    private fun readXmp(png: ByteArray, chunk: Chunk): String? {
        val dataEnd = chunk.dataStart + chunk.dataLength
        var pos = chunk.dataStart + XMP_KEYWORD.size + 1
        if (pos + 2 > dataEnd) {
            return null
        }

        val compressed = png[pos] == 1.toByte()
        pos += 2 // compression flag and method
        val languageEnd = png.indexOfZero(pos, dataEnd).takeIf { it != -1 } ?: return null
        val translatedEnd = png.indexOfZero(languageEnd + 1, dataEnd).takeIf { it != -1 } ?: return null
        val textStart = translatedEnd + 1
        val text = png.copyOfRange(textStart, dataEnd)
        val bytes = if (compressed) inflate(text) ?: return null else text
        return String(bytes, Charsets.UTF_8)
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun buildXmpChunk(xmp: String): ByteArray {
        val data = ByteWriter()
            .bytes(XMP_KEYWORD)
            .u8(0) // keyword terminator
            .u8(0) // uncompressed
            .u8(0) // compression method
            .u8(0) // empty language tag
            .u8(0) // empty translated keyword
            .bytes(xmp.toByteArray(Charsets.UTF_8))
            .toByteArray()

        val type = "iTXt".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }.value

        return ByteWriter(data.size + 12).u32be(data.size.toLong()).bytes(type).bytes(data).u32be(crc).toByteArray()
    }
}
