package com.aidy.petrobot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidy.petrobot.data.AidyDeviceLink
import com.aidy.petrobot.data.CalendarSettings
import com.aidy.petrobot.data.ChatLine
import com.aidy.petrobot.data.DiaryComposer
import com.aidy.petrobot.data.DiaryDemo
import com.aidy.petrobot.data.DiaryEntry
import com.aidy.petrobot.data.DiaryStore
import com.aidy.petrobot.data.PhoneCalendar
import com.aidy.petrobot.data.ScheduleItem
import com.aidy.petrobot.data.ScheduleStore
import com.aidy.petrobot.data.key
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val SyncDays = 7
private const val CellHeight = 50f // 日付 + ミニ Aidy または予定タイトル 1 件
private const val MaxChips = 3     // 1 日に並べる予定タイトルの数
private const val ChipTop = 29f
private const val ChipHeight = 12f
private const val ChipPitch = 13f
private val WeekLabels = listOf("日", "月", "火", "水", "木", "金", "土")
private val Sunday = Theme.hex(0xef4444)
private val Saturday = Theme.hex(0x3b82f6)
private val Amber = Theme.hex(0xf59e0b)
private val ChipBg = Theme.hex(0xfef3c7)
private val ChipFg = Theme.hex(0xb45309)
private val Danger = Theme.hex(0xef4444)
// スマホの別アプリのカレンダーの予定 (このアプリの予定と色で見分ける)
private val PhoneChipBg = Theme.hex(0xdcfce7)
private val PhoneChipFg = Theme.hex(0x15803d)

/** 日曜 = 0 の曜日。 */
private fun LocalDate.dow() = dayOfWeek.value % 7

private fun dateLabel(d: LocalDate) = "${d.year}年${d.monthValue}月${d.dayOfMonth}日 (${WeekLabels[d.dow()]})"

private fun weekRangeLabel(start: LocalDate, end: LocalDate) = when {
    start.year != end.year -> "${start.year}/${start.monthValue}/${start.dayOfMonth} 〜 ${end.year}/${end.monthValue}/${end.dayOfMonth}"
    start.monthValue != end.monthValue -> "${start.year}年${start.monthValue}月${start.dayOfMonth}日 〜 ${end.monthValue}月${end.dayOfMonth}日"
    else -> "${start.year}年${start.monthValue}月${start.dayOfMonth}日 〜 ${end.dayOfMonth}日"
}

/** 日記ページの状態 (設定画面から週表示を切り替える・表示時の自動同期)。 */
class DiaryState {
    var selected by mutableStateOf(LocalDate.now())
    var month by mutableStateOf(YearMonth.now())
    var weekAnchor by mutableStateOf(LocalDate.now().minusDays(LocalDate.now().dow().toLong()))
    var weekView by mutableStateOf(CalendarSettings.weekView)
    var logsOpen by mutableStateOf(false)
    /** 予定フォーム: null=閉じている / ""=新規 / それ以外=編集中の予定 id。 */
    var editingId by mutableStateOf<String?>(null)
    var confirmDelete by mutableStateOf(false)
    var syncing by mutableStateOf(false)
    var syncText by mutableStateOf(AidyDeviceLink.lastSyncLabel)
    /** 保存データが変わったら増やす (再描画)。 */
    var version by mutableIntStateOf(0)
    /** いまの時刻 (分)。変わったら予定を並べ直す。 */
    var minute by mutableStateOf("")
    var autoSynced = false
    val mascot = MascotState()

    // 本体の /api/week (今週の予定、読むだけ)
    var deviceWeekDays by mutableStateOf<List<AidyDeviceLink.WeekDay>>(emptyList())
    var deviceWeekStatus by mutableStateOf("「同期」を押すと本体の今週の予定を取得します")

    // 本体の /api/days (会話・記録がある日、同期前でもカレンダーに印をつける)
    var deviceDays by mutableStateOf<Set<String>>(emptySet())

