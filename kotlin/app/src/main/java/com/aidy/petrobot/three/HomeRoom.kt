package com.aidy.petrobot.three

import com.aidy.petrobot.data.BodyLayout
import com.aidy.petrobot.data.BodySlot
import com.aidy.petrobot.data.ModuleCatalog
import com.aidy.petrobot.data.ModuleConfig
import com.aidy.petrobot.data.ModuleKind
import com.aidy.petrobot.ui.AidyFace
import com.aidy.petrobot.ui.AidyMascotArt
import com.aidy.petrobot.ui.FaceScreen
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import io.github.sceneview.material.setColor
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.MeshNode
import io.github.sceneview.node.Node
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** three.js の Object3D 相当 (position / rotation (Euler XYZ) を three の数値で保持)。 */
class TNode(val node: Node) {
    var p = V3.Zero
    var r = V3.Zero
    var s = V3.One

    fun apply() = node.setLocal(p, Q.euler(r), s)

    /** 親 (pose) から見たこの節の姿勢。 */
    fun pose() = Pose(p, Q.euler(r), s)
}

/**
 * 3D ペットルーム + 本体v2 (ModuleConfig のモジュール付き) + 自律AI行動 + 操作への反応 (Unity 版 AidyHomeRoom)。
 * ホーム画面と、AR タブ「画面上」の付け替えプレビュー (editable: 面をタップで選択) の 2 か所で使う。
 * WebDemo の数値・状態遷移をそのまま移植している。
 * root (部屋全体)、camera、light を Scene に渡し、毎フレーム frame() を呼ぶ。
 */
class HomeRoom(private val gfx: Gfx, val editable: Boolean) {
    companion object {
        const val BodySize = 1.1f        // 本体の一辺 (シーン単位) = 150mm
        const val MmToScene = BodySize / 150f
        const val RobotBaseY = 0.14f     // 足の先端が床に接地する高さ
        const val RestY = -0.1f          // 足がないとき: 本体の底面が床 (-0.65) に着く高さ
        const val MoveSpeed = 0.015f
        const val Step = 1f / 60f        // WebDemo は requestAnimationFrame (60fps) 基準の定数で動く
        const val DizzyTurns = 1.5f      // この回転量 (回) を短時間に超えると目を回す
        const val DozeAfter = 30f        // 何も触られないと居眠りする (秒)
        const val HoldPoseAfter = 6f     // 付け替え画面: 触ってからこの秒数は、回した向きのまま止めておく (底面などを選べるように)
        const val ZoomMin = 0.45f
        const val ZoomMax = 1.8f
    }

    enum class AIState { Wander, Idle, Look }
    private enum class Reaction { None, Happy, Dizzy, Tickle, Snuggle, Surprised, Bob, Dance, Stretch, Sneeze, Doze, Wake }

    // ------------------------------------------------------------ 通知
    var onStatus: (String) -> Unit = {}
    var onModelBadge: (Boolean, String) -> Unit = { _, _ -> }
    /** UI のデフォルメ Aidy に同じ表情をさせるための通知 (表情, 秒数)。 */
    var onFaceReaction: (AidyFace, Float) -> Unit = { _, _ -> }
    var onModulesChanged: () -> Unit = {}
    /** 3D モデルの面がタップで選ばれた/解除された (null)。editable のときだけ。 */
    var onFaceSelected: (String?) -> Unit = {}

    val root: Node = gfx.empty(null, if (editable) "ModuleEdit3D" else "Home3D")
    val camera = AidyCamera(gfx.engine)
    val light: LightNode
    val clearColor = 0xfff1f5f9.toInt()

    var aiEnabled = true
        private set
    var draggingModel = false
    /** 画面に映っているか (居眠りは映っているときだけ)。 */
    var visible = true
    val faceOn get() = rig.faceOn
    var selectedFace: String? = null
        private set

    /** UI の 1 単位 (dp) の画素数。 */
    var density = 1f

    private val robot: TNode
    private val body: TNode
    val rig: ModuleRig

    private var aiState = AIState.Wander
    private var aiTimer = 0f
    /** 床をタップされて真ん中へ戻っている途中。 */
    private var recalling = false
    private var targetPos = V3.Zero
    private var animTime = 0f
    private var accumulator = 0f

    // 反応
    private var reaction = Reaction.None
    private var reactionTimer = 0f
    private var reactionTime = 0f
    private var spinAccum = 0f
    private var spinVel = 0f
    private var lastTouch = 0f
    private val tapTimes = ArrayList<Float>()
    private var holding = false
    private val faceOverlay: MeshNode
    private val overlayMats = HashMap<Pair<AidyFace, Boolean>, MaterialInstance>()
    private val stars: Node
    private val starNodes = ArrayList<Node>()
    private val zzz: Node
    private val zNodes = ArrayList<Pair<Node, MaterialInstance>>()

    // 面を見せる向き
    private var focusTimer = 0f
    private var focusRot = V3.Zero

