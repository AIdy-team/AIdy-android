package com.aidy.petrobot.three

import com.google.android.filament.MaterialInstance
import io.github.sceneview.node.Node

/**
 * ホームの部屋: 床・壁・窓と家具 (Kenney Furniture Kit, CC0 / assets/room) + 簡単な形 (箱・円柱・球)。
 * 家具は 1 = 床 1 枚の寸法で、正面が -Z 向き。S 倍して 180° 回し、奥の壁 (BackZ) に背を付けて並べる。
 * 本体 (1.1) がお散歩する範囲 (x ±1.1, z ±0.6) とラグの上は空けておく。家具は影を落とさない (受けるだけ)。
 *
 * room: [RoomIds] のどれか (リビング・自分の部屋・子ども部屋) か [StageRoom] (着せ替えのきせかえルーム: 試着室の中のスツール)。
 * mats: 材質の使い回し (部屋を作り直しても同じ色は同じ材質)。
 */
class RoomDecor(private val gfx: Gfx, private val root: Node, room: String, private val mats: HashMap<Long, MaterialInstance>) {
    companion object {
        const val Floor = -0.65f
        const val BackZ = -2.8f
        private const val S = 2.2f
        /** Kenney の家具を実寸にする倍率 (本体 30cm 基準。Kenney の 1 = 約 1.85m)。 */
        private const val K = 6.8f

        const val Living = "living"
        const val Desk = "desk"
        const val Kids = "kids"
        const val StageRoom = "stage"
        val RoomIds = listOf(Living, Desk, Kids)
        /** きせかえルームのスツールの高さ (床から座面まで)。Aidy はこの上に立つ。 */
        const val StoolHeight = 1.875f
        /** スツールの座面の幅。 */
        private const val StoolWidth = 1.875f

        /** 使う家具 (先読み用)。 */
        val Names = listOf(
            // リビング
            "tableCoffee", "loungeSofa", "pillowBlue", "bear", "lampRoundFloor", "pottedPlant",
            "rugRounded", "sideTable", "plantSmall1", "books", "bookcaseOpen",
            // 自分の部屋
            "desk", "computerScreen", "computerKeyboard", "computerMouse", "lampRoundTable", "speakerSmall", "plantSmall2",
            // 子ども部屋
            "rugRound", "bedBunk", "bookcaseOpenLow", "sideTableDrawers", "lampSquareTable", "radio", "plantSmall3", "cardboardBoxOpen",
            // きせかえルーム
            "stoolBar",
        )

        // 形のメッシュ (一度だけ作る。GPU 側も Gfx が MeshData ごとに使い回す)
        private val cube by lazy { Meshes.cube() }
        private val sphere by lazy { Meshes.sphere(1f) }
        private val cylinders = HashMap<Int, MeshData>()
        private fun cylinder(topRatio: Float): MeshData = synchronized(cylinders) {
            cylinders.getOrPut((topRatio * 100f).toInt()) { Meshes.cylinder(topRatio, 1f, 1f, 28) }
        }
        private val starMesh by lazy { Meshes.star(1f, 0.45f) }
    }

    private fun mat(argb: Int, rough: Float = 0.75f): MaterialInstance =
        mats.getOrPut((argb.toLong() and 0xffffffffL) or ((rough * 100f).toLong() shl 32)) { gfx.lit(argb, 0f, rough) }

    init {
        when (room) {
            Desk -> desk()
            Kids -> kids()
            StageRoom -> stage()
            else -> living()
        }
    }

    // ------------------------------------------------------------ 実寸の縮尺
    //
    // 本体は一辺 30cm = BodySize (1.1)。1m = 1.1 / 0.3 ≒ 3.67。
    // Kenney の家具は 1 = 約 1.85m (机の高さ 0.384 ≒ 72cm、ソファの高さ 0.46 ≒ 85cm) なので、K 倍すると実寸になる。
    // 本体の足もとの高さ (Floor) が Aidy の立っている面: リビングはテーブルの上、自分の部屋は机の上、子ども部屋は床。

    // ------------------------------------------------------------ リビング (テーブルの上)

