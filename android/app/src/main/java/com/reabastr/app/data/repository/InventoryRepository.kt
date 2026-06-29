package com.reabastr.app.data.repository

import com.reabastr.app.data.local.dao.CategoryDao
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.dao.ProductDao
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.local.entity.OutboxEvent
import com.reabastr.app.data.local.entity.ProductEntity
import com.reabastr.app.data.remote.ApiService
import com.reabastr.app.data.remote.dto.AddEanRequest
import com.reabastr.app.data.remote.dto.CategoryOrderItem
import com.reabastr.app.data.remote.dto.CreateCategoryRequest
import com.reabastr.app.data.remote.dto.CreateProductRequest
import com.reabastr.app.data.remote.dto.ReorderCategoriesRequest
import com.reabastr.app.data.remote.dto.UpdateCategoryRequest
import com.reabastr.app.data.remote.dto.UpdateProductRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mediates between Room (local) and the REST API (remote) for inventory operations.
 *
 * - Stock adjustments (+/-) are local-first: applied to Room immediately and queued
 *   to the outbox for background sync (offline-friendly).
 * - Catalog operations (products & categories CRUD) are write-through to the API so
 *   the backend (DynamoDB) is the source of truth and data syncs across devices. The
 *   local Room cache is updated from the server response (using server-assigned IDs).
 *
 * UI talks to this repository, never directly to the network or DAOs.
 */
@Singleton
class InventoryRepository @Inject constructor(
    private val productDao: ProductDao,
    private val categoryDao: CategoryDao,
    private val outboxDao: OutboxDao,
    private val apiService: ApiService
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

    // --- Product CRUD (write-through to API, then update local cache) ---

    /**
     * Creates a product on the backend, then caches the server result locally
     * (with the server-assigned productId). Returns the created entity or an error.
     */
    suspend fun createProduct(product: ProductEntity): Result<ProductEntity> = runCatching {
        val categoryId = product.categoryId
            ?: throw CatalogException("A category is required")
        val resp = apiService.createProduct(
            CreateProductRequest(
                name = product.name,
                categoryId = categoryId,
                idealQty = product.idealQty,
                eans = product.eans,
                refs = product.refs
            )
        )
        val body = resp.requireBody("create product")
        val saved = ProductEntity(
            productId = body.productId,
            householdId = product.householdId,
            name = body.name,
            categoryId = body.categoryId,
            idealQty = body.idealQty,
            currentQty = body.currentQty,
            eans = body.eans,
            refs = body.refs,
            lastSyncedAt = System.currentTimeMillis()
        )
        productDao.insert(saved)
        saved
    }

    /**
     * Updates a product on the backend (name/category/idealQty/refs), syncs EAN
     * additions/removals via the EAN endpoints, then updates the local cache.
     */
    suspend fun updateProduct(product: ProductEntity): Result<Unit> = runCatching {
        val resp = apiService.updateProduct(
            product.productId,
            UpdateProductRequest(
                name = product.name,
                categoryId = product.categoryId,
                idealQty = product.idealQty,
                refs = product.refs
            )
        )
        resp.requireBody("update product")

        // Sync EAN changes as a diff against the cached copy
        val existingEans = productDao.getProductById(product.productId)?.eans ?: emptyList()
        val toAdd = product.eans - existingEans.toSet()
        val toRemove = existingEans - product.eans.toSet()
        for (ean in toAdd) {
            apiService.addEan(product.productId, AddEanRequest(ean)).requireSuccess("add EAN")
        }
        for (ean in toRemove) {
            apiService.removeEan(product.productId, ean).requireSuccess("remove EAN")
        }

        productDao.update(product.copy(lastSyncedAt = System.currentTimeMillis()))
    }

    /**
     * Deletes a product on the backend, then removes it from the local cache.
     */
    suspend fun deleteProduct(productId: String): Result<Unit> = runCatching {
        apiService.deleteProduct(productId).requireSuccess("delete product")
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

    // --- Category CRUD (write-through to API, then update local cache) ---

    /** Creates a category on the backend, then caches it locally with its server ID. */
    suspend fun createCategory(category: CategoryEntity): Result<CategoryEntity> = runCatching {
        val resp = apiService.createCategory(CreateCategoryRequest(name = category.name))
        val body = resp.requireBody("create category")
        val saved = CategoryEntity(
            categoryId = body.categoryId,
            householdId = category.householdId,
            name = body.name,
            sortOrder = body.sortOrder
        )
        categoryDao.insert(saved)
        saved
    }

    /** Updates a category's name on the backend, then updates the local cache. */
    suspend fun updateCategory(category: CategoryEntity): Result<Unit> = runCatching {
        apiService.updateCategory(
            category.categoryId,
            UpdateCategoryRequest(name = category.name, sortOrder = category.sortOrder)
        ).requireBody("update category")
        categoryDao.update(category)
    }

    /**
     * Deletes a category on the backend (reassigning its products to [reassignToId]),
     * then mirrors the reassignment + deletion locally.
     */
    suspend fun deleteCategory(categoryId: String, reassignToId: String): Result<Unit> = runCatching {
        apiService.deleteCategory(categoryId, reassignToId).requireSuccess("delete category")
        // Mirror the server-side reassignment locally, then delete the category.
        productDao.reassignCategory(categoryId, reassignToId)
        categoryDao.deleteById(categoryId)
    }

    /** Batch-updates category sort orders on the backend, then locally. */
    suspend fun reorderCategories(categories: List<CategoryEntity>): Result<Unit> = runCatching {
        apiService.reorderCategories(
            ReorderCategoriesRequest(
                order = categories.map { CategoryOrderItem(it.categoryId, it.sortOrder) }
            )
        ).requireSuccess("reorder categories")
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

/** Raised when a catalog API call fails; carries a user-facing message. */
class CatalogException(message: String) : Exception(message)

/** Returns the parsed body on success, or throws a [CatalogException] with the server message. */
private fun <T> Response<T>.requireBody(action: String): T {
    if (!isSuccessful) throw CatalogException(parseError(action))
    return body() ?: throw CatalogException("Empty response while trying to $action")
}

/** Ensures a 2xx response or throws a [CatalogException]. */
private fun <T> Response<T>.requireSuccess(action: String) {
    if (!isSuccessful) throw CatalogException(parseError(action))
}

/** Extracts a human-readable message from a Retrofit error body. */
private fun <T> Response<T>.parseError(action: String): String {
    val raw = try {
        errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    val message = raw?.let {
        Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1)
    }
    return message ?: "Failed to $action (HTTP ${code()})"
}

/** A product with its derived buy quantity for the shopping list. */
data class ShoppingListItem(
    val product: ProductEntity,
    val buyQty: Int
)
