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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aidy.petrobot.data.AidyDeviceLink
import com.aidy.petrobot.data.BodyBridge
import com.aidy.petrobot.data.FaceLink
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun day(sec: Long) = if (sec <= 0) "" else SimpleDateFormat("M/d", Locale.JAPAN).format(Date(sec * 1000))

// ---------------------------------------------------------------- Aidy の名前

/** Aidy の名前 (本体の呼び名と読み)。 */
@Composable
fun AidyNameCard() {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var yomi by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(BodyBridge.facesVersion, AidyDeviceLink.isConfigured) {
        if (!AidyDeviceLink.isConfigured) { note = "本体とつながっていません"; return@LaunchedEffect }
        try {
            FaceLink.loadAidyName()
            name = FaceLink.aidyName; yomi = FaceLink.aidyYomi; loaded = true; note = ""
        } catch (e: Exception) { note = e.message ?: "" }
    }
    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(IconId.Aidy, 14.dp, Theme.Primary)
            Label("Aidyの名前", 14f, Theme.TextMain, true, Modifier.padding(start = 8.dp))
        }
        Label("本体がこの名前で自己紹介し、呼ばれたと分かるようになります", 11f, Theme.TextSub)
        Input(name, { name = it.take(12) }, "名前 (例: Aidy)", false)
        Input(yomi, { yomi = it.take(16) }, "読み (カタカナ。例: アイディ)", false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(note, 11f, if (note.startsWith("保存")) Theme.Green else Theme.Slate400, modifier = Modifier.weight(1f))
            SmallButton("保存", Theme.Primary, Color.White) {
                if (name.isBlank()) { note = "名前を入れてね"; return@SmallButton }
                scope.launch {
                    note = try { FaceLink.saveAidyName(name.trim(), yomi.trim()); "保存しました" } catch (e: Exception) { e.message ?: "保存できませんでした" }
                }
            }
        }
        if (!loaded && note.isEmpty()) Label("読み込み中…", 11f, Theme.Slate400)
    }
    Spacer(Modifier.height(10.dp))
}

// ---------------------------------------------------------------- 顔で見分ける人

/** 本体が顔で見分ける人の一覧。タップで「どんな人か」の確認・編集・削除、＋ でスマホのカメラで登録。 */
@Composable
fun FacePeopleCard() {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FaceLink.Person?>(null) }
    var enroll by remember { mutableStateOf<Triple<String, String, FaceLink.Profile>?>(null) }

    fun reload() {
        if (!AidyDeviceLink.isConfigured) { note = "本体とつながっていません"; return }
        scope.launch { note = try { FaceLink.refresh(); "" } catch (e: Exception) { e.message ?: "" } }
    }
    LaunchedEffect(BodyBridge.facesVersion, AidyDeviceLink.isConfigured) { reload() }

    if (adding) PersonFormDialog(null, onDismiss = { adding = false }) { n, y, p ->
        adding = false
        enroll = Triple(n, y, p)
    }
    enroll?.let { (n, y, p) ->
        FaceEnrollDialog(n, y, p, onDone = { enroll = null; note = "${n}さんを登録しました"; reload() }, onDismiss = { enroll = null; reload() })
    }
    editing?.let { person ->
        PersonFormDialog(person, onDismiss = { editing = null }) { n, y, p ->
            editing = null
            scope.launch { note = try { FaceLink.update(person.id, n, y, p); "保存しました" } catch (e: Exception) { e.message ?: "" } }
        }
    }

    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(IconId.Aidy, 14.dp, Theme.Primary)
            Label("顔で見分ける人", 14f, Theme.TextMain, true, Modifier.weight(1f).padding(start = 8.dp))
            SmallButton("読み直す", Theme.Slate100, Theme.Slate600) { reload() }
            Spacer(Modifier.size(6.dp))
            SmallButton("＋ 登録", Theme.Primary, Color.White) { adding = true }
        }
        Label("スマホの前のカメラで、本体と同じ手順 (正面・横・反対の横・上・下・正面) で顔を登録します", 11f, Theme.TextSub)
        if (FaceLink.people.isEmpty()) Label(if (FaceLink.supported == false) "本体がまだこの機能に対応していません" else "登録している人はいません", 12f, Theme.Slate400)
        for (p in FaceLink.people) {
            Row(
                Modifier.fillMaxWidth().pressable(0.98f) { editing = p }.background(Theme.Device, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(34.dp).background(if (p.kind == "member") Theme.PrimaryLight else Theme.Slate100, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                    Label(p.name.take(1), 15f, if (p.kind == "member") Theme.Primary else Theme.Slate600, true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Label(p.name + (if (p.yomi.isNotEmpty() && p.yomi != p.name) " (${p.yomi})" else ""), 13f, Theme.TextMain, true, maxLines = 1)
                    val sub = listOfNotNull(
                        if (p.kind == "member") "登録" else "声で覚えた",
                        p.profile.relation.ifEmpty { null },
                        "${p.count}枚",
                        day(p.seen).ifEmpty { null }?.let { "最後に見た $it" },
                    ).joinToString(" ・ ")
                    Label(sub, 10f, Theme.TextSub, maxLines = 1)
                }
                Icon(IconId.ChevronRight, 10.dp, Theme.Slate400)
            }
        }
        if (note.isNotEmpty()) Label(note, 11f, if (note.endsWith("ました")) Theme.Green else Theme.Slate400)
    }
    Spacer(Modifier.height(10.dp))
}

