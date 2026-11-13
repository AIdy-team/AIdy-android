package com.aidy.petrobot.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aidy.petrobot.data.AidyMarkers
import com.aidy.petrobot.data.MarkerStore
import com.aidy.petrobot.data.ModuleConfig
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.three.AidyCamera
import com.aidy.petrobot.three.AidyColors
import com.aidy.petrobot.three.Deg2Rad
import com.aidy.petrobot.three.Meshes
import com.aidy.petrobot.three.ModuleRig
import com.aidy.petrobot.three.PI
import com.aidy.petrobot.three.Pose
import com.aidy.petrobot.three.Q
import com.aidy.petrobot.three.Rad2Deg
import com.aidy.petrobot.three.V3
import com.aidy.petrobot.three.destroyTree
import com.aidy.petrobot.three.setLocal
import com.aidy.petrobot.ui.FrameClock
import com.aidy.petrobot.ui.Graphics
import com.aidy.petrobot.ui.IconId
import com.aidy.petrobot.ui.LocalGraphics
import com.aidy.petrobot.ui.Theme
import com.aidy.petrobot.ui.setupQuality
import com.google.android.filament.LightManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.sceneview.Scene
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

/** 本体は 150mm 角だが、実寸だと画面に対して大きすぎるので 1/2 で表示。 */
private const val ARModelSize = 0.075f
/** Web カメラの内部パラメータは取れないので、長辺の画角をこの値とみなす。 */
private const val WebcamHFov = 62f
/** Web カメラではこれだけ見えなければ消す (ワールド追跡がないため)。 */
private const val MarkerLostSec = 0.5f
/** Web カメラ: 机の面はカメラの 35cm 下とみなす (端末を机の上で構えたくらい)。 */
private const val TableDepth = 0.35f
/** ジャイロがないとき: カメラはこの角度だけ下を向いているとみなす。 */
private const val NoGyroTilt = 40f
private const val MaxPlaceDistance = 3f
private const val Step = 1f / 60f

/** カメラの起動状態。 */
enum class ARMode { None, Starting, AR, Webcam }

/** 表示方法。Choose = まだ選んでいない (モデルは出さない)。 */
enum class Placement { Choose, Marker, Free }

/** AR 画面上部のバッジ。 */
data class ARStatus(val icon: IconId, val color: Color, val size: Float, val text: String)

/**
 * AR 画面 (Unity 版 AidyARView)。ARCore が使える端末では床/机の平面に本体を配置し、
 * 使えない場合は「カメラ映像 (CameraX) の上に透明な 3D を重ねて、タップした位置を基準に配置」する方式で動作する。
 * 表示するモデルは ModuleConfig のモジュール付き (付け替えはタブの「画面上」で行う)。
 * はじめはモデルを出さず、表示方法 (Placement) を選んでもらう:
 *  ・マーカー: ArUco マーカー (AidyMarkers) が写ったら、その上にモデルを立たせる。「あなたのAidy」マーカーにはいまの着せ替え、
 *    名刺マーカーには標準構成の Aidy。ARCore ではマーカーの位置をワールドに固定し、マーカーが外れてもその場に残る。
 *  ・マーカーなし: 机や床の面をタップして置く。ARCore は見つけた平面、カメラ映像だけのときは
 *    ジャイロで端末の傾きを追い、カメラの TableDepth 下にあるとみなした水平面に置く。
 */
class ARController(private val g: Graphics) {
    private val gfx = g.gfx

    var mode by mutableStateOf(ARMode.None)
        private set
    var placement by mutableStateOf(Placement.Marker)
        private set
    /** カメラを使う (AR タブの「AR」を表示中)。 */
    var cameraWanted by mutableStateOf(false)
        private set
    var frontCamera by mutableStateOf(false)
        private set
    var status by mutableStateOf(ARStatus(IconId.HandPointer, Color.White, 12f, "カメラ越しに机や床をタップして配置"))
        private set
    /** AR タブが見えている (見えていないときはカメラを止める)。 */
    var pageVisible by mutableStateOf(false)

