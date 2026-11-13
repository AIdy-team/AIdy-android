package com.aidy.petrobot.data

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.aidy.petrobot.AidyApplication
import com.aidy.petrobot.ar.ArucoDetector
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * AR に使う ArUco マーカー (DICT_4X4_50)。ID0 = いまの着せ替え、ID1 = 名刺 (標準構成)、
 * ID2〜 = アプリで作った着せ替えマーカー (MarkerStore)。印刷用の ID0 / ID1 は docs/markers/ にもある。
 * 寸法は黒い正方形 (外枠を含む) の一辺。印刷した大きさと違うと、モデルの距離・大きさがずれる。
 */
object AidyMarkers {
    /** アプリで着せ替えたいまの Aidy を出すマーカー。 */
    const val MyId = 0
    /** 名刺などで配るマーカー。標準構成の Aidy を出す。 */
    const val CardId = 1

    const val MySizeM = 0.08f
    const val CardSizeM = 0.03f

    /** いま認識する ID (固定の 2 つ + 作った着せ替えマーカー)。 */
    fun activeIds(): Set<Int> = buildSet {
        add(MyId)
        add(CardId)
        addAll(MarkerStore.ids)
    }

    fun size(id: Int) = if (id == CardId) CardSizeM else MySizeM
    fun sizeMm(id: Int) = (size(id) * 1000f).roundToInt()

    fun name(id: Int) = when (id) {
        MyId -> "あなたのAidy"
        CardId -> "名刺のAidy (標準)"
        else -> "着せ替えAidy #$id"
    }

    /** そのマーカーで出す色のパターン。いまの着せ替えマーカーはいまの色、名刺は標準のグレー。 */
    fun color(id: Int): ColorPattern = when (id) {
        MyId -> BodyColor.pattern
        CardId -> ColorPattern.Default
        else -> MarkerStore.color(id) ?: ColorPattern.Default
    }

    /** そのマーカーで出す構成。null = いまの着せ替え (ModuleConfig)。 */
    fun config(id: Int): Map<String, ModuleKind>? = when (id) {
        MyId -> null
        CardId -> ModuleConfig.Default
        else -> MarkerStore.get(id)
    }
}

/**
 * アプリで作った着せ替えマーカー (ID → そのときのモジュール構成)。端末に保存される。
 * マーカーと構成の対応はこの端末にしかないので、ほかの端末でかざしても出ない。
 */
object MarkerStore {
    private const val PrefKey = "aidy.markers.v1"
    private const val FirstId = 2

    private class Entry(val slots: Map<String, ModuleKind>, var color: ColorPattern?)

    private var saved: TreeMap<Int, Entry>? = null

    /** 作った・消した・色を変えたとき。 */
    val changed = mutableListOf<() -> Unit>()

    val ids: List<Int> get() = load().keys.toList()

    fun get(id: Int): Map<String, ModuleKind>? = load()[id]?.slots

    /** そのマーカーの色のパターン (作ったときの色)。古いマーカーなど色がなければ null。 */
    fun color(id: Int): ColorPattern? = load()[id]?.color

    /** 同じ構成・同じ色のマーカーがあればその ID。 */
    fun find(slots: Map<String, ModuleKind>, color: ColorPattern): Int? =
        load().entries.firstOrNull { it.value.slots == slots && (it.value.color ?: ColorPattern.Default) == color }?.key

    /** 構成と色を空いている ID に保存する。空きがなければ null。 */
    fun create(slots: Map<String, ModuleKind>, color: ColorPattern): Int? {
        val map = load()
        for (id in FirstId until ArucoDetector.Dict4x4_50.size) {
            if (map.containsKey(id)) continue
            map[id] = Entry(LinkedHashMap(slots), color)
            save()
            changed.toList().forEach { it() }
            return id
        }
        return null
    }

    /** 作ったマーカーの色を変える。 */
    fun setColor(id: Int, color: ColorPattern) {
        val e = load()[id] ?: return
        if (e.color == color) return
        e.color = color
        save()
        changed.toList().forEach { it() }
    }

    fun delete(id: Int) {
        if (load().remove(id) == null) return
        save()
        changed.toList().forEach { it() }
    }

    // 形式: "2=top_1:Ear,back_1:Tail45|#A0A0A0,*#2F4A8A,top_1#FFFFFF;3=..." (Unity 版の形式 + "|色のパターン")
    private fun load(): TreeMap<Int, Entry> {
        saved?.let { return it }
        val map = TreeMap<Int, Entry>()
        saved = map
        for (entry in Prefs.getString(PrefKey, "").split(';')) {
            if (entry.isEmpty()) continue
            val head = entry.split('=')
            if (head.size != 2) continue
            val id = head[0].toIntOrNull() ?: continue
            val body = head[1].split('|')
            map[id] = Entry(ModuleConfig.parse(body[0]), body.getOrNull(1)?.let { ColorPattern.parse(it) })
        }
        return map
    }

