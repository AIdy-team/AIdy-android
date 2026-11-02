package com.aidy.petrobot.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * 本体の顔モニター (LCD 480x320) に映る顔。本体の faces.py (Pillow で描く版) を移したもの (Unity 版 AidyFaceScreen)。
 * 黒い画面に白い目を 2 つ描き、表情ごとの目の形・瞬き・きょろきょろ・動き (fx) も同じにする。
 * ここは絵 (240x160 の ARGB、上の行から・正面から見たまま) を作るだけで、3D 側 (three.FaceScreenNode) がテクスチャに貼る。
 */
class FaceScreen {
    companion object {
        private const val W = 480f
        private const val H = 320f
        private val EyeX = floatArrayOf(146f, 334f)
        private const val EyeY = 160f
        private val BlinkDefault = 5f to 11f

        const val TexW = 240 // 480x320 の半分で描く
        const val TexH = 160
        private const val Tick = 0.05f // lcd.py の 20fps と同じ間隔で目の形をなめらかに変える

        private val Faces: Map<String, FaceDef> = linkedMapOf(
            "normal" to FaceDef("ふつう"),
            "happy" to FaceDef("うれしい", Shape(w = 54f, h = 100f, dy = -10f, low = 0.32f), "bounce"),
            "sad" to FaceDef("かなしい", Shape(w = 50f, h = 112f, dy = 16f, top = 0.2f, tilt = -18f)),
            "angry" to FaceDef("おこる", Shape(w = 56f, h = 122f, dy = 4f, top = 0.3f, tilt = 24f)),
            "sleepy" to FaceDef("ねむい", Shape(w = 50f, h = 74f, dy = 22f, top = 0.38f), blink = 3f to 6f),
            "sleep" to FaceDef("ねてる", Shape(w = 64f, h = 9f, dy = 22f), "zz", blink = null),
            "dazed" to FaceDef("ぼーっと", Shape(w = 34f, h = 36f, dy = 12f), "drift"),
            "lowbatt" to FaceDef("電池少ない", Shape(w = 44f, h = 100f, dy = -26f, top = 0.2f, tilt = -14f, dim = 0.4f), "battlow"),
            "scared" to FaceDef("おびえる", Shape(w = 40f, h = 104f, dy = 8f, top = 0.1f, tilt = -16f), "shake"),
            "listen" to FaceDef("聞いている", Shape(w = 54f, h = 146f, tint = 0.85f), "level"),
            "speak" to FaceDef("話している", Shape(w = 52f, h = 124f), "level"),
        )

        /** 前の名前や使わなくなった表情は近いものにする (faces.py の ALIAS)。 */
        private val Alias = mapOf(
            "love" to "happy", "laugh" to "happy", "smug" to "happy", "wink" to "happy", "surprised" to "normal",
            "confused" to "normal", "tilt" to "normal", "noticed" to "normal", "lookaround" to "normal", "waiting" to "normal",
            "wake" to "normal", "stare" to "normal", "nod" to "normal", "doubletake" to "normal", "lookup" to "normal",
            "lookdown" to "normal", "cry" to "sad", "shy" to "normal", "suspicious" to "angry", "dizzy" to "dazed",
            "charging" to "normal", "error" to "sad", "attach" to "normal", "fall" to "scared", "hot" to "dazed", "cold" to "scared",
        )

        /** アプリの表情 (デフォルメ Aidy と共通) → 本体の表情の名前。 */
        fun fromApp(face: AidyFace?): String = when (face) {
            AidyFace.Happy -> "happy"
            AidyFace.Sleepy -> "sleepy"
            AidyFace.Dizzy -> "dazed"
            AidyFace.Surprised -> Alias.getValue("surprised")
            else -> "normal"
        }

        val names: Collection<String> get() = Faces.keys
        fun label(name: String) = Faces[name]?.label ?: name

        private fun now() = System.nanoTime() / 1e9f

        private fun roundBox(x: Float, y: Float, hw: Float, hh: Float, r: Float): Float {
            val qx = abs(x) - (hw - r)
            val qy = abs(y) - (hh - r)
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
        }

        private fun segment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val abx = bx - ax
            val aby = by - ay
            val t = (((px - ax) * abx + (py - ay) * aby) / (abx * abx + aby * aby)).coerceIn(0f, 1f)
            val dx = px - (ax + abx * t)
            val dy = py - (ay + aby * t)
            return sqrt(dx * dx + dy * dy)
        }
    }

    /** 目の形。BASE からの変更を表情ごとに持つ。 */
    private data class Shape(
        val w: Float = 50f, val h: Float = 140f, val dx: Float = 0f, val dy: Float = 0f, val top: Float = 0f,
        val tilt: Float = 0f, val low: Float = 0f, val dim: Float = 0f, val tint: Float = 0f,
    ) {
        fun lerp(b: Shape, k: Float): Shape {
            fun step(c: Float, v: Float, snap: Float): Float {
                val n = c + (v - c) * k
                return if (abs(n - v) < snap) v else n
            }
            return Shape(
                step(w, b.w, 0.3f), step(h, b.h, 0.3f), step(dx, b.dx, 0.3f), step(dy, b.dy, 0.3f), step(top, b.top, 0.01f),
                step(tilt, b.tilt, 0.3f), step(low, b.low, 0.01f), step(dim, b.dim, 0.01f), step(tint, b.tint, 0.01f),
            )
        }
    }

    private class FaceDef(
        val label: String,
        val shape: Shape = Shape(),
        val fx: String? = null,
        val blink: Pair<Float, Float>? = BlinkDefault, // null = 瞬きしない
    )

    var face = "normal"
        private set

    /** 画面の絵 (240x160、上の行から ARGB、正面から見たまま)。 */
    val pixels = IntArray(TexW * TexH)

    /** 声の大きさ 0〜1 (聞いている・話している間、目が少し動く)。 */
    var level = 0f

    /** 画面に映っているか。映っていない間は描かず、表情もすぐその形にする。 */
    var visible = true

    private var cur = Shape()
    private var target = Shape()
    private var lookX = 0f
    private var lookY = 0f
    private var lookT = -99f
    private var gx = 0f
    private var gy = 0f
    private var lv = 0f
    private var glanceX = 0f
    private var glanceY = 0f
    private var glanceEnd = 0f
    private var nextGlance: Float
    private var tFace: Float
    private var nextBlink: Float
    private var blinkT = -1f
    private var tickAcc = 0f
    private var lastUpdate = -1f
    private var doubleBlink = false
    private var dirty = true
    private var lastSig: String? = null

    init {
        val now = now()
        nextGlance = now + 6f
        nextBlink = now + 1f + Random.nextFloat() * 2f // 並んだ本体どうしで瞬きがそろわないように
        tFace = now
        draw(now)
    }

    /** 表情を変える (名前は faces.py の FACES。ALIAS の名前も使える)。 */
    fun setFace(name0: String?) {
        var name = (name0 ?: "normal").trim()
        if (name == "thinking") return // 本体でも考え中は表情を変えない
        Alias[name]?.let { name = it }
        if (name !in Faces) name = "normal"
        if (name == face) return
        face = name
        tFace = now()
        target = Faces.getValue(name).shape
        if (!visible) cur = target
        dirty = true
    }

    /** その方向を見る (x: -60〜60, y: -30〜30)。3 秒たつと前に戻る。 */
    fun look(x: Float, y: Float = 0f) {
        lookX = x
        lookY = y
        lookT = now()
    }

    /** すぐに瞬きする。 */
    fun blink() {
        if (Faces.getValue(face).blink == null) return
        blinkT = now()
        doubleBlink = false
    }

    /** 毎フレーム呼ぶ。絵が変わったら true。 */
    fun update(): Boolean {
        val now = now()
        val dt = if (lastUpdate < 0f) 0f else now - lastUpdate
        lastUpdate = now
        tickAcc += dt
        if (tickAcc < Tick) return false
        tickAcc = min(tickAcc - Tick, Tick)
        if (!visible && !dirty) return false
        return draw(now)
    }

    // ------------------------------------------------------------ 描く

    private fun blinkK(now: Float): Float {
        val rng = Faces.getValue(face).blink ?: return 1f
        if (blinkT < 0f && now >= nextBlink) {
            blinkT = now
            doubleBlink = Random.nextFloat() < 0.15f
        }
        if (blinkT < 0f) return 1f
        val x = (now - blinkT) / 0.16f
        if (x >= 1f) {
            if (doubleBlink) {
                doubleBlink = false
                blinkT = now + 0.12f
            } else {
                blinkT = -1f
                nextBlink = now + rng.first + Random.nextFloat() * (rng.second - rng.first)
            }
            return 1f
        }
        return if (x < 0f) 1f else max(0.08f, abs(1f - 2f * x))
    }

    private fun draw(now: Float): Boolean {
        val def = Faces.getValue(face)
        val fx = def.fx
        cur = cur.lerp(target, 0.35f)
        var p = cur

        // 目線: 指示があればそれ、なければふつうの顔のときだけ、ときどき自分で少し見る
        var tx = 0f
        var ty = 0f
        if (now - lookT > 3f) {
            if (face == "normal") {
                if (now > nextGlance) {
                    glanceX = floatArrayOf(-16f, 16f, 0f)[Random.nextInt(3)]
                    glanceY = 0f
                    glanceEnd = now + 1.2f
                    nextGlance = now + 5f + Random.nextFloat() * 5f
                }
                if (now < glanceEnd) {
                    tx = glanceX
                    ty = glanceY
                }
            }
        } else {
            tx = lookX
            ty = lookY
        }
        gx = if (abs(gx + (tx - gx) * 0.3f - tx) < 0.5f) tx else gx + (tx - gx) * 0.3f
        gy = if (abs(gy + (ty - gy) * 0.3f - ty) < 0.5f) ty else gy + (ty - gy) * 0.3f
        lv += (level.coerceIn(0f, 1f) - lv) * 0.5f

        // 動き
        var ox = 0f
        var oy = 0f
        val tf = now - tFace
        when (fx) {
            "shake" -> {
                ox = Random.nextFloat() * 4f - 2f
                oy = Random.nextFloat() * 3f - 1.5f
            }
            "drift" -> {
                ox = sin(now * 0.7f) * 12f
                oy = sin(now * 0.45f) * 6f
            }
            "bounce" -> if (tf < 0.6f) oy = -sin(tf / 0.6f * PI.toFloat()) * 8f
            "level" -> p = p.copy(h = p.h * (1f + 0.06f * lv))
        }
        p = p.copy(h = p.h * blinkK(now))

        val anim = if (fx == "zz" || fx == "battlow") (now * 12f).toInt() else 0
        val sig = "$face|${r1(p.w)}|${r1(p.h)}|${r1(p.dy + oy)}|${r1(ox + gx)}|${r1(gy)}|${r3(p.top)}|${r1(p.tilt)}|${r3(p.low)}|${r3(p.tint)}|${r3(p.dim)}|$anim"
        if (sig == lastSig && !dirty) return false
        lastSig = sig
        dirty = false

        pixels.fill(0xff000000.toInt())
        // 白 → シアン (127,224,255) を tint で混ぜ、dim で暗く
        val k = 1f - p.dim
        val col = rgb((255 + (127 - 255) * p.tint) * k, (255 + (224 - 255) * p.tint) * k, 255f * k)
        val bx = ox + gx + p.dx
        val by = oy + gy * 0.8f + p.dy
        for (side in 0 until 2) drawEye(p, side, EyeX[side] + bx, EyeY + by, col)

        if (fx == "zz") {
            for (i in 0 until 3) {
                val ph = (now / 2.4f + i / 3f) % 1f
                val c = 200f * (1f - ph)
                drawZ(372f + i * 16f + ph * 10f, 118f - i * 22f - ph * 26f, 14f + i * 4f, rgb(c, c, c))
            }
        }
        if (fx == "battlow" && (now * 1.6f).toInt() % 2 == 0) drawBattery(240f, 272f, rgb(255f, 91f, 91f), 1, 0.9f)
        return true
    }

    private fun r1(v: Float) = (v * 10f).roundToInt()
    private fun r3(v: Float) = (v * 1000f).roundToInt()
    private fun rgb(r: Float, g: Float, b: Float) =
        (0xff shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    /**
     * 1 つの目 (角の丸い縦長の四角) を描く。上まぶたは斜めに切り (tilt>0 は内側が下がる = おこる)、
     * 下まぶたは目の下を平らに持ち上げる。
     */
    private fun drawEye(p: Shape, side: Int, cx: Float, cy: Float, col: Int) {
        val w = max(2f, p.w)
        val h = max(2f, p.h)
        val hw = w * 0.5f
        val hh = h * 0.5f
        val r = min(w, h) * 0.5f
        val y0 = cy - hh
        val y1 = cy + hh
        val lid = p.top > 0.001f || abs(p.tilt) > 0.5f
        val lidBase = y0 + p.top * h
        val k = tan(Math.toRadians(p.tilt.toDouble()).toFloat()) * if (side == 0) 1f else -1f // 左目は右が内側
        val lowY = y1 - p.low * h
        fill(cx - hw - 2f, cy - hh - 2f, cx + hw + 2f, cy + hh + 2f, col) { x, y ->
            var d = roundBox(x - cx, y - cy, hw, hh, r)
            if (lid) d = max(d, lidBase + k * (x - cx) - y)
            if (p.low > 0.01f) d = max(d, y - lowY)
            d
        }
    }

    /** Z の字 (線で描く。ねてる)。 */
    private fun drawZ(x: Float, y: Float, s: Float, col: Int) {
        val lw = max(2f, s / 6f) * 0.5f
        fill(x - lw - 2f, y - lw - 2f, x + s + lw + 2f, y + s + lw + 2f, col) { px, py ->
            min(segment(px, py, x, y, x + s, y), min(segment(px, py, x + s, y, x, y + s), segment(px, py, x, y + s, x + s, y + s))) - lw
        }
    }

    /** 電池のアイコン (枠・出っ張り・残量の棒)。電池少ない。 */
    private fun drawBattery(cx: Float, cy: Float, col: Int, level: Int, s: Float) {
        val w = 74f * s
        val h = 38f * s
        val x0 = cx - w * 0.5f
        val y0 = cy - h * 0.5f
        val lw = max(2f, (4f * s).toInt().toFloat())
        fill(x0 - 2f, y0 - 2f, x0 + w + 9f * s, y0 + h + 2f, col) { x, y ->
            val box = roundBox(x - cx, y - cy, w * 0.5f, h * 0.5f, 8f * s)
            var d = max(box, -(box + lw))
            val nubCx = x0 + w + 4.5f * s
            d = min(d, roundBox(x - nubCx, y - cy, 2.5f * s, 8f * s, 2f * s))
            for (i in 0 until level.coerceIn(0, 3)) {
                val bx0 = x0 + 7f * s + i * 21f * s
                d = min(d, roundBox(x - (bx0 + 8.5f * s), y - cy, 8.5f * s, h * 0.5f - 7f * s, 3f * s))
            }
            d
        }
    }

    /** 画面座標 (480x320、y 下向き) の範囲を塗る。dist は図形までの距離 (内側が負)。 */
    private inline fun fill(xMin: Float, yMin: Float, xMax: Float, yMax: Float, col: Int, dist: (Float, Float) -> Float) {
        val sx = W / TexW
        val sy = H / TexH
        val i0 = max(0, floor(xMin / sx).toInt())
        val i1 = min(TexW - 1, ceil(xMax / sx).toInt())
        val j0 = max(0, floor(yMin / sy).toInt())
        val j1 = min(TexH - 1, ceil(yMax / sy).toInt())
        for (j in j0..j1) {
            val y = (j + 0.5f) * sy
            for (i in i0..i1) {
                val a = (0.5f - dist((i + 0.5f) * sx, y) / sx).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val idx = j * TexW + i
                pixels[idx] = AidyMascotArt.lerpArgb(pixels[idx], col, a)
            }
        }
    }
}