    // スマホの別アプリのカレンダー (設定で ON のときだけ。読むだけで保存しない)
    var phoneEvents by mutableStateOf<Map<String, List<ScheduleItem>>>(emptyMap())
    /** 設定で結合の ON/OFF を変えたら増やす (読み直し)。 */
    var phoneVersion by mutableIntStateOf(0)
    /** 更新ボタンが押されたら true (同期を必ず頼む)。 */
    var forceSync = false
    var refreshing by mutableStateOf(false)

    /** カレンダーの更新ボタン: スマホのカレンダーの同期を頼んで読み直す。 */
    fun refreshCalendar() {
        if (refreshing) return
        forceSync = true
        refreshing = true
        phoneVersion++
    }

    /** その日の予定 (このアプリ + スマホのカレンダー)。 */
    fun plansFor(d: LocalDate): List<ScheduleItem> = ScheduleStore.forDateMerged(d, phoneEvents[d.key()].orEmpty())

    /**
     * 保存データ (日記・予定) は Compose の状態ではないので、それを読む部品はこれを呼んで
     * 保存・同期・時刻の変化で描き直されるようにする。
     */
    fun observe() = version.toString() + minute

    init {
        DiaryDemo.seedIfNeeded()
    }

    /** 月表示/週表示を切り替える (カレンダー上のボタン・設定画面)。端末に覚えておく。 */
    fun applyWeekView(on: Boolean) {
        if (weekView == on) return
        weekView = on
        CalendarSettings.weekView = on
        month = YearMonth.from(selected)
        weekAnchor = selected.minusDays(selected.dow().toLong())
    }

    fun select(date: LocalDate) {
        if (date != selected) {
            editingId = null
            confirmDelete = false
        }
        selected = date
        month = YearMonth.from(date)
        weekAnchor = date.minusDays(date.dow().toLong())
        Coach.event("day")
    }

    fun shiftPeriod(delta: Int) {
        if (weekView) weekAnchor = weekAnchor.plusDays(7L * delta)
        else month = month.plusMonths(delta.toLong())
    }

    fun openEditor(id: String?) {
        editingId = id
        confirmDelete = false
    }
}

/**
 * 日記ページ (Unity 版 AidyDiaryPage): 月カレンダー (日記のある日にミニ Aidy、予定のある日にタイトル)
 * + 選択日の日記カード + 会話ログ + 予定カード。
 */
