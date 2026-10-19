@file:OptIn(ExperimentalMaterial3Api::class)

package com.heping.erpnative.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * 通用扫码框（CameraX + ML Kit，离线可用）。
 *
 * 支持两用：
 *  - 扫商品条码（EAN-13/UPC-A）→ 开单页快速加商品
 *  - 扫纸质单据上的条码 → 订单号检索
 *
 * 连续扫同一码会重复触发，这里用「同一码1.2 秒冷却」防抖。
 */
@Composable
fun ScannerDialog(
    title: String = "扫码",
    hint: String = "把条码对准取景框",
    onResult: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var hasCam by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    ) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCam = granted }

    LaunchedEffect(Unit) {
        if (!hasCam) launcher.launch(Manifest.permission.CAMERA)
    }

    // 对外只允许触发一次回调
    var done by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!hasCam) {
                    Text("需要相机权限才能扫码，请在系统设置里允许「相机」", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text("重新授权")
                    }
                } else {
                    CameraPreview(
                        onBarcode = { raw, fmt ->
                            if (!done) {
                                done = true
                                onResult(raw, fmt)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(
                        "支持商品条码（EAN-13/UPC）及纸质单据上的条码",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** CameraX 取景 + ML Kit 分析 */
@Composable
private fun CameraPreview(onBarcode: (String, Int) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX
                )
                .build()
        )
    }

    // 同一码冷却，避免连扫重复触发
    var lastRaw by remember { mutableStateOf("") }
    var lastAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            try { scanner.close() } catch (_: Exception) {}
            executor.shutdown()
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color.Black, RoundedCornerShape(10.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val pv = PreviewView(c)
                pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                val providerFuture = ProcessCameraProvider.getInstance(c)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build()
                        // ⚠️ Preview.surfaceProvider 只有 setter，没有 getter，
                        //    Kotlin 合成属性用不了（Unresolved reference），必须调 setter 方法。
                        preview.setSurfaceProvider(pv.surfaceProvider)
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                            val media = proxy.image
                            if (media == null) { proxy.close(); return@setAnalyzer }
                            try {
                                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                    .addOnSuccessListener { list ->
                                        val b = list.firstOrNull() ?: return@addOnSuccessListener
                                        val raw = b.rawValue?.trim()
                                        if (raw.isNullOrEmpty()) return@addOnSuccessListener
                                        val now = System.currentTimeMillis()
                                        if (raw == lastRaw && now - lastAt < 1200) return@addOnSuccessListener
                                        lastRaw = raw; lastAt = now
                                        onBarcode(raw, b.format)
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } catch (_: Exception) {
                                proxy.close()
                            }
                        }
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        camera?.cameraControl?.enableTorch(torch)
                    } catch (_: Exception) {
                        // 无后置摄像头等异常，界面保持黑框 + 提示
                    }
                }, ContextCompat.getMainExecutor(c))
                pv
            }
        )
        // 取景框
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.78f)
                .height(96.dp)
                .border(2.dp, Color(0xFF1AAD19), RoundedCornerShape(6.dp))
        )
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            IconButton(onClick = {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
            }) {
                Icon(
                    if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "手电筒",
                    tint = Color.White
                )
            }
        }
    }
}

/** 供调用方判断是否有可用相机（无摄像头设备给出提示） */
fun hasCameraHardware(ctx: Context): Boolean =
    ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)