    var onPlaced: (String) -> Unit = {}
    var onHint: (String) -> Unit = {}
    var onPlacementChanged: (Placement) -> Unit = {}

    private var arCapable = true
    private var cameraCount = 2

    private val tracker = MarkerTracker()
    private val lastSeen = HashMap<Int, Float>()
    private var placedOnMarker = false
    private var placedYaw = 0f
    private var ringAngle = 0f
    private var accumulator = 0f
    private var draggingAR = false
    private var dragDistance = 0f

    /** マーカーの上に立つモデル (またはタップで置くモデル)。根元 = 足元、model = 足先が根元に付く高さ。 */
    private inner class ARModel(parent: Node, config: Map<String, ModuleKind>?, ringColor: Int?, bodyColor: com.aidy.petrobot.data.ColorPattern? = null) {
        val root: Node = gfx.empty(parent, "ARModel")
        val model: Node = gfx.empty(root, "Model")
        val rig = ModuleRig(gfx, model, ARModelSize, false, config, shadows = false, fixedColor = bodyColor)
        val ring: Node? = ringColor?.let { makeRing(root, it) }
        val live = config == null

        init {
            if (!rig.hasModel) {
                gfx.mesh(model, Meshes.cube(), gfx.lit(AidyColors.Satin, 0.55f, 0.35f), false, false).setLocal(V3.Zero, Q.Identity, V3.One * ARModelSize)
            }
            root.isVisible = false
            place(0f)
        }

        fun place(yaw: Float) {
            model.setLocal(V3(0f, ARModelSize * 0.5f + rig.legReach, 0f), Q.euler(0f, yaw, 0f))
            ring?.setLocal(V3(0f, 0.002f, 0f), Q.euler(-PI / 2f, 0f, ringAngle), V3.One * (ARModelSize / 0.9f))
        }

        var visible: Boolean
            get() = root.isVisible
            set(v) {
                root.isVisible = v
            }
    }

    /** 足元の輪 (RingGeometry(0.4, 0.48, 32) を本体の大きさに合わせたもの)。床に寝かせて置く。 */
    private fun makeRing(parent: Node, argb: Int): Node {
        val n = gfx.empty(parent, "TargetRing")
        gfx.mesh(n, Meshes.ring(0.4f, 0.48f, 32), gfx.unlit(argb), false, false)
        return n
    }

    // ---- ARCore のシーン (ワールド座標)
    val arRoot: Node = gfx.empty(null, "ARWorld")
    val arLight = LightNode(g.engine, LightManager.Type.DIRECTIONAL) {
        color(1f, 1f, 1f)
        intensity(100_000f * 0.6f)
        val d = V3(-0.35f, -0.8f, -0.5f).normalized()
        direction(d.x, d.y, d.z)
        castShadows(false)
    }
    private val placed = ARModel(arRoot, null, 0xff38bdf8.toInt())
    private val arMarkerModels = HashMap<Int, ARModel>()
    private var lastFrame: Frame? = null
    private var lastTracking: TrackingState? = null
    private var arClock = FrameClock()

    // ---- カメラ映像に重ねるシーン (カメラは原点。マーカーモードでは向きも固定なので、カメラ座標 = ワールド座標)
    val fbRoot: Node = gfx.empty(null, "ARFallback")
    val fbCamera = AidyCamera(g.engine).apply {
        fov = { webcamVerticalFov() }
        nearPlane = 0.01f
        farPlane = 100f
        lookAt(V3.Zero, V3(0f, 0f, -1f))
    }
    // カメラの左上から当てる光
    val fbLight = LightNode(g.engine, LightManager.Type.DIRECTIONAL) {
        color(1f, 1f, 1f)
        intensity(100_000f * 0.7f)
        val d = V3(0.35f, -0.57f, -0.74f).normalized()
        direction(d.x, d.y, d.z)
        castShadows(false)
    }
    private val free = ARModel(fbRoot, null, 0xff38bdf8.toInt())
    private val reticle: Node = makeRing(fbRoot, Theme.argb(0xffffff, 0.85f)).also { it.isVisible = false }
    private val webcamMarkerModels = HashMap<Int, ARModel>()
    private var fbClock = FrameClock()

