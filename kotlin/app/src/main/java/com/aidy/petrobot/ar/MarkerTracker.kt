package com.aidy.petrobot.ar

import android.util.Log
import com.aidy.petrobot.data.AidyMarkers
import com.aidy.petrobot.three.Pose
import com.aidy.petrobot.three.Q
import com.aidy.petrobot.three.V3
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/** マーカーの姿勢 (カメラ座標、または AR のワールド座標)。モデルを立たせる向き: 上 = マーカーの面の法線、正面 = マーカーの下辺の方。 */
class MarkerSighting(val id: Int, val position: V3, val rotation: Q)

/** 画面上の向き (x 右, y 下) を表す 2 次元ベクトル。 */
data class Axis2(val x: Float, val y: Float)

/**
 * カメラ画像からマーカーを探す (Unity 版 AidyMarkerTracker)。検出は別スレッドで行い、終わったら次の画像を受け付ける。
 * 画像は撮像素子の向き (横長) のまま渡し、画面に対する向き (imageRight / imageDown: 画像の右・下が画面上のどちら向きか)
 * でカメラ座標 (OpenGL: x 右, y 上, -z 前) に直す。
 */
class MarkerTracker {
    private val detector = ArucoDetector()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "AidyMarker").also { it.isDaemon = true } }
    private var buffer = ByteArray(0)

    /** 検出中 (または結果を受け取っていない) の画像とそのときのカメラ。 */
    private class Job(
        val future: Future<List<ArucoMarker>>,
        val fx: Double, val fy: Double, val cx: Double, val cy: Double,
        val right: Axis2, val down: Axis2, val camToWorld: Pose,
    )

    @Volatile private var job: Job? = null

    /** 前の結果をまだ受け取っていない (カメラのスレッドから見て、次の画像を書き込めない)。 */
    val busy get() = job != null

    /** 輝度画像を書き込むバッファ (submit まで使える)。 */
    fun buffer(w: Int, h: Int): ByteArray {
        if (buffer.size != w * h) buffer = ByteArray(w * h)
        return buffer
    }

    /**
     * buffer に書いた画像の検出を始める。f/c は画像の画素単位の内部パラメータ。
     * imageRight / imageDown: 画像の +x / +y が画面 (x 右, y 下) のどちら向きか。camToWorld: 撮影時のカメラの姿勢 (OpenGL のカメラ)。
     */
    fun submit(w: Int, h: Int, fx: Double, fy: Double, cx: Double, cy: Double, imageRight: Axis2, imageDown: Axis2, camToWorld: Pose) {
        if (busy) return
        val img = buffer
        val ids = AidyMarkers.activeIds()
        val f = executor.submit<List<ArucoMarker>> { detector.detect(img, w, h, ids) }
        job = Job(f, fx, fy, cx, cy, imageRight, imageDown, camToWorld)
    }

    /** 検出が終わっていれば結果を返す (まだなら null)。 */
    fun tryCollect(): List<MarkerSighting>? {
        val j = job ?: return null
        if (!j.future.isDone) return null
        val found = try {
            j.future.get()
        } catch (e: Exception) {
            Log.w("Aidy", "マーカー検出に失敗", e)
            emptyList()
        }
        val out = ArrayList<MarkerSighting>()
        for (m in found) {
            val res = ArucoPose.solve(m.corners, AidyMarkers.size(m.id).toDouble(), j.fx, j.fy, j.cx, j.cy) ?: continue
            val r = res.r
            val tv = res.t
            // OpenCV のカメラ座標 (画像の x 右, y 下, z 前) → OpenGL のカメラ座標
            val pos = toGl(j, tv[0], tv[1], tv[2])
            val axisY = toGl(j, r[1], r[4], r[7])
            val axisZ = toGl(j, r[2], r[5], r[8])
            val rot = Q.look(-axisY, axisZ)
            out += MarkerSighting(m.id, j.camToWorld.point(pos), j.camToWorld.r * rot)
        }
        job = null
        return out
    }

    private fun toGl(j: Job, x: Double, y: Double, z: Double): V3 {
        val sx = (x * j.right.x + y * j.down.x).toFloat()
        val sy = (x * j.right.y + y * j.down.y).toFloat()
        return V3(sx, -sy, -z.toFloat())
    }

    fun shutdown() = executor.shutdownNow()

    companion object {
        /** 画面を時計回りに deg 度回して表示している画像の、画像の右・下の向き (画面座標、y 下)。 */
        fun rotatedAxes(deg: Int): Pair<Axis2, Axis2> {
            val a = Math.toRadians(deg.toDouble())
            return snap(cos(a).toFloat(), sin(a).toFloat()) to snap(-sin(a).toFloat(), cos(a).toFloat())
        }

        /**
         * 画面の正規化座標 (左上原点) → カメラ画像の正規化座標 の 2x2 部分 (image = M * screen) から、画像の右・下の向きを求める。
         * 逆行列の列が、画像の各軸が画面上で向く方向。
         */
        fun axesFromDisplayMatrix(m00: Float, m01: Float, m10: Float, m11: Float): Pair<Axis2, Axis2> {
            val det = m00 * m11 - m01 * m10
            if (abs(det) < 1e-6f) return rotatedAxes(90)
            return snap(m11 / det, -m10 / det) to snap(-m01 / det, m00 / det)
        }

        private fun snap(x: Float, y: Float) = if (abs(x) >= abs(y)) Axis2(sign(x), 0f) else Axis2(0f, sign(y))
    }
}