@Composable
fun DiaryPage(state: DiaryState, shown: Boolean) {
    val scope = rememberCoroutineScope()
    fun startSync(silent: Boolean) {
        if (state.syncing) return
        state.syncing = true
        state.mascot.react(AidyFace.Surprised, 0.6f)
        scope.launch {
            val r = AidyDeviceLink.syncRecent(SyncDays)
            state.syncing = false
            state.syncText = if (r.ok || !silent) r.message else "未接続 ・ " + AidyDeviceLink.lastSyncLabel
            state.mascot.react(if (r.ok) AidyFace.Happy else AidyFace.Sleepy, 1.6f)
            if (r.updated > 0) state.version++
        }
        scope.launch {
            val w = AidyDeviceLink.fetchWeek()
            if (w.ok) {
                state.deviceWeekDays = w.days
                state.deviceWeekStatus = if (w.synced > 0)
                    "Googleカレンダー同期: " + java.time.Instant.ofEpochSecond(w.synced).atZone(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                else "本体がGoogleカレンダーにまだつながっていません"
            } else {
                state.deviceWeekStatus = w.error ?: "本体からの返事を読み取れませんでした"
            }
        }
        scope.launch {
            val d = AidyDeviceLink.fetchDays()
            if (d.ok) state.deviceDays = d.days
        }
    }
    // ページが表示されたとき。初回だけ本体と自動同期する
    LaunchedEffect(shown) {
        if (shown && !state.autoSynced) {
            state.autoSynced = true
            startSync(true)
        }
    }
    // 分が変わったら並べ直す (時刻を過ぎた予定を後ろへ回し、次の予定を先頭に出す)
    LaunchedEffect(Unit) {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        while (true) {
            state.minute = LocalDateTime.now().format(fmt)
            delay(5000)
        }
    }

    // スマホのカレンダーの予定を、いま見ている月・週の前後 1 週間ぶん読む
    LaunchedEffect(shown, state.month, state.weekAnchor, state.phoneVersion) {
        if (!shown) return@LaunchedEffect
        val monthStart = state.month.atDay(1)
        val monthEnd = state.month.atEndOfMonth()
        val from = minOf(monthStart, state.weekAnchor).minusDays(7)
        val to = maxOf(monthEnd, state.weekAnchor.plusDays(6)).plusDays(7)
        state.phoneEvents = PhoneCalendar.load(from, to)
        state.version++
        if (state.forceSync) {
            state.forceSync = false
            PhoneCalendar.pullSync(force = true)
            delay(3000)
            state.phoneEvents = PhoneCalendar.load(from, to)
            state.refreshing = false
        }
        // Google カレンダーのアプリで足した予定は、Android の同期のあとで読めるようになる。同期を頼んで少し後に読み直す
        PhoneCalendar.pullSync()
        for (wait in listOf(5000L, 15000L)) {
            delay(wait)
            state.phoneEvents = PhoneCalendar.load(from, to)
        }
    }

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    ScrollPage(scroll = scroll) {
        state.observe()
        // ---- Header
        Spacer(Modifier.height(6.dp))
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AidyMascot(44.dp, AidyFace.Normal, interactive = true, state = state.mascot)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("Aidyの日記", 18f, Theme.TextMain, true)
                Label(state.syncText, 11f, Theme.TextSub)
            }
            Row(
                Modifier
                    .coachTarget("diarySync")
                    .pressable(0.95f) { startSync(false) }
                    .box(Theme.PrimaryLight, 16.dp, border = Theme.hex(0xbfdbfe))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(IconId.Wifi, 12.dp, Theme.Primary)
                Label(if (state.syncing) "同期中…" else "同期", 12f, Theme.Primary, true)
            }
        }
        Spacer(Modifier.height(14.dp))
        CalendarModeToggle(state)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().coachTarget("calendar")) { Calendar(state) }
        Spacer(Modifier.height(12.dp))
        DeviceWeekCard(state)
        Spacer(Modifier.height(12.dp))

        // ---- 予定 (先に出す) → その日の日記
        Box(Modifier.fillMaxWidth().coachTarget("schedule")) { ScheduleCard(state) }
        Spacer(Modifier.height(12.dp))
        val entry = DiaryStore.get(state.selected)
        if (entry != null) EntryCard(state, entry) else EmptyCard(state) { startSync(false) }
    }
        // 会話ログが長いとき用: ある程度下にスクロールしたら「上に戻る」ボタンを出す
        val showTop = scroll.value > 600
        if (showTop) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 22.dp)
                    .size(48.dp)
                    .shadow(6.dp, RoundedCornerShape(24.dp))
                    .pressable(0.9f) { scope.launch { scroll.animateScrollTo(0) } }
                    .background(Theme.Primary, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(IconId.ChevronRight, 18.dp, Color.White, Modifier.rotate(-90f))
            }
        }
    }
}

@Composable
private fun cardModifier() = Modifier.fillMaxWidth().box(Color.White, 18.dp, 2.dp, Theme.Border)

