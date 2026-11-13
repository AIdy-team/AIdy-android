package com.aidy.petrobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.data.AidyDeviceLink
import com.aidy.petrobot.data.SettingsStore
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 本体の画面のどのページの設定か (AIdy_アプリ連携_仕様.md の表)。知らないキーは「その他」。 */
private fun settingGroup(key: String) = when (key) {
    "voice_db", "pop", "alarm" -> "音量"
    "speed", "mic", "gun" -> "速さ・耳"
    "talk", "brief", "night", "react" -> "動き"
    else -> "その他"
}

/**
 * リモコン: 本体の設定 (GET/POST /api/settings)。
 * 本体が返す items の並び・label・max をそのまま使って段階のスライダーを出す。
 * ページが見えたら本体から読み込み、スライダーを離したら {"key", "level"} を送る (返事の一覧で描き直す)。
 */
@Composable
fun VoiceSettingsCard(visible: Boolean) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(SettingsStore.items) }
    var status by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Theme.TextSub) }
    var busy by remember { mutableStateOf(false) }

    fun show(r: AidyDeviceLink.SettingsResult, okText: String) {
        items = r.items
        status = if (r.ok) okText else r.error ?: "できませんでした"
        statusColor = when {
            r.ok -> Theme.Green
            r.unsupported || !AidyDeviceLink.isConfigured -> Theme.TextSub
            else -> Theme.hex(0xef4444)
        }
    }

    fun load() {
        if (busy) return
        busy = true
        status = "本体の設定を読み込み中…"
        statusColor = Theme.Slate600
        scope.launch {
            show(AidyDeviceLink.fetchSettings(), "本体の設定を読み込みました")
            busy = false
        }
    }

    fun send(key: String, level: Int) {
        busy = true
        status = "本体に送信中…"
        statusColor = Theme.Slate600
        scope.launch {
            show(AidyDeviceLink.saveSetting(key, level), "本体に反映しました")
            busy = false
        }
    }

    LaunchedEffect(visible) { if (visible) load() }

    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(42.dp).background(Theme.PrimaryLight, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(IconId.Sliders, 18.dp, Theme.Primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("Aidyの設定", 14f, Theme.TextMain, true)
                Label("声の大きさ・話す速さ・動きを本体に送る", 12f, Theme.TextSub)
            }
            SmallButton("読み込む", Theme.Slate100, Theme.Slate600) { load() }
        }

        if (items.isEmpty()) {
            Label("本体とつながると、ここに本体の設定が出ます", 12f, Theme.TextSub, modifier = Modifier.padding(top = 6.dp))
        }
        var lastGroup = ""
        items.forEach { item ->
            val group = settingGroup(item.key)
            if (group != lastGroup) {
                lastGroup = group
                Label(group, 11f, Theme.Slate400, true, Modifier.padding(top = 8.dp))
            }
            SettingRow(item, enabled = !busy) { level -> if (level != item.level) send(item.key, level) }
        }
        if (status.isNotEmpty()) Label(status, 11f, statusColor, true)
    }
    Spacer(Modifier.height(10.dp))
}

/** 設定 1 項目: 名前 + 今の段階 (show の文字か「n / max」) + 0〜max の段階スライダー。 */
@Composable
private fun SettingRow(item: AidyDeviceLink.SettingItem, enabled: Boolean, onDone: (Int) -> Unit) {
    var v by remember(item.key, item.level, item.max) { mutableFloatStateOf(item.level.toFloat()) }
    val level = v.roundToInt()
    val shown = if (level == item.level && item.show.isNotEmpty()) item.show else "$level / ${item.max}"
    if (item.max <= 0) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(item.label, 13f, Theme.TextMain, true, Modifier.weight(1f))
            Label(item.show.ifEmpty { "-" }, 13f, Theme.Primary, true)
        }
        return
    }
    SliderRow(item.label, shown, v, 0f..item.max.toFloat(), (item.max - 1).coerceAtLeast(0), { if (enabled) v = it }, { onDone(v.roundToInt()) })
}

/** 丸いつまみのスライダー (つまみ = 白い丸 + 青い縁、線は太めの角丸)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(
    title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(title, 13f, Theme.TextMain, true, Modifier.weight(1f))
            Label(value, 13f, Theme.Primary, true)
        }
        Slider(
            value = v,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            thumb = {
                Box(
                    Modifier
                        .size(26.dp)
                        .shadow(3.dp, CircleShape)
                        .background(Color.White, CircleShape)
                        .border(3.dp, Theme.Primary, CircleShape),
                )
            },
            track = { state ->
                val f = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(Theme.Slate100, cornerRadius = r)
                    drawRoundRect(Theme.Primary, size = Size(size.width * f, size.height), cornerRadius = r)
                }
            },
        )
    }
}
