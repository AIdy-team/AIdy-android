package com.aidy.petrobot.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 本体との「会話・表情・モジュール・予定の知らせ」の窓口 (docs/本体アプリ連携_会話表情予定モジュール_仕様.txt)。
 *
 *  アプリ → 本体
 *    POST /api/app/chat       {"cid","text","via"}            スマホで話した / 打った言葉
 *    POST /api/app/reminders  {"now","tz","items":[...]}      これから 24 時間の予定 (本体が時間になったら声で知らせる)
 *    GET  /api/ports                                          モジュールの差し込み口 (11/20 から本体にある)
 *  本体 → アプリ
 *    GET  /api/app/events?since=N  → {"seq", "events":[...]}  アプリが 2 秒ごとに取りに行く (Wi-Fi・BLE どちらでも)
 *    BLE のときは {"ev": {...}} の 1 行を TX で通知してもよい (すぐ届く)。同じ seq は 2 回処理しない
 *
 *  知らせの種類 (type): say (Aidy の返事・ひとりごと) / face (表情) / hear (本体のマイクで聞いた言葉) /
 *                       module (モジュールの抜き差し) / reminder (予定を知らせた)
 */
object BodyBridge {
    /** 会話の 1 行。who = "me" (スマホ) / "aidy" / "body" (本体のマイクで聞いた人の言葉) / "sys" (アプリからのお知らせ)。 */
    data class Msg(val id: Long, val who: String, val text: String, val at: Long = System.currentTimeMillis(), val via: String = "")

    /** モジュールの差し込み口 1 つ (GET /api/ports の ports の中身)。 */
    data class Port(val port: Int, val place: String, val name: String, val kind: String, val cls: String, val state: String)

    data class Ports(val ok: Boolean, val ports: List<Port>, val watts: Double, val budget: Double, val at: Long)

    private const val MaxMsgs = 100
    private const val PollFastMs = 2000L
    private const val PollSlowMs = 6000L
    private const val PollUnsupportedMs = 30_000L
    private const val ReminderEveryMs = 10 * 60_000L
    private const val ReminderHours = 24L

    val messages = mutableStateListOf<Msg>()
    /** Aidy の吹き出しに出す言葉 (null で消す) と、出した時刻。 */
    var bubble by mutableStateOf<String?>(null)
    var bubbleAt by mutableStateOf(0L)
    /** 返事を待っている (吹き出しに「…」)。 */
    var waiting by mutableStateOf(false)
    /** 本体の表情の名前 (faces.py と同じ: normal・happy・sad・angry・sleepy・sleep・dazed・scared・listen・speak…)。 */
    var face by mutableStateOf<String?>(null)
    var faceVersion by mutableIntStateOf(0)
    var ports by mutableStateOf<Ports?>(null)
    /** モジュールの抜き差しの知らせ (ホームで一言出す)。 */
    var moduleNote by mutableStateOf<String?>(null)
    var moduleNoteVersion by mutableIntStateOf(0)
    /** 本体がこの窓口に対応しているか (404 なら false)。null = まだ分からない。 */
    var supported by mutableStateOf<Boolean?>(null)
    var lastError by mutableStateOf<String?>(null)
    /** 顔の登録・Aidy の名前が本体で変わった (知らせ faces / aidy)。 */
    var facesVersion by mutableIntStateOf(0)

    private var seq = -1L
    private var nextId = 1L
    private var lastReminderSync = 0L
    private var reminderDirty = true
    private val main = Handler(Looper.getMainLooper())

    init {
        BleLink.onEvent = { ev -> main.post { handle(ev) } }
    }

    private fun add(who: String, text: String, via: String = "") {
        messages += Msg(nextId++, who, text, via = via)
        while (messages.size > MaxMsgs) messages.removeAt(0)
    }

