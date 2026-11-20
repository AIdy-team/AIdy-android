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
        val Names = listOf("stoolBar") // ホームの 3 部屋はデザインの寸法で形から作る。モデルを使うのはきせかえルームのスツールだけ

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

    // ------------------------------------------------------------ デザイン「お部屋 3パターン」の寸法
    //
    // デザインの座標 (mm): X 右・Y 上 (0 = カメラの高さ)・Z 奥。Aidy (一辺 300mm) は Y = -350 の面に立ち、中心は Z = 1050。
    // ここでは Aidy の一辺 300mm = BodySize (1.1) で換算し、Y = -350 の面を Floor (Aidy の足もと) に合わせる。
    // お散歩の範囲 (x ±1.1, z ±0.6) にかかる小物は、デザインより少し外へずらしている。

    // init (部屋を作る) より後ろに書いてあるので、値を持つプロパティにせず毎回計算する (初期化前は 0 になるため)
    private val dk: Float get() = 1.1f / 300f
    private fun dx(mm: Float) = mm * dk
    private fun dy(mm: Float) = Floor + (mm + 350f) * dk
    private fun dz(mm: Float) = -(mm - 1050f) * dk

    /** デザインの箱 [x0, x1, y0, y1, z0, z1] (mm)。 */
    private fun dbox(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, color: Int, rough: Float = 0.75f) {
        box((dx(x0) + dx(x1)) * 0.5f, dy(y0), (dz(z0) + dz(z1)) * 0.5f, dx(x1) - dx(x0), dy(y1) - dy(y0), dz(z0) - dz(z1), color, rough = rough)
    }

    /** デザインの部屋の箱: 奥の壁・左右の壁・天井・床 (mm)。null の面は作らない。z は奥の壁から手前 (カメラの後ろ) まで。 */
    private fun dRoom(
        x0: Float, x1: Float, yFloor: Float, yCeil: Float, zBack: Float,
        back: Int, left: Int?, right: Int?, ceil: Int?, floor: Int?,
    ) {
        val w = dx(x1) - dx(x0)
        val cx = (dx(x0) + dx(x1)) * 0.5f
        val h = dy(yCeil) - dy(yFloor)
        val cy = (dy(yFloor) + dy(yCeil)) * 0.5f
        val zb = dz(zBack)
        val front = 9f
        val depth = front - zb
        val cz = (front + zb) * 0.5f
        gfx.mesh(root, Meshes.plane(w + 30f, h + 30f), mat(back, 0.9f), false, true, name = "Back").setLocal(V3(cx, cy, zb))
        left?.let { gfx.mesh(root, Meshes.plane(depth, h), mat(it, 0.9f), false, true, name = "Left").setLocal(V3(dx(x0), cy, cz), Q.euler(0f, PI / 2f, 0f)) }
        right?.let { gfx.mesh(root, Meshes.plane(depth, h), mat(it, 0.9f), false, true, name = "Right").setLocal(V3(dx(x1), cy, cz), Q.euler(0f, -PI / 2f, 0f)) }
        ceil?.let { gfx.mesh(root, Meshes.plane(w, depth), mat(it, 0.9f), false, true, name = "Ceiling").setLocal(V3(cx, dy(yCeil), cz), Q.euler(PI / 2f, 0f, 0f)) }
        floor?.let { gfx.mesh(root, Meshes.plane(w, depth), mat(it, 0.6f), false, true, name = "Floor").setLocal(V3(cx, dy(yFloor), cz), Q.euler(-PI / 2f, 0f, 0f)) }
    }

    // ------------------------------------------------------------ リビング (ローテーブルの上。奥にソファと窓)

    private fun living() {
        val white = 0xffffffff.toInt()
        dRoom(-800f, 2000f, -750f, 1650f, 2900f, back = 0xffefe6da.toInt(), left = 0xffe3d6c5.toInt(), right = 0xffe3d6c5.toInt(), ceil = 0xfff6f0e8.toInt(), floor = 0xff9c7b5b.toInt())

        // 窓 (枠・ガラス・桟) とカーテン
        dbox(-300f, 800f, 150f, 1250f, 2890f, 2900f, white, 0.6f)
        dbox(-250f, 750f, 200f, 1200f, 2880f, 2890f, 0xffcfe6f5.toInt(), 0.2f)
        dbox(225f, 275f, 200f, 1200f, 2870f, 2880f, white, 0.6f)
        dbox(-250f, 750f, 675f, 725f, 2870f, 2880f, white, 0.6f)
        dbox(-560f, -320f, 0f, 1550f, 2750f, 2850f, 0xffe3a97c.toInt(), 0.95f)
        dbox(820f, 1060f, 0f, 1550f, 2750f, 2850f, 0xffe3a97c.toInt(), 0.95f)

        // ソファ (背もたれ・座面・クッション 2 つ・ひじかけ)
        dbox(-1300f, 400f, -750f, 100f, 2550f, 2800f, 0xff8fa5b9.toInt(), 0.9f)
        dbox(-1300f, 400f, -750f, -330f, 1900f, 2800f, 0xff8fa5b9.toInt(), 0.9f)
        dbox(-1050f, -450f, -330f, 150f, 2400f, 2550f, 0xffa9bccd.toInt(), 0.95f)
        dbox(-400f, 200f, -330f, 150f, 2400f, 2550f, 0xffa9bccd.toInt(), 0.95f)
        dbox(250f, 400f, -750f, -150f, 1900f, 2550f, 0xff829aaf.toInt(), 0.9f)

        // 観葉植物 (鉢・葉)
        dbox(550f, 850f, -750f, -450f, 2300f, 2600f, 0xffc97b5a.toInt())
        dbox(500f, 900f, -450f, 250f, 2250f, 2650f, 0xff6e9f6a.toInt(), 0.9f)

        // Aidy が乗っているローテーブル (高さ 400mm。天板 = Floor) と脚
        dbox(-1000f, 1000f, -380f, -350f, 450f, 1400f, 0xffb88a5e.toInt(), 0.6f)
        for (lx in listOf(-940f, 900f)) for (lz in listOf(500f, 1310f)) dbox(lx, lx + 40f, -750f, -380f, lz, lz + 40f, 0xff9c7552.toInt(), 0.6f)

        // テーブルの上: 本 (平置き)・マグカップ・リモコン
        dbox(-420f, -180f, -350f, -342f, 1080f, 1380f, 0xffd9774b.toInt())
        cyl(dx(560f), Floor, dz(990f), dx(40f), dx(40f), dx(95f), 0xfff7f2ea.toInt(), rough = 0.4f)
        dbox(600f, 620f, -330f, -280f, 975f, 1005f, 0xffede6db.toInt(), 0.4f)
        dbox(-420f, -370f, -350f, -330f, 800f, 980f, 0xff3f4a57.toInt(), 0.5f)
    }

    // ------------------------------------------------------------ 自分の部屋 (机の上。壁まで約 30cm)

    private fun desk() {
        val white = 0xffffffff.toInt()
        val roomFloor = -350f - 720f // 机の高さ 720mm の下が部屋の床
        dRoom(-4000f, 4000f, roomFloor, 1800f, 1500f, back = 0xffdce3ee.toInt(), left = null, right = null, ceil = null, floor = 0xffb8916a.toInt())

        // コルクボードとメモ
        dbox(-620f, -120f, 150f, 550f, 1485f, 1500f, 0xffb48a60.toInt())
        dbox(-600f, -140f, 170f, 530f, 1480f, 1485f, 0xffd9b48c.toInt(), 0.95f)
        dbox(-560f, -440f, 380f, 500f, 1475f, 1480f, white, 0.9f)
        dbox(-400f, -280f, 400f, 500f, 1475f, 1480f, 0xfffde68a.toInt(), 0.9f)
        dbox(-460f, -320f, 220f, 340f, 1475f, 1480f, 0xffbfdbfe.toInt(), 0.9f)

        // 壁の棚 (2 段): 上に本と植木、下に箱
        dbox(100f, 700f, 650f, 680f, 1300f, 1500f, 0xff9c7a5a.toInt(), 0.6f)
        dbox(100f, 700f, 250f, 280f, 1300f, 1500f, 0xff9c7a5a.toInt(), 0.6f)
        val books = listOf(
            Triple(130f, 170f, 930f) to 0xff3b5b8c.toInt(), Triple(175f, 205f, 880f) to 0xffd9774b.toInt(),
            Triple(210f, 255f, 950f) to 0xff6b9f7a.toInt(), Triple(260f, 290f, 900f) to 0xffe7c36a.toInt(),
            Triple(295f, 330f, 920f) to 0xff7e6bb0.toInt(),
        )
        for ((b, c) in books) dbox(b.first, b.second, 680f, b.third, 1320f, 1480f, c)
        dbox(520f, 640f, 680f, 800f, 1350f, 1450f, 0xffa7c4a0.toInt(), 0.9f)
        dbox(150f, 350f, 280f, 430f, 1330f, 1480f, 0xffefe4d2.toInt(), 0.9f)

        // 机 (天板 = Floor) と脚
        dbox(-900f, 900f, -380f, -350f, 450f, 1500f, 0xffcba57b.toInt(), 0.5f)
        for (lx in listOf(-860f, 820f)) for (lz in listOf(490f, 1420f)) dbox(lx, lx + 40f, roomFloor, -380f, lz, lz + 40f, 0xff8a6a4c.toInt(), 0.6f)

        // 机の上: デスクライト (台・柱・かさ)・ペン立てとペン・ノート
        val lamp = 0xff4b5563.toInt()
        dbox(-660f, -500f, -350f, -325f, 1150f, 1310f, lamp, 0.5f)
        dbox(-595f, -570f, -325f, 80f, 1215f, 1245f, lamp, 0.5f)
        dbox(-640f, -420f, 30f, 110f, 1150f, 1350f, lamp, 0.5f)
        dbox(480f, 560f, -350f, -240f, 1150f, 1230f, 0xff7c93b3.toInt(), 0.5f)
        dbox(495f, 510f, -240f, -120f, 1180f, 1195f, 0xfff2b544.toInt(), 0.5f)
        dbox(525f, 538f, -240f, -100f, 1180f, 1193f, 0xffe5604d.toInt(), 0.5f)
        dbox(-450f, -250f, -350f, -340f, 750f, 1000f, white, 0.9f)
    }

    // ------------------------------------------------------------ 子ども部屋 (床のラグの上。Aidy 目線で家具が大きい)

    private fun kids() {
        val white = 0xffffffff.toInt()
        dRoom(-900f, 2500f, -350f, 2050f, 2600f, back = 0xffe6f3ec.toInt(), left = 0xffd5eadf.toInt(), right = 0xffd5eadf.toInt(), ceil = 0xfff3f9f5.toInt(), floor = 0xffebc48f.toInt())

        // 窓と額
        dbox(-500f, 300f, 600f, 1700f, 2590f, 2600f, white, 0.6f)
        dbox(-450f, 250f, 650f, 1650f, 2580f, 2590f, 0xffcde8fa.toInt(), 0.2f)
        dbox(-300f, 0f, 1100f, 1250f, 2575f, 2580f, white, 0.6f)
        dbox(700f, 1100f, 900f, 1200f, 2590f, 2600f, 0xfff5cf5b.toInt(), 0.6f)
        dbox(730f, 1070f, 930f, 1170f, 2585f, 2590f, white, 0.9f)

        // ガーランド (ひもと旗)
        dbox(-900f, 2500f, 1880f, 1888f, 2570f, 2575f, 0xff8b7b6b.toInt())
        val flags = listOf(0xfff59e6b.toInt(), 0xff6ba8f5.toInt(), 0xfff5cf5b.toInt(), 0xff8fd19e.toInt(), 0xfff28b9b.toInt(), 0xffb49cf0.toInt())
        for (i in 0 until 12) {
            gfx.mesh(root, cube, mat(flags[i % flags.size], 0.8f), false, true, name = "Flag")
                .setLocal(V3(dx(-700f + i * 270f), dy(1800f), dz(2565f)), Q.euler(0f, 0f, PI / 4f), V3(dx(90f), dx(90f), 0.01f))
        }

        // 右奥: 白い低い棚とおもちゃ
        dbox(600f, 1400f, -350f, 550f, 2200f, 2550f, 0xfff7f3ec.toInt(), 0.7f)
        dbox(700f, 900f, 550f, 750f, 2300f, 2500f, 0xff6ba8f5.toInt(), 0.6f)
        dbox(950f, 1100f, 550f, 700f, 2300f, 2450f, 0xfff59e6b.toInt(), 0.6f)

        // 左: おもちゃ箱
        dbox(-850f, -500f, -350f, 50f, 1800f, 2200f, 0xfff5cf5b.toInt(), 0.7f)

        // ラグ (Aidy が立っている場所)
        dbox(-700f, 700f, -350f, -345f, 550f, 1800f, 0xffa8d5c2.toInt(), 0.95f)

        // 積み木 (3 つ) とボール
        dbox(550f, 630f, -350f, -270f, 1000f, 1080f, 0xff6ba8f5.toInt(), 0.55f)
        dbox(640f, 720f, -350f, -270f, 1000f, 1080f, 0xfff59e6b.toInt(), 0.55f)
        dbox(595f, 675f, -270f, -190f, 1000f, 1080f, 0xfff5cf5b.toInt(), 0.55f)
        sph(dx(-600f), dy(-260f), dz(1090f), dx(90f), 0xfff28b82.toInt())
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
        val halfW = ux(540f) // カメラを近づけたぶん少し狭めの試着室
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

        // 右: ハンガーラック (台・柱・横棒) と青い服 (カメラを近づけたので画面に入るよう内側へ寄せる)
        bx(310f, 470f, floorMm, -780f, 1520f, 1680f, 0xff5b6475.toInt())
        bx(430f, 455f, -780f, 900f, 1590f, 1615f, 0xff5b6475.toInt(), 0.5f)
        bx(350f, 490f, 690f, 710f, 1585f, 1620f, 0xff8b6b4a.toInt(), 0.6f)
        bx(360f, 480f, 150f, 690f, 1560f, 1600f, 0xff6ba8f5.toInt(), 0.9f)

        // 左: 低いベンチと靴 (内側へ寄せる)
        bx(-470f, -250f, floorMm, -600f, 1300f, 1500f, 0xffc8a46e.toInt(), 0.6f)
        bx(-400f, -290f, -600f, -470f, 1360f, 1440f, 0xff2b2f36.toInt(), 0.5f)
        bx(-440f, -260f, -600f, -585f, 1340f, 1460f, 0xff2b2f36.toInt(), 0.5f)

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
