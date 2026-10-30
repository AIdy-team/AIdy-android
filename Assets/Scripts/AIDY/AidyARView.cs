using System;
using System.Collections;
using System.Collections.Generic;
using Unity.XR.CoreUtils;
using Unity.Collections;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.SpatialTracking;
using UnityEngine.UI;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.ARSubsystems;
using UnityEngine.XR.Management;
#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine.Android;
#endif

namespace Aidy
{
    /// <summary>
    /// AR 画面。ARCore が使える端末では AR Foundation で床/机の平面に本体v2_モデルを配置し、
    /// 使えない場合 (非対応端末・エディタ・PC) は WebDemo と同じく
    /// 「カメラ映像の上にタップした位置を基準に配置」する方式で動作する。
    /// 表示するモデルは ModuleConfig のモジュール付き (付け替えはタブの「画面上」で行う)。
    /// はじめはモデルを出さず、表示方法 (Placement) を選んでもらう:
    ///  ・マーカー: ArUco マーカー (AidyMarkers) が写ったら、その上にモデルを立たせる。「あなたのAidy」マーカーにはいまの着せ替え、
    ///    名刺マーカーには標準構成の Aidy。実機 AR ではマーカーの位置をワールドに固定し、マーカーが外れてもその場に残る。
    ///  ・マーカーなし: 机や床の面をタップして置く。実機 AR は ARCore が見つけた平面、Web カメラ (Unity Remote など) は
    ///    ジャイロで端末の傾きを追い、カメラの TableDepth 下にあるとみなした水平面に置く。
    /// </summary>
    public class AidyARView : MonoBehaviour
    {
        public const int FallbackLayer = 9;
        const float Step = 1f / 60f;
        const float ARModelSize = 0.075f; // 本体は 150mm 角だが、実寸だと画面に対して大きすぎるので 1/2 で表示
        const float WebcamHFov = 62f;    // Web カメラの内部パラメータは取れないので、長辺の画角をこの値とみなす
        const float MarkerLostSec = 0.5f; // Web カメラではこれだけ見えなければ消す (ワールド追跡がないため)
        const float TableDepth = 0.35f;   // Web カメラ: 机の面はカメラの 35cm 下とみなす (端末を机の上で構えたくらい)
        const float NoGyroTilt = 40f;     // ジャイロがないとき: カメラはこの角度だけ下を向いているとみなす
        const float MaxPlaceDistance = 3f;

        public enum Mode { None, Starting, AR, Webcam }

        /// <summary>表示方法。Choose = まだ選んでいない (モデルは出さない)。</summary>
        public enum Placement { Choose, Marker, Free }

        public Mode CurrentMode { get; private set; } = Mode.None;
        public Placement CurrentPlacement { get; private set; } = Placement.Choose;
        public event Action<Placement> PlacementChanged;
        public bool PageVisible;

        // UI (AidyApp が設定)
        public RectTransform Page;
        public Image Black;
        public RectTransform WebcamArea;
        public RawImage WebcamImage;
        public RawImage OverlayImage;
        public Action<IconId, Color, float, string> SetStatus;
        public Action<string> SetPosition;
        public Action<string> Hint;

        // フォールバック表示 (WebDemo の arScene)
        Camera fallbackCam;
        RenderTexture rt;
        Canvas canvas;
        WebCamTexture webcam;
        string webcamName;
        bool arCapable;

        // 実機AR
        ARSession session;
        GameObject originGo;
        Camera arCam;
        ARRaycastManager raycaster;
        Transform placedRoot, placedModel, placedRing;
        AidyModuleRig placedRig;
        ARPlaneManager planeManager;
        float placedYaw, ringAngle;
        ARSessionState lastState = ARSessionState.None;
        static readonly List<ARRaycastHit> hits = new List<ARRaycastHit>();

        bool draggingAR;
        float dragDistance;
        float accumulator;

        // ArUco マーカー
        readonly AidyMarkerTracker tracker = new AidyMarkerTracker();
        readonly List<MarkerSighting> sightings = new List<MarkerSighting>();
        readonly Dictionary<int, float> lastSeen = new Dictionary<int, float>();
        ARCameraManager cameraManager;
        Matrix4x4? displayMatrix;
        bool placedOnMarker;                   // placedRoot をマーカーに合わせている (自動で回さない)

        /// <summary>マーカーの上に立つモデル。根元 = マーカーの中心、Model = 足先がマーカーに付く高さ。</summary>
        class MarkerModel
        {
            public Transform Root, Model;
            public AidyModuleRig Rig;
            public bool Live; // いまの着せ替えに追従する
        }

        // ID ごとに初めて見えたときに作る。実機AR はワールドに置く (ID0 だけは placedRoot を使う)、Web カメラは fallbackCam の子
        readonly Dictionary<int, MarkerModel> arMarkerModels = new Dictionary<int, MarkerModel>();
        readonly Dictionary<int, MarkerModel> webcamMarkerModels = new Dictionary<int, MarkerModel>();
        Color32[] pixels;
        Texture2D testFrame;                   // 検証用: Web カメラの代わりに静止画

        // マーカーなし (Web カメラ): 仮想の机の面に置くモデルと、置く場所の目印
        Transform freeRoot, freeModel, freeRing, reticle;
        AidyModuleRig freeRig;
        Quaternion? gyroYaw;                   // ジャイロの向きを、選んだときの向きが正面になるよう合わせる
        bool gyroRequested;

        public static AidyARView Create(Transform parent, Vector3 worldOffset)
        {
            var root = new GameObject("AR_View");
            root.layer = FallbackLayer;
            root.transform.SetParent(parent, false);
            root.transform.position = worldOffset;
            var view = root.AddComponent<AidyARView>();
            view.BuildFallbackScene();
            view.arCapable = XRLoaderActive;
            ModuleConfig.Changed += view.OnModuleChanged;
            AidyMarkerStore.Changed += view.OnMarkersChanged;
            return view;
        }

