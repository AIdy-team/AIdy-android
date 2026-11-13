package com.aidy.petrobot.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 色のパターン: 本体の色 + パーツの色。
 * パーツの色は「取り付け口ごとの色 (slots)」→「パーツ全部の色 (parts)」→ 標準のグレーの順に決まる。
 * 色は 0xffRRGGBB。
 */
data class ColorPattern(
    val body: Int,
    val parts: Int? = null,
    val slots: Map<String, Int> = emptyMap(),
) {
    /** その取り付け口のパーツの色。 */
    fun partColor(slotId: String): Int = slots[slotId] ?: parts ?: BodyColor.PartDefault

    fun withBody(c: Int) = copy(body = c or Opaque)

    /** パーツ全部をこの色にする (取り付け口ごとの色は消す)。 */
    fun withParts(c: Int) = copy(parts = c or Opaque, slots = emptyMap())

    fun withSlot(slotId: String, c: Int) = copy(slots = slots + (slotId to (c or Opaque)))

    /** 文字にする: "#本体,*#パーツ,top_1#色,..."。マーカーの保存形式に入るので ; = | は使わない。 */
    fun format(): String = buildString {
        append(BodyColor.hex(body))
        parts?.let { append(",*").append(BodyColor.hex(it)) }
        for ((id, c) in slots.toSortedMap()) append(',').append(id).append(BodyColor.hex(c))
    }

    companion object {
        private const val Opaque = 0xff000000.toInt()

        val Default = ColorPattern(BodyColor.Default)

        /** format の逆。昔の "#RRGGBB" (本体だけ) も読める。読めなければ null。 */
        fun parse(s: String): ColorPattern? {
            val tokens = s.trim().split(',').filter { it.isNotBlank() }
            val body = tokens.firstOrNull()?.let { BodyColor.parse(it) } ?: return null
            var parts: Int? = null
            val slots = LinkedHashMap<String, Int>()
            for (t in tokens.drop(1)) {
                val i = t.indexOf('#')
                if (i < 0) continue
                val c = BodyColor.parse(t.substring(i)) ?: continue
                val key = t.substring(0, i)
                if (key == "*") parts = c else if (key.isNotEmpty()) slots[key] = c
            }
            return ColorPattern(body, parts, slots)
        }
    }
}

/**
 * いまの色のパターン (ホームで選ぶ) と、パターンのプリセット (用意したもの + ユーザーが保存したもの)。端末に保存する。
 */
object BodyColor {
    /** 3D モデルのもとの色 (グレー)。 */
    const val Default = 0xffa0a0a0.toInt()
    /** パーツのもとの色。 */
    const val PartDefault = 0xffa0a0a0.toInt()
    const val MaxPresets = 12

    private const val OldCurrentKey = "aidy.body.color"
    private const val OldPresetsKey = "aidy.body.colorPresets"
    private const val PatternKey = "aidy.color.pattern"
    private const val PatternPresetsKey = "aidy.color.patternPresets"

    /** 色を選ぶときの見本の色。 */
    val BuiltIn: List<Int> = listOf(
        Default,
        0xfff5f5f0.toInt(), // ホワイト
        0xff3a3d44.toInt(), // チャコール
        0xff7cc4f0.toInt(), // スカイ
        0xfff4a7c0.toInt(), // ピンク
        0xff8fd6b4.toInt(), // ミント
        0xfff6d36b.toInt(), // イエロー
        0xfff08a5d.toInt(), // オレンジ
        0xffa78bfa.toInt(), // ラベンダー
        0xff2f4a8a.toInt(), // ネイビー
        0xffe5484d.toInt(), // レッド
        0xff8b5e3c.toInt(), // ブラウン
    )

    /** 最初から選べるパターン (消せない)。 */
    val BuiltInPatterns: List<ColorPattern> = listOf(
        ColorPattern.Default,
        ColorPattern(0xfff5f5f0.toInt(), 0xff2f4a8a.toInt()), // 白 × ネイビー
        ColorPattern(0xff3a3d44.toInt(), 0xfff08a5d.toInt()), // チャコール × オレンジ
        ColorPattern(0xff7cc4f0.toInt(), 0xfff5f5f0.toInt()), // スカイ × 白
        ColorPattern(0xfff4a7c0.toInt(), 0xfff5f5f0.toInt()), // ピンク × 白
        ColorPattern(0xff8fd6b4.toInt(), 0xfff6d36b.toInt()), // ミント × イエロー
        ColorPattern(0xfff6d36b.toInt(), 0xff3a3d44.toInt()), // イエロー × チャコール
        ColorPattern(0xffa78bfa.toInt(), 0xfff4a7c0.toInt()), // ラベンダー × ピンク
        ColorPattern(0xff2f4a8a.toInt(), 0xff7cc4f0.toInt()), // ネイビー × スカイ
        ColorPattern(0xfff5f5f0.toInt(), 0xffe5484d.toInt()), // 白 × レッド
    )

    /** 変わったら増える (Compose の再描画用)。 */
    var version by mutableIntStateOf(0)
        private set

    /** パターンが変わったとき (3D の材質の更新)。 */
    val changed = mutableListOf<(ColorPattern) -> Unit>()

    private var cur: ColorPattern? = null
    private var presets: MutableList<ColorPattern>? = null

    /** いまのパターン。 */
    var pattern: ColorPattern
        get() = cur ?: (
            ColorPattern.parse(Prefs.getString(PatternKey, ""))
                ?: ColorPattern(Prefs.getInt(OldCurrentKey, Default)) // 本体の色だけだった頃の保存
            ).also { cur = it }
        set(value) {
            if (value == pattern) return
            cur = value
            Prefs.putString(PatternKey, value.format())
            version++
            changed.toList().forEach { it(value) }
        }

    /** いまの本体の色。 */
    val current: Int get() = pattern.body

    /** 保存したパターン (新しい順)。 */
    val saved: List<ColorPattern> get() = loadPresets().toList()

    /** プリセットに保存する。同じものがあれば先頭へ移す。いっぱいなら一番古いものを消す。 */
    fun savePreset(p: ColorPattern) {
        val list = loadPresets()
        list.remove(p)
        list.add(0, p)
        while (list.size > MaxPresets) list.removeAt(list.size - 1)
        storePresets()
    }

    fun deletePreset(p: ColorPattern) {
        if (loadPresets().remove(p)) storePresets()
    }

    private fun loadPresets(): MutableList<ColorPattern> {
        presets?.let { return it }
        val stored = Prefs.getString(PatternPresetsKey, "")
        val list = if (stored.isNotEmpty()) stored.split(';').mapNotNull { ColorPattern.parse(it) }.toMutableList()
        else Prefs.getString(OldPresetsKey, "").split(',').mapNotNull { parse(it) }.map { ColorPattern(it) }.toMutableList()
            .also { if (it.isNotEmpty()) { Prefs.putString(PatternPresetsKey, it.joinToString(";") { p -> p.format() }); Prefs.putString(OldPresetsKey, "") } }
        presets = list
        return list
    }

    private fun storePresets() {
        Prefs.putString(PatternPresetsKey, loadPresets().joinToString(";") { it.format() })
        version++
    }

    /** "#RRGGBB"。 */
    fun hex(c: Int) = "#%06X".format(c and 0xffffff)

    /** "#RRGGBB" / "RRGGBB" → 0xffRRGGBB。読めなければ null。 */
    fun parse(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toLongOrNull(16)?.let { (it.toInt() or 0xff000000.toInt()) }
    }
}
