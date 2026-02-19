package com.tudorc.mediabus.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.tudorc.mediabus.util.MediaBusHaptics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScannerDialog(
    onDismiss: () -> Unit,
    onPayloadScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    BackHandler {
        MediaBusHaptics.performTap(context)
        onDismiss()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanned = remember { AtomicBoolean(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val setupError = remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)

        val setupRunnable = Runnable {
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (scanned.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    try {
                        val lumaBytes = extractLumaPlane(imageProxy)
                        val source = PlanarYUVLuminanceSource(
                            lumaBytes,
                            imageProxy.width,
                            imageProxy.height,
                            0,
                            0,
                            imageProxy.width,
                            imageProxy.height,
                            false,
                        )
                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                        val result = runCatching { reader.decodeWithState(binaryBitmap) }
                            .getOrNull()
                        reader.reset()
                        val value = result?.text
                        if (!value.isNullOrBlank() && scanned.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(context).execute {
                                MediaBusHaptics.performRelease(context)
                                onPayloadScanned(value)
                            }
                        }
                    } catch (_: NotFoundException) {
                        // No QR in this frame.
                    } catch (_: Throwable) {
                        // Ignore transient decode failures.
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure { throwable ->
                setupError.value = throwable.message ?: "Camera unavailable"
            }
        }

        providerFuture.addListener(setupRunnable, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            runCatching { reader.reset() }
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Scan pairing QR",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = setupError.value ?: "Point camera to the client's pairing QR code",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
        }
        Button(
            onClick = {
                MediaBusHaptics.performTap(context)
                onDismiss()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp),
        ) {
            Text("Cancel")
        }
    }
}

private fun extractLumaPlane(imageProxy: ImageProxy): ByteArray {
    val plane = imageProxy.planes.first()
    val width = imageProxy.width
    val height = imageProxy.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val source = plane.buffer
    val luma = ByteArray(width * height)

    if (pixelStride == 1 && rowStride == width) {
        source.rewind()
        source.get(luma, 0, luma.size)
        return luma
    }

    val rowBuffer = ByteArray(rowStride)
    for (row in 0 until height) {
        val rowOffset = row * rowStride
        source.position(rowOffset)
        source.get(rowBuffer, 0, rowStride)
        val outOffset = row * width
        var colIn = 0
        for (col in 0 until width) {
            luma[outOffset + col] = rowBuffer[colIn]
            colIn += pixelStride
        }
    }
    return luma
}
