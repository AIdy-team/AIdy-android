package com.aidy.petrobot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.data.BodyLayout
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleConfig
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.three.HomeRoom
import com.aidy.petrobot.three.ModulePreview
import com.aidy.petrobot.three.clamp01
import com.aidy.petrobot.three.inverseLerp
import com.aidy.petrobot.three.lerp
import com.aidy.petrobot.three.repeat
import com.aidy.petrobot.three.smoothStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val SnapDp = 60f       // ドロップ先スロットの吸着距離
private const val CircleDp = 68f     // 中央のプレビューの直径
private const val VisibleSlots = 4f  // 表示幅に並ぶ間隔の数 (中央 + 左右 1 つずつ + 端に半分ずつ)
private const val Arc = 10f          // 端へ行くほど下がる量 (円形の軌道)
private const val SpinSpeed = 45f    // 中央のプレビューが回る速さ (度/秒)
private val DangerColor = Theme.hex(0xef4444)

/** Unity の Mathf.SmoothDamp。 */
private fun smoothDamp(current: Float, target: Float, velocity: FloatArray, smoothTime: Float, dt: Float): Float {
    val st = max(0.0001f, smoothTime)
    val omega = 2f / st
    val x = omega * dt
    val exp = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
    val change = current - target
    val tgt = current - change
    val temp = (velocity[0] + omega * change) * dt
    velocity[0] = (velocity[0] - omega * temp) * exp
    var out = tgt + (change + temp) * exp
    if ((target - current > 0f) == (out > target)) {
        out = target
        velocity[0] = (out - target) / dt
    }
    return out
}

/**
 * プレビュー画面下のモジュールトレイ (Unity 版 AidyModuleTray)。モジュールを丸いプレビューで並べ、左右にスワイプして選ぶ。
 * 4 つ以上あるときは円形にループするカルーセル (中央が大きく、端は小さく下がる)。面を選ぶ前は全モジュール、
 * 3D モデルの面をタップするとその面に付けられるモジュールだけになる。
 * モジュールを上へドラッグして 3D モデルのスロット (光る丸) へドロップで取り付け/差し替え、
 * 3D モデル上のモジュールをトレイへドラッグで取り外し。
 */
class ModuleTrayState(private val room: HomeRoom, private val placement: ViewPlacement) : ModuleDragTarget {
    val shown = mutableStateListOf<ModuleKind>()
    var face by mutableStateOf<String?>(null)
        private set

    // カルーセル: pos = 中央にいるモジュールの番号 (連続値。ループするので n の倍数ずれても同じ)
    var pos by mutableFloatStateOf(0f)
    internal var target = 0f
    internal val velocity = FloatArray(1)
    internal var swipeVelocity = 0f
    internal var swiping = false
    internal var stepPx = 1f

    val previews = mutableStateMapOf<ModuleKind, ImageBitmap>()
    internal var spinning: ModuleKind? = null
    internal var spinYaw = 0f

    var hint by mutableStateOf("")
        private set
    var hintError by mutableStateOf(false)
        private set

    // ドラッグ
    var ghost by mutableStateOf<Offset?>(null)
        private set
    var dragKind by mutableStateOf<ModuleKind?>(null)
        private set
    var dragFrom by mutableStateOf<String?>(null)
        private set
    private var hover: String? = null
    internal var trayBounds = Rect.Zero
    internal var carouselOrigin = Offset.Zero
    val dragging get() = ghost != null

    val loop get() = shown.size >= 4

    init {
        refresh()
    }

    fun refresh() {
        val f = room.selectedFace
        face = f
        val kinds = if (f == null) ModuleCatalog.All else ModuleCatalog.forFace(f)
        // 並びが同じなら作り直さない (取り付けのたびにカルーセルの位置が戻らないように)
        if (kinds == shown.toList()) return
        val focused = if (shown.isNotEmpty()) shown[wrap(pos.roundToInt())] else null
        shown.clear()
        shown.addAll(kinds)
        // 前に中央にいたモジュールが残っていればそれを中央に
        val start = if (focused != null) max(0, kinds.indexOf(focused)) else 0
        target = if (loop) start.toFloat() else 0f
        pos = target
        velocity[0] = 0f
        spinning = null
        spinYaw = 0f
    }

