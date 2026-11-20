package com.aidy.petrobot.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidy.petrobot.data.BodyBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 吹き出しを出しておく時間。 */
private const val BubbleMs = 12_000L

/**
 * 3D の Aidy の上に出す吹き出し (本体から届いた返事・ひとりごと)。返事待ちの間は「…」。
 * 下向きのしっぽで Aidy から出ているように見せる。
 */
@Composable
fun AidyBubble(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(BodyBridge.bubbleAt, BodyBridge.waiting) {
        now = System.currentTimeMillis()
        delay(BubbleMs + 50)
        now = System.currentTimeMillis()
    }
    val text = BodyBridge.bubble
    val fresh = text != null && now - BodyBridge.bubbleAt < BubbleMs
    val show = BodyBridge.waiting || fresh
    AnimatedVisibility(show, modifier, enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .box(Color.White, 18.dp, 6.dp, Theme.Border)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (BodyBridge.waiting && !fresh) ThinkingDots()
                else Label(text ?: "", 15f, Theme.TextMain, true, align = TextAlign.Center)
            }
            // しっぽ (下の Aidy を指す)
            Canvas(Modifier.size(22.dp, 12.dp)) {
                val p = Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                }
                drawPath(p, Color.White)
                drawLine(Theme.Border, Offset(0f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
                drawLine(Theme.Border, Offset(size.width, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
            }
        }
    }
}

@Composable
private fun ThinkingDots() {
    var n by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) { while (true) { delay(350); n = n % 3 + 1 } }
    Label("考え中" + "・".repeat(n), 14f, Theme.TextSub, true)
}

/**
 * ホームの下の会話欄: 履歴ボタン・文字の入力・マイク (スマホの音声入力)・送信。
 * 送った言葉は本体に届き (POST /api/app/chat)、本体の返事は吹き出しに出る。
 */
@Composable
fun HomeChatBar(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    fun send(t: String, via: String) {
        if (t.isBlank()) return
        scope.launch { BodyBridge.sendChat(t, via) }
    }

    // スマホの音声入力 (Google の音声入力の画面。マイクの許可はその画面が持つ)
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            send(heard, "voice")
        }
    }
    fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Aidy に話しかけてね")
        try {
            voice.launch(i)
        } catch (e: Exception) {
            BodyBridge.messages += BodyBridge.Msg(System.nanoTime(), "sys", "このスマホでは音声入力が使えません")
            showLog = true
        }
    }

    Column(modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showLog) ChatLog(Modifier.fillMaxWidth().heightIn(max = 260.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .box(Color.White.copy(alpha = 0.96f), 28.dp, 4.dp, Theme.Border)
                .consumeTaps()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoundButton(if (showLog) Theme.PrimaryLight else Theme.Slate100, { showLog = !showLog }) { LogIcon(if (showLog) Theme.Primary else Theme.Slate600) }
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(200) },
                singleLine = true,
                textStyle = TextStyle(color = Theme.TextMain, fontSize = 14.sp),
                cursorBrush = SolidColor(Theme.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(text, "text"); text = "" }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Label(if (BodyBridge.supported == false) "本体がまだ会話に未対応です" else "Aidy に話しかける…", 14f, Theme.Slate400)
                        inner()
                    }
                },
            )
            if (text.isBlank()) {
                RoundButton(Theme.PrimaryLight, ::startVoice) { MicIcon(Theme.Primary) }
            } else {
                RoundButton(Theme.Primary, { send(text, "text"); text = "" }) { SendIcon(Color.White) }
            }
        }
    }
}

/** 会話の履歴 (スマホ・本体のマイク・Aidy・お知らせ)。 */
@Composable
private fun ChatLog(modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(BodyBridge.messages.size) { if (BodyBridge.messages.isNotEmpty()) list.animateScrollToItem(BodyBridge.messages.size - 1) }
    Box(modifier.box(Color.White.copy(alpha = 0.96f), 18.dp, 4.dp, Theme.Border).consumeTaps().padding(10.dp)) {
        if (BodyBridge.messages.isEmpty()) {
            Label("まだ会話はありません。下の欄に書くか、マイクで話しかけてね", 12f, Theme.Slate400, modifier = Modifier.padding(6.dp))
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BodyBridge.messages, key = { it.id }) { m ->
                val mine = m.who == "me"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    when (m.who) {
                        "sys" -> Label(m.text, 11f, Theme.Slate400, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
                        else -> Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            if (m.who == "body") Label("本体のマイク", 9f, Theme.Slate400)
                            Box(
                                Modifier
                                    .widthIn(max = 240.dp)
                                    .background(
                                        when (m.who) { "me" -> Theme.Primary; "body" -> Theme.Slate100; else -> Theme.PrimaryLight },
                                        RoundedCornerShape(14.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Label((if (m.via == "voice" && mine) "🎤 " else "") + m.text, 13f, if (mine) Color.White else Theme.TextMain)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).pressable(0.9f, onClick = onClick).background(bg, RoundedCornerShape(21.dp)), contentAlignment = Alignment.Center) { content() }
}

/** マイクの絵。 */
@Composable
private fun MicIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val sw = w * 0.1f
        drawRoundRect(color, Offset(w * 0.34f, w * 0.06f), androidx.compose.ui.geometry.Size(w * 0.32f, w * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.22f, w * 0.22f), androidx.compose.ui.geometry.Size(w * 0.56f, w * 0.5f), style = Stroke(sw))
        drawLine(color, Offset(w / 2f, w * 0.72f), Offset(w / 2f, w * 0.9f), sw)
        drawLine(color, Offset(w * 0.34f, w * 0.92f), Offset(w * 0.66f, w * 0.92f), sw)
    }
}

/** 送信 (紙飛行機) の絵。 */
@Composable
private fun SendIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.08f, w * 0.12f); lineTo(w * 0.95f, w * 0.5f); lineTo(w * 0.08f, w * 0.88f)
            lineTo(w * 0.2f, w * 0.5f); close()
        }
        drawPath(p, color)
    }
}

/** 履歴 (吹き出し 2 つ) の絵。 */
@Composable
private fun LogIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val sw = w * 0.09f
        drawRoundRect(color, Offset(w * 0.06f, w * 0.12f), androidx.compose.ui.geometry.Size(w * 0.6f, w * 0.42f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(sw))
        drawRoundRect(color, Offset(w * 0.34f, w * 0.46f), androidx.compose.ui.geometry.Size(w * 0.6f, w * 0.42f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
    }
}
