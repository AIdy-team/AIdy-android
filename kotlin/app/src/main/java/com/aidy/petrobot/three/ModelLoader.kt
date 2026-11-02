package com.aidy.petrobot.three

import android.util.Log
import com.aidy.petrobot.AidyApplication
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.data.MountAxis
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 三角形メッシュ (頂点ごとに位置・法線、任意で UV)。indices は 3 つで 1 枚 (反時計回りが表)。
 * groups: 材質ごとの index の範囲 (start, count, 名前)。
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    val uvs: FloatArray? = null,
    val groups: List<Group> = listOf(Group(0, indices.size, "")),
) {
    class Group(val start: Int, val count: Int, val material: String)

    val vertexCount get() = positions.size / 3

    val min: V3 by lazy { bound(true) }
    val max: V3 by lazy { bound(false) }

    private fun bound(lo: Boolean): V3 {
        if (positions.isEmpty()) return V3.Zero
        var x = positions[0]; var y = positions[1]; var z = positions[2]
        for (i in 0 until vertexCount) {
            val px = positions[i * 3]; val py = positions[i * 3 + 1]; val pz = positions[i * 3 + 2]
            if (lo) { x = min(x, px); y = min(y, py); z = min(z, pz) } else { x = max(x, px); y = max(y, py); z = max(z, pz) }
        }
        return V3(x, y, z)
    }
}

/**
 * モジュールのモデル (STL / OBJ / 3MF) を読み、取り付け面ローカル (+Y=外向き, +Z=正面、単位 mm) のメッシュにする。
 * Unity 版 AidyModelLoader と同じ変換を Unity 座標で行ってから X を反転してこの座標 (右手系) にする。
 */
object ModelLoader {
    private const val CreaseAngle = 40f
    private val Formats = listOf("stl", "obj", "3mf")
    private val cache = HashMap<String, MeshData?>()

    fun load(kind: ModuleKind): MeshData? {
        val path = ModuleCatalog.modelPath(kind) ?: return null
        return load(path, ModuleCatalog.axis(kind))
    }

