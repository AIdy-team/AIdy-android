package com.aidy.petrobot.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.SliderDefaults
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
import com.aidy.petrobot.data.VoiceStore
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 話す速さの言葉 (1.0 = ふつう)。 */
private fun speedLabel(s: Float) = when {
    s < 0.8f -> "ゆっくり"
    s < 0.95f -> "少しゆっくり"
    s <= 1.05f -> "ふつう"
    s <= 1.3f -> "少しはやい"
    else -> "はやい"
}

/**
 * リモコン: 本体の音量・話す速さ (GET/POST /api/settings)。
 * ページが見えたら本体から読み込み、スライダーを離したら本体へ送る。つながっていなくても端末には覚えておく。
 */
@Composable
fun VoiceSettingsCard(visible: Boolean) {
    val scope = rememberCoroutineScope()
    var volume by remember { mutableFloatStateOf(VoiceStore.volume.toFloat()) }
    var speed by remember { mutableFloatStateOf(VoiceStore.speed) }
    var status by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Theme.TextSub) }
    var busy by remember { mutableStateOf(false) }

    fun show(r: AidyDeviceLink.SettingsResult, okText: String) {
        r.settings?.let {
            volume = it.volume.toFloat()
            speed = it.speed
        }
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

    fun send() {
        busy = true
        status = "本体に送信中…"
        statusColor = Theme.Slate600
        val v = AidyDeviceLink.VoiceSettings(volume.roundToInt(), (speed * 20f).roundToInt() / 20f)
        scope.launch {
            show(AidyDeviceLink.saveSettings(v), "本体に反映しました")
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
                Label("Aidyの声", 14f, Theme.TextMain, true)
                Label("音量と話す速さを本体に送る", 12f, Theme.TextSub)
            }
            SmallButton("読み込む", Theme.Slate100, Theme.Slate600) { load() }
        }

        SliderRow("音量", "${volume.roundToInt()}%", volume, 0f..100f, 19, { volume = it }, { send() })
        SliderRow("話す速さ", "${speedLabel(speed)} (×${"%.2f".format(speed)})", speed, VoiceStore.MinSpeed..VoiceStore.MaxSpeed, 19, { speed = it }, { send() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("ゆっくり", 10f, Theme.Slate400)
            Label("ふつう ×1.0", 10f, Theme.Slate400)
            Label("はやい", 10f, Theme.Slate400)
        }
        if (status.isNotEmpty()) Label(status, 11f, statusColor, true)
    }
    Spacer(Modifier.height(10.dp))
}

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
            colors = SliderDefaults.colors(
                thumbColor = Theme.Primary,
                activeTrackColor = Theme.Primary,
                inactiveTrackColor = Theme.Slate300,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}