    // カメラ映像の大きさ (画角の計算用)
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var videoRotation = 0
    private var viewW = 1
    private var viewH = 1

    // ジャイロ
    private var gyro: Q? = null
    private var gyroYaw: Q? = null

    init {
        ModuleConfig.changed += { _, _, _ ->
            placed.rig.sync()
            free.rig.sync()
            for (models in listOf(arMarkerModels, webcamMarkerModels)) for (m in models.values) if (m.live) m.rig.sync()
        }
        // 着せ替えマーカーを作った・消したとき: 作ったマーカーのモデルは次に見えたときに作り直す
        MarkerStore.changed += {
            for (models in listOf(arMarkerModels, webcamMarkerModels)) {
                for (id in models.keys.toList()) {
                    if (id == AidyMarkers.MyId || id == AidyMarkers.CardId) continue
                    val m = models.remove(id)!!
                    m.rig.release()
                    m.root.destroyTree()
                }
            }
        }
    }

    private fun now() = SystemClock.uptimeMillis() / 1000f

    // マーカー検出 (CPU で画像を調べる) の回数を抑える。発熱対策
    @Volatile private var lastSubmit = 0f

    /** 次の検出を回してよいか。ふだんは 1 秒に 10 回、ARCore でマーカーを捉えているときは 1 秒に 3 回。 */
    private fun detectDue(): Boolean {
        val t = now()
        val holding = mode == ARMode.AR && arMarkerModels.values.any { it.visible }
        if (t - lastSubmit < if (holding) 0.33f else 0.1f) return false
        lastSubmit = t
        return true
    }

    // ------------------------------------------------------------ 起動 / 停止

    fun startCamera() {
        cameraWanted = true
        if (mode == ARMode.None) mode = ARMode.Starting
        showPlacementStatus()
    }

    /** 「画面上」に切り替えたとき: カメラを止める。 */
    fun stopCamera() {
        cameraWanted = false
    }

    /** ARCore が使えるか調べて、AR / カメラ映像のどちらで動かすか決める。 */
    internal suspend fun begin(context: Context) {
        if (mode != ARMode.Starting) return
        if (arCapable) {
            var a = ArCoreApk.getInstance().checkAvailability(context)
            var waited = 0
            while (a.isTransient && waited < 3000) {
                delay(200)
                waited += 200
                a = ArCoreApk.getInstance().checkAvailability(context)
            }
            if (a.isSupported) {
                mode = ARMode.AR
                status = ARStatus(IconId.HandPointer, Color.White, 12f, "ARカメラ起動中…")
                return
            }
            arCapable = false
        }
        mode = ARMode.Webcam
        showPlacementStatus()
    }

    internal fun arFailed(e: Exception) {
        Log.w("Aidy", "ARCore を使えないのでカメラ映像で表示します", e)
        arCapable = false
        mode = ARMode.Webcam
        showPlacementStatus()
    }

    /** AR セッションを作り直したとき: ワールド座標が変わるので置いたモデルはいったん消す。 */
    internal fun arSessionCreated() {
        placed.visible = false
        placedOnMarker = false
        for (m in arMarkerModels.values) m.visible = false
    }

    /** カメラ映像を次のカメラに切り替える (背面 ⇔ 前面)。切り替え先の名前を返す。 */
    fun switchCamera(): String? {
        if (mode == ARMode.AR || mode == ARMode.Starting || cameraCount < 2) return null
        frontCamera = !frontCamera
        return if (frontCamera) "前面カメラ" else "背面カメラ"
    }

    internal fun setCameraCount(n: Int) {
        cameraCount = n
    }

    // ------------------------------------------------------------ 表示方法 (マーカー / マーカーなし)