        static bool XRLoaderActive
        {
            get
            {
                var s = XRGeneralSettings.Instance;
                return s != null && s.Manager != null && s.Manager.activeLoader != null;
            }
        }

        public void Bind(Canvas c) { canvas = c; }

        /// <summary>モジュールの付け替えを AR のモデルにも反映する。</summary>
        void OnModuleChanged(string slot, ModuleKind? removed, ModuleKind? added)
        {
            placedRig?.Sync();
            freeRig?.Sync();
            if (placedModel != null) placedModel.localPosition = new Vector3(0, ARModelSize * 0.5f + placedRig.LegReach, 0);
            if (freeModel != null) freeModel.localPosition = new Vector3(0, ARModelSize * 0.5f + freeRig.LegReach, 0);
            foreach (var models in new[] { arMarkerModels, webcamMarkerModels })
                foreach (MarkerModel m in models.Values)
                {
                    if (!m.Live) continue;
                    m.Rig.Sync();
                    m.Model.localPosition = new Vector3(0, ARModelSize * 0.5f + m.Rig.LegReach, 0); // 足の有無で足先の高さが変わる
                }
        }

        /// <summary>着せ替えマーカーを作った・消したとき: 作ったマーカーのモデルは次に見えたときに作り直す。</summary>
        void OnMarkersChanged()
        {
            foreach (var models in new[] { arMarkerModels, webcamMarkerModels })
                foreach (int id in new List<int>(models.Keys))
                {
                    if (id == AidyMarkers.MyId || id == AidyMarkers.CardId) continue;
                    Destroy(models[id].Root.gameObject);
                    models.Remove(id);
                }
        }

        /// <summary>
        /// マーカーの上に立つモデル。実機AR (ar) はワールド、Web カメラは fallbackCam の子。まだなければ作る。
        /// 構成は AidyMarkers.Config (null ならいまの着せ替えに追従)。消された着せ替えマーカーなら null。
        /// </summary>
        MarkerModel MarkerModelFor(int id, bool ar)
        {
            var models = ar ? arMarkerModels : webcamMarkerModels;
            if (models.TryGetValue(id, out MarkerModel m)) return m;
            IReadOnlyDictionary<string, ModuleKind> config = AidyMarkers.Config(id);
            if (config == null && id != AidyMarkers.MyId) return null;
            int layer = ar ? 0 : FallbackLayer;
            var root = new GameObject("Marker_" + id).transform;
            root.gameObject.layer = layer;
            root.SetParent(ar ? null : fallbackCam.transform, false);
            var model = new GameObject("Model").transform;
            model.gameObject.layer = layer;
            model.SetParent(root, false);
            m = new MarkerModel { Root = root, Model = model, Live = config == null };
            m.Rig = new AidyModuleRig(model, ARModelSize, layer, false, config);
            model.localPosition = new Vector3(0, ARModelSize * 0.5f + m.Rig.LegReach, 0);
            Three.SetLayer(root.gameObject, layer);
            root.gameObject.SetActive(false);
            models[id] = m;
            return m;
        }

        /// <summary>検証用: Web カメラの代わりに静止画を AR の映像として使う (-aidyMarkerTest)。</summary>
        public void UseTestImage(Texture2D image)
        {
            testFrame = image;
            CurrentMode = Mode.Webcam;
            WebcamImage.texture = image;
            WebcamImage.enabled = true;
        }

        /// <summary>「画面上」に切り替えたとき: カメラを止める (AR セッション/Web カメラ)。</summary>
        public void StopCamera()
        {
            if (webcam != null && webcam.isPlaying) webcam.Pause();
            if (session != null) session.enabled = false;
        }

        // ------------------------------------------------------------ フォールバック 3D シーン

        void BuildFallbackScene()
        {
            var t = transform;
            var camGo = new GameObject("AR_Fallback_Camera");
            camGo.layer = FallbackLayer;
            camGo.transform.SetParent(t, false);
            fallbackCam = camGo.AddComponent<Camera>();
            fallbackCam.clearFlags = CameraClearFlags.SolidColor;
            fallbackCam.backgroundColor = new Color(0, 0, 0, 0);
            fallbackCam.fieldOfView = 45f;
            fallbackCam.nearClipPlane = 0.1f;
            fallbackCam.farClipPlane = 1000f;
            fallbackCam.cullingMask = 1 << FallbackLayer;
            fallbackCam.depth = -40;
            fallbackCam.allowHDR = false;
            fallbackCam.enabled = false;
            camGo.transform.localPosition = Three.Pos(0, 0, 4);
            camGo.transform.localRotation = Quaternion.LookRotation(Vector3.back, Vector3.up);

            // カメラの左上から当てる光 (カメラと一緒に動くので、マーカーの上のモデルも同じ向きから照らされる)
            var lightGo = new GameObject("AR_Fallback_Light");
            lightGo.layer = FallbackLayer;
            lightGo.transform.SetParent(camGo.transform, false);
            lightGo.transform.localRotation = Quaternion.Euler(35f, 25f, 0f);
            var light = lightGo.AddComponent<Light>();
            light.type = LightType.Directional;
            light.intensity = 0.7f;
            light.cullingMask = 1 << FallbackLayer;

            // マーカーなしで置くモデル (実寸) と、置く場所の目印
            freeRoot = new GameObject("AR_Free_本体v2").transform;
            freeRoot.SetParent(t, false);
            freeModel = new GameObject("Model").transform;
            freeModel.SetParent(freeRoot, false);
            freeRig = new AidyModuleRig(freeModel, ARModelSize, FallbackLayer, false);
            freeModel.localPosition = new Vector3(0, ARModelSize * 0.5f + freeRig.LegReach, 0);
            if (!freeRig.HasModel) PlaceholderCube(freeModel);
            freeRing = Ring(freeRoot, Theme.Hex(0x38bdf8));
            Three.SetLayer(freeRoot.gameObject, FallbackLayer);
            freeRoot.gameObject.SetActive(false);

            reticle = new GameObject("AR_Reticle").transform;
            reticle.SetParent(t, false);
            Ring(reticle, Theme.Hex(0xffffff, 0.85f));
            Three.SetLayer(reticle.gameObject, FallbackLayer);
            reticle.gameObject.SetActive(false);
        }

