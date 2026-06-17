package com.openwarden.parent.android.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.openwarden.parent.pairing.PairingParseResult
import com.openwarden.parent.pairing.PairingPayloadParser
import com.openwarden.parent.state.AppState
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "PairScreen"

/**
 * Camera QR-scan screen for pairing the parent app with a child device.
 *
 * Flow:
 * 1. Request camera permission if not yet granted.
 * 2. Show a live camera preview via CameraX + PreviewView.
 * 3. Attach an [ImageAnalysis] use-case that feeds frames to ML Kit barcode
 *    scanner; on each detected QR value, call [PairingPayloadParser.parse].
 * 4a. [PairingParseResult.Success] → call [AppState.setPeer] to persist the
 *     pinned peer, then invoke [onPaired].
 * 4b. [PairingParseResult.Failure] → show a Snackbar; camera keeps scanning.
 *
 * The parse + persist logic is entirely in the shared module; this screen is a
 * thin wiring layer. No crypto, no key generation here (PROTOCOL.md §7 scope note).
 *
 * @param appState the shared app state; [AppState.setPeer] is called on success.
 * @param onPaired callback invoked once a peer has been successfully pinned.
 */
@Composable
fun PairScreen(
    appState: AppState,
    onPaired: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Guard: don't scan again after a successful pair in this composition.
    var paired by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasCameraPermission) {
                QrCameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onQrDetected = { rawValue ->
                        if (paired) return@QrCameraPreview
                        when (val result = PairingPayloadParser.parse(rawValue)) {
                            is PairingParseResult.Success -> {
                                paired = true
                                appState.setPeer(result.peer)
                                onPaired()
                            }
                            is PairingParseResult.Failure -> {
                                Log.w(TAG, "QR rejected: ${result.reason}")
                                scope.launch {
                                    snackbarHostState.showSnackbar("Invalid QR: ${result.reason}")
                                }
                            }
                        }
                    },
                )
                Text(
                    text = "Point at the child device's pairing QR",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Camera permission is required to scan the child pairing QR.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Composable wrapper around a [PreviewView] + ML Kit barcode analyser.
 *
 * Separated from [PairScreen] so the camera lifecycle binding is testable
 * in isolation. The analyser fires [onQrDetected] once per unique value
 * detected in a frame; deduplication (to avoid repeated callbacks for the
 * same QR while the code is held in frame) is handled by the caller via the
 * [paired] guard in [PairScreen].
 */
@Composable
private fun QrCameraPreview(
    modifier: Modifier = Modifier,
    onQrDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyserExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            analyserExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also { p ->
                        p.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analyser = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analyser.setAnalyzer(analyserExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    barcodes
                                        .filter { it.format == Barcode.FORMAT_QR_CODE }
                                        .mapNotNull { it.rawValue }
                                        .forEach { onQrDetected(it) }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }

                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analyser,
                        )
                    }.onFailure { e ->
                        Log.e(TAG, "CameraX bind failed", e)
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
        modifier = modifier,
    )
}
