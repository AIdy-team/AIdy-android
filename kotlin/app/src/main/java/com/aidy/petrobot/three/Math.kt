package com.aidy.petrobot.three

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D の小さな数学 (右手系・Y 上・カメラは -Z を向く = Filament / three.js / ARCore と同じ)。
 *
 * Unity 版は左手系 (+X = 正面から見て左) なので、Unity の座標で書かれたデータ (body_v2_layout.json、
 * モジュールの取り付け面ローカル) は X を反転して取り込む: 位置 (x, y, z) → (-x, y, z)、
 * 回転 AngleAxis(a, axis) → AngleAxis(-a, (-axis.x, axis.y, axis.z))。
 * WebDemo (three.js) の数値はそのまま使える。
 */
data class V3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    operator fun div(s: Float) = V3(x / s, y / s, z / s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    operator fun get(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length get() = sqrt(x * x + y * y + z * z)
    val sqrLength get() = x * x + y * y + z * z
    fun normalized(): V3 {
        val l = length
        return if (l > 1e-12f) this / l else this
    }
    fun mul(o: V3) = V3(x * o.x, y * o.y, z * o.z)
    /** Unity 座標 ⇔ この座標 (X 反転)。 */
    fun mirrorX() = V3(-x, y, z)
    fun toFloat3() = Float3(x, y, z)

    companion object {
        val Zero = V3()
        val One = V3(1f, 1f, 1f)
        val Right = V3(1f, 0f, 0f)
        val Up = V3(0f, 1f, 0f)
        val Forward = V3(0f, 0f, 1f)
        fun min(a: V3, b: V3) = V3(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
        fun max(a: V3, b: V3) = V3(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        fun lerp(a: V3, b: V3, t: Float) = a + (b - a) * t
        fun distance(a: V3, b: V3) = (a - b).length
    }
}

/** 回転 (単位クォータニオン)。 */
data class Q(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {
    operator fun times(o: Q) = Q(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    operator fun times(v: V3): V3 {
        // v + 2w(q×v) + 2 q×(q×v)
        val qv = V3(x, y, z)
        val t = (qv cross v) * 2f
        return v + t * w + (qv cross t)
    }

    fun inverse() = Q(-x, -y, -z, w)

    fun normalized(): Q {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l > 1e-12f) Q(x / l, y / l, z / l, w / l) else Identity
    }

    fun toQuaternion() = Quaternion(x, y, z, w)

    companion object {
        val Identity = Q()

        /** angle はラジアン。 */
        fun axisAngle(axis: V3, angle: Float): Q {
            val a = axis.normalized()
            val s = sin(angle * 0.5f)
            return Q(a.x * s, a.y * s, a.z * s, cos(angle * 0.5f))
        }

        /** three.js の Euler (XYZ 順、ラジアン) = Rx · Ry · Rz。 */
        fun euler(rx: Float, ry: Float, rz: Float) =
            axisAngle(V3.Right, rx) * axisAngle(V3.Up, ry) * axisAngle(V3.Forward, rz)

        fun euler(r: V3) = euler(r.x, r.y, r.z)

        /** 列ベクトル (x, y, z 軸) から。正規直交であること。 */
        fun fromBasis(xa: V3, ya: V3, za: V3): Q {
            val m00 = xa.x; val m10 = xa.y; val m20 = xa.z
            val m01 = ya.x; val m11 = ya.y; val m21 = ya.z
            val m02 = za.x; val m12 = za.y; val m22 = za.z
            val tr = m00 + m11 + m22
            return if (tr > 0f) {
                val s = sqrt(tr + 1f) * 2f
                Q((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Q(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Q((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Q((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }.normalized()
        }

        /** +Z を forward に、+Y をできるだけ up に向ける回転 (Unity の LookRotation と同じ役割、右手系)。 */
        fun look(forward: V3, up: V3): Q {
            val z = forward.normalized()
            var xa = up cross z
            if (xa.sqrLength < 1e-10f) xa = (if (abs(z.y) < 0.9f) V3.Up else V3.Right) cross z
            xa = xa.normalized()
            val ya = z cross xa
            return fromBasis(xa, ya, z)
        }

        /** from を to に向ける最小の回転。 */
        fun fromTo(from: V3, to: V3): Q {
            val a = from.normalized()
            val b = to.normalized()
            val d = a dot b
            if (d > 0.999999f) return Identity
            if (d < -0.999999f) {
                var axis = V3.Right cross a
                if (axis.sqrLength < 1e-6f) axis = V3.Up cross a
                return axisAngle(axis, Math.PI.toFloat())
            }
            val c = a cross b
            return Q(c.x, c.y, c.z, 1f + d).normalized()
        }

        fun slerp(a: Q, b0: Q, t: Float): Q {
            var b = b0
            var d = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
            if (d < 0f) {
                b = Q(-b.x, -b.y, -b.z, -b.w)
                d = -d
            }
            if (d > 0.9995f) {
                return Q(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t).normalized()
            }
            val th = acos(d.coerceIn(-1f, 1f))
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return Q(a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb, a.w * wa + b.w * wb)
        }

        /** 2 つの回転の角度 (度)。 */
        fun angleDeg(a: Q, b: Q): Float {
            val d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceAtMost(1f)
            return Math.toDegrees(2.0 * acos(d.toDouble())).toFloat()
        }
    }
}

/** 位置・回転・拡大 (親子の合成とその逆)。 */
data class Pose(val p: V3 = V3.Zero, val r: Q = Q.Identity, val s: V3 = V3.One) {
    fun point(v: V3) = p + r * v.mul(s)
    fun dir(v: V3) = r * v
    fun inversePoint(v: V3): V3 {
        val l = r.inverse() * (v - p)
        return V3(l.x / s.x, l.y / s.y, l.z / s.z)
    }
    fun inverseDir(v: V3) = r.inverse() * v
    /** this (親) の子 child を合成 (拡大は一様とみなす)。 */
    operator fun times(c: Pose) = Pose(point(c.p), r * c.r, s.mul(c.s))
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun clamp01(v: Float) = v.coerceIn(0f, 1f)
fun smoothStep(a: Float, b: Float, t: Float): Float {
    val x = clamp01((t - a) / (b - a))
    return x * x * (3 - 2 * x)
}
fun inverseLerp(a: Float, b: Float, v: Float) = if (a != b) clamp01((v - a) / (b - a)) else 0f
fun repeat(t: Float, length: Float) = (t - kotlin.math.floor(t / length) * length).coerceIn(0f, length)
const val PI = Math.PI.toFloat()

// ---------------------------------------------------------------- Unity 版のコードを移すための変換

/** Unity の位置 → この座標。 */
fun uPos(x: Float, y: Float, z: Float) = V3(-x, y, z)

/** Unity の Quaternion.AngleAxis(deg, axis) → この座標の回転。 */
fun uRot(deg: Float, axis: V3): Q = Q.axisAngle(axis.mirrorX(), -Math.toRadians(deg.toDouble()).toFloat())

/** Unity の Quaternion.LookRotation(forward, up) (どちらも Unity 座標) → この座標の回転。 */
fun uLook(forward: V3, up: V3 = V3.Up): Q = Q.look(forward.mirrorX(), up.mirrorX())

/** Unity の回転 → この座標の回転 (鏡映で挟む)。 */
fun Q.mirrorX() = Q(x, -y, -z, w)

const val Deg2Rad = (Math.PI / 180.0).toFloat()
const val Rad2Deg = (180.0 / Math.PI).toFloat()
