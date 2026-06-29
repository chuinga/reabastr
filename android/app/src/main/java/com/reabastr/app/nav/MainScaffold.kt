package com.reabastr.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.reabastr.app.R
import com.reabastr.app.home.HomePage
import com.reabastr.app.home.HomeViewModel
import com.reabastr.app.scanner.QuickCreateBottomSheet
import com.reabastr.app.scanner.QuickCreateViewModel
import com.reabastr.app.scanner.ScannerOverlay
import com.reabastr.app.scanner.ScannerService
import com.reabastr.app.settings.SettingsPage
import com.reabastr.app.settings.SettingsViewModel
import com.reabastr.app.setup.SetupPage
import com.reabastr.app.setup.SetupViewModel
import com.reabastr.app.shopping.ShoppingListPage
import com.reabastr.app.shopping.ShoppingListViewModel

/**
 * The four primary destinations, each reachable from the bottom navigation bar.
 */
private enum class TopDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    Home("home", R.string.nav_home, Icons.Filled.Inventory2),
    Shopping("shopping", R.string.nav_shopping, Icons.AutoMirrored.Filled.List),
    Setup("setup", R.string.nav_setup, Icons.Filled.Tune),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings)
}

/**
 * Root of the authenticated experience. Hosts the four pages behind a persistent
 * bottom navigation bar so the user can always move between them (and "go back").
 */
@Composable
fun MainScaffold(
    householdId: String,
    scannerService: ScannerService,
    onHouseholdLeft: () -> Unit,
    onSignedOut: () -> Unit
) {
    val navController = rememberNavController()
    val destinations = TopDestination.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                destinations.forEach { dest ->
                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(TopDestination.Home.route) {
                val vm: HomeViewModel = hiltViewModel()
                LaunchedEffect(householdId) { vm.setHouseholdId(householdId) }
                HomeRoute(viewModel = vm, householdId = householdId, scannerService = scannerService)
            }
            composable(TopDestination.Shopping.route) {
                val vm: ShoppingListViewModel = hiltViewModel()
                LaunchedEffect(householdId) { vm.setHouseholdId(householdId) }
                ShoppingRoute(viewModel = vm, scannerService = scannerService)
            }
            composable(TopDestination.Setup.route) {
                val vm: SetupViewModel = hiltViewModel()
                LaunchedEffect(householdId) { vm.setHouseholdId(householdId) }
                SetupPage(viewModel = vm)
            }
            composable(TopDestination.Settings.route) {
                val vm: SettingsViewModel = hiltViewModel()
                SettingsPage(
                    viewModel = vm,
                    onHouseholdLeft = onHouseholdLeft,
                    onSignedOut = onSignedOut
                )
            }
        }
    }
}

/**
 * Home page wrapped with the shared scanner overlay and quick-create sheet.
 * Handles both directions of stock adjustment and manual product entry.
 */
@Composable
private fun HomeRoute(
    viewModel: HomeViewModel,
    householdId: String,
    scannerService: ScannerService
) {
    var isScannerVisible by remember { mutableStateOf(false) }
    var quickCreateEan by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        HomePage(
            viewModel = viewModel,
            onNavigateToQuickCreate = { ean -> quickCreateEan = ean },
            onOpenScanner = { isScannerVisible = true },
            onAddManually = { quickCreateEan = "" }
        )

        ScannerOverlay(
            isVisible = isScannerVisible,
            scannerService = scannerService,
            onBarcodeScanned = { ean ->
                isScannerVisible = false
                scannerService.stopScanning()
                viewModel.onScanResult(ean)
            },
            onDismiss = { isScannerVisible = false }
        )
    }

    // Quick-create sheet: opened either from an unknown scan (pre-filled EAN)
    // or from the manual "Add product" action (empty EAN, user can type one).
    quickCreateEan?.let { ean ->
        val quickCreateViewModel: QuickCreateViewModel = hiltViewModel()
        LaunchedEffect(ean) { quickCreateViewModel.initialize(ean, householdId) }
        QuickCreateBottomSheet(
            viewModel = quickCreateViewModel,
            onDismiss = { quickCreateEan = null },
            onProductCreated = { quickCreateEan = null }
        )
    }
}

/**
 * Shopping list page wrapped with the shared scanner overlay (restock on scan).
 */
@Composable
private fun ShoppingRoute(
    viewModel: ShoppingListViewModel,
    scannerService: ScannerService
) {
    var isScannerVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ShoppingListPage(
            viewModel = viewModel,
            onOpenScanner = { isScannerVisible = true }
        )

        ScannerOverlay(
            isVisible = isScannerVisible,
            scannerService = scannerService,
            onBarcodeScanned = { ean ->
                isScannerVisible = false
                scannerService.stopScanning()
                viewModel.onScanResult(ean)
            },
            onDismiss = { isScannerVisible = false }
        )
    }
}