    private fun living() {
        val roomFloor = Floor - 0.23f * K // ローテーブルの高さ (約 43cm) だけ下が部屋の床
        val backZ = -6.2f
        room(0xfff3ece2.toInt(), 0xffdcb48a.toInt(), roomFloor, backZ)
        put("rugRounded", 0f, -1.5f, y = roomFloor + 0.003f, s = K, override = mapOf("carpet" to 0xffb9d3ef.toInt(), "carpetDarker" to 0xff8fb2dc.toInt()))

        // Aidy が乗っているローテーブル (天板 = Floor)
        put("tableCoffee", 0f, 0f, y = roomFloor, s = K)
        // テーブルの上の小物: 本・植木鉢・マグカップ・リモコン
        put("books", -1.85f, -0.85f, y = Floor, s = K, yaw = PI * 0.9f)
        put("plantSmall1", 1.9f, -0.85f, y = Floor, s = K)
        cyl(1.95f, Floor, 0.75f, 0.17f, 0.17f, 0.36f, 0xfff5f0e1.toInt(), rough = 0.4f)
        cyl(1.95f, Floor + 0.34f, 0.75f, 0.14f, 0.14f, 0.025f, 0xff6b4a2b.toInt(), rough = 0.2f)
        box(-1.8f, Floor, 0.8f, 0.17f, 0.08f, 0.6f, 0xff3a3d44.toInt(), yaw = 0.3f, rough = 0.5f)

        // 奥: ソファ (クッション・くま)、左にスタンドライト、右に観葉植物
        val sofaZ = backZ + 0.41f * K * 0.5f + 0.05f
        put("loungeSofa", 0f, sofaZ, y = roomFloor, s = K)
        val seat = roomFloor + 0.23f * K
        put("pillowBlue", -1.6f, sofaZ - 0.4f, y = seat, s = K, yaw = PI * 0.92f)
        put("bear", 1.5f, sofaZ - 0.2f, y = seat, s = K * 0.45f, yaw = PI * 1.1f)
        put("lampRoundFloor", -4.4f, backZ + 0.7f, y = roomFloor, s = K)
        put("pottedPlant", 4.4f, backZ + 0.9f, y = roomFloor, s = K)

        // 外側 (引くと見える): 左にサイドテーブル、右に本棚
        put("sideTable", -7.2f, backZ + 0.22f * K * 0.5f + 0.05f, y = roomFloor, s = K)
        put("bookcaseOpen", 7.4f, backZ + 0.25f * K * 0.5f + 0.05f, y = roomFloor, s = K)
    }

    // ------------------------------------------------------------ 自分の部屋 (PC のあるデスクの上)

    private fun desk() {
        val deskH = 0.384f * K // 机の高さ (約 72cm)
        val roomFloor = Floor - deskH
        val backZ = -2.65f // 机は壁に付けて置く
        room(0xffe6ecf2.toInt(), 0xffb8916a.toInt(), roomFloor, backZ)

        // 机 (天板 = Floor)。お散歩できるよう、モデルより少し奥行きのある机にする
        val deskScale = V3(K * 1.2f, K, K * 1.6f)
        val deskDepth = 0.392f * deskScale.z
        put("desk", 0f, backZ + deskDepth * 0.5f + 0.02f, y = roomFloor, scale = deskScale, override = mapOf("wood" to 0xffd9b48c.toInt()))
        // デスクマット (お散歩する場所)
        gfx.mesh(root, Meshes.plane(3.8f, 2.5f), mat(0xff4f7d6a.toInt(), 0.9f), false, true, name = "DeskMat")
            .setLocal(V3(0f, Floor + 0.004f, 0.1f), Q.euler(-PI / 2f, 0f, 0f))

        // PC: モニター・キーボード・マウス
        put("computerScreen", 0f, backZ + 0.45f, y = Floor, s = K)
        put("computerKeyboard", -0.2f, -1.55f, y = Floor, s = K)
        put("computerMouse", 1.45f, -1.5f, y = Floor, s = K)
        // 左: 卓上ライトと本の山、右: スピーカー・植木鉢・マグカップ
        put("lampRoundTable", -2.45f, backZ + 0.6f, y = Floor, s = K, yaw = PI * 0.85f)
        put("books", -2.4f, -0.5f, y = Floor, s = K, yaw = PI * 1.1f)
        put("books", -2.35f, -0.5f, y = Floor + 0.104f * K, s = K, yaw = PI * 0.95f)
        put("speakerSmall", 2.45f, backZ + 0.55f, y = Floor, s = K, yaw = PI * 1.1f)
        put("plantSmall2", 2.5f, -0.9f, y = Floor, s = K)
        cyl(2.15f, Floor, 0.5f, 0.17f, 0.17f, 0.36f, 0xff7cc4f0.toInt(), rough = 0.4f)

        // 壁の棚 (本と植木鉢)
        box(0f, Floor + 3.4f, backZ + 0.35f, 4.6f, 0.12f, 0.7f, 0xffd9b48c.toInt(), rough = 0.6f)
        put("books", -1.3f, backZ + 0.35f, y = Floor + 3.52f, s = K, yaw = PI * 0.95f)
        put("plantSmall3", 1.4f, backZ + 0.35f, y = Floor + 3.52f, s = K)
    }

