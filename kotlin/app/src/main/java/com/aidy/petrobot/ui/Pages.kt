package com.aidy.petrobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.ar.ARCameraView
import com.aidy.petrobot.ar.ARController
import com.aidy.petrobot.ar.Placement
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.aidy.petrobot.data.AidyDeviceLink
import com.aidy.petrobot.data.BleLink
import com.aidy.petrobot.data.PhoneCalendar
import com.aidy.petrobot.data.BodyColor
import com.aidy.petrobot.data.BodyBridge
import com.aidy.petrobot.data.CalendarSettings
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleConfig
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.three.HomeRoom
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import com.aidy.petrobot.data.RoomPref
import com.aidy.petrobot.three.RoomDecor

// ---------------------------------------------------------------- PAGE 0: ホーム (3D ペットルーム)

/** いまのモジュールの一覧 (耳×2・尻尾…)。 */
fun moduleSummaryText(slots: Map<String, ModuleKind>): String {
    val counts = HashMap<ModuleKind, Int>()
    for (k in slots.values) counts[k] = (counts[k] ?: 0) + 1
    val parts = ModuleCatalog.All.mapNotNull { k -> counts[k]?.let { n -> if (n > 1) "${ModuleCatalog.name(k)}×$n" else ModuleCatalog.name(k) } }
    return if (parts.isNotEmpty()) parts.joinToString("・") else "なし (本体だけ)"
}

/** ホーム画面の 3D ルームの状態 (吹き出しの文字・マスコット・お知らせ・着せ替え中か)。 */
class HomeUi(val room: HomeRoom) {
    var stateText by mutableStateOf("こんにちは！")
    val mascot = MascotState()
    val toast = ToastState()
    /** 着せ替え画面 (きせかえルーム) を開いている。下のタブを隠す。 */
    var dressing by mutableStateOf(false)
    val curtain = CurtainState()

    init {
        room.onStatus = { stateText = it }
        room.onModelBadge = { ok, text -> toast.show(if (ok) AidyFace.Happy else AidyFace.Sleepy, text, if (ok) 2.8f else 0f) }
        room.onFaceReaction = { face, seconds -> mascot.react(face, seconds) }
        room.onUserAction = { Coach.event(it) }
        room.reportModelState()
    }
}

