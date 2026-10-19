package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A shopping item's link reads the same in the app as on the web's item row. */
class ProductLinkTest {

    @Test
    fun amazonProductLinksAreTidiedToTheirId() {
        val link = productLink("https://www.amazon.com/Paper-Towels/dp/b07abc1234/ref=sr_1_1?tag=x")
        assertEquals("https://www.amazon.com/dp/B07ABC1234", link.href)
        assertEquals("View on Amazon", link.label)
        assertEquals("B07ABC1234", link.asin)
    }

    @Test
    fun findsTheIdUnderOtherPathsAndInTheQuery() {
        assertEquals("B07ABC1234", extractAsin("https://amazon.co.uk/gp/product/B07ABC1234"))
        assertEquals("B07ABC1234", extractAsin("https://www.amazon.com/gp/aw/d/B07ABC1234?x=1"))
        assertEquals("B07ABC1234", extractAsin("https://www.amazon.com/s?asin=b07abc1234"))
        assertNull(extractAsin("https://www.amazon.com/s?k=towels"))
    }

    @Test
    fun shortAmazonLinksSayAmazonButKeepTheirAddress() {
        assertTrue(isAmazonUrl("https://amzn.to/3xYz"))
        assertTrue(isAmazonUrl("https://a.co/d/abc"))
        val link = productLink("https://amzn.to/3xYz")
        assertEquals("https://amzn.to/3xYz", link.href)
        assertEquals("View on Amazon", link.label)
        assertNull(link.asin)
    }

    @Test
    fun otherShopsShowTheirAddressWithoutWww() {
        assertFalse(isAmazonUrl("https://www.target.com/p/towels/-/A-123"))
        val link = productLink(" https://www.target.com/p/towels/-/A-123 ")
        assertEquals("target.com", link.label)
        assertEquals("https://www.target.com/p/towels/-/A-123", link.href)
        assertNull(link.asin)
    }

    @Test
    fun aLinkThatIsNotAnAddressShowsAsTyped() {
        assertEquals("not a link", productLink("not a link").label)
    }
}
