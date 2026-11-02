package com.aidy.petrobot.three

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Transform
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.normalToTangent
import io.github.sceneview.material.setColor
import io.github.sceneview.node.MeshNode
import io.github.sceneview.node.Node
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/** Node に位置・回転・拡大をまとめて設定する。 */
fun Node.setLocal(p: V3 = V3.Zero, r: Q = Q.Identity, s: V3 = V3.One) {
    transform = Transform(p.toFloat3(), r.toQuaternion(), s.toFloat3())
}

fun Node.setLocal(p: V3, r: Q, s: Float) = setLocal(p, r, V3(s, s, s))

/** 子も含めて Filament の実体を消す。 */
fun Node.destroyTree() {
    for (c in childNodes.toList()) c.destroyTree()
    destroy()
}

/**
 * Filament のメッシュ・材質の生成 (Unity 版 AidyMeshes / AidyMaterials に相当)。エンジンごとに 1 つ。
 * 頂点は常に 位置・接線 (法線から)・UV を持つ (照明あり・なしどちらの材質でも使える)。
 */
class Gfx(val engine: Engine, val materials: MaterialLoader) {
    class GpuMesh(val vb: VertexBuffer, val ibs: List<IndexBuffer>, val counts: List<Int>, val box: Box)

    private val gpuCache = HashMap<MeshData, GpuMesh>()
    private val unlitCache = HashMap<Int, MaterialInstance>()

    fun gpu(data: MeshData): GpuMesh = gpuCache.getOrPut(data) { upload(data) }

    private fun upload(d: MeshData): GpuMesh {
        val n = d.vertexCount
        val vb = VertexBuffer.Builder()
            .bufferCount(3)
            .vertexCount(n)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        val pos = floats(n * 3)
        pos.put(d.positions).flip()
        val tan = floats(n * 4)
        for (i in 0 until n) {
            val q = normalToTangent(Float3(d.normals[i * 3], d.normals[i * 3 + 1], d.normals[i * 3 + 2]))
            tan.put(q.x).put(q.y).put(q.z).put(q.w)
        }
        tan.flip()
        val uv = floats(n * 2)
        if (d.uvs != null) uv.put(d.uvs) else repeat(n * 2) { uv.put(0f) }
        uv.flip()
        vb.setBufferAt(engine, 0, pos)
        vb.setBufferAt(engine, 1, tan)
        vb.setBufferAt(engine, 2, uv)

        val ibs = ArrayList<IndexBuffer>()
        val counts = ArrayList<Int>()
        for (g in d.groups) {
            val ib = IndexBuffer.Builder().indexCount(g.count).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
            val buf = ByteBuffer.allocateDirect(g.count * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            buf.put(d.indices, g.start, g.count).flip()
            ib.setBuffer(engine, buf)
            ibs += ib
            counts += g.count
        }
        val lo = d.min
        val hi = d.max
        val c = (lo + hi) * 0.5f
        val h = (hi - lo) * 0.5f
        return GpuMesh(vb, ibs, counts, Box(c.x, c.y, c.z, maxOf(h.x, 1e-4f), maxOf(h.y, 1e-4f), maxOf(h.z, 1e-4f)))
    }

    private fun floats(n: Int) = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // ------------------------------------------------------------ 材質

    /** MeshStandardMaterial(color, metalness, roughness)。argb のアルファが 1 未満なら半透明。 */
    fun lit(argb: Int, metallic: Float, roughness: Float): MaterialInstance =
        materials.createColorInstance(argb, metallic, roughness)

    fun lit(r: Float, g: Float, b: Float, metallic: Float, roughness: Float): MaterialInstance =
        materials.createColorInstance(colorOf(r, g, b, 1f), metallic, roughness)

    /** 照明なしの単色 (MeshBasicMaterial)。半透明は照明ありの半透明材質で代用する。 */
    fun unlit(argb: Int): MaterialInstance {
        if ((argb ushr 24) != 0xff) return materials.createColorInstance(argb, 0f, 1f, 0f)
        return unlitCache.getOrPut(argb) { materials.createImageInstance(solidTexture(argb)) }
    }

    /** 照明なしの単色 (色を後から変える用、共有しない)。 */
    fun unlitOwn(argb: Int): Pair<MaterialInstance, Texture> {
        val t = solidTexture(argb)
        return materials.createImageInstance(t) to t
    }

    fun setUnlitColor(t: Texture, argb: Int) = upload(t, 1, 1, intArrayOf(argb))

    fun setLitColor(m: MaterialInstance, argb: Int) = m.setColor(argb)

    /** 照明なしのテクスチャ材質。 */
    fun unlitTexture(t: Texture): MaterialInstance = materials.createImageInstance(t)

    fun texture(w: Int, h: Int): Texture = Texture.Builder()
        .width(w).height(h).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .build(engine)

    private fun solidTexture(argb: Int) = texture(1, 1).also { upload(it, 1, 1, intArrayOf(argb)) }

    /** ARGB (上の行から) を書き込む。上の行が v=0。 */
    fun upload(t: Texture, w: Int, h: Int, argb: IntArray) {
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        for (i in 0 until w * h) {
            val c = argb[i]
            buf.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
        }
        buf.flip()
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
    }

    // ------------------------------------------------------------ ノード

    fun empty(parent: Node?, name: String? = null): Node = Node(engine).also {
        it.name = name
        it.isTouchable = false
        parent?.addChildNode(it)
    }

    /** メッシュ (材質グループが複数なら groups の順に materials を割り当てる)。 */
    fun mesh(
        parent: Node?,
        data: MeshData,
        material: MaterialInstance,
        castShadows: Boolean = false,
        receiveShadows: Boolean = false,
        group: Int = 0,
        name: String? = null,
    ): MeshNode {
        val g = gpu(data)
        val node = MeshNode(
            engine, RenderableManager.PrimitiveType.TRIANGLES, g.vb, g.ibs[group], g.box, material,
        ) {
            castShadows(castShadows)
            receiveShadows(receiveShadows)
        }
        node.name = name
        node.isTouchable = false
        node.isEditable = false
        parent?.addChildNode(node)
        return node
    }

    fun destroy() {
        for (g in gpuCache.values) {
            engine.destroyVertexBuffer(g.vb)
            g.ibs.forEach { engine.destroyIndexBuffer(it) }
        }
        gpuCache.clear()
    }
}

/** three.js の各 Geometry と同じ寸法・向きのメッシュ (右手系、反時計回りが表)。 */
object Meshes {
    private class B {
        val p = ArrayList<Float>()
        val n = ArrayList<Float>()
        val uv = ArrayList<Float>()
        val idx = ArrayList<Int>()
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float = 0f, w: Float = 0f): Int {
            p += x; p += y; p += z
            n += nx; n += ny; n += nz
            uv += u; uv += w
            return p.size / 3 - 1
        }
        fun t(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }
        fun build() = MeshData(p.toFloatArray(), n.toFloatArray(), idx.toIntArray(), uv.toFloatArray())
    }

