package com.reabastr.app.worker

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
import com.reabastr.app.data.remote.dto.CategoryResponse
import com.reabastr.app.data.remote.dto.ProductResponse
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that pulls the full household state from the backend via GET /sync
 * and reconciles the local Room cache.
 *
 * Reconciliation preserves pending outbox deltas: after applying server state,
 * the local currentQty is adjusted by the sum of pending (unsent) outbox events
 * for each product, so the UI remains consistent with what the user expects.
 *
 * Formula: localQty = serverQty + sum(pending_deltas_for_product)
 */
@HiltWorker
class ReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val apiService: ApiService,
    private val productDao: ProductDao,
    private val categoryDao: CategoryDao,
    private val outboxDao: OutboxDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ReconcileWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting reconciliation")

        return try {
            val response = apiService.getFullSync()

            if (!response.isSuccessful) {
                Log.w(TAG, "Sync endpoint returned HTTP ${response.code()}")
                return Result.retry()
            }

            val syncData = response.body() ?: run {
                Log.w(TAG, "Sync response body is null")
                return Result.retry()
            }

            // Get pending outbox events before reconciliation
            val pendingEvents = outboxDao.getPendingEvents()

            // Calculate pending delta sums per product
            val pendingDeltasByProduct: Map<String, Int> = pendingEvents
                .groupBy { it.productId }
                .mapValues { (_, events) -> events.sumOf { it.delta } }

            // Determine householdId: prefer the backend household, fall back to cache.
            val householdId = resolveHouseholdId()

            if (householdId == null) {
                Log.w(TAG, "Could not determine householdId for reconciliation")
                return Result.success() // nothing to reconcile yet
            }

            // Convert server products to entities, preserving pending deltas
            val serverProducts = syncData.products.map { productResponse ->
                val pendingDelta = pendingDeltasByProduct[productResponse.productId] ?: 0
                productResponse.toEntity(householdId, pendingDelta)
            }

            // Convert server categories to entities
            val serverCategories = syncData.categories.map { it.toEntity(householdId) }

            // Replace local cache with reconciled state
            productDao.deleteAllByHousehold(householdId)
            productDao.insertAll(serverProducts)

            categoryDao.deleteAllByHousehold(householdId)
            categoryDao.insertAll(serverCategories)

            Log.d(TAG, "Reconciliation complete: ${serverProducts.size} products, ${serverCategories.size} categories")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Reconciliation failed", e)
            Result.retry()
        }
    }

    /**
     * Resolves the householdId from the backend (authoritative), falling back to
     * the local cache if the network lookup fails.
     */
    private suspend fun resolveHouseholdId(): String? {
        try {
            val resp = apiService.getHousehold()
            if (resp.isSuccessful) {
                resp.body()?.householdId?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getHousehold failed during reconcile; falling back to cache", e)
        }
        val existingProducts = productDao.getAllProducts()
        return existingProducts.firstOrNull()?.householdId
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
            refs = refs,
            lastSyncedAt = System.currentTimeMillis()
        )
    }

    private fun CategoryResponse.toEntity(householdId: String): CategoryEntity {
        return CategoryEntity(
            categoryId = categoryId,
            householdId = householdId,
            name = name,
            sortOrder = sortOrder
        )
    }
}