    /** 表示方法を切り替える。表示中のモデルはいったん全部消す (選び直したら置き直し / マーカーを写し直し)。 */
    fun changePlacement(p: Placement) {
        placement = p
        placedOnMarker = false
        gyroYaw = null
        placed.visible = false
        free.visible = false
        reticle.isVisible = false
        for (models in listOf(arMarkerModels, webcamMarkerModels)) for (m in models.values) m.visible = false
        lastSeen.clear()
        showPlacementStatus()
        onPlacementChanged(p)
    }

    private fun showPlacementStatus() {
        if (mode == ARMode.Starting) return
        status = when {
            placement == Placement.Choose -> ARStatus(IconId.HandPointer, Color.White, 12f, "表示方法を選んでね")
            placement == Placement.Marker -> ARStatus(IconId.Crosshairs, Color.White, 12f, "マーカーをカメラに写してね")
            mode == ARMode.AR && lastTracking != TrackingState.TRACKING ->
                ARStatus(IconId.HandPointer, Color.White, 12f, "端末をゆっくり動かして床や机を認識中…")
            else -> ARStatus(
                IconId.HandPointer, Color.White, 12f,
                if (mode == ARMode.Webcam && gyro != null) "机や床をタップして置いてね (スマホの傾きに追従)" else "机や床をタップして置いてね",
            )
        }
    }

    // ------------------------------------------------------------ 入力

    internal fun pointerDown() {
        draggingAR = true
        dragDistance = 0f
    }

    internal fun drag(dxDp: Float, dyDp: Float) {
        if (!draggingAR) return
        dragDistance += abs(dxDp) + abs(dyDp)
        if (dragDistance <= 8f || placement != Placement.Free) return
        placedYaw += dxDp * 0.01f
    }

    internal fun pointerUp(x: Float, y: Float) {
        if (draggingAR && dragDistance <= 8f) {
            when (placement) {
                Placement.Marker -> onHint("マーカーを写すと、その上にAidyが出てくるよ")
                Placement.Free -> if (mode == ARMode.AR) placeAR(x, y, false) else if (mode == ARMode.Webcam) placeFree(x, y, false)
                Placement.Choose -> {}
            }
        }
        draggingAR = false
    }

    /** 「中央に再配置」: マーカーなしのとき、画面の中央に見えている机や床に置き直す。 */
    fun resetPosition() {
        when (placement) {
            Placement.Marker -> {
                onHint("マーカーモードではマーカーの上に出るよ")
                return
            }
            Placement.Choose -> return
            Placement.Free -> {}
        }
        if (mode == ARMode.AR) placeAR(viewW * 0.5f, viewH * 0.5f, true)
        else placeFree(viewW * 0.5f, viewH * 0.5f, true)
    }

    internal fun setViewSize(w: Int, h: Int) {
        viewW = max(1, w)
        viewH = max(1, h)
    }

    private fun fmt(v: Float) = String.format("%.2f", v)

    private fun placeAR(x: Float, y: Float, center: Boolean) {
        // 机や床 (ARCore が見つけた上向きの平面) の上にだけ置く
        val hit = try {
            lastFrame?.hitTest(x, y)?.firstOrNull { h ->
                val p = h.trackable as? Plane
                p != null && p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.isPoseInPolygon(h.hitPose)
            }
        } catch (e: Exception) {
            null
        }
        if (hit == null) {
            onHint("床や机を認識中… 端末をゆっくり動かして、青い面が出たらタップしてね")
            return
        }
        val pose = hit.hitPose
        placed.root.setLocal(V3(pose.tx(), pose.ty(), pose.tz()), Q.Identity)
        placed.visible = true
        placedOnMarker = false
        if (center) placedYaw = 0f
        onPlaced(if (center) "基準位置: (${fmt(pose.tx())}, ${fmt(pose.tz())}) 本体v2中央配置" else "基準位置: (${fmt(pose.tx())}, ${fmt(pose.tz())}) 本体v2配置完了")
    }