/**
 * ホーム (デザイン「ホーム（整理後）」): 3D はページいっぱい、状態は Aidy の頭の横の吹き出し、
 * 右下に「お部屋を変える」「着せ替え」の丸ボタン。着せ替えはカーテンの演出のあと、きせかえルームに切り替える。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun HomePage(ui: HomeUi, active: Boolean, onEdit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showRooms by remember { mutableStateOf(false) }
    // 着せ替えのドラッグ&ドロップ (AR の付け替えと同じしくみ: トレイからスロットの丸へ、本体のパーツをシートへで取り外し)
    val placement = remember { ViewPlacement() }
    val tray = remember(ui.room) { ModuleTrayState(ui.room, placement) }

    fun setDressing(on: Boolean) {
        if (ui.curtain.progress.isRunning || ui.dressing == on) return
        scope.launch {
            ui.curtain.play {
                ui.room.setRoom(if (on) RoomDecor.StageRoom else RoomPref.current)
                ui.room.setStageMode(on)
                ui.dressing = on
            }
        }
    }
    BackHandler(enabled = ui.dressing) { setDressing(false) }

    Box(Modifier.fillMaxSize().background(Theme.Device)) {
        View3D(
            ui.room, active, ui.stateText, ui.mascot, null,
            Modifier.fillMaxSize().coachTarget("home3d"),
            bottomInset = if (ui.dressing) DressingSheetHeight.dp else 0.dp,
            toast = ui.toast,
            toastTop = 52.dp,
            // 右下の丸ボタンと重ならないように、ズームボタンはその上へ
            zoomBottom = if (ui.dressing) null else (HomeButtonsBottom + HomeButtonsHeight + 14f).dp,
            zoomEnd = if (ui.dressing) 10.dp else (HomeButtonsEnd + 28f - 17f).dp,
            tray = if (ui.dressing) tray else null,
            placement = placement,
        )
        StatusBubble(ui.room, ui.stateText, active)

        if (ui.dressing) {
            DressingOverlay(ui, tray) { setDressing(false) }
        } else {
            // 右下: お部屋を変える・着せ替え (アイコンのみ)
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = HomeButtonsEnd.dp, bottom = HomeButtonsBottom.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeRoundButton("お部屋を変える", HomeIconPaths.House, 56.dp, 24.dp) { showRooms = true }
                HomeRoundButton("着せ替え", HomeIconPaths.Shirt, 56.dp, 24.dp, Modifier.coachTarget("color")) { setDressing(true) }
            }
        }

        if (ui.dressing) {
            ModuleDragGhost(tray) // ドラッグ中のパーツ (指の少し上)
        } else {
            // Aidy の吹き出し (本体からの返事)。3D の Aidy の頭の上あたり
            AidyBubble(Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 24.dp, end = 24.dp))

            // 会話欄 (文字・スマホのマイク)。いちばん下。右下の丸ボタンはその上
            HomeChatBar(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = ChatMargin.dp)
                    .fillMaxWidth()
                    .coachTarget("chat"),
            )
        }
        CurtainOverlay(ui.curtain)
    }

    // 本体の表情を 3D の顔に / モジュールの抜き差しを一言
    LaunchedEffect(BodyBridge.faceVersion) { if (BodyBridge.faceVersion > 0) ui.room.setBodyFace(BodyBridge.face) }
    LaunchedEffect(BodyBridge.moduleNoteVersion) {
        val n = BodyBridge.moduleNote
        if (BodyBridge.moduleNoteVersion > 0 && n != null) ui.toast.show(AidyFace.Surprised, n, 3f)
    }
    if (showRooms) RoomPickerSheet(ui.room) { showRooms = false }
}

/** 会話欄 (高さ 56) の下の余白と、右下の丸ボタンの位置 (dp)。丸ボタンは会話欄の上、高さは 56 + 12 + 56。 */
private const val ChatMargin = 12f
private const val HomeButtonsEnd = 12f
private const val HomeButtonsBottom = ChatMargin + 56f + 12f
private const val HomeButtonsHeight = 124f

// ---------------------------------------------------------------- PAGE 2: リモコン・モジュール

@Composable
fun RemotePage(visible: Boolean) {
    var showDictManager by remember { mutableStateOf(false) }
    if (showDictManager) DictManagerDialog(onDismiss = { showDictManager = false })

    ScrollPage(Modifier.background(Theme.Device)) {
        SectionTitle("Aidy本体", true)
        VoiceSettingsCard(visible)
        Card(IconId.Book, Theme.hex(0x8b5cf6), Theme.hex(0xf3e8ff), "辞書 (よくある質問・聞き間違い・読み)", "本体の返事をカスタマイズする", onClick = { showDictManager = true }) {
            Icon(IconId.ChevronRight, 12.dp, Theme.Slate400)
        }

        SectionTitle("本体のモジュール (差し込み口)", false)
        BodyPortsCard(visible)

        SectionTitle("拡張モジュール (追加3Dモデル連携)", false)
        Card(IconId.Video, Theme.hex(0x8b5cf6), Theme.hex(0xf3e8ff), "カメラフィードモジュール", "拡張OBJモデル接続可能") { Icon(IconId.Plus, 16.dp, Theme.Primary) }
        Card(IconId.Microchip, Theme.hex(0xec4899), Theme.hex(0xfce7f3), "センサーモジュール", "温度/湿度 拡張モジュール") { Icon(IconId.Plus, 16.dp, Theme.Primary) }
    }
}

// ---------------------------------------------------------------- PAGE 4: 設定

