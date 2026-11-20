package com.aidy.petrobot.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aidy.petrobot.ar.ARController
import com.aidy.petrobot.three.HomeRoom
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.aidy.petrobot.data.BodyLayout
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.three.BodyModel
import com.aidy.petrobot.three.ModelLoader
import kotlinx.coroutines.launch

private const val HeaderHeight = 50f

private const val TabBarHeight = 64f

private const val PageHome = 0
private const val PageDiary = 1
private const val PageRemote = 2
private const val PageAR = 3
private const val PageSettings = 4
private const val PageCount = 5

/** 初回起動のチュートリアルの手順 (実際の画面をさわって覚える)。 */
private fun coachSteps() = listOf(
    CoachStep("はじめまして！ ぼくはAidy。これからいっしょに、このアプリの使いかたを練習しよう！", page = PageHome, face = AidyFace.Happy),
    // ---- ホーム
    CoachStep("まずはぼくを指でトンとタップして、呼んでみて！", "home3d", PageHome, waitFor = "call", praise = "なあに？ ちゃんとこっちを向いたよ！", needs = "home"),
    CoachStep("こんどは、ぼくの体の上で指を左右にこすってみて。なでなでになるよ！", "home3d", PageHome, waitFor = "pet", praise = "きもちいい〜。なでてくれてありがとう！", needs = "home", gesture = CoachGesture.Rub),
    CoachStep(
        "指でぼくを大きくなぞると、くるっと回るよ。やってみて！", "home3d", PageHome,
        waitFor = "rotate", praise = "くるくる〜。上手に回せたね！", needs = "home", gesture = CoachGesture.Swipe,
    ),
    CoachStep(
        "ぼくの色を変えてみよう！ 右下のシャツのボタンで着せ替えを開いて、「カラー」タブで好きな色をタップしてね。", "color", PageHome,
        waitFor = "colorSet", praise = "すてきな色！ ありがとう！ 着せ替えは右上の「できた！」で終われるよ。", needs = "home",
    ),
    // ---- 日記
    CoachStep("つぎは日記を見てみよう。下の「日記」をタップしてみて！", "tab$PageDiary", PageHome, waitFor = "page$PageDiary", praise = "日記の画面に来たよ！"),
    CoachStep(
        "カレンダーの好きな日にちをタップしてみて！ その日の日記と予定が見られるよ。", "calendar", PageDiary,
        waitFor = "day", praise = "日記がある日には、小さなぼくが出るんだ！",
    ),
    CoachStep("本体とつないだら、「同期」を押してね。ぼくとおしゃべりした内容から、日記ができるよ。", "diarySync", PageDiary),
    CoachStep("ここで予定を入れられるよ。「＋ 追加」から書いてね。", "schedule", PageDiary),
    // ---- リモコン
    CoachStep("下の「リモコン」をタップしてみて！", "tab$PageRemote", PageDiary, waitFor = "page$PageRemote", praise = "リモコンの画面だよ！"),
    CoachStep("ここで、ぼくの声の大きさや話す速さを変えられるよ。本体とつないでから使ってね。", "voice", PageRemote),
    // ---- AR (付け替え)
    CoachStep("下の「AR」をタップしてみて！", "tab$PageAR", PageRemote, waitFor = "page$PageAR", praise = "付け替えの画面に来たよ！"),
    CoachStep(
        "パーツを付ける場所を選ぼう。ぼくの体の横の面（顔がないほう）をタップしてみて！", "ar3d", PageAR,
        waitFor = "face", praise = "その面が青く光ったね！", needs = "ar",
    ),
    CoachStep(
        "下に、その面に付けられるパーツが出てきたよ。パーツを指で押さえたまま、ぼくの体の光っている丸まで引っぱってみて！", "arEdit", PageAR,
        waitFor = "attach", praise = "似合うかな？ ありがとう！", needs = "ar",
    ),
    CoachStep("上の「AR」に切りかえると、カメラに写したマーカーの上にぼくが出てくるよ。", "arSwitch", PageAR, needs = "ar"),
    // ---- 設定
    CoachStep("最後に、右上の歯車をタップしてみて！", "gear", PageAR, waitFor = "page$PageSettings", praise = "設定の画面だよ！"),
    CoachStep("ここで本体とつなぐよ。本体に出るQRコードを「QRコードを読み取る」で写して、「保存して接続を試す」を押してね。", "deviceLink", PageSettings),
    CoachStep("これでおしまい！ 使いかたを忘れたら、設定のいちばん下の「使いかた」を見てね。これからよろしくね！", page = PageSettings, face = AidyFace.Happy),
)