    /** カメラ映像だけのとき: 画面上の点から見た、仮想の机の面の上の点。 */
    private fun surfacePoint(x: Float, y: Float): V3? {
        val (o, d) = fbCamera.ray(x, y)
        val planeY = o.y - TableDepth
        if (d.y >= -1e-4f) return null
        val t = (planeY - o.y) / d.y
        if (t > MaxPlaceDistance) return null
        return o + d * t
    }

    private fun placeFree(x: Float, y: Float, center: Boolean) {
        val p = surfacePoint(x, y)
        if (p == null) {
            onHint("カメラを少し下に向けて、机や床を写してね")
            return
        }
        free.root.setLocal(p, Q.Identity)
        free.visible = true
        reticle.isVisible = false
        if (center) placedYaw = 0f
        onPlaced(if (center) "基準位置: (${fmt(p.x)}, ${fmt(p.z)}) 本体v2中央配置" else "基準位置: (${fmt(p.x)}, ${fmt(p.z)}) 本体v2配置完了")
    }

    // ------------------------------------------------------------ 毎フレーム

    private fun step(dt: Float) {
        accumulator = (accumulator + dt).coerceAtMost(0.25f)
        while (accumulator >= Step) {
            accumulator -= Step
            // animateAR: targetRing.rotation.z += 0.01 / 非ドラッグ時 本体 rotation.y += 0.004
            ringAngle += 0.01f
            if (!draggingAR && !placedOnMarker) placedYaw += 0.004f // マーカーの上ではマーカーの向きのまま
        }
        placed.place(placedYaw)
        free.place(placedYaw)
        for (models in listOf(arMarkerModels, webcamMarkerModels)) for (m in models.values) m.place(0f)
        placed.rig.update()
        free.rig.update()
    }

    /** ARCore の毎フレーム。 */
    internal fun onArFrame(frame: Frame) {
        lastFrame = frame
        step(arClock.dt(frame.timestamp))
        val tracking = frame.camera.trackingState
        if (tracking != lastTracking) {
            lastTracking = tracking
            showPlacementStatus()
        }
        tracker.tryCollect()?.forEach { applySighting(it, true) }
        if (pageVisible && placement == Placement.Marker && !tracker.busy && tracking == TrackingState.TRACKING && detectDue()) submitArFrame(frame)
    }

