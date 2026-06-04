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
    val name: String = "",
    val idealQty: String = "",
    val selectedCategoryId: String? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val nameError: Boolean = false,
    val idealQtyError: Boolean = false,
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
        _uiState.value = QuickCreateUiState(ean = ean)
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
     * Validates the form and submits the product creation request.
     * On success: POSTs to backend, inserts locally, emits [QuickCreateEvent.ProductCreated].
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

        if (hasError) return

        _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val request = CreateProductRequest(
                    name = name,
                    categoryId = categoryId!!,
                    idealQty = idealQty!!,
                    eans = listOf(state.ean)
                )

                val response = apiService.createProduct(request)

                if (response.isSuccessful) {
                    val productResponse = response.body()!!
                    val product = ProductEntity(
                        productId = productResponse.productId,
                        householdId = householdId,
                        name = productResponse.name,
                        categoryId = productResponse.categoryId,
                        idealQty = productResponse.idealQty,
                        currentQty = productResponse.currentQty,
                        eans = productResponse.eans,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                    inventoryRepository.createProduct(product)
                    _uiState.value = _uiState.value.copy(isSubmitting = false)
                    _events.emit(QuickCreateEvent.ProductCreated(product))
                } else {
                    val errorBody = response.errorBody()?.string()
                    val message = parseErrorMessage(errorBody)
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        errorMessage = message
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = e.message ?: "Network error"
                )
            }
        }
    }

    private fun parseErrorMessage(errorBody: String?): String {
        if (errorBody == null) return "Unknown error"
        // Try to extract message from standard error response shape
        return try {
            val regex = """"message"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(errorBody)?.groupValues?.get(1) ?: "Request failed"
        } catch (_: Exception) {
            "Request failed"
        }
    }
}
