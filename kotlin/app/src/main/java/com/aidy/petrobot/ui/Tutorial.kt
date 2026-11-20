package com.aidy.petrobot.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * 使いかたガイド: 項目の一覧から、知りたい項目の説明を開く説明書。設定のいちばん下の「使いかた」から開く。
 * 初回起動時に出る「実際の画面をさわって覚える」チュートリアルは Coach.kt。
 */

// ---------------------------------------------------------------- 説明の中身

/** どの画面の説明か (下のタブ・右上の歯車の場所を図で示す)。 */
private enum class TutTarget(val label: String) { Home("ホーム"), Diary("日記"), Remote("リモコン"), AR("AR"), Settings("設定") }

/**
 * 説明の 1 項目。image を指定すると、その下に画像の枠を出す。
 * 画像は app/src/main/assets/tutorial/<image> に置けば自動で表示され、無いあいだは点線の「画像を入れる場所」が出る。
 */
private class TutPoint(val icon: IconId, val title: String, val text: String, val image: String? = null, val imageCaption: String = "")

private class TutStep(
    val title: String,
    val lead: String,
    val face: AidyFace = AidyFace.Normal,
    val target: TutTarget? = null,
    val numbered: Boolean = false,
    val points: List<TutPoint> = emptyList(),
    val note: String? = null,
    /** 一覧に出すアイコンと、ひとことの説明。 */
    val icon: IconId = IconId.Aidy,
    val summary: String = "",
)