    private fun say(text: String) {
        bubble = text
        bubbleAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ 会話 (アプリ → 本体)

    /** スマホで話した / 打った言葉を本体に送る。via = "text" / "voice"。返事は知らせ (say) で届く。 */
    suspend fun sendChat(text0: String, via: String) {
        val text = text0.trim().take(200)
        if (text.isEmpty()) return
        add("me", text, via)
        if (!AidyDeviceLink.isConfigured) {
            add("sys", "本体とつながっていません。設定で本体のQRを読み取ってね")
            return
        }
        val cid = "c" + System.currentTimeMillis()
        waiting = true
        try {
            val r = AidyDeviceLink.call("POST", "/api/app/chat", JSONObject().put("cid", cid).put("text", text).put("via", via))
            when (r.code) {
                200 -> {
                    supported = true
                    // 本体がその場で返事を返す作りでもよい ({"reply": "...", "face": "happy"})
                    val o = runCatching { r.json() }.getOrNull()
                    val reply = o?.optString("reply").orEmpty()
                    if (reply.isNotEmpty()) handle(JSONObject().put("type", "say").put("text", reply).put("face", o?.optString("face")).put("cid", cid))
                    else waitReply() // 返事は events で来る。waiting は say が来るか時間切れで消す
                }
                404 -> { supported = false; waiting = false; add("sys", "本体がまだスマホとの会話に対応していません (本体を新しくしてね)") }
                else -> { waiting = false; add("sys", AidyDeviceLink.errorMessage(r.code)) }
            }
        } catch (e: Exception) {
            waiting = false
            add("sys", AidyDeviceLink.errorMessage(null, e))
        }
    }

    /** 返事 (say) が来るまで最大 30 秒、早めに知らせを取りに行く。 */
    private suspend fun waitReply() {
        val until = System.currentTimeMillis() + 30_000
        while (waiting && System.currentTimeMillis() < until) {
            delay(700)
            runCatching { pollOnce() }
        }
        if (waiting) {
            waiting = false
            add("sys", "Aidy から返事が来ませんでした")
        }
    }

    // ------------------------------------------------------------------ 知らせ (本体 → アプリ)

    /** GET /api/app/events を 1 回。 */
    suspend fun pollOnce() {
        val r = AidyDeviceLink.call("GET", "/api/app/events?since=$seq")
        when (r.code) {
            200 -> {
                supported = true
                lastError = null
                val o = r.json()
                val arr = o.optJSONArray("events") ?: JSONArray()
                val first = seq < 0
                for (i in 0 until arr.length()) {
                    val ev = arr.optJSONObject(i) ?: continue
                    // 初回は古い会話を吹き出しに出さない (表情・モジュールだけ合わせる)
                    if (first && ev.optString("type") == "say") continue
                    handle(ev)
                }
                val s = o.optLong("seq", seq)
                if (s >= 0) seq = s
            }
            404 -> supported = false
            else -> lastError = AidyDeviceLink.errorMessage(r.code)
        }
    }

    /** 知らせ 1 件を画面に反映する (メインスレッドで呼ぶ)。 */
    fun handle(ev: JSONObject) {
        val s = ev.optLong("seq", -1)
        if (s >= 0) {
            if (s <= seq && seq >= 0 && ev.has("seq")) return // もう処理した
            if (s > seq) seq = s
        }
        when (ev.optString("type")) {
            "say" -> {
                val text = ev.optString("text").trim()
                if (text.isNotEmpty()) {
                    add("aidy", text, ev.optString("via"))
                    say(text)
                }
                ev.optString("face").takeIf { it.isNotEmpty() && it != "null" }?.let { applyFace(it) }
                waiting = false
            }
            "face" -> ev.optString("face").takeIf { it.isNotEmpty() }?.let { applyFace(it) }
            "hear" -> ev.optString("text").trim().takeIf { it.isNotEmpty() }?.let { add("body", it) }
            "module" -> {
                val name = ev.optString("name").ifEmpty { ev.optString("kind") }.ifEmpty { "モジュール" }
                val place = ev.optString("place").ifEmpty { "P" + ev.optInt("port") }
                moduleNote = when (ev.optString("what")) {
                    "attach", "on", "ok" -> "${place}に${name}がつながったよ"
                    "detach", "off" -> "${place}の${name}が外れたよ"
                    "unknown" -> "${place}に知らないモジュールがつながったよ"
                    "power" -> "電気が足りないみたい (${name})"
                    "error", "short" -> "${place}の様子がおかしいよ。すぐ外してね"
                    else -> "${place}: ${name} (${ev.optString("what")})"
                }
                moduleNoteVersion++
                ev.optJSONObject("ports")?.let { ports = parsePorts(it) } ?: run { portsDirty = true }
            }
            "faces" -> facesVersion++ // 顔の登録が増えた・変わった・消えた → 一覧を読み直す
            "aidy" -> facesVersion++  // Aidy の名前が変わった
            "reminder" -> {
                val title = ev.optString("title")
                if (title.isNotEmpty()) add("sys", "本体が予定「$title」を知らせました")
            }
        }
    }

    private fun applyFace(name: String) {
        face = name
        faceVersion++
    }

    // ------------------------------------------------------------------ モジュール

    private var portsDirty = true

    suspend fun fetchPorts() {
        val r = AidyDeviceLink.call("GET", "/api/ports")
        if (r.code == 200) ports = parsePorts(r.json())
        portsDirty = false
    }

    fun parsePorts(o: JSONObject): Ports {
        val list = ArrayList<Port>()
        val p = o.optJSONObject("ports")
        if (p != null) {
            for (k in p.keys()) {
                val m = p.optJSONObject(k) ?: continue
                list += Port(
                    k.toIntOrNull() ?: m.optInt("port"), m.optString("place").ifEmpty { "P$k" }, m.optString("name"),
                    m.optString("type").ifEmpty { m.optString("kind") }, m.optString("cls"), m.optString("state"),
                )
            }
        }
        return Ports(o.optBoolean("ok", false), list.sortedBy { it.port }, o.optDouble("watts", 0.0), o.optDouble("budget", 28.0), System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ 予定 (アプリ → 本体)

    /** 予定が変わったら呼ぶ (次の見回りで本体に送り直す)。 */
    fun markRemindersDirty() {
        reminderDirty = true
    }

    /** これから 24 時間の予定 (このアプリの予定 + 結合しているスマホのカレンダー) を本体に送る。 */
    suspend fun sendReminders(): Boolean {
        val now = LocalDateTime.now()
        val end = now.plusHours(ReminderHours)
        val zone = ZoneId.systemDefault()
        val items = JSONArray()
        val app = ScheduleStore.all().filter { it.date >= now.toLocalDate().toString() && it.date <= end.toLocalDate().toString() }
        val phone = runCatching { PhoneCalendar.load(now.toLocalDate(), end.toLocalDate()).values.flatten() }.getOrDefault(emptyList())
        val seen = HashSet<Triple<String, String, String>>()
        for (it in app + phone) {
            if (!seen.add(Triple(it.date, it.time, it.title))) continue // 同じ予定は 1 回だけ
            val date = runCatching { LocalDate.parse(it.date) }.getOrNull() ?: continue
            val start = if (it.allDay) date.atStartOfDay() else date.atTime(runCatching { LocalTime.parse(it.time) }.getOrNull() ?: continue)
            if (!it.allDay && (start.isBefore(now.minusMinutes(5)) || start.isAfter(end))) continue
            items.put(
                JSONObject()
                    .put("sid", if (it.fromPhone) "p${it.phoneEventId}@${it.date}" else "a${it.id}")
                    .put("date", it.date).put("time", it.time).put("all_day", it.allDay)
                    .put("start", start.atZone(zone).toEpochSecond())
                    .put("title", it.title.take(60)).put("memo", it.memo.take(120))
                    .put("source", if (it.fromPhone) "phone" else "app")
                    .put("lead", JSONArray(if (it.allDay) listOf<Int>() else listOf(10, 0))),
            )
        }
        val body = JSONObject().put("now", System.currentTimeMillis() / 1000).put("tz", zone.id).put("hours", ReminderHours).put("items", items)
        val r = AidyDeviceLink.call("POST", "/api/app/reminders", body)
        if (r.code == 200) {
            reminderDirty = false
            lastReminderSync = System.currentTimeMillis()
        }
        return r.code == 200
    }

    // ------------------------------------------------------------------ 見回り

    /**
     * アプリが前に出ている間ずっと回す。fast = ホーム画面が見えている (表情・返事をすぐ出す)。
     * 本体が未対応 (404) なら 30 秒おきにだけ確かめる。
     */
    suspend fun loop(fast: () -> Boolean) {
        while (true) {
            if (!AidyDeviceLink.isConfigured) {
                delay(PollSlowMs)
                continue
            }
            try {
                if (supported != false || seq < 0) pollOnce()
                if (portsDirty) runCatching { fetchPorts() }
                val now = System.currentTimeMillis()
                if (supported != false && (reminderDirty || now - lastReminderSync > ReminderEveryMs)) runCatching { sendReminders() }
            } catch (e: Exception) {
                lastError = AidyDeviceLink.errorMessage(null, e)
            }
            delay(
                when {
                    supported == false -> PollUnsupportedMs
                    lastError != null -> PollSlowMs * 2
                    fast() -> PollFastMs
                    else -> PollSlowMs
                },
            )
        }
    }
}
