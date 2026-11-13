package com.aidy.petrobot.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aidy.petrobot.data.AidyDeviceLink
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * 本体の QR ("http://host:port/?t=token"、仕様書 AIdy_アプリ連携_仕様.md 参照) をカメラで読み取る
 * フルスクリーンのダイアログ。読み取れたら onResult(住所・合言葉・Bluetooth名) を呼んで自動で閉じる。
 * 端末内の ML Kit だけで処理する (通信なし)。
 */
@Composable
fun QrScannerDialog(onResult: (AidyDeviceLink.QrInfo) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

        var message by remember { mutableStateOf("本体の QR を枠の中に写してね") }
        var handled by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                val previewView = remember {
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                }
                val executor = remember { Executors.newSingleThreadExecutor() }
                val scanner = remember {
                    BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                }
                DisposableEffect(Unit) {
                    onDispose {
                        executor.shutdown()
                        scanner.close()
                    }
                }
                DisposableEffect(Unit) {
                    val future = ProcessCameraProvider.getInstance(context)
                    var provider: ProcessCameraProvider? = null
                    future.addListener({
                        val p = future.get()
                        provider = p
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null || handled) {
                                proxy.close()
                            } else {
                                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { codes ->
                                        if (handled) return@addOnSuccessListener
                                        val raw = codes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                                        val parsed = AidyDeviceLink.parseQr(raw)
                                        if (parsed != null) {
                                            handled = true
                                            onResult(parsed)
                                        } else {
                                            message = "この QR コードは Aidy 本体のものじゃないみたい"
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            }
                        }
                        try {
                            p.unbindAll()
                            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (e: Exception) {
                            message = "カメラを開けませんでした"
                        }
                    }, ContextCompat.getMainExecutor(context))
                    onDispose { provider?.unbindAll() }
                }
                AndroidView({ previewView }, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(240.dp)
                        .border(3.dp, Color.White, RoundedCornerShape(16.dp)),
                )
            } else {
                Label("カメラの権限が必要です", 14f, Color.White, true, Modifier.align(Alignment.Center))
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Label(message, 13f, Color.White, true, align = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                SmallButton("閉じる", Color.White.copy(alpha = 0.2f), Color.White, onDismiss)
            }
        }
    }
}