        static void PlaceholderCube(Transform parent)
        {
            var cube = GameObject.CreatePrimitive(PrimitiveType.Cube);
            Destroy(cube.GetComponent<Collider>());
            cube.transform.SetParent(parent, false);
            cube.transform.localScale = Vector3.one * ARModelSize;
            cube.GetComponent<MeshRenderer>().sharedMaterial = AidyMaterials.Standard(new Color(0.627451f, 0.627451f, 0.627451f), 0.55f, 0.35f);
        }

        /// <summary>足元の輪 (RingGeometry(0.4, 0.48, 32) を本体の大きさに合わせたもの)。床に寝かせて置く。</summary>
        static Transform Ring(Transform parent, Color color)
        {
            var ring = new GameObject("TargetRing").transform;
            ring.SetParent(parent, false);
            ring.localPosition = new Vector3(0, 0.002f, 0);
            ring.localRotation = Three.Rot(new Vector3(Mathf.PI / 2, 0, 0));
            ring.localScale = Vector3.one * (ARModelSize / 0.9f);
            AidyMeshes.Create("Ring", ring, AidyMeshes.Ring(0.4f, 0.48f, 32), AidyMaterials.Basic(color));
            return ring;
        }

        // ------------------------------------------------------------ 表示方法 (マーカー / マーカーなし)

        /// <summary>表示方法を切り替える。表示中のモデルはいったん全部消す (選び直したら置き直し / マーカーを写し直し)。</summary>
        public void SetPlacement(Placement p)
        {
            CurrentPlacement = p;
            placedOnMarker = false;
            gyroYaw = null;
            if (placedRoot != null) placedRoot.gameObject.SetActive(false);
            freeRoot.gameObject.SetActive(false);
            reticle.gameObject.SetActive(false);
            foreach (var models in new[] { arMarkerModels, webcamMarkerModels })
                foreach (MarkerModel m in models.Values) m.Root.gameObject.SetActive(false);
            lastSeen.Clear();
            UpdatePlanes();
            ShowPlacementStatus();
            PlacementChanged?.Invoke(p);
        }

        void ShowPlacementStatus()
        {
            if (CurrentMode == Mode.Starting) return;
            switch (CurrentPlacement)
            {
                case Placement.Choose:
                    SetStatus?.Invoke(IconId.HandPointer, Color.white, 12, "表示方法を選んでね");
                    break;
                case Placement.Marker:
                    SetStatus?.Invoke(IconId.Crosshairs, Color.white, 12, "マーカーをカメラに写してね");
                    break;
                case Placement.Free when CurrentMode == Mode.AR && ARSession.state < ARSessionState.SessionTracking:
                    SetStatus?.Invoke(IconId.HandPointer, Color.white, 12, "端末をゆっくり動かして床や机を認識中…");
                    break;
                case Placement.Free:
                    SetStatus?.Invoke(IconId.HandPointer, Color.white, 12, CurrentMode == Mode.Webcam && GyroActive
                        ? "机や床をタップして置いてね (スマホの傾きに追従)"
                        : "机や床をタップして置いてね");
                    break;
            }
        }

        /// <summary>ARCore の平面は「マーカーなし」のときだけ探して表示する。</summary>
        void UpdatePlanes()
        {
            if (planeManager == null) return;
            bool on = CurrentPlacement == Placement.Free;
            planeManager.enabled = on;
            foreach (ARPlane plane in planeManager.trackables) plane.gameObject.SetActive(on);
        }

        bool GyroActive
        {
            get
            {
                if (!gyroRequested)
                {
                    gyroRequested = true;
                    Input.gyro.enabled = true;
                }
                if (!Input.gyro.enabled) return false;
                Quaternion q = Input.gyro.attitude;
                return Mathf.Abs(Quaternion.Dot(q, q) - 1f) < 0.1f && Quaternion.Angle(q, Quaternion.identity) > 0.01f;
            }
        }

        /// <summary>
        /// Web カメラの向き。マーカーなしではジャイロで端末の傾きを追う (なければ少し下向きに固定)。
        /// マーカーのモデルはカメラの子なので、向きを変えてもマーカーとの位置関係は変わらない。
        /// </summary>
        void UpdateWebcamPose()
        {
            Quaternion facing = Quaternion.LookRotation(Vector3.back, Vector3.up);
            Transform cam = fallbackCam.transform;
            if (CurrentMode != Mode.Webcam || CurrentPlacement != Placement.Free)
            {
                cam.localRotation = facing;
                return;
            }
            if (!GyroActive)
            {
                cam.localRotation = facing * Quaternion.Euler(NoGyroTilt, 0, 0);
                return;
            }
            // 端末の姿勢 (右手系・Z 上) → Unity のカメラ (左手系・Y 上、背面カメラの向き = forward)
            Quaternion q = Input.gyro.attitude;
            Quaternion att = Quaternion.Euler(90f, 0, 0) * new Quaternion(q.x, q.y, -q.z, -q.w);
            if (!gyroYaw.HasValue)
            {
                Vector3 f = Vector3.ProjectOnPlane(att * Vector3.forward, Vector3.up);
                if (f.sqrMagnitude < 1e-4f) f = Vector3.ProjectOnPlane(att * Vector3.up, Vector3.up);
                gyroYaw = Quaternion.FromToRotation(f.normalized, facing * Vector3.forward);
            }
            cam.localRotation = gyroYaw.Value * att;
        }