@Composable
fun SettingsPage(diary: DiaryState, onOpenDiary: () -> Unit, onStartTutorial: () -> Unit, onOpenGuide: () -> Unit) {
    ScrollPage(Modifier.background(Theme.Device)) {
        SectionTitle("Aidy本体と接続", true)
        val linkLabel = if (!AidyDeviceLink.isConfigured) "未設定（下で合言葉を入れてね）" else AidyDeviceLink.targetLabel + " から日記を同期"
        Card(IconId.Aidy, Theme.Primary, Theme.PrimaryLight, "Aidy本体の会話ログ", linkLabel, onClick = onOpenDiary) {
            Icon(IconId.ChevronRight, 12.dp, Theme.Slate400)
        }
        DeviceLinkCard()
        Label("音量・話す速さ・辞書はリモコンのページにあります", 11f, Theme.TextSub, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))

        SectionTitle("Aidyと人", false)
        AidyNameCard()
        FacePeopleCard()

        SectionTitle("ロボット設定", false)
        Card(null, Color.Unspecified, Color.Unspecified, "アセンブリ構造", "本体v2_モデル.obj (透明化防止完全対応)") { Icon(IconId.Check, 14.dp, Theme.Green) }

        SectionTitle("日記", false)
        Card(null, Color.Unspecified, Color.Unspecified, "カレンダー表示", "週表示に切り替える") {
            AidySwitch(diary.weekView, { on -> diary.applyWeekView(on) })
        }
        PhoneCalendarCard(diary)
        if (diary.phoneVersion >= 0 && PhoneCalendar.active) PhoneCalendarList(diary)

        SectionTitle("操作 & ネットワーク", false)
        Card(null, Color.Unspecified, Color.Unspecified, "モジュール自動合体", "パーツSocketアタッチ機能 (ON)") { AidySwitch(true) }
        Card(null, Color.Unspecified, Color.Unspecified, "Wi-Fi 接続", "AIDY-5G-2.4G (接続済み)") { Icon(IconId.Wifi, 16.dp, Theme.Green) }

        SectionTitle("今後の開発予定", false)
        FutureFeatureList()

        // 使いかた: 紙芝居のガイドと、実際の画面で練習するチュートリアル (開発中はいつでも始められるよう、ここに置く)
        SectionTitle("使いかた", false)
        Card(IconId.Book, Theme.Primary, Theme.PrimaryLight, "使いかたガイド", "全部の画面の使いかたを読む", onClick = onOpenGuide) {
            Icon(IconId.ChevronRight, 12.dp, Theme.Slate400)
        }
        Card(IconId.HandPointer, Theme.hex(0xca8a04), Theme.hex(0xfef9c3), "チュートリアル", "Aidyといっしょに、実際にさわって覚える", onClick = onStartTutorial) {
            SmallButton("チュートリアルを開始", Theme.Primary, Color.White, onStartTutorial)
        }
    }
}

// ---------------------------------------------------------------- 設定: Aidy本体と接続 (Bluetooth / Wi-Fi・合言葉)

/**
 * 通り道 (Bluetooth / Wi-Fi) と、本体の名前 or 住所・合言葉を、QR 読み取りか手入力で設定し、
 * /api/ping で接続を確かめるカード。Bluetooth のときは先に実行時の許可を求める。
 */