    fun title(): String {
        val f = face ?: return "モジュール ${shown.size}種 ・ 面をタップで取り付け"
        val counts = LinkedHashMap<ModuleKind, Int>()
        for (s in BodyLayout.onFace(f)) {
            val k = room.moduleAt(s.id) ?: continue
            counts[k] = (counts[k] ?: 0) + 1
        }
        val parts = counts.map { (k, n) -> if (n > 1) "${ModuleCatalog.name(k)}×$n" else ModuleCatalog.name(k) }
        return if (parts.isNotEmpty()) "${BodyLayout.faceLabel(f)} ・ 付いている: ${parts.joinToString("、")}"
        else "${BodyLayout.faceLabel(f)}に付けられるモジュール"
    }

    internal fun wrap(i: Int): Int {
        val n = shown.size
        return if (n == 0) 0 else ((i % n) + n) % n
    }

    /** モジュール i の中央からのずれ (間隔の数)。 */
    internal fun offsetOf(i: Int): Float {
        val n = shown.size
        return if (loop) repeat(i - pos + n * 0.5f, n.toFloat()) - n * 0.5f else i - (n - 1) * 0.5f // 3 つ以下は横に並べるだけ
    }

    internal fun frame(dt: Float) {
        if (shown.isEmpty() || swiping || !loop) return
        pos = smoothDamp(pos, target, velocity, 0.16f, max(dt, 0.0001f))
        if (abs(pos - target) < 0.0005f) {
            // 止まったら 0..n-1 に戻しておく (ループなので見た目は同じ)
            target -= floor(target / shown.size) * shown.size
            pos = target
            velocity[0] = 0f
        }
    }

    /** 中央で回すモジュール (トレイが見えていて止まっているときだけ)。 */
    internal fun spinTarget(): ModuleKind? {
        if (!loop || abs(pos - target) >= 0.3f || !room.visible || dragging) return null
        return shown.getOrNull(wrap(pos.roundToInt()))
    }

    // ---------------------------------------------------------------- スワイプ

    internal fun beginSwipe() {
        swiping = true
        swipeVelocity = 0f
    }

    internal fun swipe(dxPx: Float, dt: Float) {
        if (!swiping) return
        val d = -dxPx / stepPx
        pos += d
        target = pos
        swipeVelocity = lerp(swipeVelocity, d / max(dt, 0.001f), 0.35f)
    }

    internal fun endSwipe() {
        if (!swiping) return
        swiping = false
        // はじいた勢いの分だけ先へ (1 回で最大 3 つ)
        val fling = (swipeVelocity * 0.12f).coerceIn(-3f, 3f)
        target = kotlin.math.round(pos + fling)
        velocity[0] = swipeVelocity
    }

    /** 横のモジュールをタップしたら中央へ回す。 */
    internal fun tapped(i: Int) {
        if (!loop) return
        val off = offsetOf(i)
        if (abs(off) > 0.5f) target = kotlin.math.round(pos + off)
    }

    private fun setHint(text: String, error: Boolean = false) {
        hint = text
        hintError = error
    }

    // ---------------------------------------------------------------- ドラッグ & ドロップ

    private fun targetSlot(window: Offset): String? {
        val kind = dragKind ?: return null
        val l = placement.toLocal(window)
        return room.slotAtScreen(l.x, l.y, SnapDp, { ModuleCatalog.fits(kind, it) })
    }