private val Steps = listOf(
    TutStep(
        "このアプリでできること",
        "このアプリでは、ペットロボット「Aidy（アイディ）」といっしょに楽しく過ごすための、いろいろなことができます。",
        AidyFace.Happy,
        icon = IconId.Aidy, summary = "はじめての人はここから",
        points = listOf(
            TutPoint(IconId.Aidy, "ホーム", "Aidyをなでたり、体の色を変えたりできます。"),
            TutPoint(IconId.Book, "日記", "Aidyと話した内容から日記ができます。予定もメモできます。"),
            TutPoint(IconId.Gamepad, "リモコン", "Aidyの声の大きさや、話す速さを変えられます。"),
            TutPoint(IconId.Camera, "AR", "耳やしっぽを付け替えたり、カメラで机の上にAidyを出したりできます。"),
        ),
        note = "このガイドは「設定」のいちばん下の「使いかた」からいつでも見られます。",
    ),
    TutStep(
        "画面の切りかえかた",
        "アプリには5つの画面があります。行きたい画面へは、次の3つの方法で移れます。",
        icon = IconId.HandPointer, summary = "タブ・スワイプ・歯車",
        points = listOf(
            TutPoint(IconId.HandPointer, "下のボタンをタップ", "画面のいちばん下にある「ホーム」「日記」「リモコン」「AR」をタップすると、その画面に切りかわります。"),
            TutPoint(IconId.ChevronRight, "左右にスワイプ", "画面を指で左右になぞっても、となりの画面に移れます。"),
            TutPoint(IconId.Gear, "右上の歯車は「設定」", "本体とのつなぎ方や、カレンダーの設定は、右上の歯車のマークから開きます。"),
        ),
        note = "スマホの「戻る」操作をすると、いつでもホームに戻れます。",
    ),
    TutStep(
        "Aidy本体とつなぐ",
        "本体とつなぐと、会話から日記を作ったり、声の設定を変えたりできるようになります。はじめに1回だけ設定すればOKです。",
        target = TutTarget.Settings,
        numbered = true,
        icon = IconId.Plug, summary = "QRコードを読み取るだけ",
        points = listOf(
            TutPoint(
                IconId.Plug, "Aidy本体の電源を入れる", "本体の電源を入れて、画面に顔が出るまで待ちます。",
                image = "connect_1.png", imageCaption = "本体の電源が入った画面",
            ),
            TutPoint(
                IconId.Aidy, "本体にQRコードを出す", "本体の画面に、つなぐためのQRコードを表示します。",
                image = "connect_2.png", imageCaption = "本体にQRコードが出ている画面",
            ),
            TutPoint(
                IconId.Camera, "アプリでQRコードを読み取る",
                "右上の歯車 →「Aidy本体と接続」の「QRコードを読み取る」をタップして、本体のQRコードをスマホのカメラで写します。",
            ),
            TutPoint(
                IconId.Check, "つながったか確かめる",
                "「保存して接続を試す」を押して、緑色の文字が出たら完了です。本体の画面も確認してね。",
                image = "connect_3.png", imageCaption = "つながったときの本体の画面",
            ),
        ),
        note = "赤い文字が出たときは、スマホと本体を近づけるか、同じWi-Fiにつながっているかを確かめて、もう一度「保存して接続を試す」を押してね。",
    ),
    TutStep(
        "ホーム：Aidyとあそぶ",
        "アプリを開くと最初に出る画面です。お部屋の中のAidyとふれあえます。",
        AidyFace.Happy,
        TutTarget.Home,
        icon = IconId.House, summary = "呼ぶ・なでる・色を変える",
        points = listOf(
            TutPoint(IconId.HandPointer, "タップで呼ぶ", "Aidyを指でトンとタップすると、呼ばれたと思ってこっちを向きます。"),
            TutPoint(IconId.Heart, "左右にこすってなでなで", "Aidyの体の上で、指を左右に細かく動かすと、なでなでになります。ハートが出たら成功です。"),
            TutPoint(IconId.Cubes, "回す・大きくする", "1本の指で大きくなぞるとAidyの向きが変わります。2本の指で広げると大きく見えます（右下の＋・−ボタンでもOK）。"),
            TutPoint(IconId.Paw, "色を変える", "右上の「色」を押すと、Aidyの体の色を選べます。"),
            TutPoint(IconId.MapPin, "お散歩", "下の「お散歩」をオンにすると、Aidyがお部屋の中を自由に歩きまわります。"),
            TutPoint(IconId.Cubes, "付け替え", "「付け替え」を押すと、耳やしっぽを付け替える画面に移ります。"),
        ),
    ),
    TutStep(
        "日記：Aidyとの思い出",
        "Aidyと話した内容が、日記としてカレンダーに残ります。予定のメモにも使えます。",
        target = TutTarget.Diary,
        icon = IconId.Book, summary = "カレンダー・日記・予定",
        points = listOf(
            TutPoint(IconId.Book, "カレンダーで日にちを選ぶ", "日にちをタップすると、その日の日記と予定が見られます。日記がある日には小さなAidyが出ます。"),
            TutPoint(IconId.Aidy, "会話ログを受け取る", "「Aidyから会話ログを受け取る」を押すと、本体と話した内容から日記ができます（先に本体とつないでおいてね）。"),
            TutPoint(IconId.Plus, "予定を入れる", "「＋ 追加」から予定を書けます。時刻を空けておくと「終日」の予定になります。"),
            TutPoint(IconId.Check, "緑色の予定", "緑色の予定は、スマホのカレンダーの予定です（設定でオンにしたときだけ出ます。見るだけです）。"),
        ),
    ),
    TutStep(
        "リモコン：Aidyの設定を変える",
        "本体の声や返事を、スマホから変えられます。本体とつながっているときに使えます。",
        target = TutTarget.Remote,
        icon = IconId.Gamepad, summary = "声の大きさ・話す速さ・辞書",
        points = listOf(
            TutPoint(IconId.Sliders, "声の大きさ・話す速さ", "「読み込む」を押すと本体の今の設定が出てきます。変えたい項目を動かして、本体に送ります。"),
            TutPoint(IconId.Book, "辞書", "よくある質問への答えや、聞き間違えやすい言葉、読みかたを登録して、Aidyの返事をかしこくできます。"),
            TutPoint(IconId.Microchip, "拡張モジュール", "カメラやセンサーなどの追加パーツは、ここに並びます（準備中）。"),
        ),
    ),
    TutStep(
        "AR：付け替え＆カメラで見る",
        "上の切りかえで「画面上」と「AR」を選べます。",
        target = TutTarget.AR,
        icon = IconId.Camera, summary = "パーツの付け替え・マーカー",
        points = listOf(
            TutPoint(IconId.Cubes, "「画面上」でパーツを付ける", "まずAidyの面（上・横など）をタップして選びます。下に、その面に付けられるパーツが出るので、指で押さえたままAidyの体の光っている丸まで引っぱると付きます。"),
            TutPoint(IconId.XMark, "パーツを外す", "付いているパーツを、赤い「ここで離すと取り外し」のところまで引っぱると外れます。"),
            TutPoint(IconId.Camera, "「AR」でカメラに映す", "「AR」に切りかえて、専用のマーカー（白黒の四角い模様）をカメラで写すと、その上にAidyが出てきます。"),
            TutPoint(IconId.Crosshairs, "マーカーを作る", "パーツ一覧の「マーカー作成」から、今の着せ替えのマーカー画像を保存して印刷できます。"),
        ),
    ),
    TutStep(
        "設定",
        "右上の歯車から開きます。困ったときはここを見てね。",
        target = TutTarget.Settings,
        icon = IconId.Gear, summary = "接続・カレンダー・保存",
        points = listOf(
            TutPoint(IconId.Plug, "Aidy本体と接続", "本体とのつなぎ方はここで設定します。うまくつながらないときは、もう一度「保存して接続を試す」を押してね。"),
            TutPoint(IconId.Book, "スマホのカレンダーと結合", "オンにすると、Googleカレンダーなどの予定も、日記のカレンダーに出てきます。"),
            TutPoint(IconId.Check, "書き出し・読み込み", "予定と日記をファイルに保存しておけます。スマホを買い替えるときに便利です。"),
            TutPoint(IconId.Lightbulb, "使いかたをもう一度見る", "設定のいちばん下の「使いかた」から、このガイドや、実際にさわって覚えるチュートリアルをいつでも開けます。"),
        ),
    ),
)

