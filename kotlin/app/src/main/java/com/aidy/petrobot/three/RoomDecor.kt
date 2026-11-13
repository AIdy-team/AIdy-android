package com.aidy.petrobot.three

import com.google.android.filament.MaterialInstance
import io.github.sceneview.node.Node

/**
 * ホームの部屋: 床・壁・窓と家具 (Kenney Furniture Kit, CC0 / assets/room)。
 * 家具は 1 = 床 1 枚の寸法で、正面が -Z 向き。S 倍して 180° 回し、奥の壁 (BackZ) に背を付けて並べる。
 * 本体 (1.1) がお散歩する範囲 (x ±1.1, z ±0.6) とラグの上は空けておく。家具は影を落とさない (受けるだけ)。
 */
class RoomDecor(private val gfx: Gfx, private val root: Node) {
    companion object {
        const val Floor = -0.65f
        const val BackZ = -2.8f
        private const val S = 2.2f
        private const val WallColor = 0xfff3ece2.toInt()
        private const val FloorColor = 0xffdcb48a.toInt()
        private const val SkyColor = 0xffbfe3fb.toInt()

        /** 使う家具 (先読み用)。 */
        val Names = listOf(
            "wall", "wallWindow", "loungeSofa", "pillowBlue", "bear", "lampRoundFloor", "pottedPlant",
            "rugRounded", "sideTable", "plantSmall1", "books", "bookcaseOpen",
        )
    }

    private val mats = HashMap<Int, MaterialInstance>()
    private fun mat(argb: Int) = mats.getOrPut(argb) { gfx.lit(argb, 0f, 0.75f) }

    init {
        // 奥の壁 (タイルの外も同じ色)・床・窓の外の空
        gfx.mesh(root, Meshes.plane(16f, 7f), mat(WallColor), false, true, name = "Wall")
            .setLocal(V3(0f, Floor + 3.5f, BackZ - 0.03f))
        gfx.mesh(root, Meshes.plane(16f, 12f), gfx.lit(FloorColor, 0f, 0.55f), false, true, name = "Floor")
            .setLocal(V3(0f, Floor, -1f), Q.euler(-PI / 2f, 0f, 0f))

        // 壁: 真ん中に窓、左右に壁
        for (i in -2..2) {
            val name = if (i == 0) "wallWindow" else "wall"
            put(name, i * S, backed(name), override = mapOf("_defaultMat" to WallColor))
        }
        sky()

        // ソファ (窓の下) + クッション + くま
        put("loungeSofa", 0f, backed("loungeSofa"))
        val seat = Floor + 0.23f * S
        put("pillowBlue", -0.65f, BackZ + 0.3f, y = seat, yaw = PI * 0.92f)
        put("bear", 0.62f, BackZ + 0.42f, y = seat, s = 1.5f, yaw = PI * 1.1f)

        // ソファの左右: スタンドライト・観葉植物
        put("lampRoundFloor", -1.5f, backed("lampRoundFloor") + 0.1f)
        put("pottedPlant", 1.55f, backed("pottedPlant") + 0.15f)

        // ラグ (お散歩する場所)
        put("rugRounded", 0f, 0.05f, y = Floor + 0.002f, override = mapOf("carpet" to 0xffb9d3ef.toInt(), "carpetDarker" to 0xff8fb2dc.toInt()))

        // 外側 (引くと見える): 左にサイドテーブル + 小物、右に本棚
        put("sideTable", -2.6f, backed("sideTable"))
        val table = Floor + 0.38f * S
        put("plantSmall1", -2.9f, BackZ + 0.25f, y = table)
        put("books", -2.35f, BackZ + 0.25f, y = table, yaw = PI * 0.9f)
        put("bookcaseOpen", 2.65f, backed("bookcaseOpen"))
    }

    /** 奥の壁に背を付けたときの中心の z。 */
    private fun backed(name: String, s: Float = S): Float {
        val m = RoomModel.load(name) ?: return BackZ + 0.5f
        return BackZ + (m.mesh.max.z - m.mesh.min.z) * s * 0.5f + 0.01f
    }

    /**
     * 家具を置く。(x, z) = 底面の中心、y = 底の高さ、yaw = 向き (π で正面がカメラ側)。
     * override = 材質ごとの色の差し替え。
     */
    private fun put(
        name: String, x: Float, z: Float, y: Float = Floor, s: Float = S, yaw: Float = PI,
        override: Map<String, Int> = emptyMap(),
    ) {
        val m = RoomModel.load(name) ?: return
        val lo = m.mesh.min
        val hi = m.mesh.max
        val pivot = gfx.empty(root, name)
        pivot.setLocal(V3(x, y, z), Q.euler(0f, yaw, 0f), s)
        val offset = V3(-(lo.x + hi.x) * 0.5f, -lo.y, -(lo.z + hi.z) * 0.5f)
        for ((i, g) in m.mesh.groups.withIndex()) {
            val color = override[g.material] ?: m.colors[g.material] ?: 0xffcccccc.toInt()
            val material = if (g.material == "glass") gfx.lit((color and 0xffffff) or 0x55000000, 0f, 0.1f) else mat(color)
            gfx.mesh(pivot, m.mesh, material, false, true, group = i, name = "$name.${g.material}").setLocal(offset)
        }
    }

    /** 窓の向こうの空 (窓ガラスの範囲より少し大きい板を壁の裏に置く)。 */
    private fun sky() {
        val m = RoomModel.load("wallWindow") ?: return
        val g = m.mesh.groups.firstOrNull { it.material == "glass" } ?: return
        var lx = Float.MAX_VALUE; var hx = -Float.MAX_VALUE; var ly = Float.MAX_VALUE; var hy = -Float.MAX_VALUE
        for (k in g.start until g.start + g.count) {
            val v = m.mesh.indices[k]
            lx = minOf(lx, m.mesh.positions[v * 3]); hx = maxOf(hx, m.mesh.positions[v * 3])
            ly = minOf(ly, m.mesh.positions[v * 3 + 1]); hy = maxOf(hy, m.mesh.positions[v * 3 + 1])
        }
        if (hx < lx) return
        val cx = -((lx + hx) * 0.5f - (m.mesh.min.x + m.mesh.max.x) * 0.5f) * S // 180° 回すので左右反転
        val cy = Floor + (ly + hy) * 0.5f * S
        gfx.mesh(root, Meshes.plane((hx - lx) * S + 0.3f, (hy - ly) * S + 0.3f), gfx.unlit(SkyColor), name = "Sky")
            .setLocal(V3(cx, cy, BackZ - 0.015f))
    }
}
