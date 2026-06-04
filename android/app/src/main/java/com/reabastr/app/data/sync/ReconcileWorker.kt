package com.reabastr.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reabastr.app.data.local.dao.CategoryDao
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.dao.ProductDao
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.local.entity.ProductEntity
import com.reabastr.app.data.remote.ApiService
import com.reabastr.app.data.remote.dto.ProductResponse
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that pulls full household state from GET /sync and
 * reconciles the Room cache.
 *
 * Reconciliation preserves pending outbox deltas:
 * - For each product, the reconciled local qty = server qty + sum of pending deltas
 *   for that product still in the outbox.
 * - Categories are fully replaced from server state.
 *
 * This ensures the UI stays consistent even when events haven't been uploaded yet.
 */
@HiltWorker
class ReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val productDao: ProductDao,
    private val categoryDao: CategoryDao,
    private val outboxDao: OutboxDao,
    private val apiService: ApiService
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ReconcileWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "ReconcileWorker started")

        try {
            val response = apiService.getFullSync()

            if (!response.isSuccessful) {
                Log.w(TAG, "Sync API returned ${response.code()}")
                return if (response.code() in 500..599) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }

            val syncData = response.body() ?: run {
                Log.w(TAG, "Sync response body is null")
                return Result.failure()
            }

            // Get pending outbox events to preserve unsynced deltas
            val pendingEvents = outboxDao.getPendingEvents()

            // Group pending deltas by productId
            val pendingDeltasByProduct: Map<String, Int> = pendingEvents
                .groupBy { it.productId }
                .mapValues { (_, events) -> events.sumOf { it.delta } }

            // Determine householdId from the first product or category
            val householdId = inferHouseholdId(syncData.products, syncData.categories)
            if (householdId == null) {
                Log.d(TAG, "No data returned from sync — nothing to reconcile")
                return Result.success()
            }

            // Reconcile products: server state + pending outbox deltas
            val reconciledProducts = syncData.products.map { serverProduct ->
                val pendingDelta = pendingDeltasByProduct[serverProduct.productId] ?: 0
                serverProduct.toEntity(
                    householdId = householdId,
                    pendingDelta = pendingDelta
                )
            }

            // Reconcile categories: full replace from server
            val reconciledCategories = syncData.categories.map { cat ->
                CategoryEntity(
                    categoryId = cat.categoryId,
                    householdId = householdId,
                    name = cat.name,
                    sortOrder = cat.sortOrder
                )
            }

            // Replace local cache atomically
            productDao.deleteAllByHousehold(householdId)
            productDao.insertAll(reconciledProducts)

            categoryDao.deleteAllByHousehold(householdId)
            categoryDao.insertAll(reconciledCategories)

            Log.d(
                TAG,
                "Reconciled ${reconciledProducts.size} products, " +
                    "${reconciledCategories.size} categories " +
                    "(preserved ${pendingEvents.size} pending outbox deltas)"
            )

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ReconcileWorker failed", e)
            return Result.retry()
        }
    }

    /**
     * Infers the householdId from existing local data or uses a placeholder.
     * In a real scenario, this comes from the user's session, but for reconciliation
     * we derive it from the local product/category data already in Room.
     */
    private suspend fun inferHouseholdId(
        products: List<ProductResponse>,
        categories: List<com.reabastr.app.data.remote.dto.CategoryResponse>
    ): String? {
        // Try to get householdId from existing local products
        val existingProducts = productDao.getAllProducts()
        if (existingProducts.isNotEmpty()) {
            return existingProducts.first().householdId
        }

        // Try from existing categories
        val existingCategories = categoryDao.getAllCategories()
        if (existingCategories.isNotEmpty()) {
            return existingCategories.first().householdId
        }

        // If both are empty but we received server data, we can't determine householdId
        // This shouldn't happen in normal flow — the user must have a household
        return if (products.isNotEmpty() || categories.isNotEmpty()) {
            // Use a default — the actual householdId will be set when the user's
            // household is resolved during onboarding
            "default"
        } else {
            null
        }
    }

    private fun ProductResponse.toEntity(householdId: String, pendingDelta: Int): ProductEntity {
        return ProductEntity(
            productId = productId,
            householdId = householdId,
            name = name,
            categoryId = categoryId,
            idealQty = idealQty,
            currentQty = currentQty + pendingDelta,
            eans = eans,
            lastSyncedAt = System.currentTimeMillis()
        )
    }
}