// 設定はタブバーに出さず、ヘッダーのギアアイコンからのみ開く
private val TabLabels = listOf("ホーム", "日記", "リモコン", "AR")
private val TabIcons = listOf(IconId.Aidy, IconId.Book, IconId.Gamepad, IconId.Camera)

/**
 * アプリ全体の状態 (3D ルーム・AR・各ページ)。
 * 3D モデルの読み込みは重いので、画面は先に出して、ホーム・AR は loadModels が終わってから作る (それまでは「読み込み中」)。
 */
class AppState(private val g: Graphics) {
    var home by mutableStateOf<HomeUi?>(null)
        private set
    var ar by mutableStateOf<ARPageUi?>(null)
        private set
    val diary = DiaryState()
    val markerSheet = MarkerSheetState()

    /** モデルを裏で読み (解析はキャッシュがあれば一瞬)、読み終わったら 3D の部屋を作る。 */
    suspend fun loadModels() {
        withContext(Dispatchers.Default) {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<Any?>>()
            jobs += async { BodyLayout.slots }
            jobs += async { BodyModel.load("models/body_face.obj") }
            jobs += async { BodyModel.load("models/body.obj") }
            for (k in ModuleCatalog.All) jobs += async { ModelLoader.load(k) }
            for (n in com.aidy.petrobot.three.RoomDecor.Names) jobs += async { com.aidy.petrobot.three.RoomModel.load(n) }
            jobs.awaitAll()
        }
        // Filament の部品は UI スレッドで作る。ホームを先に出し、1 フレーム空けてから AR
        home = HomeUi(HomeRoom(g.gfx, false))
        withFrameNanos { }
        ar = ARPageUi(HomeRoom(g.gfx, true), ARController(g))
    }
}

private fun PagerState.isVisible(page: Int) = layoutInfo.visiblePagesInfo.any { it.index == page }

