package com.reabastr.app.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.local.entity.ProductEntity
import com.reabastr.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for the Setup page — products and categories management.
 */
data class SetupUiState(
    val products: List<ProductEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val isLoading: Boolean = true,
    // Product form state
    val productFormName: String = "",
    val productFormIdealQty: String = "",
    val productFormCategoryId: String? = null,
    val productFormNameError: Boolean = false,
    val productFormIdealQtyError: Boolean = false,
    val productFormCategoryError: Boolean = false,
    // Category form state
    val categoryFormName: String = "",
    val categoryFormNameError: Boolean = false,
    // Edit product dialog
    val editingProduct: ProductEntity? = null,
    val editProductName: String = "",
    val editProductIdealQty: String = "",
    val editProductCategoryId: String? = null,
    val editProductNameError: Boolean = false,
    val editProductIdealQtyError: Boolean = false,
    // Delete category dialog
    val deletingCategory: CategoryEntity? = null,
    val reassignCategoryId: String? = null
)

/**
 * One-shot events emitted by SetupViewModel for transient effects.
 */
sealed interface SetupEvent {
    data class ShowError(val message: String) : SetupEvent
    data object ProductCreated : SetupEvent
    data object ProductUpdated : SetupEvent
    data object ProductDeleted : SetupEvent
    data object CategoryCreated : SetupEvent
    data object CategoryDeleted : SetupEvent
    data object LastCategoryError : SetupEvent
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _householdId = MutableStateFlow(
        savedStateHandle.get<String>("householdId") ?: ""
    )

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SetupEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SetupEvent> = _events.asSharedFlow()

    private var observeJob: Job? = null
    private var reorderJob: Job? = null

    val householdId: String get() = _householdId.value

    init {
        if (_householdId.value.isNotEmpty()) {
            observeData()
        }
    }

    /**
     * Sets the household ID and begins observing products and categories.
     */
    fun setHouseholdId(householdId: String) {
        if (householdId == _householdId.value) return
        _householdId.value = householdId
        observeData()
    }