    /** ARCore のカメラ画像 (CPU 側、撮像素子の向き) を輝度で取り出して検出に回す。 */
    private fun submitArFrame(frame: Frame) {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            return
        }
        image.use { img ->
            val W = img.width
            val H = img.height
            val div = if (W > 800) 2 else 1
            val w = W / div
            val h = H / div
            val plane = img.planes[0]
            val src = plane.buffer
            val row = plane.rowStride
            val px = plane.pixelStride
            val buf = tracker.buffer(w, h)
            for (y in 0 until h) {
                val s = y * div * row
                val d = y * w
                for (x in 0 until w) buf[d + x] = src.get(s + x * div * px)
            }
            val k = frame.camera.imageIntrinsics
            val dims = k.imageDimensions
            val sx = w.toDouble() / dims[0]
            val sy = h.toDouble() / dims[1]
            val f = k.focalLength
            val c = k.principalPoint
            // 画面の正規化座標 → 画像の正規化座標 の変換から、画像の軸が画面のどちらを向くか
            val out = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.IMAGE_NORMALIZED, out)
            val (right, down) = MarkerTracker.axesFromDisplayMatrix(out[2] - out[0], out[4] - out[0], out[3] - out[1], out[5] - out[1])
            val cp = frame.camera.displayOrientedPose
            val cam = Pose(V3(cp.tx(), cp.ty(), cp.tz()), Q(cp.qx(), cp.qy(), cp.qz(), cp.qw()))
            tracker.submit(w, h, f[0] * sx, f[1] * sy, c[0] * sx, c[1] * sy, right, down, cam)
        }
    }

    /** カメラ映像に重ねるシーンの毎フレーム。 */
    internal fun onFallbackFrame(nanos: Long) {
        step(fbClock.dt(nanos))
        updateWebcamPose()
        tracker.tryCollect()?.forEach { applySighting(it, false) }
        // しばらく見えないマーカーのモデルは消す
        val t = now()
        for ((id, m) in webcamMarkerModels) {
            val seen = lastSeen[id]
            m.visible = mode == ARMode.Webcam && placement == Placement.Marker && seen != null && t - seen < MarkerLostSec
        }
        // まだ置いていなければ、画面の中央に見えている机の面に目印を出す
        val p = if (mode == ARMode.Webcam && placement == Placement.Free && !free.visible) surfacePoint(viewW * 0.5f, viewH * 0.5f) else null
        if (p != null) reticle.setLocal(p, Q.euler(-PI / 2f, 0f, ringAngle), V3.One * (ARModelSize / 0.9f))
        reticle.isVisible = p != null
        fbCamera.shiftY = 0f // 画角 (映像の大きさで変わる) を反映
    }

    /**
     * カメラの向き。マーカーなしではジャイロで端末の傾きを追う (なければ少し下向きに固定)。
     * マーカーモードでは向きを固定し、カメラ座標 = ワールド座標にする。
     */
    private fun updateWebcamPose() {
        if (mode != ARMode.Webcam || placement != Placement.Free) {
            fbCamera.lookAt(V3.Zero, V3(0f, 0f, -1f))
            return
        }
        val att = gyro
        if (att == null) {
            val r = Q.axisAngle(V3.Right, -NoGyroTilt * Deg2Rad)
            fbCamera.lookAt(V3.Zero, r * V3(0f, 0f, -1f))
            return
        }
        if (gyroYaw == null) {
            var f = att * V3(0f, 0f, -1f)
            f = V3(f.x, 0f, f.z)
            if (f.sqrLength < 1e-4f) {
                val u = att * V3.Up
                f = V3(u.x, 0f, u.z)
            }
            gyroYaw = Q.fromTo(f.normalized(), V3(0f, 0f, -1f))
        }
        val r = gyroYaw!! * att
        fbCamera.lookAt(V3.Zero, r * V3(0f, 0f, -1f))
    }

    /** 端末の姿勢 (回転ベクトルセンサー: 端末座標 → 世界 (Z 上))。カメラ (背面、縦持ち) の座標は端末の座標と同じ向き。 */
    internal fun setGyro(w: Float, x: Float, y: Float, z: Float) {
        val hadGyro = gyro != null
        // 世界 Z 上 → Y 上
        gyro = Q.axisAngle(V3.Right, -PI / 2f) * Q(x, y, z, w)
        if (!hadGyro) showPlacementStatus()
    }

    internal fun clearGyro() {
        gyro = null
    }

    private fun applySighting(s: MarkerSighting, world: Boolean) {
        if (placement != Placement.Marker) return // 切り替える前に送った画像の結果
        val t = now()
        val prev = lastSeen[s.id]
        val fresh = prev == null || t - prev > 2f
        lastSeen[s.id] = t
        if (fresh) {
            status = ARStatus(IconId.Circle, Theme.Green, 10f, "マーカー認識: ${AidyMarkers.name(s.id)}")
            onPlaced("基準位置: マーカー上 (${AidyMarkers.name(s.id)})")
        }
        val m: ARModel
        if (world && s.id == AidyMarkers.MyId) {
            m = placed
            if (!placedOnMarker) {
                placedOnMarker = true
                placedYaw = 0f
            }
        } else {
            m = markerModelFor(s.id, world) ?: return
        }
        // 揺れを抑える: 近ければ半分ずつ寄せ、大きく動いたら (別の場所に置き直した) すぐ合わせる
        val pos = m.root.position
        val quat = m.root.quaternion
        val p0 = V3(pos.x, pos.y, pos.z)
        val q0 = Q(quat.x, quat.y, quat.z, quat.w)
        val snap = !m.visible || (p0 - s.position).length > 0.05f || Q.angleDeg(q0, s.rotation) > 30f
        val p = if (snap) s.position else V3.lerp(p0, s.position, 0.5f)
        val r = if (snap) s.rotation else Q.slerp(q0, s.rotation, 0.5f)
        m.root.setLocal(p, r)
        m.visible = true
    }

    /** マーカーの上に立つモデル。まだなければ作る。構成は AidyMarkers.config (null ならいまの着せ替えに追従)。消された着せ替えマーカーなら null。 */
    private fun markerModelFor(id: Int, ar: Boolean): ARModel? {
        val models = if (ar) arMarkerModels else webcamMarkerModels
        models[id]?.let { return it }
        val config = AidyMarkers.config(id)
        if (config == null && id != AidyMarkers.MyId) return null
        // いまの着せ替えマーカーはいまの色に追従、それ以外はマーカーに保存した色
        val color = if (id == AidyMarkers.MyId) null else AidyMarkers.color(id)
        val m = ARModel(if (ar) arRoot else fbRoot, config, null, color)
        models[id] = m
        return m
    }

    // ------------------------------------------------------------ カメラ映像 (CameraX)

    /** 映像を輝度にして (長辺が 800 を超えるなら 1/2 に縮めて) 検出に回す (カメラのスレッド)。 */
    internal fun analyze(image: ImageProxy) {
        image.use { img ->
            val W = img.width
            val H = img.height
            videoW = W
            videoH = H
            videoRotation = img.imageInfo.rotationDegrees
            if (!pageVisible || placement != Placement.Marker || mode != ARMode.Webcam || tracker.busy || !detectDue()) return
            val div = if (max(W, H) > 800) 2 else 1
            val w = W / div
            val h = H / div
            val plane = img.planes[0]
            val src = plane.buffer
            val row = plane.rowStride
            val px = plane.pixelStride
            val buf = tracker.buffer(w, h)
            for (y in 0 until h) {
                val s = y * div * row
                val d = y * w
                for (x in 0 until w) buf[d + x] = src.get(s + x * div * px)
            }
            val f = webcamFocalPx(W, H) / div
            var (right, down) = MarkerTracker.rotatedAxes(videoRotation)
            // 前面カメラは鏡像で表示しているので、画面の左右を反転する
            if (frontCamera) {
                right = Axis2(-right.x, right.y)
                down = Axis2(-down.x, down.y)
            }
            tracker.submit(w, h, f, f, (w - 1) * 0.5, (h - 1) * 0.5, right, down, Pose())
        }
    }

    private fun webcamFocalPx(w: Int, h: Int) = max(w, h) * 0.5 / tan(WebcamHFov * 0.5 * Deg2Rad)

    /** 画面に見えている範囲の縦の画角 (映像は FILL_CENTER = cover で拡大・回転して表示している)。 */
    fun webcamVerticalFov(): Float {
        val w = videoW
        val h = videoH
        if (w <= 16 || h <= 16) return 45f
        val swap = videoRotation % 180 != 0
        val vw = if (swap) h else w
        val vh = if (swap) w else h
        val scale = max(viewW.toFloat() / vw, viewH.toFloat() / vh)
        val visible = viewH / scale // 画面の縦に入っている映像の画素数
        return (2.0 * atan(visible * 0.5 / webcamFocalPx(w, h)) * Rad2Deg).toFloat()
    }
}