@Composable
private fun DeviceLinkCard() {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AidyDeviceLink.mode) }
    var host by remember { mutableStateOf(AidyDeviceLink.host) }
    var bleName by remember { mutableStateOf(AidyDeviceLink.bleName) }
    var token by remember { mutableStateOf(AidyDeviceLink.token) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (AidyDeviceLink.isConfigured) "保存済み。「保存して接続を試す」で確かめてね" else "まだ設定されていません") }
    var statusColor by remember { mutableStateOf(Theme.TextSub) }
    var showScanner by remember { mutableStateOf(false) }

    fun runPing() {
        checking = true
        statusColor = Theme.Slate600
        status = if (mode == AidyDeviceLink.Mode.Bluetooth) "Bluetooth で本体を探しています…" else "接続を確認中…"
        scope.launch {
            val r = AidyDeviceLink.ping()
            checking = false
            statusColor = if (r.ok) Theme.Green else Theme.hex(0xef4444)
            status = r.message
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) runPing()
        else {
            checking = false
            statusColor = Theme.hex(0xef4444)
            status = "Bluetooth の許可がないと本体とつなげません (スマホの設定 > アプリ > 権限 でも変えられます)"
        }
    }

    fun testConnect() {
        if (checking) return
        // QR の中身 (http://…/?t=…) をそのままアドレス欄に貼った場合は、住所と合言葉に分ける
        AidyDeviceLink.parseQr(host)?.let { q ->
            host = q.host
            if (token.isBlank()) token = q.token
            if (bleName.isBlank()) bleName = q.ble
        }
        AidyDeviceLink.mode = mode
        AidyDeviceLink.host = host
        AidyDeviceLink.bleName = bleName
        AidyDeviceLink.token = token
        host = AidyDeviceLink.host
        bleName = AidyDeviceLink.bleName
        token = AidyDeviceLink.token
        if (mode == AidyDeviceLink.Mode.Bluetooth && !BleLink.hasPermissions()) {
            checking = true
            permLauncher.launch(BleLink.requiredPermissions())
        } else {
            runPing()
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onResult = { q ->
                if (q.host.isNotEmpty()) host = q.host
                if (q.ble.isNotEmpty()) bleName = q.ble
                token = q.token
                showScanner = false
                testConnect()
            },
            onDismiss = { showScanner = false },
        )
    }

    Column(
        Modifier.fillMaxWidth().coachTarget("deviceLink").box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("つなぎ方", 11f, Theme.TextSub, true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ble = mode == AidyDeviceLink.Mode.Bluetooth
            SmallButton("Bluetooth", if (ble) Theme.Primary else Theme.Slate100, if (ble) Color.White else Theme.Slate600) { mode = AidyDeviceLink.Mode.Bluetooth }
            SmallButton("Wi-Fi", if (!ble) Theme.Primary else Theme.Slate100, if (!ble) Color.White else Theme.Slate600) { mode = AidyDeviceLink.Mode.Wifi }
        }
        if (mode == AidyDeviceLink.Mode.Bluetooth) {
            Label("本体の Bluetooth 名 (空なら近くの Aidy を探す)", 11f, Theme.TextSub, true)
            Input(bleName, { bleName = it.take(32) }, "例: AIdy-1A2B", false)
        } else {
            Label("本体のアドレス (IP:ポート)", 11f, Theme.TextSub, true)
            Input(host, { host = it.take(64) }, "例: 192.168.1.23:8090", false)
        }
        Label("合言葉 (本体の QR の t= の後ろ)", 11f, Theme.TextSub, true)
        Input(token, { token = it.take(64) }, "QRコードの合言葉", false, password = true)
        Label(status, 12f, statusColor, true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("QRコードを読み取る", Theme.Slate100, Theme.Slate600) { showScanner = true }
            Box(Modifier.weight(1f))
            SmallButton("保存して接続を試す", Theme.Primary, Color.White) { testConnect() }
        }
    }
    Spacer(Modifier.height(10.dp))
}


// ---------------------------------------------------------------- 設定: スマホのカレンダーと結合

/**
 * スマホの別アプリのカレンダーの予定を、このアプリのカレンダーに一緒に出すかどうか。
 * ON にするときにカレンダーの許可を求める。許可されなければ OFF のまま。
 */
@Composable
private fun PhoneCalendarCard(diary: DiaryState) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(PhoneCalendar.active) }
    var note by remember {
        mutableStateOf(
            when {
                PhoneCalendar.switchOn && !PhoneCalendar.hasPermission() -> "カレンダーの許可が取り消されたので結合していません"
                PhoneCalendar.active && !PhoneCalendar.canWrite() -> "スマホの予定を表示中 (書く許可がないので、このアプリの予定はスマホに入りません)"
                PhoneCalendar.active -> "スマホのカレンダーと予定をやりとりしています"
                else -> "Googleカレンダーなどスマホのカレンダーと予定を共有します (スマホの予定は緑で表示)"
            }
        )
    }

    fun apply(v: Boolean) {
        PhoneCalendar.switchOn = v
        on = PhoneCalendar.active
        diary.phoneVersion++
        if (on) scope.launch {
            val n = PhoneCalendar.pushAll()
            if (n > 0) { diary.version++; note = "スマホのカレンダーと予定をやりとりしています (このアプリの予定を${n}件スマホに入れました)" }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when {
            !PhoneCalendar.hasPermission() -> {
                apply(false)
                note = "カレンダーの許可がないので結合できません (スマホの設定 > アプリ > 権限 で許可できます)"
            }
            !PhoneCalendar.canWrite() -> {
                apply(true)
                note = "スマホの予定を表示中 (書く許可がないので、このアプリの予定はスマホに入りません)"
            }
            else -> {
                note = "スマホのカレンダーと予定をやりとりしています"
                apply(true)
            }
        }
    }

    Card(null, Color.Unspecified, Color.Unspecified, "スマホのカレンダーと結合", note) {
        AidySwitch(on, { v ->
            if (!v) {
                apply(false)
                note = "結合をやめました (スマホに入れた予定はそのまま残ります)"
            } else if (PhoneCalendar.hasPermission() && PhoneCalendar.canWrite()) {
                note = "スマホのカレンダーと予定をやりとりしています"
                apply(true)
            } else {
                launcher.launch(PhoneCalendar.Permissions)
            }
        })
    }
}