/** Unity 版 AidyApp: ヘッダー + 横スワイプのページ + ページの点 + 下のタブバー。 */
@Composable
fun AidyApp(app: AppState) {
    val pager = rememberPagerState { PageCount }
    val scope = rememberCoroutineScope()
    fun goTo(page: Int) {
        scope.launch { pager.animateScrollToPage(page, animationSpec = tween(300, easing = PageSnapEasing)) }
    }

    val homeVisible by remember { derivedStateOf { pager.isVisible(PageHome) } }
    val diaryVisible by remember { derivedStateOf { pager.isVisible(PageDiary) } }
    val arVisible by remember { derivedStateOf { pager.isVisible(PageAR) } }
    val current = pager.currentPage
    // 着せ替え中 (ホームのページが着せ替え画面)。下のタブはそのまま出し、ほかのページから戻っても着せ替えのまま
    val dressing = app.home?.dressing == true
    // 着せ替え画面を見ている間だけ横スワイプのページ移動を止める (3D を回す操作とぶつかるため。タブでは移動できる)
    val dressingShown = dressing && current == PageHome

    // 使いかたガイド (設定の「使いかた」から開く紙芝居)
    var showGuide by rememberSaveable { mutableStateOf(false) }

    // 初回起動のチュートリアル (実際の画面で Aidy が案内する)。設定の「使いかた」からも始められる。
    // 画面の作り直し (設定変更など) でここがもう一度走っても、進めている途中なら最初に戻さない
    LaunchedEffect(Unit) { if (!Coach.done && !Coach.active) Coach.start(coachSteps()) }
    Coach.homeReady = app.home != null
    Coach.arReady = app.ar != null
    // ページを移ったら知らせる (「日記をタップしてみて」などの手順用)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { Coach.event("page$it") } }
    // 本体との会話・表情・モジュール・予定の見回り (アプリが前に出ている間だけ)
    val homeNow by rememberUpdatedState(homeVisible)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            com.aidy.petrobot.data.BodyBridge.loop { homeNow }
        }
    }
    // 手順ごとに見せるページへ移る (ページを移るのを待っている手順はそのまま)
    val coachStep = Coach.step
    LaunchedEffect(coachStep) {
        val s = coachStep ?: return@LaunchedEffect
        val p = s.page ?: return@LaunchedEffect
        if (s.waitFor?.startsWith("page") != true && pager.currentPage != p) goTo(p)
        if (s.page == PageAR && s.needs == "ar") app.ar?.setARMode(false)
        // お散歩中で画面のはしにいても、指差しのところ (まんなか) に呼び戻す
        if (s.target == "home3d") app.home?.room?.recall()
    }

    // ページ切替 (OnPageChanged)。AR ページは読み込み後にできるので、できたときにも合わせる
    val arUi = app.ar
    LaunchedEffect(pager, arUi) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val a = arUi ?: return@collect
            a.pageShown = page == PageAR
            if (page == PageAR && a.arMode) a.ar.startCamera()
        }
    }
    // 見えているページだけ AR の検出を回す
    arUi?.let { it.ar.pageVisible = arVisible && it.arMode }

    // Android の戻るボタン
    BackHandler(enabled = app.markerSheet.isOpen || current != PageHome) {
        if (app.markerSheet.isOpen) app.markerSheet.close() else goTo(PageHome)
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        // 3D ビューで本体の外を速くスワイプしたらページを移る (dir = +1 で次、-1 で前)
        CompositionLocalProvider(LocalPageSwipe provides (if (dressingShown) null else ({ dir: Int -> if (!Coach.active) goTo((pager.currentPage + dir).coerceIn(0, PageCount - 1)) })))  {
        Column(Modifier.fillMaxSize().background(Theme.Device).statusBarsPadding()) {
            Header { goTo(PageSettings) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 全ページを作ったままにする (ページの使い回しで 3D ビュー (SurfaceView) の Filament の View が壊れるのを防ぐ)
                // チュートリアル中も指のスワイプでページを移らない (案内しているページから外れないように。移動はタブ・歯車で)
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = PageCount, key = { it }, userScrollEnabled = !dressingShown && !Coach.active) { page ->
                    when (page) {
                        PageHome -> app.home?.let { home ->
                            HomePage(home, homeVisible) {
                                app.ar?.setARMode(false)
                                goTo(PageAR)
                            }
                        } ?: ModelLoadingView()
                        PageDiary -> DiaryPage(app.diary, diaryVisible && pager.settledPage == PageDiary)
                        PageRemote -> RemotePage(pager.settledPage == PageRemote)
                        PageAR -> arUi?.let { ARPage(it, arVisible) { app.markerSheet.open() } } ?: ModelLoadingView()
                        PageSettings -> SettingsPage(
                            app.diary,
                            onOpenDiary = { goTo(PageDiary) },
                            onStartTutorial = { Coach.start(coachSteps()) },
                            onOpenGuide = { showGuide = true },
                        )
                    }
                }
                // ホーム・AR (3D の操作)・日記 (カレンダー端の日付)・リモコン (操作ボタン) は矢印と重なるので隠す (タブ/スワイプで移動)
                val hide = true // 全ページ: スワイプ・タブで移動 (設定も本体との接続カードと重なるので隠す)
                NavArrow(IconId.ChevronLeft, current != 0, hide, Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) { goTo(current - 1) }
                NavArrow(IconId.ChevronRight, current != PageCount - 1, hide, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) { goTo(current + 1) }
            }


            TabBar(current) { goTo(it) }
        }
        }
        MarkerSheet(app.markerSheet)
        if (showGuide) {
            HowToGuide { openSettings ->
                showGuide = false
                scope.launch { pager.scrollToPage(if (openSettings) PageSettings else PageHome) }
            }
        }
        CoachOverlay()
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    val context = LocalContext.current
    // ロゴ画像 (aidy_logo_dark = 黒文字・明るい背景用)。無ければマスコット + 文字
    val logo = remember {
        runCatching { context.assets.open("aidy_logo_dark.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().height(HeaderHeight.dp).background(Theme.Device).padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) {
            val h = 26f
            Image(logo, "AIDY", Modifier.size((h * logo.width / logo.height).dp, h.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AidyMascot(30.dp, AidyFace.Normal, interactive = true)
                Label("AIDY", 20f, Theme.TextMain, true)
            }
        }
        Box(Modifier.weight(1f))
        Box(
            Modifier.size(44.dp).coachTarget("gear").clickable(remember { MutableInteractionSource() }, null, onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(IconId.Gear, 24.dp, Theme.TextSub) }
    }
}

@Composable
private fun NavArrow(icon: IconId, enabled0: Boolean, hidden: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val enabled = enabled0 && !hidden
    if (hidden) return
    Box(
        modifier
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .pressable(enabled = enabled, onClick = onClick)
            .box(Color.White.copy(alpha = 0.85f), 20.dp, 4.dp, Color.White.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, 16.dp, Theme.Slate600) }
}


@Composable
private fun TabBar(current: Int, onClick: (Int) -> Unit) {
    // 背景はナビゲーションバーの裏まで伸ばす
    Column(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.Border))
        Row(Modifier.fillMaxWidth().height((TabBarHeight - 1f).dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in TabLabels.indices) {
                val active = i == current
                val fg = animatedColor(if (active) Theme.Primary else Theme.Slate400)
                val bg = animatedColor(if (active) Theme.PrimaryLight else Color.White.copy(alpha = 0f))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .coachTarget("tab$i")
                            .pressable { onClick(i) }
                            .background(bg, RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(TabIcons[i], 18.dp, fg)
                        Label(TabLabels[i], 11f, fg)
                    }
                }
            }
        }
    }
}