/** AR 画面の帯のタッチ: タップで配置、ドラッグで回転。 */
private fun Modifier.arTouch(c: ARController) = this.pointerInput(c) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            down.consume()
            c.pointerDown()
            var last = down.position
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    c.pointerUp(ch.position.x, ch.position.y)
                    ch.consume()
                    break
                }
                val d = ch.positionChange()
                last = ch.position
                c.drag(d.x / density, d.y / density)
                ch.consume()
            }
        }
    }
}

/** AR 画面のカメラ映像 + 3D (ページいっぱい)。カメラを使わないときは黒。 */
@Composable
fun ARCameraView(c: ARController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Box(modifier.background(Color.Black).onSizeChanged { c.setViewSize(it.width, it.height) }) {
        if (!c.cameraWanted || !c.pageVisible) return@Box
        LaunchedEffect(granted) {
            if (!granted) launcher.launch(Manifest.permission.CAMERA)
        }
        if (!granted) return@Box
        LaunchedEffect(c.mode) { c.begin(context) }
        when (c.mode) {
            ARMode.AR -> ARCoreHost(c)
            ARMode.Webcam -> WebcamHost(c)
            else -> {}
        }
        Box(Modifier.fillMaxSize().arTouch(c))
    }
}

@Composable
private fun ARCoreHost(c: ARController) {
    val g = LocalGraphics.current
    val cameraNode = rememberARCameraNode(g.engine)
    val env = remember { g.environment(null) }
    val nodes = remember { listOf(c.arRoot) }
    ARScene(
        modifier = Modifier.fillMaxSize(),
        engine = g.engine,
        materialLoader = g.materialLoader,
        environmentLoader = g.environmentLoader,
        sessionConfiguration = { _, config ->
            // マーカーだけで表示するので平面は探さない (CPU の節約)
            config.planeFindingMode = if (c.placement == Placement.Free) Config.PlaneFindingMode.HORIZONTAL else Config.PlaneFindingMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.focusMode = Config.FocusMode.AUTO
            config.depthMode = Config.DepthMode.DISABLED
        },
        // ARCore の平面は「マーカーなし」のときだけ表示する
        planeRenderer = c.placement == Placement.Free,
        environment = env,
        mainLightNode = c.arLight,
        cameraNode = cameraNode,
        childNodes = nodes,
        onSessionCreated = { c.arSessionCreated() },
        onSessionFailed = { c.arFailed(it) },
        onSessionUpdated = { _, frame -> c.onArFrame(frame) },
        onGestureListener = null,
    )
}