/**
 * スマホに入っているカレンダーの一覧。カレンダーごとに
 *   「表示」= その予定をこのアプリのカレンダーに出すか
 *   「予定を入れる」= このアプリで足した予定をそこにも書くか (いくつでも選べる)
 */
@Composable
private fun PhoneCalendarList(diary: DiaryState) {
    val scope = rememberCoroutineScope()
    var cals by remember { mutableStateOf<List<PhoneCalendar.Cal>>(emptyList()) }
    var targets by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var shown by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var note by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            cals = PhoneCalendar.calendars()
            targets = PhoneCalendar.writeTargets(cals)
            shown = cals.filter { PhoneCalendar.isShown(it.id) }.map { it.id }.toSet()
        }
    }
    LaunchedEffect(diary.phoneVersion) { refresh() }

    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("つなぐカレンダー", 14f, Theme.TextMain, true)
        Label("「表示」はこのアプリに出すカレンダー、「入れる」はこのアプリで足した予定を書きこむカレンダーです (いくつでも選べます)", 11f, Theme.TextSub)
        if (cals.isEmpty()) Label("スマホにカレンダーが見つかりません", 12f, Theme.Slate400)
        for (c in cals) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).background(Color(c.color or 0xFF000000.toInt()), RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Label(c.label, 13f, Theme.TextMain, true, maxLines = 1)
                    Label(c.kind + (if (!c.writable) " ・ 見るだけ" else ""), 10f, Theme.TextSub, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("表示", 9f, Theme.Slate400)
                    AidySwitch(c.id in shown, { v ->
                        PhoneCalendar.setShown(c.id, v)
                        shown = if (v) shown + c.id else shown - c.id
                        diary.phoneVersion++
                    }, 0.8f)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("入れる", 9f, if (c.writable) Theme.Slate400 else Theme.Slate300)
                    if (c.writable) {
                        AidySwitch(c.id in targets, { v ->
                            PhoneCalendar.setWriteTarget(cals, c.id, v)
                            targets = PhoneCalendar.writeTargets(cals)
                            if (v) scope.launch {
                                val n = PhoneCalendar.pushAll()
                                if (n > 0) { diary.version++; note = "「${c.label}」に予定を${n}件入れました" }
                            }
                            diary.phoneVersion++
                        }, 0.8f)
                    } else {
                        Box(Modifier.size(width = 40.dp, height = 24.dp))
                    }
                }
            }
        }
        if (note.isNotEmpty()) Label(note, 11f, Theme.Green, true)
        Label("Google のカレンダーは、スマホの同期のあとに反映されます (数秒〜数分)。", 10f, Theme.Slate400)
    }
    Spacer(Modifier.height(10.dp))
}

// ---------------------------------------------------------------- 設定: 今後の開発予定 (仕様書にあるが本体・アプリにまだない口)

/**
 * 仕様書 (AIdy_アプリ連携_仕様.md) にあっても、まだこのアプリ (または本体) にない機能をまとめたリスト。
 * 口ができたときにすぐ実装できるよう、どの API に対応するかをここに残しておく。
 */
