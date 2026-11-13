package com.aidy.petrobot.three

import com.aidy.petrobot.data.BodyColor
import com.aidy.petrobot.data.ColorPattern
import com.aidy.petrobot.data.BodyLayout
import com.aidy.petrobot.data.BodySlot
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleConfig
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.ui.FaceScreen
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import io.github.sceneview.node.MeshNode
import io.github.sceneview.node.Node
import kotlin.math.abs
import kotlin.math.sin

/** 本体・モジュールの材質の色。 */
object AidyColors {
    const val Satin = 0xffa0a0a0.toInt()          // 0.627 (モジュール: metal 0.55 / rough 0.35)
    const val SteelSatin = 0xffa0a0a0.toInt()     // 本体: metal 0.35 / rough 0.4
    const val EnamelWhite = 0xfff6f6f3.toInt()    // 顔パーツ: metal 0 / rough 0.15
    const val Lens = 0xfffef9c3.toInt()
}

/**
 * モジュールの形 (Filament に依存しない記述)。ホーム・AR の 3D とモジュール一覧のプレビュー (ソフトウェア描画) で共通。
 * 座標は取り付け面ローカル (+Y=外向き, +Z=正面 or 上)、size = 本体の一辺 (シーン単位)。
 */
class ModuleShape(val pivots: List<Pose>, val parts: List<Part>) {
    enum class Mat { Satin, Lens }

    /** pivot = -1 なら取り付け面そのもの、それ以外は pivots の番号 (振る部分)。 */
    class Part(val mesh: MeshData, val pivot: Int, val local: Pose, val mat: Mat)

    companion object {
        private val fallbackCache = HashMap<String, MeshData>()

        private fun cached(key: String, make: () -> MeshData) = synchronized(fallbackCache) { fallbackCache.getOrPut(key, make) }

        /** Unity 版 AidyModuleRig.BuildModule と同じ形・向き。slot は向きの調整に使う (null なら背面スロット向き)。 */
        fun build(kind: ModuleKind, slot: BodySlot?, size: Float): ModuleShape {
            val mm = size / 150f
            val k = size / 1.1f
            val pivots = ArrayList<Pose>()
            val parts = ArrayList<Part>()
            when (kind) {
                ModuleKind.Hand, ModuleKind.HandLight -> {
                    // 手: マグネットのくぼみを本体に当て、本体の側面に沿って正面へ伸びる。くぼみを軸に上下に振る
                    pivots += Pose()
                    val hand = ModelLoader.load(kind)
                    if (hand != null) {
                        parts += Part(hand, 0, Pose(s = V3(mm, mm, mm)), Mat.Satin)
                        if (kind == ModuleKind.HandLight) {
                            // 懐中電灯のレンズ (長手方向の後ろ端) を光らせる
                            val c = (hand.min + hand.max) * 0.5f
                            val lens = cached("lens") { Meshes.circle(5.5f, 24) }
                            parts += Part(lens, 0, Pose(V3(c.x, c.y, hand.min.z - 0.3f) * mm, uRot(180f, V3.Up), V3(mm, mm, mm)), Mat.Lens)
                        }
                    } else {
                        val cyl = cached("hand") { Meshes.cylinder(15f, 15f, 80f, 16) }
                        parts += Part(cyl, 0, Pose(uPos(0f, 15f, 25f) * mm, uRot(90f, V3.Right), V3(mm, mm, mm)), Mat.Satin)
                    }
                }
                ModuleKind.Hat, ModuleKind.Tv -> {
                    // シルクハット・ブラウン管テレビ: 平らな底を上面に置くだけ (動かない)
                    val hat = ModelLoader.load(kind)
                    if (hat != null) parts += Part(hat, -1, Pose(s = V3(mm, mm, mm)), Mat.Satin)
                    else {
                        val cyl = cached("hat") { Meshes.cylinder(25f, 25f, 35f, 24) }
                        parts += Part(cyl, -1, Pose(V3(0f, 17.5f * mm, 0f), s = V3(mm, mm, mm)), Mat.Satin)
                    }
                }
                ModuleKind.Ear -> {
                    val ear = ModelLoader.load(kind)
                    if (ear != null) parts += Part(ear, -1, Pose(s = V3(mm, mm, mm)), Mat.Satin)
                    else {
                        val cyl = cached("ear") { Meshes.cylinder(0.08f, 0.14f, 0.3f, 16) }
                        parts += Part(cyl, -1, Pose(V3(0f, 0.15f * k, 0f), s = V3(k, k, k)), Mat.Satin)
                    }
                }
                ModuleKind.Tail45, ModuleKind.TailCurl -> {
                    // 根元で左右に振る (wag)。台座をスロットに当て、+Z (背面なら上) へ反る
                    pivots += Pose()
                    val tail = ModelLoader.load(kind)
                    if (tail != null) {
                        // 差し込み部の長手 (モデルの X) をスロットの長手に合わせる。上面のスロットは前後向きなので横へ反る
                        val longLocal = if (slot != null) slot.mountRotation.inverse() * slot.longAxis else V3.Right
                        val r = if (abs(longLocal.z) > 0.5f) uRot(90f, V3.Up) else Q.Identity
                        parts += Part(tail, 0, Pose(r = r, s = V3(mm, mm, mm)), Mat.Satin)
                    } else {
                        // モデルがなければ少し持ち上げた棒
                        val tilt = uRot(35f, V3.Right)
                        val cyl = cached("tail") { Meshes.cylinder(0.03f, 0.04f, 0.45f, 16) }
                        parts += Part(cyl, 0, Pose(tilt * V3(0f, 0.2f * k, 0f), tilt, V3(k, k, k)), Mat.Satin)
                    }
                }
                ModuleKind.Legs -> {
                    // 足v1.3mf: 1 スロットに 1 つ。上面 (本体に当たる面) を pivot にして前後に振る
                    val foot = ModelLoader.load(kind)
                    if (foot != null) {
                        pivots += Pose()
                        parts += Part(foot, 0, Pose(s = V3(mm, mm, mm)), Mat.Satin)
                    } else {
                        // モデルがなければ前後 2 本の円柱
                        val cyl = cached("leg") { Meshes.cylinder(0.08f, 0.1f, 0.28f, 16) }
                        for (z in floatArrayOf(0.35f, -0.35f)) {
                            pivots += Pose(V3(0f, 0f, z * k))
                            parts += Part(cyl, pivots.size - 1, Pose(V3(0f, 0.1f * k, 0f), s = V3(k, k, k)), Mat.Satin)
                        }
                    }
                }
                ModuleKind.Face -> Unit
            }
            return ModuleShape(pivots, parts)
        }
    }
}