        /// <summary>Web カメラ: 画面上の点 (0〜1) から見た、仮想の机の面の上の点 (AR_View の座標)。</summary>
        bool SurfacePoint(Vector2 viewport, out Vector3 local)
        {
            local = default;
            Ray ray = fallbackCam.ViewportPointToRay(viewport);
            var plane = new Plane(transform.up, fallbackCam.transform.position - transform.up * TableDepth);
            if (!plane.Raycast(ray, out float d) || d > MaxPlaceDistance) return false;
            local = transform.InverseTransformPoint(ray.GetPoint(d));
            return true;
        }

        void PlaceFree(Vector2 viewport, bool center)
        {
            if (!SurfacePoint(viewport, out Vector3 p))
            {
                Hint?.Invoke("カメラを少し下に向けて、机や床を写してね");
                return;
            }
            freeRoot.localPosition = p;
            freeRoot.localRotation = Quaternion.identity;
            freeRoot.gameObject.SetActive(true);
            reticle.gameObject.SetActive(false);
            if (center) placedYaw = 0f;
            SetPosition?.Invoke(center
                ? $"基準位置: ({p.x:F2}, {p.z:F2}) 本体v2中央配置"
                : $"基準位置: ({p.x:F2}, {p.z:F2}) 本体v2配置完了");
        }

        /// <summary>まだ置いていなければ、画面の中央に見えている机の面に目印を出す。</summary>
        void UpdateReticle()
        {
            Vector3 p = default;
            bool show = CurrentMode == Mode.Webcam && CurrentPlacement == Placement.Free && !freeRoot.gameObject.activeSelf
                        && SurfacePoint(new Vector2(0.5f, 0.5f), out p);
            if (show)
            {
                reticle.localPosition = p;
                reticle.localRotation = Quaternion.Euler(0, ringAngle * Mathf.Rad2Deg, 0);
            }
            if (reticle.gameObject.activeSelf != show) reticle.gameObject.SetActive(show);
        }

        // ------------------------------------------------------------ 起動 (startWebcam)

        public void StartCamera()
        {
            switch (CurrentMode)
            {
                case Mode.None:
                    StartCoroutine(Begin());
                    break;
                case Mode.Webcam:
                    if (testFrame == null && (webcam == null || !webcam.isPlaying)) StartCoroutine(StartWebcam());
                    break;
                case Mode.AR:
                    if (session != null && !session.enabled) session.enabled = true;
                    break;
            }
        }

        IEnumerator Begin()
        {
            CurrentMode = Mode.Starting;
            if (arCapable)
            {
                CreateAR();
                yield return ARSession.CheckAvailability();
                if (ARSession.state == ARSessionState.NeedsInstall) yield return ARSession.Install();
                if (ARSession.state >= ARSessionState.Ready)
                {
                    CurrentMode = Mode.AR;
                    session.enabled = true;
                    SetStatus?.Invoke(IconId.HandPointer, Color.white, 12, "ARカメラ起動中…");
                    UpdatePlanes();
                    yield break;
                }
                DestroyAR();
                arCapable = false;
            }
            CurrentMode = Mode.Webcam;
            yield return StartWebcam();
        }

        IEnumerator StartWebcam()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (!Permission.HasUserAuthorizedPermission(Permission.Camera))
            {
                bool done = false;
                var cb = new PermissionCallbacks();
                cb.PermissionGranted += _ => done = true;
                cb.PermissionDenied += _ => done = true;
                cb.PermissionDeniedAndDontAskAgain += _ => done = true;
                Permission.RequestUserPermission(Permission.Camera, cb);
                float waited = 0f;
                while (!done && waited < 60f)
                {
                    waited += Time.unscaledDeltaTime;
                    yield return null;
                }
            }
#endif
            WebCamDevice[] devices = WebCamTexture.devices;
            if (devices.Length > 0)
            {
                if (webcam == null) webcam = new WebCamTexture(webcamName ?? PickWebcam(devices), 1280, 720, 30);
                webcamName = webcam.deviceName;
                webcam.Play();
                float waited = 0f;
                while (webcam.width <= 16 && waited < 4f)
                {
                    waited += Time.unscaledDeltaTime;
                    yield return null;
                }
                if (webcam.isPlaying && webcam.width > 16)
                {
                    WebcamImage.texture = webcam;
                    WebcamImage.enabled = true;
                    ShowPlacementStatus();
                    yield break;
                }
            }
            ShowPlacementStatus();
        }

        /// <summary>
        /// 使うカメラを選ぶ: 背面カメラ優先 (facingMode: "environment")。
        /// エディタで Unity Remote 5 がつながっているときは、PC の Web カメラより iPhone 側の背面カメラを選ぶ。
        /// </summary>
        static string PickWebcam(WebCamDevice[] devices)
        {
#if UNITY_EDITOR
            if (UnityEditor.EditorApplication.isRemoteConnected)
            {
                foreach (var d in devices)
                    if (!d.isFrontFacing && IsRemoteCamera(d.name)) return d.name;
                foreach (var d in devices)
                    if (IsRemoteCamera(d.name)) return d.name;
            }
#endif
            foreach (var d in devices)
                if (!d.isFrontFacing) return d.name;
            return devices[0].name;
        }

        static bool IsRemoteCamera(string name)
        {
            foreach (string key in new[] { "Back", "背面", "iPhone", "iPad", "Remote", "Front", "前面" })
                if (name.IndexOf(key, StringComparison.OrdinalIgnoreCase) >= 0) return true;
            return false;
        }