@Composable
private fun FutureFeatureList() {
    Column(
        Modifier.fillMaxWidth().background(Theme.Slate100, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("下は本体側にまだ口が無いので、このアプリからは作れません。本体が対応したら実装してください。", 11f, Theme.TextSub)
        FutureRow("本体の今のようす (表情・人・曲・タイマー)", "GET /api/status (本体側が指示書の段階2で対応予定)")
        FutureRow("予定をまとめて見る・やることを足す", "GET /api/schedule ・ POST /api/todo (本体側が指示書の段階3で対応予定)")
        FutureRow("文字で話させる・話しかける", "POST /api/say ・ POST /api/talk (本体側が指示書の段階4で対応予定)")
        FutureRow("スマホのマイクで話しかける", "POST /api/speech (本体側が指示書の段階5で対応予定)")
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun FutureRow(title: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label("・$title", 12f, Theme.TextMain, true)
        Label(sub, 11f, Theme.TextSub)
    }
}

// ---------------------------------------------------------------- PAGE 3: AR

private const val TrayHeight = 160f
private const val TrayMargin = 12f
private const val SwitchSpace = 56f

/** AR タブの状態: 「画面上」(付け替えプレビュー) と「AR」(カメラ) の切り替え。 */
class ARPageUi(val editRoom: HomeRoom, val ar: ARController) {
    var arMode by mutableStateOf(false)
    var editStateText by mutableStateOf("面をタップして付け替えよう")
    val editMascot = MascotState()
    val arToast = ToastState()
    val placement = ViewPlacement()
    val tray = ModuleTrayState(editRoom, placement)
    var pageShown = false

    init {
        editRoom.onStatus = { editStateText = it }
        editRoom.onFaceReaction = { face, seconds -> editMascot.react(face, seconds) }
        editRoom.onFaceSelected = { tray.refresh() }
        editRoom.onModulesChanged = { tray.refresh() }
        editRoom.onUserAction = { Coach.event(it) }
        ar.onPlaced = { text ->
            when {
                text.contains("未配置") -> arToast.show(AidyFace.Normal, "床や机をタップして置いてね", 3f)
                text.contains("中央") -> arToast.show(AidyFace.Happy, "まんなかに戻ったよ！", 2.2f)
                else -> arToast.show(AidyFace.Happy, "ここに置いたよ！", 2.2f)
            }
        }
        ar.onHint = { arToast.show(AidyFace.Normal, it, 2.8f) }
    }

    /** true: カメラで AR / false: 画面上の 3D で付け替えプレビュー。 */
    fun setARMode(on: Boolean) {
        if (on && !arMode) ar.changePlacement(Placement.Marker) // AR はマーカーで表示する (マーカーなしの配置はなくした)
        arMode = on
        if (on && pageShown) ar.startCamera()
        if (!on) ar.stopCamera()
    }

}

@Composable
fun ARPage(ui: ARPageUi, active: Boolean, onMarkerButton: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Theme.Device).coachTarget("arEdit")) {
        if (ui.arMode) ARCameraMode(ui, onMarkerButton)
        else {
            // ---- 画面上: 3D プレビューで面をタップ → モジュールをドラッグ&ドロップで付け替え
            View3D(
                ui.editRoom, active, ui.editStateText, ui.editMascot, "面をタップで選択 ・ ドラッグで回転 ・ ピンチでズーム",
                Modifier.fillMaxSize().coachTarget("ar3d"),
                bottomInset = (TrayHeight + TrayMargin * 2f).dp,
                topInset = SwitchSpace.dp,
                tray = ui.tray,
                placement = ui.placement,
            )
            ModuleTray(
                ui.tray, onMarkerButton,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = TrayMargin.dp)
                    .fillMaxWidth()
                    .height(TrayHeight.dp),
            )
        }

        // ---- 切り替え [画面上 | AR]
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .size(220.dp, 38.dp)
                .coachTarget("arSwitch")
                .box(Color.White.copy(alpha = 0.95f), 19.dp, 5.dp, Theme.Border)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Segment(IconId.Cubes, "画面上", !ui.arMode, Modifier.weight(1f)) { ui.setARMode(false) }
            Segment(IconId.Camera, "AR", ui.arMode, Modifier.weight(1f)) { ui.setARMode(true) }
        }

        if (!ui.arMode) ModuleDragGhost(ui.tray)
    }
}