    // ズーム (カメラと注視点の距離の倍率)
    private val camLook = V3(0f, 0.2f, 0f)
    private val camBase = if (editable) V3(0f, 1.7f, 3.6f) else V3(0f, 2.2f, 4.2f)
    private var zoom = 1f
    private var zoomTarget = 1f

    private val moduleListener: (String, ModuleKind?, ModuleKind?) -> Unit = { s, r, a -> onModuleChanged(s, r, a) }

    private fun now() = System.nanoTime() / 1e9f

    init {
        // カメラ: PerspectiveCamera(45) position (0, 2.2, 4.2) lookAt (0, 0.2, 0)
        camera.fov = { AidyCamera.homeFov(it) }
        camera.nearPlane = 0.1f
        camera.farPlane = 1000f
        applyCamera()

        // DirectionalLight(0xffffff, 0.85) position (4, 8, 5) + 影
        light = LightNode(gfx.engine, LightManager.Type.DIRECTIONAL) {
            color(1f, 1f, 1f)
            intensity(100_000f * 0.85f)
            val d = -V3(4f, 8f, 5f).normalized()
            direction(d.x, d.y, d.z)
            castShadows(true)
        }

        // 床・ラグ・壁
        if (editable) {
            val floor = gfx.mesh(root, Meshes.plane(8f, 8f), gfx.lit(0xffe2e8f0.toInt(), 0f, 0.4f), false, true, name = "Floor")
            floor.setLocal(V3(0f, -0.65f, 0f), Q.euler(-PI / 2f, 0f, 0f))
            val rug = gfx.mesh(root, Meshes.circle(1.6f, 32), gfx.lit(0xffe0e7ff.toInt(), 0f, 0.8f), false, true, name = "Rug")
            rug.setLocal(V3(0f, -0.64f, 0f), Q.euler(-PI / 2f, 0f, 0f))
            val wall = gfx.mesh(root, Meshes.plane(8f, 4f), gfx.lit(0xfff8fafc.toInt(), 0f, 0.9f), false, true, name = "Wall")
            wall.setLocal(V3(0f, 1.35f, -2.5f))
        } else {
            // ホーム: 家具のある部屋
            RoomDecor(gfx, root)
        }

        // ロボットモデルアセンブリ
        robot = TNode(gfx.empty(root, "RobotGroup")).also { it.p = V3(0f, RobotBaseY, 0f); it.apply() }
        body = TNode(gfx.empty(robot.node, "BodyGroup")).also { it.apply() }

        // 本体 (顔モジュールの有無で 顔あり/顔なし) + モジュール
        rig = ModuleRig(gfx, body.node, BodySize, editable)

        faceOverlay = buildFaceOverlay()
        stars = buildStars()
        zzz = buildZzz()

        ModuleConfig.changed += moduleListener
        lastTouch = now()
        if (editable) toggleAIWalk(false)
    }

    fun destroy() {
        ModuleConfig.changed -= moduleListener
    }

    /** UI 側のイベント登録後に、読み込み結果を通知する。 */
    fun reportModelState() {
        if (!rig.hasModel) onModelBadge(false, "本体の3Dモデルを読み込めなかった…")
        else onModelBadge(true, "準備OK！ 本体とおなじ形だよ")
    }

    // ------------------------------------------------------------ モジュール (ModuleConfig に保存、全 3D モデル共通)

    fun moduleAt(slotId: String) = ModuleConfig.at(slotId)

    private fun onModuleChanged(slot: String, removed: ModuleKind?, added: ModuleKind?) {
        rig.sync()
        lastTouch = now()
        if (added != null)
            startReaction(Reaction.Happy, 1.6f, "♥ ${ModuleCatalog.name(added)}モジュールを付けてもらった！", AidyFace.Happy)
        else if (removed != null)
            startReaction(Reaction.Surprised, 1.2f, "${ModuleCatalog.name(removed)}モジュールが外れた！", AidyFace.Surprised)
        onModulesChanged()
    }

    // ------------------------------------------------------------ 面の選択 (editable: 3D モデルをタップ)

    /** 面を選ぶ: その面をカメラへ向けたまま止まり、面とスロットを光らせる。null で解除。 */
    fun selectFace(face: String?) {
        if (!editable) return
        selectedFace = face
        if (face != null) focusFace(face, Float.MAX_VALUE)
        else {
            focusTimer = 0f
            aiTimer = 0.8f
        }
        rig.setSelectedFace(face)
        onFaceSelected(face)
    }

    /** 3D ビュー上のタップ (ビュー内の画素)。editable: 本体の面を選ぶ (外れたら解除)。ホーム: なでなで。 */
    fun tapAt(x: Float, y: Float) {
        if (wakeIfDozing()) return
        val face = pickFace(x, y)
        if (!editable) {
            if (face != null) petRobotDirectly()
            else if (moduleAtScreen(x, y, 0f) == null) recall()
            return
        }
        if (face == null) {
            // 面を選択中は、ほかの面のパーツのタップには反応しない
            if (selectedFace != null && moduleAtScreen(x, y, 8f) != null) return
            if (selectedFace != null) {
                selectFace(null)
                onStatus("面の選択を解除しました")
            }
            return
        }
        if (countTaps()) return
        if (face != selectedFace) selectFace(face)
        startReaction(Reaction.Bob, 0.4f, "${BodyLayout.faceLabel(face)}を選んだよ ・ モジュールをドラッグしてね", AidyFace.Happy)
    }

