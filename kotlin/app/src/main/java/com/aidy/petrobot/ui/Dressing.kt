package com.aidy.petrobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.aidy.petrobot.three.ModulePreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import com.aidy.petrobot.data.BodyColor
import com.aidy.petrobot.data.BodyLayout
import com.aidy.petrobot.data.BodySlot
import com.aidy.petrobot.data.ColorPattern
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleConfig
import com.aidy.petrobot.data.ModuleKind

/** 着せ替えのシートの高さ (ナビゲーションバーの分は別)。3D は残りの真ん中に写す。 */
const val DressingSheetHeight = 330f

/** パーツのカテゴリ (面) と表示名。 */
private val DressFaces = listOf("top" to "上面", "right" to "右面", "left" to "左面", "back" to "背面", "bottom" to "下面")

private fun faceName(face: String) = DressFaces.firstOrNull { it.first == face }?.second ?: BodyLayout.faceLabel(face)

/**
 * 面の取り付け口に「左・中央・右」(縦に並ぶ面は「上・中央・下」) の名前を付ける。
 * body_v2_layout.json の位置を、その面を外から見た向きに直して並べる。
 */
fun slotPositionNames(face: String): List<Pair<BodySlot, String>> {
    val slots = BodyLayout.onFace(face).filter { !it.isMonitor }
    if (slots.isEmpty()) return emptyList()
    if (slots.size == 1) return listOf(slots[0] to "中央")
    val pts = slots.map { BodyLayout.project(face, it.position) }
    val spreadX = pts.maxOf { it.first } - pts.minOf { it.first }
    val spreadY = pts.maxOf { it.second } - pts.minOf { it.second }
    val horizontal = spreadX >= spreadY
    val sorted = slots.zip(pts).sortedBy { (_, p) -> if (horizontal) p.first else -p.second }.map { it.first }
    val names = when (sorted.size) {
        2 -> if (horizontal) listOf("左", "右") else listOf("上", "下")
        3 -> if (horizontal) listOf("左", "中央", "右") else listOf("上", "中央", "下")
        else -> sorted.indices.map { "${it + 1}" }
    }
    return sorted.zip(names)
}

/** 取り付け口の表示名 (例: 上面・中央)。 */
private fun slotName(slotId: String): String {
    val s = BodyLayout.get(slotId) ?: return slotId
    if (s.isMonitor) return "正面"
    val pos = slotPositionNames(s.face).firstOrNull { it.first.id == slotId }?.second
    return faceName(s.face) + (pos?.let { "・$it" } ?: "")
}

/**
 * 着せ替え画面の UI (きせかえルームの 3D の上に重ねる): 右上の「できた！」と、下の「パーツ／色」シート。
 * パーツは AR の付け替えと同じく、シートのパーツを 3D の光っている丸へドラッグ&ドロップで付ける。
 * 外すときは、本体のパーツをシートの上か Aidy の外へドラッグする。タップだけでは付け外ししない。
 */
@Composable
fun DressingOverlay(ui: HomeUi, tray: ModuleTrayState, onDone: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var face by remember { mutableStateOf("top") }

    // 向きは AR の付け替えと同じく、回した向きのまま保持する。
    // 自動で向きを変えるのは、開いたとき (正面) と、面のボタンを押したときだけ
    LaunchedEffect(Unit) { ui.room.showFace(null) }
    // パーツのタブでは選んでいる面の丸を光らせる。カラーのタブでは消す
    LaunchedEffect(tab) { ui.room.selectFace(if (tab == 0) face else null, rotate = false) }

    // 本体の面をタップ: その面のパーツ付け替えへ (向きは変えない)
    DisposableEffect(ui.room) {
        ui.room.onStageFaceTap = { f ->
            if (DressFaces.any { it.first == f }) {
                tab = 0
                face = f
                ui.room.selectFace(f, rotate = false)
            }
        }
        onDispose { ui.room.onStageFaceTap = {} }
    }

    Box(Modifier.fillMaxSize()) {
        // 右上: できた！
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .height(44.dp)
                .semantics { role = Role.Button; contentDescription = "できた！ ホームへ戻る" }
                .pressable(0.95f, onClick = onDone)
                .box(Theme.Primary, 22.dp, 5.dp)
                .padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StrokeIcon(HomeIconPaths.Check, 18.dp, Color.White, 3f)
            Label("できた！", 15f, Color.White, true)
        }

        // 下: パーツ／色 シート (本体のパーツをここへドラッグすると取り外し)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // チュートリアルの「色」の手順: 着せ替えを開いたら、このシート全体をさわれるようにする
                .coachTarget("color")
                .onGloballyPositioned { tray.trayBounds = it.boundsInWindow() }
                .shadow(10.dp, SheetTop)
                .background(Color.White, SheetTop)
                .consumeTaps()
                .height(DressingSheetHeight.dp)
                .padding(top = 12.dp),
        ) {
            DressTabs(tab) { tab = it }
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
                // 面のボタン: その面をこちらへ向けて、丸を光らせる
                if (tab == 0) PartsTab(tray, face) {
                    face = it
                    ui.room.selectFace(it, rotate = true)
                } else ColorsTab(ui)
            }
        }
    }
}

