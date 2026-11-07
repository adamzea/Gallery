package org.fossify.gallery.helpers.rating

/**
 * Edits the standard XMP star rating (`xmp:Rating`) inside an XMP packet.
 *
 * `xmp:Rating` is the field shown as "Rating" by Windows Explorer, Lightroom, Bridge, digiKam and most
 * other photo managers. Windows also writes its own percentage based `MicrosoftPhoto:Rating`; if a
 * packet has one it is kept in sync (or removed together with `xmp:Rating`).
 */
object XmpRating {
    const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/"
    private const val MICROSOFT_PHOTO_NAMESPACE = "http://ns.microsoft.com/photo/1.0/"

    /** Windows maps stars to percentages like this. */
    private val STARS_TO_PERCENT = intArrayOf(0, 1, 25, 50, 75, 99)

    fun starsToPercent(stars: Int) = STARS_TO_PERCENT[stars]

    /**
     * Returns [xmp] with its rating set to [stars], or with the rating removed if [stars] is null.
     * Returns null if a rating has to be added but the packet has no RDF structure to hold it.
     */
    fun update(xmp: String, stars: Int?): String? {
        val xmpPrefixes = prefixesBoundTo(xmp, XMP_NAMESPACE)
        val microsoftPrefixes = prefixesBoundTo(xmp, MICROSOFT_PHOTO_NAMESPACE)
        if (stars == null) {
            var result = xmp
            xmpPrefixes.forEach { result = removeProperty(result, it, "Rating") }
            microsoftPrefixes.forEach { result = removeProperty(result, it, "Rating") }
            return result
        }

        require(stars in 0..5)
        var result = xmp
        var found = false
        for (prefix in xmpPrefixes) {
            if (hasProperty(result, prefix, "Rating")) {
                found = true
                result = replaceProperty(result, prefix, "Rating", stars.toString())
            }
        }

        for (prefix in microsoftPrefixes) {
            result = replaceProperty(result, prefix, "Rating", starsToPercent(stars).toString())
        }

        if (!found) {
            result = insertRatingAttribute(result, stars) ?: return null
        }
        return result
    }

    /** A complete XMP packet holding only the rating. */
    fun newPacket(stars: Int): String {
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
            " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "  <rdf:Description rdf:about=\"\" xmlns:xmp=\"$XMP_NAMESPACE\" xmp:Rating=\"$stars\"/>\n" +
            " </rdf:RDF>\n" +
            "</x:xmpmeta>\n" +
            "<?xpacket end=\"w\"?>"
    }

    /**
     * The new XMP for a file: [oldXmp] updated, or a new packet if the file had none.
     * Returns [oldXmp] itself when nothing changes (including clearing a file without XMP, which returns null).
     * Throws [UnsupportedOperationException] if the existing packet can't hold a rating.
     */
    fun updateOrCreate(oldXmp: String?, stars: Int?): String? = when {
        oldXmp != null -> update(oldXmp, stars) ?: throw UnsupportedOperationException("XMP packet has no rdf:Description")
        stars == null -> null
        else -> newPacket(stars)
    }

    private fun prefixesBoundTo(xmp: String, namespace: String): Set<String> {
        val regex = Regex("""xmlns:([\w.-]+)\s*=\s*(["'])${Regex.escape(namespace)}\2""")
        return regex.findAll(xmp).map { it.groupValues[1] }.toSet()
    }

    private fun attributeRegex(qName: String) = Regex("""(?<![\w:.-])(${Regex.escape(qName)}\s*=\s*)(["'])[^"']*\2""")

    private fun elementRegex(qName: String): Regex {
        val name = Regex.escape(qName)
        return Regex("""(<$name(?:\s[^>]*)?>)[^<]*(</$name\s*>)""")
    }

    private fun hasProperty(xmp: String, prefix: String, name: String): Boolean {
        val qName = "$prefix:$name"
        return attributeRegex(qName).containsMatchIn(xmp) || elementRegex(qName).containsMatchIn(xmp)
    }

    /** Replaces both the attribute form `p:Name="v"` and the element form `<p:Name>v</p:Name>`. */
    private fun replaceProperty(xmp: String, prefix: String, name: String, value: String): String {
        val qName = "$prefix:$name"
        return xmp
            .replace(attributeRegex(qName)) { "${it.groupValues[1]}${it.groupValues[2]}$value${it.groupValues[2]}" }
            .replace(elementRegex(qName)) { "${it.groupValues[1]}$value${it.groupValues[2]}" }
    }

    private fun removeProperty(xmp: String, prefix: String, name: String): String {
        val qName = Regex.escape("$prefix:$name")
        val attribute = Regex("""\s+$qName\s*=\s*(["'])[^"']*\1""")
        val element = Regex("""\s*<$qName(?:\s[^>]*)?>[^<]*</$qName\s*>""")
        val emptyElement = Regex("""\s*<$qName(?:\s[^>]*)?/>""")
        return xmp.replace(attribute, "").replace(element, "").replace(emptyElement, "")
    }

    private fun insertRatingAttribute(xmp: String, stars: Int): String? {
        val description = Regex("""<rdf:Description(?=[\s/>])""").find(xmp)
        if (description != null) {
            val tagEnd = xmp.indexOf('>', description.range.last)
            if (tagEnd == -1) {
                return null
            }

            val tag = xmp.substring(description.range.first, tagEnd)
            val xmpBinding = Regex("""xmlns:xmp\s*=\s*(["'])([^"']*)\1""").find(tag)
            val (prefix, declaration) = when {
                xmpBinding == null -> "xmp" to " xmlns:xmp=\"$XMP_NAMESPACE\""
                xmpBinding.groupValues[2] == XMP_NAMESPACE -> "xmp" to ""
                // "xmp" is bound to something unexpected on this element, use a prefix nobody else uses
                else -> "fossifyXmp" to " xmlns:fossifyXmp=\"$XMP_NAMESPACE\""
            }

            val insertAt = description.range.last + 1
            return xmp.substring(0, insertAt) + "$declaration $prefix:Rating=\"$stars\"" + xmp.substring(insertAt)
        }

        // No rdf:Description at all, add one right inside rdf:RDF
        val rdf = Regex("""<rdf:RDF(?:\s[^>]*)?>""").find(xmp) ?: return null
        if (rdf.value.endsWith("/>")) {
            return null
        }

        val insertAt = rdf.range.last + 1
        val newDescription = "<rdf:Description rdf:about=\"\" xmlns:xmp=\"$XMP_NAMESPACE\" xmp:Rating=\"$stars\"/>"
        return xmp.substring(0, insertAt) + newDescription + xmp.substring(insertAt)
    }
}