        /// <summary>カメラ映像を次のカメラに切り替える (Unity Remote の iPhone カメラ ⇔ PC の Web カメラなど)。切り替え先の名前を返す。</summary>
        public string SwitchCamera()
        {
            if (CurrentMode == Mode.AR || CurrentMode == Mode.Starting) return null;
            WebCamDevice[] devices = WebCamTexture.devices;
            if (devices.Length == 0) return null;
            int cur = Array.FindIndex(devices, d => d.name == webcamName);
            webcamName = devices[(cur + 1) % devices.Length].name;
            if (webcam != null)
            {
                webcam.Stop();
                Destroy(webcam);
                webcam = null;
            }
            pixels = null;
            if (CurrentMode == Mode.None) StartCoroutine(Begin());
            else StartCoroutine(StartWebcam());
            return webcamName;
        }

        // ------------------------------------------------------------ 実機 AR (AR Foundation + ARCore)

        void CreateAR()
        {
            var sessionGo = new GameObject("AR Session");
            session = sessionGo.AddComponent<ARSession>();
            sessionGo.AddComponent<ARInputManager>();

            originGo = new GameObject("XR Origin");
            originGo.SetActive(false);
            var origin = originGo.AddComponent<XROrigin>();
            var offset = new GameObject("Camera Offset");
            offset.transform.SetParent(originGo.transform, false);

            var camGo = new GameObject("AR Camera");
            camGo.transform.SetParent(offset.transform, false);
            arCam = camGo.AddComponent<Camera>();
            arCam.clearFlags = CameraClearFlags.SolidColor;
            arCam.backgroundColor = Color.black;
            arCam.nearClipPlane = 0.03f;
            arCam.farClipPlane = 30f;
            arCam.cullingMask = 1 << 0;
            arCam.depth = 0;
            arCam.enabled = false;
            cameraManager = camGo.AddComponent<ARCameraManager>();
            cameraManager.frameReceived += args => displayMatrix = args.displayMatrix ?? displayMatrix;
            camGo.AddComponent<ARCameraBackground>();
            var tpd = camGo.AddComponent<TrackedPoseDriver>();
            tpd.SetPoseSource(TrackedPoseDriver.DeviceType.GenericXRDevice, TrackedPoseDriver.TrackedPose.ColorCamera);
            tpd.trackingType = TrackedPoseDriver.TrackingType.RotationAndPosition;
            tpd.updateType = TrackedPoseDriver.UpdateType.UpdateAndBeforeRender;

            origin.Camera = arCam;
            origin.CameraFloorOffsetObject = offset;

            raycaster = originGo.AddComponent<ARRaycastManager>();
            planeManager = originGo.AddComponent<ARPlaneManager>();
            planeManager.requestedDetectionMode = PlaneDetectionMode.Horizontal;
            planeManager.planePrefab = CreatePlaneTemplate();
            planeManager.planesChanged += args =>
            {
                if (CurrentPlacement == Placement.Free) return;
                foreach (ARPlane plane in args.added) plane.gameObject.SetActive(false);
            };

            // 実機AR用ライト (配置したモデルのみ照らす)
            var lightGo = new GameObject("AR Light");
            lightGo.transform.SetParent(originGo.transform, false);
            lightGo.transform.rotation = Quaternion.Euler(50f, -30f, 0f);
            var light = lightGo.AddComponent<Light>();
            light.type = LightType.Directional;
            light.intensity = 0.6f;
            light.cullingMask = 1 << 0;

            BuildPlacedModel();
            originGo.SetActive(true);
        }

        GameObject CreatePlaneTemplate()
        {
            // 非アクティブな親の下に置くことで、テンプレート自体は動作させずに複製元として使う
            var holder = new GameObject("PlaneTemplateHolder");
            holder.SetActive(false);
            holder.transform.SetParent(originGo.transform, false);
            var go = new GameObject("ARPlane");
            go.transform.SetParent(holder.transform, false);
            go.AddComponent<MeshFilter>();
            var mr = go.AddComponent<MeshRenderer>();
            mr.sharedMaterial = AidyMaterials.Basic(Theme.Hex(0x38bdf8, 0.16f));
            mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            var lr = go.AddComponent<LineRenderer>();
            lr.useWorldSpace = false;
            lr.loop = true;
            lr.widthMultiplier = 0.004f;
            lr.sharedMaterial = AidyMaterials.Basic(Theme.Hex(0x38bdf8));
            go.AddComponent<ARPlane>();
            go.AddComponent<ARPlaneMeshVisualizer>();
            return go;
        }

        void BuildPlacedModel()
        {
            placedRoot = new GameObject("AR_Placed_本体v2").transform;
            placedRoot.gameObject.SetActive(false);

            placedModel = new GameObject("Model").transform;
            placedModel.SetParent(placedRoot, false);
            placedRig = new AidyModuleRig(placedModel, ARModelSize, 0, false);
            placedModel.localPosition = new Vector3(0, ARModelSize * 0.5f + placedRig.LegReach, 0); // 足があれば足先を床に
            if (!placedRig.HasModel)
            {
                var cube = GameObject.CreatePrimitive(PrimitiveType.Cube);
                Destroy(cube.GetComponent<Collider>());
                cube.transform.SetParent(placedModel, false);
                cube.transform.localScale = Vector3.one * ARModelSize;
                cube.GetComponent<MeshRenderer>().sharedMaterial = AidyMaterials.Standard(new Color(0.627451f, 0.627451f, 0.627451f), 0.55f, 0.35f);
            }

            placedRing = Ring(placedRoot, Theme.Hex(0x38bdf8));
        }

