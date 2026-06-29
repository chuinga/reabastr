package com.reabastr.app.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reabastr.app.data.local.entity.ProductEntity
import com.reabastr.app.data.repository.DeltaResult
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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Home page (Take from Stock).
 */
data class HomeUiState(
    val products: List<ProductEntity> = emptyList(),
    val outOfStockProductId: String? = null,
    val isLoading: Boolean = true
)

/**
 * One-shot events emitted by HomeViewModel for navigation or transient effects.
 */
sealed interface HomeEvent {
    /** Unknown EAN scanned — navigate to quick-create flow with pre-populated EAN. */
    data class NavigateToQuickCreate(val ean: String) : HomeEvent

    /** Scanner should be opened. */
    data object OpenScanner : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Household ID passed via SavedStateHandle (navigation argument) or set manually
    private val _householdId = MutableStateFlow(
        savedStateHandle.get<String>("householdId") ?: ""
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    private var outOfStockDismissJob: Job? = null
    private var observeJob: Job? = null

    val householdId: String get() = _householdId.value

    init {
        if (_householdId.value.isNotEmpty()) {
            observeProducts()
        }
    }

    /**
     * Sets the household ID and begins observing products.
     * Call this when the household ID is known after onboarding.
     */
    fun setHouseholdId(householdId: String) {
        if (householdId == _householdId.value) return
        _householdId.value = householdId
        observeProducts()
    }

    private fun observeProducts() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            inventoryRepository.observeProducts(_householdId.value)
                .collect { products ->
                    _uiState.value = _uiState.value.copy(
                        products = products,
                        isLoading = false
                    )
                }
        }
    }

    /**
     * Decrements a product's stock by 1 (take from stock).
     * If currentQty is 0, shows out-of-stock message for 3 seconds.
     */
    fun decrementProduct(productId: String) {
        viewModelScope.launch {
            when (inventoryRepository.decrementStock(productId)) {
                is DeltaResult.Success -> {
                    // Successfully decremented — UI will update via Flow
                }
                is DeltaResult.OutOfStock -> {
                    showOutOfStockMessage(productId)
                }
                is DeltaResult.ProductNotFound,
                is DeltaResult.InvalidDelta -> {
                    // Ignore — shouldn't happen in normal flow
                }
            }
        }
    }

    /**
     * Increments a product's stock by 1 (put into stock / restock).
     */
    fun incrementProduct(productId: String) {
        viewModelScope.launch {
            inventoryRepository.incrementStock(productId)
        }
    }

    /**
     * Handles a scanned barcode result from the scanner.
     * - If EAN maps to a known product → decrement it
     * - If EAN is unknown → emit event to navigate to quick-create
     */
    fun onScanResult(ean: String) {
        viewModelScope.launch {
            val product = inventoryRepository.lookupProductByEan(_householdId.value, ean)
            if (product != null) {
                decrementProduct(product.productId)
            } else {
                _events.emit(HomeEvent.NavigateToQuickCreate(ean))
            }
        }
    }

    /**
     * Opens the barcode scanner.
     */
    fun openScanner() {
        _events.tryEmit(HomeEvent.OpenScanner)
    }

    /**
     * Shows out-of-stock indicator for the given product, auto-dismissing after 3 seconds.
     */
    private fun showOutOfStockMessage(productId: String) {
        outOfStockDismissJob?.cancel()
        _uiState.value = _uiState.value.copy(outOfStockProductId = productId)
        outOfStockDismissJob = viewModelScope.launch {
            delay(3000L)
            _uiState.value = _uiState.value.copy(outOfStockProductId = null)
        }
    }

    /**
     * Dismisses the out-of-stock message manually (if user interacts before 3s).
     */
    fun dismissOutOfStockMessage() {
        outOfStockDismissJob?.cancel()
        _uiState.value = _uiState.value.copy(outOfStockProductId = null)
    }
}