    /** PlaneGeometry(w, h): XY 平面、+Z 向き。UV は左上が (0,0) (画像の上の行が上)。 */
    fun plane(w: Float, h: Float): MeshData {
        val x = w * 0.5f
        val y = h * 0.5f
        val b = B()
        val a = b.v(-x, -y, 0f, 0f, 0f, 1f, 0f, 1f)
        val c = b.v(x, -y, 0f, 0f, 0f, 1f, 1f, 1f)
        val d = b.v(x, y, 0f, 0f, 0f, 1f, 1f, 0f)
        val e = b.v(-x, y, 0f, 0f, 0f, 1f, 0f, 0f)
        b.t(a, c, d)
        b.t(a, d, e)
        return b.build()
    }

    /** CircleGeometry(r, segments): XY 平面、+Z 向き。 */
    fun circle(r: Float, seg: Int): MeshData {
        val b = B()
        val c = b.v(0f, 0f, 0f, 0f, 0f, 1f, 0.5f, 0.5f)
        for (i in 0..seg) {
            val a = i.toFloat() / seg * PI * 2f
            b.v(cos(a) * r, sin(a) * r, 0f, 0f, 0f, 1f, 0.5f + cos(a) * 0.5f, 0.5f - sin(a) * 0.5f)
        }
        for (i in 0 until seg) b.t(c, c + 1 + i, c + 2 + i)
        return b.build()
    }

    /** RingGeometry(inner, outer, segments): XY 平面、+Z 向き。 */
    fun ring(inner: Float, outer: Float, seg: Int): MeshData {
        val b = B()
        for (i in 0..seg) {
            val a = i.toFloat() / seg * PI * 2f
            b.v(cos(a) * inner, sin(a) * inner, 0f, 0f, 0f, 1f)
            b.v(cos(a) * outer, sin(a) * outer, 0f, 0f, 0f, 1f)
        }
        for (i in 0 until seg) {
            val a = i * 2
            b.t(a, a + 1, a + 3)
            b.t(a, a + 3, a + 2)
        }
        return b.build()
    }