        void DestroyAR()
        {
            if (session != null) Destroy(session.gameObject);
            if (originGo != null) Destroy(originGo);
            if (placedRoot != null) Destroy(placedRoot.gameObject);
            foreach (MarkerModel m in arMarkerModels.Values) Destroy(m.Root.gameObject);
            arMarkerModels.Clear();
            session = null;
            originGo = null;
            planeManager = null;
            arCam = null;
            cameraManager = null;
        }

        void PlaceAR(Vector2 screen, bool center)
        {
            // 机や床 (ARCore が見つけた水平面) の上にだけ置く
            if (raycaster == null || !raycaster.Raycast(screen, hits, TrackableType.PlaneWithinPolygon))
            {
                Hint?.Invoke("床や机を認識中… 端末をゆっくり動かして、青い面が出たらタップしてね");
                return;
            }
            Pose pose = hits[0].pose;
            placedRoot.gameObject.SetActive(true);
            placedRoot.SetPositionAndRotation(pose.position, Quaternion.identity);
            placedOnMarker = false;
            if (center) placedYaw = 0f;
            SetPosition?.Invoke(center
                ? $"基準位置: ({pose.position.x:F2}, {pose.position.z:F2}) 本体v2中央配置"
                : $"基準位置: ({pose.position.x:F2}, {pose.position.z:F2}) 本体v2配置完了");
        }

        // ------------------------------------------------------------ 入力 (AidyARTouch から)

        public void PointerDown()
        {
            draggingAR = true;
            dragDistance = 0f;
        }

        public void Drag(Vector2 delta)
        {
            if (!draggingAR) return;
            dragDistance += Mathf.Abs(delta.x) + Mathf.Abs(delta.y);
            if (dragDistance <= 8f || CurrentPlacement != Placement.Free) return;
            placedYaw += delta.x * 0.01f;
        }

        public void PointerUp(Vector2 screen)
        {
            if (draggingAR && dragDistance <= 8f)
            {
                if (CurrentPlacement == Placement.Marker) Hint?.Invoke("マーカーを写すと、その上にAidyが出てくるよ");
                else if (CurrentPlacement == Placement.Free)
                {
                    if (CurrentMode == Mode.AR) PlaceAR(screen, false);
                    else if (ScreenToViewport(screen, out Vector2 vp)) PlaceFree(vp, false);
                }
            }
            draggingAR = false;
        }

        bool ScreenToViewport(Vector2 screen, out Vector2 viewport)
        {
            viewport = default;
            RectTransform r = OverlayImage.rectTransform;
            if (!RectTransformUtility.ScreenPointToLocalPointInRectangle(r, screen, null, out Vector2 local)) return false;
            Rect rect = r.rect;
            viewport = new Vector2((local.x - rect.xMin) / rect.width, (local.y - rect.yMin) / rect.height);
            return true;
        }

        /// <summary>「中央に再配置」: マーカーなしのとき、画面の中央に見えている机や床に置き直す。</summary>
        public void ResetPosition()
        {
            switch (CurrentPlacement)
            {
                case Placement.Marker:
                    Hint?.Invoke("マーカーモードではマーカーの上に出るよ");
                    return;
                case Placement.Choose:
                    return;
            }
            if (CurrentMode == Mode.AR)
            {
                Vector2 c = RectTransformUtility.WorldToScreenPoint(null, Page.TransformPoint(Page.rect.center));
                PlaceAR(c, true);
            }
            else PlaceFree(new Vector2(0.5f, 0.5f), true);
        }

        // ------------------------------------------------------------ 毎フレーム

        bool OverlayVisible => CurrentMode == Mode.Webcam || (!arCapable && CurrentMode == Mode.None);

        void Update()
        {
            accumulator = Mathf.Min(accumulator + Time.deltaTime, 0.25f);
            while (accumulator >= Step)
            {
                accumulator -= Step;
                // animateAR: targetRing.rotation.z += 0.01 / 非ドラッグ時 本体 rotation.y += 0.004
                ringAngle += 0.01f;
                if (!draggingAR && !placedOnMarker) placedYaw += 0.004f; // マーカーの上ではマーカーの向きのまま
            }
            Quaternion ringRot = Three.Rot(new Vector3(Mathf.PI / 2, 0, ringAngle));
            if (placedRing != null) placedRing.localRotation = ringRot;
            freeRing.localRotation = ringRot;
            if (placedModel != null) placedModel.localRotation = Three.Rot(new Vector3(0, placedYaw, 0));
            freeModel.localRotation = Three.Rot(new Vector3(0, placedYaw, 0));

            bool overlay = OverlayVisible;
            OverlayImage.enabled = overlay;
            fallbackCam.enabled = overlay && PageVisible;
            Black.enabled = CurrentMode != Mode.AR;
            if (CurrentMode != Mode.Webcam) WebcamImage.enabled = false;
            if (arCam != null) arCam.enabled = CurrentMode == Mode.AR && PageVisible;

            if (CurrentMode == Mode.AR && ARSession.state != lastState)
            {
                lastState = ARSession.state;
                if (lastState == ARSessionState.SessionTracking || lastState == ARSessionState.SessionInitializing) ShowPlacementStatus();
            }

            UpdateWebcamRect();
            UpdateWebcamPose();
            UpdateMarkers();
            UpdateReticle();
        }

        // ------------------------------------------------------------ ArUco マーカー

