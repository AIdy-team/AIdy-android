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
 */
@Composable
fun DressingOverlay(ui: HomeUi, onDone: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var face by remember { mutableStateOf("top") }

    // 向きは AR の付け替えと同じく、回した向きのまま保持する。
    // 自動で向きを変えるのは、開いたとき (正面) と、面・場所のボタンを押したときだけ
    LaunchedEffect(Unit) { ui.room.showFace(null) }

    // 本体の面をタップ: その面のパーツ付け替えへ (向きは変えない)
    DisposableEffect(ui.room) {
        ui.room.onStageFaceTap = { f ->
            if (DressFaces.any { it.first == f }) {
                tab = 0
                face = f
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

        // 下: パーツ／色 シート
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // チュートリアルの「色」の手順: 着せ替えを開いたら、このシート全体をさわれるようにする
                .coachTarget("color")
                .shadow(10.dp, SheetTop)
                .background(Color.White, SheetTop)
                .consumeTaps()
                .height(DressingSheetHeight.dp)
                .padding(top = 12.dp),
        ) {
            DressTabs(tab) { tab = it }
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
                // 面・場所のボタン: その面をこちらへ向ける
                if (tab == 0) PartsTab(ui, face) { face = it; ui.room.showFace(it) } else ColorsTab(ui)
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
        for ((i, name) in listOf("パーツ", "色").withIndex()) {
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
private fun PartsTab(ui: HomeUi, face: String, onFace: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = ModuleConfig.version
    val slots = remember(face) { slotPositionNames(face) }
    // 面ごとに選んでいる取り付け口 (最初は中央か先頭)
    val picked = remember { mutableStateMapOf<String, String>() }
    val selId = picked[face]?.takeIf { id -> slots.any { it.first.id == id } }
        ?: (slots.firstOrNull { it.second == "中央" } ?: slots.firstOrNull())?.first?.id
    val sel = selId?.let { BodyLayout.get(it) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        // 取り付け口 (左・中央・右)
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((slot, pos) in slots) {
                val on = slot.id == selId
                val kind = ModuleConfig.at(slot.id)
                Column(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .semantics { role = Role.RadioButton; selected = on }
                        .pressable(0.96f) {
                            picked[face] = slot.id
                            // 場所を選んでも、その面 (上面・背面など) をこちらへ向ける (onFace が向きを変える)
                            onFace(face)
                        }
                        .background(if (on) Theme.PrimaryLight else Color.White, RoundedCornerShape(12.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) Theme.Primary else Theme.Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Label(pos, 12f, if (on) Theme.Primary else Theme.TextMain, true)
                    Label(kind?.let { ModuleCatalog.name(it) } ?: "空き", 10f, Theme.TextSub, maxLines = 1)
                }
            }
        }

        // 付けられるパーツ (この面に付けられるもの。付けられる場所が限られるものは「中央のみ」など)
        val kinds = remember(face) { ModuleCatalog.forFace(face).filter { it != ModuleKind.Face } }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cur = selId?.let { ModuleConfig.at(it) }
            PartCard("なし", null, Theme.Slate400, null, selected = cur == null, enabled = selId != null) {
                selId?.let { ModuleConfig.detach(it) }
                ui.room.touched()
            }
            for (k in kinds) {
                val fitting = slots.filter { ModuleCatalog.fits(k, it.first) }
                val badge = if (fitting.size < slots.size) fitting.joinToString("・") { it.second } + "のみ" else null
                val fitsHere = sel != null && ModuleCatalog.fits(k, sel)
                PartCard(ModuleCatalog.name(k), ModuleCatalog.icon(k), ModuleCatalog.tint(k), badge, selected = cur == k, enabled = true, dim = !fitsHere) {
                    // 選んでいる場所に付けられなければ、付けられる場所 (空いている所を優先) へ移る
                    val target = if (fitsHere) selId!! else (fitting.firstOrNull { ModuleConfig.at(it.first.id) == null } ?: fitting.firstOrNull())?.first?.id
                    if (target != null) {
                        picked[face] = target
                        if (ModuleConfig.at(target) != k) ModuleConfig.replace(k, target)
                    }
                    ui.room.touched()
                }
            }
        }
    }
}

@Composable
private fun PartCard(
    name: String, icon: IconId?, tint: Color, badge: String?, selected: Boolean, enabled: Boolean, dim: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(88.dp, 104.dp)
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = name + (badge?.let { "（$it）" } ?: "") }
            .alpha(if (!enabled) 0.4f else if (dim && !selected) 0.6f else 1f)
            .pressable(0.95f, enabled = enabled, onClick = onClick)
            .background(if (selected) Theme.PrimaryLight else Color.White, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Theme.Primary else Theme.Border, shape)
            .padding(6.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.padding(top = 6.dp).size(44.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, 24.dp, tint) else StrokeIcon(HomeIconPaths.Close, 20.dp, tint, 2.4f)
            }
            Label(name, 12f, Theme.TextMain, true, maxLines = 1)
        }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopCenter).background(Theme.hex(0xfef3c7), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            ) { Label(badge, 9f, Theme.hex(0x92400e), true, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------- 色

private const val TargetBody = "body"
private const val TargetAllParts = "*"

@Composable
private fun ColorsTab(ui: HomeUi) {
    @Suppress("UNUSED_VARIABLE") val v = BodyColor.version
    @Suppress("UNUSED_VARIABLE") val mv = ModuleConfig.version
    val pattern = BodyColor.pattern
    var target by remember { mutableStateOf(TargetBody) }
    var message by remember { mutableStateOf("") }
    val partSlots = ModuleConfig.current.filter { it.value != ModuleKind.Face }.toList()
    if (target != TargetBody && target != TargetAllParts && partSlots.none { it.first == target }) target = TargetBody

    fun colorOf(t: String, p: ColorPattern = pattern) = when (t) {
        TargetBody -> p.body
        TargetAllParts -> p.parts ?: BodyColor.PartDefault
        else -> p.partColor(t)
    }

    fun apply(p: ColorPattern) {
        BodyColor.pattern = p // 3D にすぐ映る (保存もされる)
        Coach.event("colorSet") // チュートリアル「色を変えてみよう」
        ui.room.touched()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 色を変える対象
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TargetChip("本体", colorOf(TargetBody), target == TargetBody) { target = TargetBody }
            TargetChip("パーツ全部", colorOf(TargetAllParts), target == TargetAllParts) { target = TargetAllParts }
            for ((id, kind) in partSlots) {
                TargetChip("${ModuleCatalog.name(kind)}（${slotName(id)}）", colorOf(id), target == id) { target = id }
            }
        }

        // 12 色のパレット (6 × 2)
        val current = colorOf(target)
        Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in BodyColor.BuiltIn.chunked(6)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (c in row) {
                        Box(Modifier.semantics { contentDescription = BodyColor.hex(c) }) {
                            Swatch(c, (c and 0xffffff) == (current and 0xffffff)) {
                                apply(
                                    when (target) {
                                        TargetBody -> pattern.withBody(c)
                                        TargetAllParts -> pattern.withParts(c)
                                        else -> pattern.withSlot(target, c)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 色パターン (内蔵 10 + 保存 最大 12) と「＋保存」
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(message.ifEmpty { "色パターン（保存したものは長押しで削除）" }, 11f, if (message.isNotEmpty()) Theme.Primary else Theme.TextSub, true, Modifier.weight(1f), maxLines = 1)
            SmallButton("＋保存", Theme.PrimaryLight, Theme.Primary) {
                BodyColor.savePreset(pattern)
                message = "いまの色を保存したよ（${BodyColor.saved.size}/${BodyColor.MaxPresets}）"
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val saved = BodyColor.saved
            for (p in saved) PatternSwatch(p, p == pattern, { apply(p) }, onLongPress = {
                BodyColor.deletePreset(p)
                message = "保存した色パターンを削除したよ"
            })
            if (saved.isNotEmpty()) Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 34.dp).background(Theme.Border))
            for (p in BodyColor.BuiltInPatterns) PatternSwatch(p, p == pattern, { apply(p) })
        }
    }
}
