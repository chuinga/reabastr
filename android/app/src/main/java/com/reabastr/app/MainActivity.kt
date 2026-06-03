package com.reabastr.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reabastr.app.auth.AuthState
import com.reabastr.app.auth.AuthViewModel
import com.reabastr.app.auth.SignInScreen
import com.reabastr.app.ui.theme.ReabastrTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReabastrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReabastrApp()
                }
            }
        }

        // Handle OAuth callback if launched via deep link
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "reabastr" && uri.host == "callback") {
            val code = uri.getQueryParameter("code")
            if (code != null) {
                // Store code for the ViewModel to pick up
                OAuthCallbackHolder.pendingCode = code
            }
        }
    }
}

/**
 * Simple holder for the OAuth authorization code between Activity and ViewModel.
 * This is needed because the callback arrives via intent before Compose is aware.
 */
object OAuthCallbackHolder {
    var pendingCode: String? = null
}

@Composable
fun ReabastrApp() {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    // Check for pending OAuth callback
    val pendingCode = OAuthCallbackHolder.pendingCode
    if (pendingCode != null) {
        OAuthCallbackHolder.pendingCode = null
        authViewModel.handleOAuthCallback(pendingCode)
    }

    when (authState) {
        is AuthState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        is AuthState.Unauthenticated -> {
            SignInScreen(viewModel = authViewModel)
        }
        is AuthState.Authenticated -> {
            // Main app content — will be wired with navigation in later tasks
            AuthenticatedPlaceholder(authState as AuthState.Authenticated)
        }
    }
}

@Composable
private fun AuthenticatedPlaceholder(state: AuthState.Authenticated) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = "Welcome, ${state.displayName ?: state.email}"
        )
    }
}
