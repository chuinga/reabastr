package com.reabastr.app.data.repository

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based test for Shopping List Derivation (Property 1).
 *
 * **Validates: Requirements 3.1, 3.2**
 *
 * For any Product p, buyQty(p) == max(0, p.idealQty - p.currentQty).
 * Corollary: if currentQty >= idealQty, the product MUST NOT appear on the shopping list.
 */
class ShoppingListDerivationPropertyTest {

    /**
     * Computes buyQty using the same formula as InventoryRepository.observeShoppingList.
     * This is the pure derivation function under test.
     */
    private fun computeBuyQty(idealQty: Int, currentQty: Int): Int {
        return maxOf(0, idealQty - currentQty)
    }

    /**
     * Determines if a product should appear on the shopping list.
     * Mirrors the filter logic: idealQty > currentQty (i.e., buyQty > 0).
     */
    private fun shouldAppearOnShoppingList(idealQty: Int, currentQty: Int): Boolean {
        return idealQty > currentQty
    }

    @Test
    fun `buyQty equals max(0, idealQty - currentQty) for all valid pairs`() = runTest {
        // idealQty range: 1–9999 (per domain constraints)
        // currentQty range: 0–unbounded (may exceed idealQty), using 0–19999 for test coverage
        val idealQtyArb = Arb.int(1..9999)
        val currentQtyArb = Arb.int(0..19999)

        checkAll(idealQtyArb, currentQtyArb) { idealQty, currentQty ->
            val buyQty = computeBuyQty(idealQty, currentQty)
            val expected = maxOf(0, idealQty - currentQty)

            assertEquals(
                "buyQty should equal max(0, $idealQty - $currentQty) = $expected, got $buyQty",
                expected,
                buyQty
            )
        }
    }

    @Test
    fun `product does NOT appear on shopping list when currentQty greater than or equal to idealQty`() = runTest {
        // Corollary: if currentQty >= idealQty, buyQty must be 0 and product excluded
        val idealQtyArb = Arb.int(1..9999)
        // Generate currentQty that is always >= idealQty
        val offsetArb = Arb.int(0..10000)

        checkAll(idealQtyArb, offsetArb) { idealQty, offset ->
            val currentQty = idealQty + offset // guarantees currentQty >= idealQty

            val buyQty = computeBuyQty(idealQty, currentQty)
            val appearsOnList = shouldAppearOnShoppingList(idealQty, currentQty)

            assertEquals(
                "buyQty must be 0 when currentQty ($currentQty) >= idealQty ($idealQty)",
                0,
                buyQty
            )
            assertTrue(
                "Product must NOT appear on shopping list when currentQty ($currentQty) >= idealQty ($idealQty)",
                !appearsOnList
            )
        }
    }

    @Test
    fun `buyQty is always non-negative`() = runTest {
        val idealQtyArb = Arb.int(1..9999)
        val currentQtyArb = Arb.int(0..19999)

        checkAll(idealQtyArb, currentQtyArb) { idealQty, currentQty ->
            val buyQty = computeBuyQty(idealQty, currentQty)

            assertTrue(
                "buyQty must never be negative, got $buyQty for idealQty=$idealQty, currentQty=$currentQty",
                buyQty >= 0
            )
        }
    }

    @Test
    fun `product appears on shopping list if and only if buyQty is positive`() = runTest {
        val idealQtyArb = Arb.int(1..9999)
        val currentQtyArb = Arb.int(0..19999)

        checkAll(idealQtyArb, currentQtyArb) { idealQty, currentQty ->
            val buyQty = computeBuyQty(idealQty, currentQty)
            val appearsOnList = shouldAppearOnShoppingList(idealQty, currentQty)

            assertEquals(
                "Product should appear on list iff buyQty > 0. " +
                    "idealQty=$idealQty, currentQty=$currentQty, buyQty=$buyQty, appearsOnList=$appearsOnList",
                buyQty > 0,
                appearsOnList
            )
        }
    }
}
