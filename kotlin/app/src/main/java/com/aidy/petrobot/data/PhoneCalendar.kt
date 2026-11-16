package com.aidy.petrobot.data

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.aidy.petrobot.AidyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * スマホの別のアプリのカレンダー (Google カレンダーなど、端末のカレンダーに入っている予定) を読む。
 * 読むだけで、書き込み・このアプリへの保存はしない (表示のときにこのアプリの予定と結合する)。
 * 使うには 設定 > 日記 > 「スマホのカレンダーと結合」を ON にし、カレンダーの許可 (READ_CALENDAR) が要る。
 */
object PhoneCalendar {
    private const val EnabledKey = "aidy.calendar.phoneMerge"
    private const val JulianEpochOffset = 2440588L // ユリウス日 → 1970-01-01 からの日数

    /** 設定のスイッチ (ユーザーが ON にしたか)。 */
    var switchOn: Boolean
        get() = Prefs.getInt(EnabledKey, 0) == 1
        set(value) = Prefs.putInt(EnabledKey, if (value) 1 else 0)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(AidyApplication.context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 実際に結合するか (スイッチ ON かつ許可あり)。許可を後から取り消されたら自動で結合しない。 */
    val active get() = switchOn && hasPermission()

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
        )
        val map = HashMap<String, MutableList<ScheduleItem>>()
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        try {
            AidyApplication.context.contentResolver.query(
                uri, cols,
                "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}",
                null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val eventId = c.getLong(0)
                    val beginMs = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    val startDay = LocalDate.ofEpochDay(c.getLong(3) - JulianEpochOffset)
                    val endDay = LocalDate.ofEpochDay(c.getLong(4) - JulianEpochOffset)
                    val title = c.getString(5)?.trim().orEmpty().ifEmpty { "(タイトルなし)" }
                    val location = c.getString(6)?.trim().orEmpty()
                    val calName = c.getString(7)?.trim().orEmpty()
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
        map
    }
}