    private fun save() {
        Prefs.putString(PrefKey, load().entries.joinToString(";") { (id, e) ->
            "$id=${ModuleConfig.format(e.slots)}" + (e.color?.let { "|" + it.format() } ?: "")
        })
    }
}

/** マーカーの画像を作る・保存する。 */
object MarkerImage {
    private const val PrintDpi = 300

    /** 白い余白 (1 マス) 付きのマーカー画像。cellPx = 1 マスの画素数。 */
    fun create(id: Int, cellPx: Int): Bitmap {
        val n = 8 * cellPx // 余白 1 + 黒枠 1 + 4 ビット + 黒枠 1 + 余白 1
        val px = IntArray(n * n)
        val code = ArucoDetector.Dict4x4_50[id]
        for (y in 0 until n) {
            val row = y / cellPx
            for (x in 0 until n) {
                val col = x / cellPx
                val white = when {
                    row == 0 || row == 7 || col == 0 || col == 7 -> true          // 余白
                    row == 1 || row == 6 || col == 1 || col == 6 -> false         // 黒枠
                    else -> (code shr (15 - ((row - 2) * 4 + (col - 2))) and 1) == 1 // 1 = 白
                }
                px[y * n + x] = if (white) -0x1 else -0x1000000
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /**
     * 印刷用 PNG を保存し、保存先を返す。黒い正方形が AidyMarkers.size の大きさになるよう 300dpi で作り、PNG に解像度を書き込む。
     * Android 10 以降はギャラリー (Pictures/AIDY)、それより前はアプリのフォルダ。
     */
    fun savePng(id: Int): String {
        val squarePx = (AidyMarkers.sizeMm(id) / 25.4f * PrintDpi).roundToInt()
        val bmp = create(id, maxOf(8, squarePx / 6))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val png = withDpi(out.toByteArray(), PrintDpi)
        val file = "aidy_marker_id${id}_${AidyMarkers.sizeMm(id)}mm.png"
        saveToGallery(file, png)?.let { return it }
        val dir = File(AidyApplication.context.getExternalFilesDir(null) ?: AidyApplication.context.filesDir, "Markers")
        dir.mkdirs()
        val path = File(dir, file)
        path.writeBytes(png)
        return path.absolutePath
    }

    /** Android 10 以降: MediaStore で Pictures/AIDY に保存 (権限不要)。それより前は null。 */
    private fun saveToGallery(file: String, png: ByteArray): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val resolver = AidyApplication.context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AIDY")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(png) } ?: return null
            "ギャラリー (Pictures/AIDY/$file)"
        } catch (e: Exception) {
            Log.w("AIDY", "ギャラリーへの保存に失敗: ${e.message}")
            null
        }
    }

    /** PNG の IHDR の後ろに pHYs (解像度) を足す。印刷ソフトがこの dpi で原寸に印刷する。 */
    private fun withDpi(png: ByteArray, dpi: Int): ByteArray {
        val ihdrEnd = 8 + 4 + 4 + 13 + 4 // シグネチャ + IHDR (長さ・種類・データ・CRC)
        if (png.size < ihdrEnd) return png
        val ppm = (dpi / 0.0254f).roundToInt().toLong()
        val chunk = ByteArray(4 + 4 + 9 + 4)
        writeU32(chunk, 0, 9)
        chunk[4] = 'p'.code.toByte(); chunk[5] = 'H'.code.toByte(); chunk[6] = 'Y'.code.toByte(); chunk[7] = 's'.code.toByte()
        writeU32(chunk, 8, ppm)
        writeU32(chunk, 12, ppm)
        chunk[16] = 1 // 単位: メートル
        writeU32(chunk, 17, crc(chunk, 4, 13))
        val o = ByteArray(png.size + chunk.size)
        System.arraycopy(png, 0, o, 0, ihdrEnd)
        System.arraycopy(chunk, 0, o, ihdrEnd, chunk.size)
        System.arraycopy(png, ihdrEnd, o, ihdrEnd + chunk.size, png.size - ihdrEnd)
        return o
    }

    private fun writeU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private fun crc(b: ByteArray, offset: Int, count: Int): Long {
        var c = 0xFFFFFFFFL
        for (i in offset until offset + count) {
            c = c xor (b[i].toLong() and 0xFF)
            repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        }
        return c xor 0xFFFFFFFFL
    }
}
