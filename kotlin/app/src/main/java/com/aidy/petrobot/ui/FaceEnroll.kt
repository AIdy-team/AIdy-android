package com.aidy.petrobot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aidy.petrobot.data.FaceLink
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 送る画像: 顔のまわり (顔の幅の 2.2 倍) を切り出し、長い辺 480 画素の JPEG にする。本体がこの中で顔を見つけ直す。 */
private const val CropScale = 2.2f
private const val SendMaxPx = 480
private const val JpegQuality = 90
/** 1 枚送ってから次を送るまで (本体の enroll.py と同じ 0.25 秒)。 */
private const val TakeGapMs = 250L

/**
 * スマホのカメラ (前のカメラ) で顔を登録するフルスクリーンの画面。本体の画面での登録 (enroll.py) と同じ手順・同じ見た目:
 *   正面 → 横 → 反対の横 → 上 → 下 → もう一度正面 (各 3 枚・合わせて 18 枚)、60 秒で終わらなければやめる。
 * スマホは「顔が枠に入って、だいたいその向き」のときだけ 1 枚送る。使えるかどうかの最後の判断と、顔を数字にするのは本体
 * (本体と同じ顔の見つけ方・向きのそろえ方・数字の作り方になるので、本体のカメラで登録したときとの差が小さい)。
 */