/**
 * 本体の顔モニターの画面 (FaceScreen の絵を貼った板)。寸法は body_v2_layout.json の monitor
 * (74.8x51mm、顔パネルは正面から 5mm 奥)。
 */
class FaceScreenNode(gfx: Gfx, parent: Node, bodySize: Float) {
    val face = FaceScreen()
    private val gfx = gfx
    private val tex: Texture = gfx.texture(FaceScreen.TexW, FaceScreen.TexH)
    val node: MeshNode

    init {
        val monitor = BodyLayout.get("monitor")
        val mm = bodySize / 150f
        val w = (monitor?.widthMm ?: 74.8f) * mm
        val h = (monitor?.heightMm ?: 51f) * mm
        val panelDepthMm = 5f // 外装の厚み
        val c = if (monitor != null) monitor.position * bodySize - monitor.normal * (panelDepthMm * mm) else V3(0f, 12.5f * mm, 70f * mm)
        node = gfx.mesh(parent, Meshes.plane(w, h), gfx.unlitTexture(tex), name = "FaceScreen")
        node.setLocal(c + V3(0f, 0f, 0.3f * mm)) // 顔パネル (白) の少し手前
        gfx.upload(tex, FaceScreen.TexW, FaceScreen.TexH, face.pixels)
    }

    var isVisible: Boolean
        get() = node.isVisible
        set(v) {
            node.isVisible = v
            face.visible = v
        }

    /** 毎フレーム呼ぶ。 */
    fun update() {
        face.visible = node.isVisible
        if (face.update()) gfx.upload(tex, FaceScreen.TexW, FaceScreen.TexH, face.pixels)
    }
}

/** 顔あり/顔なしの両モデル。顔パーツの着脱で表示を切り替える。 */
class BodyVariants(private val gfx: Gfx, parent: Node, targetSize: Float, color: Int) {
    /** 外装 (色を変える部分) の材質。 */
    private val shellMats = ArrayList<MaterialInstance>()
    val withFace: Node? = build(gfx, parent, targetSize, "models/body_face.obj", color = color, shellMats = shellMats)
    val plain: Node? = build(gfx, parent, targetSize, "models/body.obj", color = color, shellMats = shellMats)