    /** basePath: assets 内の拡張子なしのパス ("models/tail2")。stl → obj → 3mf の順に探す。 */
    fun load(basePath: String, axis: MountAxis): MeshData? {
        synchronized(cache) { if (cache.containsKey(basePath)) return cache[basePath] }
        var m: MeshData? = null
        var bytes: ByteArray? = null
        var format: String? = null
        for (f in Formats) {
            bytes = try {
                AidyApplication.context.assets.open("$basePath.$f").use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (bytes != null) {
                format = f
                break
            }
        }
        if (bytes == null) Log.w("AIDY", "モデルが見つかりません: $basePath (.stl / .obj)")
        else {
            try {
                val tris = when (format) {
                    "obj" -> parseObj(bytes)
                    "3mf" -> parse3mf(bytes)
                    else -> parseStl(bytes)
                }
                m = build(tris, axis)
            } catch (e: Exception) {
                Log.w("AIDY", "モデルの読込に失敗 $basePath.$format: ${e.message}")
            }
        }
        synchronized(cache) { cache[basePath] = m }
        return m
    }

    // ------------------------------------------------------------ 読み込み (三角形ごとに 3 頂点、単位 mm)

    private fun parseStl(b: ByteArray): MutableList<V3> {
        val v = ArrayList<V3>()
        if (b.size >= 84) {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.getInt(80).toLong() and 0xffffffffL
            if (84 + 50L * n == b.size.toLong()) {
                for (i in 0 until n.toInt()) {
                    var o = 84 + 50 * i + 12
                    for (k in 0 until 3) {
                        v += V3(bb.getFloat(o), bb.getFloat(o + 4), bb.getFloat(o + 8))
                        o += 12
                    }
                }
                return toMm(v)
            }
        }
        for (raw in String(b, Charsets.US_ASCII).split('\n')) {
            val line = raw.trim()
            if (!line.startsWith("vertex")) continue
            val p = line.split(Regex("\\s+"))
            if (p.size < 4) continue
            v += V3(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
        }
        return toMm(v)
    }

    /** STL は単位を持たないので、全体が 1 未満ならメートルとみなして mm にする。 */
    private fun toMm(v: MutableList<V3>): MutableList<V3> {
        if (v.isEmpty()) return v
        var lo = v[0]
        var hi = v[0]
        for (p in v) {
            lo = V3.min(lo, p)
            hi = V3.max(hi, p)
        }
        val size = hi - lo
        if (max(size.x, max(size.y, size.z)) >= 1f) return v
        for (i in v.indices) v[i] = v[i] * 1000f
        return v
    }

    /**
     * OBJ (v と f だけ使う)。Blender の既定の書き出し (Y 上・-Z 前) を STL と同じ Z 上 (x, -z, y) に戻す。
     * 多角形の面は耳切り法で三角形に分ける。
     */
    private fun parseObj(b: ByteArray): MutableList<V3> {
        val verts = ArrayList<V3>()
        val tris = ArrayList<V3>()
        val poly = ArrayList<V3>()
        val ws = Regex("\\s+")
        for (raw in String(b, Charsets.UTF_8).split('\n')) {
            val line = raw.trim()
            val vertex = line.startsWith("v ")
            val face = line.startsWith("f ")
            if (!vertex && !face) continue
            val p = line.split(ws)
            if (vertex) {
                if (p.size >= 4) verts += V3(p[1].toFloat(), -p[3].toFloat(), p[2].toFloat())
                continue
            }
            poly.clear()
            for (i in 1 until p.size) {
                val s = p[i].substringBefore('/')
                val idx = s.toInt()
                poly += verts[if (idx > 0) idx - 1 else verts.size + idx] // 負の番号は末尾からの相対
            }
            triangulate(poly, tris)
        }
        return toMm(tris)
    }

    /** 平面の多角形を耳切り法で三角形に分けて output に足す (巻き順は元の多角形のまま)。 */
    fun triangulate(poly: List<V3>, output: MutableList<V3>) {
        val n = poly.size
        if (n < 3) return
        if (n == 3) {
            output.addAll(poly)
            return
        }
        // Newell 法で面の法線を求め、その平面に投影した 2D で耳を探す
        var normal = V3.Zero
        for (i in 0 until n) {
            val a = poly[i]
            val c = poly[(i + 1) % n]
            normal += V3((a.y - c.y) * (a.z + c.z), (a.z - c.z) * (a.x + c.x), (a.x - c.x) * (a.y + c.y))
        }
        normal = if (normal.sqrLength > 0f) normal.normalized() else V3.Forward
        val u = (normal cross (if (abs(normal.x) < 0.9f) V3.Right else V3.Up)).normalized()
        val w = normal cross u
        val px = FloatArray(n) { poly[it] dot u }
        val py = FloatArray(n) { poly[it] dot w }
        fun cross(a: Int, b: Int, c: Int) = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
        fun crossP(a: Int, b: Int, x: Float, y: Float) = (px[b] - px[a]) * (y - py[a]) - (py[b] - py[a]) * (x - px[a])
        fun inTri(p: Int, a: Int, b: Int, c: Int) =
            crossP(a, b, px[p], py[p]) >= 0f && crossP(b, c, px[p], py[p]) >= 0f && crossP(c, a, px[p], py[p]) >= 0f

        val rest = ArrayList<Int>(n)
        for (i in 0 until n) rest += i
        while (rest.size > 3) {
            var clipped = false
            var i = 0
            while (i < rest.size && !clipped) {
                val ia = rest[(i + rest.size - 1) % rest.size]
                val ib = rest[i]
                val ic = rest[(i + 1) % rest.size]
                if (cross(ia, ib, ic) > 0f) { // 凹んだ頂点は耳にならない
                    var inside = false
                    for (j in rest) if (j != ia && j != ib && j != ic && inTri(j, ia, ib, ic)) {
                        inside = true
                        break
                    }
                    if (!inside) {
                        output += poly[ia]
                        output += poly[ib]
                        output += poly[ic]
                        rest.removeAt(i)
                        clipped = true
                    }
                }
                i++
            }
            if (!clipped) break // 退化した面: 残りは扇形で埋める
        }
        for (i in 1 until rest.size - 1) {
            output += poly[rest[0]]
            output += poly[rest[i]]
            output += poly[rest[i + 1]]
        }
    }

    private val VertexRe = Regex("<vertex\\s+x=\"([^\"]+)\"\\s+y=\"([^\"]+)\"\\s+z=\"([^\"]+)\"")
    private val TriangleRe = Regex("<triangle\\s+v1=\"(\\d+)\"\\s+v2=\"(\\d+)\"\\s+v3=\"(\\d+)\"")
    private val MeshRe = Regex("<mesh>(.*?)</mesh>", RegexOption.DOT_MATCHES_ALL)

    /** 3MF (zip 内の 3D/3dmodel.model) の全オブジェクトのメッシュをまとめて読む。 */
    private fun parse3mf(b: ByteArray): MutableList<V3> {
        var xml: String? = null
        ZipInputStream(ByteArrayInputStream(b)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.endsWith(".model", ignoreCase = true)) {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
            }
        }
        val text = xml ?: throw IllegalStateException("3dmodel.model がありません")
        val unit = when (Regex("unit=\"(\\w+)\"").find(text)?.groupValues?.get(1)) {
            "micron" -> 0.001f
            "centimeter" -> 10f
            "inch" -> 25.4f
            "foot" -> 304.8f
            "meter" -> 1000f
            else -> 1f
        }
        val tris = ArrayList<V3>()
        for (mesh in MeshRe.findAll(text)) {
            val body = mesh.groupValues[1]
            val verts = VertexRe.findAll(body).map {
                V3(it.groupValues[1].toFloat(), it.groupValues[2].toFloat(), it.groupValues[3].toFloat()) * unit
            }.toList()
            for (t in TriangleRe.findAll(body)) {
                tris += verts[t.groupValues[1].toInt()]
                tris += verts[t.groupValues[2].toInt()]
                tris += verts[t.groupValues[3].toInt()]
            }
        }
        return tris
    }

    // ------------------------------------------------------------ 取り付け面ローカルへの変換 + 法線

    private fun build(src0: List<V3>, axis: MountAxis): MeshData? {
        var src = src0
        if (src.size < 3) return null
        var lo = src[0]
        var hi = src[0]
        for (p in src) {
            lo = V3.min(lo, p)
            hi = V3.max(hi, p)
        }
        if (axis == MountAxis.TopPadZUp) {
            src = firstPart(src, lo, hi)
            lo = src[0]
            hi = src[0]
            for (p in src) {
                lo = V3.min(lo, p)
                hi = V3.max(hi, p)
            }
        }
        val pocket = when (axis) {
            MountAxis.MagnetPlusX -> findPocket(src, lo, hi)
            MountAxis.TopPadZUp -> topPad(src, hi.z)
            else -> 0f to 0f
        }
        val count = src.size - src.size % 3
        val pos = Array(count) { i ->
            val p = src[i]
            // Unity 版と同じ取り付け面ローカル (Unity 座標) にしてから X 反転
            val u = when (axis) {
                MountAxis.ZUpFrontMinusY -> V3(-p.x, p.z - lo.z, -p.y)
                MountAxis.OutwardMinusY -> {
                    val cx = (lo.x + hi.x) * 0.5f
                    val cz = (lo.z + hi.z) * 0.5f
                    V3(-(p.x - cx), hi.y - p.y, p.z - cz)
                }
                MountAxis.SlotTabPlusY -> V3(-p.x, p.y - lo.y, p.z)
                MountAxis.TopPadZUp -> V3(p.x - pocket.first, hi.z - p.z, -(p.y - pocket.second))
                MountAxis.MagnetPlusX -> V3(-(p.z - pocket.second), hi.x - p.x, p.y - pocket.first)
            }
            u.mirrorX()
        }
        return withNormals(pos)
    }

    /** 三角形の並び (3 頂点ずつ) から、外向きの巻き順と折り目付きの法線を持つメッシュを作る。 */
    fun withNormals(pos: Array<V3>): MeshData {
        val count = pos.size - pos.size % 3
        // 外向きの面が表 (反時計回り) になるよう巻き順をそろえる (符号付き体積が負なら反転)
        var vol = 0.0
        for (i in 0 until count step 3) vol += (pos[i] dot (pos[i + 1] cross pos[i + 2])).toDouble()
        if (vol < 0) for (i in 0 until count step 3) {
            val t = pos[i + 1]; pos[i + 1] = pos[i + 2]; pos[i + 2] = t
        }

        // 面法線を求め、同じ位置で角度が CreaseAngle 以内の面だけ平均してなめらかにする (角は立てたまま)
        val tris = count / 3
        val faceN = Array(tris) { t -> (pos[t * 3 + 1] - pos[t * 3]) cross (pos[t * 3 + 2] - pos[t * 3]) } // 長さ = 面積の 2 倍
        val faceU = Array(tris) { faceN[it].normalized() }
        val shared = HashMap<Long, MutableList<Int>>()
        fun key(p: V3): Long {
            val x = (p.x * 1000f).roundToInt().toLong() and 0x1fffff
            val y = (p.y * 1000f).roundToInt().toLong() and 0x1fffff
            val z = (p.z * 1000f).roundToInt().toLong() and 0x1fffff
            return (x shl 42) or (y shl 21) or z
        }
        for (t in 0 until tris) for (k in 0 until 3) shared.getOrPut(key(pos[t * 3 + k])) { ArrayList(6) } += t
        val cosA = cos(Math.toRadians(CreaseAngle.toDouble())).toFloat()
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        for (t in 0 until tris) {
            val nt = faceU[t]
            for (k in 0 until 3) {
                val i = t * 3 + k
                var sx = 0f; var sy = 0f; var sz = 0f
                for (o in shared.getValue(key(pos[i]))) {
                    if ((faceU[o] dot nt) >= cosA) {
                        sx += faceN[o].x; sy += faceN[o].y; sz += faceN[o].z
                    }
                }
                var n = V3(sx, sy, sz)
                n = if (n.sqrLength > 0f) n.normalized() else nt
                positions[i * 3] = pos[i].x; positions[i * 3 + 1] = pos[i].y; positions[i * 3 + 2] = pos[i].z
                normals[i * 3] = n.x; normals[i * 3 + 1] = n.y; normals[i * 3 + 2] = n.z
            }
        }
        return MeshData(positions, normals, IntArray(count) { it })
    }

    /** X の真ん中に隙間があれば (左右 2 つ入りのモデル)、X が小さい側の三角形だけ返す。 */
    private fun firstPart(src: List<V3>, lo: V3, hi: V3): List<V3> {
        val mid = (lo.x + hi.x) * 0.5f
        val part = ArrayList<V3>()
        var i = 0
        while (i + 2 < src.size) {
            val x = max(src[i].x, max(src[i + 1].x, src[i + 2].x))
            if (min(src[i].x, min(src[i + 1].x, src[i + 2].x)) < mid && x >= mid) return src // 真ん中をまたぐ = 1 つのモデル
            if (x < mid) {
                part += src[i]; part += src[i + 1]; part += src[i + 2]
            }
            i += 3
        }
        return if (part.size >= 3) part else src
    }

    /** 上面 (Z = top) の平らな面の中心 (x, y)。 */
    private fun topPad(src: List<V3>, top: Float): Pair<Float, Float> {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in src) {
            if (p.z < top - 0.05f) continue
            x0 = min(x0, p.x); y0 = min(y0, p.y); x1 = max(x1, p.x); y1 = max(y1, p.y)
        }
        return (x0 + x1) * 0.5f to (y0 + y1) * 0.5f
    }

