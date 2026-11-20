package com.aidy.petrobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.three.HomeRoom
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 3D ビュー上のモジュールのドラッグ (つかんだモジュール, スロット, 指の位置 (ウィンドウ座標)) を受ける側。 */
interface ModuleDragTarget {
    fun beginDrag(kind: ModuleKind, fromSlot: String?, window: Offset)
    fun drag(window: Offset)
    fun endDrag(window: Offset)
}

/** 3D ビューのウィンドウ上の位置 (ウィンドウ座標 ⇔ ビュー内の画素)。 */
class ViewPlacement {
    var origin = Offset.Zero
    fun toLocal(window: Offset) = window - origin
}

/**
 * 3D ビュー上の操作 (Unity 版 AidyHomeTouch): ドラッグで回転、タップ (ホーム: なでなで / プレビュー: 面の選択)、
 * 長押しでうっとり、2 本指ピンチでズーム。プレビューでは選んだ面のモジュールをつかんで移動/取り外し。
 */
/** 3D ビューで本体の外を速く横にスワイプしたときのページ移動 (dir = +1 で次、-1 で前)。AidyApp が渡す。 */
val LocalPageSwipe = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/**
 * onRub: ホームで本体の上を指で左右にこすっている (なでなで) ときに、指の位置 (ビュー内の画素) を知らせる (ハートを出す用)。
 */