    /** body の世界姿勢 (root はシーンの原点)。 */
    private fun bodyWorld(): Pose = robot.pose() * body.pose()

    /** タップ位置の本体の面 (立方体とレイの交差)。外れたら、近くに見えている取り付け済みモジュールの面。 */
    private fun pickFace(x: Float, y: Float): String? {
        val (ro, rd) = camera.ray(x, y)
        val bw = bodyWorld()
        val o = bw.inversePoint(ro)
        val d = bw.inverseDir(rd).let { v -> V3(v.x / bw.s.x, v.y / bw.s.y, v.z / bw.s.z) }
        val h = BodySize * 0.5f
        var tNear = -Float.MAX_VALUE
        var tFar = Float.MAX_VALUE
        var axis = -1
        for (i in 0 until 3) {
            if (abs(d[i]) < 1e-6f) {
                if (o[i] < -h || o[i] > h) {
                    tNear = Float.MAX_VALUE
                    break
                }
                continue
            }
            var t1 = (-h - o[i]) / d[i]
            var t2 = (h - o[i]) / d[i]
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            if (t1 > tNear) { tNear = t1; axis = i }
            tFar = min(tFar, t2)
        }
        if (axis >= 0 && tNear <= tFar && tNear > 0f) {
            val side = sign(o[axis] + d[axis] * tNear)
            val n = when (axis) { 0 -> V3(side, 0f, 0f); 1 -> V3(0f, side, 0f); else -> V3(0f, 0f, side) }
            for (f in BodyLayout.Faces) if ((BodyLayout.faceNormal(f) dot n) > 0.9f) return f
        }
        // 箱から外れた所: 尻尾・耳の先など、モジュールの見た目に重なっていればその面 (面を選択中はその面のモジュールだけ)
        val slot = moduleAtScreen(x, y, 8f, onlySelected = true)
            ?: slotAtScreen(x, y, 70f, { ModuleConfig.at(it.id) != null }, onlyFacing = false)
        return slot?.let { BodyLayout.get(it)?.face }
    }

    /**
     * 画面上で一番近いスロット (maxDp は UI 単位)。面を選んでいればその面だけ、
     * onlyFacing なら手前を向いている面だけを候補にする。
     */
    fun slotAtScreen(x: Float, y: Float, maxDp: Float, filter: ((BodySlot) -> Boolean)?, onlyFacing: Boolean = true, onlySelected: Boolean = true): String? {
        var best = maxDp * density
        var hit: String? = null
        val bw = bodyWorld()
        for (fr in rig.frameList) {
            if (onlySelected && selectedFace != null && fr.slot.face != selectedFace) continue
            if (filter != null && !filter(fr.slot)) continue
            val local = Pose(fr.slot.position * BodySize, fr.slot.mountRotation)
            val world = bw.point(local.point(V3(0f, 0.04f, 0f)))
            val up = bw.dir(local.dir(V3.Up)).normalized()
            if (onlyFacing && (up dot (camera.eye - world).normalized()) < 0.15f) continue
            val sp = camera.worldToScreen(world)
            if (sp.z <= 0f) continue
            val dist = sqrt((sp.x - x) * (sp.x - x) + (sp.y - y) * (sp.y - y))
            if (dist < best) {
                best = dist
                hit = fr.slot.id
            }
        }
        return hit
    }

    /**
     * 画面上で指が重なっている取り付け済みモジュール (見た目の外形を画面に写した四角 + marginDp の余白)。
     * onlySelected なら選択中の面のモジュールだけ (面を選んでいなければ全部)。重なっているときは手前のものを選ぶ。
     */
    fun moduleAtScreen(x: Float, y: Float, marginDp: Float = 18f, onlySelected: Boolean = false): String? {
        val margin = marginDp * density
        val bw = bodyWorld()
        var hit: String? = null
        var bestScore = Float.MAX_VALUE
        for (fr in rig.frameList) {
            if (onlySelected && selectedFace != null && fr.slot.face != selectedFace) continue
            val hull = rig.hullOf(fr.slot.id)
            if (hull.isEmpty()) continue
            val sw = bw * Pose(fr.slot.position * BodySize, fr.slot.mountRotation)
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            var zSum = 0f
            var n = 0
            for (v in hull) {
                val sp = camera.worldToScreen(sw.point(v))
                if (sp.z <= 0f) continue
                x0 = min(x0, sp.x); y0 = min(y0, sp.y); x1 = max(x1, sp.x); y1 = max(y1, sp.y)
                zSum += sp.z
                n++
            }
            if (n == 0) continue
            // 小さいモジュールは指で押さえにくいので、最低でも 44dp 角の当たりにする
            val minHalf = 22f * density
            val cx = (x0 + x1) * 0.5f
            val cy = (y0 + y1) * 0.5f
            val hx = max((x1 - x0) * 0.5f, minHalf) + margin
            val hy = max((y1 - y0) * 0.5f, minHalf) + margin
            if (abs(x - cx) > hx || abs(y - cy) > hy) continue
            // 手前 (奥行きが小さい) を優先。同じくらいなら中心に近いほう
            val score = zSum / n + 0.002f * hypot(x - cx, y - cy) / density
            if (score < bestScore) {
                bestScore = score
                hit = fr.slot.id
            }
        }
        return hit
    }