@Composable
private fun Calendar(state: DiaryState) {
    state.observe()
    Column(cardModifier().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)) {
        val today = LocalDate.now()
        val label: String
        val rows: List<List<LocalDate?>>
        if (state.weekView) {
            val end = state.weekAnchor.plusDays(6)
            label = weekRangeLabel(state.weekAnchor, end)
            rows = listOf((0 until 7).map { state.weekAnchor.plusDays(it.toLong()) })
        } else {
            val m = state.month
            label = "${m.year}年${m.monthValue}月"
            val offset = m.atDay(1).dow()
            val days = m.lengthOfMonth()
            val n = ceil((offset + days) / 7f).toInt()
            rows = (0 until n).map { r ->
                (0 until 7).map { c ->
                    val day = r * 7 + c - offset + 1
                    if (day < 1 || day > days) null else m.atDay(day)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            MonthButton(IconId.ChevronLeft) { state.shiftPeriod(-1) }
            Label(label, 15f, Theme.TextMain, true, Modifier.weight(1f), align = TextAlign.Center)
            MonthButton(IconId.ChevronRight) { state.shiftPeriod(1) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until 7) {
                val col = if (i == 0) Sunday else if (i == 6) Saturday else Theme.TextSub
                Label(WeekLabels[i], 11f, col, true, Modifier.weight(1f).height(18.dp), align = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        for (row in rows) {
            // 行の中でいちばん多い予定タイトル数に合わせて、その週の行だけ縦に伸ばす
            val chips = row.maxOf { d -> if (d == null || DiaryStore.get(d) != null) 0 else min(state.plansFor(d).size, MaxChips) }
            val h = CellHeight + max(0, chips - 1) * ChipPitch
            Row(Modifier.fillMaxWidth().height(h.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (d in row) {
                    Box(Modifier.weight(1f).height(h.dp)) {
                        if (d != null) DayCell(state, d, today)
                    }
                }
            }
        }
    }
}

/** カレンダーの上: 月表示 / 週表示の切り替え (アイコン付きの 2 つ割りボタン)。 */
@Composable
private fun CalendarModeToggle(state: DiaryState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label("カレンダー", 13f, Theme.TextSub, true, Modifier.padding(start = 4.dp))
        Spacer(Modifier.size(6.dp))
        RefreshButton(state)
        Spacer(Modifier.weight(1f))
        Row(Modifier.background(Theme.Slate100, RoundedCornerShape(18.dp)).padding(3.dp)) {
            for (week in listOf(false, true)) {
                val on = state.weekView == week
                Row(
                    Modifier
                        .height(32.dp)
                        .pressable(0.95f) { state.applyWeekView(week) }
                        .box(if (on) Color.White else Color.Transparent, 15.dp, if (on) 2.dp else 0.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CalendarGlyph(week, if (on) Theme.Primary else Theme.Slate400)
                    Label(if (week) "週" else "月", 12f, if (on) Theme.Primary else Theme.Slate600, true)
                }
            }
        }
    }
}

/** カレンダーの更新ボタン (丸い矢印)。押すとスマホのカレンダーの同期を頼んで読み直す。更新中は回る。 */
@Composable
private fun RefreshButton(state: DiaryState) {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "refresh")
    val angle by spin.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing)),
        label = "refreshAngle",
    )
    Box(
        Modifier.size(32.dp).pressable(0.9f) { state.refreshCalendar() }.background(Theme.Slate100, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val color = Theme.Slate600
        androidx.compose.foundation.Canvas(Modifier.size(16.dp).rotate(if (state.refreshing) angle else 0f)) {
            val sw = 2.dp.toPx()
            val r = size.width / 2f - sw
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            drawArc(
                color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            // 矢印の先 (弧の終わり = -60° の位置に、時計回りの向きの三角)
            val a = Math.toRadians(-60.0)
            val tip = androidx.compose.ui.geometry.Offset(c.x + r * Math.cos(a).toFloat(), c.y + r * Math.sin(a).toFloat())
            val h = sw * 2.2f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(tip.x + h, tip.y - h * 0.2f)
                lineTo(tip.x - h * 0.9f, tip.y - h * 0.9f)
                lineTo(tip.x - h * 0.1f, tip.y + h * 1.1f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** カレンダーの絵: 月 = 3 段のマス、週 = 1 段のマス。 */
@Composable
private fun CalendarGlyph(week: Boolean, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val sw = 1.5.dp.toPx()
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(sw / 2f, w * 0.14f), androidx.compose.ui.geometry.Size(w - sw, w * 0.82f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.15f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawRect(color, androidx.compose.ui.geometry.Offset(sw / 2f, w * 0.14f), androidx.compose.ui.geometry.Size(w - sw, w * 0.18f))
        val rows = if (week) 1 else 3
        val cell = w * 0.13f
        for (r in 0 until rows) for (c in 0 until 4) {
            val x = w * 0.16f + c * w * 0.19f
            val y = if (week) w * 0.55f else w * 0.42f + r * w * 0.16f
            drawRect(color, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell * (if (week) 1.6f else 0.8f)))
        }
    }
}

@Composable
private fun MonthButton(icon: IconId, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).pressable(0.92f, onClick = onClick).background(Theme.Slate100, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, 12.dp, Theme.Slate600) }
}

@Composable
private fun DayCell(state: DiaryState, date: LocalDate, today: LocalDate) {
    state.observe()
    val entry = DiaryStore.get(date)
    val sel = date == state.selected
    val isToday = date == today
    val dow = date.dow()
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .padding(1.dp)
            .fillMaxSize()
            .let { if (sel || isToday) it.background(Theme.PrimaryLight, shape) else it }
            // 選択中は塗りつぶさず枠線で示す (予定タイトルの色を隠さないため)
            .let { if (sel) it.border(2.dp, Theme.Primary, shape) else it }
            .clickable { state.select(date) },
    ) {
        val numColor = if (sel || isToday) Theme.Primary else if (dow == 0) Sunday else if (dow == 6) Saturday else Theme.TextMain
        Label(date.dayOfMonth.toString(), 13f, numColor, true, Modifier.align(Alignment.TopCenter).padding(top = 6.dp), align = TextAlign.Center)
        if (entry != null) {
            val px = with(LocalDensity.current) { 16.dp.roundToPx() }
            val face = DiaryComposer.faceFor(entry.mood)
            val bmp = remember(face, px) { AidyMascotArt.get(face, px, 16f) }
            Image(bmp, null, Modifier.align(Alignment.TopCenter).padding(top = 26.dp).size(16.dp))
        } else if (state.deviceDays.contains(date.key())) {
            // 本体にはあるがまだ同期していない会話 (/api/days)。同期すると日記になる
            Box(Modifier.align(Alignment.TopCenter).padding(top = 32.dp).size(6.dp).background(Theme.Slate300, RoundedCornerShape(3.dp)))
        }
        val plans = state.plansFor(date)
        val shownCount = if (entry == null) min(plans.size, MaxChips) else 0 // 日記のある日はミニ Aidy を優先
        val hidden = plans.size - shownCount
        for (i in 0 until shownCount) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = (ChipTop + i * ChipPitch - 1f).dp, start = 2.dp, end = 2.dp)
                    .fillMaxWidth()
                    .height(ChipHeight.dp)
                    .background(if (plans[i].fromPhone) PhoneChipBg else ChipBg, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Label(plans[i].title, 8f, if (plans[i].fromPhone) PhoneChipFg else ChipFg, true, Modifier.padding(horizontal = 2.dp), maxLines = 1, align = TextAlign.Center)
            }
        }
        // 表示しきれない予定の件数 (右上の「+1」)
        if (hidden > 0) Label("+$hidden", 8f, Amber, true, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 2.dp))
    }
}

