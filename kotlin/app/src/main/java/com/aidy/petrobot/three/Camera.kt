package com.aidy.petrobot.three

import com.google.android.filament.Camera
import com.google.android.filament.Engine
import io.github.sceneview.node.CameraNode
import kotlin.math.atan
import kotlin.math.tan

/**
 * 縦の画角 (度) と上下のずらし (NDC) を自分で決めるカメラ。Unity 版の Camera.fieldOfView + 射影行列の m[1,2] に相当。
 * 位置と向き (eye → target) も持ち、タップ判定用のレイ・画面への投影を自前で計算する (描画と同じ式)。
 */
class AidyCamera(engine: Engine) : CameraNode(engine) {
    /** 縦横比 → 縦の画角 (度)。 */
    var fov: (Float) -> Float = { 45f }
    /** 写る範囲を上下にずらす量 (NDC。正で絵が上へ動く)。 */
    var shiftY = 0f
        set(v) {
            field = v
            updateProjection()
        }
    var nearPlane = 0.1f
    var farPlane = 1000f

    var eye = V3(0f, 0f, 1f)
        private set
    var target = V3.Zero
        private set
    var width = 1
        private set
    var height = 1
        private set

    init {
        // 太陽と同じ強さのライト (100000 lux) で適正になる露出 (sunny f/16)
        setExposure(16.0f, 1.0f / 125.0f, 100.0f)
    }

    val aspect get() = width.toFloat() / height.coerceAtLeast(1)
    val fovDeg get() = fov(aspect)

    override fun updateProjection(focalLength: Double, near: Float, far: Float, aspect: Double) {
        viewport?.let {
            width = it.width.coerceAtLeast(1)
            height = it.height.coerceAtLeast(1)
        }
        if (view == null) return
        val a = width.toDouble() / height
        setProjection(fov(a.toFloat()).toDouble(), a, nearPlane.toDouble(), farPlane.toDouble(), Camera.Fov.VERTICAL)
        setShift(0.0, shiftY.toDouble())
    }

    fun lookAt(eye: V3, target: V3) {
        this.eye = eye
        this.target = target
        setLocal(eye, Q.look(eye - target, V3.Up)) // カメラは -Z を向く
    }

    private fun basis(): Triple<V3, V3, V3> {
        val f = (target - eye).normalized()
        var r = f cross V3.Up
        if (r.sqrLength < 1e-8f) r = V3.Right
        r = r.normalized()
        val u = r cross f
        return Triple(r, u, f)
    }

    /** 画面の画素 (左上原点) → ワールドのレイ (始点, 単位方向)。 */
    fun ray(px: Float, py: Float): Pair<V3, V3> {
        val (r, u, f) = basis()
        val t = tan(fovDeg * 0.5f * Deg2Rad)
        val nx = px / width * 2f - 1f
        val ny = 1f - py / height * 2f - shiftY
        val d = (f + r * (nx * t * aspect) + u * (ny * t)).normalized()
        return eye to d
    }

    /** ワールド → 画面の画素 (左上原点)。z = カメラからの奥行き (負なら後ろ)。 */
    fun worldToScreen(p: V3): V3 {
        val (r, u, f) = basis()
        val d = p - eye
        val z = d dot f
        val t = tan(fovDeg * 0.5f * Deg2Rad)
        val nx = (d dot r) / (z * t * aspect)
        val ny = (d dot u) / (z * t) + shiftY
        return V3((nx + 1f) * 0.5f * width, (1f - ny) * 0.5f * height, z)
    }

    companion object {
        /** 縦の画角 45° は横幅 0.7 (縦長のカード) を想定。それより細長い画面では横が見切れないよう広げる。 */
        fun homeFov(aspect: Float): Float {
            val designAspect = 0.7f
            val designFov = 45f
            if (aspect >= designAspect) return designFov
            val halfTan = tan(designFov * 0.5f * Deg2Rad) * designAspect / aspect
            return 2f * atan(halfTan) * Rad2Deg
        }
    }
}
