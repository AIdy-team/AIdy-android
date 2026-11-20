package com.aidy.petrobot.data

import android.Manifest
import android.content.ContentUris
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentValues
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.aidy.petrobot.AidyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * スマホのカレンダー (Google カレンダーなど、端末のカレンダーに入っている予定) と、このアプリの予定をつなぐ。
 *  - 読む: スマホの予定を、このアプリのカレンダーに一緒に表示する (このアプリには保存しない)。
 *  - 書く: このアプリで足した・直した・消した予定を、スマホのカレンダーにも反映する。
 * 使うには 設定 > 日記 > 「スマホのカレンダーと結合」を ON にし、カレンダーの許可 (読む・書く) が要る。
 */
object PhoneCalendar {
    private const val EnabledKey = "aidy.calendar.phoneMerge"
    private const val JulianEpochOffset = 2440588L // ユリウス日 → 1970-01-01 からの日数
    private const val TimedMinutes = 60L           // 時刻つきの予定の長さ (このアプリには終わりの時刻が無いので 1 時間)

    val Permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    /** 設定のスイッチ (ユーザーが ON にしたか)。 */
    var switchOn: Boolean
        get() = Prefs.getInt(EnabledKey, 0) == 1
        set(value) = Prefs.putInt(EnabledKey, if (value) 1 else 0)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(AidyApplication.context, p) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(): Boolean = granted(Manifest.permission.READ_CALENDAR)
    fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)

    /** 実際に結合するか (スイッチ ON かつ許可あり)。許可を後から取り消されたら自動で結合しない。 */
    val active get() = switchOn && hasPermission()
    private val writeActive get() = active && canWrite()

    // ------------------------------------------------------------------ 読む

    /** from〜to (両端含む) の予定を日付ごとに。結合しないときは空。 */
    suspend fun load(from: LocalDate, to: LocalDate): Map<String, List<ScheduleItem>> = withContext(Dispatchers.IO) {
        if (!active) return@withContext emptyMap()
        val zone = ZoneId.systemDefault()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.START_DAY,
            CalendarContract.Instances.END_DAY,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val hidden = ids(HiddenKey) ?: emptySet()
        // STATUS が空 (NULL) の予定も多い (祝日・Google の予定など)。"!= 取り消し" だけだと NULL が全部落ちるので IS NULL も入れる
        val where = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})"
        val map = HashMap<String, MutableList<ScheduleItem>>()
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        try {
            AidyApplication.context.contentResolver.query(uri, cols, where, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(8) in hidden) continue
                    val eventId = c.getLong(0)
                    val beginMs = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    val startDay = LocalDate.ofEpochDay(c.getLong(3) - JulianEpochOffset)
                    val endDay0 = LocalDate.ofEpochDay(c.getLong(4) - JulianEpochOffset)
                    val endDay = if (endDay0 < startDay) startDay else endDay0
                    val title = c.getString(5)?.trim().orEmpty().ifEmpty { "(タイトルなし)" }
                    val location = c.getString(6)?.trim().orEmpty()
                    val calName = displayName(c.getString(7)?.trim().orEmpty())
                    val startTime = if (allDay) "" else Instant.ofEpochMilli(beginMs).atZone(zone).format(hm)
                    // 何日にもまたがる予定は、その日ごとに出す (2 日目からは終日扱い)
                    var d = if (startDay < from) from else startDay
                    val last = if (endDay > to) to else endDay
                    while (d <= last) {
                        val key = d.key()
                        map.getOrPut(key) { mutableListOf() } += ScheduleItem(
                            id = "phone:$eventId:$beginMs:$key",
                            date = key,
                            time = if (d == startDay) startTime else "",
                            title = title,
                            memo = location,
                            source = "phone",
                            calendar = calName,
                            phoneEventId = eventId,
                        )
                        d = d.plusDays(1)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w("AIDY", "カレンダーの許可がない: ${e.message}")
        } catch (e: Exception) {
            Log.w("AIDY", "スマホのカレンダーを読めませんでした: ${e.message}")
        }
        Log.i("AIDY", "スマホのカレンダー: ${map.values.sumOf { it.size }} 件 ($from〜$to)")
        map
    }

    // ------------------------------------------------------------------ カレンダーの一覧と選択

    /** メールアドレスの名前 (Google のメインのカレンダーなど) は出さず「マイカレンダー」にする。 */
    fun displayName(raw: String): String = if (raw.isEmpty() || raw.contains('@')) "マイカレンダー" else raw

    /** スマホに入っているカレンダー 1 つ (Google・端末・Exchange など)。 */
    data class Cal(
        val id: Long, val name: String, val account: String, val accountType: String,
        val color: Int, val writable: Boolean, val primary: Boolean,
    ) {
        /** 画面に出す名前 (メールアドレスは出さない)。 */
        val label: String get() = displayName(name)

        /** 画面に出す種類 (「Google」「この端末」など)。 */
        val kind: String
            get() = when {
                accountType == "com.google" -> "Google"
                accountType == CalendarContract.ACCOUNT_TYPE_LOCAL -> "この端末"
                accountType.contains("exchange", true) || accountType.contains("outlook", true) -> "Outlook / Exchange"
                accountType.contains("samsung", true) -> "Samsung"
                else -> accountType.substringAfterLast('.')
            }
    }

    private const val HiddenKey = "aidy.calendar.hiddenIds" // 表示しないカレンダー
    private const val WriteKey = "aidy.calendar.writeIds"   // 予定を入れるカレンダー (未設定ならメインの 1 つ)
    private const val Unset = "-"

    private fun ids(key: String): Set<Long>? {
        val raw = Prefs.getString(key, Unset)
        if (raw == Unset) return null
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    private fun putIds(key: String, v: Set<Long>) = Prefs.putString(key, v.joinToString(","))

    /** スマホのカレンダーの一覧 (表示中のもの)。読む許可が無ければ空。 */
    suspend fun calendars(): List<Cal> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val cols = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val out = mutableListOf<Cal>()
        try {
            AidyApplication.context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, cols, "${CalendarContract.Calendars.VISIBLE} = 1", null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Cal(
                        c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                        c.getInt(4), c.getInt(5) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, c.getInt(6) == 1,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("AIDY", "カレンダーの一覧を読めませんでした: ${e.message}")
        }
        out
    }

    fun isShown(calId: Long) = calId !in (ids(HiddenKey) ?: emptySet())

    fun setShown(calId: Long, on: Boolean) {
        val h = (ids(HiddenKey) ?: emptySet()).toMutableSet()
        if (on) h -= calId else h += calId
        putIds(HiddenKey, h)
    }

    /** 予定を入れるカレンダー。まだ選んでいなければ、メイン (無ければ書き込める最初の 1 つ)。 */
    fun writeTargets(cals: List<Cal>): Set<Long> {
        val chosen = ids(WriteKey)
        val writable = cals.filter { it.writable }
        if (chosen != null) return chosen.filter { id -> writable.any { it.id == id } }.toSet()
        val def = writable.firstOrNull { it.primary } ?: writable.firstOrNull()
        return setOfNotNull(def?.id)
    }

    fun setWriteTarget(cals: List<Cal>, calId: Long, on: Boolean) {
        val t = writeTargets(cals).toMutableSet()
        if (on) t += calId else t -= calId
        putIds(WriteKey, t)
    }

    /**
     * Android にカレンダーの同期を頼む。Google カレンダーは、ここで書いた予定を同期のときに Google に送り、
     * Google カレンダーのアプリで作った予定も同期のときにここ (他のアプリが読める場所) に降ろす。
     * 同期は Android が順番に行うので、反映まで少し (数秒〜数分) かかる。
     */
    fun requestSync(accounts: Collection<Pair<String, String>>) {
        for ((name, type) in accounts) {
            if (name.isEmpty() || type.isEmpty() || type == CalendarContract.ACCOUNT_TYPE_LOCAL) continue
            try {
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras)
            } catch (e: Exception) {
                Log.w("AIDY", "同期を頼めませんでした: ${e.message}")
            }
        }
    }

    private var lastPullMs = 0L

    /** 読む前に、ときどき (1 分に 1 回まで) 同期を頼む。Google カレンダーのアプリで足した予定を取り込むため。 */
    suspend fun pullSync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!active || (!force && now - lastPullMs < 60_000)) return
        lastPullMs = now
        requestSync(calendars().filter { isShown(it.id) }.map { it.account to it.accountType }.toSet())
    }

    // ------------------------------------------------------------------ 書く

    private fun values(item: ScheduleItem, calendarId: Long?): ContentValues {
        val date = LocalDate.parse(item.date)
        val v = ContentValues()
        if (calendarId != null) v.put(CalendarContract.Events.CALENDAR_ID, calendarId)
        v.put(CalendarContract.Events.TITLE, item.title)
        v.put(CalendarContract.Events.DESCRIPTION, item.memo)
        if (item.allDay) {
            // 終日の予定は UTC の 0 時〜翌 0 時 (Android の決まり)
            v.put(CalendarContract.Events.ALL_DAY, 1)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            v.put(CalendarContract.Events.DTSTART, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            v.put(CalendarContract.Events.DTEND, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        } else {
            val zone = ZoneId.systemDefault()
            val start = date.atTime(LocalTime.parse(item.time)).atZone(zone)
            v.put(CalendarContract.Events.ALL_DAY, 0)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            v.put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            v.put(CalendarContract.Events.DTEND, start.plusMinutes(TimedMinutes).toInstant().toEpochMilli())
        }
        return v
    }

    private fun startMs(item: ScheduleItem): Long {
        val date = LocalDate.parse(item.date)
        return if (item.allDay) date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        else date.atTime(LocalTime.parse(item.time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * そのカレンダーで、同じタイトル・同じ開始の予定を探す。
     * Google は同期のときに、アプリが書いた予定を別の番号で入れ直すことがあるので、番号が消えたらこれで探し直す。
     */
    private fun findSame(cal: Long, item: ScheduleItem): Long? {
        if (item.title.isEmpty() || item.date.isEmpty()) return null
        return AidyApplication.context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} = ? AND " +
                "${CalendarContract.Events.DTSTART} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(cal.toString(), item.title, startMs(item).toString()), "${CalendarContract.Events._ID} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** スマホのその予定がどのカレンダーにあるか (消されていたら null)。 */
    private fun eventCalendar(eventId: Long): Long? =
        AidyApplication.context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.DELETED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && c.getInt(1) == 0) c.getLong(0) else null }

    /**
     * このアプリの予定を、選んだカレンダーすべてに書く (無ければ足す、あれば直す)。
     * 返り値 = 新しい書き先 (カレンダー → 予定の番号)。結合が OFF・書く許可が無いときは null (変えない)。
     */
    suspend fun push(item: ScheduleItem, before: ScheduleItem? = null): Map<Long, Long>? = withContext(Dispatchers.IO) {
        if (!writeActive || item.fromPhone || item.date.isEmpty()) return@withContext null
        val cr = AidyApplication.context.contentResolver
        val cals = calendars()
        val targets = writeTargets(cals)
        val links = HashMap<Long, Long>()
        // いまある書き先を、実際のカレンダー番号に直す (古い形の 0 や、向こうで消された予定を整理)
        for ((cal0, ev) in item.phoneLinks) {
            val cal = eventCalendar(ev) ?: continue
            if (cal0 == 0L || cal0 == cal) links[cal] = ev
        }
        val touched = HashSet<Long>()
        try {
            for (cal in targets) {
                // 番号で見つからなければ、直す前 / 直した後の中身で同じ予定を探す (二重に入れない)
                val ev = links[cal] ?: (before?.let { findSame(cal, it) } ?: findSame(cal, item))?.also { links[cal] = it }
                if (ev != null) {
                    cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev), values(item, null), null, null)
                } else {
                    val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values(item, cal)) ?: continue
                    links[cal] = ContentUris.parseId(uri)
                }
                touched += cal
            }
        } catch (e: SecurityException) {
            Log.w("AIDY", "カレンダーに書く許可がない: ${e.message}")
        } catch (e: Exception) {
            Log.w("AIDY", "スマホのカレンダーに書けませんでした: ${e.message}")
        }
        requestSync(cals.filter { it.id in touched }.map { it.account to it.accountType }.toSet())
        links
    }

    /** このアプリで消した予定を、書いた先のカレンダーすべてからも消す。 */
    suspend fun remove(item: ScheduleItem) = withContext(Dispatchers.IO) {
        if (!writeActive) return@withContext
        val cals = calendars()
        val touched = HashSet<Long>()
        val evs = item.phoneLinks.values.filter { eventCalendar(it) != null }.toMutableSet()
        for (cal in writeTargets(cals)) findSame(cal, item)?.let { evs += it }
        for (ev in evs) {
            try {
                eventCalendar(ev)?.let { touched += it }
                AidyApplication.context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev), null, null,
                )
            } catch (e: Exception) {
                Log.w("AIDY", "スマホのカレンダーから消せませんでした: ${e.message}")
            }
        }
        requestSync(cals.filter { it.id in touched }.map { it.account to it.accountType }.toSet())
    }

    /** このアプリの予定を 1 件スマホに書いて、書き先を覚える。 */
    suspend fun pushAndLink(item: ScheduleItem, before: ScheduleItem? = null) {
        val links = push(item, before) ?: return
        withContext(Dispatchers.Main) { ScheduleStore.setPhoneLinks(item.id, links) }
    }

    /**
     * 選んだカレンダーにまだ入っていない、このアプリの予定をまとめて書く
     * (結合を ON にしたとき・書き込み先を足したとき)。書いた件数を返す。
     */
    suspend fun pushAll(): Int {
        if (!writeActive) return 0
        val targets = writeTargets(calendars())
        var n = 0
        for (item in ScheduleStore.all()) {
            if (targets.all { it in item.phoneLinks.keys }) continue
            val links = push(item) ?: continue
            withContext(Dispatchers.Main) { ScheduleStore.setPhoneLinks(item.id, links) }
            n++
        }
        return n
    }
}
