package com.reabastr.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reusable editor for a list of identifier strings (e.g. EANs or reference codes).
 * Shows an input row with an Add button and the current values as removable chips.
 *
 * State is hoisted: the parent owns both the committed [values] and the in-progress
 * [inputValue], and reacts to [onInputChange], [onAdd], and [onRemove].
 */
@Composable
fun IdentifierListEditor(
    label: String,
    values: List<String>,
    inputValue: String,
    isError: Boolean,
    errorText: String?,
    numeric: Boolean,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                value = inputValue,
                onValueChange = onInputChange,
                label = { Text(label) },
                isError = isError,
                supportingText = if (isError && errorText != null) {
                    { Text(errorText) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onAdd,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }

        if (values.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            // Render values as removable chips, wrapping onto multiple lines.
            ChipFlow(values = values, onRemove = onRemove)
        }
    }
}

/**
 * Simple wrapping layout of removable chips without depending on experimental FlowRow:
 * groups chips two-per-row, which is plenty for short identifier codes.
 */
@Composable
private fun ChipFlow(values: List<String>, onRemove: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value ->
            AssistChip(
                onClick = { onRemove(value) },
                label = {
                    Text(
                        text = value,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove $value",
                        modifier = Modifier.width(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}
