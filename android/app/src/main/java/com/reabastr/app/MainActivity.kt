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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reabastr.app.auth.AuthState
import com.reabastr.app.auth.AuthViewModel
import com.reabastr.app.auth.SignInScreen
import com.reabastr.app.auth.SignUpScreen
import com.reabastr.app.auth.WelcomeScreen
import com.reabastr.app.nav.MainScaffold
import com.reabastr.app.onboarding.OnboardScreen
import com.reabastr.app.onboarding.OnboardState
import com.reabastr.app.onboarding.OnboardViewModel
import com.reabastr.app.scanner.ScannerService
import com.reabastr.app.ui.theme.ReabastrTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var scannerService: ScannerService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReabastrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReabastrApp(scannerService = scannerService)
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
            val error = uri.getQueryParameter("error")
            when {
                code != null -> OAuthCallbackHolder.pendingCode = code
                error != null -> {
                    val description = uri.getQueryParameter("error_description")
                    OAuthCallbackHolder.pendingError = description ?: error
                }
            }
        }
    }
}

/**
 * Holder for the OAuth result between Activity and Compose. The fields are
 * Compose state so that setting them from [MainActivity.onNewIntent] triggers
 * a recomposition, allowing the app to pick up the authorization code (or error)
 * even though the deep link arrives outside the Compose lifecycle.
 */
object OAuthCallbackHolder {
    var pendingCode by mutableStateOf<String?>(null)
    var pendingError by mutableStateOf<String?>(null)
}

@Composable
fun ReabastrApp(scannerService: ScannerService) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    // Consume a pending OAuth callback (code or error) reactively. Setting the
    // holder fields from onNewIntent triggers recomposition, and this effect
    // runs the token exchange exactly once per delivered code.
    val pendingCode = OAuthCallbackHolder.pendingCode
    LaunchedEffect(pendingCode) {
        if (pendingCode != null) {
            OAuthCallbackHolder.pendingCode = null
            authViewModel.handleOAuthCallback(pendingCode)
        }
    }

    val pendingError = OAuthCallbackHolder.pendingError
    LaunchedEffect(pendingError) {
        if (pendingError != null) {
            OAuthCallbackHolder.pendingError = null
            authViewModel.onOAuthError(pendingError)
        }
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
            AuthFlow(authViewModel = authViewModel)
        }
        is AuthState.Authenticated -> {
            AuthenticatedContent(scannerService = scannerService)
        }
    }
}

/**
 * The signed-out experience: a welcome screen branching to log-in or sign-up,
 * with back navigation between them.
 */
private enum class AuthScreen { Welcome, LogIn, SignUp }

@Composable
private fun AuthFlow(authViewModel: AuthViewModel) {
    var screen by remember { mutableStateOf(AuthScreen.Welcome) }

    when (screen) {
        AuthScreen.Welcome -> WelcomeScreen(
            onLogIn = { screen = AuthScreen.LogIn },
            onSignUp = {
                authViewModel.resetSignUp()
                screen = AuthScreen.SignUp
            }
        )
        AuthScreen.LogIn -> SignInScreen(
            viewModel = authViewModel,
            onBack = { screen = AuthScreen.Welcome }
        )
        AuthScreen.SignUp -> SignUpScreen(
            viewModel = authViewModel,
            onBack = { screen = AuthScreen.Welcome }
        )
    }
}

/**
 * Content shown after authentication. Checks household membership
 * and shows onboarding if needed, otherwise shows the main app.
 */
@Composable
private fun AuthenticatedContent(scannerService: ScannerService) {
    val onboardViewModel: OnboardViewModel = hiltViewModel()
    val onboardState by onboardViewModel.onboardState.collectAsStateWithLifecycle()

    when (onboardState) {
        is OnboardState.Checking -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        is OnboardState.NeedsHousehold, is OnboardState.Error -> {
            OnboardScreen(viewModel = onboardViewModel)
        }
        is OnboardState.HasHousehold -> {
            val householdId = (onboardState as OnboardState.HasHousehold).householdId
            MainScaffold(
                householdId = householdId,
                scannerService = scannerService,
                onHouseholdLeft = { onboardViewModel.checkHousehold() },
                onSignedOut = { /* authState flips to Unauthenticated; UI reacts */ }
            )
        }
    }
}