    /** fromSlot: 3D モデル上のモジュールをつかんだときのスロット (トレイからなら null)。 */
    override fun beginDrag(kind: ModuleKind, fromSlot: String?, window: Offset) {
        if (fromSlot == null && room.selectedFace == null) {
            setHint("先に3Dモデルの面をタップしてね (${ModuleCatalog.name(kind)}を付ける場所)", true)
            return
        }
        dragKind = kind
        dragFrom = fromSlot
        hover = null
        ghost = window
        room.setDropState(kind, null)
        setHint(if (fromSlot != null) "別のスロットへ移動 / Aidyの外かトレイで離すと取り外し" else "${ModuleCatalog.name(kind)}を光っている丸へ")
        drag(window)
    }

    override fun drag(window: Offset) {
        if (!dragging) return
        ghost = window
        val over = targetSlot(window)
        if (over != hover) {
            hover = over
            room.setDropState(dragKind, hover)
        }
    }

    override fun endDrag(window: Offset) {
        if (!dragging) return
        val kind = dragKind!!
        val from = dragFrom
        val target = targetSlot(window)
        ghost = null
        room.setDropState(null, null)
        val overTray = trayBounds.contains(window)
        if (target != null && target != from) {
            val s = BodyLayout.get(target)!!
            val old = room.moduleAt(target)
            if (from != null) {
                ModuleConfig.detach(from)
                // 移動で別のモジュールに重ねたときは入れ替える
                if (old != null) ModuleConfig.attach(old, from)
            }
            ModuleConfig.replace(kind, target)
            setHint(
                if (old != null) "${BodyLayout.faceLabel(s.face)}の${s.label}を ${ModuleCatalog.name(old)} → ${ModuleCatalog.name(kind)} に切り替えました"
                else "${BodyLayout.faceLabel(s.face)}の${s.label}に${ModuleCatalog.name(kind)}を取り付けました",
            )
        } else if (from != null && (overTray || (target == null && !placement.toLocal(window).let { room.isOverRobot(it.x, it.y) }))) {
            // トレイの上か、Aidy から離れた所で指を離したら取り外し
            ModuleConfig.detach(from)
            setHint("${ModuleCatalog.name(kind)}を取り外しました")
        } else if (target == null && placement.toLocal(window).let { room.slotAtScreen(it.x, it.y, SnapDp, null) } != null) {
            setHint("${ModuleCatalog.name(kind)}はそこには付けられません", true)
        } else setHint("")
        dragKind = null
        dragFrom = null
    }
}

/** モジュールの色の枠が付いた丸いプレビュー画像。 */
@Composable
fun CirclePreview(kind: ModuleKind, image: ImageBitmap?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .border(2.dp, ModuleCatalog.tint(kind), CircleShape),
    ) {
        if (image != null) Image(image, null, Modifier.fillMaxSize())
    }
}

