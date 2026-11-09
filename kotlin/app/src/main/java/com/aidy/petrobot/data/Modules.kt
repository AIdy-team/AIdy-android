package com.aidy.petrobot.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.aidy.petrobot.AidyApplication
import com.aidy.petrobot.three.Q
import com.aidy.petrobot.three.V3
import com.aidy.petrobot.ui.IconId
import com.aidy.petrobot.ui.Theme
import org.json.JSONObject
import kotlin.math.abs

/** 本体に取り付けるモジュールの種類。 */
enum class ModuleKind { Ear, Tail, Tail2, Tail45, TailCurl, Hat, Legs, Face, Hand, HandLight }

/** モジュールの名前・アイコン・色・モデルファイル・取り付けられる面。 */
object ModuleCatalog {
    val All = listOf(ModuleKind.Ear, ModuleKind.Tail, ModuleKind.Tail2, ModuleKind.Tail45, ModuleKind.TailCurl, ModuleKind.Hat, ModuleKind.Legs, ModuleKind.Face, ModuleKind.Hand, ModuleKind.HandLight)

    fun name(k: ModuleKind) = when (k) {
        ModuleKind.Ear -> "耳"
        ModuleKind.Tail -> "尻尾"
        ModuleKind.Tail2 -> "尻尾2"
        ModuleKind.Tail45 -> "尻尾45"
        ModuleKind.TailCurl -> "巻き尻尾"
        ModuleKind.Hat -> "シルクハット"
        ModuleKind.Legs -> "足"
        ModuleKind.Hand -> "手"
        ModuleKind.HandLight -> "ライト手"
        ModuleKind.Face -> "顔"
    }

    fun icon(k: ModuleKind) = when (k) {
        ModuleKind.Ear -> IconId.Ear
        ModuleKind.Tail, ModuleKind.Tail2, ModuleKind.Tail45, ModuleKind.TailCurl -> IconId.Tail
        ModuleKind.Hat -> IconId.Hat
        ModuleKind.Legs -> IconId.Leg
        ModuleKind.Hand -> IconId.Hand
        ModuleKind.HandLight -> IconId.Flashlight
        ModuleKind.Face -> IconId.Aidy
    }

    fun tint(k: ModuleKind): Color = when (k) {
        ModuleKind.Ear -> Theme.hex(0xf97316)
        ModuleKind.Tail -> Theme.hex(0x8b5cf6)
        ModuleKind.Tail2 -> Theme.hex(0x6366f1)
        ModuleKind.Tail45 -> Theme.hex(0xa855f7)
        ModuleKind.TailCurl -> Theme.hex(0x0ea5e9)
        ModuleKind.Hat -> Theme.hex(0x334155)
        ModuleKind.Legs -> Theme.hex(0x10b981)
        ModuleKind.Hand -> Theme.hex(0xec4899)
        ModuleKind.HandLight -> Theme.hex(0xeab308)
        ModuleKind.Face -> Theme.Primary
    }

    /** モジュールをそのスロットに付けられるか (空きかどうかは見ない)。 */
    fun fits(k: ModuleKind, s: BodySlot): Boolean = when (k) {
        ModuleKind.Face -> s.isMonitor
        ModuleKind.Ear -> !s.isMonitor && (s.face == "top" || s.face == "left" || s.face == "right")
        ModuleKind.Tail, ModuleKind.Tail2, ModuleKind.Tail45, ModuleKind.TailCurl -> !s.isMonitor && (s.face == "back" || s.face == "top")
        ModuleKind.Hat -> s.id == "top_2" // つばが 80mm あるので上面の真ん中だけ
        ModuleKind.Legs -> !s.isMonitor && s.face == "bottom"
        ModuleKind.Hand, ModuleKind.HandLight -> !s.isMonitor && (s.face == "left" || s.face == "right")
    }

    fun isTail(k: ModuleKind) = k == ModuleKind.Tail || k == ModuleKind.Tail2 || k == ModuleKind.Tail45 || k == ModuleKind.TailCurl

    /** モデルファイル (assets/models/ 以下、拡張子なし)。stl → obj → 3mf の順に探す。顔はモデルを持たない (本体v2_顔ありに切り替え)。 */
    fun modelPath(k: ModuleKind): String? = when (k) {
        ModuleKind.Ear -> "models/ear"
        ModuleKind.Tail -> "models/tail"
        ModuleKind.Tail2 -> "models/tail2"
        ModuleKind.Tail45 -> "models/tail45"
        ModuleKind.TailCurl -> "models/tail_curl"
        ModuleKind.Hat -> "models/hat"
        ModuleKind.Hand -> "models/hand"
        ModuleKind.HandLight -> "models/hand_light"
        ModuleKind.Legs -> "models/legs"
        ModuleKind.Face -> null
    }

