package com.aidy.petrobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aidy.petrobot.data.AidyDeviceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 辞書 (よくある質問・聞き間違い・読み) をその場で見て・足して・消せるフルスクリーンのダイアログ。
 * GET /api/dict ・ POST /api/add ・ POST /api/del を使う (仕様書 AIdy_アプリ連携_仕様.md 参照)。
 */
@Composable
fun DictManagerDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scope = rememberCoroutineScope()
        var tab by remember { mutableStateOf(0) }
        var dict by remember { mutableStateOf<AidyDeviceLink.DictData?>(null) }
        var status by remember { mutableStateOf("読み込み中…") }
        var statusColor by remember { mutableStateOf(Theme.TextSub) }
        var busy by remember { mutableStateOf(false) }

        fun reload() {
            status = "読み込み中…"
            statusColor = Theme.TextSub
            scope.launch {
                val r = AidyDeviceLink.fetchDict()
                if (r.ok) {
                    dict = r.dict
                    status = ""
                } else {
                    status = r.error ?: "読み込めませんでした"
                    statusColor = Theme.hex(0xef4444)
                }
            }
        }
        LaunchedEffect(Unit) { reload() }

        fun onUpdated(d: AidyDeviceLink.DictData) {
            dict = d
            status = ""
        }
        fun onError(message: String) {
            status = message
            statusColor = Theme.hex(0xef4444)
        }

        Column(Modifier.fillMaxSize().background(Theme.Device).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("辞書 (よくある質問・聞き間違い・読み)", 15f, Theme.TextMain, true, Modifier.weight(1f))
                SmallButton("閉じる", Theme.Slate100, Theme.Slate600, onDismiss)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DictTab("よくある質問", tab == 0, Modifier.weight(1f)) { tab = 0 }
                DictTab("聞き間違い", tab == 1, Modifier.weight(1f)) { tab = 1 }
                DictTab("読み", tab == 2, Modifier.weight(1f)) { tab = 2 }
            }
            if (status.isNotEmpty()) {
                Label(status, 12f, statusColor, true, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            ScrollPage {
                when (tab) {
                    0 -> FaqEditor(dict?.faq ?: emptyList(), busy, scope, { busy = it }, ::onUpdated, ::onError)
                    1 -> PairEditor("asr", "聞き間違い", "正しい言葉", dict?.asr ?: emptyList(), busy, scope, { busy = it }, ::onUpdated, ::onError)
                    else -> PairEditor("yomi", "書き方", "読み (ひらがな・カタカナ)", dict?.yomi ?: emptyList(), busy, scope, { busy = it }, ::onUpdated, ::onError)
                }
            }
        }
    }
}

@Composable
private fun DictTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(36.dp).pressable(0.95f, onClick = onClick).background(if (selected) Theme.Primary else Theme.Slate100, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Label(text, 12f, if (selected) Color.White else Theme.Slate600, true) }
}

@Composable
private fun FaqEditor(
    items: List<AidyDeviceLink.FaqEntry>,
    busy: Boolean,
    scope: CoroutineScope,
    setBusy: (Boolean) -> Unit,
    onUpdated: (AidyDeviceLink.DictData) -> Unit,
    onError: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) Label("まだ登録されていません", 12f, Theme.Slate400)
        items.forEachIndexed { idx, f ->
            Row(
                Modifier.fillMaxWidth().box(Color.White, 12.dp, 1.dp, Theme.Slate100).padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(f.q, 13f, Theme.TextMain, true)
                    Label(f.a, 12f, Theme.TextSub)
                }
                SmallButton("削除", Theme.hex(0xfee2e2), Theme.hex(0xef4444)) {
                    if (busy) return@SmallButton
                    setBusy(true)
                    scope.launch {
                        val r = AidyDeviceLink.deleteDictEntry("faq", idx = idx)
                        setBusy(false)
                        if (r.ok && r.dict != null) onUpdated(r.dict) else onError(r.error ?: "削除できませんでした")
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        var q by remember { mutableStateOf("") }
        var a by remember { mutableStateOf("") }
        Label("質問を追加", 11f, Theme.TextSub, true)
        Input(q, { q = it.take(100) }, "例: 何ができるの？", false)
        Input(a, { a = it.take(400) }, "答え (400文字まで)", true)
        SmallButton("追加", Theme.Primary, Color.White) {
            if (busy) return@SmallButton
            if (q.isBlank() || a.isBlank()) {
                onError("両方を入れてください")
                return@SmallButton
            }
            setBusy(true)
            scope.launch {
                val r = AidyDeviceLink.addDictEntry("faq", q.trim(), a.trim())
                setBusy(false)
                if (r.ok && r.dict != null) {
                    onUpdated(r.dict)
                    q = ""
                    a = ""
                } else onError(r.error ?: "追加できませんでした")
            }
        }
    }
}

@Composable
private fun PairEditor(
    kind: String,
    labelA: String,
    labelB: String,
    items: List<Pair<String, String>>,
    busy: Boolean,
    scope: CoroutineScope,
    setBusy: (Boolean) -> Unit,
    onUpdated: (AidyDeviceLink.DictData) -> Unit,
    onError: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) Label("まだ登録されていません", 12f, Theme.Slate400)
        items.forEach { (a, b) ->
            Row(
                Modifier.fillMaxWidth().box(Color.White, 12.dp, 1.dp, Theme.Slate100).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(a, 13f, Theme.TextMain, true)
                    Label("→ $b", 12f, Theme.TextSub)
                }
                SmallButton("削除", Theme.hex(0xfee2e2), Theme.hex(0xef4444)) {
                    if (busy) return@SmallButton
                    setBusy(true)
                    scope.launch {
                        val r = AidyDeviceLink.deleteDictEntry(kind, key = a)
                        setBusy(false)
                        if (r.ok && r.dict != null) onUpdated(r.dict) else onError(r.error ?: "削除できませんでした")
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        Label(labelA, 11f, Theme.TextSub, true)
        Input(a, { a = it.take(100) }, "", false)
        Label(labelB, 11f, Theme.TextSub, true)
        Input(b, { b = it.take(100) }, "", false)
        SmallButton("追加", Theme.Primary, Color.White) {
            if (busy) return@SmallButton
            if (a.isBlank() || b.isBlank()) {
                onError("両方を入れてください")
                return@SmallButton
            }
            setBusy(true)
            scope.launch {
                val r = AidyDeviceLink.addDictEntry(kind, a.trim(), b.trim())
                setBusy(false)
                if (r.ok && r.dict != null) {
                    onUpdated(r.dict)
                    a = ""
                    b = ""
                } else onError(r.error ?: "追加できませんでした")
            }
        }
    }
}
