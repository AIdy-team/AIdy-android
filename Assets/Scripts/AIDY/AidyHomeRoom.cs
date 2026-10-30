using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// 3D ペットルーム + 本体v2 (ModuleConfig のモジュール付き) + 自律AI行動 + 操作への反応。
    /// ホーム画面 (表示のみ) と、AR タブ「画面上」の付け替えプレビュー (editable: 面をタップで選択) の2か所で使う。
    /// WebDemo の数値・状態遷移をそのまま移植している (three.js 座標は Three で変換)。
    /// 本体の寸法・スロット位置は Resources/AIDY/body_v2_layout.json (本体v2.3mf から測った寸法)。
    /// </summary>
    public class AidyHomeRoom : MonoBehaviour
    {
        public const int Layer = 8;      // ホーム
        public const int EditLayer = 10; // 付け替えプレビュー (ライトが混ざらないようホームと分ける)
        const float BodySize = 1.1f;     // 本体の一辺 (シーン単位) = 150mm
        const float MmToScene = BodySize / 150f;
        const float RobotBaseY = 0.14f;  // 足の先端が床に接地する高さ
        const float RestY = -0.1f;       // 足がないとき: 本体の底面が床 (-0.65) に着く高さ
        const float MoveSpeed = 0.015f;
        const float Step = 1f / 60f;     // WebDemo は requestAnimationFrame (60fps) 基準の定数で動く
        const float DizzyTurns = 1.5f;   // この回転量 (回) を短時間に超えると目を回す
        const float DozeAfter = 30f;     // 何も触られないと居眠りする (秒)
        const float ZoomMin = 0.45f, ZoomMax = 1.8f;

        public enum AIState { Wander, Idle, Look }
        enum Reaction { None, Happy, Dizzy, Tickle, Snuggle, Surprised, Bob, Dance, Stretch, Sneeze, Doze, Wake }

        public Camera Cam { get; private set; }
        public event Action<string> StatusChanged;
        public event Action<bool, string> ModelBadgeChanged;
        /// <summary>UI のデフォルメ Aidy に同じ表情をさせるための通知 (表情, 秒数)。</summary>
        public event Action<AidyFace, float> FaceReaction;
        public event Action ModulesChanged;
        /// <summary>3D モデルの面がタップで選ばれた/解除された (null)。editable のときだけ。</summary>
        public event Action<string> FaceSelected;

        public bool Editable { get; private set; }
        public bool AIEnabled { get; private set; } = true;
        public bool DraggingModel;
        public bool FaceOn => rig.FaceOn;
        public string SelectedFace { get; private set; }

        int layer;
        TNode robot, body;
        AidyModuleRig rig;
        RenderTexture rt;
        RawImage target;
        Canvas canvas;

        AIState aiState = AIState.Wander;
        float aiTimer;
        Vector3 targetPos;
        float animTime;
        float accumulator;

        // 反応
        Reaction reaction;
        float reactionTimer, reactionTime, spinAccum, spinVel, lastTouch;
        readonly List<float> tapTimes = new List<float>();
        bool holding;
        GameObject faceOverlay, stars, zzz;
        MeshRenderer faceOverlayRenderer;
        readonly Dictionary<(AidyFace, bool), Material> overlayMats = new Dictionary<(AidyFace, bool), Material>();

        // 面を見せる向き
        float focusTimer;
        Vector3 focusRot;

        // ズーム (カメラと注視点の距離の倍率)
        Vector3 camLook, camBase;
        float zoom = 1f, zoomTarget = 1f;

        public static AidyHomeRoom Create(Transform parent, Vector3 worldOffset, bool editable = false)
        {
            int layer = editable ? EditLayer : Layer;
            var root = new GameObject(editable ? "ModuleEdit3D_Environment" : "Home3D_Environment");
            root.layer = layer;
            root.transform.SetParent(parent, false);
            root.transform.position = worldOffset;
            var room = root.AddComponent<AidyHomeRoom>();
            room.layer = layer;
            room.Editable = editable;
            room.Build();
            if (editable) room.ToggleAIWalk(false);
            return room;
        }

        void Build()
        {
            var t = transform;

            // カメラ: PerspectiveCamera(45) position (0, 2.2, 4.2) lookAt (0, 0.2, 0)
            var camGo = new GameObject("Home_Camera");
            camGo.transform.SetParent(t, false);
            Cam = camGo.AddComponent<Camera>();
            Cam.clearFlags = CameraClearFlags.SolidColor;
            Cam.backgroundColor = Theme.Hex(0xf1f5f9);
            Cam.fieldOfView = 45f;
            Cam.nearClipPlane = 0.1f;
            Cam.farClipPlane = 1000f;
            Cam.cullingMask = 1 << layer;
            Cam.depth = -50;
            Cam.allowHDR = false;
            camLook = Three.Pos(0, 0.2f, 0);
            camBase = Editable ? Three.Pos(0, 1.7f, 3.6f) : Three.Pos(0, 2.2f, 4.2f);
            ApplyCamera();

            // DirectionalLight(0xffffff, 0.85) position (4, 8, 5) + 影
            var lightGo = new GameObject("Directional Light");
            lightGo.transform.SetParent(t, false);
            var light = lightGo.AddComponent<Light>();
            light.type = LightType.Directional;
            light.color = Color.white;
            light.intensity = 0.85f;
            light.shadows = LightShadows.Soft;
            light.shadowStrength = 1f;
            light.shadowBias = 0.03f;
            light.shadowNormalBias = 0.2f;
            light.cullingMask = 1 << layer;
            lightGo.transform.rotation = Quaternion.LookRotation(-Three.Pos(4, 8, 5).normalized, Vector3.up);

            // 床・ラグ・壁
            var floor = AidyMeshes.Create("Floor", t, AidyMeshes.Plane(8, 8), AidyMaterials.Standard(Theme.Hex(0xe2e8f0), 0f, 0.4f), false, true);
            Three.Set(floor.transform, new Vector3(0, -0.65f, 0), new Vector3(-Mathf.PI / 2, 0, 0));
            var rug = AidyMeshes.Create("Rug", t, AidyMeshes.Circle(1.6f, 32), AidyMaterials.Standard(Theme.Hex(Editable ? 0xe0e7ffu : 0xdbeafeu), 0f, 0.8f), false, true);
            Three.Set(rug.transform, new Vector3(0, -0.64f, 0), new Vector3(-Mathf.PI / 2, 0, 0));
            var wall = AidyMeshes.Create("Wall", t, AidyMeshes.Plane(8, 4), AidyMaterials.Standard(Theme.Hex(0xf8fafc), 0f, 0.9f), false, true);
            Three.Set(wall.transform, new Vector3(0, 1.35f, -2.5f), Vector3.zero);
            Three.SetLayer(gameObject, layer);

            // ロボットモデルアセンブリ
            robot = new TNode("RobotGroup", t) { P = new Vector3(0, RobotBaseY, 0) };
            robot.Apply();
            body = new TNode("BodyGroup", robot.T);
            body.Apply();

            // 本体 (顔モジュールの有無で 顔あり/顔なし) + モジュール
            rig = new AidyModuleRig(body.T, BodySize, layer, Editable);

            BuildFaceOverlay();
            BuildStars();
            BuildZzz();

            ModuleConfig.Changed += OnModuleChanged;
            lastTouch = Time.unscaledTime;
        }

        void OnDestroy()
        {
            ModuleConfig.Changed -= OnModuleChanged;
            if (rt != null) rt.Release();
        }

        /// <summary>UI 側のイベント登録後に、読み込み結果を通知する。</summary>
        public void ReportModelState()
        {
            if (!rig.HasModel) ModelBadgeChanged?.Invoke(false, "本体の3Dモデルを読み込めなかった…");
            else ModelBadgeChanged?.Invoke(true, "準備OK！ 本体とおなじ形だよ");
        }

        public void BindTarget(RawImage image)
        {
            target = image;
            canvas = image.canvas;
        }

        // ------------------------------------------------------------ モジュール (ModuleConfig に保存、全 3D モデル共通)

        public ModuleKind? ModuleAt(string slotId) => ModuleConfig.At(slotId);
        public bool CanAttach(ModuleKind kind, string slotId) => ModuleConfig.CanAttach(kind, slotId);
        public bool Attach(ModuleKind kind, string slotId) => ModuleConfig.Attach(kind, slotId);
        public bool Replace(ModuleKind kind, string slotId) => ModuleConfig.Replace(kind, slotId);
        public void Detach(string slotId) => ModuleConfig.Detach(slotId);

        void OnModuleChanged(string slot, ModuleKind? removed, ModuleKind? added)
        {
            rig.Sync();
            lastTouch = Time.unscaledTime;
            if (added.HasValue)
                StartReaction(Reaction.Happy, 1.6f, $"♥ {ModuleCatalog.Name(added.Value)}モジュールを付けてもらった！", AidyFace.Happy);
            else if (removed.HasValue)
                StartReaction(Reaction.Surprised, 1.2f, $"{ModuleCatalog.Name(removed.Value)}モジュールが外れた！", AidyFace.Surprised);
            ModulesChanged?.Invoke();
        }

        // ------------------------------------------------------------ 面の選択 (editable: 3D モデルをタップ)

        /// <summary>面を選ぶ: その面をカメラへ向けたまま止まり、面とスロットを光らせる。null で解除。</summary>
        public void SelectFace(string face)
        {
            if (!Editable) return;
            SelectedFace = face;
            if (face != null) FocusFace(face, float.MaxValue);
            else
            {
                focusTimer = 0f;
                aiTimer = 0.8f;
            }
            rig.SetSelectedFace(face);
            FaceSelected?.Invoke(face);
        }

        /// <summary>3D ビュー上のタップ。editable: 本体の面を選ぶ (外れたら解除)。ホーム: なでなで。</summary>
        public void TapAt(Vector2 screen)
        {
            if (WakeIfDozing()) return;
            string face = PickFace(screen);
            if (!Editable)
            {
                if (face != null) PetRobotDirectly();
                return;
            }
            if (face == null)
            {
                if (SelectedFace != null)
                {
                    SelectFace(null);
                    StatusChanged?.Invoke("面の選択を解除しました");
                }
                return;
            }
            if (CountTaps()) return;
            if (face != SelectedFace) SelectFace(face);
            StartReaction(Reaction.Bob, 0.4f, $"{BodyLayout.FaceLabel(face)}を選んだよ ・ モジュールをドラッグしてね", AidyFace.Happy);
        }

        /// <summary>スクリーン座標 → 3D ビューのビューポート座標 (0〜1)。</summary>
        public bool ScreenToViewport(Vector2 screen, out Vector2 vp)
        {
            vp = default;
            if (target == null) return false;
            RectTransform r = target.rectTransform;
            if (!RectTransformUtility.ScreenPointToLocalPointInRectangle(r, screen, null, out Vector2 local)) return false;
            Rect rect = r.rect;
            vp = new Vector2((local.x - rect.x) / rect.width, (local.y - rect.y) / rect.height);
            return vp.x >= 0 && vp.x <= 1 && vp.y >= 0 && vp.y <= 1;
        }

        Vector2 WorldToScreen(Vector3 world)
        {
            Vector3 vp = Cam.WorldToViewportPoint(world);
            Rect rect = target.rectTransform.rect;
            var local = new Vector3(rect.x + vp.x * rect.width, rect.y + vp.y * rect.height, 0);
            return RectTransformUtility.WorldToScreenPoint(null, target.rectTransform.TransformPoint(local));
        }

        /// <summary>タップ位置の本体の面 (立方体とレイの交差)。外れたら、近くに見えている取り付け済みモジュールの面。</summary>
        string PickFace(Vector2 screen)
        {
            if (!ScreenToViewport(screen, out Vector2 vp)) return null;
            Ray ray = Cam.ViewportPointToRay(vp);
            Vector3 o = body.T.InverseTransformPoint(ray.origin);
            Vector3 d = body.T.InverseTransformDirection(ray.direction);
            float h = BodySize * 0.5f, tNear = float.MinValue, tFar = float.MaxValue;
            int axis = -1;
            for (int i = 0; i < 3; i++)
            {
                if (Mathf.Abs(d[i]) < 1e-6f)
                {
                    if (o[i] < -h || o[i] > h) { tNear = float.MaxValue; break; }
                    continue;
                }
                float t1 = (-h - o[i]) / d[i], t2 = (h - o[i]) / d[i];
                if (t1 > t2) (t1, t2) = (t2, t1);
                if (t1 > tNear) { tNear = t1; axis = i; }
                tFar = Mathf.Min(tFar, t2);
            }
            if (axis >= 0 && tNear <= tFar && tNear > 0f)
            {
                float side = Mathf.Sign(o[axis] + d[axis] * tNear);
                var n = Vector3.zero;
                n[axis] = side;
                foreach (string f in BodyLayout.Faces)
                    if (Vector3.Dot(BodyLayout.FaceNormal(f), n) > 0.9f) return f;
            }
            string slot = SlotAtScreen(screen, 70f, s => ModuleConfig.At(s.Id).HasValue, false, false);
            return slot != null ? BodyLayout.Get(slot).Face : null;
        }

        /// <summary>
        /// スクリーン上で一番近いスロット (maxPx は UI 単位)。面を選んでいればその面だけ、
        /// onlyFacing なら手前を向いている面だけを候補にする。
        /// </summary>
        public string SlotAtScreen(Vector2 screen, float maxPx, Func<BodySlot, bool> filter, bool onlyFacing = true, bool onlySelected = true)
        {
            if (target == null) return null;
            float scale = canvas != null ? canvas.scaleFactor : 1f;
            float best = maxPx * scale;
            string hit = null;
            foreach (AidyModuleRig.SlotFrame fr in rig.Frames)
            {
                if (onlySelected && SelectedFace != null && fr.Slot.Face != SelectedFace) continue;
                if (filter != null && !filter(fr.Slot)) continue;
                Vector3 world = fr.T.TransformPoint(new Vector3(0, 0.04f, 0));
                if (onlyFacing && Vector3.Dot(fr.T.up, (Cam.transform.position - world).normalized) < 0.15f) continue;
                float dist = Vector2.Distance(WorldToScreen(world), screen);
                if (dist < best)
                {
                    best = dist;
                    hit = fr.Slot.Id;
                }
            }
            return hit;
        }

        /// <summary>ドラッグ中のモジュール (null で終了) と、いま指が重なっているスロット。目印の色を変える。</summary>
        public void SetDropState(ModuleKind? kind, string hoverSlot) => rig.SetDrop(kind, hoverSlot);

        /// <summary>面をカメラへ向ける。</summary>
        public void FocusFace(string face, float seconds = 4f)
        {
            switch (face)
            {
                case "top": focusRot = new Vector3(1.13f, 0, 0); break;
                case "bottom": focusRot = new Vector3(-2.0f, 0, 0); break;
                case "back": focusRot = new Vector3(0, Mathf.PI, 0); break;
                case "left": focusRot = new Vector3(0, Mathf.PI / 2f, 0); break;
                case "right": focusRot = new Vector3(0, -Mathf.PI / 2f, 0); break;
                default: focusRot = Vector3.zero; break;
            }
            // R.y は今の向きから一番近い回り方にする
            focusRot.y += Mathf.Round((robot.R.y - focusRot.y) / (Mathf.PI * 2f)) * Mathf.PI * 2f;
            focusTimer = seconds;
        }

        // ------------------------------------------------------------ ズーム (ピンチ / ホイール / ボタン)

        /// <summary>factor &gt; 1 で近づく。</summary>
        public void ZoomBy(float factor)
        {
            zoomTarget = Mathf.Clamp(zoomTarget / factor, ZoomMin, ZoomMax);
            lastTouch = Time.unscaledTime;
        }

        public void ResetZoom() => zoomTarget = 1f;

        void ApplyCamera()
        {
            Cam.transform.localPosition = camLook + (camBase - camLook) * zoom;
            Cam.transform.LookAt(transform.TransformPoint(camLook), Vector3.up);
        }

        // ------------------------------------------------------------ 操作への反応

        /// <summary>タップ (なでなで)。短時間に3回以上ならくすぐったがる。</summary>
        public void PetRobotDirectly()
        {
            if (WakeIfDozing() || CountTaps()) return;
            if (reaction != Reaction.Dizzy)
                StartReaction(Reaction.Happy, 2.5f, "♥ なでなでしてもらってうれしい！", AidyFace.Happy);
        }

        bool CountTaps()
        {
            float now = Time.unscaledTime;
            tapTimes.Add(now);
            tapTimes.RemoveAll(t => now - t > 1.2f);
            if (tapTimes.Count < 3) return false;
            tapTimes.Clear();
            StartReaction(Reaction.Tickle, 1.6f, "くすぐったい〜！ やめて〜", AidyFace.Happy);
            return true;
        }

        /// <summary>指が触れた (居眠りしていたら起きる)。</summary>
        public void Touched()
        {
            lastTouch = Time.unscaledTime;
        }

        bool WakeIfDozing()
        {
            lastTouch = Time.unscaledTime;
            if (reaction != Reaction.Doze) return false;
            StartReaction(Reaction.Wake, 0.8f, "はっ！ 寝てないよ！", AidyFace.Surprised);
            return true;
        }

        /// <summary>長押し (指を置いたまま) で撫でられてうっとりする。</summary>
        public void HoldStart()
        {
            if (WakeIfDozing()) return;
            holding = true;
            StartReaction(Reaction.Snuggle, 999f, "なでなで… うっとり", AidyFace.Sleepy);
        }

        public void HoldEnd()
        {
            if (!holding) return;
            holding = false;
            body.T.localScale = Vector3.one;
            StartReaction(Reaction.Happy, 1.2f, "♥ もっとなでて！", AidyFace.Happy);
        }

        /// <summary>モーションを再生する: dance / stretch / sneeze / doze / dizzy。</summary>
        public void Play(string motion)
        {
            switch (motion)
            {
                case "dance": StartReaction(Reaction.Dance, 3.2f, "♪ ごきげんダンス！", AidyFace.Happy); break;
                case "stretch": StartReaction(Reaction.Stretch, 2f, "うーん… のびのび〜", AidyFace.Sleepy); break;
                case "sneeze": StartReaction(Reaction.Sneeze, 1.3f, "は… はっ…", AidyFace.Surprised); break;
                case "doze": StartReaction(Reaction.Doze, 999f, "すやすや… Zzz", AidyFace.Sleepy, "sleep"); break;
                case "dizzy": StartReaction(Reaction.Dizzy, 3.5f, "@_@ 目が回った〜", AidyFace.Dizzy); break;
            }
        }

        /// <summary>screenFace: 顔モニターに出す本体の表情 (null なら face から決める)。</summary>
        void StartReaction(Reaction r, float seconds, string status, AidyFace face, string screenFace = null)
        {
            if (reaction == Reaction.Snuggle && r != Reaction.Happy) body.T.localScale = Vector3.one;
            reaction = r;
            reactionTimer = seconds;
            reactionTime = 0f;
            StatusChanged?.Invoke(status);
            FaceReaction?.Invoke(face, Mathf.Min(seconds, 4f));
            ShowFaceOverlay(face, screenFace);
            stars.SetActive(r == Reaction.Dizzy);
            zzz.SetActive(r == Reaction.Doze);
        }

        void EndReaction()
        {
            Reaction was = reaction;
            reaction = Reaction.None;
            body.T.localScale = Vector3.one;
            ShowFaceOverlay(null);
            stars.SetActive(false);
            zzz.SetActive(false);
            rig.WagTails(0);
            rig.WaveHands(0);
            switch (was)
            {
                case Reaction.Dizzy: StatusChanged?.Invoke("ふぅ… やっと目が回らなくなった"); break;
                case Reaction.Wake: StartReaction(Reaction.Stretch, 2f, "うーん… のびのび〜", AidyFace.Sleepy); return;
                case Reaction.Sneeze: StatusChanged?.Invoke("ずずっ… 失礼しました"); break;
            }
            if (AIEnabled && was != Reaction.Dizzy && SelectedFace == null) ChooseNextAIState();
            else aiTimer = 1.5f;
        }

        /// <summary>
        /// 顔モニターに表情を表示する (null で本来の顔に戻す)。顔モジュールがあれば本体と同じ顔 (AidyFaceScreen) で、
        /// なければ開口に表情の絵を貼る。
        /// </summary>
        void ShowFaceOverlay(AidyFace? face, string screenFace = null)
        {
            AidyFaceScreen screen = rig.FaceOn ? rig.Bodies.Screen : null;
            if (screen != null)
            {
                faceOverlay.SetActive(false);
                if (face == AidyFace.Blink) screen.Blink();
                else screen.SetFace(screenFace ?? AidyFaceScreen.FromApp(face));
                return;
            }
            if (face == null || face == AidyFace.Normal)
            {
                faceOverlay.SetActive(false);
                return;
            }
            bool on = rig.FaceOn;
            var key = (face.Value, on);
            if (!overlayMats.TryGetValue(key, out Material mat))
            {
                // 顔あり: 白いエナメルの上に濃い目 / 顔なし: 黒いディスプレイに光る目
                Color bg = on ? new Color(0.964706f, 0.964706f, 0.952941f) : Theme.Hex(0x0f172a);
                Color fg = on ? Theme.Hex(0x1e293b) : Theme.Hex(0x7dd3fc);
                overlayMats[key] = mat = AidyMaterials.Textured(AidyMascotArt.RenderScreen(face.Value, 256, 168, bg, fg));
            }
            faceOverlayRenderer.sharedMaterial = mat;
            faceOverlay.SetActive(true);
        }

        void BuildFaceOverlay()
        {
            // 顔モニター開口 84x55mm (中心 y=+12.5mm) の外側の面に貼る
            BodySlot monitor = BodyLayout.Get("monitor");
            float w = (monitor != null ? monitor.WidthMm : 74.8f) * MmToScene;
            float h = (monitor != null ? monitor.HeightMm : 51f) * MmToScene;
            Vector3 c = monitor != null ? monitor.Position * BodySize : new Vector3(0, 12.5f * MmToScene, BodySize * 0.5f);
            faceOverlay = AidyMeshes.Create("FaceOverlay", body.T, AidyMeshes.Plane(w, h), AidyMaterials.Basic(Color.white));
            faceOverlay.transform.localPosition = c + new Vector3(0, 0, 0.003f);
            faceOverlayRenderer = faceOverlay.GetComponent<MeshRenderer>();
            faceOverlay.SetActive(false);
        }

        void BuildStars()
        {
            stars = new GameObject("DizzyStars");
            stars.layer = layer;
            stars.transform.SetParent(body.T, false);
            stars.transform.localPosition = new Vector3(0, BodySize * 0.5f + 0.42f, 0);
            Mesh star = StarMesh(0.07f, 0.03f);
            var mat = AidyMaterials.Basic(Theme.Hex(0xfacc15));
            for (int i = 0; i < 3; i++) AidyMeshes.Create("Star" + i, stars.transform, star, mat);
            stars.SetActive(false);
        }

        /// <summary>居眠り中に頭の上へ浮かぶ「Z」。</summary>
        void BuildZzz()
        {
            zzz = new GameObject("Zzz");
            zzz.layer = layer;
            zzz.transform.SetParent(body.T, false);
            zzz.transform.localPosition = new Vector3(-0.25f, BodySize * 0.5f + 0.25f, 0);
            Mesh z = ZMesh(0.1f);
            for (int i = 0; i < 3; i++)
            {
                var m = AidyMaterials.Basic(Theme.Hex(0x6366f1, 1f));
                m.SetFloat("_SrcBlend", (float)UnityEngine.Rendering.BlendMode.SrcAlpha);
                m.SetFloat("_DstBlend", (float)UnityEngine.Rendering.BlendMode.OneMinusSrcAlpha);
                m.SetFloat("_ZWrite", 0f);
                m.renderQueue = 3000;
                AidyMeshes.Create("Z" + i, zzz.transform, z, m);
            }
            zzz.SetActive(false);
        }

        static Mesh StarMesh(float outer, float inner)
        {
            var v = new Vector3[11];
            var tri = new int[30];
            v[0] = Vector3.zero;
            for (int i = 0; i < 10; i++)
            {
                float a = Mathf.PI / 2f + i * Mathf.PI / 5f;
                float r = i % 2 == 0 ? outer : inner;
                v[i + 1] = new Vector3(Mathf.Cos(a) * r, Mathf.Sin(a) * r, 0);
                tri[i * 3] = 0;
                tri[i * 3 + 1] = i + 1;
                tri[i * 3 + 2] = (i + 1) % 10 + 1;
            }
            var m = new Mesh { name = "Star", vertices = v, triangles = tri };
            m.RecalculateBounds();
            return m;
        }

        /// <summary>「Z」の字 (上の棒・斜め・下の棒) の板ポリ。</summary>
        static Mesh ZMesh(float s)
        {
            float t = 0.22f * s, h = s * 0.5f;
            var v = new[]
            {
                new Vector3(-h, h - t, 0), new Vector3(h, h - t, 0), new Vector3(h, h, 0), new Vector3(-h, h, 0),     // 上
                new Vector3(-h, -h, 0), new Vector3(h, -h, 0), new Vector3(h, -h + t, 0), new Vector3(-h, -h + t, 0), // 下
                new Vector3(h - t * 1.3f, h - t, 0), new Vector3(h, h - t, 0), new Vector3(-h + t * 1.3f, -h + t, 0), new Vector3(-h, -h + t, 0) // 斜め
            };
            var tri = new[] { 0, 2, 1, 0, 3, 2, 4, 6, 5, 4, 7, 6, 8, 9, 10, 8, 10, 11 };
            var m = new Mesh { name = "Z", vertices = v, triangles = tri };
            m.RecalculateBounds();
            return m;
        }

        // ------------------------------------------------------------ 自律AIエージェント

        public void ToggleAIWalk(bool enabled)
        {
            AIEnabled = enabled;
            if (!AIEnabled)
            {
                aiState = AIState.Idle;
                robot.P = new Vector3(0, robot.P.y, 0);
                robot.R = Vector3.zero;
                robot.Apply();
                if (!Editable) StatusChanged?.Invoke("お散歩はお休み中");
            }
            else
            {
                ChooseNextAIState();
            }
        }

        void ChooseNextAIState()
        {
            if (!AIEnabled) return;
            aiTimer = UnityEngine.Random.value * 3f + 2f;

            // ときどき気まぐれなモーション
            float m = UnityEngine.Random.value;
            if (m < 0.08f) { Play("dance"); return; }
            if (m < 0.14f) { Play("stretch"); return; }
            if (m < 0.18f) { Play("sneeze"); return; }

            float r = UnityEngine.Random.value;
            if (r < 0.5f && rig.HasLegs)
            {
                aiState = AIState.Wander;
                targetPos = new Vector3((UnityEngine.Random.value - 0.5f) * 2.2f, 0, (UnityEngine.Random.value - 0.5f) * 1.2f);
                StatusChanged?.Invoke("気ままにお散歩中…");
            }
            else if (r < 0.75f)
            {
                aiState = AIState.Idle;
                StatusChanged?.Invoke(rig.HasLegs ? "日向ぼっこ中" : "足がないのでお散歩できない…");
            }
            else
            {
                aiState = AIState.Look;
                StatusChanged?.Invoke("周りをきょろきょろ");
            }
        }

        /// <summary>ドラッグ回転 (dx, dy は CSS px 相当、y は下向き正)。勢いよく回し続けると目を回す。</summary>
        public void RotateBy(float dx, float dy)
        {
            if (WakeIfDozing()) return;
            robot.R.y += dx * 0.01f;
            robot.R.x += dy * 0.01f;
            robot.Apply();
            focusTimer = 0f;
            if (SelectedFace != null) SelectFace(null);

            spinAccum += (Mathf.Abs(dx) + Mathf.Abs(dy)) * 0.01f;
            spinVel = dx * 0.01f;
            if (reaction != Reaction.Dizzy && spinAccum > DizzyTurns * Mathf.PI * 2f)
            {
                spinAccum = 0f;
                Play("dizzy");
            }
        }

        void Update()
        {
            accumulator = Mathf.Min(accumulator + Time.deltaTime, 0.25f);
            while (accumulator >= Step)
            {
                accumulator -= Step;
                Tick();
            }
            if (Mathf.Abs(zoom - zoomTarget) > 0.0005f)
            {
                zoom = Mathf.Lerp(zoom, zoomTarget, 1f - Mathf.Exp(-12f * Time.unscaledDeltaTime));
                ApplyCamera();
            }
            if (stars.activeSelf) OrbitStars();
            if (zzz.activeSelf) FloatZzz();
        }

        void OrbitStars()
        {
            int i = 0;
            foreach (Transform s in stars.transform)
            {
                float a = reactionTime * 4f + i * Mathf.PI * 2f / 3f;
                s.localPosition = new Vector3(Mathf.Cos(a) * 0.32f, Mathf.Sin(a * 2f) * 0.04f, Mathf.Sin(a) * 0.32f);
                s.rotation = Quaternion.LookRotation(s.position - Cam.transform.position, Vector3.up) * Quaternion.AngleAxis(reactionTime * 200f, Vector3.forward);
                i++;
            }
        }

        void FloatZzz()
        {
            int i = 0;
            foreach (Transform z in zzz.transform)
            {
                float p = Mathf.Repeat(reactionTime * 0.45f + i / 3f, 1f); // 0→1 で下から上へ
                z.localPosition = new Vector3(-p * 0.25f + Mathf.Sin(p * 6f) * 0.03f, p * 0.45f, 0);
                z.localScale = Vector3.one * (0.6f + p * 0.9f);
                z.rotation = Quaternion.LookRotation(z.position - Cam.transform.position, Vector3.up);
                var mr = z.GetComponent<MeshRenderer>();
                Color c = Theme.Hex(0x6366f1);
                c.a = Mathf.Sin(p * Mathf.PI);
                mr.sharedMaterial.SetColor("_Color", c);
                i++;
            }
        }

        void Tick()
        {
            const float dt = 0.016f;
            spinAccum *= Mathf.Exp(-0.6f * dt); // 回し続けないと溜まらない

            // しばらく触られないと居眠り (見えているときだけ)
            if (reaction == Reaction.None && SelectedFace == null && !DraggingModel && Cam.enabled && Time.unscaledTime - lastTouch > DozeAfter)
                Play("doze");

            if (reaction != Reaction.None)
            {
                if (!DraggingModel || reaction == Reaction.Snuggle) TickReaction(dt);
                // 取り付け直後の喜びジャンプ中も、選んだ面へ向き続ける
                if (focusTimer > 0f && (reaction == Reaction.Happy || reaction == Reaction.Surprised || reaction == Reaction.Bob))
                {
                    focusTimer -= dt;
                    robot.R = Vector3.Lerp(robot.R, focusRot, 0.12f);
                }
            }
            else if (focusTimer > 0f && !DraggingModel)
            {
                focusTimer -= dt;
                robot.R = Vector3.Lerp(robot.R, focusRot, 0.12f);
                robot.P = Vector3.Lerp(robot.P, new Vector3(0, robot.P.y, 0), 0.08f);
                rig.SwingLegs(0, 0);
                if (focusTimer <= 0f) aiTimer = 0.5f;
            }
            else if (AIEnabled && !DraggingModel)
            {
                TickAI(dt);
            }
            else if (!DraggingModel)
            {
                // お散歩なし: その場で呼吸するように上下
                animTime += 0.03f;
                body.P.y = Mathf.Sin(animTime) * 0.03f;
            }

            // ドラッグで傾いたまま/面を見せた後は、ゆっくり水平に戻る
            if (!DraggingModel && focusTimer <= 0f && reaction != Reaction.Dizzy && reaction != Reaction.Sneeze && reaction != Reaction.Tickle)
            {
                robot.R.x = Mathf.Lerp(robot.R.x, 0, 0.08f);
                robot.R.z = Mathf.Lerp(robot.R.z, 0, 0.08f);
            }
            robot.P.y = Mathf.Lerp(robot.P.y, rig.HasLegs ? RobotBaseY : RestY, 0.1f);

            robot.Apply();
            body.Apply();
        }

        void TickAI(float dt)
        {
            aiTimer -= dt;
            switch (aiState)
            {
                case AIState.Wander:
                {
                    Vector3 dir = targetPos - robot.P;
                    dir.y = 0;
                    float dist = dir.magnitude;
                    if (dist > 0.05f)
                    {
                        dir.Normalize();
                        robot.P += dir * MoveSpeed;
                        float targetAngle = Mathf.Atan2(dir.x, dir.z);
                        robot.R.y = Mathf.LerpUnclamped(robot.R.y, targetAngle, 0.1f);

                        animTime += 0.18f;
                        rig.SwingLegs(0.4f, animTime);
                        rig.WaveHands(Mathf.Sin(animTime) * 0.15f);
                        body.P.y = Mathf.Abs(Mathf.Sin(animTime * 2f)) * 0.06f;
                    }
                    else
                    {
                        ChooseNextAIState();
                    }
                    break;
                }
                case AIState.Idle:
                    animTime += 0.03f;
                    body.P.y = Mathf.Sin(animTime) * 0.03f;
                    rig.SwingLegs(0, 0);
                    break;
                case AIState.Look:
                    animTime += 0.05f;
                    robot.R.y = Mathf.Sin(animTime) * 0.5f;
                    break;
            }

            if (aiTimer <= 0f) ChooseNextAIState();
        }

        void TickReaction(float dt)
        {
            reactionTime += dt;
            reactionTimer -= dt;
            float t = reactionTime;

            switch (reaction)
            {
                case Reaction.Happy:
                    body.P.y = Mathf.Abs(Mathf.Sin(t * 15.6f)) * (SelectedFace != null ? 0.1f : 0.25f);
                    rig.WagTails(Mathf.Sin(t * 31f) * 0.4f);
                    rig.WaveHands(Mathf.Sin(t * 12f) * 0.5f);
                    break;
                case Reaction.Bob:
                    body.P.y = Mathf.Sin(Mathf.Clamp01(t / 0.4f) * Mathf.PI) * 0.06f;
                    break;
                case Reaction.Tickle:
                    // 小刻みにくねくね + ぴょこぴょこ
                    robot.R.z = Mathf.Sin(t * 28f) * 0.12f;
                    body.P.y = Mathf.Abs(Mathf.Sin(t * 20f)) * 0.08f;
                    rig.WagTails(Mathf.Sin(t * 40f) * 0.6f);
                    rig.WaveHands(Mathf.Sin(t * 30f) * 0.35f);
                    rig.SwingLegs(0.3f, t * 30f);
                    break;
                case Reaction.Snuggle:
                {
                    // ゆっくり押しつぶされて伸びる + しっぽゆらゆら
                    float squish = 1f - (0.5f + 0.5f * Mathf.Sin(t * 3f)) * 0.06f;
                    body.T.localScale = new Vector3(1f / Mathf.Sqrt(squish), squish, 1f / Mathf.Sqrt(squish));
                    body.P.y = Mathf.Lerp(body.P.y, -0.02f, 0.1f);
                    rig.WagTails(Mathf.Sin(t * 2.5f) * 0.25f);
                    break;
                }
                case Reaction.Dizzy:
                {
                    // 回された勢いが残ってくるくる → ふらふら揺れる
                    float fade = Mathf.Clamp01(reactionTimer / 3.5f);
                    spinVel *= 0.96f;
                    robot.R.y += spinVel;
                    robot.R.z = Mathf.Sin(t * 6f) * 0.2f * fade;
                    robot.R.x = Mathf.Cos(t * 6f) * 0.12f * fade;
                    body.P.y = Mathf.Abs(Mathf.Sin(t * 3f)) * 0.03f;
                    rig.SwingLegs(0.2f * fade, t * 6f);
                    rig.WaveHands(Mathf.Sin(t * 6f) * 0.3f * fade);
                    break;
                }
                case Reaction.Surprised:
                case Reaction.Wake:
                    body.P.y = Mathf.Sin(Mathf.Clamp01(t / 0.35f) * Mathf.PI) * 0.18f;
                    break;
                case Reaction.Dance:
                {
                    // くるっと1回転 → 左右にステップ → ジャンプ
                    float p = t / 3.2f;
                    robot.R.y += p < 0.3f ? 0.33f : 0f;
                    robot.R.z = p >= 0.3f && p < 0.8f ? Mathf.Sin(t * 9f) * 0.18f : 0f;
                    body.P.y = Mathf.Abs(Mathf.Sin(t * 9f)) * (p > 0.8f ? 0.3f : 0.1f);
                    rig.WaveHands(Mathf.Sin(t * 9f) * 0.6f);
                    rig.WagTails(Mathf.Sin(t * 18f) * 0.5f);
                    rig.SwingLegs(0.35f, t * 18f);
                    break;
                }
                case Reaction.Stretch:
                {
                    // ぐーっと縦に伸びて、ゆっくり戻る
                    float s = Mathf.Sin(Mathf.Clamp01(t / 2f) * Mathf.PI);
                    float sy = 1f + s * 0.14f;
                    body.T.localScale = new Vector3(1f / Mathf.Sqrt(sy), sy, 1f / Mathf.Sqrt(sy));
                    body.P.y = s * 0.08f;
                    rig.WaveHands(s * 0.7f);
                    break;
                }
                case Reaction.Sneeze:
                    // ためて (のけぞる) → くしゅんっ (前へがくっ)
                    if (t < 0.6f)
                    {
                        robot.R.x = Mathf.Lerp(robot.R.x, -0.18f, 0.1f);
                    }
                    else
                    {
                        if (t - dt < 0.6f)
                        {
                            StatusChanged?.Invoke("くしゅんっ！");
                            FaceReaction?.Invoke(AidyFace.Blink, 0.6f);
                            ShowFaceOverlay(AidyFace.Blink);
                        }
                        float q = Mathf.Clamp01((t - 0.6f) / 0.25f);
                        robot.R.x = Mathf.Lerp(0.3f, 0f, q * q);
                        body.P.y = Mathf.Sin(q * Mathf.PI) * 0.1f;
                    }
                    break;
                case Reaction.Doze:
                {
                    // すやすや: ゆっくり大きく呼吸
                    float b = 0.5f + 0.5f * Mathf.Sin(t * 1.6f);
                    body.T.localScale = new Vector3(1f + b * 0.02f, 1f - b * 0.03f, 1f + b * 0.02f);
                    body.P.y = Mathf.Lerp(body.P.y, -0.02f, 0.05f);
                    robot.R.z = Mathf.Sin(t * 0.8f) * 0.04f;
                    rig.SwingLegs(0, 0);
                    break;
                }
            }

            if (reactionTimer <= 0f) EndReaction();
        }

        // ------------------------------------------------------------ RenderTexture (canvas-container 内に表示)

        void LateUpdate()
        {
            if (target == null || !Cam.enabled) return;
            Rect r = target.rectTransform.rect;
            float s = canvas != null ? canvas.scaleFactor : 1f;
            int w = Mathf.Max(16, Mathf.RoundToInt(r.width * s));
            int h = Mathf.Max(16, Mathf.RoundToInt(r.height * s));
            if (rt != null && rt.width == w && rt.height == h) return;

            if (rt != null)
            {
                Cam.targetTexture = null;
                rt.Release();
                Destroy(rt);
            }
            rt = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32) { antiAliasing = 4, name = "HomeRT" };
            rt.Create();
            Cam.targetTexture = rt;
            target.texture = rt;
            Cam.ResetProjectionMatrix();
            Cam.fieldOfView = FieldOfView((float)w / h);
            // 上下に UI が重なっている分だけ写る範囲をずらし、モデルが見えている部分の中央に来るようにする
            float shift = (ViewInsetBottom - ViewInsetTop) * s / h;
            if (Mathf.Abs(shift) > 1e-4f)
            {
                Matrix4x4 m = Cam.projectionMatrix;
                m[1, 2] = -shift;
                Cam.projectionMatrix = m;
            }
        }

        /// <summary>3D ビューの上下に重なっている UI の高さ (UI の単位)。全画面表示のときに App が設定する。</summary>
        public float ViewInsetTop, ViewInsetBottom;

        /// <summary>縦の画角 45° は横幅 0.7 (縦長のカード) を想定。それより細長い画面 (全画面表示) では横が見切れないよう広げる。</summary>
        static float FieldOfView(float aspect)
        {
            const float DesignAspect = 0.7f, DesignFov = 45f;
            if (aspect >= DesignAspect) return DesignFov;
            float halfTan = Mathf.Tan(DesignFov * 0.5f * Mathf.Deg2Rad) * DesignAspect / aspect;
            return 2f * Mathf.Atan(halfTan) * Mathf.Rad2Deg;
        }
    }

    /// <summary>
    /// 3D ビュー上の操作: ドラッグで回転、タップ (ホーム: なでなで / プレビュー: 面の選択)、長押しでうっとり、
    /// 2本指ピンチ・マウスホイールでズーム。プレビューでは選んだ面のモジュールをつかんで移動/取り外し。
    /// </summary>
    public class AidyHomeTouch : MonoBehaviour, IPointerDownHandler, IPointerUpHandler, IInitializePotentialDragHandler, IBeginDragHandler, IDragHandler, IEndDragHandler, IScrollHandler
    {
        const float HoldSeconds = 0.6f;

        public AidyHomeRoom Room;
        public AidyModuleTray Tray;
        bool draggingModule, pinching;
        float moved, downTime, pinchDist;
        bool down, held;
        Canvas canvas;

        float Scale
        {
            get
            {
                if (canvas == null) canvas = GetComponentInParent<Canvas>();
                return canvas != null ? canvas.scaleFactor : 1f;
            }
        }

        public void OnPointerDown(PointerEventData e)
        {
            moved = 0f;
            down = true;
            held = false;
            downTime = Time.unscaledTime;
            Room.DraggingModel = true;
            Room.Touched();
        }

        public void OnPointerUp(PointerEventData e)
        {
            if (Input.touchCount > 1) return; // ピンチ中の片方の指
            down = false;
            Room.DraggingModel = false;
            if (held) Room.HoldEnd();
            else if (moved < 8f && !draggingModule && !pinching) Room.TapAt(e.position);
            pinching = false;
        }

        public void OnScroll(PointerEventData e)
        {
            Room.ZoomBy(1f + e.scrollDelta.y * 0.12f);
        }

        public void OnInitializePotentialDrag(PointerEventData e) { e.useDragThreshold = false; }

        public void OnBeginDrag(PointerEventData e)
        {
            // プレビュー: 選んだ面の取り付け済みモジュールをつかんだら、モジュールのドラッグ (移動/取り外し) にする
            draggingModule = false;
            if (!Room.Editable || Room.SelectedFace == null || Tray == null || held) return;
            string slot = Room.SlotAtScreen(e.pressPosition, 45f, s => Room.ModuleAt(s.Id).HasValue);
            if (slot == null) return;
            draggingModule = true;
            Room.DraggingModel = false;
            Tray.BeginDrag(Room.ModuleAt(slot).Value, slot, e);
        }

        public void OnEndDrag(PointerEventData e)
        {
            if (!draggingModule) return;
            draggingModule = false;
            moved = 100f; // タップ扱いにしない
            Tray.EndDrag(e);
        }

        public void OnDrag(PointerEventData e)
        {
            if (draggingModule)
            {
                Tray.Drag(e);
                return;
            }
            if (pinching || Input.touchCount > 1) return;
            Vector2 d = e.delta / Scale;
            moved += Mathf.Abs(d.x) + Mathf.Abs(d.y);
            if (held && moved < 8f) return;
            if (held)
            {
                held = false;
                Room.HoldEnd();
            }
            Room.RotateBy(d.x, -d.y);
        }

        void Update()
        {
            // 2本指ピンチでズーム
            if (down && Input.touchCount >= 2)
            {
                float dist = Vector2.Distance(Input.GetTouch(0).position, Input.GetTouch(1).position);
                if (!pinching)
                {
                    pinching = true;
                    pinchDist = dist;
                    moved = 100f;
                    if (held)
                    {
                        held = false;
                        Room.HoldEnd();
                    }
                }
                else if (pinchDist > 1f && dist > 1f)
                {
                    Room.ZoomBy(dist / pinchDist);
                    pinchDist = dist;
                }
                return;
            }
            if (Input.touchCount == 0 && !Input.GetMouseButton(0))
            {
                if (pinching) Room.DraggingModel = false;
                pinching = false;
            }

            if (down && !held && !pinching && moved < 8f && Time.unscaledTime - downTime >= HoldSeconds)
            {
                held = true;
                Room.HoldStart();
            }
        }
    }
}
