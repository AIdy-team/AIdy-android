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
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
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
 * Wi-Fi 経由で Aidy 本体 (http://{host}) と話す。口の形は AIdy_アプリ連携_仕様.md のとおり。
 * すべての通信に合言葉 (ヘッダー X-Aidy-Token) が必要。401・403 はどちらも「合言葉が違います」として扱う。
 * まだ本体にない口 (設定・ようす・予定の書き込み・話しかけ) は
 * 設定画面の「今後の開発予定」に載せるだけで、ここには実装しない。
 */
object AidyDeviceLink {
    private const val HostKey = "aidy.device.host"
    private const val TokenKey = "aidy.device.token"
    private const val LastSyncKey = "aidy.device.lastSync"
    private const val TimeoutMs = 4000

    /** 本体のアドレス ("192.168.1.23:8090" など、QR の host の部分)。未設定なら空文字。 */
    var host: String
        get() = Prefs.getString(HostKey, "")
        set(value) = Prefs.putString(HostKey, value.trim())

    /** 合言葉 (QR の "t=" の後ろ)。未設定なら空文字。画面には伏せ字でしか出さないこと。 */
    var token: String
        get() = Prefs.getString(TokenKey, "")
        set(value) = Prefs.putString(TokenKey, value.trim())

    /** 直前に /api/ping が答えた展示モードかどうか。 */
    var exhibit: Boolean = false
        private set

    val isConfigured get() = host.isNotEmpty() && token.isNotEmpty()

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

    private fun open(path: String): HttpURLConnection {
        val conn = URL("http://$host$path").openConnection() as HttpURLConnection
        conn.connectTimeout = TimeoutMs
        conn.readTimeout = TimeoutMs
        conn.setRequestProperty("X-Aidy-Token", token)
        return conn
    }

    private fun openPost(path: String, body: JSONObject): HttpURLConnection {
        val conn = URL("http://$host$path").openConnection() as HttpURLConnection
        conn.connectTimeout = TimeoutMs
        conn.readTimeout = TimeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("X-Aidy-Token", token)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return conn
    }

    /** 接続エラー・401・403 の言い回しをアプリ全体でそろえる。 */
    private fun errorMessage(code: Int?) = when {
        code == 401 || code == 403 -> "合言葉が違います。本体のQRを読み直してください"
        code == null -> "Aidy本体につながりません ($host)"
        else -> "本体でエラーが起きました ($code)"
    }

    /** GET /api/ping。設定画面の「保存して接続を試す」で使う。 */
    suspend fun ping(): Result = withContext(Dispatchers.IO) {
        if (host.isEmpty() || token.isEmpty()) return@withContext Result(false, "住所と合言葉を入れてください")
        try {
            val conn = open("/api/ping")
            try {
                val code = conn.responseCode
                if (code != 200) return@withContext Result(false, errorMessage(code))
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                exhibit = JSONObject(body).optBoolean("exhibit", false)
                Result(true, "Aidy本体につながりました")
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Result(false, errorMessage(null))
        }
    }

    /** 今日から days 日分さかのぼってログを取得し、日記を作り直して保存する。 */
    suspend fun syncRecent(days: Int): Result = withContext(Dispatchers.IO) {
        if (host.isEmpty() || token.isEmpty()) return@withContext Result(false, "本体と接続していません (設定 > Aidy本体と接続)")

        var updated = 0
        for (i in 0 until days) {
            val date = LocalDate.now().minusDays(i.toLong()).key()
            val body: String
            try {
                val conn = open("/api/logs?date=$date")
                try {
                    val code = conn.responseCode
                    if (code == 401 || code == 403) return@withContext Result(false, errorMessage(code), updated)
                    if (code != 200) continue // 404 等 = その日のログなし
                    body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                return@withContext Result(false, errorMessage(null), updated)
            }

            val entry = try {
                val o = JSONObject(body)
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
        Result(true, if (updated > 0) "${updated}日分の日記を更新しました" else "新しい会話ログはありませんでした", updated)
    }

    /**
     * 本体の QR の中身 ("http://192.168.1.23:8090/?t=AbCdEf123456") から住所と合言葉を取り出す。
     * 読めなければ null。
     */
    fun parseQr(raw: String): Pair<String, String>? {
        val text = raw.trim()
        return try {
            val uri = URI(text)
            val h = uri.authority ?: return null
            val t = (uri.query ?: return null).split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "t" }
                ?.get(1) ?: return null
            if (h.isEmpty() || t.isEmpty()) null else h to t
        } catch (e: URISyntaxException) {
            null
        }
    }

    /** GET /api/week (読むだけ)。今日から7日分の予定・やることを取得する。 */
    suspend fun fetchWeek(): WeekResult = withContext(Dispatchers.IO) {
        if (host.isEmpty() || token.isEmpty()) return@withContext WeekResult(false, emptyList(), 0, "本体と接続していません")
        try {
            val conn = open("/api/week")
            try {
                val code = conn.responseCode
                if (code != 200) return@withContext WeekResult(false, emptyList(), 0, errorMessage(code))
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val o = JSONObject(body)
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
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            WeekResult(false, emptyList(), 0, errorMessage(null))
        } catch (e: Exception) {
            Log.w("AIDY", "week の解析に失敗: ${e.message}")
            WeekResult(false, emptyList(), 0, "本体からの返事を読み取れませんでした")
        }
    }

    data class DaysResult(val ok: Boolean, val days: Set<String>, val error: String?)

    /** GET /api/days。会話・記録がある日の一覧 ("yyyyMMdd") を "yyyy-MM-dd" に揃えて返す。 */
    suspend fun fetchDays(): DaysResult = withContext(Dispatchers.IO) {
        if (host.isEmpty() || token.isEmpty()) return@withContext DaysResult(false, emptySet(), "本体と接続していません")
        try {
            val conn = open("/api/days")
            try {
                val code = conn.responseCode
                if (code != 200) return@withContext DaysResult(false, emptySet(), errorMessage(code))
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONObject(body).optJSONArray("days") ?: JSONArray()
                val days = (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optString(i)
                    if (s.length == 8) "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}" else null
                }.toSet()
                DaysResult(true, days, null)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            DaysResult(false, emptySet(), errorMessage(null))
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
    suspend fun fetchDict(): DictResult = withContext(Dispatchers.IO) {
        if (host.isEmpty() || token.isEmpty()) return@withContext DictResult(false, null, "本体と接続していません")
        try {
            val conn = open("/api/dict")
            try {
                val code = conn.responseCode
                if (code != 200) return@withContext DictResult(false, null, errorMessage(code))
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                DictResult(true, parseDict(JSONObject(body)), null)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            DictResult(false, null, errorMessage(null))
        } catch (e: Exception) {
            Log.w("AIDY", "dict の解析に失敗: ${e.message}")
            DictResult(false, null, "本体からの返事を読み取れませんでした")
        }
    }

    /** POST /api/add。kind は "faq" ・ "asr" ・ "yomi"。成功なら更新後の辞書 (dict) が返る。 */
    suspend fun addDictEntry(kind: String, a: String, b: String): DictResult = withContext(Dispatchers.IO) {
        if (host.isEmpty() || token.isEmpty()) return@withContext DictResult(false, null, "本体と接続していません")
        postDict("/api/add", JSONObject().put("kind", kind).put("a", a).put("b", b))
    }

    /** POST /api/del ("asr" ・ "yomi" は key、"faq" は idx)。成功なら更新後の辞書 (dict) が返る。 */
    suspend fun deleteDictEntry(kind: String, key: String? = null, idx: Int? = null): DictResult = withContext(Dispatchers.IO) {
        if (host.isEmpty() || token.isEmpty()) return@withContext DictResult(false, null, "本体と接続していません")
        val body = JSONObject().put("kind", kind)
        if (key != null) body.put("key", key)
        if (idx != null) body.put("idx", idx)
        postDict("/api/del", body)
    }

    private fun postDict(path: String, body: JSONObject): DictResult {
        return try {
            val conn = openPost(path, body)
            try {
                val code = conn.responseCode
                if (code != 200) return DictResult(false, null, errorMessage(code))
                val res = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                if (!res.optBoolean("ok", false)) return DictResult(false, null, res.optString("error", "できませんでした"))
                val dictObj = res.optJSONObject("dict")
                DictResult(true, dictObj?.let { parseDict(it) }, null)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            DictResult(false, null, errorMessage(null))
        } catch (e: Exception) {
            Log.w("AIDY", "辞書の更新に失敗: ${e.message}")
            DictResult(false, null, "本体からの返事を読み取れませんでした")
        }
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
