package com.aidy.petrobot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.runtime.key
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.data.Prefs
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * 初回起動のチュートリアル (コーチマーク)。
 * 実際の画面の上を暗くして、説明したい部品だけを明るく切り抜き、デフォルメ Aidy が吹き出しと指差しで案内する。
 * 「やってみよう」の手順では、切り抜いた部品だけ実際にさわれて、できたら (Coach.event) 次へ進む。
 *
 * 画面の部品は Modifier.coachTarget("id") で場所を登録する。
 */

/** 指のアニメーション: タップ (押して波紋) / こする (左右に往復) / なぞる (横に大きくすべらせる)。 */
enum class CoachGesture { Tap, Rub, Swipe }

/** チュートリアルの 1 手順。 */
class CoachStep(
    /** Aidy のセリフ。 */
    val text: String,
    /** 明るく切り抜く部品 (coachTarget の id)。null なら画面のまんなかで話すだけ。 */
    val target: String? = null,
    /** この手順で見せるページ (違うページにいたら移る)。 */
    val page: Int? = null,
    /** このイベント (Coach.event) が来たら次へ。null なら「つぎへ」ボタンで進む。 */
    val waitFor: String? = null,
    /** できたときのひとこと。 */
    val praise: String = "じょうず！",
    val face: AidyFace = AidyFace.Normal,
    /** 3D の読み込みを待つ ("home" / "ar")。 */
    val needs: String? = null,
    /** 指の動き。 */
    val gesture: CoachGesture = CoachGesture.Tap,
)

object Coach {
    private const val DoneKey = "coachDone"

    /** 初回のチュートリアルを終えたか (スキップでも終えた扱い)。 */
    var done: Boolean
        get() = Prefs.getInt(DoneKey, 0) == 1
        set(v) = Prefs.putInt(DoneKey, if (v) 1 else 0)

    var active by mutableStateOf(false)
        private set
    var index by mutableIntStateOf(0)
        private set
    /** やってみようの手順が「できた」状態 (ほめてから次へ進む)。 */
    var cleared by mutableStateOf(false)
        private set

    /** 部品の場所 (画面全体の座標、px)。 */
    val targets = mutableStateMapOf<String, Rect>()

    /** 3D の読み込みが終わったか (AidyApp が入れる)。 */
    var homeReady by mutableStateOf(false)
    var arReady by mutableStateOf(false)

    var steps: List<CoachStep> = emptyList()
    val step: CoachStep? get() = if (active) steps.getOrNull(index) else null

    fun start(steps: List<CoachStep>) {
        this.steps = steps
        index = 0
        cleared = false
        active = true
    }

    fun next() {
        cleared = false
        if (index + 1 >= steps.size) finish() else index++
    }

    fun finish() {
        active = false
        cleared = false
        done = true
    }

    /** 画面からのできごと ("pet", "page1" など)。待っている手順ならできた扱いにする。 */
    fun event(name: String) {
        val s = step ?: return
        if (!cleared && s.waitFor == name) cleared = true
    }

    fun setTarget(id: String, r: Rect) {
        if (targets[id] != r) targets[id] = r
    }
}