    fun axis(k: ModuleKind) = when (k) {
        ModuleKind.Ear, ModuleKind.Hat -> MountAxis.ZUpFrontMinusY
        ModuleKind.Tail, ModuleKind.Tail2, ModuleKind.Tail45 -> MountAxis.SlotTabPlusY
        ModuleKind.TailCurl -> MountAxis.EndPlusX
        ModuleKind.Legs -> MountAxis.TopPadZUp
        else -> MountAxis.MagnetPlusX
    }

    /** その面に付けられるモジュール。 */
    fun forFace(face: String): List<ModuleKind> =
        All.filter { k -> BodyLayout.onFace(face).any { fits(k, it) } }
}

/** モデルファイルの向き。取り付け面ローカル (+Y=外向き, +Z=正面) へどう変換するか。 */
enum class MountAxis {
    /** Z 上・-Y 正面・原点 (x,y) が取り付け位置、底面を取り付け面に置く (mimi2.stl)。 */
    ZUpFrontMinusY,
    /** -Y 方向へ突き出す。+Y 側の端 (XZ の中心) を取り付け面に置く。 */
    OutwardMinusY,
    /** +X 側の面にあるマグネット用のくぼみを本体に当てる。くぼみの中心を取り付け位置に、+Y 側の端を正面 (+Z) に向ける (手_短い*.3mf)。 */
    MagnetPlusX,
    /** Z 上・+Y 外向き。-Y 側の台座の底面 (Y 最小の面) を取り付け面に置き、その中心を取り付け位置にして +Z 側へ反らせる (tail.stl, tail2.stl, 尻尾_45.stl)。 */
    SlotTabPlusY,
    /** Z 上・-X 外向き。+X 端の面 (X 最大の面) を取り付け面に置き、その中心を取り付け位置にする (尻尾_巻き2.stl)。 */
    EndPlusX,
    /** Z 上・-Y 正面。上面の平らな面を本体の底面に当て、その中心を取り付け位置にする。左右 2 つ入りなら X が小さい方だけ使う (足v1.3mf)。 */
    TopPadZUp,
}

/**
 * 本体の取り付け口 (接続スロット13本 + 顔モニター開口)。座標は本体の一辺=1 に正規化。
 * Unity 版の JSON (+X=正面から見て左) を X 反転して、この座標 (+X=正面から見て右) で持つ。
 */
class BodySlot(
    val id: String,
    val face: String,
    val label: String,
    val position: V3,
    val normal: V3,
    val longAxis: V3,
    val isMonitor: Boolean,
    val widthMm: Float,
    val heightMm: Float,
) {
    /** 取り付け面のローカル座標系 (+Y=外向き法線, +Z=正面 or 上)。 */
    val mountRotation: Q
        get() {
            val forward = if (abs(normal.z) > 0.5f) V3.Up else V3.Forward
            return Q.look(forward, normal)
        }
}

/** assets/models/body_v2_layout.json (本体v2.3mf から測った寸法) から取り付け口を読み込む。 */
object BodyLayout {
    val Faces = listOf("top", "front", "back", "left", "right", "bottom")

    val slots: List<BodySlot> by lazy { load() }

    fun get(id: String): BodySlot? = slots.firstOrNull { it.id == id }

    fun onFace(face: String) = slots.filter { it.face == face }

    fun faceLabel(face: String) = when (face) {
        "top" -> "上面"
        "front" -> "正面"
        "back" -> "背面"
        "left" -> "左面"
        "right" -> "右面"
        else -> "底面"
    }

    /** 面の外向き法線 (この座標。左面 = 正面から見て左 = -X)。 */
    fun faceNormal(face: String) = when (face) {
        "top" -> V3.Up
        "front" -> V3.Forward
        "back" -> -V3.Forward
        "left" -> -V3.Right
        "right" -> V3.Right
        else -> -V3.Up
    }

    /** その面を外から見たときの 2D 座標 (右, 上)。上面/底面は正面が画面下/上になる向き。 */
    fun project(face: String, p: V3): Pair<Float, Float> = when (face) {
        "front" -> p.x to p.y
        "back" -> -p.x to p.y
        "left" -> p.z to p.y
        "right" -> -p.z to p.y
        "top" -> p.x to -p.z
        else -> p.x to p.z
    }

    private fun load(): List<BodySlot> {
        val list = mutableListOf<BodySlot>()
        val text = try {
            AidyApplication.context.assets.open("models/body_v2_layout.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.w("AIDY", "body_v2_layout.json が見つかりません")
            return list
        }
        val features = JSONObject(text).getJSONArray("features")
        val perFace = HashMap<String, Int>()
        fun v(o: JSONObject?) = if (o == null) V3.Zero
        else V3(o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optDouble("z").toFloat()).mirrorX()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val id = f.getString("id")
            val monitor = id == "monitor"
            if (f.optString("type") != "slot" && !monitor) continue // カメラ穴・マイク穴は取り付け口ではない
            val face = f.getString("face")
            val n = (perFace[face] ?: 0) + 1
            perFace[face] = n
            list += BodySlot(
                id = id,
                face = face,
                label = if (monitor) "モニター" else "スロット$n",
                position = v(f.optJSONObject("position")),
                normal = v(f.optJSONObject("normal")),
                longAxis = v(f.optJSONObject("longAxis")),
                isMonitor = monitor,
                widthMm = f.optDouble("widthMm", 0.0).toFloat(),
                heightMm = f.optDouble("heightMm", 0.0).toFloat(),
            )
        }
        return list
    }
}

