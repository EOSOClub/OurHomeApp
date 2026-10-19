package com.eosoclub.ourhome.ui

import com.eosoclub.ourhome.data.ShoppingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The shopping item forms' Est. price (read like the web's form reads it), and the row's line under the name. */
class ShoppingPriceTest {

    @Test
    fun readsPlainPrices() {
        assertEquals(4.99, priceOrNull("4.99")!!, 0.0)
        assertEquals(12.0, priceOrNull(" 12 ")!!, 0.0)
        assertEquals(0.0, priceOrNull("0")!!, 0.0)
        assertEquals(1_000_000.0, priceOrNull("1000000")!!, 0.0)
    }

    @Test
    fun refusesWhatTheServerWouldRefuse() {
        assertNull(priceOrNull(""))
        assertNull(priceOrNull("1.2.3"))
        assertNull(priceOrNull("."))
        assertNull(priceOrNull("1000000.01"))
    }

    @Test
    fun rowDetailsPutTheNotesLast() {
        val item = ShoppingItem(id = "i", listId = "l", name = "Oat milk", priority = "high", recurring = true, notes = "The big carton", estimatedPrice = 4.5)
        assertEquals("$4.50 · Recurring · High priority · The big carton", itemDetails(item))
        assertEquals("$4.50", itemDetails(item.copy(priority = "medium", recurring = false, notes = " ")))
        assertEquals("The big carton", itemDetails(item.copy(priority = "low", recurring = false, estimatedPrice = null)))
        assertNull(itemDetails(ShoppingItem(id = "i", listId = "l", name = "Eggs")))
    }

    @Test
    fun showsSavedPricesWithoutTrailingZeros() {
        assertEquals("4.5", priceText(4.5))
        assertEquals("12", priceText(12.0))
        assertEquals("3.99", priceText(3.99))
        assertEquals("0", priceText(0.0))
    }
}