fun Modifier.roomTouch(
    room: HomeRoom,
    placement: ViewPlacement,
    tray: ModuleDragTarget?,
    onSwipe: ((Int) -> Unit)? = null,
    onRub: ((Offset) -> Unit)? = null,
) = this.pointerInput(room, onSwipe) {
    val holdMs = 600L
    val density = this.density
    awaitPointerEventScope {
        while (true) {
            // 最初の指
            var ev = awaitPointerEvent(PointerEventPass.Main)
            val first = ev.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            first.consume()
            val pressPos = first.position
            var moved = 0f
            var held = false
            var pinching = false
            var pinchDist = 0f
            var draggingModule = false
            var dragStarted = false
            val downTime = System.currentTimeMillis()
            room.draggingModel = true
            room.touched()
            var lastPos = pressPos
            // 本体の外から始めた指: 速い横スワイプならページ移動 (回した分は元に戻す)
            val offRobot = onSwipe != null && !room.isOverRobot(pressPos.x, pressPos.y)
            val turn0 = room.saveTurn()
            val velocity = VelocityTracker()
            velocity.addPosition(first.uptimeMillis, pressPos)
            // なでなで (ホームで本体の上から始めた指を、左右に細かく往復させる)
            val canRub = !room.editable && room.isOverRobot(pressPos.x, pressPos.y)
            var rubbing = false
            var lastSign = 0
            var stroke = 0f
            var reversals = 0

            while (true) {
                val remain = holdMs - (System.currentTimeMillis() - downTime)
                val next = if (!held && !pinching && moved < 8f && remain > 0) withTimeoutOrNull(remain) { awaitPointerEvent(PointerEventPass.Main) }
                else awaitPointerEvent(PointerEventPass.Main)
                if (next == null) {
                    // 指を置いたまま 0.6 秒
                    if (!held && !pinching && moved < 8f) {
                        held = true
                        room.holdStart()
                    }
                    continue
                }
                ev = next
                val pressed = ev.changes.filter { it.pressed }
                ev.changes.forEach { it.consume() }

                // 2 本指ピンチでズーム
                if (pressed.size >= 2) {
                    val dist = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                    if (!pinching) {
                        pinching = true
                        pinchDist = dist
                        moved = 100f
                        if (held) {
                            held = false
                            room.holdEnd()
                        }
                    } else if (pinchDist > 1f && dist > 1f) {
                        room.zoomBy(dist / pinchDist)
                        pinchDist = dist
                    }
                    continue
                }
                if (pressed.isEmpty()) {
                    // 指を離した
                    room.draggingModel = false
                    val up = ev.changes.firstOrNull()?.position ?: lastPos
                    ev.changes.firstOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    val v = velocity.calculateVelocity()
                    val vx = v.x / density
                    val total = (up - pressPos) / density
                    val swipe = offRobot && !draggingModule && !pinching && !held &&
                        abs(vx) > 700f && abs(total.x) > 40f && abs(total.x) > abs(total.y) * 1.5f
                    if (rubbing) room.holdEnd()
                    else if (swipe) {
                        room.restoreTurn(turn0)
                        onSwipe?.invoke(if (vx < 0f) 1 else -1)
                    } else if (draggingModule) {
                        tray?.endDrag(placement.origin + up)
                    } else if (held) room.holdEnd()
                    else if (moved < 8f && !pinching) room.tapAt(up.x, up.y)
                    break
                }
                if (pinching) continue
                val c = pressed[0]
                // 上で consume 済みなので positionChange() は常に 0 になる。消費を無視して移動量を読む
                val delta = c.positionChangeIgnoreConsumed()
                lastPos = c.position
                velocity.addPosition(c.uptimeMillis, c.position)

                // プレビュー: 選んだ面の取り付け済みモジュールをつかんだら、モジュールのドラッグ (移動/取り外し) にする
                if (!dragStarted && (delta.x != 0f || delta.y != 0f)) {
                    dragStarted = true
                    if (room.editable && tray != null && !held && room.selectedFace != null) {
                        // 面をタップで選んでから、その面のモジュールだけつかめる (ほかの面のモジュールは反応しない)
                        // モジュールの見た目全体 (+余白) で判定する
                        val slot = room.moduleAtScreen(pressPos.x, pressPos.y, 18f, onlySelected = true)
                            ?: room.slotAtScreen(pressPos.x, pressPos.y, 45f, { room.moduleAt(it.id) != null })
                        if (slot != null) {
                            draggingModule = true
                            room.draggingModel = false
                            tray.beginDrag(room.moduleAt(slot)!!, slot, placement.origin + c.position)
                        }
                    }
                }
                if (draggingModule) {
                    tray?.drag(placement.origin + c.position)
                    continue
                }
                val dx = delta.x / density
                val dy = delta.y / density
                moved += abs(dx) + abs(dy)

                // 左右の往復を数える。2 回折り返したら (あまり遠くへ動かしていなければ) なでなで
                if (canRub && abs(dx) > 0.3f) {
                    val sign = if (dx > 0f) 1 else -1
                    if (lastSign != 0 && sign != lastSign && stroke > 6f) {
                        reversals++
                        stroke = 0f
                        if (rubbing) onRub?.invoke(c.position)
                    }
                    lastSign = sign
                    stroke += abs(dx)
                    val net = (c.position - pressPos) / density
                    if (!rubbing && reversals >= 2 && abs(net.x) < 120f && abs(net.y) < 80f) {
                        rubbing = true
                        if (held) held = false
                        room.restoreTurn(turn0) // こすっている間に回った分は戻す
                        room.rubStart()
                        onRub?.invoke(c.position)
                    }
                }
                if (rubbing) continue
                if (held && moved < 8f) continue
                if (held) {
                    held = false
                    room.holdEnd()
                }
                room.rotateBy(dx, dy)
            }
        }
    }
}

/**
 * 3D ビュー (操作ヒント + ズームボタン)。Unity 版 AidyApp.View3D。
 * bottomInset / topInset は上下に重ねる UI の分だけヒント・ズームボタン / 吹き出しをずらす。
 */
