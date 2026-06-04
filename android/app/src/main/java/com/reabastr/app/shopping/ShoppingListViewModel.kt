package com.reabastr.app.shopping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.repository.InventoryRepository
import com.reabastr.app.data.repository.ShoppingListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A shopping list group: a category with its products sorted alphabetically.
 */
data class ShoppingListGroup(
    val categoryName: String,
    val sortOrder: Int,
    val items: List<ShoppingListItem>
)

/**
 * UI state for the Shopping List page.
 */
data class ShoppingListUiState(
    val groups: List<ShoppingListGroup> = emptyList(),
    val isEmpty: Boolean = true,
    val isLoading: Boolean = true
)

/**
 * One-shot events emitted by ShoppingListViewModel for transient effects.
 */
sealed interface ShoppingListEvent {
    /** Scanned EAN not recognized — show error. */
    data class UnrecognizedEan(val ean: String) : ShoppingListEvent

    /** Scanner should be opened. */
    data object OpenScanner : ShoppingListEvent
}

@HiltViewModel
class ShoppingListViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _householdId = MutableStateFlow(
        savedStateHandle.get<String>("householdId") ?: ""
    )

    private val _uiState = MutableStateFlow(ShoppingListUiState())
    val uiState: StateFlow<ShoppingListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ShoppingListEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ShoppingListEvent> = _events.asSharedFlow()

    private var observeJob: Job? = null

    val householdId: String get() = _householdId.value

    init {
        if (_householdId.value.isNotEmpty()) {
            observeShoppingList()
        }
    }

    /**
     * Sets the household ID and begins observing the derived shopping list.
     */
    fun setHouseholdId(householdId: String) {
        if (householdId == _householdId.value) return
        _householdId.value = householdId
        observeShoppingList()
    }

    private fun observeShoppingList() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                inventoryRepository.observeShoppingList(_householdId.value),
                inventoryRepository.observeCategories(_householdId.value)
            ) { shoppingItems, categories ->
                buildGroupedList(shoppingItems, categories)
            }.collect { groups ->
                _uiState.value = ShoppingListUiState(
                    groups = groups,
                    isEmpty = groups.isEmpty(),
                    isLoading = false
                )
            }
        }
    }

    /**
     * Groups shopping list items by category sortOrder, sorts alphabetically within groups.
     * Products with no category go to an "Uncategorized" group at the end.
     */
    private fun buildGroupedList(
        items: List<ShoppingListItem>,
        categories: List<CategoryEntity>
    ): List<ShoppingListGroup> {
        val categoryMap = categories.associateBy { it.categoryId }

        // Separate categorized vs uncategorized items
        val categorized = mutableMapOf<String, MutableList<ShoppingListItem>>()
        val uncategorized = mutableListOf<ShoppingListItem>()

        for (item in items) {
            val categoryId = item.product.categoryId
            if (categoryId != null && categoryMap.containsKey(categoryId)) {
                categorized.getOrPut(categoryId) { mutableListOf() }.add(item)
            } else {
                uncategorized.add(item)
            }
        }

        // Build groups sorted by category sortOrder, items alphabetically within
        val groups = mutableListOf<ShoppingListGroup>()

        categorized.entries
            .sortedBy { categoryMap[it.key]?.sortOrder ?: Int.MAX_VALUE }
            .forEach { (categoryId, groupItems) ->
                val category = categoryMap[categoryId]!!
                groups.add(
                    ShoppingListGroup(
                        categoryName = category.name,
                        sortOrder = category.sortOrder,
                        items = groupItems.sortedBy { it.product.name.lowercase() }
                    )
                )
            }

        // Uncategorized group at the end
        if (uncategorized.isNotEmpty()) {
            groups.add(
                ShoppingListGroup(
                    categoryName = UNCATEGORIZED_GROUP_NAME,
                    sortOrder = Int.MAX_VALUE,
                    items = uncategorized.sortedBy { it.product.name.lowercase() }
                )
            )
        }

        return groups
    }

    /**
     * Increments a product's stock by 1 (restock from shopping list).
     * If currentQty >= idealQty after increment, the item will automatically
     * disappear from the list on the next Flow emission (same UI update cycle).
     */
    fun incrementProduct(productId: String) {
        viewModelScope.launch {
            inventoryRepository.incrementStock(productId)
        }
    }

    /**
     * Handles a scanned barcode result from the scanner.
     * - If EAN maps to a known product → increment it (restock)
     * - If EAN is unknown → emit error event (no quick-create on Shopping List)
     */
    fun onScanResult(ean: String) {
        viewModelScope.launch {
            val product = inventoryRepository.lookupProductByEan(_householdId.value, ean)
            if (product != null) {
                incrementProduct(product.productId)
            } else {
                _events.emit(ShoppingListEvent.UnrecognizedEan(ean))
            }
        }
    }

    /**
     * Opens the barcode scanner.
     */
    fun openScanner() {
        _events.tryEmit(ShoppingListEvent.OpenScanner)
    }

    companion object {
        // Sentinel name for the uncategorized group — UI uses string resource instead
        const val UNCATEGORIZED_GROUP_NAME = "__uncategorized__"
    }
}
