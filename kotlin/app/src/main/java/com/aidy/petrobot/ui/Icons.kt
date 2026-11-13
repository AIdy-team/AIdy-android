package com.aidy.petrobot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** WebDemo で使っている Font Awesome アイコンに対応する ID。 */
enum class IconId {
    Gear, ChevronLeft, ChevronRight, House, Gamepad, Camera, Sliders, Brain, Check, XMark,
    Paw, Cubes, Heart, Snowflake, Lightbulb, Plug, Video, Microchip, Plus, Crosshairs,
    HandPointer, Circle, Wifi, MapPin, Aidy, Book, Ear, Tail, Leg, Hand, Flashlight, Battery, Hat
}

/** 2 次元ベクトル (SDF 用)。 */
data class V2(val x: Float, val y: Float) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(s: Float) = V2(x * s, y * s)
    infix fun dot(o: V2) = x * o.x + y * o.y
    val length get() = sqrt(x * x + y * y)
}

/** SDF プリミティブ (Unity 版 AidyIcons と同じ。y は下向き)。 */
object Sdf {
    fun v(x: Float, y: Float) = V2(x, y)
    fun u(a: Float, b: Float) = min(a, b)
    fun sub(a: Float, b: Float) = max(a, -b)
    fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    fun rot(v: V2, deg: Float): V2 {
        val a = Math.toRadians(deg.toDouble()).toFloat()
        val c = cos(a)
        val s = sin(a)
        return V2(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    fun ellipse(p: V2, cx: Float, cy: Float, rx: Float, ry: Float) =
        (V2((p.x - cx) / rx, (p.y - cy) / ry).length - 1f) * min(rx, ry)

    fun circle(p: V2, cx: Float, cy: Float, r: Float) = (p - V2(cx, cy)).length - r

    fun seg(p: V2, a: V2, b: V2, width: Float): Float {
        val pa = p - a
        val ba = b - a
        val h = clamp01((pa dot ba) / (ba dot ba))
        return (pa - ba * h).length - width * 0.5f
    }

    fun polyline(p: V2, width: Float, vararg pts: V2): Float {
        var d = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) d = u(d, seg(p, pts[i], pts[i + 1], width))
        return d
    }

    fun box(p: V2, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Float {
        val c = V2((x0 + x1) * 0.5f, (y0 + y1) * 0.5f)
        return roundBox(p - c, V2((x1 - x0) * 0.5f, (y1 - y0) * 0.5f), r)
    }

    fun rotBox(p: V2, c: V2, half: V2, angle: Float, r: Float): Float {
        val q = p - c
        val cs = cos(-angle)
        val sn = sin(-angle)
        return roundBox(V2(q.x * cs - q.y * sn, q.x * sn + q.y * cs), half, r)
    }

    fun roundBox(p: V2, half: V2, r: Float): Float {
        val qx = abs(p.x) - half.x + r
        val qy = abs(p.y) - half.y + r
        return V2(max(qx, 0f), max(qy, 0f)).length + min(max(qx, qy), 0f) - r
    }

    fun poly(p: V2, vararg v: V2): Float {
        var d = (p - v[0]) dot (p - v[0])
        var s = 1f
        var j = v.size - 1
        for (i in v.indices) {
            val e = v[j] - v[i]
            val w = p - v[i]
            val b = w - e * clamp01((w dot e) / (e dot e))
            d = min(d, b dot b)
            val c1 = p.y >= v[i].y
            val c2 = p.y < v[j].y
            val c3 = e.x * w.y > e.y * w.x
            if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s
            j = i
        }
        return s * sqrt(d)
    }
}

/**
 * アイコンを 24x24 グリッド上の SDF 図形の合成で描き、白抜き画像として生成する (ColorFilter で着色)。
 */
object AidyIcons {
    private val cache = HashMap<Pair<IconId, Int>, ImageBitmap>()

    /** sizePx x sizePx の白いアイコン (アルファでかたち)。 */
    /** 作り済みならその画像、なければ null (作るのは get)。 */
    fun cached(id: IconId, sizePx: Int): ImageBitmap? = synchronized(cache) { cache[id to max(8, sizePx)] }

    fun get(id: IconId, sizePx: Int): ImageBitmap {
        val size = max(8, sizePx)
        synchronized(cache) { cache[id to size]?.let { return it } }
        val scale = size / 24f
        val px = IntArray(size * size)
        // 画素の中心で距離を測り、境界から ±0.5px をなめらかにぼかす (距離場なので 1 回で十分きれい。以前の 4x4 より 16 倍速い)
        for (y in 0 until size) for (x in 0 until size) {
            val p = V2((x + 0.5f) / scale, (y + 0.5f) / scale)
            val a = Sdf.clamp01(0.5f - sdf(id, p) * scale)
            px[y * size + x] = ((a * 255f).roundToInt() shl 24) or 0xffffff
        }
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
        synchronized(cache) { cache[id to size] = bmp }
        return bmp
    }

    private fun sdf(id: IconId, p: V2): Float = with(Sdf) {
        when (id) {
            IconId.Gear -> {
                var d = circle(p, 12f, 12f, 7.2f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0).toFloat()
                    val c = V2(12 + cos(a) * 9f, 12 + sin(a) * 9f)
                    d = u(d, rotBox(p, c, V2(2.0f, 1.9f), a, 0.6f))
                }
                sub(d, circle(p, 12f, 12f, 3.1f))
            }
            IconId.ChevronLeft -> polyline(p, 3.2f, v(15f, 4.5f), v(7.5f, 12f), v(15f, 19.5f))
            IconId.ChevronRight -> polyline(p, 3.2f, v(9f, 4.5f), v(16.5f, 12f), v(9f, 19.5f))
            IconId.House -> sub(
                poly(p, v(12f, 2.8f), v(22.5f, 12.2f), v(19.5f, 12.2f), v(19.5f, 21f), v(4.5f, 21f), v(4.5f, 12.2f), v(1.5f, 12.2f)),
                box(p, 10f, 14.5f, 14f, 22f, 1f),
            )
            IconId.Gamepad -> {
                var d = box(p, 1.5f, 7f, 22.5f, 18.5f, 5.2f)
                d = sub(d, u(box(p, 5.2f, 11.7f, 9.8f, 13.3f, 0.4f), box(p, 6.7f, 10.2f, 8.3f, 14.8f, 0.4f)))
                d = sub(d, circle(p, 15.8f, 11.3f, 1.35f))
                sub(d, circle(p, 18.6f, 13.9f, 1.35f))
            }
            IconId.Camera -> {
                var d = u(box(p, 2f, 7f, 22f, 20.5f, 2.6f), poly(p, v(7.6f, 7.5f), v(9.3f, 4f), v(14.7f, 4f), v(16.4f, 7.5f)))
                d = sub(d, circle(p, 12f, 13.6f, 4.9f))
                u(d, circle(p, 12f, 13.6f, 3.0f))
            }
            IconId.Sliders -> {
                var d = seg(p, v(3f, 6f), v(21f, 6f), 2f)
                d = u(d, seg(p, v(3f, 12f), v(21f, 12f), 2f))
                d = u(d, seg(p, v(3f, 18f), v(21f, 18f), 2f))
                d = u(d, circle(p, 15f, 6f, 2.7f))
                d = u(d, circle(p, 8f, 12f, 2.7f))
                u(d, circle(p, 17f, 18f, 2.7f))
            }
            IconId.Brain -> {
                var d = circle(p, 8.2f, 8.5f, 4.4f)
                d = u(d, circle(p, 15.8f, 8.5f, 4.4f))
                d = u(d, circle(p, 6.8f, 14.6f, 4.4f))
                d = u(d, circle(p, 17.2f, 14.6f, 4.4f))
                d = u(d, circle(p, 12f, 7f, 4f))
                d = u(d, circle(p, 12f, 17f, 3.8f))
                d = u(d, circle(p, 9.5f, 18f, 3f))
                d = u(d, circle(p, 14.5f, 18f, 3f))
                sub(d, seg(p, v(12f, 3f), v(12f, 21f), 1.3f))
            }
            IconId.Check -> polyline(p, 3f, v(4.5f, 12.5f), v(9.5f, 17.5f), v(19.5f, 7f))
            IconId.XMark -> u(seg(p, v(6f, 6f), v(18f, 18f), 3f), seg(p, v(18f, 6f), v(6f, 18f), 3f))
            IconId.Paw -> {
                var d = u(circle(p, 12f, 16.8f, 4.4f), u(circle(p, 8.8f, 18.2f, 3f), circle(p, 15.2f, 18.2f, 3f)))
                d = u(d, circle(p, 5.4f, 10.8f, 2.4f))
                d = u(d, circle(p, 9.4f, 6.4f, 2.5f))
                d = u(d, circle(p, 14.6f, 6.4f, 2.5f))
                u(d, circle(p, 18.6f, 10.8f, 2.4f))
            }
            IconId.Cubes -> u(box(p, 2.5f, 12.5f, 11f, 21f, 1.4f), u(box(p, 13f, 12.5f, 21.5f, 21f, 1.4f), box(p, 7.75f, 3f, 16.25f, 11.5f, 1.4f)))
            IconId.Heart -> u(circle(p, 8.3f, 9f, 4.9f), u(circle(p, 15.7f, 9f, 4.9f), poly(p, v(3.85f, 11.2f), v(20.15f, 11.2f), v(12f, 20.6f))))
            IconId.Snowflake -> {
                val c = v(12f, 12f)
                var d = Float.MAX_VALUE
                for (k in 0 until 6) {
                    val a = Math.toRadians(k * 60.0 + 90.0).toFloat()
                    val uu = V2(cos(a), sin(a))
                    d = u(d, seg(p, c, c + uu * 9.5f, 2f))
                    val q = c + uu * 6.3f
                    d = u(d, seg(p, q, q + rot(uu, 45f) * 3f, 1.8f))
                    d = u(d, seg(p, q, q + rot(uu, -45f) * 3f, 1.8f))
                }
                d
            }
            IconId.Lightbulb -> u(circle(p, 12f, 9.3f, 6.6f), u(poly(p, v(7.6f, 12.5f), v(16.4f, 12.5f), v(14.8f, 17f), v(9.2f, 17f)), box(p, 9f, 18.2f, 15f, 21.5f, 1.2f)))
            IconId.Plug -> {
                var d = box(p, 5.5f, 8f, 18.5f, 15.5f, 3f)
                d = u(d, box(p, 10.4f, 14f, 13.6f, 21.5f, 0.6f))
                d = u(d, box(p, 7.8f, 2.5f, 10.2f, 8.5f, 1f))
                u(d, box(p, 13.8f, 2.5f, 16.2f, 8.5f, 1f))
            }
            IconId.Video -> u(box(p, 1.5f, 6f, 15.5f, 18f, 2.6f), poly(p, v(16.8f, 10.2f), v(22.5f, 6.8f), v(22.5f, 17.2f), v(16.8f, 13.8f)))
            IconId.Microchip -> {
                var d = sub(box(p, 6f, 6f, 18f, 18f, 1.8f), box(p, 8.8f, 8.8f, 15.2f, 15.2f, 1f))
                d = u(d, box(p, 10.3f, 10.3f, 13.7f, 13.7f, 0.6f))
                for (k in floatArrayOf(9f, 12f, 15f)) {
                    d = u(d, seg(p, v(k, 2.5f), v(k, 6f), 1.6f))
                    d = u(d, seg(p, v(k, 18f), v(k, 21.5f), 1.6f))
                    d = u(d, seg(p, v(2.5f, k), v(6f, k), 1.6f))
                    d = u(d, seg(p, v(18f, k), v(21.5f, k), 1.6f))
                }
                d
            }
            IconId.Plus -> u(seg(p, v(12f, 4.5f), v(12f, 19.5f), 3f), seg(p, v(4.5f, 12f), v(19.5f, 12f), 3f))
            IconId.Crosshairs -> {
                var d = abs((p - v(12f, 12f)).length - 7.6f) - 1.1f
                d = u(d, seg(p, v(12f, 1.5f), v(12f, 7.5f), 2.2f))
                d = u(d, seg(p, v(12f, 16.5f), v(12f, 22.5f), 2.2f))
                d = u(d, seg(p, v(1.5f, 12f), v(7.5f, 12f), 2.2f))
                d = u(d, seg(p, v(16.5f, 12f), v(22.5f, 12f), 2.2f))
                u(d, circle(p, 12f, 12f, 1.7f))
            }
            IconId.HandPointer -> {
                var d = seg(p, v(9.8f, 3.2f), v(9.8f, 13f), 3.6f)
                d = u(d, box(p, 7.6f, 10.5f, 19.6f, 21.8f, 3.4f))
                d = u(d, seg(p, v(13.6f, 10.2f), v(13.6f, 12.5f), 3.2f))
                d = u(d, seg(p, v(17.2f, 11.2f), v(17.2f, 13.5f), 3f))
                u(d, seg(p, v(5f, 13.5f), v(8.4f, 17.4f), 3f))
            }
            IconId.Circle -> circle(p, 12f, 12f, 9f)
            IconId.Wifi -> {
                val c = v(12f, 19.5f)
                val wedge = poly(p, c, v(-10f, -2.5f), v(34f, -2.5f))
                var d = circle(p, 12f, 19.5f, 2.1f)
                for (r in floatArrayOf(14.5f, 9.8f, 5.1f)) d = u(d, max(abs((p - c).length - r) - 1.3f, wedge))
                d
            }
            IconId.MapPin -> sub(u(circle(p, 12f, 9f, 6.8f), poly(p, v(6.1f, 12.4f), v(17.9f, 12.4f), v(12f, 22f))), circle(p, 12f, 9f, 2.7f))
            IconId.Aidy -> {
                // デフォルメ Aidy の単色版 (枠 + ディスプレイ、目は抜き)
                val outer = box(p, 2.2f, 2.2f, 21.8f, 21.8f, 4.5f)
                var d = max(outer, -(outer + 1.8f))
                d = u(d, sub(box(p, 5.4f, 7.8f, 18.6f, 16.6f, 2f), u(ellipse(p, 10f, 12.2f, 1.25f, 2.1f), ellipse(p, 14f, 12.2f, 1.25f, 2.1f))))
                d = u(d, circle(p, 12f, 5.2f, 1.05f))
                u(d, seg(p, v(11f, 19.2f), v(13f, 19.2f), 1.4f))
            }
            // 耳モジュール: アーチ形 + 内側の溝
            IconId.Ear -> sub(
                u(poly(p, v(3.5f, 20.5f), v(6f, 9f), v(12f, 3f), v(18f, 9f), v(20.5f, 20.5f)), circle(p, 12f, 8.5f, 5.6f)),
                poly(p, v(8.6f, 18.8f), v(10f, 11f), v(12f, 8.2f), v(14f, 11f), v(15.4f, 18.8f)),
            )
            IconId.Tail -> u(polyline(p, 3.2f, v(4.5f, 20f), v(8f, 15.5f), v(13f, 14f), v(17f, 10.5f), v(18.5f, 5.5f)), circle(p, 18.6f, 4.8f, 2.4f))
            // シルクハット: つば + 筒
            IconId.Hat -> u(box(p, 2.5f, 15.5f, 21.5f, 19f, 1.6f), box(p, 6.5f, 4.5f, 17.5f, 17f, 1.8f))
            IconId.Leg -> u(box(p, 3.5f, 3.5f, 20.5f, 8f, 2f), u(box(p, 5.5f, 6f, 10f, 20.5f, 2f), box(p, 14f, 6f, 18.5f, 20.5f, 2f)))
            // 手モジュール: 本体から横に伸びる角丸の腕
            IconId.Hand -> u(box(p, 2.5f, 8.5f, 17.5f, 15.5f, 3.2f), box(p, 16f, 6.5f, 21.5f, 17.5f, 2.6f))
            IconId.Flashlight -> {
                var d = u(box(p, 2.5f, 8.5f, 14f, 15.5f, 3f), poly(p, v(13f, 8.5f), v(17.5f, 5.5f), v(17.5f, 18.5f), v(13f, 15.5f)))
                d = u(d, seg(p, v(19.5f, 7f), v(22f, 5.2f), 1.5f))
                d = u(d, seg(p, v(20f, 12f), v(22.8f, 12f), 1.5f))
                u(d, seg(p, v(19.5f, 17f), v(22f, 18.8f), 1.5f))
            }
            IconId.Battery -> {
                val body = box(p, 1.5f, 6.5f, 20.5f, 17.5f, 2.6f)
                u(max(body, -(body + 1.6f)), box(p, 20.5f, 9.5f, 23f, 14.5f, 1f))
            }
            IconId.Book -> {
                var d = sub(box(p, 4f, 2.5f, 20f, 18.5f, 2.2f), box(p, 8f, 6f, 16f, 9.2f, 0.6f))
                d = u(d, sub(box(p, 4f, 16.5f, 20f, 21.5f, 2f), box(p, 6.4f, 18f, 21f, 20f, 0.6f)))
                d
            }
        }
    }
}

/** 着色したアイコン。 */
@Composable
fun Icon(id: IconId, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { ceil(size.toPx()).toInt() }
    // まだ作っていない絵は裏のスレッドで作る (起動時に UI スレッドを止めない)。できるまでは空けておく
    val bmp by produceState(AidyIcons.cached(id, px), id, px) {
        if (value == null) value = withContext(Dispatchers.Default) { AidyIcons.get(id, px) }
    }
    val b = bmp
    if (b == null) Box(modifier.size(size))
    else Image(b, null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}
