package org.fossify.gallery.helpers.rating

import java.util.Locale

enum class RatingResult {
    /** The file was modified. */
    CHANGED,

    /** The file already had the requested rating (or no rating to clear). */
    UNCHANGED,

    /** The file couldn't be parsed or written. */
    FAILED
}

enum class RatingFormat {
    JPEG, PNG, WEBP, HEIF, DNG, ISO_MEDIA
}

/**
 * Writes or clears the star rating stored in a media file's metadata.
 *
 * Photos get the standard XMP `xmp:Rating`, stored where each format keeps its XMP. MP4/MOV/3GP videos
 * additionally get the Microsoft "Xtra" rating that Windows Explorer shows for videos.
 */
object MediaRating {
    /** Formats that need the whole file rewritten are only handled up to this size. */
    private const val MAX_IN_MEMORY_SIZE = 96L * 1024 * 1024

    fun formatOf(path: String): RatingFormat? {
        return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> RatingFormat.JPEG
            "png" -> RatingFormat.PNG
            "webp" -> RatingFormat.WEBP
            "heic", "heif" -> RatingFormat.HEIF
            "dng" -> RatingFormat.DNG
            "mp4", "m4v", "mov", "3gp", "3g2" -> RatingFormat.ISO_MEDIA
            else -> null
        }
    }

    /** Sets the rating to [stars] (1-5), or removes it if [stars] is null. */
    fun write(data: RandomAccessData, format: RatingFormat, stars: Int?): RatingResult {
        require(stars == null || stars in 0..5) { "stars must be between 0 and 5" }
        return try {
            when (format) {
                RatingFormat.JPEG -> rewriteInMemory(data) { JpegRating.transform(it, stars) }
                RatingFormat.PNG -> rewriteInMemory(data) { PngRating.transform(it, stars) }
                RatingFormat.WEBP -> rewriteInMemory(data) { WebpRating.transform(it, stars) }
                RatingFormat.HEIF -> rewriteInMemory(data) { HeifRating.transform(it, stars) }
                RatingFormat.DNG -> TiffRating.write(data, stars)
                RatingFormat.ISO_MEDIA -> IsoMediaRating.write(data, stars)
            }
        } catch (e: Exception) {
            RatingResult.FAILED
        } catch (e: OutOfMemoryError) {
            RatingResult.FAILED
        }
    }

    private fun rewriteInMemory(data: RandomAccessData, transform: (ByteArray) -> ByteArray?): RatingResult {
        val length = data.length()
        if (length > MAX_IN_MEMORY_SIZE) {
            return RatingResult.FAILED
        }

        val original = data.read(0, length.toInt())
        val updated = transform(original) ?: return RatingResult.FAILED
        if (updated.contentEquals(original)) {
            return RatingResult.UNCHANGED
        }

        try {
            data.write(0, updated)
            if (updated.size < original.size) {
                data.truncate(updated.size.toLong())
            }
        } catch (e: Exception) {
            try {
                data.write(0, original)
                data.truncate(original.size.toLong())
            } catch (ignored: Exception) {
            }
            return RatingResult.FAILED
        }
        return RatingResult.CHANGED
    }
}
