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
private const val DotsHeight = 24f
private const val TabBarHeight = 64f

private const val PageHome = 0
private const val PageDiary = 1
private const val PageRemote = 2
private const val PageAR = 3
private const val PageSettings = 4
private const val PageCount = 5

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
        CompositionLocalProvider(LocalPageSwipe provides { dir: Int -> goTo((pager.currentPage + dir).coerceIn(0, PageCount - 1)) }) {
        Column(Modifier.fillMaxSize().background(Theme.Device).statusBarsPadding()) {
            Header { goTo(PageSettings) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 全ページを作ったままにする (ページの使い回しで 3D ビュー (SurfaceView) の Filament の View が壊れるのを防ぐ)
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = PageCount, key = { it }) { page ->
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
                        PageSettings -> SettingsPage(app.diary) { goTo(PageDiary) }
                    }
                }
                // ホーム・AR (3D の操作)・日記 (カレンダー端の日付)・リモコン (操作ボタン) は矢印と重なるので隠す (タブ/スワイプで移動)
                val hide = true // 全ページ: スワイプ・タブで移動 (設定も本体との接続カードと重なるので隠す)
                NavArrow(IconId.ChevronLeft, current != 0, hide, Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) { goTo(current - 1) }
                NavArrow(IconId.ChevronRight, current != PageCount - 1, hide, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) { goTo(current + 1) }
            }
            Dots(current) { goTo(it) }
            TabBar(current) { goTo(it) }
        }
        }
        MarkerSheet(app.markerSheet)
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
            Modifier.size(44.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onSettings),
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
private fun Dots(current: Int, onClick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(DotsHeight.dp).background(Theme.Device),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in TabLabels.indices) {
            val t by animateFloatAsState(if (i == current) 1f else 0f, tween(300, easing = EaseCss), label = "dot")
            Box(
                Modifier
                    .width((8f + 16f * t).dp)
                    .height(8.dp)
                    .background(lerp(Theme.Slate300, Theme.Primary, t), RoundedCornerShape(4.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { onClick(i) },
            )
        }
    }
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