@Composable
fun ModuleTray(state: ModuleTrayState, onMarkerButton: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current

    // プレビュー画像 (はじめに全部描き、中央のものだけゆっくり回しながら描き直す)
    LaunchedEffect(state) {
        for (k in ModuleCatalog.All) {
            if (state.previews[k] != null) continue
            val bmp = withContext(Dispatchers.Default) { ModulePreview.render(k, 0f) }
            state.previews[k] = bmp.asImageBitmap()
        }
        val buffers = HashMap<ModuleKind, Array<Bitmap?>>()
        var flip = 0
        var last = System.nanoTime()
        while (true) {
            delay(50)
            val now = System.nanoTime()
            val dt = (now - last) / 1e9f
            last = now
            val center = state.spinTarget()
            if (center != state.spinning) {
                // 回していたものは正面に戻す
                state.spinning?.let { k -> state.previews[k] = withContext(Dispatchers.Default) { ModulePreview.render(k, 0f) }.asImageBitmap() }
                state.spinning = center
                state.spinYaw = 0f
            }
            if (center == null) continue
            state.spinYaw += SpinSpeed * dt
            val buf = buffers.getOrPut(center) { arrayOfNulls(2) }
            flip = 1 - flip
            val yaw = state.spinYaw
            val bmp = withContext(Dispatchers.Default) { ModulePreview.render(center, yaw, buf[flip]) }
            buf[flip] = bmp
            if (state.spinning == center) state.previews[center] = bmp.asImageBitmap()
        }
    }
    LaunchedEffect(state) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else (now - last) / 1e9f
            last = now
            state.frame(dt)
        }
    }

    // 着せ替えが変わったら見出しを作り直す
    @Suppress("UNUSED_VARIABLE") val v = ModuleConfig.version
    Box(
        modifier
            .box(Color.White, 18.dp, 4.dp, Theme.Border)
            .onGloballyPositioned { state.trayBounds = it.boundsInWindow() }
            .consumeTaps(),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(IconId.Cubes, 12.dp, Theme.Primary)
                Label(state.title(), 12f, Theme.TextSub, true, Modifier.weight(1f), maxLines = 1)
                // いまの着せ替えのマーカーを作る画面を開く
                Row(
                    Modifier
                        .height(22.dp)
                        .pressable(0.96f, onClick = onMarkerButton)
                        .background(Theme.PrimaryLight, RoundedCornerShape(11.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(IconId.Camera, 10.dp, Theme.Primary)
                    Label("マーカー作成", 11f, Theme.Primary, true)
                }
            }
            Carousel(state)
            Label(
                state.hint, 11f, if (state.hintError) DangerColor else Theme.Primary, true,
                Modifier.fillMaxWidth().height(14.dp), maxLines = 1, align = TextAlign.Center,
            )
        }
        // 3D モデルからモジュールを持ってきたときの「ここで離すと取り外し」
        if (state.dragging && state.dragFrom != null) {
            Row(
                Modifier
                    .matchParentSize()
                    .box(Theme.hex(0xfef2f2, 0.97f), 18.dp, border = DangerColor, borderWidth = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Icon(IconId.XMark, 14.dp, DangerColor)
                Label("ここで離すと取り外し", 13f, DangerColor, true)
            }
        }
    }
}