        void UpdateMarkers()
        {
            if (tracker.TryCollect(sightings))
                foreach (MarkerSighting s in sightings) ApplySighting(s);

            if (PageVisible && !tracker.Busy && CurrentPlacement == Placement.Marker)
            {
                if (CurrentMode == Mode.AR) SubmitARFrame();
                else if (CurrentMode == Mode.Webcam) SubmitWebcamFrame();
            }

            // Web カメラ: しばらく見えないマーカーのモデルは消す
            foreach (var kv in webcamMarkerModels)
            {
                bool visible = CurrentMode == Mode.Webcam && CurrentPlacement == Placement.Marker
                               && lastSeen.TryGetValue(kv.Key, out float t) && Time.unscaledTime - t < MarkerLostSec;
                if (kv.Value.Root.gameObject.activeSelf != visible) kv.Value.Root.gameObject.SetActive(visible);
            }
            // 実寸のモデルを映像の画角で写す
            fallbackCam.nearClipPlane = 0.01f;
            fallbackCam.fieldOfView = WebcamVerticalFov();
        }

        void ApplySighting(MarkerSighting s)
        {
            if (CurrentPlacement != Placement.Marker) return; // 切り替える前に送った画像の結果
            bool fresh = !lastSeen.TryGetValue(s.Id, out float prev) || Time.unscaledTime - prev > 2f;
            lastSeen[s.Id] = Time.unscaledTime;
            if (fresh)
            {
                SetStatus?.Invoke(IconId.Circle, Theme.Green, 10, $"マーカー認識: {AidyMarkers.Name(s.Id)}");
                SetPosition?.Invoke($"基準位置: マーカー上 ({AidyMarkers.Name(s.Id)})");
            }

            Transform root;
            if (CurrentMode == Mode.AR && s.Id == AidyMarkers.MyId)
            {
                root = placedRoot;
                if (root == null) return;
                if (!placedOnMarker)
                {
                    placedOnMarker = true;
                    placedYaw = 0f;
                }
            }
            else
            {
                MarkerModel m = MarkerModelFor(s.Id, CurrentMode == Mode.AR);
                if (m == null) return;
                root = m.Root;
            }

            // 揺れを抑える: 近ければ半分ずつ寄せ、大きく動いたら (別の場所に置き直した) すぐ合わせる
            bool world = CurrentMode == Mode.AR;
            Vector3 p = world ? root.position : root.localPosition;
            Quaternion r = world ? root.rotation : root.localRotation;
            bool snap = !root.gameObject.activeSelf || (p - s.Position).magnitude > 0.05f || Quaternion.Angle(r, s.Rotation) > 30f;
            p = snap ? s.Position : Vector3.Lerp(p, s.Position, 0.5f);
            r = snap ? s.Rotation : Quaternion.Slerp(r, s.Rotation, 0.5f);
            if (world) root.SetPositionAndRotation(p, r);
            else root.SetLocalPositionAndRotation(p, r);
            root.gameObject.SetActive(true);
        }

        /// <summary>ARCore のカメラ画像 (CPU 側、撮像素子の向き) を輝度で取り出して検出に回す。</summary>
        void SubmitARFrame()
        {
            if (cameraManager == null || arCam == null || !cameraManager.TryAcquireLatestCpuImage(out XRCpuImage image)) return;
            using (image)
            {
                int div = image.width > 800 ? 2 : 1;
                int w = image.width / div, h = image.height / div;
                var p = new XRCpuImage.ConversionParams(image, TextureFormat.R8, XRCpuImage.Transformation.None)
                {
                    outputDimensions = new Vector2Int(w, h)
                };
                if (image.GetConvertedDataSize(p) != w * h) return;
                var native = new NativeArray<byte>(w * h, Allocator.Temp);
                try
                {
                    image.Convert(p, native);
                    native.CopyTo(tracker.Buffer(w, h));
                }
                finally
                {
                    native.Dispose();
                }

                double fx, fy, cx, cy;
                if (cameraManager.TryGetIntrinsics(out XRCameraIntrinsics k) && k.resolution.x > 0 && k.resolution.y > 0)
                {
                    double sx = w / (double)k.resolution.x, sy = h / (double)k.resolution.y;
                    fx = k.focalLength.x * sx;
                    fy = k.focalLength.y * sy;
                    cx = k.principalPoint.x * sx;
                    cy = k.principalPoint.y * sy;
                }
                else
                {
                    fx = fy = Mathf.Max(w, h) * 0.5 / Mathf.Tan(WebcamHFov * 0.5f * Mathf.Deg2Rad);
                    cx = (w - 1) * 0.5;
                    cy = (h - 1) * 0.5;
                }
                Vector2 right, down;
                if (displayMatrix.HasValue) AidyMarkerTracker.AxesFromDisplayMatrix(displayMatrix.Value, out right, out down);
                else AidyMarkerTracker.RotatedAxes(90, out right, out down); // 縦持ち・背面カメラの一般的な向き
                Transform c = arCam.transform;
                tracker.Submit(w, h, fx, fy, cx, cy, right, down, Matrix4x4.TRS(c.position, c.rotation, Vector3.one));
            }
        }

        // Web カメラ (または検証用の静止画) の映像
        Texture CamTexture => testFrame != null ? testFrame : (Texture)webcam;
        int CamRotation => testFrame != null || webcam == null ? 0 : webcam.videoRotationAngle;
        bool CamMirrored => testFrame == null && webcam != null && webcam.videoVerticallyMirrored && !RemoteCamera;

        /// <summary>
        /// Unity Remote 5 の端末カメラ。iPhone は videoVerticallyMirrored = true を返すが、エディタに届く映像は
        /// 反転済みなので、補正すると逆に反転してしまう (マーカーも鏡像になって読めない)。
        /// </summary>
        static bool RemoteCamera
        {
            get
            {
#if UNITY_EDITOR
                return UnityEditor.EditorApplication.isRemoteConnected;
#else
                return false;
#endif
            }
        }

