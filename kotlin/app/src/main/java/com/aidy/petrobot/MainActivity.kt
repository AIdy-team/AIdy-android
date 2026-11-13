package com.aidy.petrobot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.aidy.petrobot.ui.AidyLoadingScreen
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedVisibility
import com.aidy.petrobot.ui.AidyApp
import com.aidy.petrobot.ui.AppState
import com.aidy.petrobot.ui.Graphics
import com.aidy.petrobot.ui.LocalGraphics
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader

private const val SplashMs = 1600

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 明るい背景なのでステータスバー・ナビゲーションバーの文字は暗く
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xfff8fafc.toInt(), 0xfff8fafc.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xffffffff.toInt(), 0xffffffff.toInt()),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 90/120Hz の端末でも 60Hz で描く (3D の描画回数を減らして発熱を抑える)
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        setContent {
            // アプリ全体で 1 つの Filament エンジン (ホーム・付け替え・AR で共有)
            val engine = rememberEngine()
            val materialLoader = rememberMaterialLoader(engine)
            val environmentLoader = rememberEnvironmentLoader(engine)
            val graphics = remember { Graphics(engine, materialLoader, environmentLoader) }
            CompositionLocalProvider(LocalGraphics provides graphics) {
                val app = remember { AppState(graphics) }
                // 起動画面 (動くロゴ)。その裏で画面を組み立て、3D モデルを読み込む。
                // 画面の準備ができたら (最短 1.6 秒) 先に開き、モデルが読み終わるまで 3D の所は「読み込み中」を出す
                var splash by remember { mutableStateOf(true) }
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) { app.loadModels() }
                LaunchedEffect(Unit) {
                    progress.animateTo(1f, tween(SplashMs, easing = LinearOutSlowInEasing))
                    splash = false
                }
                Box(Modifier.fillMaxSize()) {
                    AidyApp(app)
                    AnimatedVisibility(splash, enter = EnterTransition.None, exit = fadeOut(tween(350))) {
                        AidyLoadingScreen(progress.value, if (progress.value < 0.6f) "Aidyを起こしています…" else "まもなく起動します")
                    }
                }
            }
        }
    }
}