/** 人の情報の入力 (新しく登録 / 確認・編集・削除)。削除は必ずもう一度確かめる。 */
@Composable
private fun PersonFormDialog(person: FaceLink.Person?, onDismiss: () -> Unit, onSave: (String, String, FaceLink.Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(person?.name.orEmpty()) }
    var yomi by remember { mutableStateOf(person?.yomi.orEmpty()) }
    var relation by remember { mutableStateOf(person?.profile?.relation.orEmpty()) }
    var birthday by remember { mutableStateOf(person?.profile?.birthday.orEmpty()) }
    var likes by remember { mutableStateOf(person?.profile?.likes.orEmpty()) }
    var memo by remember { mutableStateOf(person?.profile?.memo.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && person != null) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Column(
                Modifier.fillMaxWidth().box(Color.White, 20.dp, 8.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Label("${person.name}さんを消しますか？", 16f, Theme.TextMain, true, align = TextAlign.Center)
                Label("本体から顔の情報 (${person.count}枚分) と、どんな人かのメモが消えます。元に戻せません。", 12f, Theme.TextSub, align = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton("やめる", Theme.Slate100, Theme.Slate600) { confirmDelete = false }
                    SmallButton("消す", Theme.hex(0xef4444), Color.White) {
                        scope.launch {
                            try {
                                FaceLink.delete(person.id)
                                confirmDelete = false
                                onDismiss()
                            } catch (e: Exception) {
                                confirmDelete = false
                                error = e.message ?: "消せませんでした"
                            }
                        }
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().box(Color.White, 20.dp, 8.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(if (person == null) "顔を登録する人" else "${person.name}さん", 16f, Theme.TextMain, true)
            if (person != null) {
                Label(
                    (if (person.kind == "member") "登録した人" else "声の自己紹介で覚えた人") + " ・ 顔 ${person.count}枚" +
                        (day(person.made).ifEmpty { null }?.let { " ・ 登録 $it" } ?: "") + (day(person.seen).ifEmpty { null }?.let { " ・ 最後に見た $it" } ?: ""),
                    11f, Theme.TextSub,
                )
            }
            Label("呼び名", 11f, Theme.TextSub, true)
            Input(name, { name = it.take(20) }, "例: りおん", false)
            Label("読み (ひらがな。Aidy がこの読みで呼びます)", 11f, Theme.TextSub, true)
            Input(yomi, { yomi = it.take(20) }, "例: りおん", false)
            Label("どんな人か (どれも空でOK)", 11f, Theme.TextSub, true)
            Input(relation, { relation = it.take(20) }, "関係 (例: 家族・友だち・チームの人)", false)
            Input(birthday, { birthday = it.take(10) }, "誕生日 (例: 04-12)", false)
            Input(likes, { likes = it.take(60) }, "好きなもの (例: サッカー、いちご)", false)
            Input(memo, { memo = it.take(200) }, "メモ (Aidy に知っておいてほしいこと)", true)
            if (error.isNotEmpty()) Label(error, 11f, Theme.hex(0xef4444))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (person != null) SmallButton("消す", Theme.hex(0xfee2e2), Theme.hex(0xdc2626)) { confirmDelete = true }
                Spacer(Modifier.weight(1f))
                SmallButton("キャンセル", Theme.Slate100, Theme.Slate600, onDismiss)
                SmallButton(if (person == null) "カメラで登録へ" else "保存", Theme.Primary, Color.White) {
                    if (name.isBlank()) { error = "呼び名を入れてね"; return@SmallButton }
                    if (!AidyDeviceLink.isConfigured) { error = "本体とつながっていません"; return@SmallButton }
                    onSave(name.trim(), yomi.trim(), FaceLink.Profile(relation.trim(), birthday.trim(), likes.trim(), memo.trim()))
                }
            }
        }
    }
}
