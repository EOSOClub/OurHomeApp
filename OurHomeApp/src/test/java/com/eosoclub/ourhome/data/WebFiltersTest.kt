package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFiltersTest {

    @Test
    fun `knows the web's filters by key, and nothing else`() {
        assertEquals(WebFilterChoice.Family, WebFilterChoice.of("family"))
        assertEquals(WebFilterChoice.Adult, WebFilterChoice.of("adult"))
        assertNull(WebFilterChoice.of(null))
        assertNull(WebFilterChoice.of("strict"))
    }

    @Test
    fun `Private DNS uses a filter only when it's that hostname, however it's written`() {
        assertTrue(usesFilter("family.cloudflare-dns.com", WebFilterChoice.Adult.hostname))
        assertTrue(usesFilter("Family.Cloudflare-DNS.com.", WebFilterChoice.Adult.hostname))
        assertFalse(usesFilter(PRIVATE_DNS_OFF, WebFilterChoice.Adult.hostname))
        assertFalse(usesFilter("dns.google", WebFilterChoice.Family.hostname))
        assertFalse(usesFilter(null, WebFilterChoice.Family.hostname))
    }

    @Test
    fun `the card's line says the filter and whether their phone uses it`() {
        assertEquals("Off", LookedAfterWebFilter().summary)
        assertEquals("Family filter · on", LookedAfterWebFilter("family", true).summary)
        assertEquals("Adult sites only · not on their phone", LookedAfterWebFilter("adult", false).summary)
        assertEquals("Family filter · waiting for their phone", LookedAfterWebFilter("family", null).summary)
    }

    @Test
    fun `an older server sends no web filter, so the card stays away`() {
        val json = Json { ignoreUnknownKeys = true }
        assertNull(json.decodeFromString(LookedAfter.serializer(), """{"id":"mia","name":"Mia"}""").webFilter)
        val now = json.decodeFromString(
            LookedAfter.serializer(),
            """{"id":"mia","name":"Mia","webFilter":{"filter":"family","on":false}}""",
        )
        assertEquals(LookedAfterWebFilter("family", false), now.webFilter)
    }
}
