package com.reabastr.app.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reabastr.app.R
import com.reabastr.app.data.local.entity.CategoryEntity
import com.reabastr.app.data.local.entity.ProductEntity
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupPage(
    viewModel: SetupViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val lastCategoryErrorMsg = stringResource(R.string.setup_last_category_error)
    val productCreatedMsg = stringResource(R.string.setup_product_created)
    val categoryCreatedMsg = stringResource(R.string.setup_category_created)

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SetupEvent.LastCategoryError -> {
                    snackbarHostState.showSnackbar(lastCategoryErrorMsg)
                }
                is SetupEvent.ProductCreated -> {
                    snackbarHostState.showSnackbar(productCreatedMsg)
                }
                is SetupEvent.CategoryCreated -> {
                    snackbarHostState.showSnackbar(categoryCreatedMsg)
                }
                is SetupEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                else -> { /* ProductUpdated, ProductDeleted, CategoryDeleted handled via UI updates */ }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.setup_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab bar: Products | Categories
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.setup_tab_products)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.setup_tab_categories)) }
                )
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                selectedTab == 0 -> {
                    ProductsTab(
                        products = uiState.products,
                        categories = uiState.categories,
                        formName = uiState.productFormName,
                        formIdealQty = uiState.productFormIdealQty,
                        formCategoryId = uiState.productFormCategoryId,
                        formNameError = uiState.productFormNameError,
                        formIdealQtyError = uiState.productFormIdealQtyError,
                        formCategoryError = uiState.productFormCategoryError,
                        onNameChange = viewModel::updateProductFormName,
                        onIdealQtyChange = viewModel::updateProductFormIdealQty,
                        onCategoryChange = viewModel::updateProductFormCategoryId,
                        onCreateProduct = viewModel::createProduct,
                        onEditProduct = viewModel::startEditingProduct,
                        onDeleteProduct = viewModel::deleteProduct
                    )
                }
                selectedTab == 1 -> {
                    CategoriesTab(
                        categories = uiState.categories,
                        formName = uiState.categoryFormName,
                        formNameError = uiState.categoryFormNameError,
                        onNameChange = viewModel::updateCategoryFormName,
                        onCreateCategory = viewModel::createCategory,
                        onDeleteCategory = viewModel::startDeletingCategory,
                        onReorder = viewModel::reorderCategories
                    )
                }
            }
        }
    }

    // Edit Product Dialog
    uiState.editingProduct?.let {
        EditProductDialog(
            categories = uiState.categories,
            name = uiState.editProductName,
            idealQty = uiState.editProductIdealQty,
            categoryId = uiState.editProductCategoryId,
            nameError = uiState.editProductNameError,
            idealQtyError = uiState.editProductIdealQtyError,
            onNameChange = viewModel::updateEditProductName,
            onIdealQtyChange = viewModel::updateEditProductIdealQty,
            onCategoryChange = viewModel::updateEditProductCategoryId,
            onSave = viewModel::saveEditedProduct,
            onDismiss = viewModel::dismissEditProduct
        )
    }

    // Delete Category Reassignment Dialog
    uiState.deletingCategory?.let { category ->
        DeleteCategoryDialog(
            categoryToDelete = category,
            availableCategories = uiState.categories.filter { it.categoryId != category.categoryId },
            reassignCategoryId = uiState.reassignCategoryId,
            productsInCategory = uiState.products.count { it.categoryId == category.categoryId },
            onReassignCategoryChange = viewModel::updateReassignCategoryId,
            onConfirm = viewModel::confirmDeleteCategory,
            onDismiss = viewModel::dismissDeleteCategory
        )
    }
}

// --- Products Tab ---

