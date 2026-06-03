package com.reabastr.app.data.repository

import com.reabastr.app.data.local.dao.CategoryDao
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.dao.ProductDao
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.local.entity.OutboxEvent
import com.reabastr.app.data.local.entity.ProductEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mediates between Room (local) and the REST API (remote) for inventory operations.
 * Implements the local-first pattern: all mutations apply to Room immediately,
 * then queue to the outbox for background sync to the backend.
 *
 * UI talks to this repository, never directly to the network or DAOs.
 */
@Singleton
class InventoryRepository @Inject constructor(
    private val productDao: ProductDao,
    private val categoryDao: CategoryDao,
    private val outboxDao: OutboxDao
) {

    // --- Product Queries ---

    /** Observes all products for a household. */
    fun observeProducts(householdId: String): Flow<List<ProductEntity>> {
        return productDao.getProductsByHousehold(householdId)
    }

    /** Observes the derived shopping list: products where buyQty > 0. */
    fun observeShoppingList(householdId: String): Flow<List<ShoppingListItem>> {
        return productDao.getProductsByHousehold(householdId).map { products ->
            products
                .filter { it.idealQty > it.currentQty }
                .map { product ->
                    ShoppingListItem(
                        product = product,
                        buyQty = product.idealQty - product.currentQty
                    )
                }
        }
    }

    /** Looks up a product by EAN from the local Room cache. */
    suspend fun lookupProductByEan(householdId: String, ean: String): ProductEntity? {
        return productDao.getProductByEan(householdId, ean)
    }

    /** Gets a product by ID from local cache. */
    suspend fun getProductById(productId: String): ProductEntity? {
        return productDao.getProductById(productId)
    }

    // --- Stock Adjustments (Delta Application) ---

    /**
     * Decrements a product's currentQty by 1, applying the delta locally and
     * enqueueing the event to the outbox for backend sync.
     *
     * @return [DeltaResult.Success] if applied, [DeltaResult.OutOfStock] if currentQty is 0.
     */
    suspend fun decrementStock(productId: String): DeltaResult {
        val product = productDao.getProductById(productId)
            ?: return DeltaResult.ProductNotFound

        if (product.currentQty <= 0) {
            return DeltaResult.OutOfStock
        }

        // Apply delta to Room immediately
        productDao.applyDelta(productId, -1)

        // Enqueue to outbox for backend sync
        outboxDao.insert(
            OutboxEvent(
                productId = productId,
                delta = -1,
                timestamp = System.currentTimeMillis()
            )
        )

        return DeltaResult.Success(newQty = product.currentQty - 1)
    }

    /**
     * Increments a product's currentQty by 1, applying the delta locally and
     * enqueueing the event to the outbox for backend sync.
     */
    suspend fun incrementStock(productId: String): DeltaResult {
        val product = productDao.getProductById(productId)
            ?: return DeltaResult.ProductNotFound

        // Apply delta to Room immediately
        productDao.applyDelta(productId, 1)

        // Enqueue to outbox for backend sync
        outboxDao.insert(
            OutboxEvent(
                productId = productId,
                delta = 1,
                timestamp = System.currentTimeMillis()
            )
        )

        return DeltaResult.Success(newQty = product.currentQty + 1)
    }

    /**
     * Applies an arbitrary delta to a product's currentQty.
     * Validates that the result won't go negative for decrements.
     */
    suspend fun applyDelta(productId: String, delta: Int): DeltaResult {
        if (delta == 0) return DeltaResult.InvalidDelta

        val product = productDao.getProductById(productId)
            ?: return DeltaResult.ProductNotFound

        if (delta < 0 && product.currentQty + delta < 0) {
            return DeltaResult.OutOfStock
        }

        // Apply delta to Room immediately
        productDao.applyDelta(productId, delta)

        // Enqueue to outbox for backend sync
        outboxDao.insert(
            OutboxEvent(
                productId = productId,
                delta = delta,
                timestamp = System.currentTimeMillis()
            )
        )

        return DeltaResult.Success(newQty = product.currentQty + delta)
    }

    // --- Product CRUD ---

    /**
     * Creates a product locally in Room. The product is immediately available to the UI.
     * Background sync (via SyncRepository) handles the API call.
     */
    suspend fun createProduct(product: ProductEntity) {
        productDao.insert(product)
    }

    /**
     * Updates a product locally in Room. Preserves currentQty when updating idealQty.
     */
    suspend fun updateProduct(product: ProductEntity) {
        productDao.update(product)
    }

    /**
     * Deletes a product locally from Room.
     */
    suspend fun deleteProduct(productId: String) {
        productDao.deleteById(productId)
    }

    // --- Category Queries ---

    /** Observes all categories for a household, sorted by sortOrder. */
    fun observeCategories(householdId: String): Flow<List<CategoryEntity>> {
        return categoryDao.getCategoriesByHousehold(householdId)
    }

    /** Gets a category by ID from local cache. */
    suspend fun getCategoryById(categoryId: String): CategoryEntity? {
        return categoryDao.getCategoryById(categoryId)
    }

    // --- Category CRUD ---

    /** Creates a category locally in Room. */
    suspend fun createCategory(category: CategoryEntity) {
        categoryDao.insert(category)
    }

    /** Updates a category locally in Room. */
    suspend fun updateCategory(category: CategoryEntity) {
        categoryDao.update(category)
    }

    /** Deletes a category locally from Room. */
    suspend fun deleteCategory(categoryId: String) {
        categoryDao.deleteById(categoryId)
    }

    /**
     * Batch-updates category sort orders (e.g., after drag-and-drop reorder).
     */
    suspend fun reorderCategories(categories: List<CategoryEntity>) {
        categories.forEach { categoryDao.update(it) }
    }

    // --- Outbox ---

    /** Observes the number of pending outbox events. */
    fun observeOutboxEvents(): Flow<List<OutboxEvent>> {
        return outboxDao.observeAllActive()
    }

    /** Returns the current count of pending outbox events. */
    suspend fun getPendingOutboxCount(): Int {
        return outboxDao.getPendingCount()
    }

    // --- Bulk Operations (for sync/reconciliation) ---

    /** Replaces all products for a household (used during reconciliation). */
    suspend fun replaceAllProducts(householdId: String, products: List<ProductEntity>) {
        productDao.deleteAllByHousehold(householdId)
        productDao.insertAll(products)
    }

    /** Replaces all categories for a household (used during reconciliation). */
    suspend fun replaceAllCategories(householdId: String, categories: List<CategoryEntity>) {
        categoryDao.deleteAllByHousehold(householdId)
        categoryDao.insertAll(categories)
    }
}

/** Represents the result of a stock delta operation. */
sealed interface DeltaResult {
    data class Success(val newQty: Int) : DeltaResult
    data object OutOfStock : DeltaResult
    data object ProductNotFound : DeltaResult
    data object InvalidDelta : DeltaResult
}

/** A product with its derived buy quantity for the shopping list. */
data class ShoppingListItem(
    val product: ProductEntity,
    val buyQty: Int
)
