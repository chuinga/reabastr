package com.reabastr.app.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reabastr.app.R
import com.reabastr.app.data.local.entity.CategoryEntity

/**
 * Bottom sheet for quick-creating a product when an unrecognized EAN is scanned.
 * Pre-populates the EAN (read-only) and requires name, idealQty, and category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCreateBottomSheet(
    viewModel: QuickCreateViewModel,
    onDismiss: () -> Unit,
    onProductCreated: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is QuickCreateEvent.ProductCreated -> onProductCreated()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        QuickCreateContent(
            uiState = uiState,
            onNameChange = viewModel::updateName,
            onEanChange = viewModel::updateEan,
            onIdealQtyChange = viewModel::updateIdealQty,
            onCategorySelected = viewModel::updateSelectedCategoryId,
            onSubmit = viewModel::submit,
            onCancel = onDismiss
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickCreateContent(
    uiState: QuickCreateUiState,
    onNameChange: (String) -> Unit,
    onEanChange: (String) -> Unit,
    onIdealQtyChange: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Title
        Text(
            text = stringResource(R.string.quick_create_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        // EAN field: editable + optional in manual mode, read-only when pre-filled from a scan
        OutlinedTextField(
            value = uiState.ean,
            onValueChange = onEanChange,
            label = {
                Text(
                    stringResource(
                        if (uiState.eanEditable) R.string.quick_create_ean_optional_label
                        else R.string.quick_create_ean_label
                    )
                )
            },
            readOnly = !uiState.eanEditable,
            enabled = uiState.eanEditable,
            isError = uiState.eanError,
            supportingText = if (uiState.eanError) {
                { Text(stringResource(R.string.quick_create_ean_error)) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Name field (required, 1-100 chars)
        OutlinedTextField(
            value = uiState.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.quick_create_name_label)) },
            isError = uiState.nameError,
            supportingText = if (uiState.nameError) {
                { Text(stringResource(R.string.quick_create_name_error)) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // IdealQty field (required, 1-999)
        OutlinedTextField(
            value = uiState.idealQty,
            onValueChange = { value ->
                // Only allow digits
                if (value.all { it.isDigit() } && value.length <= 3) {
                    onIdealQtyChange(value)
                }
            },
            label = { Text(stringResource(R.string.quick_create_ideal_qty_label)) },
            isError = uiState.idealQtyError,
            supportingText = if (uiState.idealQtyError) {
                { Text(stringResource(R.string.quick_create_ideal_qty_error)) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Category dropdown
        CategoryDropdown(
            categories = uiState.categories,
            selectedCategoryId = uiState.selectedCategoryId,
            isError = uiState.categoryError,
            onCategorySelected = onCategorySelected
        )

        // Error message from API
        if (uiState.errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 16.dp),
                    strokeWidth = 2.dp
                )
            }

            OutlinedButton(
                onClick = onCancel,
                enabled = !uiState.isSubmitting
            ) {
                Text(stringResource(R.string.quick_create_cancel))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = onSubmit,
                enabled = !uiState.isSubmitting
            ) {
                Text(stringResource(R.string.quick_create_submit))
            }
        }

        // Bottom padding for navigation bar
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
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
            label = { Text(stringResource(R.string.quick_create_category_label)) },
            isError = isError,
            supportingText = if (isError) {
                { Text(stringResource(R.string.quick_create_category_error)) }
            } else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
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
                    text = {
                        Text(
                            text = category.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onCategorySelected(category.categoryId)
                        expanded = false
                    }
                )
            }
        }
    }
}