@Composable
private fun Carousel(state: ModuleTrayState) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height((CircleDp + 34f).dp)
            .clipToBounds()
            .onGloballyPositioned { state.carouselOrigin = it.positionInWindow() },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val itemW = with(density) { (CircleDp + 16f).dp.toPx() }
        state.stepPx = max(widthPx / VisibleSlots, with(density) { (CircleDp + 8f).dp.toPx() })
        val n = state.shown.size
        val loop = state.loop
        val fadeEnd = min(2.4f, n * 0.5f - 0.05f)
        val fadeStart = fadeEnd - 0.7f
        val pos = state.pos // 読んでおく (動いたら並べ直す)

        // スワイプ / タップ / 上へのドラッグ (取り付け) を受ける
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    val slop = viewConfiguration.touchSlop
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                            val start = down.position
                            // 押した所のモジュール (中央に近いものを優先)
                            var hitIndex = -1
                            var bestA = Float.MAX_VALUE
                            for (i in 0 until state.shown.size) {
                                val off = state.offsetOf(i)
                                val a = abs(off)
                                val alpha = if (state.loop) 1f - smoothStep(0f, 1f, inverseLerp(fadeStart, fadeEnd, a)) else 1f
                                if (alpha <= 0.3f) continue
                                val scale = if (state.loop) lerp(1f, 0.74f, clamp01(a)) * lerp(1f, 0.85f, clamp01(a - 1f)) else 0.92f
                                val cx = widthPx * 0.5f + off * state.stepPx
                                if (abs(start.x - cx) <= itemW * 0.5f * scale && a < bestA) {
                                    bestA = a
                                    hitIndex = i
                                }
                            }
                            var mode = 0 // 0: 未定, 1: スワイプ, 2: 取り付けのドラッグ, 3: ページャーへ渡す
                            var lastTime = down.uptimeMillis
                            var released = false
                            while (!released) {
                                val ev = awaitPointerEvent(PointerEventPass.Main)
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                                val dt = (c.uptimeMillis - lastTime) / 1000f
                                lastTime = c.uptimeMillis
                                if (!c.pressed) {
                                    released = true
                                    when (mode) {
                                        0 -> if (hitIndex >= 0) state.tapped(hitIndex)
                                        1 -> state.endSwipe()
                                        2 -> state.endDrag(state.carouselOrigin + c.position)
                                    }
                                    if (mode != 3) c.consume()
                                    break
                                }
                                if (mode == 0) {
                                    val d = c.position - start
                                    if (abs(d.x) > slop || abs(d.y) > slop) {
                                        val horizontal = abs(d.x) > abs(d.y)
                                        mode = when {
                                            // ループしない (3 つ以下) ときは送る先がないので、親のページャーにページを切り替えさせる
                                            horizontal && !state.loop -> 3
                                            horizontal -> 1
                                            hitIndex >= 0 -> 2
                                            else -> 1
                                        }
                                        when (mode) {
                                            1 -> state.beginSwipe()
                                            2 -> state.beginDrag(state.shown[hitIndex], null, state.carouselOrigin + c.position)
                                        }
                                    }
                                }
                                when (mode) {
                                    1 -> {
                                        state.swipe(c.positionChange().x, dt)
                                        c.consume()
                                    }
                                    2 -> {
                                        state.drag(state.carouselOrigin + c.position)
                                        c.consume()
                                    }
                                }
                            }
                        }
                    }
                },
        )

        // 中央に近いものを手前に
        val order = (0 until n).sortedByDescending { abs(state.offsetOf(it)) }
        for (i in order) {
            val k = state.shown[i]
            val off = state.offsetOf(i)
            val a = abs(off)
            val scale = if (loop) lerp(1f, 0.74f, clamp01(a)) * lerp(1f, 0.85f, clamp01(a - 1f)) else 0.92f
            val y = if (loop) -Arc * min(a, 2.5f) * min(a, 2.5f) * 0.5f else 0f
            val alpha = if (loop) 1f - smoothStep(0f, 1f, inverseLerp(fadeStart, fadeEnd, a)) else 1f
            val labelAlpha = if (loop) clamp01(1.4f - a) else 1f
            val bold = !loop || a < 0.5f
            val x = widthPx * 0.5f + off * state.stepPx - itemW * 0.5f
            val yPx = with(density) { (4f - y).dp.toPx() }
            Column(
                Modifier
                    .offset { IntOffset(x.roundToInt(), yPx.roundToInt()) }
                    .size((CircleDp + 16f).dp, (CircleDp + 22f).dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CirclePreview(k, state.previews[k], CircleDp.dp)
                Box(Modifier.weight(1f))
                Label(
                    ModuleCatalog.name(k), 11f, Theme.TextMain.copy(alpha = labelAlpha), bold,
                    Modifier.padding(bottom = 2.dp), maxLines = 1, align = TextAlign.Center,
                )
            }
        }
    }
}

/** ドラッグ中のモジュール (指で隠れないよう、指の少し上に出す)。ページ全体を覆う Box の中に置く。 */
@Composable
fun ModuleDragGhost(state: ModuleTrayState) {
    var origin by androidx.compose.runtime.remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val g = state.ghost ?: return@Box
        val kind = state.dragKind ?: return@Box
        val density = LocalDensity.current
        val half = with(density) { 32.dp.toPx() }
        val up = with(density) { 40.dp.toPx() }
        CirclePreview(
            kind, state.previews[kind], 64.dp,
            Modifier
                .offset { IntOffset((g.x - origin.x - half).roundToInt(), (g.y - origin.y - up - half).roundToInt()) }
                .graphicsLayer { alpha = 0.95f }
                .shadow(8.dp, CircleShape),
        )
    }
}