/** 本体 (Google カレンダーの控え + 本体のやることリスト) と同期する今週の予定。書き込みはまだない。 */
@Composable
private fun DeviceWeekCard(state: DiaryState) {
    Column(cardModifier().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("今週の予定（本体と同期）", 13f, Theme.TextMain, true)
        Label(state.deviceWeekStatus, 11f, Theme.TextSub)
        if (state.deviceWeekDays.isEmpty()) {
            Label("予定はありませんでした", 12f, Theme.Slate400)
        } else {
            for (d in state.deviceWeekDays) DeviceWeekRow(d)
        }
    }
}

@Composable
private fun DeviceWeekRow(d: AidyDeviceLink.WeekDay) {
    val label = runCatching { LocalDate.parse(d.date) }
        .getOrNull()
        ?.let { "${it.monthValue}/${it.dayOfMonth}（${WeekLabels[it.dow()]}）" }
        ?: d.date
    val parts = buildList {
        d.events.forEach { add("${it.time} ${it.title}") }
        d.todo.forEach { add("${it.label} ${it.text}") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label, 12f, Theme.TextMain, true, Modifier.widthIn(min = 56.dp))
        Label(if (parts.isNotEmpty()) parts.joinToString(" ・ ") else "予定なし", 12f, if (parts.isNotEmpty()) Theme.TextMain else Theme.Slate400, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Label(text, 11f, fg, true)
    }
}

@Composable
private fun EntryCard(state: DiaryState, e: DiaryEntry) {
    Column(cardModifier().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AidyMascot(48.dp, DiaryComposer.faceFor(e.mood), interactive = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Label(dateLabel(state.selected), 12f, Theme.TextSub, true)
                Label(e.title, 16f, Theme.TextMain, true)
            }
            if (e.source == "demo") Pill("サンプル", Theme.hex(0xfef3c7), Theme.hex(0xb45309))
        }
        Spacer(Modifier.height(14.dp))
        androidx.compose.material3.Text(e.summary, color = Theme.Slate700, fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("おしゃべり ${e.userCount}回", Theme.PrimaryLight, Theme.Primary)
            if (e.logs.isNotEmpty()) Pill("${e.logs.first().time} 〜 ${e.logs.last().time}", Theme.Slate100, Theme.Slate600)
        }
        if (e.logs.isEmpty()) return@Column
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.Slate100))
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clickable { state.logsOpen = !state.logsOpen }.padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(IconId.Aidy, 13.dp, Theme.TextSub)
            Label("会話ログ", 13f, Theme.TextSub, true, Modifier.weight(1f))
            Label("${e.logs.size}件", 11f, Theme.Slate400)
            Icon(IconId.ChevronRight, 11.dp, Theme.Slate400, Modifier.rotate(if (state.logsOpen) 90f else 0f))
        }
        if (state.logsOpen) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (l in e.logs) ChatRow(l)
                Row(
                    Modifier.fillMaxWidth().clickable { state.logsOpen = false }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconId.ChevronRight, 10.dp, Theme.Slate400, Modifier.rotate(-90f))
                    Spacer(Modifier.size(6.dp))
                    Label("会話ログを閉じる", 12f, Theme.Slate400, true)
                }
            }
        }
    }
}