@Composable
private fun Segment(icon: IconId, text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (selected) Color.White else Theme.Slate600
    Row(
        modifier
            .height(32.dp)
            .pressable(0.96f, onClick = onClick)
            .background(if (selected) Theme.Primary else Color.Transparent, RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, 12.dp, fg)
        Label(text, 12f, fg, true)
    }
}

/** AR (カメラ): 本体を床や机に置いて見る。 */
@Composable
private fun ARCameraMode(ui: ARPageUi, onMarkerButton: () -> Unit) {
    val ar = ui.ar
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ARCameraView(ar, Modifier.fillMaxSize())

        // 上: 状態のバッジ
        val st = ar.status
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, end = 20.dp, top = 62.dp)
                .box(Theme.hex(0x0f172a, 0.8f), 20.dp, border = Color.White.copy(alpha = 0.2f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(st.icon, st.size.dp, st.color)
            Label(st.text, 12f, Color.White, true)
        }
        AidyToast(ui.arToast, Modifier.align(Alignment.TopCenter).padding(top = 110.dp))

        // 下: 操作ボタン
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ARButton(IconId.Crosshairs, "マーカー・色", true, Modifier.weight(1f), onMarkerButton)
            ARButton(IconId.Camera, "カメラ切替", false, Modifier.weight(1f)) {
                val cam = ar.switchCamera()
                if (cam == null) ui.arToast.show(AidyFace.Sleepy, "切り替えられるカメラがないよ", 2.2f)
                else ui.arToast.show(AidyFace.Happy, "カメラ: $cam", 2.2f)
            }
        }

    }
}

@Composable
private fun ARButton(icon: IconId, text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (primary) Color.White else Theme.Slate900
    Row(
        modifier
            .height(48.dp)
            .pressable(0.97f, onClick = onClick)
            .box(if (primary) Theme.Primary else Color.White.copy(alpha = 0.9f), 24.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, 13.dp, fg)
        Label(text, 13f, fg, true, maxLines = 1)
    }
}


/** 本体の差し込み口 (GET /api/ports) の様子。抜き差しの知らせが来たら自動で読み直す。 */
@Composable
private fun BodyPortsCard(visible: Boolean) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    fun refresh() {
        if (!AidyDeviceLink.isConfigured) { note = "本体とつながっていません"; return }
        scope.launch {
            note = try { BodyBridge.fetchPorts(); "" } catch (e: Exception) { "読み込めませんでした" }
        }
    }
    LaunchedEffect(visible) { if (visible) refresh() }
    val p = BodyBridge.ports
    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(IconId.Plug, 14.dp, Theme.Primary)
            Label("つながっているモジュール", 14f, Theme.TextMain, true, Modifier.weight(1f).padding(start = 8.dp))
            SmallButton("読み直す", Theme.Slate100, Theme.Slate600) { refresh() }
        }
        when {
            p == null -> Label(note.ifEmpty { "まだ読み込んでいません" }, 12f, Theme.Slate400)
            !p.ok && p.ports.isEmpty() -> Label("本体が差し込み口を読めていません", 12f, Theme.Slate400)
            p.ports.isEmpty() -> Label("何もつながっていません", 12f, Theme.TextSub)
            else -> for (m in p.ports) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(m.place, 11f, Theme.Slate400, true, Modifier.size(width = 44.dp, height = 18.dp))
                    Label(m.name.ifEmpty { m.kind.ifEmpty { "モジュール" } }, 13f, Theme.TextMain, true, Modifier.weight(1f), maxLines = 1)
                    val (txt, col) = when (m.state) {
                        "ok" -> "使える" to Theme.Green
                        "unknown" -> "知らない" to Theme.Slate400
                        "power" -> "電気不足" to Theme.hex(0xf59e0b)
                        "short", "error" -> "異常" to Theme.hex(0xef4444)
                        else -> m.state.ifEmpty { "?" } to Theme.Slate400
                    }
                    Label(txt, 11f, col, true)
                }
            }
        }
        if (p != null) Label("電気 %.1f / %.0f W".format(p.watts, p.budget), 10f, Theme.Slate400)
        if (note.isNotEmpty() && p != null) Label(note, 10f, Theme.Slate400)
    }
    Spacer(Modifier.height(10.dp))
}