/** この部品をチュートリアルで指せるように場所を登録する。指す番になったら、スクロールの外でも見えるところまで動かす。 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.coachTarget(id: String): Modifier = composed {
    val req = remember { BringIntoViewRequester() }
    val wanted = Coach.step?.target == id
    LaunchedEffect(wanted) {
        if (wanted) {
            delay(350) // ページを移るのを待つ
            req.bringIntoView()
        }
    }
    this.bringIntoViewRequester(req).onGloballyPositioned { Coach.setTarget(id, it.boundsInRoot()) }
}

// ---------------------------------------------------------------- 画面

/** 画面の上にかぶせるチュートリアル。アプリ全体の Box のいちばん上に置く。 */
@Composable
fun CoachOverlay() {
    val step = Coach.step ?: return
    val density = LocalDensity.current

    BackHandler { /* チュートリアル中は戻る操作で抜けないようにする (右上のスキップで終わる) */ }

    // できたら少しほめてから次へ
    LaunchedEffect(Coach.index, Coach.cleared) {
        if (Coach.cleared) {
            delay(1300)
            Coach.next()
        }
    }

    val loading = when (step.needs) {
        "home" -> !Coach.homeReady
        "ar" -> !Coach.arReady
        else -> false
    }
    val waiting = step.waitFor != null && !Coach.cleared

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val pad = with(density) { 6.dp.toPx() }
        val raw = step.target?.let { Coach.targets[it] }
        // 画面に出ていない部品 (別ページ・スクロールの外) は切り抜かない
        val hole = raw?.takeIf { !loading && it.width > 1f && it.height > 1f && it.bottom > 0f && it.top < screenH }
            ?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }

        // ---- 暗幕 (部品のところだけ切り抜く) + 光る枠
        val pulse = rememberInfiniteTransition(label = "coach")
        val glow by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.6f))
            if (hole != null) {
                val r = CornerRadius(16.dp.toPx())
                drawRoundRect(Color.Transparent, hole.topLeft, hole.size, r, blendMode = BlendMode.Clear)
                val g = (3f + 4f * glow).dp.toPx()
                drawRoundRect(
                    Theme.Primary.copy(alpha = 0.5f + 0.5f * glow),
                    Offset(hole.left - g / 2f, hole.top - g / 2f), Size(hole.width + g, hole.height + g), r,
                    style = Stroke(3.dp.toPx()),
                )
            }
        }

        // ---- タッチを止める板。「やってみよう」の手順では切り抜いたところだけ下の画面にとどく
        if (waiting && hole != null) TouchBlockersAround(hole, screenW, screenH)
        else Box(Modifier.fillMaxSize().blockTouches())

        // ---- 指差し (部品を指す)
        var bubbleH by remember { mutableIntStateOf(0) }
        val big = hole != null && hole.height > screenH * 0.45f
        val bubbleAbove = hole != null && !big && hole.center.y > screenH * 0.5f
        if (hole != null && !Coach.cleared) key(Coach.index) { PointingHand(hole, bubbleAbove, big, step.gesture) }

        // ---- Aidy の吹き出し
        val margin = with(density) { 16.dp.toPx() }
        val handSpace = with(density) { 64.dp.toPx() }
        val top = with(density) { 40.dp.toPx() }
        val y = when {
            hole == null -> (screenH - bubbleH) / 2f
            big -> hole.top + margin // 大きい部品 (3D) は、その上のほうに重ねる
            bubbleAbove -> hole.top - handSpace - bubbleH
            else -> hole.bottom + handSpace
        }.coerceIn(top, (screenH - bubbleH - margin).coerceAtLeast(top))
        // 手順が変わるたびに、吹き出しがぽよんと出る
        val pop = remember(Coach.index) { Animatable(0f) }
        LaunchedEffect(Coach.index) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f)) }
        Box(
            Modifier
                .offset { IntOffset(0, y.roundToInt()) }
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .onSizeChanged { bubbleH = it.height }
                .graphicsLayer {
                    val s = 0.86f + 0.14f * pop.value
                    scaleX = s
                    scaleY = s
                    alpha = pop.value.coerceIn(0f, 1f)
                },
        ) {
            Bubble(step, loading, waiting)
        }
    }
}

/** 切り抜き (hole) のまわり 4 枚だけタッチを止める (切り抜きの中は下の画面が受け取る)。 */
@Composable
private fun TouchBlockersAround(hole: Rect, w: Float, h: Float) {
    val d = LocalDensity.current
    @Composable
    fun block(l: Float, t: Float, r: Float, b: Float) {
        if (r <= l || b <= t) return
        with(d) {
            Box(Modifier.offset { IntOffset(l.roundToInt(), t.roundToInt()) }.size((r - l).toDp(), (b - t).toDp()).blockTouches())
        }
    }
    val l = hole.left.coerceIn(0f, w)
    val r = hole.right.coerceIn(0f, w)
    val t = hole.top.coerceIn(0f, h)
    val b = hole.bottom.coerceIn(0f, h)
    block(0f, 0f, w, t)
    block(0f, b, w, h)
    block(0f, t, l, b)
    block(r, t, w, b)
}

/**
 * 部品を指す指 (背景なし)。部品の上 (吹き出しが上) か下から指す。大きい部品 (3D) はまんなかを指す。
 * Tap: すっと近づいて押す → 指先から波紋 / Rub: 指先で左右にこする / Swipe: 横に大きくすべらせる。
 */