@Composable
fun FaceEnrollDialog(name: String, yomi: String, profile: FaceLink.Profile, onDone: (FaceLink.Person?) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

        var session by remember { mutableStateOf<FaceLink.Session?>(null) }
        var stepKey by remember { mutableStateOf("front") }
        var got by remember { mutableStateOf(0) }
        var total by remember { mutableStateOf(0) }
        var hint by remember { mutableStateOf("本体とつないでいます…") }
        var okNow by remember { mutableStateOf(false) }
        var finished by remember { mutableStateOf<String?>(null) } // 終わったときの見出し
        var failed by remember { mutableStateOf(false) }
        var secLeft by remember { mutableStateOf(60) }
        // 解析のスレッドから見る値
        val busy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        val lastTake = remember { java.util.concurrent.atomic.AtomicLong(0L) }
        val side = remember { java.util.concurrent.atomic.AtomicInteger(0) } // 最初に向いた横 (+1 / -1)

        fun stop(title: String, fail: Boolean) {
            if (finished != null) return
            finished = title
            failed = fail
            okNow = false
        }

        // 本体に「始めます」
        LaunchedEffect(Unit) {
            try {
                val s = FaceLink.start(name, yomi, profile)
                session = s
                stepKey = s.steps.first().key
                secLeft = s.timeoutSec
                hint = s.steps.first().label
            } catch (e: Exception) {
                hint = e.message ?: "本体が登録を始められませんでした"
                stop("登録を始められませんでした", true)
            }
        }
        // 60 秒で終わらなければやめる
        LaunchedEffect(session) {
            val s = session ?: return@LaunchedEffect
            val until = System.currentTimeMillis() + s.timeoutSec * 1000L
            while (finished == null) {
                secLeft = max(0, ((until - System.currentTimeMillis()) / 1000).toInt())
                if (secLeft <= 0) {
                    FaceLink.cancel(s.sid)
                    stop("時間切れ", true)
                    hint = "もう一度ためしてね (明るい所で、枠に顔を入れてね)"
                    break
                }
                delay(250)
            }
        }

        fun send(bmp: Bitmap, s: FaceLink.Session, key: String, yaw: Float) {
            scope.launch {
                try {
                    val b64 = withContext(Dispatchers.Default) {
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, JpegQuality, out)
                        bmp.recycle()
                        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                    if (finished != null) return@launch
                    val r = FaceLink.frame(s.sid, key, b64)
                    total = r.total
                    if (r.ok) {
                        if (key == "side1" && side.get() == 0) side.set(if (yaw > 0) 1 else -1)
                    }
                    if (r.step != stepKey) { stepKey = r.step; got = 0 } else got = r.got
                    hint = if (r.ok) (s.steps.firstOrNull { it.key == stepKey }?.label ?: "") else r.reason.ifEmpty { "もう一度" }
                    when {
                        r.done -> {
                            stop("覚えたよ！", false)
                            val p = runCatching { FaceLink.finish(s.sid) }.getOrNull()
                            hint = "${name}さんの顔を覚えました (${r.total}枚)"
                            delay(1500)
                            onDone(p)
                        }
                        r.timeout -> { stop("時間切れ", true); hint = "もう一度ためしてね" }
                    }
                } catch (e: Exception) {
                    hint = e.message ?: "本体に送れませんでした"
                } finally {
                    lastTake.set(System.currentTimeMillis())
                    busy.set(false)
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                val previewView = remember {
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                }
                val executor = remember { Executors.newSingleThreadExecutor() }
                val detector = remember {
                    FaceDetection.getClient(
                        FaceDetectorOptions.Builder()
                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                            .setMinFaceSize(0.15f)
                            .build(),
                    )
                }
                DisposableEffect(Unit) { onDispose { executor.shutdown(); detector.close() } }
                DisposableEffect(Unit) {
                    val future = ProcessCameraProvider.getInstance(context)
                    var provider: ProcessCameraProvider? = null
                    future.addListener({
                        val p = future.get()
                        provider = p
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                    .build(),
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            val s = session
                            if (media == null || s == null || finished != null) { proxy.close(); return@setAnalyzer }
                            val rot = proxy.imageInfo.rotationDegrees
                            val image = InputImage.fromMediaImage(media, rot)
                            val w = if (rot % 180 == 0) proxy.width else proxy.height
                            val h = if (rot % 180 == 0) proxy.height else proxy.width
                            detector.process(image)
                                .addOnSuccessListener { faces ->
                                    val key = stepKey
                                    val (msg, good) = check(faces, w, h, key, side.get())
                                    okNow = good
                                    if (!good) { if (!busy.get()) hint = msg; return@addOnSuccessListener }
                                    hint = s.steps.firstOrNull { it.key == key }?.label ?: msg
                                    val now = System.currentTimeMillis()
                                    if (busy.get() || now - lastTake.get() < TakeGapMs) return@addOnSuccessListener
                                    busy.set(true)
                                    val crop = try { cropFace(proxy.toBitmap(), rot, faces[0]) } catch (e: Exception) { null }
                                    if (crop == null) { busy.set(false); return@addOnSuccessListener }
                                    send(crop, s, key, faces[0].headEulerAngleY)
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                        try {
                            p.unbindAll()
                            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                        } catch (e: Exception) {
                            hint = "カメラを開けませんでした"
                        }
                    }, ContextCompat.getMainExecutor(context))
                    onDispose { provider?.unbindAll() }
                }
                AndroidView({ previewView }, Modifier.fillMaxSize())
                // 顔の枠 (本体の画面と同じ楕円)。向きが合っていると緑
                val col = if (okNow) Color(0xFF5BE3A0) else Color.White
                Canvas(Modifier.fillMaxSize()) {
                    val ow = size.width * 0.62f
                    val oh = ow * 1.32f
                    drawRect(Color.Black.copy(alpha = 0.25f))
                    drawOval(col, Offset((size.width - ow) / 2f, size.height * 0.44f - oh / 2f), GSize(ow, oh), style = Stroke(5.dp.toPx()))
                }
            } else {
                Label("カメラの権限が必要です", 14f, Color.White, true, Modifier.align(Alignment.Center))
            }

            // 上: いまの指示と進み具合
            val steps = session?.steps ?: FaceLink.DefaultSteps
            val idx = steps.indexOfFirst { it.key == stepKey }.coerceAtLeast(0)
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(top = 40.dp, bottom = 14.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(finished ?: steps[idx].label, 20f, Color.White, true, align = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    steps.forEachIndexed { i, _ ->
                        val on = i < idx || (finished != null && !failed)
                        Box(Modifier.size(10.dp).background(if (on) Color(0xFF5BE3A0) else if (i == idx) Color.White else Color(0xFF5A606A), RoundedCornerShape(5.dp)))
                    }
                    Spacer(Modifier.size(8.dp))
                    Label("${total} / ${steps.size * (session?.perStep ?: 3)}枚 ・ 残り${secLeft}秒", 12f, Color.White.copy(alpha = 0.8f))
                }
            }
            // 下: ヒントとボタン
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Label(hint, 15f, if (failed) Color(0xFFF0BE5A) else Color.White, true, align = TextAlign.Center)
                Label("${name}さん ・ 顔の写真は本体で数字にして、写真は残しません", 11f, Color.White.copy(alpha = 0.6f), align = TextAlign.Center)
                SmallButton(if (finished != null) "閉じる" else "やめる", Color.White.copy(alpha = 0.2f), Color.White) {
                    val s = session
                    if (finished == null && s != null) scope.launch { FaceLink.cancel(s.sid) }
                    onDismiss()
                }
            }
        }
    }
}