    /** +X 面のくぼみ (面から 3〜6mm 奥の底面) の中心 (y, z)。見つからなければ全体の中心。 */
    private fun findPocket(src: List<V3>, lo: V3, hi: V3): Pair<Float, Float> {
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (p in src) {
            if (p.x < hi.x - 6f || p.x > hi.x - 3f || p.z < lo.z + 3f || p.z > hi.z - 3f) continue
            y0 = min(y0, p.y); y1 = max(y1, p.y); z0 = min(z0, p.z); z1 = max(z1, p.z)
        }
        if (y0 > y1) return (lo.y + hi.y) * 0.5f to (lo.z + hi.z) * 0.5f
        return (y0 + y1) * 0.5f to (z0 + z1) * 0.5f
    }
}

/**
 * 本体モデル (.obj、usemtl で材質を分ける) の読み込み。WebDemo の buildScaledInstance と同じく
 * 「中心を原点へ → 最大辺が 1 になるよう一括スケール → X 軸 -90° 回転 (前面を +Z へ)」した形で返す。
 * OBJ の座標 (右手系) をそのまま使う。
 */
object BodyModel {
    private val cache = HashMap<String, MeshData?>()

    fun load(path: String): MeshData? {
        synchronized(cache) { if (cache.containsKey(path)) return cache[path] }
        val m = try {
            AidyApplication.context.assets.open(path).use { parse(it.readBytes()) }
        } catch (e: Exception) {
            Log.w("AIDY", "本体モデルの読込に失敗 $path: ${e.message}")
            null
        }
        synchronized(cache) { cache[path] = m }
        return m
    }

