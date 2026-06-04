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
import com.reabastr.app.home.HomePage
import com.reabastr.app.home.HomeViewModel
import com.reabastr.app.onboarding.OnboardScreen
import com.reabastr.app.onboarding.OnboardState
import com.reabastr.app.onboarding.OnboardViewModel
import com.reabastr.app.scanner.ScannerOverlay
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
fun ReabastrApp(scannerService: ScannerService) {
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
            AuthenticatedContent(scannerService = scannerService)
        }
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
            MainAppContent(householdId = householdId, scannerService = scannerService)
        }
    }
}

/**
 * Main app content after authentication and onboarding are complete.
 * Currently shows the Home page (Take from Stock) with scanner overlay support.
 */
@Composable
private fun MainAppContent(householdId: String, scannerService: ScannerService) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    var isScannerVisible by remember { mutableStateOf(false) }

    LaunchedEffect(householdId) {
        homeViewModel.setHouseholdId(householdId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HomePage(
            viewModel = homeViewModel,
            onNavigateToQuickCreate = { /* Will be wired in task 15.2 */ },
            onOpenScanner = { isScannerVisible = true }
        )

        // Scanner overlay — shared composable, shown as full-screen overlay
        ScannerOverlay(
            isVisible = isScannerVisible,
            scannerService = scannerService,
            onBarcodeScanned = { ean ->
                isScannerVisible = false
                scannerService.stopScanning()
                homeViewModel.onScanResult(ean)
            },
            onDismiss = {
                isScannerVisible = false
            }
        )
    }
}
