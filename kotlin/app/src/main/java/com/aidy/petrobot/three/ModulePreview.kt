package com.aidy.petrobot.three

import android.graphics.Bitmap
import com.aidy.petrobot.data.BodyLayout
import com.aidy.petrobot.data.BodySlot
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleKind
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * モジュール一覧の丸いプレビュー画像 (Unity 版 AidyModulePreview)。モジュールの形を CPU で描く (Z バッファ + 平行光)。
 * 顔は本体v2_顔ありを正面寄りから、ほかは本体に付いたときと同じ向きで斜め横から見る。
 */
object ModulePreview {
    const val Px = 256
    private const val Fov = 28f

    private class Tri(val a: V3, val b: V3, val c: V3, val na: V3, val nb: V3, val nc: V3, val color: V3, val metallic: Float, val roughness: Float, val unlit: Boolean)

    private class Entry(val tris: List<Tri>, val center: V3, val radius: Float, val viewDir: V3, val bg: Int)

    private val entries = HashMap<ModuleKind, Entry>()
    private val lightDir = V3(4f, 8f, 5f).normalized()

    private fun srgbToLinear(c: Float) = c.pow(2.2f)
    private fun linearToSrgb(c: Float) = c.coerceIn(0f, 1f).pow(1f / 2.2f)

    private fun rgb(argb: Int) = V3(((argb shr 16) and 0xff) / 255f, ((argb shr 8) and 0xff) / 255f, (argb and 0xff) / 255f)

    private fun ensure(kind: ModuleKind): Entry = synchronized(entries) {
        entries.getOrPut(kind) { build(kind) }
    }

    /** 代表のスロット: 耳=上面, 尻尾=背面, 足=底面, 手=左面。 */
    private fun sampleSlot(kind: ModuleKind): BodySlot? {
        val face = when {
            kind == ModuleKind.Ear || kind == ModuleKind.Hat || kind == ModuleKind.Tv -> "top"
            ModuleCatalog.isTail(kind) -> "back"
            kind == ModuleKind.Legs -> "bottom"
            else -> "left"
        }
        return BodyLayout.onFace(face).firstOrNull { ModuleCatalog.fits(kind, it) }
            ?: BodyLayout.slots.firstOrNull { ModuleCatalog.fits(kind, it) }
    }

