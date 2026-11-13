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
import com.aidy.petrobot.data.AidyDeviceLink
import com.aidy.petrobot.data.BodyColor
import com.aidy.petrobot.data.CalendarSettings
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleConfig
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.three.HomeRoom
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- PAGE 0: ホーム (3D ペットルーム)

private const val SummaryHeight = 58f
private const val SummaryMargin = 12f

/** いまのモジュールの一覧 (耳×2・尻尾…)。 */
fun moduleSummaryText(slots: Map<String, ModuleKind>): String {
    val counts = HashMap<ModuleKind, Int>()
    for (k in slots.values) counts[k] = (counts[k] ?: 0) + 1
    val parts = ModuleCatalog.All.mapNotNull { k -> counts[k]?.let { n -> if (n > 1) "${ModuleCatalog.name(k)}×$n" else ModuleCatalog.name(k) } }
    return if (parts.isNotEmpty()) parts.joinToString("・") else "なし (本体だけ)"
}

/** ホーム画面の 3D ルームの状態 (吹き出しの文字・マスコット・お知らせ)。 */
class HomeUi(val room: HomeRoom) {
    var stateText by mutableStateOf("こんにちは！")
    val mascot = MascotState()
    val toast = ToastState()

    init {
        room.onStatus = { stateText = it }
        room.onModelBadge = { ok, text -> toast.show(if (ok) AidyFace.Happy else AidyFace.Sleepy, text, if (ok) 2.8f else 0f) }
        room.onFaceReaction = { face, seconds -> mascot.react(face, seconds) }
        room.reportModelState()
    }
}

/** 1 画面で完結 (スクロールなし): 3D はページいっぱい、構成のまとめはその上に浮かべる。 */
@Composable
fun HomePage(ui: HomeUi, active: Boolean, onEdit: () -> Unit) {
    var showColor by remember { mutableStateOf(false) }
    if (showColor) {
        ColorPatternDialog(
            "Aidyの色",
            BodyColor.pattern,
            ui.room.rig.attached,
            onPreview = { ui.room.rig.applyPattern(it) },
            onConfirm = {
                BodyColor.pattern = it
                ui.room.rig.applyPattern(it)
                showColor = false
                ui.toast.show(AidyFace.Happy, "新しい色になったよ！", 2.2f)
            },
            onDismiss = {
                ui.room.rig.applyPattern(BodyColor.pattern) // 選んでいた色を戻す
                showColor = false
            },
        )
    }
    Box(Modifier.fillMaxSize().background(Theme.Device)) {
        View3D(
            ui.room, active, ui.stateText, ui.mascot, "タップでなでなで ・ ドラッグで回転 ・ ピンチでズーム",
            Modifier.fillMaxSize(),
            bottomInset = (SummaryHeight + SummaryMargin * 2f).dp,
            toast = ui.toast,
            toastTop = 52.dp,
        )
        // 右上: 本体の色を変える
        @Suppress("UNUSED_VARIABLE") val colorVersion = BodyColor.version
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .height(34.dp)
                .pressable(0.95f) { showColor = true }
                .box(Color.White.copy(alpha = 0.94f), 17.dp, 4.dp, Theme.Border)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PatternDot(BodyColor.pattern, 24.dp)
            Label("色", 12f, Theme.TextMain, true)
        }

        // いまのモジュール + お散歩 + 付け替えへ
        val summary = run {
            @Suppress("UNUSED_VARIABLE") val v = ModuleConfig.version
            moduleSummaryText(ModuleConfig.current)
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = SummaryMargin.dp)
                .fillMaxWidth()
                .height(SummaryHeight.dp)
                .box(Color.White.copy(alpha = 0.94f), 18.dp, 4.dp, Theme.Border)
                .consumeTaps()
                .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(IconId.Cubes, 14.dp, Theme.Primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Label("いまのモジュール", 10f, Theme.TextSub, true)
                Label(summary, 12f, Theme.TextMain, true, maxLines = 1)
            }
            Label("お散歩", 10f, Theme.Slate400, true)
            AidySwitch(ui.room.aiEnabled, 0.8f) { ui.room.toggleAIWalk(it) }
            Row(
                Modifier
                    .pressable(0.95f, onClick = onEdit)
                    .background(Theme.Primary, RoundedCornerShape(15.dp))
                    .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Label("付け替え", 12f, Color.White, true)
                Icon(IconId.ChevronRight, 10.dp, Color.White)
            }
        }
    }
}

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

        SectionTitle("標準モジュール (モデル合体済み)", false)
        Card(IconId.Snowflake, Theme.Primary, Theme.Slate100, "リビングエアコン", "温度: 24°C / 自動") { AidySwitch(true) }
        Card(IconId.Lightbulb, Theme.Primary, Theme.Slate100, "メイン照明", "オン / 100% 輝度") { AidySwitch(true) }
        Card(IconId.Plug, Theme.Primary, Theme.Slate100, "スマートプラグ", "オフ / タイマー設定") { AidySwitch(false) }

        SectionTitle("拡張モジュール (追加3Dモデル連携)", false)
        Card(IconId.Video, Theme.hex(0x8b5cf6), Theme.hex(0xf3e8ff), "カメラフィードモジュール", "拡張OBJモデル接続可能") { Icon(IconId.Plus, 16.dp, Theme.Primary) }
        Card(IconId.Microchip, Theme.hex(0xec4899), Theme.hex(0xfce7f3), "センサーモジュール", "温度/湿度 拡張モジュール") { Icon(IconId.Plus, 16.dp, Theme.Primary) }
    }
}

