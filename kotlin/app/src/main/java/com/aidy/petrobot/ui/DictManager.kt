package com.aidy.petrobot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aidy.petrobot.data.AidyDeviceLink
import kotlinx.coroutines.launch

/** 辞書の種類ごとの見た目と説明。 */
private class DictKind(
    val kind: String,
    val title: String,
    val short: String,
    val help: String,
    val labelA: String,
    val labelB: String,
    val hintA: String,
    val hintB: String,
    val color: Color,
    val bg: Color,
)

private val Kinds = listOf(
    DictKind(
        "faq", "よくある質問", "質問",
        "聞かれたら、決まった答えをそのまま話します",
        "質問", "答え", "例: 何ができるの？", "例: お話ししたり、予定を教えたりできるよ (400文字まで)",
        Theme.hex(0x8b5cf6), Theme.hex(0xf3e8ff),
    ),
    DictKind(
        "asr", "聞き間違い", "聞き間違い",
        "よく聞き間違える言葉を、正しい言葉に直してから考えます",
        "聞き間違えた言葉", "正しい言葉", "例: あいでぃ", "例: Aidy",
        Theme.hex(0xf97316), Theme.hex(0xffedd5),
    ),
    DictKind(
        "yomi", "読み", "読み",
        "漢字や英語を、ここで決めた読み方で話します",
        "書き方", "読み (ひらがな・カタカナ)", "例: 明日", "例: あした",
        Theme.hex(0x10b981), Theme.hex(0xd1fae5),
    ),
)

/** 1 件分 (表示用)。key は削除に使う (faq は番号、ほかは書き方)。 */
private class DictItem(val a: String, val b: String, val key: String?, val idx: Int?)

/**
 * 辞書 (よくある質問・聞き間違い・読み) を見て・探して・足して・消せるフルスクリーンの画面。
 * GET /api/dict ・ POST /api/add ・ POST /api/del を使う (仕様書 AIdy_アプリ連携_仕様.md 参照)。
 */