    private fun observeData() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                inventoryRepository.observeProducts(_householdId.value),
                inventoryRepository.observeCategories(_householdId.value)
            ) { products, categories ->
                Pair(products, categories)
            }.collect { (products, categories) ->
                _uiState.value = _uiState.value.copy(
                    products = products,
                    categories = categories,
                    isLoading = false
                )
            }
        }
    }

    // --- Product Form ---

    fun updateProductFormName(name: String) {
        _uiState.value = _uiState.value.copy(
            productFormName = name,
            productFormNameError = false
        )
    }

    fun updateProductFormIdealQty(qty: String) {
        _uiState.value = _uiState.value.copy(
            productFormIdealQty = qty,
            productFormIdealQtyError = false
        )
    }

    fun updateProductFormCategoryId(categoryId: String) {
        _uiState.value = _uiState.value.copy(
            productFormCategoryId = categoryId,
            productFormCategoryError = false
        )
    }

    /**
     * Validates and creates a new product.
     * Name: 1-100 chars, idealQty: 1-9999, category required.
     */
    fun createProduct() {
        val state = _uiState.value
        val name = state.productFormName.trim()
        val idealQtyStr = state.productFormIdealQty.trim()
        val categoryId = state.productFormCategoryId

        var hasError = false

        if (name.isEmpty() || name.length > 100) {
            _uiState.value = _uiState.value.copy(productFormNameError = true)
            hasError = true
        }

        val idealQty = idealQtyStr.toIntOrNull()
        if (idealQty == null || idealQty < 1 || idealQty > 9999) {
            _uiState.value = _uiState.value.copy(productFormIdealQtyError = true)
            hasError = true
        }

        if (categoryId == null) {
            _uiState.value = _uiState.value.copy(productFormCategoryError = true)
            hasError = true
        }

        if (hasError) return

        viewModelScope.launch {
            val product = ProductEntity(
                productId = UUID.randomUUID().toString(),
                householdId = _householdId.value,
                name = name,
                categoryId = categoryId,
                idealQty = idealQty!!,
                currentQty = 0,
                eans = emptyList(),
                lastSyncedAt = 0L
            )
            inventoryRepository.createProduct(product)

            // Reset form
            _uiState.value = _uiState.value.copy(
                productFormName = "",
                productFormIdealQty = "",
                productFormCategoryId = null,
                productFormNameError = false,
                productFormIdealQtyError = false,
                productFormCategoryError = false
            )
            _events.emit(SetupEvent.ProductCreated)
        }
    }

    // --- Edit Product ---

    fun startEditingProduct(product: ProductEntity) {
        _uiState.value = _uiState.value.copy(
            editingProduct = product,
            editProductName = product.name,
            editProductIdealQty = product.idealQty.toString(),
            editProductCategoryId = product.categoryId,
            editProductNameError = false,
            editProductIdealQtyError = false
        )
    }

    fun dismissEditProduct() {
        _uiState.value = _uiState.value.copy(editingProduct = null)
    }

    fun updateEditProductName(name: String) {
        _uiState.value = _uiState.value.copy(
            editProductName = name,
            editProductNameError = false
        )
    }

    fun updateEditProductIdealQty(qty: String) {
        _uiState.value = _uiState.value.copy(
            editProductIdealQty = qty,
            editProductIdealQtyError = false
        )
    }

    fun updateEditProductCategoryId(categoryId: String) {
        _uiState.value = _uiState.value.copy(editProductCategoryId = categoryId)
    }

    /**
     * Validates and saves the edited product.
     */
    fun saveEditedProduct() {
        val state = _uiState.value
        val product = state.editingProduct ?: return
        val name = state.editProductName.trim()
        val idealQtyStr = state.editProductIdealQty.trim()
        val categoryId = state.editProductCategoryId

        var hasError = false

        if (name.isEmpty() || name.length > 100) {
            _uiState.value = _uiState.value.copy(editProductNameError = true)
            hasError = true
        }

        val idealQty = idealQtyStr.toIntOrNull()
        if (idealQty == null || idealQty < 1 || idealQty > 9999) {
            _uiState.value = _uiState.value.copy(editProductIdealQtyError = true)
            hasError = true
        }

        if (hasError) return

        viewModelScope.launch {
            val updated = product.copy(
                name = name,
                idealQty = idealQty!!,
                categoryId = categoryId
            )
            inventoryRepository.updateProduct(updated)
            _uiState.value = _uiState.value.copy(editingProduct = null)
            _events.emit(SetupEvent.ProductUpdated)
        }
    }

    // --- Delete Product ---

    fun deleteProduct(productId: String) {
        viewModelScope.launch {
            inventoryRepository.deleteProduct(productId)
            _events.emit(SetupEvent.ProductDeleted)
        }
    }

    // --- Category Form ---

    fun updateCategoryFormName(name: String) {
        _uiState.value = _uiState.value.copy(
            categoryFormName = name,
            categoryFormNameError = false
        )
    }

    /**
     * Validates and creates a new category.
     * Name: 1-50 chars. Assigns next sortOrder automatically.
     */
    fun createCategory() {
        val name = _uiState.value.categoryFormName.trim()

        if (name.isEmpty() || name.length > 50) {
            _uiState.value = _uiState.value.copy(categoryFormNameError = true)
            return
        }

        viewModelScope.launch {
            val categories = _uiState.value.categories
            val nextSortOrder = if (categories.isEmpty()) 1
            else categories.maxOf { it.sortOrder } + 1

            val category = CategoryEntity(
                categoryId = UUID.randomUUID().toString(),
                householdId = _householdId.value,
                name = name,
                sortOrder = nextSortOrder
            )
            inventoryRepository.createCategory(category)

            _uiState.value = _uiState.value.copy(
                categoryFormName = "",
                categoryFormNameError = false
            )
            _events.emit(SetupEvent.CategoryCreated)
        }
    }

    // --- Delete Category ---

    /**
     * Initiates category deletion. If the category has products, shows reassignment dialog.
     * Prevents deletion of the last category.
     */
    fun startDeletingCategory(category: CategoryEntity) {
        val categories = _uiState.value.categories
        if (categories.size <= 1) {
            _events.tryEmit(SetupEvent.LastCategoryError)
            return
        }

        _uiState.value = _uiState.value.copy(
            deletingCategory = category,
            reassignCategoryId = categories.firstOrNull { it.categoryId != category.categoryId }?.categoryId
        )
    }

    fun dismissDeleteCategory() {
        _uiState.value = _uiState.value.copy(deletingCategory = null)
    }

    fun updateReassignCategoryId(categoryId: String) {
        _uiState.value = _uiState.value.copy(reassignCategoryId = categoryId)
    }

    /**
     * Confirms category deletion, reassigning products to the selected category.
     */
    fun confirmDeleteCategory() {
        val state = _uiState.value
        val category = state.deletingCategory ?: return
        val reassignId = state.reassignCategoryId ?: return

        viewModelScope.launch {
            // Reassign products from deleted category to the chosen one
            val productsToReassign = state.products.filter {
                it.categoryId == category.categoryId
            }
            for (product in productsToReassign) {
                inventoryRepository.updateProduct(product.copy(categoryId = reassignId))
            }

            // Delete the category
            inventoryRepository.deleteCategory(category.categoryId)

            _uiState.value = _uiState.value.copy(deletingCategory = null)
            _events.emit(SetupEvent.CategoryDeleted)
        }
    }

    // --- Drag-to-Reorder Categories ---

    /**
     * Called when user reorders categories via drag-and-drop.
     * Debounces the batch update to within 3 seconds.
     */
    fun reorderCategories(reorderedCategories: List<CategoryEntity>) {
        // Update local UI state immediately
        val updated = reorderedCategories.mapIndexed { index, category ->
            category.copy(sortOrder = index + 1)
        }
        _uiState.value = _uiState.value.copy(categories = updated)

        // Debounce the persist call (batch update within 3s)
        reorderJob?.cancel()
        reorderJob = viewModelScope.launch {
            delay(REORDER_DEBOUNCE_MS)
            inventoryRepository.reorderCategories(updated)
        }
    }

    companion object {
        /** Batch update delay for category reorder — persist within 3 seconds. */
        private const val REORDER_DEBOUNCE_MS = 2500L
    }
}