private val SheetTop = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

@Composable
private fun DressTabs(tab: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(44.dp)
            .background(Theme.Slate100, RoundedCornerShape(22.dp))
            .padding(4.dp),
    ) {
        for ((i, name) in listOf("パーツ", "カラー").withIndex()) {
            val on = i == tab
            val bg = animatedColor(if (on) Color.White else Theme.Slate100)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .semantics { role = Role.Tab; selected = on }
                    .pressable { onSelect(i) }
                    .then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(18.dp)) else Modifier)
                    .background(bg, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Label(name, 14f, if (on) Theme.Primary else Theme.TextSub, true) }
        }
    }
}

// ---------------------------------------------------------------- パーツ

@Composable
private fun PartsTab(tray: ModuleTrayState, face: String, onFace: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = ModuleConfig.version
    val slots = remember(face) { slotPositionNames(face) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 面のカテゴリ
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, name) in DressFaces) {
                val on = id == face
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .semantics { role = Role.Tab; selected = on }
                        .pressable(0.95f) { onFace(id) }
                        .background(if (on) Theme.Primary else Theme.Slate100, RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center,
                ) { Label(name, 13f, if (on) Color.White else Theme.Slate700, true) }
            }
        }

        // この面に付けられるパーツ (付けられる場所が限られるものは「中央のみ」など)。ドラッグして付ける
        val kinds = remember(face) { ModuleCatalog.forFace(face).filter { it != ModuleKind.Face } }
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in kinds) {
                val fitting = slots.filter { ModuleCatalog.fits(k, it.first) }
                val badge = if (fitting.size < slots.size) fitting.joinToString("・") { it.second } + "のみ" else null
                val count = slots.count { ModuleConfig.at(it.first.id) == k }
                // つかんだら、そのパーツを付ける面 (いま選んでいる面) をこちらへ向ける (シルクハットなら上面)
                DragPartCard(tray, k, badge, count, Modifier.weight(1f)) { onFace(face) }
            }
            // 3 つより少ない面は左寄せのまま幅をそろえる
            repeat((3 - kinds.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
        }
    }
}

/** ドラッグで付けるパーツのカード。count = この面に付いている数 (2 つ以上なら名前に ×n)。onGrab = つかんだとき。 */
@Composable
private fun DragPartCard(tray: ModuleTrayState, kind: ModuleKind, badge: String?, count: Int, modifier: Modifier, onGrab: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val name = ModuleCatalog.name(kind)
    val tint = ModuleCatalog.tint(kind)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dragging = tray.dragKind == kind && tray.dragFrom == null
    // 面を切り替えても、つかんだときは最新の面へ向ける (pointerInput は kind が同じだと作り直されないため)
    val grab by rememberUpdatedState(onGrab)
    Box(
        modifier
            .height(100.dp)
            .semantics { contentDescription = "$name" + (badge?.let { "（$it）" } ?: "") + "。押さえたまま本体の丸へ引っぱって付ける" }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(kind) {
                var last = Offset.Zero
                detectDragGestures(
                    onDragStart = { p ->
                        last = origin + p
                        grab()
                        tray.beginDrag(kind, null, last)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        last = origin + change.position
                        tray.drag(last)
                    },
                    onDragEnd = { tray.endDrag(last) },
                    onDragCancel = { tray.endDrag(last) },
                )
            }
            .alpha(if (dragging) 0.4f else 1f)
            .background(Color.White, shape)
            .border(1.dp, Theme.Border, shape)
            .padding(6.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.padding(top = 8.dp).size(44.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(ModuleCatalog.icon(kind), 24.dp, tint)
            }
            Label(if (count > 1) "$name×$count" else name, 12f, Theme.TextMain, true, maxLines = 1)
        }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopCenter).background(Theme.hex(0xfef3c7), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            ) { Label(badge, 9f, Theme.hex(0x92400e), true, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------- カラー

private const val TargetBody = "body"
private const val TargetAllParts = "*"

/**
 * カラーの画面: 上で色を変える対象 (本体 / パーツ全部 / 付いている各パーツ) を、画像つきのカードで選び、
 * 下の 12 色から選ぶ。パーツの画像はいまの色で描くので、どのパーツが何色になるかがすぐわかる。
 */
@Composable
private fun ColorsTab(ui: HomeUi) {
    @Suppress("UNUSED_VARIABLE") val v = BodyColor.version
    @Suppress("UNUSED_VARIABLE") val mv = ModuleConfig.version
    val pattern = BodyColor.pattern
    var target by remember { mutableStateOf(TargetBody) }
    val partSlots = ModuleConfig.current.filter { it.value != ModuleKind.Face }.toList()
    if (target != TargetBody && target != TargetAllParts && partSlots.none { it.first == target }) target = TargetBody

    fun colorOf(t: String) = when (t) {
        TargetBody -> pattern.body
        TargetAllParts -> pattern.parts ?: BodyColor.PartDefault
        else -> pattern.partColor(t)
    }

    fun pick(c: Int) {
        BodyColor.pattern = when (target) { // 3D にすぐ映る (保存もされる)
            TargetBody -> pattern.withBody(c)
            TargetAllParts -> pattern.withParts(c)
            else -> pattern.withSlot(target, c)
        }
        Coach.event("colorSet") // チュートリアル「色を変えてみよう」
        ui.room.touched()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 色を変える対象 (画像つき)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorTargetCard("本体", null, target == TargetBody, { BodyPreview(colorOf(TargetBody), 56.dp) }) { target = TargetBody }
            ColorTargetCard("パーツ全部", null, target == TargetAllParts, { AllPartsPreview(partSlots.map { it.second }.distinct(), colorOf(TargetAllParts), 56.dp) }) {
                target = TargetAllParts
            }
            for ((id, kind) in partSlots) {
                ColorTargetCard(ModuleCatalog.name(kind), slotName(id), target == id, { PartColorPreview(kind, colorOf(id), 56.dp) }) { target = id }
            }
        }

        // 12 色のパレット (6 × 2)
        val current = colorOf(target)
        Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in BodyColor.BuiltIn.chunked(6)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (c in row) {
                        Box(Modifier.semantics { contentDescription = BodyColor.hex(c) }) {
                            Swatch(c, (c and 0xffffff) == (current and 0xffffff), size = 46.dp) { pick(c) }
                        }
                    }
                }
            }
        }
    }
}