    /** 画面のその位置に Aidy (本体の箱かモジュール) があるか。 */
    fun isOverRobot(x: Float, y: Float): Boolean = pickFace(x, y) != null || moduleAtScreen(x, y, 0f) != null

    /** ドラッグ中のモジュール (null で終了) と、いま指が重なっているスロット。目印の色を変える。 */
    fun setDropState(kind: ModuleKind?, hoverSlot: String?) = rig.setDrop(kind, hoverSlot)

    /** 面をカメラへ向ける。 */
    fun focusFace(face: String, seconds: Float = 4f) {
        var fr = when (face) {
            "top" -> V3(1.13f, 0f, 0f)
            "bottom" -> V3(-2.0f, 0f, 0f)
            "back" -> V3(0f, PI, 0f)
            "left" -> V3(0f, PI / 2f, 0f)
            "right" -> V3(0f, -PI / 2f, 0f)
            else -> V3.Zero
        }
        // R.y は今の向きから一番近い回り方にする
        fr = fr.copy(y = fr.y + ((robot.r.y - fr.y) / (PI * 2f)).roundToInt() * PI * 2f)
        focusRot = fr
        focusTimer = seconds
    }

    // ------------------------------------------------------------ ズーム (ピンチ / ボタン)

    /** factor > 1 で近づく。 */
    fun zoomBy(factor: Float) {
        zoomTarget = (zoomTarget / factor).coerceIn(ZoomMin, ZoomMax)
        lastTouch = now()
    }

    fun resetZoom() {
        zoomTarget = 1f
    }

    private fun applyCamera() {
        camera.lookAt(camLook + (camBase - camLook) * zoom, camLook)
    }

    /** 3D ビューの上下に重なっている UI の高さ (画素)。モデルが見えている部分の中央に来るよう写る範囲をずらす。 */
    fun setViewInsets(topPx: Float, bottomPx: Float, viewHeightPx: Float) {
        if (viewHeightPx <= 0f) return
        camera.shiftY = (bottomPx - topPx) / viewHeightPx
    }

    // ------------------------------------------------------------ 操作への反応

    /** タップ (なでなで)。短時間に3回以上ならくすぐったがる。 */
    fun petRobotDirectly() {
        if (wakeIfDozing() || countTaps()) return
        if (reaction != Reaction.Dizzy)
            startReaction(Reaction.Happy, 2.5f, "♥ なでなでしてもらってうれしい！", AidyFace.Happy)
    }

    private fun countTaps(): Boolean {
        val now = now()
        tapTimes += now
        tapTimes.removeAll { now - it > 1.2f }
        if (tapTimes.size < 3) return false
        tapTimes.clear()
        startReaction(Reaction.Tickle, 1.6f, "くすぐったい〜！ やめて〜", AidyFace.Happy)
        return true
    }

    /** 指が触れた (居眠りしていたら起きる)。 */
    fun touched() {
        lastTouch = now()
    }

    private fun wakeIfDozing(): Boolean {
        lastTouch = now()
        if (reaction != Reaction.Doze) return false
        startReaction(Reaction.Wake, 0.8f, "はっ！ 寝てないよ！", AidyFace.Surprised)
        return true
    }

    /** 長押し (指を置いたまま) で撫でられてうっとりする。 */
    fun holdStart() {
        if (wakeIfDozing()) return
        holding = true
        startReaction(Reaction.Snuggle, 999f, "なでなで… うっとり", AidyFace.Sleepy)
    }

    fun holdEnd() {
        if (!holding) return
        holding = false
        body.s = V3.One
        startReaction(Reaction.Happy, 1.2f, "♥ もっとなでて！", AidyFace.Happy)
    }

    /** モーションを再生する: dance / stretch / sneeze / doze / dizzy。 */
    fun play(motion: String) {
        when (motion) {
            "dance" -> startReaction(Reaction.Dance, 3.2f, "♪ ごきげんダンス！", AidyFace.Happy)
            "stretch" -> startReaction(Reaction.Stretch, 2f, "うーん… のびのび〜", AidyFace.Sleepy)
            "sneeze" -> startReaction(Reaction.Sneeze, 1.3f, "は… はっ…", AidyFace.Surprised)
            "doze" -> startReaction(Reaction.Doze, 999f, "すやすや… Zzz", AidyFace.Sleepy, "sleep")
            "dizzy" -> startReaction(Reaction.Dizzy, 3.5f, "@_@ 目が回った〜", AidyFace.Dizzy)
        }
    }

