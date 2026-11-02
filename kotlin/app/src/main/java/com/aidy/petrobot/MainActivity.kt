package com.aidy.petrobot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.aidy.petrobot.ui.AidyApp
import com.aidy.petrobot.ui.AppState
import com.aidy.petrobot.ui.Graphics
import com.aidy.petrobot.ui.LocalGraphics
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 明るい背景なのでステータスバー・ナビゲーションバーの文字は暗く
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xfff8fafc.toInt(), 0xfff8fafc.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xffffffff.toInt(), 0xffffffff.toInt()),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            // アプリ全体で 1 つの Filament エンジン (ホーム・付け替え・AR で共有)
            val engine = rememberEngine()
            val materialLoader = rememberMaterialLoader(engine)
            val environmentLoader = rememberEnvironmentLoader(engine)
            val graphics = remember { Graphics(engine, materialLoader, environmentLoader) }
            CompositionLocalProvider(LocalGraphics provides graphics) {
                val app = remember { AppState(graphics) }
                AidyApp(app)
            }
        }
    }
}