/**
 * 本体に付いているモジュールの構成 (スロット ID → モジュール)。ホーム・付け替えプレビュー・AR の
 * すべての 3D モデルがこれを表示する。端末に保存される。
 */
object ModuleConfig {
    private const val PrefKey = "aidy.modules.v1"
    private var slots: LinkedHashMap<String, ModuleKind>? = null

    /** (スロット, 外れたモジュール, 付いたモジュール)。差し替えは両方入る。 */
    val changed = mutableListOf<(String, ModuleKind?, ModuleKind?) -> Unit>()

    /** Compose の再描画用 (変わるたびに増える)。 */
    var version by mutableIntStateOf(0)
        private set

    val current: Map<String, ModuleKind>
        get() = load()

    fun at(slotId: String): ModuleKind? = load()[slotId]

    val faceOn get() = has(ModuleKind.Face)
    val hasLegs get() = has(ModuleKind.Legs)

    fun has(kind: ModuleKind) = load().values.any { it == kind }

    /** そのスロットに付けられるか (空きであること)。 */
    fun canAttach(kind: ModuleKind, slotId: String): Boolean {
        val s = BodyLayout.get(slotId) ?: return false
        return ModuleCatalog.fits(kind, s) && at(slotId) == null
    }

    fun attach(kind: ModuleKind, slotId: String): Boolean {
        if (!canAttach(kind, slotId)) return false
        load()[slotId] = kind
        save()
        fire(slotId, null, kind)
        return true
    }

    fun detach(slotId: String) {
        val old = at(slotId) ?: return
        load().remove(slotId)
        save()
        fire(slotId, old, null)
    }

    /** 差し替え (old → kind)。空きなら普通に取り付け。 */
    fun replace(kind: ModuleKind, slotId: String): Boolean {
        val s = BodyLayout.get(slotId) ?: return false
        if (!ModuleCatalog.fits(kind, s)) return false
        val old = at(slotId)
        if (old == kind) return false
        load()[slotId] = kind
        save()
        fire(slotId, old, kind)
        return true
    }

    /** 標準構成 (初期状態・名刺マーカーの Aidy): 上面に耳2つ・背面に尻尾・底面に足2組・正面に顔。 */
    val Default: Map<String, ModuleKind> = linkedMapOf(
        "top_1" to ModuleKind.Ear,
        "top_3" to ModuleKind.Ear,
        "back_1" to ModuleKind.Tail,
        "bottom_1" to ModuleKind.Legs,
        "bottom_3" to ModuleKind.Legs,
        "monitor" to ModuleKind.Face,
    )

    fun resetToDefault() {
        for (id in load().keys.toList()) detach(id)
        for ((k, v) in Default) attach(v, k)
    }

    private fun fire(slot: String, removed: ModuleKind?, added: ModuleKind?) {
        version++
        for (l in changed.toList()) l(slot, removed, added)
    }

    private fun load(): LinkedHashMap<String, ModuleKind> {
        slots?.let { return it }
        val map = LinkedHashMap<String, ModuleKind>()
        slots = map
        val saved = Prefs.getString(PrefKey, "")
        if (saved.isEmpty()) {
            resetToDefault()
            return map
        }
        map.putAll(parse(saved))
        return map
    }

    private fun save() {
        Prefs.putString(PrefKey, format(load()))
    }

    /** "top_1:Ear,back_1:Tail" (Unity 版の PlayerPrefs と同じ形式)。 */
    fun format(m: Map<String, ModuleKind>) = m.entries.joinToString(",") { "${it.key}:${it.value.name}" }

    fun parse(text: String): LinkedHashMap<String, ModuleKind> {
        val map = LinkedHashMap<String, ModuleKind>()
        for (pair in text.split(',')) {
            val kv = pair.split(':')
            if (kv.size != 2) continue
            val k = ModuleKind.entries.firstOrNull { it.name == kv[1] } ?: continue
            if (BodyLayout.get(kv[0]) != null) map[kv[0]] = k
        }
        return map
    }

    /** 構成の一覧表示 (耳×2・尻尾…)。 */
    fun summary(slots: Map<String, ModuleKind>): String {
        val counts = HashMap<ModuleKind, Int>()
        for (k in slots.values) counts[k] = (counts[k] ?: 0) + 1
        val parts = ModuleCatalog.All.mapNotNull { k ->
            val n = counts[k] ?: return@mapNotNull null
            if (n > 1) "${ModuleCatalog.name(k)}×$n" else ModuleCatalog.name(k)
        }
        return if (parts.isNotEmpty()) parts.joinToString("・") else "なし (本体だけ)"
    }
}