    private fun parse(b: ByteArray): MeshData? {
        val verts = ArrayList<V3>()
        val vns = ArrayList<V3>()
        // 材質ごとの三角形 (位置・法線)
        val groups = LinkedHashMap<String, Pair<ArrayList<V3>, ArrayList<V3?>>>()
        var current = groups.getOrPut("") { ArrayList<V3>() to ArrayList() }
        val ws = Regex("\\s+")
        val polyP = ArrayList<V3>()
        val polyN = ArrayList<V3?>()
        for (raw in String(b, Charsets.UTF_8).split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val p = line.split(ws)
            when (p[0]) {
                "v" -> verts += V3(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                "vn" -> vns += V3(p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                "usemtl" -> current = groups.getOrPut(if (p.size > 1) p[1] else "") { ArrayList<V3>() to ArrayList() }
                "f" -> {
                    polyP.clear()
                    polyN.clear()
                    for (i in 1 until p.size) {
                        val parts = p[i].split('/')
                        val vi = parts[0].toInt()
                        polyP += verts[if (vi > 0) vi - 1 else verts.size + vi]
                        val ni = if (parts.size >= 3 && parts[2].isNotEmpty()) parts[2].toInt() else 0
                        polyN += if (ni != 0) vns[if (ni > 0) ni - 1 else vns.size + ni] else null
                    }
                    // 扇形で分割 (本体は凸な面だけ)。凹んだ面があれば耳切り法
                    if (polyP.size == 3 || polyN.any { it == null }) {
                        val out = ArrayList<V3>()
                        ModelLoader.triangulate(polyP, out)
                        current.first.addAll(out)
                        repeat(out.size) { current.second += null }
                    } else {
                        for (i in 1 until polyP.size - 1) {
                            current.first += polyP[0]; current.first += polyP[i]; current.first += polyP[i + 1]
                            current.second += polyN[0]; current.second += polyN[i]; current.second += polyN[i + 1]
                        }
                    }
                }
            }
        }
        if (verts.isEmpty()) return null
        var lo = verts[0]
        var hi = verts[0]
        for (v in verts) {
            lo = V3.min(lo, v)
            hi = V3.max(hi, v)
        }
        val center = (lo + hi) * 0.5f
        val size = hi - lo
        var maxDim = max(size.x, max(size.y, size.z))
        if (maxDim <= 0f) maxDim = 1f
        val rot = Q.euler(-Math.PI.toFloat() / 2f, 0f, 0f)

        val total = groups.values.sumOf { it.first.size }
        val positions = FloatArray(total * 3)
        val normals = FloatArray(total * 3)
        val outGroups = ArrayList<MeshData.Group>()
        var o = 0
        for ((name, g) in groups) {
            val (ps, ns) = g
            if (ps.isEmpty()) continue
            val start = o
            for (t in 0 until ps.size / 3) {
                val a = ps[t * 3]; val bb = ps[t * 3 + 1]; val c = ps[t * 3 + 2]
                val fn = ((bb - a) cross (c - a)).normalized()
                for (k in 0 until 3) {
                    val pp = rot * ((ps[t * 3 + k] - center) / maxDim)
                    val nn = rot * (ns[t * 3 + k] ?: fn)
                    positions[o * 3] = pp.x; positions[o * 3 + 1] = pp.y; positions[o * 3 + 2] = pp.z
                    normals[o * 3] = nn.x; normals[o * 3 + 1] = nn.y; normals[o * 3 + 2] = nn.z
                    o++
                }
            }
            outGroups += MeshData.Group(start, o - start, name)
        }
        return MeshData(positions, normals, IntArray(total) { it }, groups = outGroups)
    }
}