@Composable
fun DictManagerDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scope = rememberCoroutineScope()
        var tab by remember { mutableIntStateOf(0) }
        var dict by remember { mutableStateOf<AidyDeviceLink.DictData?>(null) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var toast by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var adding by remember { mutableStateOf(false) }
        var confirmKey by remember { mutableStateOf<String?>(null) }

        fun reload() {
            loading = true
            error = null
            scope.launch {
                val r = AidyDeviceLink.fetchDict()
                loading = false
                if (r.ok) dict = r.dict else error = r.error ?: "読み込めませんでした"
            }
        }
        LaunchedEffect(Unit) { reload() }

        fun itemsOf(i: Int): List<DictItem> {
            val d = dict ?: return emptyList()
            return when (i) {
                0 -> d.faq.mapIndexed { n, f -> DictItem(f.q, f.a, null, n) }
                1 -> d.asr.map { (a, b) -> DictItem(a, b, a, null) }
                else -> d.yomi.map { (a, b) -> DictItem(a, b, a, null) }
            }
        }

        val k = Kinds[tab]

        Box(Modifier.fillMaxSize().background(Theme.Device)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // ヘッダー
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(40.dp).pressable(0.9f, onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Icon(IconId.ChevronLeft, 16.dp, Theme.Slate600)
                    }
                    AidyMascot(36.dp, if (error != null) AidyFace.Sleepy else AidyFace.Normal)
                    Column(Modifier.weight(1f)) {
                        Label("Aidyの辞書", 17f, Theme.TextMain, true)
                        Label("本体の聞き取り・話し方・答えをカスタマイズ", 11f, Theme.TextSub)
                    }
                    Box(Modifier.size(36.dp).pressable(0.9f) { reload() }.background(Theme.Slate100, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(IconId.Wifi, 14.dp, Theme.Slate600)
                    }
                }

                // 種類のタブ (件数つき)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kinds.forEachIndexed { i, kk ->
                        KindTab(kk, if (dict != null) itemsOf(i).size else null, tab == i, Modifier.weight(1f)) {
                            tab = i
                            query = ""
                            confirmKey = null
                        }
                    }
                }

                // 説明 + 検索
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().background(k.bg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(IconId.Lightbulb, 12.dp, k.color)
                        Label(k.help, 11f, k.color, true)
                    }
                    if (dict != null && itemsOf(tab).size > 3) Input(query, { query = it.take(40) }, "🔍 ${k.short}を探す", false)
                }

                // 一覧
                Box(Modifier.weight(1f)) {
                    when {
                        loading && dict == null -> CenterNote(AidyFace.Normal, "本体から辞書を読み込み中…", null, null)
                        error != null && dict == null -> CenterNote(AidyFace.Sleepy, error!!, "もう一度読み込む") { reload() }
                        else -> {
                            val all = itemsOf(tab)
                            val q = query.trim()
                            val shown = if (q.isEmpty()) all else all.filter { it.a.contains(q, true) || it.b.contains(q, true) }
                            if (all.isEmpty()) CenterNote(AidyFace.Happy, "まだ${k.title}はありません\n右下の「＋ 追加」から登録してね", null, null)
                            else ScrollPage {
                                if (shown.isEmpty()) Label("「$q」に当てはまるものはありません", 12f, Theme.Slate400, modifier = Modifier.padding(8.dp))
                                for (item in shown) {
                                    val id = "${k.kind}:${item.key ?: item.idx}"
                                    DictRow(k, item, confirmKey == id, busy,
                                        onAskDelete = { confirmKey = if (confirmKey == id) null else id },
                                        onDelete = {
                                            busy = true
                                            scope.launch {
                                                val r = AidyDeviceLink.deleteDictEntry(k.kind, key = item.key, idx = item.idx)
                                                busy = false
                                                confirmKey = null
                                                if (r.ok && r.dict != null) {
                                                    dict = r.dict
                                                    toast = "「${item.a}」を削除しました"
                                                } else toast = r.error ?: "削除できませんでした"
                                            }
                                        })
                                }
                                Spacer(Modifier.height(80.dp)) // 追加ボタンの分
                            }
                        }
                    }
                }
            }

            // 右下: 追加ボタン
            if (dict != null && !adding) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(20.dp)
                        .height(52.dp)
                        .pressable(0.95f) { adding = true }
                        .box(k.color, 26.dp, 6.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(IconId.Plus, 14.dp, Color.White)
                    Label("${k.short}を追加", 14f, Color.White, true)
                }
            }

            // お知らせ
            if (toast.isNotEmpty()) {
                LaunchedEffect(toast) {
                    kotlinx.coroutines.delay(2500)
                    toast = ""
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 86.dp)
                        .background(Theme.Slate700.copy(alpha = 0.92f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Label(toast, 12f, Color.White, true) }
            }

            // 追加のシート
            AnimatedVisibility(adding, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pressable { adding = false })
            }
            AnimatedVisibility(
                adding, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it }, exit = slideOutVertically { it },
            ) {
                AddSheet(k, busy, onCancel = { adding = false }) { a, b, setError ->
                    busy = true
                    scope.launch {
                        val r = AidyDeviceLink.addDictEntry(k.kind, a, b)
                        busy = false
                        if (r.ok && r.dict != null) {
                            dict = r.dict
                            adding = false
                            toast = "「$a」を登録しました"
                        } else setError(r.error ?: "追加できませんでした")
                    }
                }
            }
        }
    }
}

@Composable
private fun KindTab(k: DictKind, count: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .pressable(0.95f, onClick = onClick)
            .background(if (selected) k.color else Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) k.color else Theme.Slate100, RoundedCornerShape(14.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Label(k.title, 12f, if (selected) Color.White else Theme.Slate700, true)
        Label(count?.let { "${it}件" } ?: "–", 10f, if (selected) Color.White.copy(alpha = 0.85f) else Theme.Slate400, true)
    }
}