// ---------------------------------------------------------------- 画面

/**
 * 全画面の使いかたガイド。最初は項目の一覧で、タップした項目の説明を開く (説明から「一覧へ」で戻る)。
 * onClose(openSettings): 閉じたとき。「本体とつなぐ」の説明の下のボタンを押したときだけ openSettings = true。
 */
@Composable
fun HowToGuide(onClose: (openSettings: Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }

    BackHandler { if (open != null) open = null else onClose(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.Device)
            .blockTouches()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 上: 一覧では「使いかた」+ とじる / 説明では「< 一覧」
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val i = open
            if (i == null) {
                Label("使いかた", 17f, Theme.TextMain, true, Modifier.padding(start = 12.dp))
            } else {
                Row(
                    Modifier.pressable(0.95f) { open = null }.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(IconId.ChevronLeft, 13.dp, Theme.Primary)
                    Label("一覧", 15f, Theme.Primary, true)
                }
            }
            Box(Modifier.weight(1f))
            Box(Modifier.pressable(0.95f) { onClose(false) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Label("とじる", 14f, Theme.TextSub, true)
            }
        }

        // 一覧 ⇔ 説明 を横にすべらせて切り替える
        AnimatedContent(
            open,
            Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(tween(260)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(260)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(160)))
            },
            label = "guide",
        ) { i ->
            if (i == null) TopicList { open = it }
            else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { StepContent(Steps[i]) }
                // 下: 一覧へ (本体とつなぐの説明では、設定を開くボタンも)
                Row(
                    Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BigButton("一覧にもどる", false, Modifier.weight(1f)) { open = null }
                    if (Steps[i].numbered) BigButton("設定を開く", true, Modifier.weight(1f)) { onClose(true) }
                    else if (i + 1 < Steps.size) BigButton("つぎの項目", true, Modifier.weight(1f)) { open = i + 1 }
                }
            }
        }
    }
}

/** 項目の一覧 (タップでその項目の説明を開く)。 */
@Composable
private fun TopicList(onOpen: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AidyMascot(52.dp, AidyFace.Happy, interactive = true)
            Label("知りたいことをタップしてね！", 16f, Theme.Slate700, true, Modifier.weight(1f))
        }
        Steps.forEachIndexed { i, s ->
            // 一覧の行は、上から順にふわっと出す
            val shown = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(40L * i)
                shown.animateTo(1f, tween(260, easing = EaseCss))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = shown.value
                        translationY = (1f - shown.value) * 24f
                    }
                    .pressable(0.98f) { onOpen(i) }
                    .box(Color.White, 16.dp, 1.dp, Theme.Slate100)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(42.dp).background(Theme.PrimaryLight, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(s.icon, 18.dp, Theme.Primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(s.title, 15f, Theme.TextMain, true)
                    if (s.summary.isNotEmpty()) Label(s.summary, 12f, Theme.TextSub)
                }
                Icon(IconId.ChevronRight, 12.dp, Theme.Slate400)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BigButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(52.dp)
            .pressable(0.97f, onClick = onClick)
            .box(if (primary) Theme.Primary else Theme.Slate100, 26.dp, if (primary) 4.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, 16f, if (primary) Color.White else Theme.Slate600, true) }
}

