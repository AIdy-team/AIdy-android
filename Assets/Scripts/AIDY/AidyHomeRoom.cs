using System;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// ホーム画面: 3D ペットルーム + 本体v1_モデル + 合体モジュール (耳・尻尾・足・顔) + 自律AI行動。
    /// WebDemo の数値・状態遷移をそのまま移植している (three.js 座標は Three で変換)。
    /// </summary>
    public class AidyHomeRoom : MonoBehaviour
    {
        public const int Layer = 8;
        const float BodySize = 1.1f;     // 本体の一辺 (シーン単位)
        const float RobotBaseY = 0.14f;  // 足の先端が床に接地する高さ
        const float MoveSpeed = 0.015f;
        const float Step = 1f / 60f;     // WebDemo は requestAnimationFrame (60fps) 基準の定数で動く

        public enum AIState { Wander, Idle, Look, Happy }

        public Camera Cam { get; private set; }
        public event Action<string> StatusChanged;
        public event Action<bool, string> ModelBadgeChanged;

        public bool AIEnabled { get; private set; } = true;
        public bool DraggingModel;

        TNode robot, body, tail;
        TNode[] legs;
        GameObject earsGroup, tailGroup, legsGroup, faceGroup;
        RenderTexture rt;
        RawImage target;
        Canvas canvas;

        AIState aiState = AIState.Wander;
        float aiTimer;
        Vector3 targetPos;
        float animTime;
        float accumulator;

        public static AidyHomeRoom Create(Transform parent, Vector3 worldOffset)
        {
            var root = new GameObject("Home3D_Environment");
            root.layer = Layer;
            root.transform.SetParent(parent, false);
            root.transform.position = worldOffset;
            var room = root.AddComponent<AidyHomeRoom>();
            room.Build();
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
            Cam.cullingMask = 1 << Layer;
            Cam.depth = -50;
            Cam.allowHDR = false;
            camGo.transform.localPosition = Three.Pos(0, 2.2f, 4.2f);
            camGo.transform.LookAt(t.TransformPoint(Three.Pos(0, 0.2f, 0)), Vector3.up);

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
            light.cullingMask = 1 << Layer;
            lightGo.transform.rotation = Quaternion.LookRotation(-Three.Pos(4, 8, 5).normalized, Vector3.up);

            // 床・ラグ・壁
            var floor = AidyMeshes.Create("Floor", t, AidyMeshes.Plane(8, 8), AidyMaterials.Standard(Theme.Hex(0xe2e8f0), 0f, 0.4f), false, true);
            Three.Set(floor.transform, new Vector3(0, -0.65f, 0), new Vector3(-Mathf.PI / 2, 0, 0));
            var rug = AidyMeshes.Create("Rug", t, AidyMeshes.Circle(1.6f, 32), AidyMaterials.Standard(Theme.Hex(0xdbeafe), 0f, 0.8f), false, true);
            Three.Set(rug.transform, new Vector3(0, -0.64f, 0), new Vector3(-Mathf.PI / 2, 0, 0));
            var wall = AidyMeshes.Create("Wall", t, AidyMeshes.Plane(8, 4), AidyMaterials.Standard(Theme.Hex(0xf8fafc), 0f, 0.9f), false, true);
            Three.Set(wall.transform, new Vector3(0, 1.35f, -2.5f), Vector3.zero);

            // ロボットモデルアセンブリ
            robot = new TNode("RobotGroup", t) { P = new Vector3(0, RobotBaseY, 0) };
            robot.Apply();
            body = new TNode("BodyGroup", robot.T);
            body.Apply();

            Transform bodyPivot = AidyModel.BuildScaledInstance(body.T, BodySize, Layer);
            hasModel = bodyPivot != null;

            // 強制適用用 高品位サテン鋼マテリアル (Kd 0.627451 透過なし)
            var satin = AidyMaterials.Standard(new Color(0.627451f, 0.627451f, 0.627451f), 0.55f, 0.35f);

            // 耳モジュール
            earsGroup = new GameObject("Ears");
            earsGroup.layer = Layer;
            earsGroup.transform.SetParent(robot.T, false);
            var earMesh = AidyMeshes.Cylinder(0.08f, 0.14f, 0.3f, 16);
            var earL = AidyMeshes.Create("EarL", earsGroup.transform, earMesh, satin, true, true);
            Three.Set(earL.transform, new Vector3(-0.4f, 0.65f, 0), new Vector3(0, 0, -0.3f));
            var earR = AidyMeshes.Create("EarR", earsGroup.transform, earMesh, satin, true, true);
            Three.Set(earR.transform, new Vector3(0.4f, 0.65f, 0), new Vector3(0, 0, 0.3f));

            // 尻尾モジュール
            tail = new TNode("Tail", robot.T);
            tail.Apply();
            tailGroup = tail.T.gameObject;
            var tailMesh = AidyMeshes.Create("TailMesh", tail.T, AidyMeshes.Cylinder(0.04f, 0.03f, 0.45f, 16), satin, true, true);
            Three.Set(tailMesh.transform, new Vector3(0, -0.1f, -0.6f), new Vector3(-0.6f, 0, 0));

            // 足モジュール (4本、pivot を振って歩行)
            legsGroup = new GameObject("Legs");
            legsGroup.layer = Layer;
            legsGroup.transform.SetParent(robot.T, false);
            var legMesh = AidyMeshes.Cylinder(0.1f, 0.08f, 0.28f, 16);
            Vector3[] legPositions = { new Vector3(-0.35f, -0.6f, 0.35f), new Vector3(0.35f, -0.6f, 0.35f), new Vector3(-0.35f, -0.6f, -0.35f), new Vector3(0.35f, -0.6f, -0.35f) };
            legs = new TNode[legPositions.Length];
            for (int i = 0; i < legPositions.Length; i++)
            {
                legs[i] = new TNode("LegPivot" + i, legsGroup.transform) { P = legPositions[i] };
                legs[i].Apply();
                var leg = AidyMeshes.Create("Leg", legs[i].T, legMesh, satin, true, true);
                Three.Set(leg.transform, new Vector3(0, -0.05f, 0), Vector3.zero);
            }

            // カメラディスプレイ (顔)
            faceGroup = new GameObject("Face");
            faceGroup.layer = Layer;
            faceGroup.transform.SetParent(robot.T, false);
            var faceMesh = AidyMeshes.Create("Display", faceGroup.transform, AidyMeshes.Plane(0.72f, 0.46f), AidyMaterials.Basic(Theme.Hex(0x020617)));
            Three.Set(faceMesh.transform, new Vector3(0, 0, 0.56f), Vector3.zero);
            var eyeMat = AidyMaterials.Basic(Theme.Hex(0x38bdf8));
            var eyeMesh = AidyMeshes.Plane(0.09f, 0.18f);
            var eyeL = AidyMeshes.Create("EyeL", faceGroup.transform, eyeMesh, eyeMat);
            Three.Set(eyeL.transform, new Vector3(-0.16f, 0, 0.57f), Vector3.zero);
            var eyeR = AidyMeshes.Create("EyeR", faceGroup.transform, eyeMesh, eyeMat);
            Three.Set(eyeR.transform, new Vector3(0.16f, 0, 0.57f), Vector3.zero);
        }

        bool hasModel;

        /// <summary>UI 側のイベント登録後に、読み込み結果を通知する。</summary>
        public void ReportModelState()
        {
            if (hasModel) ModelBadgeChanged?.Invoke(true, "本体v1_モデル (.obj+.mtl) 読込完了");
            else ModelBadgeChanged?.Invoke(false, "OBJ読込失敗");
        }

        public void BindTarget(RawImage image)
        {
            target = image;
            canvas = image.canvas;
        }

        // ------------------------------------------------------------ モジュールパーツ着脱

        public void SetPart(string part, bool on)
        {
            switch (part)
            {
                case "ears": earsGroup.SetActive(on); break;
                case "tail": tailGroup.SetActive(on); break;
                case "legs": legsGroup.SetActive(on); break;
                case "face": faceGroup.SetActive(on); break;
            }
        }

        // ------------------------------------------------------------ 自律AIエージェント

        public void ToggleAIWalk(bool enabled)
        {
            AIEnabled = enabled;
            if (!AIEnabled)
            {
                aiState = AIState.Idle;
                robot.P = new Vector3(0, RobotBaseY, 0);
                robot.R = Vector3.zero;
                robot.Apply();
                StatusChanged?.Invoke("本体v1 AI歩行停止中");
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

            float r = UnityEngine.Random.value;
            if (r < 0.5f)
            {
                aiState = AIState.Wander;
                targetPos = new Vector3((UnityEngine.Random.value - 0.5f) * 2.2f, 0, (UnityEngine.Random.value - 0.5f) * 1.2f);
                StatusChanged?.Invoke("本体v1_モデルが気ままにお散歩中...");
            }
            else if (r < 0.75f)
            {
                aiState = AIState.Idle;
                StatusChanged?.Invoke("本体v1_モデルが日向ぼっこ中");
            }
            else
            {
                aiState = AIState.Look;
                StatusChanged?.Invoke("周囲の状況を確認中");
            }
        }

        public void PetRobotDirectly()
        {
            aiState = AIState.Happy;
            aiTimer = 2.5f;
            StatusChanged?.Invoke("♥ 本体v1_モデルが嬉しくて跳ねています！");
        }

        /// <summary>ドラッグ回転 (dx, dy は CSS px 相当、y は下向き正)。</summary>
        public void RotateBy(float dx, float dy)
        {
            robot.R.y += dx * 0.01f;
            robot.R.x += dy * 0.01f;
            robot.Apply();
        }

        void Update()
        {
            accumulator = Mathf.Min(accumulator + Time.deltaTime, 0.25f);
            while (accumulator >= Step)
            {
                accumulator -= Step;
                Tick();
            }
        }

        void Tick()
        {
            if (!AIEnabled || DraggingModel) return;
            aiTimer -= 0.016f;

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
                        for (int i = 0; i < legs.Length; i++)
                        {
                            legs[i].R.x = Mathf.Sin(animTime + (i % 2 == 0 ? 0 : Mathf.PI)) * 0.4f;
                            legs[i].Apply();
                        }
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
                    foreach (var leg in legs)
                    {
                        leg.R.x = 0;
                        leg.Apply();
                    }
                    break;
                case AIState.Look:
                    animTime += 0.05f;
                    robot.R.y = Mathf.Sin(animTime) * 0.5f;
                    break;
                case AIState.Happy:
                    animTime += 0.25f;
                    body.P.y = Mathf.Abs(Mathf.Sin(animTime)) * 0.25f;
                    tail.R.z = Mathf.Sin(animTime * 2f) * 0.4f;
                    tail.Apply();
                    break;
            }

            robot.Apply();
            body.Apply();

            // HAPPY状態も含め、タイマーが0になったら次のモーションへ移行する
            if (aiTimer <= 0f) ChooseNextAIState();
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
        }

        void OnDestroy()
        {
            if (rt != null) rt.Release();
        }
    }

    /// <summary>canvas-container 上の操作: ドラッグで回転、タップでナデナデ。</summary>
    public class AidyHomeTouch : MonoBehaviour, IPointerDownHandler, IPointerUpHandler, IInitializePotentialDragHandler, IBeginDragHandler, IDragHandler, IEndDragHandler
    {
        public AidyHomeRoom Room;
        public Action OnTap;
        float moved;
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
            Room.DraggingModel = true;
        }

        public void OnPointerUp(PointerEventData e)
        {
            Room.DraggingModel = false;
            if (moved < 8f) OnTap?.Invoke();
        }

        public void OnInitializePotentialDrag(PointerEventData e) { e.useDragThreshold = false; }
        public void OnBeginDrag(PointerEventData e) { }
        public void OnEndDrag(PointerEventData e) { }

        public void OnDrag(PointerEventData e)
        {
            Vector2 d = e.delta / Scale;
            moved += Mathf.Abs(d.x) + Mathf.Abs(d.y);
            Room.RotateBy(d.x, -d.y);
        }
    }
}