    /** screenFace: 顔モニターに出す本体の表情 (null なら face から決める)。 */
    private fun startReaction(r: Reaction, seconds: Float, status: String, face: AidyFace, screenFace: String? = null) {
        if (reaction == Reaction.Snuggle && r != Reaction.Happy) body.s = V3.One
        reaction = r
        reactionTimer = seconds
        reactionTime = 0f
        onStatus(status)
        onFaceReaction(face, min(seconds, 4f))
        showFaceOverlay(face, screenFace)
        stars.isVisible = r == Reaction.Dizzy
        zzz.isVisible = r == Reaction.Doze
    }

    private fun endReaction() {
        val was = reaction
        reaction = Reaction.None
        body.s = V3.One
        showFaceOverlay(null)
        stars.isVisible = false
        zzz.isVisible = false
        rig.wagTails(0f)
        rig.waveHands(0f)
        when (was) {
            Reaction.Dizzy -> onStatus("ふぅ… やっと目が回らなくなった")
            Reaction.Wake -> {
                startReaction(Reaction.Stretch, 2f, "うーん… のびのび〜", AidyFace.Sleepy)
                return
            }
            Reaction.Sneeze -> onStatus("ずずっ… 失礼しました")
            else -> Unit
        }
        if (aiEnabled && was != Reaction.Dizzy && selectedFace == null) chooseNextAIState()
        else aiTimer = 1.5f
    }

    /**
     * 顔モニターに表情を表示する (null で本来の顔に戻す)。顔モジュールがあれば本体と同じ顔 (FaceScreen) で、
     * なければ開口に表情の絵を貼る。
     */
    private fun showFaceOverlay(face: AidyFace?, screenFace: String? = null) {
        val screen = rig.faceScreen
        if (screen != null) {
            faceOverlay.isVisible = false
            if (face == AidyFace.Blink) screen.blink()
            else screen.setFace(screenFace ?: FaceScreen.fromApp(face))
            return
        }
        if (face == null || face == AidyFace.Normal) {
            faceOverlay.isVisible = false
            return
        }
        val on = rig.faceOn
        val mat = overlayMats.getOrPut(face to on) {
            // 顔あり: 白いエナメルの上に濃い目 / 顔なし: 黒いディスプレイに光る目
            val bg = if (on) 0xfff6f6f3.toInt() else 0xff0f172a.toInt()
            val fg = if (on) 0xff1e293b.toInt() else 0xff7dd3fc.toInt()
            val bmp = AidyMascotArt.renderScreen(face, 256, 168, bg, fg)
            val px = IntArray(256 * 168)
            bmp.getPixels(px, 0, 256, 0, 0, 256, 168)
            bmp.recycle()
            val tex = gfx.texture(256, 168)
            gfx.upload(tex, 256, 168, px)
            gfx.unlitTexture(tex)
        }
        faceOverlay.setMaterialInstanceAt(0, mat)
        faceOverlay.isVisible = true
    }

    private fun buildFaceOverlay(): MeshNode {
        // 顔モニター開口の外側の面に貼る
        val monitor = BodyLayout.get("monitor")
        val w = (monitor?.widthMm ?: 74.8f) * MmToScene
        val h = (monitor?.heightMm ?: 51f) * MmToScene
        val c = monitor?.let { it.position * BodySize } ?: V3(0f, 12.5f * MmToScene, BodySize * 0.5f)
        val n = gfx.mesh(body.node, Meshes.plane(w, h), gfx.unlit(0xffffffff.toInt()), name = "FaceOverlay")
        n.setLocal(c + V3(0f, 0f, 0.003f))
        n.isVisible = false
        return n
    }

    private fun buildStars(): Node {
        val s = gfx.empty(body.node, "DizzyStars")
        s.setLocal(V3(0f, BodySize * 0.5f + 0.42f, 0f))
        val star = Meshes.star(0.07f, 0.03f)
        val mat = gfx.unlit(0xfffacc15.toInt())
        for (i in 0 until 3) starNodes += gfx.mesh(s, star, mat, name = "Star$i")
        s.isVisible = false
        return s
    }

    /** 居眠り中に頭の上へ浮かぶ「Z」。 */
    private fun buildZzz(): Node {
        val z = gfx.empty(body.node, "Zzz")
        z.setLocal(uPos(-0.25f, BodySize * 0.5f + 0.25f, 0f))
        val mesh = Meshes.zGlyph(0.22f)
        for (i in 0 until 3) {
            val m = gfx.lit(0x806366f1.toInt(), 0f, 1f)
            val n = gfx.mesh(z, mesh, m, name = "Z$i")
            zNodes += n to m
        }
        z.isVisible = false
        return z
    }

    // ------------------------------------------------------------ 自律AIエージェント

