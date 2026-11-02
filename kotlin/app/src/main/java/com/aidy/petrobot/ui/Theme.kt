package com.aidy.petrobot.ui

import androidx.compose.ui.graphics.Color

/** WebDemo の CSS 変数・配色 (Unity 版 Theme と同じ)。 */
object Theme {
    fun hex(rgb: Int, a: Float = 1f) = Color(
        red = ((rgb shr 16) and 0xff) / 255f,
        green = ((rgb shr 8) and 0xff) / 255f,
        blue = (rgb and 0xff) / 255f,
        alpha = a,
    )

    /** android.graphics 用の ARGB。 */
    fun argb(rgb: Int, a: Float = 1f) = ((a * 255f + 0.5f).toInt() shl 24) or (rgb and 0xffffff)

    val Device = hex(0xf8fafc)
    val Primary = hex(0x3b82f6)
    val PrimaryLight = hex(0xeff6ff)
    val TextMain = hex(0x1e293b)
    val TextSub = hex(0x64748b)
    val Border = hex(0xe2e8f0)
    val Green = hex(0x10b981)
    val Slate100 = hex(0xf1f5f9)
    val Slate300 = hex(0xcbd5e1)
    val Slate400 = hex(0x94a3b8)
    val Slate600 = hex(0x475569)
    val Slate700 = hex(0x334155)
    val Slate900 = hex(0x0f172a)
}

/** CSS の cubic-bezier() と同じイージング。 */
class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    companion object {
        val Ease = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
        val EaseOut = CubicBezier(0f, 0f, 0.58f, 1f)
        val PageSnap = CubicBezier(0.2f, 0.9f, 0.3f, 1f)
    }

    private fun bez(a: Float, b: Float, s: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * a + 3f * u * s * s * b + s * s * s
    }

    fun evaluate(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        var s = t
        for (i in 0 until 24) {
            val x = bez(x1, x2, s)
            if (kotlin.math.abs(x - t) < 1e-5f) break
            if (x < t) lo = s else hi = s
            s = (lo + hi) * 0.5f
        }
        return bez(y1, y2, s)
    }
}