    /** 外装の色を変える (0xffRRGGBB)。 */
    fun setColor(argb: Int) {
        for (m in shellMats) gfx.setLitColor(m, argb)
    }
    /** 顔モニターの画面 (本体の faces.py と同じ顔)。顔ありモデルのときだけ表示する。 */
    val screen: FaceScreenNode? = if (withFace != null) FaceScreenNode(gfx, parent, targetSize).also { it.isVisible = false } else null

    val any get() = withFace != null || plain != null

    /** 戻り値は顔ありモデルを表示したか。 */
    fun setFace(on: Boolean): Boolean {
        val useFace = on && withFace != null
        withFace?.isVisible = useFace
        plain?.isVisible = !useFace
        screen?.isVisible = useFace
        return useFace
    }

    companion object {
        /** parent 配下に pivot を作って返す。モデルがなければ null。 */
        fun build(
            gfx: Gfx, parent: Node, targetSize: Float, path: String, shadows: Boolean = true,
            color: Int = BodyColor.Default, shellMats: MutableList<MaterialInstance>? = null,
        ): Node? {
            val data = BodyModel.load(path) ?: return null
            val pivot = gfx.empty(parent, "BodyPivot")
            pivot.setLocal(s = V3(targetSize, targetSize, targetSize))
            for ((i, g) in data.groups.withIndex()) {
                val mat = when {
                    g.material.contains("EnamelWhite", ignoreCase = true) -> gfx.lit(0.964706f, 0.964706f, 0.952941f, 0f, 0.15f)
                    else -> gfx.lit(color, 0.35f, 0.4f).also { shellMats?.add(it) }
                }
                gfx.mesh(pivot, data, mat, shadows, shadows, group = i)
            }
            // 外装 (外形150mm・厚さ5mm) の内側の空洞を黒い箱で埋め、穴から中身が見えないようにする (内壁とのちらつきを防ぐため 139.8mm)
            val box = gfx.mesh(pivot, cube, gfx.unlit(0xff000000.toInt()), name = "InteriorOccluder")
            val s = 139.8f / 150f
            box.setLocal(s = V3(s, s, s))
            return pivot
        }

        private val cube by lazy { Meshes.cube() }
    }
}

/**
 * 本体 (顔あり/顔なし) + ModuleConfig のモジュールを 3D で組み立てる。ホーム・付け替えプレビュー・AR で共通 (Unity 版 AidyModuleRig)。
 * 寸法は body_v2_layout.json: 本体 150mm 角 = size (シーン単位)。
 * editable のときは、面の選択ハイライトとスロットの目印 (ドロップ先) も作る。
 */