@Composable
fun View3D(
    room: HomeRoom,
    active: Boolean,
    stateText: String,
    mascot: MascotState,
    hintText: String,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    topInset: Dp = 0.dp,
    tray: ModuleDragTarget? = null,
    placement: ViewPlacement = remember { ViewPlacement() },
    toast: ToastState? = null,
    toastTop: Dp = 52.dp,
) {
    val density = LocalDensity.current
    room.density = density.density
    Box(
        modifier
            .background(Theme.hex(0xf1f5f9))
            .onGloballyPositioned {
                placement.origin = it.positionInWindow()
                val h = it.size.height.toFloat()
                room.setViewInsets(with(density) { topInset.toPx() }, with(density) { bottomInset.toPx() }, h)
            },
    ) {
        RoomScene(room, active, Modifier.fillMaxSize())
        val hearts = remember { HeartsState() }
        Box(Modifier.fillMaxSize().roomTouch(room, placement, tray, LocalPageSwipe.current) { hearts.spawn(it) })
        // なでなでしている指から、ハートがふわっと浮かぶ
        FloatingHearts(hearts)

        // 操作ヒント
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 10.dp)
                .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(IconId.HandPointer, 11.dp, Theme.TextSub)
            Label(hintText, 11f, Theme.TextSub, true)
        }

        // ズームボタン (+ / −)
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = bottomInset + 44.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (zoomIn in listOf(true, false)) {
                Box(
                    Modifier
                        .size(34.dp)
                        .pressable(0.9f) { room.zoomBy(if (zoomIn) 1.25f else 0.8f) }
                        .box(Color.White.copy(alpha = 0.9f), 17.dp, 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (zoomIn) Icon(IconId.Plus, 13.dp, Theme.Slate600)
                    else Box(Modifier.size(12.dp, 3.dp).background(Theme.Slate600, RoundedCornerShape(1.5.dp)))
                }
            }
        }

        if (toast != null) AidyToast(toast, Modifier.align(Alignment.TopCenter).padding(top = toastTop))
    }
}

// ---------------------------------------------------------------- なでなでのハート

internal class Heart(val id: Long, val pos: Offset, val drift: Float, val size: Float)

/** 浮かんでいるハート (位置はビュー内の画素)。 */
class HeartsState {
    internal val list = mutableStateListOf<Heart>()
    private var next = 0L
    private var lastAt = 0L

    fun spawn(pos: Offset) {
        val now = System.currentTimeMillis()
        if (now - lastAt < 120) return // 出しすぎない
        lastAt = now
        list += Heart(next++, pos, Random.nextFloat() * 2f - 1f, 18f + Random.nextFloat() * 10f)
        if (list.size > 12) list.removeAt(0)
    }
}

@Composable
private fun FloatingHearts(state: HeartsState) {
    val d = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        for (h in state.list) {
            key(h.id) {
                val t = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    t.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing))
                    state.list.remove(h)
                }
                val p = t.value
                val sizePx = with(d) { h.size.dp.toPx() }
                // 上へ 90dp ふわっと。左右にゆれながら、最初にぽよんと大きくなって消える
                val x = h.pos.x - sizePx / 2f + with(d) { (h.drift * 24f * p + sin(p * 9f) * 5f).dp.toPx() }
                val y = h.pos.y - sizePx / 2f - with(d) { (90f * p).dp.toPx() }
                val scale = if (p < 0.2f) 0.4f + p / 0.2f * 0.8f else 1.2f - (p - 0.2f) * 0.3f
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size(h.size.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = if (p < 0.6f) 1f else 1f - (p - 0.6f) / 0.4f
                        },
                ) { Icon(IconId.Heart, h.size.dp, Theme.hex(0xf43f5e)) }
            }
        }
    }
}

/** 本体の充電量 (本体との通信ができたら実際の値に置き換える)。 */
const val BatteryLevel = 0.78f

/** スマホのステータスバーのような電池表示: 「78%」+ 電池アイコン。 */
@Composable
fun Battery(level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Label("${(level * 100f).roundToInt()}%", 12f, Theme.Slate600, true)
        Box(Modifier.size(26.dp)) {
            Icon(IconId.Battery, 26.dp, Theme.Slate600)
            // アイコン (24 グリッド) の内側 x:3.1〜18.9, y:8.1〜15.9 を残量に応じて塗る
            val u = 26f / 24f
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (3.6f * u).dp)
                    .width((14.8f * u * level.coerceIn(0f, 1f)).dp)
                    .height((6.6f * u).dp)
                    .background(if (level > 0.2f) Theme.Green else Theme.hex(0xef4444), RoundedCornerShape(1.2.dp)),
            )
        }
    }
}
