package org.fossify.gallery.helpers.rating

/**
 * XMP lives in an "XMP " chunk at the end of the RIFF container, flagged in the VP8X header.
 * Simple (lossy/lossless only) files get a VP8X header added, which turns them into the extended format.
 */
internal object WebpRating {
    private const val FLAG_XMP = 0x04
    private const val FLAG_ALPHA = 0x10

    private class Chunk(val fourCC: String, val start: Int, val dataStart: Int, val dataLength: Int) {
        val end get() = dataStart + dataLength + (dataLength and 1)
    }

    fun transform(webp: ByteArray, stars: Int?): ByteArray? {
        if (webp.size < 12 || webp.fourCC(0) != "RIFF" || webp.fourCC(8) != "WEBP") {
            return null
        }

        val riffEnd = 8 + webp.u32le(4)
        if (riffEnd > webp.size) {
            return null
        }

        val chunks = parseChunks(webp, riffEnd.toInt()) ?: return null
        val xmpChunk = chunks.firstOrNull { it.fourCC == "XMP " }
        val oldXmp = xmpChunk?.let { String(webp, it.dataStart, it.dataLength, Charsets.UTF_8).trimEnd('\u0000') }
        val newXmp = XmpRating.updateOrCreate(oldXmp, stars)
        if (newXmp == oldXmp || newXmp == null) {
            return webp
        }

        val out = ByteWriter(webp.size + newXmp.length + 64)
        out.fourCC("RIFF").u32le(0).fourCC("WEBP")
        if (chunks.none { it.fourCC == "VP8X" }) {
            out.bytes(buildVp8x(webp, chunks.firstOrNull() ?: return null) ?: return null)
        }

        for (chunk in chunks) {
            when (chunk.fourCC) {
                "XMP " -> Unit // re-added at the end
                "VP8X" -> {
                    val copy = webp.copyOfRange(chunk.start, chunk.end)
                    copy[8] = (copy[8].toInt() or FLAG_XMP).toByte()
                    out.bytes(copy)
                }

                else -> out.bytes(webp, chunk.start, chunk.end - chunk.start)
            }
        }

        val xmpBytes = newXmp.toByteArray(Charsets.UTF_8)
        out.fourCC("XMP ").u32le(xmpBytes.size.toLong()).bytes(xmpBytes)
        if (xmpBytes.size % 2 == 1) {
            out.u8(0)
        }

        val riffSize = out.size - 8
        val trailing = webp.size - riffEnd.toInt()
        val result = out.bytes(webp, riffEnd.toInt(), trailing).toByteArray()
        ByteWriter(4).u32le(riffSize.toLong()).toByteArray().copyInto(result, 4)
        return result
    }

    private fun parseChunks(webp: ByteArray, riffEnd: Int): List<Chunk>? {
        val chunks = ArrayList<Chunk>()
        var pos = 12
        while (pos + 8 <= riffEnd) {
            val length = webp.u32le(pos + 4)
            if (length > riffEnd) {
                return null
            }

            val chunk = Chunk(webp.fourCC(pos), pos, pos + 8, length.toInt())
            if (chunk.dataStart + chunk.dataLength > riffEnd) {
                return null
            }

            chunks.add(chunk)
            pos = minOf(chunk.end, riffEnd)
        }
        return chunks
    }

    private fun buildVp8x(webp: ByteArray, image: Chunk): ByteArray? {
        val width: Int
        val height: Int
        var flags = FLAG_XMP
        when (image.fourCC) {
            "VP8 " -> {
                val p = image.dataStart
                if (image.dataLength < 10 || webp.u8(p + 3) != 0x9D || webp.u8(p + 4) != 0x01 || webp.u8(p + 5) != 0x2A) {
                    return null
                }
                width = webp.u16le(p + 6) and 0x3FFF
                height = webp.u16le(p + 8) and 0x3FFF
            }

            "VP8L" -> {
                val p = image.dataStart
                if (image.dataLength < 5 || webp.u8(p) != 0x2F) {
                    return null
                }
                val bits = webp.u32le(p + 1)
                width = (bits and 0x3FFF).toInt() + 1
                height = ((bits shr 14) and 0x3FFF).toInt() + 1
                if ((bits shr 28) and 1L == 1L) {
                    flags = flags or FLAG_ALPHA
                }
            }

            else -> return null
        }

        if (width == 0 || height == 0) {
            return null
        }

        return ByteWriter(18)
            .fourCC("VP8X")
            .u32le(10)
            .u8(flags).u8(0).u8(0).u8(0)
            .u24le(width - 1)
            .u24le(height - 1)
            .toByteArray()
    }
}
