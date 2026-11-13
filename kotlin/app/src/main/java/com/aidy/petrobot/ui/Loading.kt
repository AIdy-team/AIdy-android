package com.aidy.petrobot.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * ロゴ (aidy_logo_dark.png 330x160) を「動かす部分」と「そのままの部分」に分けたもの。
 * i の点 (青い四角) と d の中の目 (青い2本) を消した絵 + それぞれの位置。
 */
private class LogoParts(val base: ImageBitmap, val w: Int, val h: Int, val dot: IntRect, val eyes: IntRect, val dotColor: Int) {
    class IntRect(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w get() = r - l + 1
        val h get() = b - t + 1
    }

    companion object {
        fun load(open: () -> java.io.InputStream): LogoParts? = runCatching {
            val src = open().use { BitmapFactory.decodeStream(it) } ?: return null
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            // 青い画素を上半分 (i の点) と下半分 (目) に分けて囲む
            var dl = w; var dt = h; var dr = -1; var db = -1
            var el = w; var et = h; var er = -1; var eb = -1
            var dotColor = 0xff1ea7ff.toInt()
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[y * w + x]
                val a = c ushr 24
                val r = (c shr 16) and 0xff
                val b = c and 0xff
                if (a < 100 || b < 170 || r > 120) continue
                if (y < h * 0.33f) {
                    dl = min(dl, x); dt = min(dt, y); dr = max(dr, x); db = max(db, y)
                    if (a > 250) dotColor = c
                } else {
                    el = min(el, x); et = min(et, y); er = max(er, x); eb = max(eb, y)
                }
                px[y * w + x] = 0 // 動かす部分は消しておく
            }
            if (dr < 0 || er < 0) return null
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            LogoParts(bmp.asImageBitmap(), w, h, IntRect(dl, dt, dr, db), IntRect(el, et, er, eb), dotColor)
        }.getOrNull()
    }
}

/**
 * 動くロゴ。i の点が弾んで (着地でつぶれる)、d の目がまばたきして左右を見る。
 * width はロゴの横幅。
 */
@Composable
fun AnimatedAidyLogo(width: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val parts = remember { LogoParts.load { context.assets.open("aidy_logo_dark.png") } }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - start) / 1e9f }
    }
    if (parts == null) {
        AidyMascot(width * 0.4f, AidyFace.Normal, modifier)
        return
    }
    val height = width * (parts.h.toFloat() / parts.w)
    Canvas(modifier.size(width, height)) {
        val s = size.width / parts.w
        drawImage(parts.base, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero)

        // i の点: 0.9 秒ごとに弾む。空中で少し伸び、着地でつぶれる
        val period = 0.9f
        val p = (t % period) / period
        val hop = sin(p * PI.toFloat()).coerceAtLeast(0f)
        val land = if (p < 0.12f) 1f - p / 0.12f else if (p > 0.92f) (p - 0.92f) / 0.08f else 0f
        val d = parts.dot
        val dw = d.w * s
        val dh = d.h * s
        val sx = 1f + land * 0.25f - hop * 0.08f
        val sy = 1f - land * 0.25f + hop * 0.12f
        val cx = (d.l + d.w / 2f) * s
        val bottom = (d.b + 1) * s
        val lift = hop * d.h * 1.6f * s
        val rw = dw * sx
        val rh = dh * sy
        drawRoundRect(
            Color(parts.dotColor),
            topLeft = Offset(cx - rw / 2f, bottom - lift - rh),
            size = Size(rw, rh),
            cornerRadius = CornerRadius(rw * 0.18f),
        )

        // 目: 3 秒に 1 回まばたき、ときどき左右を見る
        val e = parts.eyes
        val blinkT = t % 3f
        val blink = if (blinkT > 2.75f) 1f - abs((blinkT - 2.875f) / 0.125f) else 0f
        val look = when ((t / 3f).toInt() % 4) {
            1 -> -1f
            3 -> 1f
            else -> 0f
        } * e.w * 0.18f * s
        val eyeW = (e.w * 0.3f) * s
        val eyeH = e.h * s * (1f - blink * 0.85f)
        val ecy = (e.t + e.h / 2f) * s
        for (ex in listOf(e.l + e.w * 0.15f, e.l + e.w * 0.85f)) {
            drawRoundRect(
                Color(parts.dotColor),
                topLeft = Offset(ex * s - eyeW / 2f + look, ecy - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW / 2f),
            )
        }
    }
}

/** 起動時の画面: 動くロゴ + 進み具合のバー。 */
@Composable
fun AidyLoadingScreen(progress: Float, text: String) {
    Box(Modifier.fillMaxSize().background(Theme.Device), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            AnimatedAidyLogo(200.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.width(160.dp).height(6.dp)) {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(Theme.Slate300, cornerRadius = r)
                    drawRoundRect(Theme.Primary, size = Size(size.width * progress.coerceIn(0.04f, 1f), size.height), cornerRadius = r)
                }
                Label(text, 12f, Theme.TextSub)
            }
        }
    }
}

/** 3D ビューの代わりに出す「読み込み中」(くるくる + 文字)。 */
@Composable
fun ModelLoadingView(modifier: Modifier = Modifier, text: String = "3Dモデルを読み込み中…") {
    val spin = rememberInfiniteTransition(label = "spin")
    val a by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Box(modifier.fillMaxSize().background(Theme.hex(0xf1f5f9)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    val st = 5.dp.toPx()
                    drawCircle(Theme.Slate300, size.minDimension / 2f - st / 2f, style = Stroke(st))
                    withTransform({ rotate(a) }) {
                        drawArc(Theme.Primary, 0f, 100f, false, style = Stroke(st, cap = StrokeCap.Round),
                            topLeft = Offset(st / 2f, st / 2f), size = Size(size.width - st, size.height - st))
                    }
                }
                AidyMascot(34.dp, AidyFace.Normal)
            }
            Label(text, 12f, Theme.TextSub, true)
        }
    }
}

