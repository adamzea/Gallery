package org.fossify.gallery.helpers.rating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpRatingTest {
    private fun packet(description: String) =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">$description</rdf:RDF></x:xmpmeta>"""

    @Test
    fun replacesAttributeRating() {
        val xmp = packet("""<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2" xmp:CreatorTool="Cam"/>""")
        val result = XmpRating.update(xmp, 4)!!
        assertTrue(result.contains("xmp:Rating=\"4\""))
        assertFalse(result.contains("xmp:Rating=\"2\""))
        assertTrue(result.contains("xmp:CreatorTool=\"Cam\""))
    }

    @Test
    fun replacesElementRatingWithCustomPrefix() {
        val xmp = packet("""<rdf:Description rdf:about="" xmlns:xap="http://ns.adobe.com/xap/1.0/"><xap:Rating>-1</xap:Rating></rdf:Description>""")
        val result = XmpRating.update(xmp, 4)!!
        assertTrue(result.contains("<xap:Rating>4</xap:Rating>"))
        assertFalse(result.contains("xmp:Rating"))
    }

    @Test
    fun insertsWithoutDuplicatingNamespace() {
        val xmp = packet("""<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Cam"/>""")
        val result = XmpRating.update(xmp, 4)!!
        assertTrue(result.contains("xmp:Rating=\"4\""))
        assertEquals(1, Regex("xmlns:xmp=").findAll(result).count())
    }

    @Test
    fun insertsNamespaceIntoDescriptionWithOtherNamespaces() {
        val xmp = packet("""<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:format>image/jpeg</dc:format></rdf:Description>""")
        val result = XmpRating.update(xmp, 4)!!
        assertTrue(result.contains("""<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4" rdf:about="""""))
        assertTrue(result.contains("<dc:format>image/jpeg</dc:format>"))
    }

    @Test
    fun addsDescriptionWhenRdfIsEmpty() {
        val result = XmpRating.update(packet(""), 4)!!
        assertTrue(result.contains("""<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/>"""))
    }

    @Test
    fun keepsMicrosoftPhotoRatingInSync() {
        val xmp = packet(
            """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:Rating>1</xmp:Rating></rdf:Description>""" +
                """<rdf:Description rdf:about="" xmlns:MicrosoftPhoto="http://ns.microsoft.com/photo/1.0/"><MicrosoftPhoto:Rating>1</MicrosoftPhoto:Rating></rdf:Description>"""
        )
        val result = XmpRating.update(xmp, 4)!!
        assertTrue(result.contains("<xmp:Rating>4</xmp:Rating>"))
        assertTrue(result.contains("<MicrosoftPhoto:Rating>75</MicrosoftPhoto:Rating>"))
    }

    @Test
    fun doesNotTouchOtherRatingProperties() {
        val xmp = packet("""<rdf:Description rdf:about="" xmlns:other="urn:other" other:Rating="1"/>""")
        val result = XmpRating.update(xmp, 4)!!
        assertTrue(result.contains("other:Rating=\"1\""))
        assertTrue(result.contains("xmp:Rating=\"4\""))
        assertTrue(XmpRating.update(result, null)!!.contains("other:Rating=\"1\""))
    }

    @Test
    fun removesAttributeAndElementRatings() {
        val xmp = packet(
            """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4" xmp:CreatorTool="Cam"/>""" +
                """<rdf:Description rdf:about="" xmlns:MicrosoftPhoto="http://ns.microsoft.com/photo/1.0/">
                    <MicrosoftPhoto:Rating>75</MicrosoftPhoto:Rating>
                </rdf:Description>"""
        )
        val result = XmpRating.update(xmp, null)!!
        assertFalse(result.contains("Rating"))
        assertTrue(result.contains("""<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Cam"/>"""))
    }

    @Test
    fun clearingWithoutRatingChangesNothing() {
        val xmp = packet("""<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="Cam"/>""")
        assertEquals(xmp, XmpRating.update(xmp, null))
    }

    @Test
    fun setThenClearRoundTripsNewPacket() {
        val packet = XmpRating.newPacket(4)
        assertTrue(packet.contains("xmp:Rating=\"4\""))
        assertFalse(XmpRating.update(packet, null)!!.contains("Rating"))
    }

    @Test
    fun settingSameRatingAgainChangesNothing() {
        val attribute = XmpRating.newPacket(4)
        assertEquals(attribute, XmpRating.update(attribute, 4))
        val element = packet("""<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:Rating>4</xmp:Rating></rdf:Description>""")
        assertEquals(element, XmpRating.update(element, 4))
    }

    @Test
    fun updateOrCreate() {
        assertNull(XmpRating.updateOrCreate(null, null))
        assertEquals(XmpRating.newPacket(4), XmpRating.updateOrCreate(null, 4))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun failsOnPacketWithoutRdf() {
        XmpRating.updateOrCreate("<not-xmp/>", 4)
    }
}