/** 1 件。質問は吹き出し 2 つ、聞き間違い・読みは「左 → 右」のチップ。ゴミ箱で確認してから消す。 */
@Composable
private fun DictRow(k: DictKind, item: DictItem, confirming: Boolean, busy: Boolean, onAskDelete: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (k.kind == "faq") {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bubble("Q", item.a, Theme.Slate100, Theme.TextMain, k.color, false)
                    Bubble("A", item.b, k.bg, Theme.Slate700, k.color, true)
                }
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(item.a, Theme.Slate100, Theme.TextMain, Modifier.weight(1f, fill = false))
                    Icon(IconId.ChevronRight, 10.dp, k.color)
                    Chip(item.b, k.bg, k.color, Modifier.weight(1f, fill = false))
                }
            }
            Box(
                Modifier.size(32.dp).pressable(0.9f, onClick = onAskDelete)
                    .background(if (confirming) Theme.hex(0xfee2e2) else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(IconId.XMark, 12.dp, if (confirming) Theme.hex(0xef4444) else Theme.Slate400) }
        }
        if (confirming) {
            Row(
                Modifier.fillMaxWidth().background(Theme.hex(0xfef2f2), RoundedCornerShape(10.dp)).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label("本体の辞書から削除しますか？", 11f, Theme.hex(0xb91c1c), true, Modifier.weight(1f))
                SmallButton("やめる", Color.White, Theme.Slate600, onAskDelete)
                SmallButton(if (busy) "削除中…" else "削除", Theme.hex(0xef4444), Color.White) { if (!busy) onDelete() }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Bubble(tag: String, text: String, bg: Color, fg: Color, tagColor: Color, filled: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(22.dp).background(if (filled) tagColor else Color.White, CircleShape).border(1.5.dp, tagColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Label(tag, 11f, if (filled) Color.White else tagColor, true) }
        Box(Modifier.background(bg, RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Label(text, 12f, fg, tag == "Q")
        }
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Box(modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Label(text, 13f, fg, true, maxLines = 2)
    }
}

/** 一覧の代わりに真ん中に出す案内 (読み込み中・つながらない・空)。 */
@Composable
private fun CenterNote(face: AidyFace, text: String, button: String?, onButton: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AidyMascot(72.dp, face)
        Spacer(Modifier.height(12.dp))
        Label(text, 13f, Theme.TextSub, true, Modifier.widthIn(max = 280.dp), align = TextAlign.Center)
        if (button != null && onButton != null) {
            Spacer(Modifier.height(14.dp))
            SmallButton(button, Theme.Primary, Color.White, onButton)
        }
    }
}

/** 下から出る追加のシート。プレビューで登録後の見え方を出す。 */
@Composable
private fun AddSheet(k: DictKind, busy: Boolean, onCancel: () -> Unit, onAdd: (String, String, (String) -> Unit) -> Unit) {
    var a by remember(k.kind) { mutableStateOf("") }
    var b by remember(k.kind) { mutableStateOf("") }
    var err by remember(k.kind) { mutableStateOf("") }
    val maxB = if (k.kind == "faq") 400 else 100
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .pressable { } // 下の暗い所へタップを通さない
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).background(Theme.Slate300, RoundedCornerShape(2.dp)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(28.dp).background(k.bg, CircleShape), contentAlignment = Alignment.Center) { Icon(IconId.Plus, 12.dp, k.color) }
            Label("${k.title}を追加", 16f, Theme.TextMain, true)
        }
        Label(k.labelA, 11f, Theme.TextSub, true)
        Input(a, { a = it.take(100); err = "" }, k.hintA, false)
        Label(k.labelB + if (k.kind == "faq") "  ${b.length}/$maxB" else "", 11f, Theme.TextSub, true)
        Input(b, { b = it.take(maxB); err = "" }, k.hintB, k.kind == "faq")
        if (a.isNotBlank() && b.isNotBlank()) {
            Label("こうなります", 10f, Theme.Slate400, true)
            if (k.kind == "faq") Label("「${a.trim()}」と聞かれたら →「${b.trim()}」と答える", 12f, k.color, true)
            else if (k.kind == "asr") Label("「${a.trim()}」と聞こえたら →「${b.trim()}」として考える", 12f, k.color, true)
            else Label("「${a.trim()}」は →「${b.trim()}」と読む", 12f, k.color, true)
        }
        if (err.isNotEmpty()) Label(err, 12f, Theme.hex(0xef4444), true)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(46.dp).pressable(0.97f, onClick = onCancel).background(Theme.Slate100, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) { Label("キャンセル", 13f, Theme.Slate700, true) }
            Box(
                Modifier.weight(1f).height(46.dp).pressable(0.97f) {
                    if (busy) return@pressable
                    if (a.isBlank() || b.isBlank()) err = "両方を入れてください" else onAdd(a.trim(), b.trim()) { err = it }
                }.background(k.color, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) { Label(if (busy) "送信中…" else "本体に登録", 13f, Color.White, true) }
        }
    }
}
