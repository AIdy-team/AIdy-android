package com.aidy.petrobot.ui

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.data.AidyMarkers
import com.aidy.petrobot.data.BodyColor
import com.aidy.petrobot.data.ColorPattern
import com.aidy.petrobot.data.MarkerImage
import com.aidy.petrobot.data.MarkerStore
import com.aidy.petrobot.data.ModuleConfig

/**
 * 着せ替えマーカーの作成画面 (下から出るシート、Unity 版 AidyMarkerSheet)。いまの着せ替えを空いている ID のマーカーとして保存し、
 * マーカーの画像を表示・保存する。ID0 (いまの着せ替えにいつも追従) もここで表示・保存できる。
 */
class MarkerSheetState {
    var isOpen by mutableStateOf(false)
        private set
    var selected by mutableIntStateOf(AidyMarkers.MyId)
    var message by mutableStateOf("")
    /** マーカーの一覧が変わったら増やす。 */
    var version by mutableIntStateOf(0)
    /** 新しく作るマーカーの Aidy の色 (開いたときはいまの色)。 */
    var newColor by mutableStateOf(BodyColor.pattern)
    var showColor by mutableStateOf(false)

    /** 選んでいるマーカーの色の行に出す色。「いまの着せ替え」を選んでいるときは、これから作るマーカーの色。 */
    val shownColor: ColorPattern get() = if (selected == AidyMarkers.MyId) newColor else AidyMarkers.color(selected)

    /** 色を決めたとき。作ったマーカーならその色を保存、「いまの着せ替え」ならこれから作るマーカーの色にする。 */
    fun pickColor(c: ColorPattern) {
        showColor = false
        if (selected == AidyMarkers.MyId) {
            newColor = c
            message = "この色で「作成」するとマーカーに保存されるよ"
        } else {
            MarkerStore.setColor(selected, c)
            version++
            message = "マーカー #$selected の色を変えたよ"
        }
    }

    /** 開く。いまの着せ替えと同じマーカーがあればそれを、なければ ID0 を選ぶ。 */
    fun open() {
        isOpen = true
        message = ""
        newColor = BodyColor.pattern
        showColor = false
        selected = MarkerStore.find(ModuleConfig.current, newColor) ?: AidyMarkers.MyId
    }

    fun close() {
        isOpen = false
    }

    fun createFromCurrent() {
        val same = MarkerStore.find(ModuleConfig.current, newColor)
        if (same != null) {
            selected = same
            message = "同じ着せ替え・同じ色のマーカーがもうあるよ (#$same)"
            return
        }
        val id = MarkerStore.create(ModuleConfig.current, newColor)
        if (id == null) {
            message = "マーカーがいっぱいです。使わないマーカーを削除してね"
            return
        }
        version++
        selected = id
        message = "マーカー #$id を作ったよ！ 画像を保存して印刷してね"
    }

    fun saveImage() {
        message = try {
            "保存しました: " + MarkerImage.savePng(selected)
        } catch (e: Exception) {
            Log.e("Aidy", "マーカー画像の保存に失敗", e)
            "保存に失敗しました"
        }
    }

    fun deleteSelected() {
        if (selected == AidyMarkers.MyId) return
        val id = selected
        MarkerStore.delete(id)
        version++
        selected = AidyMarkers.MyId
        message = "マーカー #$id を削除しました"
    }
}