/**
 * スマホ側の下見: 顔が 1 つ・枠の大きさ・真ん中・だいたいの向き。(ヒント, 送ってよいか)。
 * 向きはゆるめに見る (最後の判断は本体が 5 点の位置で行う。enroll.py の _pose_ok と同じ)。
 */
private fun check(faces: List<Face>, w: Int, h: Int, key: String, side: Int): Pair<String, Boolean> {
    if (faces.isEmpty()) return "顔が見えないよ" to false
    val f = faces.maxBy { it.boundingBox.width() }
    if (faces.size > 1 && faces.any { it !== f && it.boundingBox.width() > f.boundingBox.width() * 0.6f }) return "ひとりだけ映ってね" to false
    val size = f.boundingBox.width().toFloat() / w
    if (size < 0.22f) return "もう少し近づいてね" to false
    if (size > 0.75f) return "少し離れてね" to false
    val cx = f.boundingBox.exactCenterX() / w
    val cy = f.boundingBox.exactCenterY() / h
    if (abs(cx - 0.5f) > 0.18f || abs(cy - 0.45f) > 0.2f) return "枠の真ん中に顔を入れてね" to false
    val yaw = f.headEulerAngleY   // 左右 (度)
    val pitch = f.headEulerAngleX // 上下 (度・上が +)
    val ok = when (key) {
        "front", "front2" -> abs(yaw) < 10f && abs(pitch) < 12f
        "side1" -> abs(yaw) in 10f..40f
        "side2" -> abs(yaw) in 10f..40f && (side == 0 || yaw * side < 0)
        "up" -> pitch > 7f && abs(yaw) < 15f
        "down" -> pitch < -7f && abs(yaw) < 15f
        else -> true
    }
    val tip = when (key) {
        "front", "front2" -> "まっすぐ見てね"
        "side1" -> "ゆっくり横を向いてね"
        "side2" -> "反対の横を向いてね"
        "up" -> "少し上を向いてね"
        "down" -> "少し下を向いてね"
        else -> ""
    }
    return tip to ok
}

/** カメラの画像を正しい向きにして、顔のまわりを切り出し、長い辺 SendMaxPx に縮める (左右反転はしない)。 */
private fun cropFace(src: Bitmap, rot: Int, f: Face): Bitmap? {
    val up = if (rot != 0) {
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    } else src
    val b = f.boundingBox
    val side = max(b.width(), b.height()) * CropScale
    val cx = b.exactCenterX()
    val cy = b.exactCenterY() + b.height() * 0.1f
    val l = max(0f, cx - side / 2f).toInt()
    val t = max(0f, cy - side / 2f).toInt()
    val r = min(up.width.toFloat(), cx + side / 2f).toInt()
    val btm = min(up.height.toFloat(), cy + side / 2f).toInt()
    if (r - l < 64 || btm - t < 64) { up.recycle(); return null }
    val crop = Bitmap.createBitmap(up, l, t, r - l, btm - t)
    val scale = min(1f, SendMaxPx.toFloat() / max(crop.width, crop.height))
    val out = if (scale < 1f) Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true) else crop
    if (crop !== out) crop.recycle()
    if (up !== crop && up !== out) up.recycle()
    return out
}