    // ------------------------------------------------------------ 子ども部屋 (床の上・おもちゃのそば)

    private fun kids() {
        val backZ = -5.5f
        room(0xfffbe6df.toInt(), 0xffe6c9a0.toInt(), Floor, backZ)
        put("rugRound", 0f, -0.6f, y = Floor + 0.003f, s = K, override = mapOf("carpet" to 0xfff7d27a.toInt(), "carpetDarker" to 0xffeab948.toInt()))

        // 壁の星かざり
        val stars = listOf(0xfff6d36b.toInt(), 0xfff4a7c0.toInt(), 0xff7cc4f0.toInt(), 0xff8fd6b4.toInt(), 0xffa78bfa.toInt())
        for ((i, c) in stars.withIndex()) {
            gfx.mesh(root, starMesh, mat(c, 0.6f), false, false, name = "Star")
                .setLocal(V3(-3.2f + i * 1.6f, Floor + 4.4f + (i % 2) * 0.5f, backZ + 0.02f), Q.euler(0f, 0f, i * 0.3f), 0.45f)
        }

        // 左: 二段ベッド (奥の壁から手前へ)
        put("bedBunk", -4.6f, backed("bedBunk", K, backZ), s = K, override = mapOf("carpet" to 0xfff4a7c0.toInt()))

        // 奥: 低い本棚の上にくま (約 35cm)、右に引き出し付きの台 (ライト・ラジオ・植木鉢)
        put("bookcaseOpenLow", -0.4f, backed("bookcaseOpenLow", K, backZ), s = K)
        put("bear", -0.4f, backZ + 0.8f, y = Floor + 0.4f * K, s = 2.85f, yaw = PI * 1.05f)
        put("sideTableDrawers", 3.6f, backed("sideTableDrawers", K, backZ), s = K, override = mapOf("_defaultMat" to 0xff7cc4f0.toInt()))
        val table = Floor + 0.384f * K
        put("lampSquareTable", 2.6f, backZ + 0.75f, y = table, s = K)
        put("radio", 3.9f, backZ + 0.75f, y = table, s = 4.5f, yaw = PI * 0.95f)
        put("plantSmall3", 4.9f, backZ + 0.75f, y = table, s = K)

        // おもちゃ (実寸): 段ボールのおもちゃ箱、積み木 (約 8cm)、ボール (約 20cm)
        put("cardboardBoxOpen", 3.3f, -1.9f, s = K, yaw = PI * 0.9f)
        val k = 0.3f
        val tower = listOf(0xffe5484d.toInt(), 0xff2f80ed.toInt(), 0xfff6d36b.toInt(), 0xff10b981.toInt())
        for ((i, c) in tower.withIndex()) box(2.25f, Floor + i * k, 0.5f, k, k, k, c, yaw = i * 0.25f, rough = 0.55f)
        box(-2.0f, Floor, 0.7f, k, k, k, 0xfff08a5d.toInt(), yaw = 0.5f, rough = 0.55f)
        box(-2.45f, Floor, 0.35f, k, k, k, 0xffa78bfa.toInt(), yaw = -0.3f, rough = 0.55f)
        box(2.0f, Floor, 1.0f, k, k, k, 0xff8fd6b4.toInt(), yaw = 0.8f, rough = 0.55f)
        sph(-2.3f, Floor + 0.37f, -1.3f, 0.37f, 0xffe5484d.toInt())
        gfx.mesh(root, cylinder(1f), mat(0xfffafafa.toInt(), 0.5f), false, true, name = "BallBand")
            .setLocal(V3(-2.3f, Floor + 0.37f, -1.3f), Q.euler(0f, 0f, 0.4f), V3(0.375f, 0.11f, 0.375f))
    }

    // ------------------------------------------------------------ きせかえルーム (試着室の内側: 鏡の壁・ハンガー・ベンチ + 丸いスツール)