    fun toggleAIWalk(enabled: Boolean) {
        aiEnabled = enabled
        if (!aiEnabled) {
            aiState = AIState.Idle
            robot.p = V3(0f, robot.p.y, 0f)
            robot.r = V3.Zero
            robot.apply()
            if (!editable) onStatus("お散歩はお休み中")
        } else {
            chooseNextAIState()
        }
    }

    private fun chooseNextAIState() {
        if (!aiEnabled) return
        aiTimer = Random.nextFloat() * 3f + 2f

        // ときどき気まぐれなモーション
        val m = Random.nextFloat()
        if (m < 0.08f) { play("dance"); return }
        if (m < 0.14f) { play("stretch"); return }
        if (m < 0.18f) { play("sneeze"); return }

        val r = Random.nextFloat()
        if (r < 0.5f && rig.hasLegs) {
            aiState = AIState.Wander
            targetPos = V3((Random.nextFloat() - 0.5f) * 2.2f, 0f, (Random.nextFloat() - 0.5f) * 1.2f)
            onStatus("気ままにお散歩中…")
        } else if (r < 0.75f) {
            aiState = AIState.Idle
            onStatus(if (rig.hasLegs) "日向ぼっこ中" else "足がないのでお散歩できない…")
        } else {
            aiState = AIState.Look
            onStatus("周りをきょろきょろ")
        }
    }

    /** ドラッグ回転 (dx, dy は dp、y は下向き正)。勢いよく回し続けると目を回す。 */
    fun rotateBy(dx: Float, dy: Float) {
        if (wakeIfDozing()) return
        robot.r = robot.r.copy(y = robot.r.y + dx * 0.01f, x = robot.r.x + dy * 0.01f)
        robot.apply()
        focusTimer = 0f
        if (selectedFace != null) selectFace(null)

        spinAccum += (abs(dx) + abs(dy)) * 0.01f
        spinVel = dx * 0.01f
        if (reaction != Reaction.Dizzy && spinAccum > DizzyTurns * PI * 2f) {
            spinAccum = 0f
            play("dizzy")
        }
    }

    /** 毎フレーム (dt は秒)。 */
    fun frame(dt: Float) {
        accumulator = min(accumulator + dt, 0.25f)
        while (accumulator >= Step) {
            accumulator -= Step
            tick()
        }
        if (abs(zoom - zoomTarget) > 0.0005f) {
            zoom = lerp(zoom, zoomTarget, 1f - exp(-12f * dt))
            applyCamera()
        }
        if (stars.isVisible) orbitStars()
        if (zzz.isVisible) floatZzz()
        rig.update()
    }

    /** 子 (body の下の local) を、カメラに向けた世界回転 world にするための local 回転。 */
    private fun billboard(parentWorld: Pose, localPos: V3, extra: Q): Q {
        val world = parentWorld.point(localPos)
        val worldRot = Q.look(world - camera.eye, V3.Up) * extra
        return parentWorld.r.inverse() * worldRot
    }

    private fun orbitStars() {
        val parent = bodyWorld() * Pose(V3(0f, BodySize * 0.5f + 0.42f, 0f))
        for ((i, s) in starNodes.withIndex()) {
            val a = reactionTime * 4f + i * PI * 2f / 3f
            val p = uPos(cos(a) * 0.32f, sin(a * 2f) * 0.04f, sin(a) * 0.32f)
            s.setLocal(p, billboard(parent, p, uRot(reactionTime * 200f, V3.Forward)))
        }
    }

    private fun floatZzz() {
        val parent = bodyWorld() * Pose(uPos(-0.25f, BodySize * 0.5f + 0.25f, 0f))
        for ((i, pair) in zNodes.withIndex()) {
            val (z, mat) = pair
            val p = repeat(reactionTime * 0.45f + i / 3f, 1f) // 0→1 で下から上へ
            val pos = uPos(-p * 0.25f + sin(p * 6f) * 0.03f, p * 0.45f, 0f)
            val sc = 0.1f * (0.6f + p * 0.9f)
            z.setLocal(pos, billboard(parent, pos, Q.Identity), V3(sc, sc, sc))
            val alpha = sin(p * PI).coerceIn(0f, 1f)
            mat.setColor(((alpha * 255f).roundToInt() shl 24) or 0x6366f1)
        }
    }

