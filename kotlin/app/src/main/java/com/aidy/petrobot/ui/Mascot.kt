package com.aidy.petrobot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** デフォルメ Aidy の表情。ディスプレイに映る目の形だけが変わる。 */
enum class AidyFace { Normal, Blink, Happy, Sleepy, Surprised, Dizzy }

/**
 * デフォルメ Aidy (角丸ボディ + 黒ディスプレイ + 縦長の目) をフルカラーで描く (Unity 版 AidyMascotArt)。
 * 100x100 グリッド上の SDF を重ね塗りする。小さいサイズでは線を太らせ、側面の刻みを省く。
 */
object AidyMascotArt {
    private class C(var r: Float, var g: Float, var b: Float, var a: Float)

    private val Body = floatArrayOf(1f, 1f, 1f, 1f)
    private val Outline = rgba(0x334155)
    private val Display = rgba(0x0f172a)
    private val Eye = rgba(0xf8fafc)
    private val Cheek = rgba(0xf472b6, 0.8f)

    private fun rgba(rgb: Int, a: Float = 1f) =
        floatArrayOf(((rgb shr 16) and 0xff) / 255f, ((rgb shr 8) and 0xff) / 255f, (rgb and 0xff) / 255f, a)

    private val cache = HashMap<Pair<AidyFace, Int>, ImageBitmap>()

    /** sizePx 四方の画像。uiSizeDp (表示サイズ) で線の太さを調整する。 */
    fun get(face: AidyFace, sizePx: Int, uiSizeDp: Float): ImageBitmap {
        val size = max(12, sizePx)
        synchronized(cache) { cache[face to size]?.let { return it } }
        val bmp = Bitmap.createBitmap(render(face, size, uiSizeDp), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
        synchronized(cache) { cache[face to size] = bmp }
        return bmp
    }

    /** size x size の ARGB (上の行から)。 */
    fun render(face: AidyFace, size: Int, uiSize: Float): IntArray {
        val ss = 4
        val scale = size / 100f
        val stroke = max(4f, 110f / uiSize)
        val line = max(2.8f, 70f / uiSize)
        val details = uiSize >= 20f
        val px = IntArray(size * size)
        val c = C(0f, 0f, 0f, 0f)
        for (y in 0 until size) for (x in 0 until size) {
            var ar = 0f
            var ag = 0f
            var ab = 0f
            var aa = 0f // 乗算済みアルファで平均する
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val p = V2((x + (sx + 0.5f) / ss) / scale, (y + (sy + 0.5f) / ss) / scale)
                val k = scale * ss
                c.r = 0f; c.g = 0f; c.b = 0f; c.a = 0f
                val outer = Sdf.box(p, 6f, 6f, 94f, 94f, 15f)
                over(c, Body, outer, k)
                over(c, Outline, max(outer, -(outer + stroke)), k)
                over(c, Outline, Sdf.circle(p, 50f, 18f, max(3.2f, line * 1.1f)), k)
                over(c, Outline, Sdf.seg(p, V2(46f, 81f), V2(54f, 81f), line * 1.1f), k)
                if (details) {
                    for (sx2 in floatArrayOf(13f, 87f)) {
                        over(c, Outline, Sdf.seg(p, V2(sx2, 35f), V2(sx2, 41f), 2.4f), k)
                        over(c, Outline, Sdf.seg(p, V2(sx2, 59f), V2(sx2, 65f), 2.4f), k)
                    }
                }
                over(c, Display, Sdf.box(p, 20f, 29f, 80f, 71f, 8f), k)
                over(c, Eye, eyes(face, p, line), k)
                if (face == AidyFace.Happy)
                    over(c, Cheek, Sdf.u(Sdf.circle(p, 29f, 59f, 3.6f), Sdf.circle(p, 71f, 59f, 3.6f)), k)
                ar += c.r * c.a; ag += c.g * c.a; ab += c.b * c.a; aa += c.a
            }
            val n = (ss * ss).toFloat()
            aa /= n
            px[y * size + x] = if (aa > 0f) argb(ar / n / aa, ag / n / aa, ab / n / aa, aa) else 0
        }
        return px
    }

    private fun argb(r: Float, g: Float, b: Float, a: Float) =
        ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or (b * 255f).roundToInt().coerceIn(0, 255)

    fun eyes(face: AidyFace, p: V2, line: Float): Float = with(Sdf) {
        when (face) {
            AidyFace.Blink -> u(seg(p, v(36.5f, 51f), v(43.5f, 51f), line), seg(p, v(56.5f, 51f), v(63.5f, 51f), line))
            AidyFace.Happy -> u(polyline(p, line, v(35.5f, 53f), v(40f, 47f), v(44.5f, 53f)), polyline(p, line, v(55.5f, 53f), v(60f, 47f), v(64.5f, 53f)))
            AidyFace.Sleepy -> u(polyline(p, line, v(35.5f, 49f), v(40f, 53f), v(44.5f, 49f)), polyline(p, line, v(55.5f, 49f), v(60f, 53f), v(64.5f, 49f)))
            AidyFace.Surprised -> u(circle(p, 40f, 50f, 4.8f), circle(p, 60f, 50f, 4.8f))
            AidyFace.Dizzy -> u(spiral(p, 40f, 50f, 7.5f, line * 0.8f), spiral(p, 60f, 50f, 7.5f, line * 0.8f))
            else -> u(ellipse(p, 40f, 50f, 3.8f, 7.2f), ellipse(p, 60f, 50f, 3.8f, 7.2f))
        }
    }

