package com.aidy.petrobot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.data.RoomPref
import com.aidy.petrobot.three.HomeRoom
import com.aidy.petrobot.three.RoomDecor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** デザイン (AIDY ホーム画面・お部屋選択) の線アイコン。24×24 の SVG パス。 */
object HomeIconPaths {
    val House = listOf("M3 11l9-7 9 7", "M5 10v10h14V10", "M10 20v-5h4v5")
    val Shirt = listOf("M8 3L3 6l2 4 2-1v12h10V9l2 1 2-4-5-3c0 2-1.5 3-4 3S8 5 8 3z")
    val Close = listOf("M6 6l12 12", "M18 6L6 18")
    val Check = listOf("M5 12l5 5 9-10")
}

/** SVG パスを線で描くアイコン。 */
@Composable
fun StrokeIcon(paths: List<String>, size: Dp, color: Color, strokeWidth: Float = 2f) {
    val vector = remember(paths, color, strokeWidth) {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(color),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }
    Image(rememberVectorPainter(vector), null, Modifier.size(size))
}

/** ホームの右下に浮かぶ丸ボタン (白・影付き・アイコンのみ)。 */
@Composable
fun HomeRoundButton(label: String, icon: List<String>, size: Dp, iconSize: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = label; role = Role.Button }
            .pressable(0.92f, onClick = onClick)
            .box(Color.White.copy(alpha = 0.94f), size / 2, 5.dp),
        contentAlignment = Alignment.Center,
    ) { StrokeIcon(icon, iconSize, Theme.TextMain) }
}

// ---------------------------------------------------------------- 状態の吹き出し

/**
 * Aidy の頭の横に出す吹き出し (いまの状態)。3D ビューと同じ大きさ・位置に重ねる。
 * 頭の位置は毎フレーム読み直す (読むのは配置の段階だけなので、再構成は起きない)。
 */
