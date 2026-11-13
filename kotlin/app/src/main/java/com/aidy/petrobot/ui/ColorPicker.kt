package com.aidy.petrobot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aidy.petrobot.data.BodyColor
import com.aidy.petrobot.data.BodyLayout
import com.aidy.petrobot.data.ColorPattern
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleKind
import android.graphics.Color as AColor

/** 0xffRRGGBB → Compose の Color。 */
fun argbColor(c: Int) = Color(c)

/** 虹色の輪 + 中にいまの色。色を選ぶボタンの見た目。 */
@Composable
fun ColorDot(color: Int, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(
            Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
            r,
        )
        drawCircle(Color.White, r * 0.78f)
        drawCircle(argbColor(color), r * 0.64f)
    }
}

/** 色の丸 (見本)。選択中は枠を付ける。 */
@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) Theme.Primary else Theme.Border, CircleShape)
            .padding(if (selected) 5.dp else 3.dp)
            .background(argbColor(color), CircleShape)
            .pressable(0.92f, onClick = onClick),
    )
}

/** パターンの見た目: 左半分が本体の色、右半分がパーツの色 (取り付け口ごとの色があれば小さな点で)。 */
@Composable
fun PatternDot(p: ColorPattern, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(Color.White, r)
        val inner = r * 0.84f
        val tl = Offset(center.x - inner, center.y - inner)
        val sz = Size(inner * 2f, inner * 2f)
        drawArc(argbColor(p.body), 90f, 180f, true, tl, sz)
        drawArc(argbColor(p.parts ?: BodyColor.PartDefault), -90f, 180f, true, tl, sz)
        val extra = p.slots.values.distinct().take(3)
        for ((i, c) in extra.withIndex()) {
            val dc = Offset(center.x + inner * 0.45f, center.y - inner * 0.45f + i * inner * 0.45f)
            drawCircle(Color.White, inner * 0.22f, dc)
            drawCircle(argbColor(c), inner * 0.16f, dc)
        }
        drawCircle(Theme.Border, r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

/** パターンのプリセット (タップで使う。保存したものは長押しで削除)。 */
@Composable
private fun PatternSwatch(p: ColorPattern, selected: Boolean, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(40.dp)
            .border(if (selected) 3.dp else 0.dp, if (selected) Theme.Primary else Color.Transparent, CircleShape)
            .padding(3.dp)
            .pointerInput(p, onLongPress) {
                detectTapGestures(onTap = { onClick() }, onLongPress = onLongPress?.let { f -> { _: Offset -> f() } })
            },
    ) { PatternDot(p, 34.dp) }
}

/** 色を変える対象: 本体・パーツ全部・取り付け口ごとのパーツ。 */
private const val TargetBody = "body"
private const val TargetAllParts = "*"

/**
 * 色のパターンを選ぶダイアログ。上で対象 (本体 / パーツ全部 / 付いているパーツ) を選び、
 * 彩度・明るさの四角と色相のバーでその色を変える。下にパターンのプリセット (保存したもの + 用意したもの)。
 * attached = いま付いているパーツ (取り付け口 → 種類)。onPreview は選んでいる途中のパターン (3D にすぐ映す用)。
 */
@Composable
fun ColorPatternDialog(
    title: String,
    initial: ColorPattern,
    attached: Map<String, ModuleKind>,
    onPreview: (ColorPattern) -> Unit = {},
    onConfirm: (ColorPattern) -> Unit,
    onDismiss: () -> Unit,
) {
    var pattern by remember { mutableStateOf(initial) }
    var target by remember { mutableStateOf(TargetBody) }
    var message by remember { mutableStateOf("") }
    val partSlots = remember(attached) { attached.filter { it.value != ModuleKind.Face }.toList() }

    fun colorOf(t: String, p: ColorPattern = pattern): Int = when (t) {
        TargetBody -> p.body
        TargetAllParts -> p.parts ?: BodyColor.PartDefault
        else -> p.partColor(t)
    }

    val hsv0 = remember { FloatArray(3).also { AColor.colorToHSV(initial.body, it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    val color = AColor.HSVToColor(floatArrayOf(hue, sat, value))

    fun loadHsv(c: Int) {
        val h = FloatArray(3)
        AColor.colorToHSV(c, h)
        // 灰色 (彩度 0) のときは色相を保つ (バーの位置が飛ばないように)
        if (h[1] > 0.001f) hue = h[0]
        sat = h[1]
        value = h[2]
    }

    fun update(p: ColorPattern) {
        pattern = p
        onPreview(p)
    }

    fun changed() {
        val c = AColor.HSVToColor(floatArrayOf(hue, sat, value))
        update(
            when (target) {
                TargetBody -> pattern.withBody(c)
                TargetAllParts -> pattern.withParts(c)
                else -> pattern.withSlot(target, c)
            },
        )
    }

    fun select(t: String) {
        target = t
        loadHsv(colorOf(t))
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .box(Color.White, 22.dp, 12.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 見出し + いまのパターン
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PatternDot(pattern, 36.dp)
                Column(Modifier.weight(1f)) {
                    Label(title, 15f, Theme.TextMain, true)
                    Label("本体 ${BodyColor.hex(pattern.body)} ・ パーツ ${BodyColor.hex(pattern.parts ?: BodyColor.PartDefault)}", 11f, Theme.TextSub)
                }
            }

            // 色を変える対象
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TargetChip("本体", colorOf(TargetBody), target == TargetBody) { select(TargetBody) }
                TargetChip("パーツ全部", colorOf(TargetAllParts), target == TargetAllParts) { select(TargetAllParts) }
                for ((id, kind) in partSlots) {
                    val slot = BodyLayout.get(id)
                    TargetChip(ModuleCatalog.name(kind) + (slot?.let { " (${it.label})" } ?: ""), colorOf(id), target == id) { select(id) }
                }
            }

            // 彩度 (横) ・ 明るさ (縦)
            val pure = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun at(p: Offset) {
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                                changed()
                            }
                            at(down.position)
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                at(c.position)
                                c.consume()
                            }
                        }
                    },
            ) {
                val radius = CornerRadius(12.dp.toPx())
                drawRoundRect(Brush.horizontalGradient(listOf(Color.White, pure)), cornerRadius = radius)
                drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), cornerRadius = radius)
                val c = Offset(sat * size.width, (1f - value) * size.height)
                drawCircle(Color.White, 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.4f), 11.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            }

            // 色相
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun at(p: Offset) {
                                hue = (p.x / size.width).coerceIn(0f, 1f) * 359.9f
                                changed()
                            }
                            at(down.position)
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                at(c.position)
                                c.consume()
                            }
                        }
                    },
            ) {
                val stops = (0..6).map { Color(AColor.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f))) }.toMutableList()
                stops[6] = Color.Red
                drawRoundRect(Brush.horizontalGradient(stops), size = Size(size.width, size.height), cornerRadius = CornerRadius(size.height / 2f))
                val x = hue / 360f * size.width
                drawCircle(Color.White, size.height / 2f - 1.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(3.dp.toPx()))
            }

            // 見本の色
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in BodyColor.BuiltIn) Swatch(c, c == color, {
                    loadHsv(c)
                    changed()
                })
            }

            // パターンのプリセット
            @Suppress("UNUSED_VARIABLE") val v = BodyColor.version
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("パターン (本体 + パーツ)", 11f, Theme.TextSub, true, Modifier.weight(1f))
                SmallButton("＋ 保存", Theme.PrimaryLight, Theme.Primary) {
                    BodyColor.savePreset(pattern)
                    message = "パターンを保存したよ"
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                fun use(p: ColorPattern) {
                    update(p)
                    loadHsv(colorOf(target, p))
                }
                val saved = BodyColor.saved
                for (p in saved) PatternSwatch(p, p == pattern, { use(p) }, onLongPress = {
                    BodyColor.deletePreset(p)
                    message = "保存したパターンを削除したよ"
                })
                if (saved.isNotEmpty()) Box(Modifier.padding(horizontal = 2.dp).size(1.dp, 40.dp).background(Theme.Border))
                for (p in BodyColor.BuiltInPatterns) PatternSwatch(p, p == pattern, { use(p) })
            }
            Label(
                message.ifEmpty { "保存したパターンは長押しで削除" }, 11f, if (message.isNotEmpty()) Theme.Primary else Theme.Slate400,
                modifier = Modifier.fillMaxWidth(), align = TextAlign.Center,
            )

            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("キャンセル", false, Modifier.weight(1f), onDismiss)
                DialogButton("決定", true, Modifier.weight(1f)) { onConfirm(pattern) }
            }
        }
    }
}

@Composable
private fun TargetChip(text: String, color: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(32.dp)
            .pressable(0.95f, onClick = onClick)
            .background(if (selected) Theme.PrimaryLight else Theme.Slate100, RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 0.dp, if (selected) Theme.Primary else Color.Transparent, RoundedCornerShape(16.dp))
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(20.dp).background(argbColor(color), CircleShape).border(1.dp, Theme.Border, CircleShape))
        Label(text, 12f, if (selected) Theme.Primary else Theme.Slate700, true)
    }
}

@Composable
private fun DialogButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .pressable(0.97f, onClick = onClick)
            .background(if (primary) Theme.Primary else Theme.Slate100, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) { Label(text, 13f, if (primary) Color.White else Theme.Slate700, true) }
}