@Composable
private fun WebcamHost(c: ARController) {
    val g = LocalGraphics.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // カメラ映像 (背面優先) + 解析用の画像
    DisposableEffect(c.frontCamera) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val selector = if (c.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            c.setCameraCount(listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA).count { runCatching { p.hasCamera(it) }.getOrDefault(false) })
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(executor) { c.analyze(it) }
            try {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                Log.w("Aidy", "カメラを開けません", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    // ジャイロ (マーカーなしで置くときに端末の傾きを追う)
    val free = c.placement == Placement.Free
    DisposableEffect(free) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val q = FloatArray(4)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getQuaternionFromVector(q, e.values)
                c.setGyro(q[0], q[1], q[2], q[3])
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (free && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sm.unregisterListener(listener)
            c.clearGyro()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    // 透明な 3D をカメラ映像の上に重ねる (SurfaceView 同士の重なり: メディアオーバーレイ)
    val scene = rememberScene(g.engine)
    val view = rememberView(g.engine)
    val renderer = rememberRenderer(g.engine)
    val env = remember { g.environment(null) }
    val nodes = remember { listOf(c.fbRoot) }
    DisposableEffect(Unit) {
        view.setupQuality()
        view.setShadowingEnabled(false)
        onDispose { }
    }
    Scene(
        modifier = Modifier.fillMaxSize(),
        engine = g.engine,
        materialLoader = g.materialLoader,
        environmentLoader = g.environmentLoader,
        view = view,
        isOpaque = false,
        renderer = renderer,
        scene = scene,
        environment = env,
        mainLightNode = c.fbLight,
        cameraNode = c.fbCamera,
        childNodes = nodes,
        cameraManipulator = null,
        onGestureListener = null,
        onTouchEvent = null,
        onFrame = { t -> c.onFallbackFrame(t) },
        onViewCreated = { setZOrderMediaOverlay(true) },
    )
}