@Composable
private fun ProductsTab(
    products: List<ProductEntity>,
    categories: List<CategoryEntity>,
    formName: String,
    formIdealQty: String,
    formCategoryId: String?,
    formNameError: Boolean,
    formIdealQtyError: Boolean,
    formCategoryError: Boolean,
    onNameChange: (String) -> Unit,
    onIdealQtyChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onCreateProduct: () -> Unit,
    onEditProduct: (ProductEntity) -> Unit,
    onDeleteProduct: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Create product form
        item(key = "create_product_form") {
            CreateProductForm(
                categories = categories,
                name = formName,
                idealQty = formIdealQty,
                categoryId = formCategoryId,
                nameError = formNameError,
                idealQtyError = formIdealQtyError,
                categoryError = formCategoryError,
                onNameChange = onNameChange,
                onIdealQtyChange = onIdealQtyChange,
                onCategoryChange = onCategoryChange,
                onCreate = onCreateProduct
            )
        }

        item(key = "products_header") {
            Text(
                text = stringResource(R.string.setup_products_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
        }

        if (products.isEmpty()) {
            item(key = "products_empty") {
                Text(
                    text = stringResource(R.string.setup_products_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(items = products, key = { it.productId }) { product ->
                val categoryName = categories.find { it.categoryId == product.categoryId }?.name
                ProductListItem(
                    product = product,
                    categoryName = categoryName,
                    onEdit = { onEditProduct(product) },
                    onDelete = { onDeleteProduct(product.productId) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProductForm(
    categories: List<CategoryEntity>,
    name: String,
    idealQty: String,
    categoryId: String?,
    nameError: Boolean,
    idealQtyError: Boolean,
    categoryError: Boolean,
    onNameChange: (String) -> Unit,
    onIdealQtyChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onCreate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.setup_create_product_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.setup_product_name_label)) },
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.setup_product_name_error)) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = idealQty,
                onValueChange = { value ->
                    // Only allow digits
                    if (value.all { it.isDigit() }) {
                        onIdealQtyChange(value)
                    }
                },
                label = { Text(stringResource(R.string.setup_product_ideal_qty_label)) },
                isError = idealQtyError,
                supportingText = if (idealQtyError) {
                    { Text(stringResource(R.string.setup_product_ideal_qty_error)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            CategoryPicker(
                categories = categories,
                selectedCategoryId = categoryId,
                isError = categoryError,
                onCategorySelected = onCategoryChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setup_create_product_button))
            }
        }
    }
}

@Composable
private fun ProductListItem(
    product: ProductEntity,
    categoryName: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.setup_product_details,
                        product.idealQty,
                        categoryName ?: stringResource(R.string.shopping_uncategorized)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.setup_edit_product_description, product.name),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.setup_delete_product_description, product.name),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// --- Categories Tab ---

@Composable
private fun CategoriesTab(
    categories: List<CategoryEntity>,
    formName: String,
    formNameError: Boolean,
    onNameChange: (String) -> Unit,
    onCreateCategory: () -> Unit,
    onDeleteCategory: (CategoryEntity) -> Unit,
    onReorder: (List<CategoryEntity>) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Create category form at top
        CreateCategoryForm(
            name = formName,
            nameError = formNameError,
            onNameChange = onNameChange,
            onCreate = onCreateCategory,
            modifier = Modifier.padding(16.dp)
        )

        Text(
            text = stringResource(R.string.setup_categories_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (categories.isEmpty()) {
            Text(
                text = stringResource(R.string.setup_categories_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            ReorderableCategoryList(
                categories = categories,
                onReorder = onReorder,
                onDelete = onDeleteCategory
            )
        }
    }
}

@Composable
private fun CreateCategoryForm(
    name: String,
    nameError: Boolean,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.setup_create_category_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.setup_category_name_label)) },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(R.string.setup_category_name_error)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onCreate,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ReorderableCategoryList(
    categories: List<CategoryEntity>,
    onReorder: (List<CategoryEntity>) -> Unit,
    onDelete: (CategoryEntity) -> Unit
) {
    var data by remember(categories) { mutableStateOf(categories) }
    val state = rememberReorderableLazyListState(
        onMove = { from, to ->
            data = data.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        },
        onDragEnd = { _, _ ->
            onReorder(data)
        }
    )

    LazyColumn(
        state = state.listState,
        modifier = Modifier
            .fillMaxSize()
            .reorderable(state),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items = data, key = { it.categoryId }) { category ->
            ReorderableItem(
                reorderableState = state,
                key = category.categoryId
            ) { isDragging ->
                val elevation = animateColorAsState(
                    targetValue = if (isDragging) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    label = "drag_color"
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .detectReorderAfterLongPress(state),
                    colors = CardDefaults.cardColors(
                        containerColor = elevation.value
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = stringResource(R.string.setup_drag_handle_description),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDelete(category) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(
                                    R.string.setup_delete_category_description,
                                    category.name
                                ),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Dialogs ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProductDialog(
    categories: List<CategoryEntity>,
    name: String,
    idealQty: String,
    categoryId: String?,
    nameError: Boolean,
    idealQtyError: Boolean,
    onNameChange: (String) -> Unit,
    onIdealQtyChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_edit_product_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.setup_product_name_label)) },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(R.string.setup_product_name_error)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = idealQty,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() }) {
                            onIdealQtyChange(value)
                        }
                    },
                    label = { Text(stringResource(R.string.setup_product_ideal_qty_label)) },
                    isError = idealQtyError,
                    supportingText = if (idealQtyError) {
                        { Text(stringResource(R.string.setup_product_ideal_qty_error)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                CategoryPicker(
                    categories = categories,
                    selectedCategoryId = categoryId,
                    isError = false,
                    onCategorySelected = onCategoryChange
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(stringResource(R.string.setup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setup_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteCategoryDialog(
    categoryToDelete: CategoryEntity,
    availableCategories: List<CategoryEntity>,
    reassignCategoryId: String?,
    productsInCategory: Int,
    onReassignCategoryChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_delete_category_title)) },
        text = {
            Column {
                if (productsInCategory > 0) {
                    Text(
                        stringResource(
                            R.string.setup_delete_category_reassign_message,
                            categoryToDelete.name,
                            productsInCategory
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CategoryPicker(
                        categories = availableCategories,
                        selectedCategoryId = reassignCategoryId,
                        isError = false,
                        onCategorySelected = onReassignCategoryChange
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.setup_delete_category_confirm_message,
                            categoryToDelete.name
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.setup_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setup_cancel))
            }
        }
    )
}

// --- Shared Components ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<CategoryEntity>,
    selectedCategoryId: String?,
    isError: Boolean,
    onCategorySelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedCategory = categories.find { it.categoryId == selectedCategoryId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedCategory?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.setup_category_picker_label)) },
            isError = isError,
            supportingText = if (isError) {
                { Text(stringResource(R.string.setup_category_picker_error)) }
            } else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        onCategorySelected(category.categoryId)
                        expanded = false
                    }
                )
            }
        }
    }
}
