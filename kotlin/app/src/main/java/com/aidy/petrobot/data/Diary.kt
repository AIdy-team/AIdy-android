package com.aidy.petrobot.data

import android.util.Log
import com.aidy.petrobot.AidyApplication
import com.aidy.petrobot.ui.AidyFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Aidy 本体に残る会話ログの 1 行。time は "HH:mm"、speaker は "user" / "aidy"。 */
class ChatLine(var time: String, val speaker: String, val text: String) {
    val isUser get() = speaker == "user"

    fun toJson() = JSONObject().put("time", time).put("speaker", speaker).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = ChatLine(o.optString("time"), o.optString("speaker"), o.optString("text"))
    }
}

/** 1 日分の日記。date は "yyyy-MM-dd"。source は "device" (本体から同期) / "demo" (サンプル)。 */
class DiaryEntry(
    val date: String,
    val title: String,
    val summary: String,
    val mood: String,
    var source: String = "",
    val syncedAt: String = "",
    val logs: List<ChatLine> = emptyList(),
) {
    val userCount get() = logs.count { it.isUser }

    fun toJson(): JSONObject = JSONObject()
        .put("date", date).put("title", title).put("summary", summary).put("mood", mood)
        .put("source", source).put("syncedAt", syncedAt)
        .put("logs", JSONArray().apply { logs.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): DiaryEntry {
            val arr = o.optJSONArray("logs") ?: JSONArray()
            return DiaryEntry(
                o.optString("date"), o.optString("title"), o.optString("summary"), o.optString("mood"),
                o.optString("source"), o.optString("syncedAt"),
                (0 until arr.length()).map { ChatLine.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

val DateKey: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
fun LocalDate.key(): String = format(DateKey)

/** 日記の保存先 (filesDir/diary/yyyy-MM-dd.json)。起動後の初回アクセスで全件読み込む。 */
object DiaryStore {
    private var entries: HashMap<String, DiaryEntry>? = null
    private val dir get() = File(AidyApplication.context.filesDir, "diary")

    fun get(d: LocalDate): DiaryEntry? = load()[d.key()]

    /** 日付の新しい順。 */
    fun all(): List<DiaryEntry> = load().values.sortedByDescending { it.date }

    fun save(e: DiaryEntry) {
        load()[e.date] = e
        try {
            dir.mkdirs()
            File(dir, e.date + ".json").writeText(e.toJson().toString(2), Charsets.UTF_8)
        } catch (ex: IOException) {
            Log.w("AIDY", "日記の保存に失敗 ${e.date}: ${ex.message}")
        }
    }

    private fun load(): HashMap<String, DiaryEntry> {
        entries?.let { return it }
        val map = HashMap<String, DiaryEntry>()
        entries = map
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val e = DiaryEntry.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
                if (e.date.isNotEmpty()) map[e.date] = e
            } catch (ex: Exception) {
                Log.w("AIDY", "日記の読込に失敗 ${f.path}: ${ex.message}")
            }
        }
        return map
    }
}

/**
 * 会話ログから「Aidy が書いた一日日記」を組み立てる。
 * 本体側でタイトル/本文/気分を生成して送ってきた場合はそちらを優先する。
 */
object DiaryComposer {
    private val HappyWords = listOf("楽しい", "たのしい", "楽しかった", "うれしい", "嬉しい", "ありがとう", "好き", "すき", "おいしい", "美味しい", "やった", "最高", "面白", "おもしろ")
    private val TiredWords = listOf("疲れ", "つかれ", "眠い", "ねむい", "しんどい", "だるい", "つらい", "大変")

    fun compose(date: String, logs: List<ChatLine>?, title: String? = null, summary: String? = null, mood: String? = null): DiaryEntry {
        val sorted = (logs ?: emptyList()).filter { it.text.isNotEmpty() }.toMutableList()
        for (l in sorted) l.time = normalizeTime(l.time)
        sorted.sortWith { a, b -> a.time.compareTo(b.time) }

        val user = sorted.filter { it.isUser }
        val m = if (mood.isNullOrEmpty()) detectMood(user) else mood
        val t = if (title.isNullOrEmpty()) titleFor(m, user.size) else title
        val s = if (summary.isNullOrEmpty()) summarize(user, m) else summary

        return DiaryEntry(
            date = date, title = t, summary = s, mood = m, logs = sorted,
            syncedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
        )
    }

    fun faceFor(mood: String) = when (mood) {
        "happy" -> AidyFace.Happy
        "tired", "sleepy" -> AidyFace.Sleepy
        "surprised" -> AidyFace.Surprised
        else -> AidyFace.Normal
    }

    private fun detectMood(user: List<ChatLine>): String {
        var happy = 0
        var tired = 0
        for (l in user) {
            for (w in HappyWords) if (l.text.contains(w)) happy++
            for (w in TiredWords) if (l.text.contains(w)) tired++
        }
        if (happy == 0 && tired == 0) return "normal"
        return if (happy >= tired) "happy" else "tired"
    }

    private fun titleFor(mood: String, count: Int): String {
        if (count == 0) return "しずかな一日"
        return when (mood) {
            "happy" -> "たのしい一日"
            "tired" -> "ちょっとおつかれの一日"
            else -> if (count >= 8) "たくさんおしゃべりした一日" else "いつもの一日"
        }
    }

    private fun summarize(user: List<ChatLine>, mood: String): String {
        if (user.isEmpty()) return "今日はあまりおしゃべりできなかったね。明日はいっぱい話そうね。"

        val sb = StringBuilder()
        sb.append("今日は${clock(user[0].time)}から${clock(user[user.size - 1].time)}まで、${user.size}回おしゃべりしたよ。")
        val labels = listOf("朝", "昼", "夜")
        for (b in 0 until 3) {
            var best: ChatLine? = null
            for (l in user) if (bucket(l.time) == b && (best == null || l.text.length > best.text.length)) best = l
            if (best != null) sb.append("\n${labels[b]}は「${trim(best.text, 24)}」って話してくれたね。")
        }
        when (mood) {
            "happy" -> sb.append("\nたくさん笑ってくれて、ぼくもうれしかった！")
            "tired" -> sb.append("\nおつかれさま。今日はゆっくり休んでね。")
            else -> sb.append("\n明日もいっぱいお話ししようね。")
        }
        return sb.toString()
    }

    private fun bucket(time: String): Int {
        val h = time.split(':')[0].toIntOrNull() ?: 12
        return if (h < 11) 0 else if (h < 17) 1 else 2
    }

    private fun normalizeTime(t: String?): String {
        if (t.isNullOrEmpty()) return "00:00"
        val parts = t.split(':')
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return "%02d:%02d".format(h, m)
    }

    private fun clock(time: String) = if (time.startsWith("0")) time.substring(1) else time // "07:42" → "7:42"

    private fun trim(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max) + "…"
}

/**
 * Aidy 本体と話す。口 (/api/...) の形は AIdy_アプリ連携_仕様.md のとおり。
 * 通り道は 2 つから選ぶ:
 *   - Bluetooth (既定): BLE で /api/... を包んで送る (BleLink、docs/本体Bluetooth対応_仕様.txt)
 *   - Wi-Fi: http://{host}/api/... (DeviceNet)
 * すべての通信に合言葉が必要 (Wi-Fi はヘッダー X-Aidy-Token、Bluetooth はメッセージの "t")。
 * 401・403 はどちらも「合言葉が違います」として扱う。
 */
object AidyDeviceLink {
    private const val HostKey = "aidy.device.host"
    private const val TokenKey = "aidy.device.token"
    private const val ModeKey = "aidy.device.mode"
    private const val BleNameKey = "aidy.device.bleName"
    private const val LastSyncKey = "aidy.device.lastSync"
    private const val TimeoutMs = 4000

    const val DefaultPort = 8090

    enum class Mode { Bluetooth, Wifi }

    /** 本体とつなぐ通り道。既定は Bluetooth。 */
    var mode: Mode
        get() = if (Prefs.getString(ModeKey, "ble") == "wifi") Mode.Wifi else Mode.Bluetooth
        set(value) = Prefs.putString(ModeKey, if (value == Mode.Wifi) "wifi" else "ble")

    /** 本体のアドレス ("192.168.1.23:8090" など、QR の host の部分)。Wi-Fi のとき使う。未設定なら空文字。 */
    var host: String
        get() = Prefs.getString(HostKey, "")
        set(value) = Prefs.putString(HostKey, normalizeHost(value))

    /** 本体の Bluetooth の名前 ("AIdy-1A2B" など、QR の b= の部分)。空なら近くの AIdy を探す。 */
    var bleName: String
        get() = Prefs.getString(BleNameKey, "")
        set(value) {
            val v = value.trim()
            if (v != bleName) BleLink.forgetDevice()
            Prefs.putString(BleNameKey, v)
        }

    /**
     * 手で入れたアドレスを "ホスト:ポート" にそろえる。
     * "http://192.168.1.23:8090/" → "192.168.1.23:8090"、ポートが無ければ 8090 を足す。
     */
    fun normalizeHost(input: String): String {
        var s = input.trim().replace("　", "").replace(" ", "")
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (s.isEmpty()) return ""
        val hasPort = if (s.startsWith("[")) s.contains("]:") else s.count { it == ':' } == 1
        return if (hasPort) s else "$s:$DefaultPort"
    }

    /** 合言葉 (QR の "t=" の後ろ)。未設定なら空文字。画面には伏せ字でしか出さないこと。 */
    var token: String
        get() = Prefs.getString(TokenKey, "")
        set(value) = Prefs.putString(TokenKey, value.trim())

    /** 直前に /api/ping が答えた展示モードかどうか。 */
    var exhibit: Boolean = false
        private set

    val isConfigured get() = token.isNotEmpty() && (mode == Mode.Bluetooth || host.isNotEmpty())

    /** 画面に出す「どこにつなぐか」。 */
    val targetLabel: String
        get() = when (mode) {
            Mode.Bluetooth -> "Bluetooth " + bleName.ifEmpty { "(近くの Aidy)" }
            Mode.Wifi -> host.ifEmpty { "未設定" }
        }

    private const val NotConfigured = "本体と接続していません (設定 > Aidy本体と接続)"

    val lastSyncLabel: String
        get() {
            val t = Prefs.getString(LastSyncKey, "")
            return if (t.isEmpty()) "まだ本体と同期していません" else "最終同期: $t"
        }

    data class Result(val ok: Boolean, val message: String, val updated: Int = 0)
    data class WeekEvent(val time: String, val end: String, val title: String)
    data class WeekTodo(val label: String, val text: String)
    data class WeekDay(val date: String, val events: List<WeekEvent>, val todo: List<WeekTodo>)
    data class WeekResult(val ok: Boolean, val days: List<WeekDay>, val synced: Long, val error: String?)

    // ------------------------------------------------------------------ 通り道 (Bluetooth / Wi-Fi)

    /** 返事。code は HTTP と同じ意味 (200・401・404…)、body は本文 (JSON 文字列)。 */
    private class Resp(val code: Int, val body: String) {
        fun json(): JSONObject = JSONObject(body)
        /** 非200 のときに本体が返す {"error": "..."}。 */
        fun error(): String? = runCatching { JSONObject(body).optString("error").ifEmpty { null } }.getOrNull()
    }

    /** 1 回の問い合わせ。つながらないときは IOException (BleLink.BleException を含む)。 */
    private suspend fun call(method: String, path: String, body: JSONObject? = null): Resp =
        when (mode) {
            Mode.Bluetooth -> BleLink.request(bleName, method, path, token, body).let { Resp(it.first, it.second) }
            Mode.Wifi -> withContext(Dispatchers.IO) { httpCall(method, path, body) }
        }

    private fun httpCall(method: String, path: String, body: JSONObject?): Resp {
        val conn = DeviceNet.open(host, path)
        try {
            conn.connectTimeout = TimeoutMs
            conn.readTimeout = TimeoutMs
            conn.setRequestProperty("X-Aidy-Token", token)
            conn.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** 接続エラー・401・403 の言い回しをアプリ全体でそろえる。e があれば原因 (時間切れ・名前が引けない等) も添える。 */
    private fun errorMessage(code: Int?, e: Exception? = null) = when {
        code == 401 || code == 403 -> "合言葉が違います。本体のQRを読み直してください"
        code != null -> "本体でエラーが起きました ($code)"
        e is BleLink.BleException -> e.message ?: "Bluetooth で本体につながりません"
        e is java.net.UnknownHostException ->
            "本体の住所 ($host) が見つかりません。.local でなく IP (本体の「本体」タブの下に出ます) で入れてください"
        DeviceNet.wifiNetwork() == null -> "スマホが Wi-Fi につながっていません。本体と同じ Wi-Fi につないでください"
        e is java.net.SocketTimeoutException ->
            "Aidy本体につながりません ($host)。時間切れ: スマホと本体が同じ Wi-Fi か、IP が変わっていないか確かめてください"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
            "Aidy本体につながりません ($host)。本体が動いているか・アドレスとポート (8090) を確かめてください"
        else -> "Aidy本体につながりません ($host)"
    }

    /** /api/ping が返した本体のホスト名 (機体ごとに違う)。 */
    var deviceName: String = ""
        private set

    // ------------------------------------------------------------------ 口

    /** GET /api/ping。設定画面の「保存して接続を試す」で使う。 */
    suspend fun ping(): Result {
        if (token.isEmpty()) return Result(false, "合言葉を入れてください")
        if (mode == Mode.Wifi && host.isEmpty()) return Result(false, "住所と合言葉を入れてください")
        return try {
            val r = call("GET", "/api/ping")
            if (r.code != 200) return Result(false, errorMessage(r.code))
            val o = runCatching { r.json() }.getOrNull()
                ?: return Result(false, "本体からの返事を読み取れませんでした" + if (mode == Mode.Wifi) " (ポート番号が違うかも)" else "")
            exhibit = o.optBoolean("exhibit", false)
            deviceName = o.optString("host")
            val via = if (mode == Mode.Bluetooth) " (Bluetooth)" else " (Wi-Fi)"
            Result(true, (if (deviceName.isNotEmpty()) "Aidy本体 ($deviceName) につながりました" else "Aidy本体につながりました") + via)
        } catch (e: IOException) {
            Log.w("AIDY", "ping 失敗: ${e.javaClass.simpleName} ${e.message}")
            Result(false, errorMessage(null, e))
        } catch (e: Exception) {
            Log.w("AIDY", "ping 失敗: ${e.javaClass.simpleName} ${e.message}")
            Result(false, "Aidy本体につながりません: ${e.javaClass.simpleName}")
        }
    }

    /** 今日から days 日分さかのぼってログを取得し、日記を作り直して保存する。 */
    suspend fun syncRecent(days: Int): Result {
        if (!isConfigured) return Result(false, NotConfigured)

        var updated = 0
        for (i in 0 until days) {
            val date = LocalDate.now().minusDays(i.toLong()).key()
            val r = try {
                call("GET", "/api/logs?date=$date")
            } catch (e: IOException) {
                return Result(false, errorMessage(null, e), updated)
            }
            if (r.code == 401 || r.code == 403) return Result(false, errorMessage(r.code), updated)
            if (r.code != 200) continue // 404 = その日のログなし

            val entry = try {
                val o = r.json()
                val arr = o.optJSONArray("logs") ?: continue
                if (arr.length() == 0) continue
                val logs = (0 until arr.length()).map { ChatLine.fromJson(arr.getJSONObject(it)) }
                DiaryComposer.compose(date, logs, o.optString("title"), o.optString("summary"), o.optString("mood"))
            } catch (e: Exception) {
                Log.w("AIDY", "ログの解析に失敗 $date: ${e.message}")
                continue
            }
            entry.source = "device"
            withContext(Dispatchers.Main) { DiaryStore.save(entry) }
            updated++
        }
        Prefs.putString(LastSyncKey, LocalDateTime.now().format(DateTimeFormatter.ofPattern("M/d HH:mm")))
        return Result(true, if (updated > 0) "${updated}日分の日記を更新しました" else "新しい会話ログはありませんでした", updated)
    }

    /** QR から読めたもの。host は Wi-Fi 用 (無いこともある)、ble は Bluetooth の名前 (無いこともある)。 */
    data class QrInfo(val host: String, val token: String, val ble: String)

    /**
     * 本体の QR の中身から住所・合言葉・Bluetooth 名を取り出す。読めなければ null。
     *   "http://192.168.1.23:8090/?t=AbCdEf123456&b=AIdy-1A2B"   (Wi-Fi と Bluetooth の両方)
     *   "aidy://ble?b=AIdy-1A2B&t=AbCdEf123456"                   (Bluetooth だけ)
     */
    fun parseQr(raw: String): QrInfo? {
        val text = raw.trim()
        return try {
            val uri = URI(text)
            val params = (uri.rawQuery ?: return null).split("&")
                .map { it.split("=", limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8").trim() }
            val t = params["t"].orEmpty()
            val b = params["b"].orEmpty()
            val h = if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) normalizeHost(uri.rawAuthority ?: "") else ""
            if (t.isEmpty() || (h.isEmpty() && b.isEmpty() && !uri.scheme.equals("aidy", true))) null else QrInfo(h, t, b)
        } catch (e: URISyntaxException) {
            null
        }
    }

    /** GET /api/week (読むだけ)。今日から7日分の予定・やることを取得する。 */
    suspend fun fetchWeek(): WeekResult {
        if (!isConfigured) return WeekResult(false, emptyList(), 0, "本体と接続していません")
        return try {
            val r = call("GET", "/api/week")
            if (r.code != 200) return WeekResult(false, emptyList(), 0, errorMessage(r.code))
            val o = r.json()
            val arr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                val events = (d.optJSONArray("events") ?: JSONArray()).let { ea ->
                    (0 until ea.length()).map { j -> ea.getJSONObject(j).let { WeekEvent(it.optString("time"), it.optString("end"), it.optString("title")) } }
                }
                val todo = (d.optJSONArray("todo") ?: JSONArray()).let { ta ->
                    (0 until ta.length()).map { j -> ta.getJSONObject(j).let { WeekTodo(it.optString("label"), it.optString("text")) } }
                }
                WeekDay(d.optString("date"), events, todo)
            }
            WeekResult(true, days, o.optLong("synced", 0), null)
        } catch (e: IOException) {
            WeekResult(false, emptyList(), 0, errorMessage(null, e))
        } catch (e: Exception) {
            Log.w("AIDY", "week の解析に失敗: ${e.message}")
            WeekResult(false, emptyList(), 0, "本体からの返事を読み取れませんでした")
        }
    }

    data class DaysResult(val ok: Boolean, val days: Set<String>, val error: String?)

    /** GET /api/days。会話・記録がある日の一覧 ("yyyyMMdd") を "yyyy-MM-dd" に揃えて返す。 */
    suspend fun fetchDays(): DaysResult {
        if (!isConfigured) return DaysResult(false, emptySet(), "本体と接続していません")
        return try {
            val r = call("GET", "/api/days")
            if (r.code != 200) return DaysResult(false, emptySet(), errorMessage(r.code))
            val arr = r.json().optJSONArray("days") ?: JSONArray()
            val days = (0 until arr.length()).mapNotNull { i ->
                val s = arr.optString(i)
                if (s.length == 8) "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}" else null
            }.toSet()
            DaysResult(true, days, null)
        } catch (e: IOException) {
            DaysResult(false, emptySet(), errorMessage(null, e))
        } catch (e: Exception) {
            Log.w("AIDY", "days の解析に失敗: ${e.message}")
            DaysResult(false, emptySet(), "本体からの返事を読み取れませんでした")
        }
    }

    // ---------------------------------------------------------------- 辞書 (FAQ・聞き間違い・読み)

    data class FaqEntry(val q: String, val a: String)
    data class DictData(val faq: List<FaqEntry>, val asr: List<Pair<String, String>>, val yomi: List<Pair<String, String>>)
    data class DictResult(val ok: Boolean, val dict: DictData?, val error: String?)

    private fun stringList(arr: JSONArray?): List<String> =
        arr?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()

    private fun pairList(arr: JSONArray?): List<Pair<String, String>> =
        arr?.let {
            (0 until it.length()).mapNotNull { i ->
                val pair = it.optJSONArray(i)
                if (pair == null || pair.length() < 2) null else pair.optString(0) to pair.optString(1)
            }
        } ?: emptyList()

    private fun parseDict(o: JSONObject): DictData {
        val faqArr = o.optJSONArray("faq") ?: JSONArray()
        val faq = (0 until faqArr.length()).map { i ->
            val f = faqArr.getJSONObject(i)
            FaqEntry(stringList(f.optJSONArray("q")).firstOrNull() ?: "", stringList(f.optJSONArray("a")).firstOrNull() ?: "")
        }
        return DictData(faq, pairList(o.optJSONArray("asr")), pairList(o.optJSONArray("yomi")))
    }

    /** GET /api/dict。 */
    suspend fun fetchDict(): DictResult {
        if (!isConfigured) return DictResult(false, null, "本体と接続していません")
        return try {
            val r = call("GET", "/api/dict")
            if (r.code != 200) return DictResult(false, null, errorMessage(r.code))
            DictResult(true, parseDict(r.json()), null)
        } catch (e: IOException) {
            DictResult(false, null, errorMessage(null, e))
        } catch (e: Exception) {
            Log.w("AIDY", "dict の解析に失敗: ${e.message}")
            DictResult(false, null, "本体からの返事を読み取れませんでした")
        }
    }

    /** POST /api/add。kind は "faq" ・ "asr" ・ "yomi"。成功なら更新後の辞書 (dict) が返る。 */
    suspend fun addDictEntry(kind: String, a: String, b: String): DictResult {
        if (!isConfigured) return DictResult(false, null, "本体と接続していません")
        return postDict("/api/add", JSONObject().put("kind", kind).put("a", a).put("b", b))
    }

    /** POST /api/del ("asr" ・ "yomi" は key、"faq" は idx)。成功なら更新後の辞書 (dict) が返る。 */
    suspend fun deleteDictEntry(kind: String, key: String? = null, idx: Int? = null): DictResult {
        if (!isConfigured) return DictResult(false, null, "本体と接続していません")
        val body = JSONObject().put("kind", kind)
        if (key != null) body.put("key", key)
        if (idx != null) body.put("idx", idx)
        return postDict("/api/del", body)
    }

    private suspend fun postDict(path: String, body: JSONObject): DictResult {
        return try {
            val r = call("POST", path, body)
            if (r.code != 200) return DictResult(false, null, errorMessage(r.code))
            val res = r.json()
            if (!res.optBoolean("ok", false)) return DictResult(false, null, res.optString("error", "できませんでした"))
            DictResult(true, res.optJSONObject("dict")?.let { parseDict(it) }, null)
        } catch (e: IOException) {
            DictResult(false, null, errorMessage(null, e))
        } catch (e: Exception) {
            Log.w("AIDY", "辞書の更新に失敗: ${e.message}")
            DictResult(false, null, "本体からの返事を読み取れませんでした")
        }
    }

    // ------------------------------------------------------------ 本体の設定 (/api/settings)

    /**
     * 本体の設定 1 項目 (GET /api/settings の items の 1 つ)。
     * level は 0〜max。show があればその文字 (「オフ」「少なめ」など) を出し、なければ段階のバーで見せる。
     * アプリは並び・label・max を本体の返事のまま使う (キーが増えても作り直さなくてよいように)。
     */
    data class SettingItem(val key: String, val label: String, val level: Int, val max: Int, val value: Double, val show: String)

    data class SettingsResult(val ok: Boolean, val items: List<SettingItem>, val error: String?, val unsupported: Boolean = false)

    private fun parseSettings(o: JSONObject): List<SettingItem>? {
        val arr = o.optJSONArray("items") ?: return null
        if (o.has("exhibit")) exhibit = o.optBoolean("exhibit", exhibit)
        return (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = it.optString("key")
            if (key.isEmpty()) return@mapNotNull null
            val max = it.optInt("max", 0).coerceAtLeast(0)
            SettingItem(key, it.optString("label", key), it.optInt("level", 0).coerceIn(0, max), max, it.optDouble("value", 0.0), it.optString("show"))
        }
    }

    private fun readSettings(r: Resp): SettingsResult {
        when (r.code) {
            200 -> {}
            404 -> return SettingsResult(false, SettingsStore.items, "本体がまだ設定の変更に対応していません", unsupported = true)
            400 -> return SettingsResult(false, SettingsStore.items, r.error() ?: "key か level が違います")
            else -> return SettingsResult(false, SettingsStore.items, errorMessage(r.code))
        }
        val items = parseSettings(r.json()) ?: return SettingsResult(false, SettingsStore.items, "本体からの返事を読み取れませんでした")
        SettingsStore.items = items
        return SettingsResult(true, items, null)
    }

    /** GET /api/settings。読めたら端末にも覚えておく (つながっていないときの表示用)。 */
    suspend fun fetchSettings(): SettingsResult = settingsCall("GET", null)

    /** POST /api/settings {"key": "voice_db", "level": 4}。返事は GET と同じ一覧。 */
    suspend fun saveSetting(key: String, level: Int): SettingsResult =
        settingsCall("POST", JSONObject().put("key", key).put("level", level))

    private suspend fun settingsCall(method: String, body: JSONObject?): SettingsResult {
        if (!isConfigured) return SettingsResult(false, SettingsStore.items, "本体と接続していません")
        return try {
            readSettings(call(method, "/api/settings", body))
        } catch (e: IOException) {
            SettingsResult(false, SettingsStore.items, errorMessage(null, e))
        } catch (e: Exception) {
            Log.w("AIDY", "settings に失敗: ${e.message}")
            SettingsResult(false, SettingsStore.items, "本体からの返事を読み取れませんでした")
        }
    }
}

/** 最後に本体から読めた設定一覧 (本体とつながっていなくても表示する用)。 */
object SettingsStore {
    private const val Key = "aidy.device.settings"

    var items: List<AidyDeviceLink.SettingItem>
        get() = try {
            val arr = JSONArray(Prefs.getString(Key, "[]"))
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let {
                    AidyDeviceLink.SettingItem(it.optString("key"), it.optString("label"), it.optInt("level"), it.optInt("max"), it.optDouble("value", 0.0), it.optString("show"))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(value) {
            val arr = JSONArray()
            value.forEach {
                arr.put(JSONObject().put("key", it.key).put("label", it.label).put("level", it.level).put("max", it.max).put("value", it.value).put("show", it.show))
            }
            Prefs.putString(Key, arr.toString())
        }
}

/** 日記カレンダーの表示設定 (月表示 / 週表示)。設定画面から切り替える。 */
object CalendarSettings {
    private const val WeekViewKey = "aidy.calendar.weekView"

    var weekView: Boolean
        get() = Prefs.getInt(WeekViewKey, 0) == 1
        set(value) = Prefs.putInt(WeekViewKey, if (value) 1 else 0)
}

/** カレンダーに登録する予定 1 件。date は "yyyy-MM-dd"、time は "HH:mm" (空なら終日)。 */
data class ScheduleItem(
    val id: String = "",
    val date: String = "",
    val time: String = "",
    val title: String = "",
    val memo: String = "",
) {
    val allDay get() = time.isEmpty()

    /** 時刻つきの予定で、その時刻をもう過ぎているか (終日の予定は過ぎない扱い)。 */
    fun isPast(now: LocalDateTime) =
        !allDay && "$date $time" < now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    fun toJson(): JSONObject = JSONObject().put("id", id).put("date", date).put("time", time).put("title", title).put("memo", memo)

    companion object {
        fun fromJson(o: JSONObject) = ScheduleItem(o.optString("id"), o.optString("date"), o.optString("time"), o.optString("title"), o.optString("memo"))
    }
}

/** 予定の保存先 (filesDir/schedule.json)。過去・未来どの日にも登録できる。 */
object ScheduleStore {
    private var items: MutableList<ScheduleItem>? = null
    private val file get() = File(AidyApplication.context.filesDir, "schedule.json")

    /** その日の予定 (終日 → 時刻順)。時刻を過ぎた予定は後ろに回し、次の予定が先頭に来るようにする。 */
    fun forDate(d: LocalDate): List<ScheduleItem> {
        val key = d.key()
        val now = LocalDateTime.now()
        return load().filter { it.date == key }.sortedWith { a, b ->
            val pa = a.isPast(now)
            val pb = b.isPast(now)
            when {
                pa != pb -> if (pa) 1 else -1
                a.allDay != b.allDay -> if (a.allDay) -1 else 1
                else -> a.time.compareTo(b.time)
            }
        }
    }

    /** id が空なら新規追加、既存 id なら上書き。 */
    fun save(item0: ScheduleItem) {
        val list = load()
        val item = if (item0.id.isEmpty()) item0.copy(id = UUID.randomUUID().toString().replace("-", "")) else item0
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list += item
        write()
    }

    fun delete(id: String) {
        if (load().removeAll { it.id == id }) write()
    }

    /** "9:5" "0930" "9" などを "HH:mm" に揃える。空なら ""(終日)、読めなければ null。 */
    fun parseTime(s0: String?): String? {
        val s = (s0 ?: "").trim().replace("：", ":")
        if (s.isEmpty()) return ""
        var h: Int
        var m = 0
        if (s.contains(":")) {
            val p = s.split(':')
            if (p.size != 2) return null
            h = p[0].toIntOrNull() ?: return null
            if (p[1].isNotEmpty()) m = p[1].toIntOrNull() ?: return null
        } else {
            h = s.toIntOrNull() ?: return null
            if (s.length >= 3) {
                m = h % 100
                h /= 100
            }
        }
        if (h !in 0..23 || m !in 0..59) return null
        return "%02d:%02d".format(h, m)
    }

    private fun load(): MutableList<ScheduleItem> {
        items?.let { return it }
        val list = mutableListOf<ScheduleItem>()
        items = list
        if (!file.exists()) return list
        try {
            val arr = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = ScheduleItem.fromJson(arr.getJSONObject(i))
                if (it.date.isNotEmpty()) list += it
            }
        } catch (ex: Exception) {
            Log.w("AIDY", "予定の読込に失敗: ${ex.message}")
        }
        return list
    }

    private fun write() {
        try {
            val arr = JSONArray().apply { load().forEach { put(it.toJson()) } }
            file.writeText(JSONObject().put("items", arr).toString(2), Charsets.UTF_8)
        } catch (ex: IOException) {
            Log.w("AIDY", "予定の保存に失敗: ${ex.message}")
        }
    }
}

/** 本体連携が完成するまでカレンダーの見た目を確認するためのサンプル日記 (「サンプル」表示付き)。 */
object DiaryDemo {
    /** 本体からの同期が動くようになったら false にする。 */
    var enabled = true
    private const val SeededKey = "aidy.diary.demoSeeded"

    private val Conversations = listOf(
        listOf(
            listOf("07:42", "user", "おはよう、Aidy"),
            listOf("07:42", "aidy", "おはよう！今日はいい天気だよ"),
            listOf("12:15", "user", "お昼のパスタがすごくおいしかった"),
            listOf("12:16", "aidy", "いいなあ、何味だったの？"),
            listOf("12:16", "user", "トマトとバジル。また作りたい"),
            listOf("21:30", "user", "今日は楽しかった、ありがとう"),
            listOf("21:30", "aidy", "ぼくも楽しかったよ。おやすみ！"),
        ),
        listOf(
            listOf("08:05", "user", "今日は朝から会議が3つある"),
            listOf("08:05", "aidy", "がんばってね！応援してるよ"),
            listOf("19:48", "user", "ただいま。さすがに疲れた"),
            listOf("19:48", "aidy", "おかえり！おつかれさま"),
            listOf("22:10", "user", "もう眠いから寝るね"),
            listOf("22:10", "aidy", "ゆっくり休んでね。おやすみ"),
        ),
        listOf(
            listOf("10:20", "user", "Aidy、今日の予定を教えて"),
            listOf("10:20", "aidy", "15時に歯医者さんの予約があるよ"),
            listOf("16:02", "user", "歯医者おわった、意外と平気だった"),
            listOf("16:02", "aidy", "えらい！ちゃんと行けたね"),
            listOf("20:40", "user", "明日は友だちと出かける予定"),
            listOf("20:41", "aidy", "どこに行くの？楽しみだね"),
        ),
        listOf(
            listOf("09:12", "user", "週末だからのんびりする"),
            listOf("09:12", "aidy", "いいね、ぼくもいっしょにのんびりする"),
            listOf("14:30", "user", "新しい本を読み始めた。面白い"),
            listOf("14:31", "aidy", "どんなお話？あとで教えてね"),
            listOf("18:55", "user", "夕焼けがきれいだった"),
            listOf("18:55", "aidy", "見たかったなあ。写真ある？"),
        ),
    )

    private val DaysAgo = intArrayOf(1, 2, 4, 5, 8, 11, 12, 15, 19, 23, 27, 33, 38)

    fun seedIfNeeded() {
        if (!enabled || Prefs.getInt(SeededKey, 0) == 1) return
        for (i in DaysAgo.indices) {
            val logs = Conversations[i % Conversations.size].map { ChatLine(it[0], it[1], it[2]) }
            val e = DiaryComposer.compose(LocalDate.now().minusDays(DaysAgo[i].toLong()).key(), logs)
            e.source = "demo"
            DiaryStore.save(e)
        }
        Prefs.putInt(SeededKey, 1)
    }
}