    /** ぐるぐる目 (アルキメデスの渦巻き)。 */
    private fun spiral(p: V2, cx: Float, cy: Float, maxR: Float, line: Float): Float {
        val d = p - V2(cx, cy)
        val r = d.length
        var th = atan2(d.y, d.x)
        if (th < 0f) th += (PI * 2).toFloat()
        val a = maxR / (PI.toFloat() * 2f * 1.75f)
        var best = Float.MAX_VALUE
        for (k in 0 until 3) {
            val rr = a * (th + PI.toFloat() * 2f * k)
            if (rr > maxR) break
            best = min(best, abs(r - rr))
        }
        return best - line * 0.5f
    }

    /**
     * 本体の顔モニター用の表情 (w x h、上の行から ARGB)。デフォルメ Aidy のディスプレイ部分と同じ目の形を bg の上に fg で描く。
     */
    fun renderScreen(face: AidyFace, w: Int, h: Int, bg: Int, fg: Int): Bitmap {
        val ss = 3
        val px = IntArray(w * h)
        val unitsPerPx = 60f / w // ディスプレイ部分 x:20..80, y:29..71 に対応させる
        val vScale = 42f / h
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val p = V2(20f + (x + (sx + 0.5f) / ss) * unitsPerPx, 29f + (y + (sy + 0.5f) / ss) * vScale)
                a += Sdf.clamp01(0.5f - eyes(face, p, 3.4f) / unitsPerPx)
            }
            a /= ss * ss
            px[y * w + x] = lerpArgb(bg, fg, a)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun lerpArgb(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xff) + (((b shr s) and 0xff) - ((a shr s) and 0xff)) * t).roundToInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun over(under: C, o: FloatArray, d: Float, aa: Float) {
        val a = Sdf.clamp01(0.5f - d * aa) * o[3]
        if (a <= 0f) return
        val outA = a + under.a * (1f - a)
        under.r = (o[0] * a + under.r * under.a * (1f - a)) / outA
        under.g = (o[1] * a + under.g * under.a * (1f - a)) / outA
        under.b = (o[2] * a + under.b * under.a * (1f - a)) / outA
        under.a = outA
    }
}

/** 一時的な表情の変更 (React) を外から起こすための状態。 */
class MascotState {
    internal var reactFace by mutableStateOf(AidyFace.Happy)
    internal var reactAt by mutableLongStateOf(0L)
    internal var reactMs = 1200L

    /** 一時的に表情を変えてぴょんと跳ねる。 */
    fun react(face: AidyFace, durationSec: Float = 1.2f) {
        reactFace = face
        reactMs = (durationSec * 1000).toLong()
        reactAt = System.nanoTime()
    }
}

/**
 * UI のワンポイントとして置くデフォルメ Aidy。まばたき・ふわふわ揺れ、タップで喜んで跳ねる。
 * レイアウト上は size x size を占有し、絵だけを動かす。
 */
@Composable
fun AidyMascot(
    size: Dp,
    face: AidyFace,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    interactive: Boolean = false,
    state: MascotState = remember { MascotState() },
) {
    val density = LocalDensity.current
    val px = with(density) { ceil(size.toPx()).toInt() }
    val currentFace by rememberUpdatedState(face)
    var shown by remember { mutableStateOf(face) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val phase = remember { Random.nextFloat() * 6.28f }

    if (!animate) {
        shown = if (System.nanoTime() - state.reactAt < state.reactMs * 1_000_000) state.reactFace else face
    }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            var blinkAt = 1f + Random.nextFloat() * 3f
            var blinkUntil = 0f
            while (true) {
                withFrameNanos { now ->
                    val t = (now - start) / 1e9f
                    val sinceReact = (now - state.reactAt) / 1e6f
                    var want = if (state.reactAt != 0L && sinceReact < state.reactMs) state.reactFace else currentFace
                    if (want == AidyFace.Normal) {
                        if (t >= blinkAt) {
                            blinkUntil = t + 0.12f
                            blinkAt = t + 2.5f + Random.nextFloat() * 3f
                        }
                        if (t < blinkUntil) want = AidyFace.Blink
                    }
                    if (want != shown) shown = want
                    var y = sin(t * 2.2f + phase) * 0.03f
                    val hop = if (state.reactAt != 0L) sinceReact / 450f else 1f
                    if (hop in 0f..1f) y += sin(hop * PI.toFloat()) * 0.18f
                    offsetY = y
                }
            }
        }
    }
    val bmp = remember(shown, px) { AidyMascotArt.get(shown, px, size.value) }
    val mod = if (interactive) modifier.clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
    ) { state.react(AidyFace.Happy) } else modifier
    Box(mod.size(size)) {
        Image(bmp, null, Modifier.size(size).offset(y = (-offsetY * size.value).dp))
    }
}
