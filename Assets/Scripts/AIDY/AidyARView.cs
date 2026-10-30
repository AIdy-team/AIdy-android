using System;
using System.Collections;
using System.Collections.Generic;
using Unity.XR.CoreUtils;
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
    /// </summary>
    public class AidyARView : MonoBehaviour
    {
        public const int FallbackLayer = 9;
        const float Step = 1f / 60f;
        const float ARModelSize = 0.15f; // 実機ARでは実寸 (本体は 150mm 角)

        public enum Mode { None, Starting, AR, Webcam }

        public Mode CurrentMode { get; private set; } = Mode.None;
        public bool PageVisible;

        // UI (AidyApp が設定)
        public RectTransform Page;
        public Image Black;
        public RectTransform WebcamArea;
        public RawImage WebcamImage;
        public RawImage OverlayImage;
        public Action<IconId, Color, float, string> SetStatus;
        public Action<string> SetPosition;

        // フォールバック表示 (WebDemo の arScene)
        Camera fallbackCam;
        TNode arCube, targetRing;
        RenderTexture rt;
        Canvas canvas;
        WebCamTexture webcam;
        bool arCapable;

        // 実機AR
        ARSession session;
        GameObject originGo;
        Camera arCam;
        ARRaycastManager raycaster;
        Transform placedRoot, placedModel, placedRing;
        AidyModuleRig fallbackRig, placedRig;
        float placedYaw;
        ARSessionState lastState = ARSessionState.None;
        static readonly List<ARRaycastHit> hits = new List<ARRaycastHit>();

        bool draggingAR;
        float dragDistance;
        float accumulator;

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
            fallbackRig?.Sync();
            placedRig?.Sync();
            if (placedModel != null) placedModel.localPosition = new Vector3(0, ARModelSize * 0.5f + placedRig.LegReach, 0);
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

            // targetRing: RingGeometry(0.4, 0.48, 32) / 0x38bdf8
            targetRing = new TNode("TargetRing", t) { P = new Vector3(0, -0.6f, 0), R = new Vector3(Mathf.PI / 2, 0, 0) };
            targetRing.Apply();
            AidyMeshes.Create("Ring", targetRing.T, AidyMeshes.Ring(0.4f, 0.48f, 32), AidyMaterials.Basic(Theme.Hex(0x38bdf8)));

            arCube = new TNode("ARCubeGroup", t);
            arCube.Apply();
            fallbackRig = new AidyModuleRig(arCube.T, 0.9f, FallbackLayer, false);
            if (!fallbackRig.HasModel)
            {
                var cube = GameObject.CreatePrimitive(PrimitiveType.Cube);
                Destroy(cube.GetComponent<Collider>());
                cube.transform.SetParent(arCube.T, false);
                cube.transform.localScale = Vector3.one * 0.9f;
                cube.GetComponent<MeshRenderer>().sharedMaterial = AidyMaterials.Standard(new Color(0.627451f, 0.627451f, 0.627451f), 0.55f, 0.35f);
                cube.layer = FallbackLayer;
            }
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
                    if (webcam == null || !webcam.isPlaying) StartCoroutine(StartWebcam());
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
                    SetPosition?.Invoke("基準位置: 未配置 (床や机をタップ)");
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
                // facingMode: "environment" (背面カメラ優先)
                string name = devices[0].name;
                foreach (var d in devices)
                {
                    if (!d.isFrontFacing)
                    {
                        name = d.name;
                        break;
                    }
                }
                if (webcam == null) webcam = new WebCamTexture(name, 1280, 720, 30);
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
                    SetStatus?.Invoke(IconId.Circle, Theme.Green, 10, "Webカメラ動作中 (本体v2配置)");
                    yield break;
                }
            }
            SetStatus?.Invoke(IconId.HandPointer, Color.white, 12, "クリックした場所を基準に本体v2配置");
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
            camGo.AddComponent<ARCameraManager>();
            camGo.AddComponent<ARCameraBackground>();
            var tpd = camGo.AddComponent<TrackedPoseDriver>();
            tpd.SetPoseSource(TrackedPoseDriver.DeviceType.GenericXRDevice, TrackedPoseDriver.TrackedPose.ColorCamera);
            tpd.trackingType = TrackedPoseDriver.TrackingType.RotationAndPosition;
            tpd.updateType = TrackedPoseDriver.UpdateType.UpdateAndBeforeRender;

            origin.Camera = arCam;
            origin.CameraFloorOffsetObject = offset;

            raycaster = originGo.AddComponent<ARRaycastManager>();
            var planes = originGo.AddComponent<ARPlaneManager>();
            planes.requestedDetectionMode = PlaneDetectionMode.Horizontal;
            planes.planePrefab = CreatePlaneTemplate();

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

            placedRing = new GameObject("TargetRing").transform;
            placedRing.SetParent(placedRoot, false);
            placedRing.localPosition = new Vector3(0, 0.002f, 0);
            placedRing.localRotation = Three.Rot(new Vector3(Mathf.PI / 2, 0, 0));
            placedRing.localScale = Vector3.one * (ARModelSize / 0.9f);
            AidyMeshes.Create("Ring", placedRing, AidyMeshes.Ring(0.4f, 0.48f, 32), AidyMaterials.Basic(Theme.Hex(0x38bdf8)));
        }

        void DestroyAR()
        {
            if (session != null) Destroy(session.gameObject);
            if (originGo != null) Destroy(originGo);
            if (placedRoot != null) Destroy(placedRoot.gameObject);
            session = null;
            originGo = null;
            arCam = null;
        }

        void PlaceAR(Vector2 screen, bool center)
        {
            Pose pose;
            if (raycaster != null && raycaster.Raycast(screen, hits, TrackableType.PlaneWithinPolygon))
            {
                pose = hits[0].pose;
            }
            else
            {
                Ray ray = arCam.ScreenPointToRay(screen);
                pose = new Pose(ray.GetPoint(0.7f), Quaternion.identity);
            }
            placedRoot.gameObject.SetActive(true);
            placedRoot.SetPositionAndRotation(pose.position, Quaternion.identity);
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
            if (dragDistance <= 8f) return;
            if (CurrentMode == Mode.AR)
            {
                placedYaw += delta.x * 0.01f;
            }
            else
            {
                arCube.R.y += delta.x * 0.01f;
                arCube.R.x += delta.y * 0.01f;
                arCube.Apply();
            }
        }

        public void PointerUp(Vector2 screen)
        {
            if (draggingAR && dragDistance <= 8f)
            {
                if (CurrentMode == Mode.AR) PlaceAR(screen, false);
                else PlaceFallback(screen);
            }
            draggingAR = false;
        }

        void PlaceFallback(Vector2 screen)
        {
            RectTransform r = OverlayImage.rectTransform;
            if (!RectTransformUtility.ScreenPointToLocalPointInRectangle(r, screen, null, out Vector2 local)) return;
            Rect rect = r.rect;
            float mx = (local.x - rect.xMin) / rect.width * 2f - 1f;
            float my = (local.y - rect.yMin) / rect.height * 2f - 1f;
            float tx = mx * 1.5f, ty = my * 1.5f;
            arCube.P = new Vector3(tx, ty, 0);
            arCube.Apply();
            targetRing.P = new Vector3(tx, ty - 0.5f, 0);
            targetRing.Apply();
            SetPosition?.Invoke($"基準位置: ({tx:F2}, {ty:F2}) 本体v2配置完了");
        }

        public void ResetPosition()
        {
            if (CurrentMode == Mode.AR)
            {
                Vector2 c = RectTransformUtility.WorldToScreenPoint(null, Page.TransformPoint(Page.rect.center));
                PlaceAR(c, true);
                return;
            }
            arCube.P = Vector3.zero;
            arCube.R = Vector3.zero;
            arCube.Apply();
            targetRing.P = new Vector3(0, -0.6f, 0);
            targetRing.Apply();
            SetPosition?.Invoke("基準位置: (0.00, 0.00) 本体v2中央配置");
        }

        // ------------------------------------------------------------ 毎フレーム

        bool OverlayVisible => CurrentMode == Mode.Webcam || (!arCapable && CurrentMode == Mode.None);

        void Update()
        {
            accumulator = Mathf.Min(accumulator + Time.deltaTime, 0.25f);
            while (accumulator >= Step)
            {
                accumulator -= Step;
                // animateAR: targetRing.rotation.z += 0.01 / 非ドラッグ時 arCubeGroup.rotation.y += 0.004
                targetRing.R.z += 0.01f;
                targetRing.Apply();
                if (!draggingAR)
                {
                    arCube.R.y += 0.004f;
                    arCube.Apply();
                    placedYaw += 0.004f;
                }
                if (placedRing != null) placedRing.localRotation = Three.Rot(new Vector3(Mathf.PI / 2, 0, targetRing.R.z));
            }
            if (placedModel != null) placedModel.localRotation = Three.Rot(new Vector3(0, placedYaw, 0));

            bool overlay = OverlayVisible;
            OverlayImage.enabled = overlay;
            fallbackCam.enabled = overlay && PageVisible;
            Black.enabled = CurrentMode != Mode.AR;
            if (CurrentMode != Mode.Webcam) WebcamImage.enabled = false;
            if (arCam != null) arCam.enabled = CurrentMode == Mode.AR && PageVisible;

            if (CurrentMode == Mode.AR && ARSession.state != lastState)
            {
                lastState = ARSession.state;
                if (lastState == ARSessionState.SessionTracking)
                    SetStatus?.Invoke(IconId.Circle, Theme.Green, 10, "ARカメラ動作中 (床や机をタップして本体v2配置)");
                else if (lastState == ARSessionState.SessionInitializing)
                    SetStatus?.Invoke(IconId.HandPointer, Color.white, 12, "端末をゆっくり動かして床や机を認識中…");
            }

            UpdateWebcamRect();
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
            if (webcam == null || !WebcamImage.enabled || webcam.width <= 16) return;
            int rot = webcam.videoRotationAngle;
            bool swap = rot % 180 != 0;
            float w = webcam.width, h = webcam.height;
            float vw = swap ? h : w, vh = swap ? w : h;
            Vector2 area = WebcamArea.rect.size;
            float scale = Mathf.Max(area.x / vw, area.y / vh);
            var rtf = WebcamImage.rectTransform;
            rtf.sizeDelta = new Vector2(w * scale, h * scale);
            rtf.localEulerAngles = new Vector3(0, 0, -rot);
            WebcamImage.uvRect = webcam.videoVerticallyMirrored ? new Rect(0, 1, 1, -1) : new Rect(0, 0, 1, 1);
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