    private fun build(kind: ModuleKind): Entry {
        val tris = ArrayList<Tri>()
        val satin = rgb(AidyColors.Satin)
        fun add(mesh: MeshData, pose: Pose, color: V3, metallic: Float, roughness: Float, unlit: Boolean, group: MeshData.Group? = null) {
            val start = group?.start ?: 0
            val count = group?.count ?: mesh.indices.size
            fun p(i: Int) = V3(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2])
            fun n(i: Int) = V3(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2])
            val sInv = V3(1f / pose.s.x, 1f / pose.s.y, 1f / pose.s.z)
            var t = start
            while (t + 2 < start + count) {
                val ia = mesh.indices[t]; val ib = mesh.indices[t + 1]; val ic = mesh.indices[t + 2]
                tris += Tri(
                    pose.point(p(ia)), pose.point(p(ib)), pose.point(p(ic)),
                    pose.dir(n(ia).mul(sInv)).normalized(), pose.dir(n(ib).mul(sInv)).normalized(), pose.dir(n(ic).mul(sInv)).normalized(),
                    color, metallic, roughness, unlit,
                )
                t += 3
            }
        }
        if (kind == ModuleKind.Face) {
            // 顔モジュールは本体v2_顔ありに切り替わるので、その本体を見せる
            val body = BodyModel.load("models/body_face.obj")
            if (body != null) {
                for (g in body.groups) {
                    val enamel = g.material.contains("EnamelWhite", ignoreCase = true)
                    if (enamel) add(body, Pose(), V3(0.9647f, 0.9647f, 0.9529f), 0f, 0.15f, false, g)
                    else add(body, Pose(), V3(0.627f, 0.627f, 0.627f), 0.35f, 0.4f, false, g)
                }
            } else add(Meshes.circle(0.3f, 32), Pose(), rgb(0xff3b82f6.toInt()), 0f, 1f, true)
        } else {
            // 本体に付いたときと同じ向きにする
            val slot = sampleSlot(kind)
            val mount = Pose(r = slot?.mountRotation ?: Q.Identity)
            val shape = ModuleShape.build(kind, slot, 1f)
            for (part in shape.parts) {
                val pivot = if (part.pivot >= 0) shape.pivots[part.pivot] else Pose()
                val pose = mount * pivot * part.local
                if (part.mat == ModuleShape.Mat.Lens) add(part.mesh, pose, rgb(AidyColors.Lens), 0f, 1f, true)
                else add(part.mesh, pose, satin, 0.55f, 0.35f, false)
            }
        }
        var lo = V3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var hi = -lo
        for (t in tris) for (v in listOf(t.a, t.b, t.c)) {
            lo = V3.min(lo, v)
            hi = V3.max(hi, v)
        }
        val center = if (tris.isEmpty()) V3.Zero else (lo + hi) * 0.5f
        val radius = if (tris.isEmpty()) 0.2f else max(((hi - lo) * 0.5f).length, 0.01f)
        val tint = ModuleCatalog.tint(kind)
        val bg = (0xff shl 24) or
            ((255 + (tint.red * 255 - 255) * 0.12f).roundToInt() shl 16) or
            ((255 + (tint.green * 255 - 255) * 0.12f).roundToInt() shl 8) or
            (255 + (tint.blue * 255 - 255) * 0.12f).roundToInt()
        // 顔は正面から、ほかは斜め横から (尻尾の反り・手の長さが分かる向き)。Unity 版の向きを X 反転
        val viewDir = (if (kind == ModuleKind.Face) V3(-0.45f, 0.3f, 1f) else V3(-1f, 0.55f, 0.75f)).normalized()
        return Entry(tris, center, radius, viewDir, bg)
    }

    /** 縦軸まわりに yaw 度回した向きで描く (重いので UI スレッド以外で呼ぶ)。 */
    fun render(kind: ModuleKind, yawDeg: Float, out: Bitmap? = null): Bitmap {
        val e = ensure(kind)
        val px = IntArray(Px * Px) { e.bg }
        val depth = FloatArray(Px * Px) { Float.MAX_VALUE }
        val rot = uRot(yawDeg, V3.Up)
        // 外接球が画角に収まる距離 (少し余白を残す)
        val dist = e.radius / sin(Fov * 0.5f * Deg2Rad) * 1.08f
        val eye = e.viewDir * dist
        val f = (-e.viewDir).normalized()
        val r = (f cross V3.Up).normalized()
        val u = r cross f
        val t = kotlin.math.tan(Fov * 0.5f * Deg2Rad)
        val half = Px * 0.5f
        val sx = FloatArray(3)
        val sy = FloatArray(3)
        val sz = FloatArray(3)
        val shade = arrayOfNulls<V3>(3)
        val toLight = lightDir
        for (tri in e.tris) {
            val ws = arrayOf(tri.a, tri.b, tri.c)
            val ns = arrayOf(tri.na, tri.nb, tri.nc)
            var behind = false
            for (k in 0 until 3) {
                val w = rot * (ws[k] - e.center)
                val d = w - eye
                val z = d dot f
                if (z <= 0.001f) behind = true
                sz[k] = z
                sx[k] = half + (d dot r) / (z * t) * half
                sy[k] = half - (d dot u) / (z * t) * half
                // 頂点ごとに明るさを計算 (グーロー)
                val n = rot * ns[k]
                val viewV = (eye - w).normalized()
                shade[k] = shadeColor(tri, n, viewV, toLight)
            }
            if (behind) continue
            // 裏面は描かない (画面座標は y 下向きなので、表 = 時計回り)
            val area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sy[1] - sy[0]) * (sx[2] - sx[0])
            if (area >= 0f) continue
            val minX = max(0, floor(min(sx[0], min(sx[1], sx[2]))).toInt())
            val maxX = min(Px - 1, ceil(max(sx[0], max(sx[1], sx[2]))).toInt())
            val minY = max(0, floor(min(sy[0], min(sy[1], sy[2]))).toInt())
            val maxY = min(Px - 1, ceil(max(sy[0], max(sy[1], sy[2]))).toInt())
            if (minX > maxX || minY > maxY) continue
            val inv = 1f / area
            val c0 = shade[0]!!; val c1 = shade[1]!!; val c2 = shade[2]!!
            for (y in minY..maxY) {
                val py = y + 0.5f
                for (x in minX..maxX) {
                    val pxf = x + 0.5f
                    val w0 = ((sx[1] - pxf) * (sy[2] - py) - (sy[1] - py) * (sx[2] - pxf)) * inv
                    val w1 = ((sx[2] - pxf) * (sy[0] - py) - (sy[2] - py) * (sx[0] - pxf)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                    val z = w0 * sz[0] + w1 * sz[1] + w2 * sz[2]
                    val i = y * Px + x
                    if (z >= depth[i]) continue
                    depth[i] = z
                    val cr = w0 * c0.x + w1 * c1.x + w2 * c2.x
                    val cg = w0 * c0.y + w1 * c1.y + w2 * c2.y
                    val cb = w0 * c0.z + w1 * c1.z + w2 * c2.z
                    px[i] = (0xff shl 24) or ((cr * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                        ((cg * 255f).roundToInt().coerceIn(0, 255) shl 8) or (cb * 255f).roundToInt().coerceIn(0, 255)
                }
            }
        }
        val bmp = if (out != null && out.width == Px && out.height == Px && out.isMutable) out
        else Bitmap.createBitmap(Px, Px, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, Px, 0, 0, Px, Px)
        return bmp
    }

    /** 平行光 (強さ 0.95) + 環境光 + 鏡面反射 (金属感)。返す色は sRGB。 */
    private fun shadeColor(tri: Tri, n0: V3, view: V3, light: V3): V3 {
        if (tri.unlit) return tri.color
        var n = n0
        if ((n dot view) < 0f) n = -n
        val base = V3(srgbToLinear(tri.color.x), srgbToLinear(tri.color.y), srgbToLinear(tri.color.z))
        val diff = max(0f, n dot light) * 0.95f
        val ambient = 0.45f
        val h = (light + view).normalized()
        val shininess = 2f / max(0.02f, tri.roughness * tri.roughness) - 2f
        val spec = max(0f, n dot h).pow(shininess) * (0.04f + 0.6f * tri.metallic) * (shininess + 8f) / 25f
        val kd = 1f - tri.metallic * 0.6f
        val c = base * ((diff + ambient) * kd) + V3(spec, spec, spec) * (0.5f + 0.5f * diff)
        return V3(linearToSrgb(c.x), linearToSrgb(c.y), linearToSrgb(c.z))
    }

}