    private fun tick() {
        val dt = 0.016f
        spinAccum *= exp(-0.6f * dt) // 回し続けないと溜まらない

        // しばらく触られないと居眠り (見えているときだけ)
        if (reaction == Reaction.None && selectedFace == null && !draggingModel && visible && now() - lastTouch > DozeAfter)
            play("doze")

        if (reaction != Reaction.None) {
            if (!draggingModel || reaction == Reaction.Snuggle) tickReaction(dt)
            // 取り付け直後の喜びジャンプ中も、選んだ面へ向き続ける
            if (focusTimer > 0f && (reaction == Reaction.Happy || reaction == Reaction.Surprised || reaction == Reaction.Bob)) {
                focusTimer -= dt
                robot.r = V3.lerp(robot.r, focusRot, 0.12f)
            }
        } else if (focusTimer > 0f && !draggingModel) {
            focusTimer -= dt
            robot.r = V3.lerp(robot.r, focusRot, 0.12f)
            robot.p = V3.lerp(robot.p, V3(0f, robot.p.y, 0f), 0.08f)
            rig.swingLegs(0f, 0f)
            if (focusTimer <= 0f) aiTimer = 0.5f
        } else if (recalling && !draggingModel) {
            tickRecall()
        } else if (aiEnabled && !draggingModel && !holdingPose()) {
            tickAI(dt)
        } else if (!draggingModel) {
            // お散歩なし: その場で呼吸するように上下
            animTime += 0.03f
            body.p = body.p.copy(y = sin(animTime) * 0.03f)
        }

        // ドラッグで傾いたまま/面を見せた後は、ゆっくり水平に戻る
        if (!draggingModel && !holdingPose() && focusTimer <= 0f && reaction != Reaction.Dizzy && reaction != Reaction.Sneeze && reaction != Reaction.Tickle) {
            robot.r = robot.r.copy(x = lerp(robot.r.x, 0f, 0.08f), z = lerp(robot.r.z, 0f, 0.08f))
        }
        robot.p = robot.p.copy(y = lerp(robot.p.y, if (rig.hasLegs) RobotBaseY else RestY, 0.1f))

        robot.apply()
        body.apply()
    }

    /** いまの向き (スワイプでページを移ったとき、回した分を戻す用)。 */
    fun saveTurn(): V3 = robot.r

    fun restoreTurn(r: V3) {
        robot.r = r
        robot.apply()
    }

    /** 床をタップ: 真ん中へ戻ってくる (お散歩のオン・オフに関係なく)。 */
    fun recall() {
        val d = V3(robot.p.x, 0f, robot.p.z).length
        if (d < 0.08f) {
            startReaction(Reaction.Bob, 0.5f, "ここにいるよ！", AidyFace.Happy)
            return
        }
        recalling = true
        aiState = AIState.Idle
        onStatus("呼ばれた！ 戻るね")
    }

    private fun tickRecall() {
        var dir = V3(-robot.p.x, 0f, -robot.p.z)
        val dist = dir.length
        if (dist > 0.04f) {
            dir = dir.normalized()
            robot.p = robot.p + dir * min(MoveSpeed * 1.8f, dist)
            turnToward(atan2(dir.x, dir.z), 0.18f)
            animTime += 0.24f
            rig.swingLegs(0.5f, animTime)
            rig.waveHands(sin(animTime) * 0.2f)
            body.p = body.p.copy(y = abs(sin(animTime * 2f)) * if (rig.hasLegs) 0.06f else 0.1f)
        } else {
            robot.p = robot.p.copy(x = 0f, z = 0f)
            turnToward(0f, 0.2f) // カメラの方を向く
            rig.swingLegs(0f, 0f)
            if (abs(robot.r.y) < 0.05f) {
                recalling = false
                aiState = AIState.Idle
                aiTimer = 8f // しばらく真ん中にいる
                startReaction(Reaction.Bob, 0.6f, "ただいま！", AidyFace.Happy)
            }
        }
    }

    /** 向き (y 回転) を一番近い回り方で target に寄せる。 */
    private fun turnToward(target: Float, k: Float) {
        var d = (target - robot.r.y) % (PI * 2f)
        if (d > PI) d -= PI * 2f
        if (d < -PI) d += PI * 2f
        robot.r = robot.r.copy(y = robot.r.y + d * k)
    }

    /** 付け替え画面で、さっき回したばかり (向きを保つ間)。 */
    private fun holdingPose() = editable && now() - lastTouch < HoldPoseAfter

    private fun tickAI(dt: Float) {
        aiTimer -= dt
        when (aiState) {
            AIState.Wander -> {
                var dir = targetPos - robot.p
                dir = dir.copy(y = 0f)
                val dist = dir.length
                if (dist > 0.05f) {
                    dir = dir.normalized()
                    robot.p = robot.p + dir * MoveSpeed
                    val targetAngle = atan2(dir.x, dir.z)
                    robot.r = robot.r.copy(y = lerp(robot.r.y, targetAngle, 0.1f))

                    animTime += 0.18f
                    rig.swingLegs(0.4f, animTime)
                    rig.waveHands(sin(animTime) * 0.15f)
                    body.p = body.p.copy(y = abs(sin(animTime * 2f)) * 0.06f)
                } else {
                    chooseNextAIState()
                }
            }
            AIState.Idle -> {
                animTime += 0.03f
                body.p = body.p.copy(y = sin(animTime) * 0.03f)
                rig.swingLegs(0f, 0f)
            }
            AIState.Look -> {
                animTime += 0.05f
                robot.r = robot.r.copy(y = sin(animTime) * 0.5f)
            }
        }
        if (aiTimer <= 0f) chooseNextAIState()
    }

