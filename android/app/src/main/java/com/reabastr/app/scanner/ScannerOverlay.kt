package com.reabastr.app.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.reabastr.app.R
import kotlinx.coroutines.delay

/**
 * Scanner state representing the current state of the scanning overlay.
 */
sealed interface ScannerState {
    data object Scanning : ScannerState
    data object TimedOut : ScannerState
    data object PermissionDenied : ScannerState
}

private const val SCAN_TIMEOUT_MS = 10_000L

/**
 * Reusable scanner overlay composable. Shared between Home and Shopping List pages.
 *
 * Displays a camera preview with a scanning target area, handles the 10s timeout,
 * and provides retry/cancel options.
 *
 * @param isVisible Whether the scanner overlay is currently shown.
 * @param scannerService The injected ScannerService instance.
 * @param onBarcodeScanned Callback invoked when a barcode is successfully scanned.
 * @param onDismiss Callback invoked when the user cancels or closes the scanner.
 */
@Composable
fun ScannerOverlay(
    isVisible: Boolean,
    scannerService: ScannerService,
    onBarcodeScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var scannerState by remember { mutableStateOf<ScannerState>(ScannerState.Scanning) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            scannerState = ScannerState.PermissionDenied
        }
    }

    // Request permission if not already granted
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Collect scan results
    LaunchedEffect(Unit) {
        scannerService.scanResults.collect { result ->
            when (result) {
                is ScanResult.Success -> {
                    onBarcodeScanned(result.barcode)
                }
                is ScanResult.Timeout -> {
                    scannerState = ScannerState.TimedOut
                }
            }
        }
    }

    // 10s timeout countdown
    LaunchedEffect(scannerState) {
        if (scannerState == ScannerState.Scanning && hasPermission) {
            delay(SCAN_TIMEOUT_MS)
            if (scannerState == ScannerState.Scanning) {
                scannerService.emitTimeout()
            }
        }
    }

    // Cleanup on dismiss
    DisposableEffect(Unit) {
        onDispose {
            scannerService.stopScanning()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !hasPermission && scannerState == ScannerState.PermissionDenied -> {
                    PermissionDeniedContent(
                        onRetry = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onCancel = onDismiss
                    )
                }
                scannerState == ScannerState.TimedOut -> {
                    TimeoutContent(
                        onRetry = {
                            scannerState = ScannerState.Scanning
                        },
                        onCancel = onDismiss
                    )
                }
                hasPermission && scannerState == ScannerState.Scanning -> {
                    CameraPreviewContent(
                        scannerService = scannerService,
                        lifecycleOwner = lifecycleOwner
                    )
                }
            }

            // Close button — always visible
            IconButton(
                onClick = {
                    scannerService.stopScanning()
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.scanner_close),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewContent(
    scannerService: ScannerService,
    lifecycleOwner: LifecycleOwner
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Start scanning when preview is ready
    LaunchedEffect(Unit) {
        scannerService.startScanning(lifecycleOwner, previewView)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Scanning overlay with cutout
        ScanningViewfinder()

        // Instruction text
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.scanner_instruction),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        }
    }
}

/**
 * Draws a semi-transparent overlay with a transparent rounded-rect cutout
 * in the center, representing the scanning target area.
 */
@Composable
private fun ScanningViewfinder() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val overlayColor = Color.Black.copy(alpha = 0.5f)
        val cutoutWidth = size.width * 0.75f
        val cutoutHeight = size.height * 0.25f
        val cutoutLeft = (size.width - cutoutWidth) / 2f
        val cutoutTop = (size.height - cutoutHeight) / 2f

        val cutoutPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(
                        offset = Offset(cutoutLeft, cutoutTop),
                        size = Size(cutoutWidth, cutoutHeight)
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            )
        }

        // Draw the overlay, clipping out the cutout area
        clipPath(cutoutPath, clipOp = ClipOp.Difference) {
            drawRect(color = overlayColor)
        }

        // Draw corner indicators on the cutout
        val cornerLength = 32.dp.toPx()
        val cornerStroke = 3.dp.toPx()
        val cornerColor = Color.White

        // Top-left corner
        drawLine(cornerColor, Offset(cutoutLeft, cutoutTop + cornerLength), Offset(cutoutLeft, cutoutTop), cornerStroke)
        drawLine(cornerColor, Offset(cutoutLeft, cutoutTop), Offset(cutoutLeft + cornerLength, cutoutTop), cornerStroke)

        // Top-right corner
        drawLine(cornerColor, Offset(cutoutLeft + cutoutWidth - cornerLength, cutoutTop), Offset(cutoutLeft + cutoutWidth, cutoutTop), cornerStroke)
        drawLine(cornerColor, Offset(cutoutLeft + cutoutWidth, cutoutTop), Offset(cutoutLeft + cutoutWidth, cutoutTop + cornerLength), cornerStroke)

        // Bottom-left corner
        drawLine(cornerColor, Offset(cutoutLeft, cutoutTop + cutoutHeight - cornerLength), Offset(cutoutLeft, cutoutTop + cutoutHeight), cornerStroke)
        drawLine(cornerColor, Offset(cutoutLeft, cutoutTop + cutoutHeight), Offset(cutoutLeft + cornerLength, cutoutTop + cutoutHeight), cornerStroke)

        // Bottom-right corner
        drawLine(cornerColor, Offset(cutoutLeft + cutoutWidth - cornerLength, cutoutTop + cutoutHeight), Offset(cutoutLeft + cutoutWidth, cutoutTop + cutoutHeight), cornerStroke)
        drawLine(cornerColor, Offset(cutoutLeft + cutoutWidth, cutoutTop + cutoutHeight - cornerLength), Offset(cutoutLeft + cutoutWidth, cutoutTop + cutoutHeight), cornerStroke)
    }
}

@Composable
private fun TimeoutContent(
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.scanner_timeout_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scanner_timeout_message),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.scanner_cancel))
                }
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.scanner_retry))
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedContent(
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.scanner_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scanner_permission_message),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.scanner_cancel))
                }
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.scanner_grant_permission))
                }
            }
        }
    }
}
