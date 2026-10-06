package com.eosoclub.ourhome.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountsTest {
    @Test fun `negative means used, positive means restocked`() {
        assertEquals(-1.0, parseScanAmount("-1")!!, 0.0)
        assertEquals(-2.0, parseScanAmount("−2")!!, 0.0) // Unicode minus from some keyboards
        assertEquals(3.0, parseScanAmount("3")!!, 0.0)
        assertEquals(3.0, parseScanAmount("+3")!!, 0.0)
    }

    @Test fun `decimals with a dot or comma`() {
        assertEquals(0.5, parseScanAmount("0.5")!!, 0.0)
        assertEquals(-1.5, parseScanAmount(" -1,5 ")!!, 0.0)
    }

    @Test fun `rejects blank, zero and junk`() {
        assertNull(parseScanAmount(null))
        assertNull(parseScanAmount(""))
        assertNull(parseScanAmount("0"))
        assertNull(parseScanAmount("two"))
    }
}