    private fun tickReaction(dt: Float) {
        reactionTime += dt
        reactionTimer -= dt
        val t = reactionTime

        when (reaction) {
            Reaction.Happy -> {
                body.p = body.p.copy(y = abs(sin(t * 15.6f)) * (if (selectedFace != null) 0.1f else 0.25f))
                rig.wagTails(sin(t * 31f) * 0.4f)
                rig.waveHands(sin(t * 12f) * 0.5f)
            }
            Reaction.Bob -> body.p = body.p.copy(y = sin(clamp01(t / 0.4f) * PI) * 0.06f)
            Reaction.Tickle -> {
                // 小刻みにくねくね + ぴょこぴょこ
                robot.r = robot.r.copy(z = sin(t * 28f) * 0.12f)
                body.p = body.p.copy(y = abs(sin(t * 20f)) * 0.08f)
                rig.wagTails(sin(t * 40f) * 0.6f)
                rig.waveHands(sin(t * 30f) * 0.35f)
                rig.swingLegs(0.3f, t * 30f)
            }
            Reaction.Snuggle -> {
                // ゆっくり押しつぶされて伸びる + しっぽゆらゆら
                val squish = 1f - (0.5f + 0.5f * sin(t * 3f)) * 0.06f
                body.s = V3(1f / sqrt(squish), squish, 1f / sqrt(squish))
                body.p = body.p.copy(y = lerp(body.p.y, -0.02f, 0.1f))
                rig.wagTails(sin(t * 2.5f) * 0.25f)
            }
            Reaction.Dizzy -> {
                // 回された勢いが残ってくるくる → ふらふら揺れる
                val fade = clamp01(reactionTimer / 3.5f)
                spinVel *= 0.96f
                robot.r = V3(cos(t * 6f) * 0.12f * fade, robot.r.y + spinVel, sin(t * 6f) * 0.2f * fade)
                body.p = body.p.copy(y = abs(sin(t * 3f)) * 0.03f)
                rig.swingLegs(0.2f * fade, t * 6f)
                rig.waveHands(sin(t * 6f) * 0.3f * fade)
            }
            Reaction.Surprised, Reaction.Wake -> body.p = body.p.copy(y = sin(clamp01(t / 0.35f) * PI) * 0.18f)
            Reaction.Dance -> {
                // くるっと1回転 → 左右にステップ → ジャンプ
                val p = t / 3.2f
                robot.r = robot.r.copy(
                    y = robot.r.y + if (p < 0.3f) 0.33f else 0f,
                    z = if (p >= 0.3f && p < 0.8f) sin(t * 9f) * 0.18f else 0f,
                )
                body.p = body.p.copy(y = abs(sin(t * 9f)) * (if (p > 0.8f) 0.3f else 0.1f))
                rig.waveHands(sin(t * 9f) * 0.6f)
                rig.wagTails(sin(t * 18f) * 0.5f)
                rig.swingLegs(0.35f, t * 18f)
            }
            Reaction.Stretch -> {
                // ぐーっと縦に伸びて、ゆっくり戻る
                val s = sin(clamp01(t / 2f) * PI)
                val sy = 1f + s * 0.14f
                body.s = V3(1f / sqrt(sy), sy, 1f / sqrt(sy))
                body.p = body.p.copy(y = s * 0.08f)
                rig.waveHands(s * 0.7f)
            }
            Reaction.Sneeze -> {
                // ためて (のけぞる) → くしゅんっ (前へがくっ)
                if (t < 0.6f) {
                    robot.r = robot.r.copy(x = lerp(robot.r.x, -0.18f, 0.1f))
                } else {
                    if (t - dt < 0.6f) {
                        onStatus("くしゅんっ！")
                        onFaceReaction(AidyFace.Blink, 0.6f)
                        showFaceOverlay(AidyFace.Blink)
                    }
                    val q = clamp01((t - 0.6f) / 0.25f)
                    robot.r = robot.r.copy(x = lerp(0.3f, 0f, q * q))
                    body.p = body.p.copy(y = sin(q * PI) * 0.1f)
                }
            }
            Reaction.Doze -> {
                // すやすや: ゆっくり大きく呼吸
                val b = 0.5f + 0.5f * sin(t * 1.6f)
                body.s = V3(1f + b * 0.02f, 1f - b * 0.03f, 1f + b * 0.02f)
                body.p = body.p.copy(y = lerp(body.p.y, -0.02f, 0.05f))
                robot.r = robot.r.copy(z = sin(t * 0.8f) * 0.04f)
                rig.swingLegs(0f, 0f)
            }
            Reaction.None -> Unit
        }
        if (reactionTimer <= 0f) endReaction()
    }

    /** 画面上の点 (ビュー内の画素) → スロットの世界座標を画面に投影した位置 (トレイのドラッグ用)。 */
    fun slotScreen(slotId: String): V3? {
        val fr = rig.frameList.firstOrNull { it.slot.id == slotId } ?: return null
        val local = Pose(fr.slot.position * BodySize, fr.slot.mountRotation)
        return camera.worldToScreen(bodyWorld().point(local.point(V3(0f, 0.04f, 0f))))
    }

}