    /** CylinderGeometry(radiusTop, radiusBottom, height, segments): Y 軸、中心原点、上下キャップ付き。 */
    fun cylinder(rTop: Float, rBottom: Float, height: Float, seg: Int): MeshData {
        val hy = height * 0.5f
        val slope = (rBottom - rTop) / height
        val b = B()
        val top0 = b.p.size / 3
        for (i in 0..seg) {
            val a = i.toFloat() / seg * PI * 2f
            val s = sin(a)
            val c = cos(a)
            val nn = V3(s, slope, c).normalized()
            b.v(rTop * s, hy, rTop * c, nn.x, nn.y, nn.z)
            b.v(rBottom * s, -hy, rBottom * c, nn.x, nn.y, nn.z)
        }
        for (i in 0 until seg) {
            val t0 = top0 + i * 2
            val b0 = t0 + 1
            val t1 = t0 + 2
            val b1 = t0 + 3
            // 外から見て反時計回り
            b.t(t0, b0, b1)
            b.t(t0, b1, t1)
        }
        val tc = b.v(0f, hy, 0f, 0f, 1f, 0f)
        val bc = b.v(0f, -hy, 0f, 0f, -1f, 0f)
        val tr = b.p.size / 3
        for (i in 0..seg) {
            val a = i.toFloat() / seg * PI * 2f
            b.v(rTop * sin(a), hy, rTop * cos(a), 0f, 1f, 0f)
        }
        val br = b.p.size / 3
        for (i in 0..seg) {
            val a = i.toFloat() / seg * PI * 2f
            b.v(rBottom * sin(a), -hy, rBottom * cos(a), 0f, -1f, 0f)
        }
        for (i in 0 until seg) {
            b.t(tc, tr + i, tr + i + 1)
            b.t(bc, br + i + 1, br + i)
        }
        return b.build()
    }

    /** 立方体 (一辺 1、中心原点)。 */
    fun cube(): MeshData {
        val b = B()
        val faces = listOf(
            V3.Right, -V3.Right, V3.Up, -V3.Up, V3.Forward, -V3.Forward,
        )
        for (nrm in faces) {
            val u = if (kotlin.math.abs(nrm.y) > 0.5f) V3.Right else V3.Up
            val t1 = (u cross nrm).normalized()
            val t2 = nrm cross t1
            val c = nrm * 0.5f
            val corners = listOf(c - t1 * 0.5f - t2 * 0.5f, c + t1 * 0.5f - t2 * 0.5f, c + t1 * 0.5f + t2 * 0.5f, c - t1 * 0.5f + t2 * 0.5f)
            val i0 = corners.map { b.v(it.x, it.y, it.z, nrm.x, nrm.y, nrm.z) }
            // (t1, t2, n) が右手系なので t1→t2 の順は n から見て反時計回り
            b.t(i0[0], i0[1], i0[2])
            b.t(i0[0], i0[2], i0[3])
        }
        return b.build()
    }

    /** 星形 (XY 平面、+Z 向き、両面)。 */
    fun star(outer: Float, inner: Float, points: Int = 5): MeshData {
        val b = B()
        for (side in 0 until 2) {
            val nz = if (side == 0) 1f else -1f
            val c = b.v(0f, 0f, 0f, 0f, 0f, nz)
            val first = b.p.size / 3
            for (i in 0..points * 2) {
                val a = PI / 2f + i * PI / points
                val r = if (i % 2 == 0) outer else inner
                b.v(cos(a) * r, sin(a) * r, 0f, 0f, 0f, nz)
            }
            for (i in 0 until points * 2) {
                if (side == 0) b.t(c, first + i, first + i + 1) else b.t(c, first + i + 1, first + i)
            }
        }
        return b.build()
    }

    /** Z の字 (幅 1、太さ t の 3 本の帯、XY 平面、両面)。 */
    fun zGlyph(t: Float = 0.18f): MeshData {
        val b = B()
        fun bar(ax: Float, ay: Float, bx: Float, by: Float) {
            val d = V3(bx - ax, by - ay, 0f).normalized()
            val nx = -d.y * t * 0.5f
            val ny = d.x * t * 0.5f
            for (side in 0 until 2) {
                val nz = if (side == 0) 1f else -1f
                val p0 = b.v(ax - nx, ay - ny, 0f, 0f, 0f, nz)
                val p1 = b.v(bx - nx, by - ny, 0f, 0f, 0f, nz)
                val p2 = b.v(bx + nx, by + ny, 0f, 0f, 0f, nz)
                val p3 = b.v(ax + nx, ay + ny, 0f, 0f, 0f, nz)
                if (side == 0) { b.t(p0, p1, p2); b.t(p0, p2, p3) } else { b.t(p0, p2, p1); b.t(p0, p3, p2) }
            }
        }
        bar(-0.5f, 0.5f, 0.5f, 0.5f)
        bar(0.5f, 0.5f, -0.5f, -0.5f)
        bar(-0.5f, -0.5f, 0.5f, -0.5f)
        return b.build()
    }
}
