package com.eosoclub.ourhome.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagIdsTest {
    @Test fun `reads the id from a Home Assistant tag url`() {
        assertEquals(
            TagRef("cb1a7141-4cac-4b26-aa1d-4262bd962b87", TagFormat.HomeAssistant),
            tagRefFromUri("https://www.home-assistant.io/tag/cb1a7141-4cac-4b26-aa1d-4262bd962b87"),
        )
        assertEquals("pantry_coffee", tagRefFromUri("https://home-assistant.io/tag/pantry_coffee?x=1")?.tagId)
    }

    @Test fun `reads the id from an Our Home tag uri`() {
        assertEquals(TagRef("uid:04a1ff00", TagFormat.OurHome), tagRefFromUri("ourhome://tag/uid:04a1ff00"))
    }

    @Test fun `rewriting keeps the same id`() {
        val haId = "cb1a7141-4cac-4b26-aa1d-4262bd962b87"
        assertEquals(TagRef(haId, TagFormat.OurHome), tagRefFromUri(ourTagUri(haId)))
    }

    @Test fun `ignores other urls`() {
        assertNull(tagRefFromUri("https://example.com/tag/abc"))
        assertNull(tagRefFromUri("https://www.home-assistant.io/tags/abc"))
    }

    @Test fun `hardware uid becomes a lowercase hex id`() {
        assertEquals("uid:04a1ff00", uidTagId(byteArrayOf(0x04, 0xA1.toByte(), 0xFF.toByte(), 0x00)))
    }
}
