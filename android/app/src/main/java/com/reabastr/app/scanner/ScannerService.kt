package com.reabastr.app.scanner

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result emitted by ScannerService when a barcode is decoded or the scan times out.
 */
sealed interface ScanResult {
    data class Success(val barcode: String, val format: BarcodeFormat) : ScanResult
    data object Timeout : ScanResult
}

enum class BarcodeFormat {
    EAN_13,
    EAN_8
}

/**
 * On-device barcode scanner using ML Kit. Supports EAN-13 and EAN-8 formats only.
 * No network calls are made — all decoding is on-device.
 */
@Singleton
class ScannerService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
        .build()

    private val barcodeScanner = BarcodeScanning.getClient(scannerOptions)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _scanResults = Channel<ScanResult>(Channel.BUFFERED)
    val scanResults: Flow<ScanResult> = _scanResults.receiveAsFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var isScanning = false

    /**
     * Starts the camera preview and barcode analysis bound to the given lifecycle.
     * The first decoded EAN-13/EAN-8 barcode emits a [ScanResult.Success].
     */
    fun startScanning(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        if (isScanning) return
        isScanning = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (!isScanning) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )

                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    val validBarcode = barcodes.firstOrNull { barcode ->
                                        barcode.format == Barcode.FORMAT_EAN_13 ||
                                            barcode.format == Barcode.FORMAT_EAN_8
                                    }

                                    if (validBarcode != null && isScanning) {
                                        isScanning = false
                                        val format = when (validBarcode.format) {
                                            Barcode.FORMAT_EAN_13 -> BarcodeFormat.EAN_13
                                            else -> BarcodeFormat.EAN_8
                                        }
                                        _scanResults.trySend(
                                            ScanResult.Success(
                                                barcode = validBarcode.rawValue ?: "",
                                                format = format
                                            )
                                        )
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        }, context.mainExecutor)
    }

    /**
     * Stops camera scanning and releases camera resources.
     */
    fun stopScanning() {
        isScanning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    /**
     * Emits a timeout result. Called by the overlay when the 10s timer expires.
     */
    fun emitTimeout() {
        if (isScanning) {
            isScanning = false
            _scanResults.trySend(ScanResult.Timeout)
        }
    }
}