@Composable
private fun StepContent(step: TutStep) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // マスコット + タイトル
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AidyMascot(56.dp, step.face)
            Label(step.title, 21f, Theme.TextMain, true, Modifier.weight(1f))
        }
        Label(step.lead, 15f, Theme.Slate700)

        step.target?.let { WhereIsIt(it) }

        step.points.forEachIndexed { i, p -> PointCard(p, if (step.numbered) i + 1 else null) }
        // 項目のないページ (最後の「準備OK！」) は大きな Aidy でお見送り
        if (step.points.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                AidyMascot(160.dp, step.face, interactive = true)
            }
        }

        step.note?.let { note ->
            Row(
                Modifier.fillMaxWidth().background(Theme.hex(0xfef9c3), RoundedCornerShape(14.dp)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(IconId.Lightbulb, 16.dp, Theme.hex(0xca8a04))
                Label(note, 13f, Theme.hex(0x713f12), modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 説明の 1 項目 (アイコン or 番号 + 見出し + 本文 [+ 画像])。 */
@Composable
private fun PointCard(p: TutPoint, number: Int?) {
    Column(
        Modifier.fillMaxWidth().box(Color.White, 16.dp, 1.dp, Theme.Slate100).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).background(if (number != null) Theme.Primary else Theme.PrimaryLight, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (number != null) Label("$number", 16f, Color.White, true)
                else Icon(p.icon, 16.dp, Theme.Primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(p.title, 16f, Theme.TextMain, true)
                Label(p.text, 14f, Theme.TextSub)
            }
        }
        p.image?.let { TutorialImage(it, p.imageCaption) }
    }
}

/**
 * assets/tutorial/<name> の画像。まだ無ければ点線の枠 (あとで本体の画面のスクショを入れる場所) を出す。
 */
@Composable
private fun TutorialImage(name: String, caption: String) {
    val context = LocalContext.current
    val bmp = remember(name) {
        runCatching { context.assets.open("tutorial/$name").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (bmp != null) {
            Image(
                bmp, caption,
                Modifier.fillMaxWidth().aspectRatio(bmp.width / bmp.height.toFloat()).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            val line = Theme.Slate300
            Column(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Theme.Slate100, RoundedCornerShape(12.dp))
                    .drawBehind {
                        drawRoundRect(
                            line,
                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(IconId.Camera, 22.dp, Theme.Slate400)
                Label("画像を準備中", 13f, Theme.Slate400, true)
                Label("tutorial/$name", 10f, Theme.Slate400)
            }
        }
        if (caption.isNotEmpty()) Label(caption, 12f, Theme.TextSub, align = TextAlign.Center)
    }
}

/** 「この画面はどこから開くか」を、下のタブバー (または右上の歯車) の小さな図で示す。 */
@Composable
private fun WhereIsIt(target: TutTarget) {
    val tabs = listOf(TutTarget.Home to IconId.Aidy, TutTarget.Diary to IconId.Book, TutTarget.Remote to IconId.Gamepad, TutTarget.AR to IconId.Camera)
    Column(
        Modifier.fillMaxWidth().box(Theme.PrimaryLight, 16.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val how = if (target == TutTarget.Settings) "画面の右上の歯車をタップすると開きます" else "画面の下の「${target.label}」をタップすると開きます"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(IconId.HandPointer, 14.dp, Theme.Primary)
            Label(how, 13f, Theme.Primary, true, Modifier.weight(1f))
        }
        if (target == TutTarget.Settings) {
            // ミニのヘッダー: ロゴ + 歯車 (光らせる)
            Row(
                Modifier.fillMaxWidth().height(44.dp).box(Color.White, 12.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("Aidy", 16f, Theme.TextMain, true)
                Box(Modifier.weight(1f))
                Box(Modifier.size(34.dp).background(Theme.Primary, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                    Icon(IconId.Gear, 18.dp, Color.White)
                }
            }
        } else {
            // ミニのタブバー: 対象のタブだけ青くする
            Row(Modifier.fillMaxWidth().height(52.dp).box(Color.White, 12.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((t, icon) in tabs) {
                    val on = t == target
                    val fg = if (on) Color.White else Theme.Slate400
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.background(if (on) Theme.Primary else Color.Transparent,RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(icon, 15.dp, fg)
                            Label(t.label, 10f, fg, true)
                        }
                    }
                }
            }
        }
    }
}