@Composable
fun StatusBubble(room: HomeRoom, text: String, active: Boolean, modifier: Modifier = Modifier) {
    val head = remember { mutableStateOf<Offset?>(null) }
    var onLeft by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            head.value = room.headScreen()?.let { Offset(it.x, it.y) }
        }
    }
    if (text.isBlank()) return
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val h = head.value ?: return@layout
                        val gap = 26.dp.toPx()
                        val margin = 8.dp.toPx()
                        val left = h.x + gap + p.width > constraints.maxWidth - margin
                        if (left != onLeft) onLeft = left
                        val x = if (left) h.x - gap - p.width else h.x + gap
                        val y = h.y - p.height
                        p.place(
                            x.coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin)).roundToInt(),
                            y.coerceAtLeast(margin).roundToInt(),
                        )
                    }
                },
        ) {
            Column(horizontalAlignment = if (onLeft) Alignment.End else Alignment.Start) {
                Box(
                    Modifier
                        .widthIn(max = 200.dp)
                        .box(Color.White, 14.dp, 4.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Label(text, 13f, Theme.TextMain, true, maxLines = 3) }
                // しっぽ (頭の方へ向ける)
                Canvas(Modifier.padding(horizontal = 14.dp).size(14.dp, 9.dp)) {
                    val path = Path().apply {
                        if (onLeft) {
                            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
                        } else {
                            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(0f, size.height)
                        }
                        close()
                    }
                    drawPath(path, Color.White)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- カーテンの演出

/** 赤いカーテンが閉じて開く (着せ替えの出入り)。progress: 0 = 開いている, 1 = 閉じている。 */
class CurtainState {
    val progress = Animatable(0f)
    val busy get() = progress.value > 0f || progress.isRunning

    /** 閉じる (約 0.25 秒) → onClosed (背景の切り替え) → 開く (約 0.28 秒)。 */
    suspend fun play(onClosed: () -> Unit) {
        if (progress.isRunning) return
        progress.animateTo(1f, tween(240, easing = EaseCss))
        onClosed()
        withFrameNanos { }
        withFrameNanos { }
        progress.animateTo(0f, tween(280, easing = EaseCss))
    }
}

@Composable
fun CurtainOverlay(state: CurtainState) {
    val t = state.progress.value
    if (t <= 0f) return
    Canvas(
        Modifier
            .fillMaxSize()
            // 動いている間は下の 3D・ボタンを触れなくする
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        val half = size.width / 2f * t
        val fold = 34.dp.toPx()
        val stripes = Brush.horizontalGradient(
            listOf(Theme.hex(0x8a0c22), Theme.hex(0xc8183a), Theme.hex(0xa50f2a), Theme.hex(0x8a0c22)),
            startX = 0f, endX = fold, tileMode = TileMode.Repeated,
        )
        drawRect(stripes, Offset(0f, 0f), androidx.compose.ui.geometry.Size(half, size.height))
        drawRect(stripes, Offset(size.width - half, 0f), androidx.compose.ui.geometry.Size(half, size.height))
        // 金の裾
        val hem = 10.dp.toPx()
        val gold = Theme.hex(0xd4a537)
        drawRect(gold, Offset(0f, size.height - hem), androidx.compose.ui.geometry.Size(half, hem))
        drawRect(gold, Offset(size.width - half, size.height - hem), androidx.compose.ui.geometry.Size(half, hem))
    }
}

// ---------------------------------------------------------------- お部屋をえらぶ

/** お部屋の候補 (カードの小さな絵の色は 3D の部屋に合わせる)。 */
private data class RoomOption(val id: String, val name: String, val sub: String, val wall: Color, val floor: Color, val rug: Color)

private val RoomOptions = listOf(
    RoomOption(RoomDecor.Living, "リビング", "テーブルの上", Theme.hex(0xf3ece2), Theme.hex(0xdcb48a), Theme.hex(0xb9d3ef)),
    RoomOption(RoomDecor.Desk, "自分の部屋", "机の上", Theme.hex(0xe6ecf2), Theme.hex(0xc49a6c), Theme.hex(0x4f7d6a)),
    RoomOption(RoomDecor.Kids, "子ども部屋", "おもちゃのそば", Theme.hex(0xfbe6df), Theme.hex(0xe6c9a0), Theme.hex(0xf7d27a)),
)

/**
 * 「お部屋をえらぶ」シート。カードを選ぶとうしろの 3D のお部屋がすぐ変わり、そのまま保存する。
 * 背景のタップ・下へのスワイプ・× のどれで閉じても、選んだお部屋のまま。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPickerSheet(room: HomeRoom, onClose: () -> Unit) {
    var picked by remember { mutableStateOf(RoomPref.current) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun hide() {
        scope.launch { sheet.hide() }.invokeOnCompletion { onClose() }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.12f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp).size(40.dp, 5.dp).background(Theme.Slate300, RoundedCornerShape(3.dp)))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label("お部屋をえらぶ", 20f, Theme.TextMain, true)
                    Label("えらぶと、うしろのお部屋がすぐ変わるよ", 13f, Theme.TextSub)
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .semantics { contentDescription = "閉じる"; role = Role.Button }
                        .pressable(0.92f) { hide() }
                        .background(Theme.hex(0xf1f3f6), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) { StrokeIcon(HomeIconPaths.Close, 20.dp, Theme.TextMain, 2.2f) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in RoomOptions) RoomCard(r, r.id == picked, Modifier.weight(1f)) {
                    picked = r.id
                    RoomPref.current = r.id // 選んだ時点で保存 (決定ボタンなし)
                    room.setRoom(r.id)
                }
            }
        }
    }
}

@Composable
private fun RoomCard(r: RoomOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .semantics {
                contentDescription = "${r.name}（${r.sub}）"
                this.selected = selected
                role = Role.RadioButton
            }
            .pressable(0.96f, onClick = onClick)
            .background(if (selected) Theme.PrimaryLight else Color.White, shape)
            .border(2.dp, if (selected) Theme.Primary else Theme.Border, shape)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // お部屋の小さな絵 (壁・床・ラグ)
            Box(Modifier.fillMaxWidth().height(110.dp).background(r.floor, RoundedCornerShape(10.dp))) {
                Box(Modifier.fillMaxWidth().height(66.dp).background(r.wall, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)))
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(56.dp, 18.dp).background(r.rug, RoundedCornerShape(9.dp)))
            }
            Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Label(r.name, 14f, Theme.TextMain, true, maxLines = 1)
                Label(r.sub, 12f, Theme.TextSub, maxLines = 1)
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(24.dp)
                    .background(Theme.Primary, RoundedCornerShape(12.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { StrokeIcon(HomeIconPaths.Check, 14.dp, Color.White, 3f) }
        }
    }
}
