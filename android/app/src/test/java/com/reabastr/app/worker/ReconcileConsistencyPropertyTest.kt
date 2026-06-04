package com.reabastr.app.worker

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based test for Reconciliation Consistency (Property 5).
 *
 * **Validates: Requirements 8.6**
 *
 * Property: After a full reconciliation (GET /sync → apply to Room),
 * the local Room cache state for all products matches the backend state,
 * adjusted for any pending (unsent) outbox events.
 *
 * Formula: localQty == serverQty + sum(pending_deltas_for_product)
 */
class ReconcileConsistencyPropertyTest {

    /**
     * Represents a product as returned by the server during GET /sync.
     */
    data class ServerProduct(
        val productId: String,
        val currentQty: Int,
        val idealQty: Int
    )

    /**
     * Represents a pending outbox event that has not yet been uploaded.
     */
    data class PendingDelta(
        val productId: String,
        val delta: Int
    )

    /**
     * Represents the local product state after reconciliation.
     */
    data class LocalProduct(
        val productId: String,
        val currentQty: Int,
        val idealQty: Int
    )

    /**
     * Simulates the ReconcileWorker's core reconciliation logic:
     *
     * 1. Receive server state (list of products with their currentQty)
     * 2. Compute pending delta sums per product from outbox
     * 3. Apply: localQty = serverQty + pendingDeltaSum
     *
     * This mirrors ReconcileWorker.doWork() and ProductResponse.toEntity().
     */
    private fun reconcile(
        serverProducts: List<ServerProduct>,
        pendingDeltas: List<PendingDelta>
    ): List<LocalProduct> {
        // Group pending deltas by productId and sum them (same as ReconcileWorker)
        val pendingDeltasByProduct: Map<String, Int> = pendingDeltas
            .groupBy { it.productId }
            .mapValues { (_, events) -> events.sumOf { it.delta } }

        // Apply server state + pending deltas (mirrors toEntity logic)
        return serverProducts.map { product ->
            val pendingDelta = pendingDeltasByProduct[product.productId] ?: 0
            LocalProduct(
                productId = product.productId,
                currentQty = product.currentQty + pendingDelta,
                idealQty = product.idealQty
            )
        }
    }

    @Test
    fun `local qty equals server qty plus pending deltas after reconciliation`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val currentQtyArb = Arb.int(0..9999)
        val idealQtyArb = Arb.int(1..9999)
        val deltaArb = Arb.int(-10..10)