@Composable
private fun ChatRow(l: ChatLine) {
    val user = l.isUser
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (user) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!user) AidyMascot(24.dp, AidyFace.Normal, animate = false)
        if (user) Label(l.time, 10f, Theme.Slate400)
        Box(
            Modifier
                .widthIn(max = 244.dp)
                .background(if (user) Theme.Primary else Theme.Slate100, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Label(l.text, 13f, if (user) Color.White else Theme.TextMain)
        }
        if (!user) Label(l.time, 10f, Theme.Slate400)
    }
}

@Composable
private fun EmptyCard(state: DiaryState, onFetch: () -> Unit) {
    state.observe()
    val today = LocalDate.now()
    val future = state.selected > today
    val isToday = state.selected == today
    Column(cardModifier().padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AidyMascot(64.dp, if (future) AidyFace.Normal else AidyFace.Sleepy, interactive = true)
        Spacer(Modifier.height(4.dp))
        Label(dateLabel(state.selected), 12f, Theme.TextSub, true, align = TextAlign.Center)
        Label(if (future) "まだ来ていない日だよ" else if (isToday) "今日の日記はまだないよ" else "この日の日記はないみたい", 15f, Theme.TextMain, true, align = TextAlign.Center)
        Label(if (future) "下の「予定」から、この日の予定を入れておけるよ" else "おしゃべりすると、会話ログから日記ができるよ", 12f, Theme.TextSub, align = TextAlign.Center)
        if (!isToday) return@Column
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .pressable(0.96f, onClick = onFetch)
                .box(Theme.Primary, 20.dp, 4.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(IconId.Wifi, 12.dp, Color.White)
            Label("Aidyから会話ログを受け取る", 13f, Color.White, true)
        }
    }
}

@Composable
private fun ScheduleCard(state: DiaryState) {
    state.observe()
    Column(cardModifier().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(IconId.MapPin, 13.dp, Amber)
            Label("予定", 14f, Theme.TextMain, true, Modifier.weight(1f))
            if (state.editingId == null) SmallButton("＋ 追加", Theme.PrimaryLight, Theme.Primary) { state.openEditor("") }
        }
        val list = state.plansFor(state.selected)
        if (PhoneCalendar.active) {
            Label("緑はスマホのカレンダーの予定 (見るだけ。変更は元のアプリで)", 10f, PhoneChipFg)
        }
        if (list.isEmpty() && state.editingId == null) {
            Spacer(Modifier.height(6.dp))
            Label("この日の予定はまだないよ", 12f, Theme.Slate400)
        }
        for (item in list) {
            Spacer(Modifier.height(8.dp))
            if (item.id == state.editingId) Editor(state, item) else ScheduleRow(state, item)
        }
        if (state.editingId == "") {
            Spacer(Modifier.height(8.dp))
            Editor(state, ScheduleItem(date = state.selected.key()))
        }
    }
}