    /**
     * デザイン「試着室（全体）」の寸法 (mm) をそのまま使う。デザインの座標: X 右・Y 上 (0 = 目の高さ)・Z 奥、
     * 床 Y = -800、Aidy (一辺 300mm) の中心は Z = 1050。ここでは Aidy の一辺 (300mm) = BodySize (1.1) で換算する。
     */
    private fun stage() {
        val k = 1.1f / 300f
        val floorMm = -800f
        fun ux(mm: Float) = mm * k
        fun uy(mm: Float) = Floor + (mm - floorMm) * k
        fun uz(mm: Float) = -(mm - 1050f) * k
        /** デザインの箱 [x0, x1, y0, y1, z0, z1] (mm)。 */
        fun bx(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, color: Int, rough: Float = 0.75f) {
            box((ux(x0) + ux(x1)) * 0.5f, uy(y0), (uz(z0) + uz(z1)) * 0.5f, ux(x1) - ux(x0), uy(y1) - uy(y0), uz(z0) - uz(z1), color, rough = rough)
        }

        // 部屋: 幅 1200mm・高さ 2100mm。奥の壁は Z = 1920、左右の壁・床・天井は手前 (カメラの後ろ) まで伸ばす
        val halfW = ux(600f)
        val backZ = uz(1920f)
        val top = uy(1300f)
        val front = 9f
        val depth = front - backZ
        val midZ = (front + backZ) * 0.5f
        val height = top - Floor
        gfx.mesh(root, Meshes.plane(halfW * 2f, height), mat(0xffefe4d6.toInt(), 0.9f), false, true, name = "BoothBack")
            .setLocal(V3(0f, Floor + height * 0.5f, backZ))
        gfx.mesh(root, Meshes.plane(depth, height), mat(0xffe8d5c0.toInt(), 0.9f), false, true, name = "BoothLeft")
            .setLocal(V3(-halfW, Floor + height * 0.5f, midZ), Q.euler(0f, PI / 2f, 0f))
        gfx.mesh(root, Meshes.plane(depth, height), mat(0xffdfc9b1.toInt(), 0.9f), false, true, name = "BoothRight")
            .setLocal(V3(halfW, Floor + height * 0.5f, midZ), Q.euler(0f, -PI / 2f, 0f))
        gfx.mesh(root, Meshes.plane(halfW * 2f, depth), mat(0xfff7f1e8.toInt(), 0.9f), false, true, name = "BoothCeiling")
            .setLocal(V3(0f, top, midZ), Q.euler(PI / 2f, 0f, 0f))
        gfx.mesh(root, Meshes.plane(halfW * 2f, depth), mat(0xffc9a98a.toInt(), 0.6f), false, true, name = "BoothFloor")
            .setLocal(V3(0f, Floor, midZ), Q.euler(-PI / 2f, 0f, 0f))

        // 奥の壁の大きな鏡 (木の枠 + ガラス + 光の筋)
        bx(-380f, 380f, -650f, 1150f, 1900f, 1920f, 0xffb08a5e.toInt(), 0.6f)
        bx(-345f, 345f, -615f, 1115f, 1895f, 1900f, 0xffdce6ec.toInt(), 0.15f)
        for ((dx, len, w) in listOf(Triple(-150f, 420f, 34f), Triple(-75f, 300f, 17f))) {
            gfx.mesh(root, cube, gfx.lit(0x66ffffff, 0f, 0.2f), false, false, name = "MirrorGlare")
                .setLocal(V3(ux(dx), uy(820f), uz(1893f)), Q.euler(0f, 0f, -0.42f), V3(ux(w), ux(len), 0.005f))
        }

        // 右: ハンガーラック (台・柱・横棒) と青い服
        bx(420f, 580f, floorMm, -780f, 1520f, 1680f, 0xff5b6475.toInt())
        bx(540f, 565f, -780f, 900f, 1590f, 1615f, 0xff5b6475.toInt(), 0.5f)
        bx(460f, 600f, 690f, 710f, 1585f, 1620f, 0xff8b6b4a.toInt(), 0.6f)
        bx(470f, 590f, 150f, 690f, 1560f, 1600f, 0xff6ba8f5.toInt(), 0.9f)

        // 左: 低いベンチと靴
        bx(-560f, -300f, floorMm, -600f, 1300f, 1500f, 0xffc8a46e.toInt(), 0.6f)
        bx(-480f, -360f, -600f, -470f, 1360f, 1440f, 0xff2b2f36.toInt(), 0.5f)
        bx(-520f, -320f, -600f, -585f, 1340f, 1460f, 0xff2b2f36.toInt(), 0.5f)

        // スツールの下の丸いラグ
        gfx.mesh(root, Meshes.circle(1.7f, 48), mat(0xffe7d3b8.toInt(), 0.95f), false, true, name = "BoothRug")
            .setLocal(V3(0f, Floor + 0.003f, 0f), Q.euler(-PI / 2f, 0f, 0f))

        // 丸いスツール (Kenney Furniture Kit の stoolBar, CC0)。座面の上に Aidy が立つ
        val stool = RoomModel.load("stoolBar")
        if (stool != null) {
            // もとはバー用の細長いいす: 座面の幅を StoolWidth に広げ、高さを StoolHeight に合わせる
            val w = maxOf(stool.mesh.max.x - stool.mesh.min.x, stool.mesh.max.z - stool.mesh.min.z)
            val sxz = StoolWidth / w
            val sy = StoolHeight / (stool.mesh.max.y - stool.mesh.min.y)
            put("stoolBar", 0f, 0f, scale = V3(sxz, sy, sxz), override = mapOf("carpet" to 0xffc2566a.toInt()))
        }
    }