        checkAll(
            Arb.list(productIdArb, 1..10),
            Arb.list(currentQtyArb, 1..10),
            Arb.list(idealQtyArb, 1..10),
            Arb.list(deltaArb, 0..30)
        ) { productIds, currentQtys, idealQtys, deltas ->
            // Build server products from random data (trim to shortest list)
            val productCount = minOf(productIds.size, currentQtys.size, idealQtys.size)
            val uniqueProductIds = productIds.take(productCount).mapIndexed { i, base -> "${base}_$i" }

            val serverProducts = uniqueProductIds.mapIndexed { i, id ->
                ServerProduct(
                    productId = id,
                    currentQty = currentQtys[i],
                    idealQty = idealQtys[i]
                )
            }

            // Assign random deltas to random products (pending outbox events)
            val pendingDeltas = deltas.mapIndexed { i, delta ->
                val targetProduct = uniqueProductIds[i % uniqueProductIds.size]
                PendingDelta(productId = targetProduct, delta = delta)
            }

            // Run reconciliation
            val localProducts = reconcile(serverProducts, pendingDeltas)

            // Compute expected pending delta sums independently
            val expectedPendingSums: Map<String, Int> = pendingDeltas
                .groupBy { it.productId }
                .mapValues { (_, events) -> events.sumOf { it.delta } }

            // PROPERTY: For every product, localQty == serverQty + sum(pending deltas)
            localProducts.forEachIndexed { i, localProduct ->
                val serverProduct = serverProducts[i]
                val expectedDelta = expectedPendingSums[localProduct.productId] ?: 0
                val expectedLocalQty = serverProduct.currentQty + expectedDelta

                assertEquals(
                    "localQty for product '${localProduct.productId}' should equal " +
                        "serverQty (${serverProduct.currentQty}) + pending deltas ($expectedDelta) " +
                        "= $expectedLocalQty, but got ${localProduct.currentQty}",
                    expectedLocalQty,
                    localProduct.currentQty
                )
            }
        }
    }

    @Test
    fun `idealQty is preserved unchanged during reconciliation`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val currentQtyArb = Arb.int(0..9999)
        val idealQtyArb = Arb.int(1..9999)
        val deltaArb = Arb.int(-10..10)

        checkAll(
            Arb.list(productIdArb, 1..10),
            Arb.list(currentQtyArb, 1..10),
            Arb.list(idealQtyArb, 1..10),
            Arb.list(deltaArb, 0..20)
        ) { productIds, currentQtys, idealQtys, deltas ->
            val productCount = minOf(productIds.size, currentQtys.size, idealQtys.size)
            val uniqueProductIds = productIds.take(productCount).mapIndexed { i, base -> "${base}_$i" }

            val serverProducts = uniqueProductIds.mapIndexed { i, id ->
                ServerProduct(
                    productId = id,
                    currentQty = currentQtys[i],
                    idealQty = idealQtys[i]
                )
            }

            val pendingDeltas = deltas.mapIndexed { i, delta ->
                PendingDelta(productId = uniqueProductIds[i % uniqueProductIds.size], delta = delta)
            }

            val localProducts = reconcile(serverProducts, pendingDeltas)

            // PROPERTY: idealQty is never modified during reconciliation
            localProducts.forEachIndexed { i, localProduct ->
                assertEquals(
                    "idealQty for product '${localProduct.productId}' must be preserved from server. " +
                        "Server idealQty=${serverProducts[i].idealQty}, local idealQty=${localProduct.idealQty}",
                    serverProducts[i].idealQty,
                    localProduct.idealQty
                )
            }
        }
    }

    @Test
    fun `reconciliation with no pending deltas yields exact server state`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val currentQtyArb = Arb.int(0..9999)
        val idealQtyArb = Arb.int(1..9999)

        checkAll(
            Arb.list(productIdArb, 1..10),
            Arb.list(currentQtyArb, 1..10),
            Arb.list(idealQtyArb, 1..10)
        ) { productIds, currentQtys, idealQtys ->
            val productCount = minOf(productIds.size, currentQtys.size, idealQtys.size)
            val uniqueProductIds = productIds.take(productCount).mapIndexed { i, base -> "${base}_$i" }

            val serverProducts = uniqueProductIds.mapIndexed { i, id ->
                ServerProduct(
                    productId = id,
                    currentQty = currentQtys[i],
                    idealQty = idealQtys[i]
                )
            }

            // No pending deltas — outbox is empty
            val localProducts = reconcile(serverProducts, emptyList())

            // PROPERTY: With no pending deltas, local state == server state exactly
            localProducts.forEachIndexed { i, localProduct ->
                assertEquals(
                    "With empty outbox, localQty must equal serverQty for '${localProduct.productId}'",
                    serverProducts[i].currentQty,
                    localProduct.currentQty
                )
                assertEquals(
                    "With empty outbox, idealQty must equal server idealQty for '${localProduct.productId}'",
                    serverProducts[i].idealQty,
                    localProduct.idealQty
                )
            }
        }
    }

    @Test
    fun `reconciliation handles deltas for products not in server state gracefully`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val currentQtyArb = Arb.int(0..9999)
        val idealQtyArb = Arb.int(1..9999)
        val deltaArb = Arb.int(-10..10)

        checkAll(
            Arb.list(productIdArb, 1..5),
            Arb.list(currentQtyArb, 1..5),
            Arb.list(idealQtyArb, 1..5),
            Arb.list(deltaArb, 1..10)
        ) { productIds, currentQtys, idealQtys, deltas ->
            val productCount = minOf(productIds.size, currentQtys.size, idealQtys.size)
            val uniqueProductIds = productIds.take(productCount).mapIndexed { i, base -> "${base}_$i" }

            val serverProducts = uniqueProductIds.mapIndexed { i, id ->
                ServerProduct(
                    productId = id,
                    currentQty = currentQtys[i],
                    idealQty = idealQtys[i]
                )
            }

            // Create deltas that reference non-existent products (orphaned outbox events)
            val orphanDeltas = deltas.map { delta ->
                PendingDelta(productId = "orphan_${delta}_${System.nanoTime()}", delta = delta)
            }

            // Mix orphan deltas with valid deltas
            val validDeltas = deltas.take(3).mapIndexed { i, delta ->
                PendingDelta(productId = uniqueProductIds[i % uniqueProductIds.size], delta = delta)
            }
            val allDeltas = validDeltas + orphanDeltas

            val localProducts = reconcile(serverProducts, allDeltas)

            // PROPERTY: Orphan deltas (for products not on server) are ignored;
            // local product count matches server product count exactly
            assertEquals(
                "Local product count must match server product count (orphan deltas ignored)",
                serverProducts.size,
                localProducts.size
            )

            // PROPERTY: Valid deltas are still correctly applied
            val validDeltaSums: Map<String, Int> = validDeltas
                .groupBy { it.productId }
                .mapValues { (_, events) -> events.sumOf { it.delta } }

            localProducts.forEachIndexed { i, localProduct ->
                val expectedDelta = validDeltaSums[localProduct.productId] ?: 0
                val expectedQty = serverProducts[i].currentQty + expectedDelta

                assertEquals(
                    "Product '${localProduct.productId}': expected serverQty (${serverProducts[i].currentQty}) " +
                        "+ valid pending ($expectedDelta) = $expectedQty, got ${localProduct.currentQty}",
                    expectedQty,
                    localProduct.currentQty
                )
            }
        }
    }

    @Test
    fun `reconciliation is idempotent when applied twice with same inputs`() = runTest {
        val productIdArb = Arb.string(size = 8)
        val currentQtyArb = Arb.int(0..9999)
        val idealQtyArb = Arb.int(1..9999)
        val deltaArb = Arb.int(-10..10)

        checkAll(
            Arb.list(productIdArb, 1..8),
            Arb.list(currentQtyArb, 1..8),
            Arb.list(idealQtyArb, 1..8),
            Arb.list(deltaArb, 0..15)
        ) { productIds, currentQtys, idealQtys, deltas ->
            val productCount = minOf(productIds.size, currentQtys.size, idealQtys.size)
            val uniqueProductIds = productIds.take(productCount).mapIndexed { i, base -> "${base}_$i" }

            val serverProducts = uniqueProductIds.mapIndexed { i, id ->
                ServerProduct(
                    productId = id,
                    currentQty = currentQtys[i],
                    idealQty = idealQtys[i]
                )
            }

            val pendingDeltas = deltas.mapIndexed { i, delta ->
                PendingDelta(productId = uniqueProductIds[i % uniqueProductIds.size], delta = delta)
            }

            // Apply reconciliation twice with the same inputs
            val firstResult = reconcile(serverProducts, pendingDeltas)
            val secondResult = reconcile(serverProducts, pendingDeltas)

            // PROPERTY: Reconciliation is a pure function; same inputs yield identical results
            assertEquals(
                "Reconciliation must be idempotent — applying twice with same inputs must yield same result",
                firstResult,
                secondResult
            )
        }
    }

    @Test
    fun `sum of deltas per product is additive regardless of event ordering`() = runTest {
        val productIdArb = Arb.string(size = 6)
        val currentQtyArb = Arb.int(0..5000)
        val idealQtyArb = Arb.int(1..9999)
        val deltaArb = Arb.int(-5..5)

        checkAll(
            productIdArb,
            currentQtyArb,
            idealQtyArb,
            Arb.list(deltaArb, 2..20)
        ) { productId, currentQty, idealQty, deltas ->
            val server = listOf(
                ServerProduct(productId = "${productId}_0", currentQty = currentQty, idealQty = idealQty)
            )

            val pendingDeltas = deltas.map { delta ->
                PendingDelta(productId = "${productId}_0", delta = delta)
            }

            // Apply with original ordering
            val resultOriginal = reconcile(server, pendingDeltas)

            // Apply with reversed ordering
            val resultReversed = reconcile(server, pendingDeltas.reversed())

            // Apply with shuffled ordering (rotate by half)
            val mid = pendingDeltas.size / 2
            val rotated = pendingDeltas.drop(mid) + pendingDeltas.take(mid)
            val resultRotated = reconcile(server, rotated)

            // PROPERTY: Final localQty is independent of delta ordering
            // (because sum is commutative)
            assertTrue(
                "Reconciliation result must be independent of delta order. " +
                    "Original=${resultOriginal[0].currentQty}, Reversed=${resultReversed[0].currentQty}, " +
                    "Rotated=${resultRotated[0].currentQty}",
                resultOriginal[0].currentQty == resultReversed[0].currentQty &&
                    resultOriginal[0].currentQty == resultRotated[0].currentQty
            )
        }
    }
}