@Composable
private fun ScheduleRow(state: DiaryState, item: ScheduleItem) {
    state.observe()
    val past = item.isPast(LocalDateTime.now())
    val phone = item.fromPhone
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (phone) it else it.pressable(0.98f) { state.openEditor(item.id) } }
            .background(if (phone) Theme.hex(0xf0fdf4) else Theme.Slate100, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(if (item.allDay) "終日" else item.time, 12f, if (past) Theme.Slate400 else if (item.allDay) Amber else Theme.Primary, true, Modifier.widthIn(min = 44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(item.title, 13f, Theme.TextMain, true)
            if (item.memo.isNotEmpty()) Label(item.memo, 11f, Theme.TextSub)
        }
        if (phone) Label(item.calendar.ifEmpty { "スマホ" }, 10f, PhoneChipFg, true, maxLines = 1)
        else Label("編集", 11f, Theme.Slate400)
    }
}

@Composable
private fun Editor(state: DiaryState, item: ScheduleItem) {
    val scope = rememberCoroutineScope()
    val isNew = item.id.isEmpty()
    var title by remember(item.id) { mutableStateOf(item.title) }
    var time by remember(item.id) { mutableStateOf(item.time) }
    var memo by remember(item.id) { mutableStateOf(item.memo) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().box(Color.White, 12.dp, border = Theme.hex(0xbfdbfe), borderWidth = 1.5.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(if (isNew) "予定を追加" else "予定を編集", 12f, Theme.Primary, true)
        Label("タイトル", 11f, Theme.TextSub, true)
        Input(title, { title = it.take(40) }, "例: 病院、友だちとおでかけ", false)
        Label("時刻 (空欄なら終日)", 11f, Theme.TextSub, true)
        Input(time, { time = it.take(5) }, "例: 10:00", false)
        Label("メモ", 11f, Theme.TextSub, true)
        Input(memo, { memo = it.take(200) }, "なくてもOK", true)
        error?.let { Label(it, 11f, Danger) }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) {
                // 誤タップ防止: 1 回目で確認表示、2 回目で削除
                SmallButton(
                    if (state.confirmDelete) "本当に削除する？" else "削除",
                    if (state.confirmDelete) Danger else Theme.hex(0xfee2e2),
                    if (state.confirmDelete) Color.White else Danger,
                ) {
                    if (!state.confirmDelete) {
                        state.confirmDelete = true
                        return@SmallButton
                    }
                    ScheduleStore.delete(item.id)?.let { old ->
                        // スマホのカレンダーからも消す (結合 ON・書く許可ありのとき)
                        scope.launch { PhoneCalendar.remove(old); state.phoneVersion++ }
                    }
                    state.mascot.react(AidyFace.Surprised, 1f)
                    state.openEditor(null)
                    state.version++
                }
            }
            Spacer(Modifier.weight(1f))
            SmallButton("キャンセル", Theme.Slate100, Theme.Slate600) { state.openEditor(null) }
            SmallButton("保存", Theme.Primary, Color.White) {
                val t = title.trim()
                val tm = ScheduleStore.parseTime(time)
                val msg = if (t.isEmpty()) "タイトルを入れてね" else if (tm == null) "時刻は 10:00 のように入れてね" else null
                if (msg != null) {
                    error = msg
                    return@SmallButton
                }
                val saved = ScheduleStore.save(item.copy(time = tm!!, title = t, memo = memo.trim()))
                // スマホのカレンダーにも書く (結合 ON・書く許可ありのとき)
                scope.launch { PhoneCalendar.pushAndLink(saved, if (isNew) null else item); state.phoneVersion++ }
                state.mascot.react(AidyFace.Happy, 1.2f)
                state.openEditor(null)
                state.version++
            }
        }
    }
}

