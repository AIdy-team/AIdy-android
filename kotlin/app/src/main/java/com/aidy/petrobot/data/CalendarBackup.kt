package com.aidy.petrobot.data

import android.net.Uri
import com.aidy.petrobot.AidyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * このアプリのカレンダー (予定 + 日記) をファイルに書き出す / ファイルから読み込む。
 * 機種変更・バックアップ・別の端末へ渡す用。スマホの別アプリのカレンダーの予定は含めない。
 *
 * ファイル (JSON, UTF-8):
 *   {"format": "aidy-calendar", "version": 1, "exported": "2026-11-13T17:50",
 *    "schedule": [{"id","date","time","title","memo"}, ...],
 *    "diary":    [{"date","title","summary","mood","source","syncedAt","logs":[...]}, ...]}
 */
object CalendarBackup {
    const val Format = "aidy-calendar"
    const val MimeType = "application/json"

    data class Result(val ok: Boolean, val message: String)

    /** 保存ダイアログに出す既定のファイル名。 */
    fun defaultFileName(): String =
        "aidy_calendar_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".json"

    suspend fun export(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val schedule = ScheduleStore.all()
            // サンプル日記は書き出さない
            val diary = DiaryStore.all().filter { it.source != "demo" }
            val root = JSONObject()
                .put("format", Format)
                .put("version", 1)
                .put("exported", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
                .put("schedule", JSONArray().apply { schedule.forEach { put(it.toJson()) } })
                .put("diary", JSONArray().apply { diary.forEach { put(it.toJson()) } })
            val out = AidyApplication.context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext Result(false, "ファイルを書き出せませんでした")
            out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
            Result(true, "予定 ${schedule.size}件・日記 ${diary.size}日分を書き出しました")
        } catch (e: Exception) {
            Result(false, "ファイルを書き出せませんでした (${e.javaClass.simpleName})")
        }
    }

    /**
     * 読み込んで今の内容に混ぜる (消さない)。
     * 予定: 同じ id は上書き、同じ日付・時刻・タイトルは重複として飛ばす。
     * 日記: その日の日記が無い、またはサンプルなら入れる。両方あるときは会話ログが多い方を残す。
     */
    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val text = try {
            AidyApplication.context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext Result(false, "ファイルを開けませんでした")
        } catch (e: Exception) {
            return@withContext Result(false, "ファイルを開けませんでした (${e.javaClass.simpleName})")
        }
        val root = try {
            JSONObject(text.removePrefix("﻿"))
        } catch (e: Exception) {
            return@withContext Result(false, "このファイルは Aidy のカレンダーの書き出しではないみたい")
        }
        if (root.optString("format") != Format) return@withContext Result(false, "このファイルは Aidy のカレンダーの書き出しではないみたい")

        val sArr = root.optJSONArray("schedule") ?: JSONArray()
        val items = (0 until sArr.length()).mapNotNull { i -> sArr.optJSONObject(i)?.let { ScheduleItem.fromJson(it) } }
            .filter { runCatching { LocalDate.parse(it.date) }.isSuccess }
            .map { it.copy(time = ScheduleStore.parseTime(it.time) ?: "") }
        val (added, updated, _) = withContext(Dispatchers.Main) { ScheduleStore.importItems(items) }

        val dArr = root.optJSONArray("diary") ?: JSONArray()
        var diaryIn = 0
        for (i in 0 until dArr.length()) {
            val e = runCatching { DiaryEntry.fromJson(dArr.getJSONObject(i)) }.getOrNull() ?: continue
            val d = runCatching { LocalDate.parse(e.date) }.getOrNull() ?: continue
            val cur = DiaryStore.get(d)
            if (cur == null || cur.source == "demo" || e.logs.size > cur.logs.size) {
                withContext(Dispatchers.Main) { DiaryStore.save(e) }
                diaryIn++
            }
        }
        val parts = buildList {
            if (added > 0) add("予定を${added}件追加")
            if (updated > 0) add("予定を${updated}件更新")
            if (diaryIn > 0) add("日記を${diaryIn}日分入れました")
        }
        Result(true, if (parts.isEmpty()) "新しく入れるものはありませんでした (すべて登録済み)" else parts.joinToString("・"))
    }
}