// ---------------------------------------------------------------- PAGE 4: 設定

@Composable
fun SettingsPage(diary: DiaryState, onOpenDiary: () -> Unit) {
    ScrollPage(Modifier.background(Theme.Device)) {
        SectionTitle("ロボット設定", true)
        Card(null, Color.Unspecified, Color.Unspecified, "アセンブリ構造", "本体v2_モデル.obj (透明化防止完全対応)") { Icon(IconId.Check, 14.dp, Theme.Green) }

        SectionTitle("日記", false)
        Card(null, Color.Unspecified, Color.Unspecified, "カレンダー表示", "週表示に切り替える") {
            AidySwitch(CalendarSettings.weekView) { on ->
                CalendarSettings.weekView = on
                diary.applyWeekView(on)
            }
        }

        SectionTitle("操作 & ネットワーク", false)
        Card(null, Color.Unspecified, Color.Unspecified, "モジュール自動合体", "パーツSocketアタッチ機能 (ON)") { AidySwitch(true) }
        Card(null, Color.Unspecified, Color.Unspecified, "Wi-Fi 接続", "AIDY-5G-2.4G (接続済み)") { Icon(IconId.Wifi, 16.dp, Theme.Green) }

        SectionTitle("Aidy本体と接続", false)
        val hostLabel = if (AidyDeviceLink.host.isEmpty()) "未設定（下で住所と合言葉を入れてね）" else AidyDeviceLink.host
        Card(IconId.Aidy, Theme.Primary, Theme.PrimaryLight, "Aidy本体の会話ログ", "$hostLabel から Wi-Fi で日記を同期", onClick = onOpenDiary) {
            Icon(IconId.ChevronRight, 12.dp, Theme.Slate400)
        }
        DeviceLinkCard()
        Label("音量・話す速さ・辞書はリモコンのページにあります", 11f, Theme.TextSub, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))

        SectionTitle("今後の開発予定", false)
        FutureFeatureList()
    }
}

// ---------------------------------------------------------------- 設定: Aidy本体と接続 (住所・合言葉)

/** 住所 (IP:ポート) と合言葉を、カメラでの QR 読み取りか手入力で設定し、/api/ping で接続を確かめるカード。 */
@Composable
private fun DeviceLinkCard() {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(AidyDeviceLink.host) }
    var token by remember { mutableStateOf(AidyDeviceLink.token) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (AidyDeviceLink.isConfigured) "保存済み。「保存して接続を試す」で確かめてね" else "まだ設定されていません") }
    var statusColor by remember { mutableStateOf(Theme.TextSub) }
    var showScanner by remember { mutableStateOf(false) }

    fun testConnect() {
        if (checking) return
        AidyDeviceLink.host = host
        AidyDeviceLink.token = token
        checking = true
        statusColor = Theme.Slate600
        status = "接続を確認中…"
        scope.launch {
            val r = AidyDeviceLink.ping()
            checking = false
            statusColor = if (r.ok) Theme.Green else Theme.hex(0xef4444)
            status = r.message
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onResult = { h, t ->
                host = h
                token = t
                showScanner = false
                testConnect()
            },
            onDismiss = { showScanner = false },
        )
    }

    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("本体のアドレス (IP:ポート)", 11f, Theme.TextSub, true)
        Input(host, { host = it.take(64) }, "例: 192.168.1.23:8090", false)
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
    Box(Modifier.fillMaxSize().background(Theme.Device)) {
        if (ui.arMode) ARCameraMode(ui, onMarkerButton)
        else {
            // ---- 画面上: 3D プレビューで面をタップ → モジュールをドラッグ&ドロップで付け替え
            View3D(
                ui.editRoom, active, ui.editStateText, ui.editMascot, "面をタップで選択 ・ ドラッグで回転 ・ ピンチでズーム",
                Modifier.fillMaxSize(),
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