        /// <summary>Web カメラの映像を輝度にして (長辺が 800 を超えるなら 1/2 に縮めて) 検出に回す。</summary>
        void SubmitWebcamFrame()
        {
            int W, H;
            if (testFrame != null)
            {
                W = testFrame.width;
                H = testFrame.height;
                pixels = testFrame.GetPixels32();
            }
            else
            {
                if (webcam == null || !webcam.isPlaying || !webcam.didUpdateThisFrame || webcam.width <= 16) return;
                W = webcam.width;
                H = webcam.height;
                if (pixels == null || pixels.Length != W * H) pixels = new Color32[W * H];
                webcam.GetPixels32(pixels);
            }
            int div = Mathf.Max(W, H) > 800 ? 2 : 1;
            int w = W / div, h = H / div;
            byte[] buf = tracker.Buffer(w, h);
            bool mirrored = CamMirrored;
            for (int y = 0; y < h; y++)
            {
                // テクスチャの画素は下の行から。表示 (上下反転なし) では最後の行が画像の上端
                int src = (mirrored ? y * div : H - 1 - y * div) * W;
                int dst = y * w;
                for (int x = 0; x < w; x++)
                {
                    Color32 c = pixels[src + x * div];
                    buf[dst + x] = (byte)((c.r * 77 + c.g * 150 + c.b * 29) >> 8);
                }
            }
            double f = WebcamFocalPx(W, H) / div;
            AidyMarkerTracker.RotatedAxes(CamRotation, out Vector2 right, out Vector2 down);
            // モデルは fallbackCam の子としてカメラ座標で置く
            tracker.Submit(w, h, f, f, (w - 1) * 0.5, (h - 1) * 0.5, right, down, Matrix4x4.identity);
        }

        static float WebcamFocalPx(int w, int h) => Mathf.Max(w, h) * 0.5f / Mathf.Tan(WebcamHFov * 0.5f * Mathf.Deg2Rad);

        /// <summary>画面に見えている範囲の縦の画角 (映像は object-fit: cover で拡大・回転して表示している)。</summary>
        float WebcamVerticalFov()
        {
            Texture tex = CamTexture;
            if (tex == null || tex.width <= 16) return 45f;
            bool swap = CamRotation % 180 != 0;
            float vw = swap ? tex.height : tex.width, vh = swap ? tex.width : tex.height;
            Vector2 area = WebcamArea.rect.size;
            float scale = Mathf.Max(area.x / vw, area.y / vh);
            float visible = area.y / scale; // 画面の縦に入っている映像の画素数
            return 2f * Mathf.Atan(visible * 0.5f / WebcamFocalPx(tex.width, tex.height)) * Mathf.Rad2Deg;
        }

        void LateUpdate()
        {
            if (!fallbackCam.enabled) return;
            Rect r = OverlayImage.rectTransform.rect;
            float s = canvas != null ? canvas.scaleFactor : 1f;
            int w = Mathf.Max(16, Mathf.RoundToInt(r.width * s));
            int h = Mathf.Max(16, Mathf.RoundToInt(r.height * s));
            if (rt != null && rt.width == w && rt.height == h) return;
            if (rt != null)
            {
                fallbackCam.targetTexture = null;
                rt.Release();
                Destroy(rt);
            }
            rt = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32) { antiAliasing = 4, name = "ARFallbackRT" };
            rt.Create();
            fallbackCam.targetTexture = rt;
            OverlayImage.texture = rt;
        }

        /// <summary>video { object-fit: cover } + 端末の回転・反転補正。</summary>
        void UpdateWebcamRect()
        {
            Texture tex = CamTexture;
            if (tex == null || !WebcamImage.enabled || tex.width <= 16) return;
            int rot = CamRotation;
            bool swap = rot % 180 != 0;
            float w = tex.width, h = tex.height;
            float vw = swap ? h : w, vh = swap ? w : h;
            Vector2 area = WebcamArea.rect.size;
            float scale = Mathf.Max(area.x / vw, area.y / vh);
            var rtf = WebcamImage.rectTransform;
            rtf.sizeDelta = new Vector2(w * scale, h * scale);
            rtf.localEulerAngles = new Vector3(0, 0, -rot);
            WebcamImage.uvRect = CamMirrored ? new Rect(0, 1, 1, -1) : new Rect(0, 0, 1, 1);
        }

        void OnApplicationPause(bool paused)
        {
            if (webcam == null || CurrentMode != Mode.Webcam) return;
            if (paused) webcam.Pause();
            else webcam.Play();
        }

        void OnDestroy()
        {
            ModuleConfig.Changed -= OnModuleChanged;
            AidyMarkerStore.Changed -= OnMarkersChanged;
            if (webcam != null) webcam.Stop();
            if (rt != null) rt.Release();
        }
    }

    /// <summary>AR 画面のタッチ領域 (上下の UI を除いた帯)。タップで配置、ドラッグで回転。</summary>
    public class AidyARTouch : MonoBehaviour, IPointerDownHandler, IPointerUpHandler, IInitializePotentialDragHandler, IBeginDragHandler, IDragHandler, IEndDragHandler
    {
        public AidyARView View;
        Canvas canvas;

        float Scale
        {
            get
            {
                if (canvas == null) canvas = GetComponentInParent<Canvas>();
                return canvas != null ? canvas.scaleFactor : 1f;
            }
        }

        public void OnPointerDown(PointerEventData e) { View.PointerDown(); }
        public void OnPointerUp(PointerEventData e) { View.PointerUp(e.position); }
        public void OnInitializePotentialDrag(PointerEventData e) { e.useDragThreshold = false; }
        public void OnBeginDrag(PointerEventData e) { }
        public void OnEndDrag(PointerEventData e) { }

        public void OnDrag(PointerEventData e)
        {
            Vector2 d = e.delta / Scale;
            View.Drag(new Vector2(d.x, -d.y));
        }
    }
}
