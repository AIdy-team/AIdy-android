package com.aidy.petrobot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val EaseCss = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
val PageSnapEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

/** UI.Label 相当 (CSS px = dp、文字は sp)。 */
@Composable
fun Label(
    text: String,
    size: Float,
    color: Color,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        style = TextStyle(lineHeight = (size * 1.3f).sp),
    )
}

/** UI.Box 相当: 角丸の背景 + 影 + 枠線。 */
fun Modifier.box(
    bg: Color,
    radius: Dp,
    shadow: Dp = 0.dp,
    border: Color? = null,
    borderWidth: Dp = 1.dp,
): Modifier {
    val shape = RoundedCornerShape(radius)
    var m = this
    if (shadow > 0.dp) m = m.shadow(shadow, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
    m = m.background(bg, shape)
    if (border != null) m = m.border(borderWidth, border, shape)
    return m
}

/** Clickable 相当 (押している間だけ PressScale に縮む)。 */
@Composable
fun Modifier.pressable(pressScale: Float = 1f, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) pressScale else 1f, tween(150), label = "press")
    return this.scale(s).clickable(source, null, enabled = enabled, onClick = onClick)
}

/** WebDemo の .switch (44x24 トグル)。 */
@Composable
fun AidySwitch(on: Boolean, onChange: (Boolean) -> Unit, scale: Float = 1f, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (on) 1f else 0f, tween(300, easing = EaseCss), label = "switch")
    Box(
        modifier
            .size(44.dp, 24.dp)
            .clickable(remember { MutableInteractionSource() }, null) { onChange(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp, 24.dp)
                .scale(scale)
                .background(lerp(Theme.Slate300, Theme.Primary, t), RoundedCornerShape(12.dp)),
        ) {
            Box(
                Modifier
                    .offset(x = (3 + 20 * t).dp, y = 3.dp)
                    .size(18.dp)
                    .background(Color.White, RoundedCornerShape(9.dp)),
            )
        }
    }
}

/** 状態を自分で持つスイッチ。 */
@Composable
fun AidySwitch(initial: Boolean, scale: Float = 1f, onChange: (Boolean) -> Unit = {}) {
    var on by remember { mutableStateOf(initial) }
    AidySwitch(on, { on = it; onChange(it) }, scale)
}

/** section-title (margin: 16px 0 8px 4px)。 */
@Composable
fun SectionTitle(text: String, first: Boolean) {
    Spacer(Modifier.height(if (first) 16.dp else 6.dp))
    Label(text, 14f, Theme.TextSub, true, Modifier.padding(start = 4.dp))
    Spacer(Modifier.height(8.dp))
}

/** card-item (アイコン + タイトル + 説明 + 右側の部品)。 */
@Composable
fun Card(
    icon: IconId?,
    iconColor: Color,
    iconBg: Color,
    title: String,
    sub: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var m = Modifier
        .fillMaxWidth()
        .box(Color.White, 16.dp, 1.dp, Theme.Slate100)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (icon != null) {
            Box(Modifier.size(42.dp).background(iconBg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, 18.dp, iconColor)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(title, 14f, Theme.TextMain, true)
            Label(sub, 12f, Theme.TextSub)
        }
        trailing()
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * デフォルメ Aidy がしゃべる吹き出し型のお知らせ (「準備OK！」など)。ぽよんと出てきて、時間が来たらすっと消える。
 */
class ToastState {
    var text by mutableStateOf("")
        internal set
    var face by mutableStateOf(AidyFace.Happy)
        internal set
    internal var shownAt by mutableLongStateOf(0L)
    internal var seconds = 2.6f
    internal var serial by mutableIntStateOf(0)
    internal val mascot = MascotState()

    /** seconds <= 0 なら出しっぱなし。 */
    fun show(face: AidyFace, text: String, seconds: Float = 2.6f) {
        this.face = face
        this.text = text
        this.seconds = seconds
        shownAt = System.nanoTime()
        serial++
        mascot.react(face, 1.2f)
    }
}

@Composable
fun AidyToast(state: ToastState, modifier: Modifier = Modifier) {
    var alpha by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        var last = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1e9f
                last = now
                val t = (now - state.shownAt) / 1e9f
                val visible = state.serial > 0 && (state.seconds <= 0f || t <= state.seconds)
                // 出るとき: 0.35s でぽよん (1.08 まで膨らんで戻る) / 消えるとき: 0.25s でフェード
                val target = if (visible) 1f else 0f
                val step = dt / (if (visible) 0.2f else 0.25f)
                alpha = if (alpha < target) minOf(target, alpha + step) else maxOf(target, alpha - step)
                scale = if (visible && t < 0.35f) {
                    val p = t / 0.35f
                    if (p < 0.6f) 0.7f + (1.08f - 0.7f) * (p / 0.6f) else 1.08f + (1f - 1.08f) * ((p - 0.6f) / 0.4f)
                } else 1f
            }
        }
    }
    if (alpha <= 0f) return
    Row(
        modifier
            .alpha(alpha)
            .scale(scale)
            .box(Color.White, 22.dp, 6.dp, Theme.hex(0xe0e7ff), 1.5.dp)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AidyMascot(30.dp, state.face, state = state.mascot)
        Label(state.text, 13f, Theme.TextMain, true)
    }
}

/** 角丸のピル (半透明白 + 影)。 */
fun Modifier.pill(bg: Color = Color.White.copy(alpha = 0.88f), radius: Dp = 16.dp, shadow: Dp = 3.dp, shape: Shape = RoundedCornerShape(radius)) =
    this.box(bg, radius, shadow)

/** タップだけ拾う (下に通さない)。 */
fun Modifier.blockTouches() = this.then(Modifier.clickable(MutableInteractionSource(), null) {})

@Composable
fun Modifier.consumeTaps(): Modifier = this.then(Modifier.clickable(remember { MutableInteractionSource() }, null) {})

@Composable
fun animatedColor(target: Color, ms: Int = 200) = animateColorAsState(target, tween(ms), label = "color").value

/** ページ本体 (縦スクロール、左右 16dp の余白、下は 24dp)。 */
@Composable
fun ScrollPage(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        content = content,
    )
}

/** 角丸の 1 行テキスト入力。password なら入力中の文字を伏せ字にする (合言葉欄など)。 */
@Composable
fun Input(value: String, onChange: (String) -> Unit, placeholder: String, multiline: Boolean, password: Boolean = false) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = !multiline,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = Theme.TextMain, fontSize = 13.sp),
        cursorBrush = SolidColor(Theme.Primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (multiline) 64.dp else 38.dp)
            .box(Theme.Device, 10.dp, border = Theme.Border),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                if (value.isEmpty()) Label(placeholder, 13f, Theme.Slate400)
                inner()
            }
        },
    )
}

/** 角丸の小さいボタン (カード内の「保存」「キャンセル」など)。 */
@Composable
fun SmallButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.pressable(0.95f, onClick = onClick).background(bg, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, 12f, fg, true) }
}