@Composable
private fun PointingHand(hole: Rect, fromAbove: Boolean, big: Boolean, gesture: CoachGesture) {
    val d = LocalDensity.current
    val loop = rememberInfiniteTransition(label = "hand")
    val ms = when (gesture) {
        CoachGesture.Tap -> 1400
        CoachGesture.Rub -> 900
        CoachGesture.Swipe -> 1800
    }
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "t")
    fun dp(v: Float) = with(d) { v.dp.toPx() }

    val size = 46.dp
    val sizePx = dp(46f)
    // 指のアイコンは上向き。上から指すときは 180 度回す
    val pointDown = fromAbove && !big
    // 指先 (アイコンの上端の少し左) が来る点
    val tip = when {
        big -> Offset(hole.center.x, hole.center.y + dp(10f))
        pointDown -> Offset(hole.center.x, hole.top - dp(2f))
        else -> Offset(hole.center.x, hole.bottom + dp(2f))
    }

    // 動き: 指先の位置ずらし (dx, dy) と押し込み (scale)、波紋の進み具合 (0..1、負なら出さない)
    var dx = 0f
    var dy = 0f
    var press = 1f
    var ripple = -1f
    var alpha = 1f
    val away = if (pointDown) -1f else 1f // 部品から離れる向き (y)
    when (gesture) {
        CoachGesture.Tap -> {
            if (t < 0.35f) { // 少し離れた所からすっと近づく
                val p = FastOutSlowInEasing.transform(t / 0.35f)
                dy = away * dp(22f) * (1f - p)
                alpha = p.coerceAtLeast(0.2f)
            } else if (t < 0.5f) press = 1f - 0.15f * sin((t - 0.35f) / 0.15f * PI.toFloat()) // ぎゅっと押す
            if (t >= 0.42f) ripple = (t - 0.42f) / 0.58f // 押したところから波紋
            if (t > 0.85f) alpha = 1f - (t - 0.85f) / 0.15f
        }
        CoachGesture.Rub -> {
            dx = sin(t * 2f * PI.toFloat()) * dp(18f)
            press = 0.94f
        }
        CoachGesture.Swipe -> {
            // 左から右へ大きくすべらせて、ふっと消えてまた左から
            val p = (t / 0.8f).coerceAtMost(1f)
            dx = (FastOutSlowInEasing.transform(p) - 0.5f) * dp(140f)
            alpha = when {
                t < 0.1f -> t / 0.1f
                t > 0.8f -> 1f - (t - 0.8f) / 0.2f
                else -> 1f
            }
            press = 0.94f
        }
    }

    // 波紋 (指先から広がる輪)
    if (ripple in 0f..1f) {
        Canvas(Modifier.fillMaxSize()) {
            val r = dp(8f) + dp(30f) * ripple
            drawCircle(Color.White.copy(alpha = 0.8f * (1f - ripple)), r, tip, style = Stroke(dp(3f)))
            drawCircle(Theme.Primary.copy(alpha = 0.35f * (1f - ripple)), r * 0.6f, tip)
        }
    }

    // 指先が tip に来るように置く (アイコンの指先は上端・中央より少し左)
    val tipInIcon = Offset(sizePx * 0.42f, sizePx * 0.06f)
    val x = tip.x + dx - (if (pointDown) sizePx - tipInIcon.x else tipInIcon.x)
    val y = tip.y + dy - (if (pointDown) sizePx - tipInIcon.y else tipInIcon.y)
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(size)
            .graphicsLayer {
                scaleX = press
                scaleY = press
                this.alpha = alpha
                rotationZ = if (pointDown) 180f else 0f
            },
    ) {
        // 影と白いふち (暗い所でも明るい所でも見えるように)。背景の丸は付けない
        Box(Modifier.offset(2.dp, 3.dp)) { Icon(IconId.HandPointer, size, Color.Black.copy(alpha = 0.28f)) }
        for ((ox, oy) in listOf(-1.5f to 0f, 1.5f to 0f, 0f to -1.5f, 0f to 1.5f)) {
            Box(Modifier.offset(ox.dp, oy.dp)) { Icon(IconId.HandPointer, size, Color.White) }
        }
        Icon(IconId.HandPointer, size, Theme.Primary)
    }
}

/** Aidy + 吹き出し + ボタン。 */
@Composable
private fun Bubble(step: CoachStep, loading: Boolean, waiting: Boolean) {
    val total = Coach.steps.size
    val face = when {
        Coach.cleared -> AidyFace.Happy
        loading -> AidyFace.Sleepy
        else -> step.face
    }
    // できたら、吹き出しの Aidy がぴょんと跳ねる
    val mascot = remember { MascotState() }
    LaunchedEffect(Coach.index, Coach.cleared) { if (Coach.cleared) mascot.react(AidyFace.Happy, 1.2f) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AidyMascot(58.dp, face, state = mascot)
        Column(
            Modifier
                .weight(1f)
                .box(Color.White, 18.dp, 8.dp)
                .blockTouches()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 何番目か + チュートリアルをやめる (右上の歯車などを隠さないよう、吹き出しの中に置く)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("${Coach.index + 1} / $total", 11f, Theme.Slate400, true, Modifier.weight(1f))
                if (Coach.index < total - 1) {
                    Box(Modifier.pressable(0.95f) { Coach.finish() }.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                        Label("チュートリアルをやめる", 11f, Theme.Slate400, true)
                    }
                }
            }
            val text = when {
                Coach.cleared -> step.praise
                loading -> "ぼくの準備をしているよ。ちょっと待ってね…"
                else -> step.text
            }
            Label(text, 16f, Theme.TextMain, true)
            if (Coach.cleared) return@Column
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (waiting) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).background(Theme.Primary, RoundedCornerShape(4.dp)))
                        Label("やってみてね", 12f, Theme.Primary, true)
                    }
                    Spacer(Modifier.weight(1f))
                    // うまくできないときに先へ進める
                    Box(Modifier.pressable(0.95f) { Coach.next() }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Label("とばす", 12f, Theme.Slate400, true)
                    }
                } else if (!loading) {
                    Spacer(Modifier.weight(1f))
                    val last = Coach.index == total - 1
                    Box(
                        Modifier
                            .pressable(0.96f) { Coach.next() }
                            .background(Theme.Primary, RoundedCornerShape(18.dp))
                            .padding(horizontal = 22.dp, vertical = 9.dp),
                    ) { Label(if (last) "おわる" else "つぎへ", 14f, Color.White, true) }
                }
            }
        }
    }
}