@Composable
fun MarkerSheet(state: MarkerSheetState) {
    if (!state.isOpen) return
    val id = state.selected
    if (state.showColor) {
        ColorPatternDialog(
            if (id == AidyMarkers.MyId) "作るマーカーのAidyの色" else "マーカー #$id のAidyの色",
            state.shownColor,
            if (id == AidyMarkers.MyId) ModuleConfig.current else (AidyMarkers.config(id) ?: emptyMap()),
            onConfirm = { state.pickColor(it) },
            onDismiss = { state.showColor = false },
        )
    }
    // 着せ替えが変わったら ID0 の説明も変わる
    val modulesVersion = ModuleConfig.version
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pressable { state.close() })
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth()
                .box(Color.White, 22.dp, 12.dp)
                .consumeTaps()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 見出し
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(IconId.Camera, 14.dp, Theme.Primary)
                Label("着せ替えマーカー", 15f, Theme.TextMain, true, Modifier.weight(1f))
                Box(Modifier.size(26.dp).background(Theme.Slate100, RoundedCornerShape(13.dp)).pressable { state.close() }, contentAlignment = Alignment.Center) {
                    Icon(IconId.XMark, 11.dp, Theme.TextSub)
                }
            }

            // マーカーの一覧 (横スクロール)
            Row(Modifier.fillMaxWidth().height(32.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                @Suppress("UNUSED_VARIABLE") val v = state.version
                Chip(state, AidyMarkers.MyId, "いまの着せ替え")
                for (m in MarkerStore.ids) Chip(state, m, "#$m")
            }

            // マーカーの画像と説明
            Row(Modifier.fillMaxWidth().height(136.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val img = remember(id, state.version) { MarkerImage.create(id, 16).asImageBitmap() }
                Box(Modifier.size(136.dp).box(Color.White, 8.dp, border = Theme.Border).padding(4.dp)) {
                    Image(img, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                }
                val live = id == AidyMarkers.MyId
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label(AidyMarkers.name(id) + "  (ID$id)", 15f, Theme.TextMain, true)
                    val slots = remember(id, modulesVersion, state.version) { if (live) ModuleConfig.current else AidyMarkers.config(id) }
                    Label(if (slots != null) ModuleConfig.summary(slots) else "", 12f, Theme.TextSub)
                    Label(
                        if (live) "いつもいまの着せ替え・いまの色で出るマーカー。黒い四角を${AidyMarkers.sizeMm(id)}mmで印刷してね"
                        else "作ったときの着せ替え・色で出るマーカー (この端末だけ)。黒い四角を${AidyMarkers.sizeMm(id)}mmで印刷してね",
                        11f, Theme.Primary,
                    )
                }
            }

            // 色 (マーカーごとに保存)
            @Suppress("UNUSED_VARIABLE") val colorVersion = BodyColor.version
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Theme.Slate100, RoundedCornerShape(14.dp))
                    .pressable(0.98f) { state.showColor = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PatternDot(state.shownColor, 30.dp)
                Column(Modifier.weight(1f)) {
                    Label(if (id == AidyMarkers.MyId) "作るマーカーのAidyの色" else "このマーカーのAidyの色", 12f, Theme.TextMain, true)
                    Label("本体 " + BodyColor.hex(state.shownColor.body) + "  ・ タップで変更", 11f, Theme.TextSub)
                }
                Icon(IconId.ChevronRight, 12.dp, Theme.Slate400)
            }

            // ボタン
            Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton(IconId.Plus, "いまの着せ替えで作成", true, Modifier.weight(1f)) { state.createFromCurrent() }
                SheetButton(IconId.Check, "画像を保存", false, Modifier.weight(1f)) { state.saveImage() }
                if (id != AidyMarkers.MyId) SheetButton(IconId.XMark, "削除", false, Modifier.weight(0.5f)) { state.deleteSelected() }
            }
            if (state.message.isNotEmpty()) Label(state.message, 11f, Theme.TextSub, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
    }
}

@Composable
private fun Chip(state: MarkerSheetState, id: Int, text: String) {
    val on = id == state.selected
    Box(
        Modifier
            .height(30.dp)
            .pressable {
                state.message = ""
                state.selected = id
            }
            .background(if (on) Theme.Primary else Theme.Slate100, RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, 12f, if (on) Color.White else Theme.Slate700, true) }
}

@Composable
private fun SheetButton(icon: IconId, text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (primary) Color.White else Theme.Slate700
    Row(
        modifier
            .height(42.dp)
            .pressable(0.97f, onClick = onClick)
            .background(if (primary) Theme.Primary else Theme.Slate100, RoundedCornerShape(21.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, 11.dp, fg)
        Label(text, 12f, fg, true, maxLines = 1)
    }
}