/** 色を変える対象のカード (画像 + 名前 + 場所)。選んでいるものは青枠。 */
@Composable
private fun ColorTargetCard(name: String, place: String?, selected: Boolean, image: @Composable () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(84.dp)
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = name + (place?.let { "（$it）" } ?: "") }
            .pressable(0.95f, onClick = onClick)
            .background(if (selected) Theme.PrimaryLight else Color.White, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Theme.Primary else Theme.Border, shape)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        image()
        Label(name, 12f, if (selected) Theme.Primary else Theme.TextMain, true, maxLines = 1)
        Label(place ?: " ", 10f, Theme.TextSub, maxLines = 1)
    }
}

/** パーツの画像 (いまの色で描く)。描けるまではアイコン。 */
@Composable
private fun PartColorPreview(kind: ModuleKind, color: Int, size: Dp) {
    val image by produceState<ImageBitmap?>(null, kind, color) {
        value = withContext(Dispatchers.Default) { ModulePreview.render(kind, 0f, tint = color).asImageBitmap() }
    }
    Box(Modifier.size(size).clip(CircleShape).background(Color.White).border(2.dp, argbColor(color), CircleShape), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize())
        else Icon(ModuleCatalog.icon(kind), size * 0.45f, argbColor(color))
    }
}

/** 本体の画像: 本体の色の立方体 (上面は明るく、右面は暗く) と顔の画面。 */
@Composable
private fun BodyPreview(color: Int, size: Dp) {
    val base = argbColor(color)
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val s = w * 0.56f // 正面の一辺
        val d = w * 0.18f // 奥行き
        val left = (w - s - d) / 2f
        val top = (w - s - d) / 2f + d
        // 上面・右面・正面
        drawPath(Path().apply { moveTo(left, top); lineTo(left + d, top - d); lineTo(left + s + d, top - d); lineTo(left + s, top); close() }, lerp(base, Color.White, 0.35f))
        drawPath(Path().apply { moveTo(left + s, top); lineTo(left + s + d, top - d); lineTo(left + s + d, top + s - d); lineTo(left + s, top + s); close() }, lerp(base, Color.Black, 0.25f))
        drawRoundRect(base, Offset(left, top), Size(s, s), CornerRadius(s * 0.08f))
        // 顔の画面と目
        val sw = s * 0.62f
        val sh = s * 0.4f
        val sx = left + (s - sw) / 2f
        val sy = top + s * 0.28f
        drawRoundRect(Color(0xff101317), Offset(sx, sy), Size(sw, sh), CornerRadius(s * 0.05f))
        for (ex in listOf(0.33f, 0.67f)) drawRoundRect(Color.White, Offset(sx + sw * ex - s * 0.04f, sy + sh * 0.25f), Size(s * 0.08f, sh * 0.5f), CornerRadius(s * 0.04f))
    }
}

/** パーツ全部の画像: 付いているパーツのアイコンを、パーツの色の丸の上に並べる。 */
@Composable
private fun AllPartsPreview(kinds: List<ModuleKind>, color: Int, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(argbColor(color).copy(alpha = 0.25f)).border(2.dp, argbColor(color), CircleShape), contentAlignment = Alignment.Center) {
        val shown = kinds.take(3).ifEmpty { listOf(ModuleKind.Ear) }
        Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
            for (k in shown) Icon(ModuleCatalog.icon(k), size * 0.32f, lerp(argbColor(color), Color.Black, 0.35f))
        }
    }
}
