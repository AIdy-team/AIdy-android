package com.aidy.petrobot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.SideEffect
import com.aidy.petrobot.three.Gfx
import com.aidy.petrobot.three.HomeRoom
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.View
import io.github.sceneview.Scene
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.Node
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
import kotlin.math.pow

/** アプリ全体で 1 つの Filament エンジンと、その上のメッシュ・材質の生成器。 */
class Graphics(val engine: Engine, val materialLoader: MaterialLoader, val environmentLoader: EnvironmentLoader) {
    val gfx = Gfx(engine, materialLoader)

    /**
     * 環境光 (Unity 版: AmbientLight(0.95) + HemisphereLight 相当の明るい一様な光、映り込みなし) と背景色。
     * argb の背景色で塗りつぶす (null なら透明 = カメラ映像の上に重ねる)。
     */
    fun environment(background: Int?): Environment {
        val ibl = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(30_000f)
            .build(engine)
        val sky = if (background != null) {
            fun lin(c: Int) = (c / 255f).pow(2.2f)
            Skybox.Builder().color(lin((background shr 16) and 0xff), lin((background shr 8) and 0xff), lin(background and 0xff), 1f).build(engine)
        } else Skybox.Builder().color(0f, 0f, 0f, 0f).build(engine)
        return Environment(ibl, sky)
    }
}

val LocalGraphics = compositionLocalOf<Graphics> { error("Graphics がありません") }

/** 影・アンチエイリアスの設定。 */
fun View.setupQuality() {
    setShadowingEnabled(true)
    antiAliasing = View.AntiAliasing.FXAA
    // 4 倍 MSAA は実機で発熱が大きいので切る (縁のギザギザは FXAA でならす)
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = false }
    dynamicResolutionOptions = dynamicResolutionOptions.apply { enabled = false }
}

/**
 * HomeRoom を描く 3D ビュー (SceneView)。カメラ操作は SceneView に任せず、上に重ねた RoomTouch で受ける。
 * active が false の間はフレームを進めない (ページが見えていないとき)。
 */
@Composable
fun RoomScene(room: HomeRoom, active: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGraphics.current
    val scene = rememberScene(g.engine)
    val view = rememberView(g.engine)
    val renderer = rememberRenderer(g.engine)
    val env = remember { g.environment(room.clearColor) }
    val nodes = remember { listOf<Node>(room.root) }
    val state = remember { FrameClock() }
    room.visible = active
    // 見えていない間は描画そのものを止める (SceneView は RESUMED の間だけフレームを回す)
    val parentLifecycle = LocalLifecycleOwner.current.lifecycle
    val gate = remember(parentLifecycle) { GatedLifecycleOwner(parentLifecycle) }
    SideEffect { gate.active = active }
    DisposableEffect(Unit) {
        view.setupQuality()
        onDispose { }
    }
    Box(modifier) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = g.engine,
            materialLoader = g.materialLoader,
            environmentLoader = g.environmentLoader,
            view = view,
            isOpaque = true,
            renderer = renderer,
            scene = scene,
            environment = env,
            mainLightNode = room.light,
            cameraNode = room.camera,
            childNodes = nodes,
            cameraManipulator = null,
            onGestureListener = null,
            onTouchEvent = null,
            lifecycle = gate.lifecycle,
            onFrame = { t -> room.frame(state.dt(t)) },
        )
    }
}

/** フレーム間の秒数。 */
class FrameClock {
    private var last = 0L
    fun dt(now: Long): Float {
        val d = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.25f)
        last = now
        return d
    }
}

/**
 * 親 (Activity) のライフサイクルに従いつつ、active でないときは STARTED で止めるライフサイクル。
 * SceneView に渡すと、見えていないページの 3D は描画もフレーム処理もしなくなる (発熱対策)。
 */
class GatedLifecycleOwner(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    var active = false
        set(v) {
            if (field == v) return
            field = v
            sync()
        }

    private val observer = LifecycleEventObserver { _, _ -> sync() }

    init {
        parent.addObserver(observer)
    }

    private fun sync() {
        val p = parent.currentState
        if (p == Lifecycle.State.DESTROYED) {
            // 破棄は SceneView 自身が行うので、ここでは止めるだけ
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
            return
        }
        val target = if (active) p else minOf(p, Lifecycle.State.STARTED)
        registry.currentState = target
    }
}