class ModuleRig(
    private val gfx: Gfx,
    val body: Node,
    private val size: Float,
    private val editable: Boolean,
    /** 決まった構成で組む (名刺マーカーの標準 Aidy)。null なら ModuleConfig (いまの着せ替え) に従う。 */
    private val fixedSlots: Map<String, ModuleKind>? = null,
    shadows: Boolean = true,
    /** 決まった色のパターン (着せ替えマーカー)。null なら BodyColor.pattern (いまの色) に従う。 */
    private val fixedColor: ColorPattern? = null,
) {
    private val k = size / 1.1f // 足・尻尾はホームの本体 (1.1) を基準にした寸法
    /** いま表示している色のパターン。 */
    var pattern: ColorPattern = fixedColor ?: BodyColor.pattern
        private set
    /** 取り付け口ごとのパーツの材質 (パーツを付け替えても使い回す)。 */
    private val slotMats = HashMap<String, MaterialInstance>()
    private fun slotMat(id: String) = slotMats.getOrPut(id) { gfx.lit(pattern.partColor(id), 0.55f, 0.35f) }
    private val lens: MaterialInstance = gfx.unlit(AidyColors.Lens)
    private val shadows = shadows
    val bodies = BodyVariants(gfx, body, size, pattern.body)

    /** いまの色に従うとき: BodyColor が変わったら外装の色を変える。release で外す。 */
    private val colorListener: ((ColorPattern) -> Unit)? = if (fixedColor == null) ({ p: ColorPattern -> applyPattern(p) }).also { BodyColor.changed += it } else null

    /** 本体とパーツの色を変える (選んでいる途中の見た目にも使う)。 */
    fun applyPattern(p: ColorPattern) {
        pattern = p
        bodies.setColor(p.body)
        for ((id, m) in slotMats) gfx.setLitColor(m, p.partColor(id))
    }

    /** 取り付け済みのパーツ (取り付け口 → 種類)。 */
    val attached: Map<String, ModuleKind> get() = mounts.mapValues { it.value.kind }

    /** このリグを捨てるとき (色の通知を外す)。 */
    fun release() {
        colorListener?.let { BodyColor.changed -= it }
    }

    private class Mount(val kind: ModuleKind, val root: Node) {
        val swing = ArrayList<Node>()
        val swingBase = ArrayList<Pose>()
        val swingNow = ArrayList<Pose>()
        /** 見た目の外形 (各部品の箱の角)。first = 振る部分の番号 (-1 は取り付け面)、second = その座標系での点。 */
        val hull = ArrayList<Pair<Int, V3>>()
    }

    /**
     * 取り付け済みモジュールの外形の点 (取り付け面ローカル)。付いていなければ空。
     * 尻尾・手・足は今振っている角度で返す (画面でつかむ判定が動きについていくように)。
     */
    fun hullOf(slotId: String): List<V3> {
        val m = mounts[slotId] ?: return emptyList()
        return m.hull.map { (j, v) -> if (j < 0) v else m.swingNow[j].point(v) }
    }

    class SlotFrame(val slot: BodySlot, val node: Node) {
        var marker: MeshNode? = null
        var markerTex: Texture? = null
    }

    private val mounts = LinkedHashMap<String, Mount>()
    private val frames = LinkedHashMap<String, SlotFrame>()
    private var highlight: MeshNode? = null
    private var selectedFace: String? = null
    private var dropKind: ModuleKind? = null
    private var dropHover: String? = null

    val hasModel get() = bodies.any
    var faceOn = false
        private set
    val frameList: Collection<SlotFrame> get() = frames.values

    init {
        buildFrames()
        sync()
    }

    /** 足の先が本体の底面からどれだけ下に出ているか。 */
    val legReach: Float
        get() {
            if (!hasLegs) return 0f
            val leg = ModelLoader.load(ModuleKind.Legs)
            return if (leg != null) leg.max.y * size / 150f else 0.24f * k
        }

    val hasLegs get() = mounts.values.any { it.kind == ModuleKind.Legs }

    /** ModuleConfig に合わせてモジュールを作り直す。 */
    fun sync() {
        val want = fixedSlots ?: ModuleConfig.current
        for (id in mounts.keys.toList()) {
            if (want[id] == mounts[id]!!.kind) continue
            mounts.remove(id)!!.root.destroyTree()
        }
        for ((id, kind) in want) {
            if (mounts.containsKey(id)) continue
            val f = frames[id] ?: continue
            val root = gfx.empty(f.node, kind.name)
            val m = Mount(kind, root)
            val shape = ModuleShape.build(kind, f.slot, size)
            for (p in shape.pivots) {
                val n = gfx.empty(root)
                n.setLocal(p.p, p.r, p.s)
                m.swing += n
                m.swingBase += p
                m.swingNow += p
            }
            for (part in shape.parts) {
                val parent = if (part.pivot < 0) root else m.swing[part.pivot]
                val mat = if (part.mat == ModuleShape.Mat.Lens) lens else slotMat(id)
                val node = gfx.mesh(parent, part.mesh, mat, shadows && part.mat == ModuleShape.Mat.Satin, shadows && part.mat == ModuleShape.Mat.Satin)
                node.setLocal(part.local.p, part.local.r, part.local.s)
                val lo = part.mesh.min
                val hi = part.mesh.max
                for (c in 0 until 8) {
                    val v = V3(if (c and 1 == 0) lo.x else hi.x, if (c and 2 == 0) lo.y else hi.y, if (c and 4 == 0) lo.z else hi.z)
                    m.hull += part.pivot to part.local.point(v)
                }
            }
            mounts[id] = m
        }
        faceOn = want.values.any { it == ModuleKind.Face }
        bodies.setFace(faceOn)
        updateMarkers()
    }

    /** 毎フレーム (顔モニターの絵)。 */
    fun update() {
        bodies.screen?.update()
    }

    val faceScreen: FaceScreen? get() = if (faceOn) bodies.screen?.face else null

    // ------------------------------------------------------------ アニメーション

    private fun setSwing(m: Mount, j: Int, r: Q) {
        val b = m.swingBase[j]
        val now = Pose(b.p, b.r * r, b.s)
        m.swingNow[j] = now
        m.swing[j].setLocal(now.p, now.r, now.s)
    }

    fun swingLegs(amount: Float, phase: Float) {
        var slot = 0
        for (m in mounts.values) {
            if (m.kind != ModuleKind.Legs) continue
            for (j in m.swing.indices) {
                // 同じスロットの前後、隣のスロットどうしは逆位相
                val a = sin(phase + if ((j + slot) % 2 == 0) 0f else PI) * amount
                setSwing(m, j, uRot(a * Rad2Deg, V3.Right))
            }
            slot++
        }
    }

    /** 手を上下に振る (取り付け面の法線まわり = 腕の先が上下に動く)。 */
    fun waveHands(angle: Float) {
        for (m in mounts.values)
            if (m.kind == ModuleKind.Hand || m.kind == ModuleKind.HandLight)
                for (j in m.swing.indices) setSwing(m, j, uRot(angle * Rad2Deg, V3.Up))
    }

    fun wagTails(angle: Float) {
        for (m in mounts.values)
            if (ModuleCatalog.isTail(m.kind) && m.kind != ModuleKind.TailCurl) // 巻き尻尾は面に沿わせているので振らない
                for (j in m.swing.indices) setSwing(m, j, uRot(angle * Rad2Deg, V3.Forward))
    }

    // ------------------------------------------------------------ スロット (取り付け面の座標系) と目印

    private fun buildFrames() {
        val disc = if (editable) Meshes.circle(0.065f * k, 32) else null
        for (s in BodyLayout.slots) {
            val t = gfx.empty(body, "Slot_${s.id}")
            t.setLocal(s.position * size, s.mountRotation)
            val fr = SlotFrame(s, t)
            if (editable) {
                val (mat, tex) = gfx.unlitOwn(0xffffffff.toInt())
                val marker = gfx.mesh(t, disc!!, mat, name = "Marker")
                marker.setLocal(V3(0f, (if (s.isMonitor) 0.012f else 0.006f) * k, 0f), uRot(-90f, V3.Right)) // 円 (+Z 向き) を外向き (+Y) に
                marker.isVisible = false
                fr.marker = marker
                fr.markerTex = tex
            }
            frames[s.id] = fr
        }
        if (editable) {
            highlight = gfx.mesh(body, Meshes.plane(size * 0.97f, size * 0.97f), gfx.unlit(0x383b82f6), name = "FaceHighlight").also {
                it.isVisible = false
            }
        }
    }

    fun setSelectedFace(face: String?) {
        selectedFace = face
        highlight?.let { h ->
            h.isVisible = face != null
            if (face != null) {
                val n = BodyLayout.faceNormal(face)
                h.setLocal(n * (size * 0.5f + 0.004f * k), Q.look(n, if (abs(n.y) > 0.5f) V3.Forward else V3.Up))
            }
        }
        updateMarkers()
    }

    fun setDrop(kind: ModuleKind?, hover: String?) {
        dropKind = kind
        dropHover = hover
        updateMarkers()
    }

    private fun updateMarkers() {
        if (!editable) return
        for (fr in frames.values) {
            val onFace = selectedFace != null && fr.slot.face == selectedFace
            val occupied = mounts.containsKey(fr.slot.id)
            val candidate = dropKind != null && ModuleCatalog.fits(dropKind!!, fr.slot)
            val hovered = candidate && fr.slot.id == dropHover
            // 空きスロットは常に、取り付け済みは差し替え候補のときだけ目印を出す
            val show = onFace && (!occupied || candidate)
            val marker = fr.marker ?: continue
            marker.isVisible = show
            if (!show) continue
            gfx.setUnlitColor(fr.markerTex!!, if (hovered) 0xffec4899.toInt() else if (candidate) 0xff3b82f6.toInt() else 0xffffffff.toInt())
            val s = if (hovered) 1.5f else if (candidate) 1.15f else 0.8f
            marker.setLocal(V3(0f, (if (fr.slot.isMonitor) 0.012f else 0.006f) * k, 0f), uRot(-90f, V3.Right), V3(s, s, s))
        }
    }
}
