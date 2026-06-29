package com.reabastr.app.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.local.entity.ProductEntity
import com.reabastr.app.data.remote.ApiService
import com.reabastr.app.data.remote.dto.CreateProductRequest
import com.reabastr.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the quick-create product bottom sheet.
 */
data class QuickCreateUiState(
    val ean: String = "",
    val eanEditable: Boolean = false,
    val name: String = "",
    val idealQty: String = "",
    val selectedCategoryId: String? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val nameError: Boolean = false,
    val idealQtyError: Boolean = false,
    val eanError: Boolean = false,
    val categoryError: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

/**
 * One-shot events emitted by QuickCreateViewModel.
 */
sealed interface QuickCreateEvent {
    /** Product was successfully created — dismiss the sheet. */
    data class ProductCreated(val product: ProductEntity) : QuickCreateEvent
}

/**
 * ViewModel for the quick-create product flow triggered when an unrecognized EAN is scanned.
 * Handles form validation, POST to backend, and local Room insertion.
 */
@HiltViewModel
class QuickCreateViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val apiService: ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickCreateUiState())
    val uiState: StateFlow<QuickCreateUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<QuickCreateEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<QuickCreateEvent> = _events.asSharedFlow()

    private var householdId: String = ""

    /**
     * Initializes the quick-create flow with the scanned EAN and household context.
     * Loads available categories for the picker.
     */
    fun initialize(ean: String, householdId: String) {
        this.householdId = householdId
        // A blank EAN means the user opened "Add product" manually — let them
        // optionally type a barcode. A pre-filled EAN comes from a scan and is fixed.
        _uiState.value = QuickCreateUiState(ean = ean, eanEditable = ean.isBlank())
        observeCategories()
    }

    private fun observeCategories() {
        viewModelScope.launch {
            inventoryRepository.observeCategories(householdId)
                .collect { categories ->
                    _uiState.value = _uiState.value.copy(categories = categories)
                }
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(
            name = name,
            nameError = false,
            errorMessage = null
        )
    }

    fun updateEan(ean: String) {
        // Only digits, max 13 (EAN-13)
        if (ean.all { it.isDigit() } && ean.length <= 13) {
            _uiState.value = _uiState.value.copy(
                ean = ean,
                eanError = false,
                errorMessage = null
            )
        }
    }

    fun updateIdealQty(qty: String) {
        _uiState.value = _uiState.value.copy(
            idealQty = qty,
            idealQtyError = false,
            errorMessage = null
        )
    }

    fun updateSelectedCategoryId(categoryId: String) {
        _uiState.value = _uiState.value.copy(
            selectedCategoryId = categoryId,
            categoryError = false,
            errorMessage = null
        )
    }

    /**
     * Validates the form and creates the product locally (local-first), queuing
     * it for background sync. This mirrors the Setup create flow so that products
     * and their categories stay consistent on-device even before the backend has
     * been told about locally-created categories.
     */
    fun submit() {
        val state = _uiState.value
        val name = state.name.trim()
        val idealQtyStr = state.idealQty.trim()
        val categoryId = state.selectedCategoryId

        var hasError = false

        if (name.isEmpty() || name.length > 100) {
            _uiState.value = _uiState.value.copy(nameError = true)
            hasError = true
        }

        val idealQty = idealQtyStr.toIntOrNull()
        if (idealQty == null || idealQty < 1 || idealQty > 999) {
            _uiState.value = _uiState.value.copy(idealQtyError = true)
            hasError = true
        }

        if (categoryId == null) {
            _uiState.value = _uiState.value.copy(categoryError = true)
            hasError = true
        }

        // EAN is optional. If provided, it must be a valid EAN-8 or EAN-13.
        val ean = state.ean.trim()
        if (ean.isNotEmpty() && ean.length != 8 && ean.length != 13) {
            _uiState.value = _uiState.value.copy(eanError = true)
            hasError = true
        }

        if (hasError) return

        _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null)

        viewModelScope.launch {
            val product = ProductEntity(
                productId = java.util.UUID.randomUUID().toString(),
                householdId = householdId,
                name = name,
                categoryId = categoryId,
                idealQty = idealQty!!,
                currentQty = 0,
                eans = if (ean.isEmpty()) emptyList() else listOf(ean),
                refs = emptyList(),
                lastSyncedAt = 0L
            )
            inventoryRepository.createProduct(product).fold(
                onSuccess = { saved ->
                    _uiState.value = _uiState.value.copy(isSubmitting = false)
                    _events.emit(QuickCreateEvent.ProductCreated(saved))
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        errorMessage = e.message ?: "Failed to create product"
                    )
                }
            )
        }
    }
}
