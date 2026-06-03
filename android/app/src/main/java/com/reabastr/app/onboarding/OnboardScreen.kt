package com.reabastr.app.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reabastr.app.R

@Composable
fun OnboardScreen(
    viewModel: OnboardViewModel
) {
    val onboardState by viewModel.onboardState.collectAsStateWithLifecycle()
    val joinUiState by viewModel.joinUiState.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()

    when (onboardState) {
        is OnboardState.Checking -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is OnboardState.NeedsHousehold -> {
            OnboardChoiceContent(
                joinUiState = joinUiState,
                isCreating = isCreating,
                onCreateHousehold = viewModel::createHousehold,
                onCodeChanged = viewModel::onCodeChanged,
                onJoinHousehold = viewModel::joinHousehold
            )
        }

        is OnboardState.Error -> {
            ErrorContent(
                message = (onboardState as OnboardState.Error).message,
                onRetry = viewModel::checkHousehold
            )
        }

        is OnboardState.HasHousehold -> {
            // This state is handled by the parent — should not render here
        }
    }
}

@Composable
private fun OnboardChoiceContent(
    joinUiState: JoinUiState,
    isCreating: Boolean,
    onCreateHousehold: () -> Unit,
    onCodeChanged: (String) -> Unit,
    onJoinHousehold: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Home,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboard_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboard_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Create household button
        Button(
            onClick = onCreateHousehold,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isCreating && !joinUiState.isLoading
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(R.string.onboard_create_household))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider with "or"
        HorizontalDivider(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboard_or),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // Join with code section
        Icon(
            imageVector = Icons.Filled.Group,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboard_join_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = joinUiState.code,
            onValueChange = onCodeChanged,
            label = { Text(stringResource(R.string.onboard_share_code_label)) },
            placeholder = { Text(stringResource(R.string.onboard_share_code_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = joinUiState.errorMessage != null,
            supportingText = if (joinUiState.errorMessage != null) {
                { Text(joinUiState.errorMessage) }
            } else null,
            enabled = !joinUiState.isLoading && !isCreating
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onJoinHousehold,
            modifier = Modifier.fillMaxWidth(),
            enabled = joinUiState.code.isNotBlank() && !joinUiState.isLoading && !isCreating
        ) {
            if (joinUiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Group,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(R.string.onboard_join_button))
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboard_error_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.onboard_retry))
        }
    }
}