    // ------------------------------------------------------------ 共通

    /** 奥の壁 (窓なし・幅木つき) + 床。floorY = 部屋の床の高さ、backZ = 壁の位置。 */
    private fun room(wallColor: Int, floorColor: Int, floorY: Float, backZ: Float, floorRough: Float = 0.55f) {
        gfx.mesh(root, Meshes.plane(40f, 24f), mat(wallColor, 0.9f), false, true, name = "Wall")
            .setLocal(V3(0f, floorY + 12f, backZ))
        gfx.mesh(root, Meshes.plane(40f, 30f), mat(floorColor, floorRough), false, true, name = "Floor")
            .setLocal(V3(0f, floorY, backZ + 15f), Q.euler(-PI / 2f, 0f, 0f))
        // 幅木 (約 8cm)
        box(0f, floorY, backZ + 0.04f, 40f, 0.3f, 0.08f, 0xfff8f5ef.toInt(), rough = 0.6f)
    }

    /** 箱 (底の中心が (x, y, z))。 */
    private fun box(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, color: Int, yaw: Float = 0f, rough: Float = 0.75f) {
        gfx.mesh(root, cube, mat(color, rough), false, true, name = "Box")
            .setLocal(V3(x, y + h * 0.5f, z), Q.euler(0f, yaw, 0f), V3(w, h, d))
    }

    /** 円柱・円錐 (底の中心が (x, y, z))。 */
    private fun cyl(x: Float, y: Float, z: Float, rTop: Float, rBottom: Float, h: Float, color: Int, rough: Float = 0.75f) {
        gfx.mesh(root, cylinder(rTop / rBottom), mat(color, rough), false, true, name = "Cyl")
            .setLocal(V3(x, y + h * 0.5f, z), Q.Identity, V3(rBottom, h, rBottom))
    }

    private fun sph(x: Float, y: Float, z: Float, r: Float, color: Int) {
        gfx.mesh(root, sphere, mat(color, 0.45f), false, true, name = "Sphere").setLocal(V3(x, y, z), Q.Identity, r)
    }

    /** 奥の壁に背を付けたときの中心の z。 */
    private fun backed(name: String, s: Float = S, backZ: Float = BackZ): Float {
        val m = RoomModel.load(name) ?: return backZ + 0.5f
        return backZ + (m.mesh.max.z - m.mesh.min.z) * s * 0.5f + 0.01f
    }

    /**
     * 家具を置く。(x, z) = 底面の中心、y = 底の高さ、yaw = 向き (π で正面がカメラ側)。
     * override = 材質ごとの色の差し替え。
     */
    private fun put(
        name: String, x: Float, z: Float, y: Float = Floor, s: Float = S, yaw: Float = PI, scale: V3? = null,
        override: Map<String, Int> = emptyMap(),
    ) {
        val m = RoomModel.load(name) ?: return
        val lo = m.mesh.min
        val hi = m.mesh.max
        val pivot = gfx.empty(root, name)
        pivot.setLocal(V3(x, y, z), Q.euler(0f, yaw, 0f), scale ?: V3(s, s, s))
        val offset = V3(-(lo.x + hi.x) * 0.5f, -lo.y, -(lo.z + hi.z) * 0.5f)
        for ((i, g) in m.mesh.groups.withIndex()) {
            val color = override[g.material] ?: m.colors[g.material] ?: 0xffcccccc.toInt()
            val material = if (g.material == "glass") gfx.lit((color and 0xffffff) or 0x55000000, 0f, 0.1f) else mat(color)
            gfx.mesh(pivot, m.mesh, material, false, true, group = i, name = "$name.${g.material}").setLocal(offset)
        }
    }

}
