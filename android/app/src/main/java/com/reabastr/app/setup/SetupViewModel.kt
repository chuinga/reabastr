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
    val productFormEans: List<String> = emptyList(),
    val productFormEanInput: String = "",
    val productFormRefs: List<String> = emptyList(),
    val productFormRefInput: String = "",
    val productFormNameError: Boolean = false,
    val productFormIdealQtyError: Boolean = false,
    val productFormCategoryError: Boolean = false,
    val productFormEanError: Boolean = false,
    // Category form state
    val categoryFormName: String = "",
    val categoryFormNameError: Boolean = false,
    // Edit product dialog
    val editingProduct: ProductEntity? = null,
    val editProductName: String = "",
    val editProductIdealQty: String = "",
    val editProductCategoryId: String? = null,
    val editProductEans: List<String> = emptyList(),
    val editProductEanInput: String = "",
    val editProductRefs: List<String> = emptyList(),
    val editProductRefInput: String = "",
    val editProductNameError: Boolean = false,
    val editProductIdealQtyError: Boolean = false,
    val editProductEanError: Boolean = false,
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

    fun updateProductFormEanInput(ean: String) {
        if (ean.all { it.isDigit() } && ean.length <= 13) {
            _uiState.value = _uiState.value.copy(productFormEanInput = ean, productFormEanError = false)
        }
    }

    fun addProductFormEan() {
        val ean = _uiState.value.productFormEanInput.trim()
        if (ean.isEmpty()) return
        if (ean.length != 8 && ean.length != 13) {
            _uiState.value = _uiState.value.copy(productFormEanError = true)
            return
        }
        if (ean in _uiState.value.productFormEans) {
            _uiState.value = _uiState.value.copy(productFormEanInput = "")
            return
        }
        _uiState.value = _uiState.value.copy(
            productFormEans = _uiState.value.productFormEans + ean,
            productFormEanInput = "",
            productFormEanError = false
        )
    }

    fun removeProductFormEan(ean: String) {
        _uiState.value = _uiState.value.copy(
            productFormEans = _uiState.value.productFormEans - ean
        )
    }

    fun updateProductFormRefInput(ref: String) {
        _uiState.value = _uiState.value.copy(productFormRefInput = ref)
    }

    fun addProductFormRef() {
        val ref = _uiState.value.productFormRefInput.trim()
        if (ref.isEmpty() || ref in _uiState.value.productFormRefs) {
            _uiState.value = _uiState.value.copy(productFormRefInput = "")
            return
        }
        _uiState.value = _uiState.value.copy(
            productFormRefs = _uiState.value.productFormRefs + ref,
            productFormRefInput = ""
        )
    }

    fun removeProductFormRef(ref: String) {
        _uiState.value = _uiState.value.copy(
            productFormRefs = _uiState.value.productFormRefs - ref
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

        // Commit any pending EAN/ref still in the input boxes before validating.
        addProductFormEan()
        addProductFormRef()
        val state2 = _uiState.value
        // If the pending EAN was invalid, addProductFormEan flagged the error.
        if (state2.productFormEanError) hasError = true

        if (hasError) return

        val eans = state2.productFormEans
        val refs = state2.productFormRefs

        viewModelScope.launch {
            val product = ProductEntity(
                productId = UUID.randomUUID().toString(),
                householdId = _householdId.value,
                name = name,
                categoryId = categoryId,
                idealQty = idealQty!!,
                currentQty = 0,
                eans = eans,
                refs = refs,
                lastSyncedAt = 0L
            )
            inventoryRepository.createProduct(product).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        productFormName = "",
                        productFormIdealQty = "",
                        productFormCategoryId = null,
                        productFormEans = emptyList(),
                        productFormEanInput = "",
                        productFormRefs = emptyList(),
                        productFormRefInput = "",
                        productFormNameError = false,
                        productFormIdealQtyError = false,
                        productFormCategoryError = false,
                        productFormEanError = false
                    )
                    _events.emit(SetupEvent.ProductCreated)
                },
                onFailure = { e ->
                    _events.emit(SetupEvent.ShowError(e.message ?: "Failed to create product"))
                }
            )
        }
    }

    // --- Edit Product ---

    fun startEditingProduct(product: ProductEntity) {
        _uiState.value = _uiState.value.copy(
            editingProduct = product,
            editProductName = product.name,
            editProductIdealQty = product.idealQty.toString(),
            editProductCategoryId = product.categoryId,
            editProductEans = product.eans,
            editProductEanInput = "",
            editProductRefs = product.refs,
            editProductRefInput = "",
            editProductNameError = false,
            editProductIdealQtyError = false,
            editProductEanError = false
        )
    }

    fun updateEditProductEanInput(ean: String) {
        if (ean.all { it.isDigit() } && ean.length <= 13) {
            _uiState.value = _uiState.value.copy(editProductEanInput = ean, editProductEanError = false)
        }
    }

    fun addEditProductEan() {
        val ean = _uiState.value.editProductEanInput.trim()
        if (ean.isEmpty()) return
        if (ean.length != 8 && ean.length != 13) {
            _uiState.value = _uiState.value.copy(editProductEanError = true)
            return
        }
        if (ean in _uiState.value.editProductEans) {
            _uiState.value = _uiState.value.copy(editProductEanInput = "")
            return
        }
        _uiState.value = _uiState.value.copy(
            editProductEans = _uiState.value.editProductEans + ean,
            editProductEanInput = "",
            editProductEanError = false
        )
    }

    fun removeEditProductEan(ean: String) {
        _uiState.value = _uiState.value.copy(editProductEans = _uiState.value.editProductEans - ean)
    }

    fun updateEditProductRefInput(ref: String) {
        _uiState.value = _uiState.value.copy(editProductRefInput = ref)
    }

    fun addEditProductRef() {
        val ref = _uiState.value.editProductRefInput.trim()
        if (ref.isEmpty() || ref in _uiState.value.editProductRefs) {
            _uiState.value = _uiState.value.copy(editProductRefInput = "")
            return
        }
        _uiState.value = _uiState.value.copy(
            editProductRefs = _uiState.value.editProductRefs + ref,
            editProductRefInput = ""
        )
    }

    fun removeEditProductRef(ref: String) {
        _uiState.value = _uiState.value.copy(editProductRefs = _uiState.value.editProductRefs - ref)
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

        // Commit any pending EAN/ref still typed in the input boxes.
        addEditProductEan()
        addEditProductRef()
        val committed = _uiState.value
        if (committed.editProductEanError) return

        viewModelScope.launch {
            val updated = product.copy(
                name = name,
                idealQty = idealQty!!,
                categoryId = categoryId,
                eans = committed.editProductEans,
                refs = committed.editProductRefs
            )
            inventoryRepository.updateProduct(updated).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(editingProduct = null)
                    _events.emit(SetupEvent.ProductUpdated)
                },
                onFailure = { e ->
                    _events.emit(SetupEvent.ShowError(e.message ?: "Failed to update product"))
                }
            )
        }
    }

    // --- Delete Product ---

    fun deleteProduct(productId: String) {
        viewModelScope.launch {
            inventoryRepository.deleteProduct(productId).fold(
                onSuccess = { _events.emit(SetupEvent.ProductDeleted) },
                onFailure = { e ->
                    _events.emit(SetupEvent.ShowError(e.message ?: "Failed to delete product"))
                }
            )
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
            inventoryRepository.createCategory(category).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        categoryFormName = "",
                        categoryFormNameError = false
                    )
                    _events.emit(SetupEvent.CategoryCreated)
                },
                onFailure = { e ->
                    _events.emit(SetupEvent.ShowError(e.message ?: "Failed to create category"))
                }
            )
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
     * Confirms category deletion. The backend reassigns products to the chosen
     * category and removes the category; the repository mirrors this locally.
     */
    fun confirmDeleteCategory() {
        val state = _uiState.value
        val category = state.deletingCategory ?: return
        val reassignId = state.reassignCategoryId ?: return

        viewModelScope.launch {
            inventoryRepository.deleteCategory(category.categoryId, reassignId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(deletingCategory = null)
                    _events.emit(SetupEvent.CategoryDeleted)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(deletingCategory = null)
                    _events.emit(SetupEvent.ShowError(e.message ?: "Failed to delete category"))
                }
            )
        }
    }

    // --- Drag-to-Reorder Categories ---

    /**
     * Moves a category one position up or down and persists the new order
     * immediately. Provides a reliable alternative to long-press drag.
     */
    fun moveCategory(categoryId: String, up: Boolean) {
        val current = _uiState.value.categories
        val index = current.indexOfFirst { it.categoryId == categoryId }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in current.indices) return

        val reordered = current.toMutableList()
            .apply { add(target, removeAt(index)) }
            .mapIndexed { i, c -> c.copy(sortOrder = i + 1) }

        _uiState.value = _uiState.value.copy(categories = reordered)
        reorderJob?.cancel()
        reorderJob = viewModelScope.launch {
            inventoryRepository.reorderCategories(reordered).onFailure { e ->
                _events.emit(SetupEvent.ShowError(e.message ?: "Failed to reorder categories"))
            }
        }
    }

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
            inventoryRepository.reorderCategories(updated).onFailure { e ->
                _events.emit(SetupEvent.ShowError(e.message ?: "Failed to reorder categories"))
            }
        }
    }

    companion object {
        /** Batch update delay for category reorder — persist within 3 seconds. */
        private const val REORDER_DEBOUNCE_MS = 2500L
    }
}
