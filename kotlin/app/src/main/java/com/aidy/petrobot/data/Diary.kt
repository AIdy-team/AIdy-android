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
 * Wi-Fi 経由で Aidy 本体から会話ログを取得する。
 * 本体側 API: GET http://{host}/api/logs?date=yyyy-MM-dd
 *   200 → {"date":"2026-10-23","logs":[{"time":"08:12","speaker":"user","text":"おはよう"}, ...],
 *          "title":"(任意)","summary":"(任意)","mood":"(任意: happy/normal/tired)"}
 *   404 → その日のログなし
 */
object AidyDeviceLink {
    const val DefaultHost = "aidy.local"
    private const val HostKey = "aidy.device.host"
    private const val LastSyncKey = "aidy.device.lastSync"
    private const val TimeoutMs = 4000

    var host: String
        get() = Prefs.getString(HostKey, DefaultHost)
        set(value) = Prefs.putString(HostKey, value)

    val lastSyncLabel: String
        get() {
            val t = Prefs.getString(LastSyncKey, "")
            return if (t.isEmpty()) "まだ本体と同期していません" else "最終同期: $t"
        }

    data class Result(val ok: Boolean, val message: String, val updated: Int)

    /** 今日から days 日分さかのぼってログを取得し、日記を作り直して保存する。 */
    suspend fun syncRecent(days: Int): Result = withContext(Dispatchers.IO) {
        var updated = 0
        for (i in 0 until days) {
            val date = LocalDate.now().minusDays(i.toLong()).key()
            val body: String
            try {
                val conn = URL("http://$host/api/logs?date=$date").openConnection() as HttpURLConnection
                conn.connectTimeout = TimeoutMs
                conn.readTimeout = TimeoutMs
                try {
                    if (conn.responseCode != 200) continue // 404 等 = その日のログなし
                    body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                return@withContext Result(false, "Aidy本体に接続できませんでした ($host)", updated)
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
