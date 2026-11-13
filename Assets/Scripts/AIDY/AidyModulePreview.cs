using System.Collections.Generic;
using UnityEngine;

namespace Aidy
{
    /// <summary>
    /// モジュール一覧の丸いプレビュー画像。専用レイヤーの小さなステージ (本体なし) にモジュールを置き、
    /// 1 つずつ RenderTexture に描く。描くのは呼ばれたときだけ (中央のモジュールを回すときは毎フレーム)。
    /// </summary>
    public static class AidyModulePreview
    {
        public const int Layer = 11;
        const int Px = 256;
        const float Fov = 28f;
        static readonly Vector3 StageOffset = new Vector3(-3000, 0, 0);

        class Entry
        {
            public GameObject Root;
            public RenderTexture Tex;
            public float Radius;
            public Vector3 ViewDir;
            public Color Bg;
        }

        static Transform stage;
        static Camera cam;
        static Material satin;
        static readonly Dictionary<ModuleKind, Entry> entries = new Dictionary<ModuleKind, Entry>();

        /// <summary>そのモジュールのプレビュー画像 (初回は既定の向きで描く)。</summary>
        public static Texture Get(ModuleKind kind) => Ensure(kind).Tex;

        /// <summary>縦軸まわりに yaw 度回した向きで描き直す。</summary>
        public static void Render(ModuleKind kind, float yaw)
        {
            Entry e = Ensure(kind);
            Draw(e, yaw);
        }

        static Entry Ensure(ModuleKind kind)
        {
            if (entries.TryGetValue(kind, out Entry e)) return e;
            EnsureStage();
            e = new Entry
            {
                Tex = new RenderTexture(Px, Px, 24, RenderTextureFormat.ARGB32) { antiAliasing = 4, name = "ModulePreview_" + kind },
                Bg = Color.Lerp(Color.white, ModuleCatalog.Tint(kind), 0.12f),
                // 顔は正面から、ほかは斜め横から (尻尾の反り・手の長さが分かる向き)
                ViewDir = (kind == ModuleKind.Face ? new Vector3(0.45f, 0.3f, 1f) : new Vector3(1f, 0.55f, 0.75f)).normalized
            };
            BuildModel(kind, e);
            entries[kind] = e;
            Draw(e, 0f);
            return e;
        }

        static void EnsureStage()
        {
            if (stage != null) return;
            var root = new GameObject("ModulePreview_Stage");
            Object.DontDestroyOnLoad(root);
            root.transform.position = StageOffset;
            stage = root.transform;

            var camGo = new GameObject("Preview_Camera");
            camGo.transform.SetParent(stage, false);
            cam = camGo.AddComponent<Camera>();
            cam.enabled = false; // Render() で描くときだけ使う
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.fieldOfView = Fov;
            cam.nearClipPlane = 0.01f;
            cam.farClipPlane = 100f;
            cam.cullingMask = 1 << Layer;
            cam.allowHDR = false;

            // ホームと同じ向きの平行光 (影なし)
            var lightGo = new GameObject("Preview_Light");
            lightGo.transform.SetParent(stage, false);
            var light = lightGo.AddComponent<Light>();
            light.type = LightType.Directional;
            light.color = Color.white;
            light.intensity = 0.95f;
            light.shadows = LightShadows.None;
            light.cullingMask = 1 << Layer;
            lightGo.transform.rotation = Quaternion.LookRotation(-Three.Pos(4, 8, 5).normalized, Vector3.up);

            satin = AidyModuleRig.Satin();
            Three.SetLayer(root, Layer);
        }

        /// <summary>Root (回転の中心 = モデルの中心) → Offset → 取り付け面の座標系 → モジュールの形。</summary>
        static void BuildModel(ModuleKind kind, Entry e)
        {
            var root = new GameObject("Preview_" + kind);
            root.layer = Layer;
            root.transform.SetParent(stage, false);
            var offset = new GameObject("Offset").transform;
            offset.gameObject.layer = Layer;
            offset.SetParent(root.transform, false);

            if (kind == ModuleKind.Face)
            {
                // 顔モジュールは本体v2_顔ありに切り替わるので、その本体を見せる
                if (AidyModel.BuildScaledInstance(offset, 1f, Layer, AidyModel.BodyWithFacePath) == null)
                    AidyMeshes.Create("Face", offset, AidyMeshes.Circle(0.3f, 32), AidyMaterials.Basic(Theme.Primary));
            }
            else
            {
                // 本体に付いたときと同じ向きにする (代表のスロット: 耳=上面, 尻尾=背面, 足=底面, 手=左面)
                var mount = new GameObject("Mount").transform;
                mount.SetParent(offset, false);
                BodySlot slot = SampleSlot(kind);
                if (slot != null) mount.localRotation = slot.MountRotation;
                AidyModuleRig.BuildModule(kind, mount, slot, 1f, satin, new List<Transform>());
            }
            Three.SetLayer(root, Layer);

            var renderers = root.GetComponentsInChildren<Renderer>();
            if (renderers.Length > 0)
            {
                Bounds b = renderers[0].bounds;
                foreach (Renderer r in renderers) b.Encapsulate(r.bounds);
                offset.localPosition = stage.InverseTransformVector(root.transform.position - b.center);
                e.Radius = Mathf.Max(b.extents.magnitude, 0.01f);
                foreach (Renderer r in renderers)
                {
                    r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                    r.receiveShadows = false;
                }
            }
            else e.Radius = 0.2f;
            root.SetActive(false);
            e.Root = root;
        }

        static BodySlot SampleSlot(ModuleKind kind)
        {
            string face = kind == ModuleKind.Ear || kind == ModuleKind.Hat ? "top" : ModuleCatalog.IsTail(kind) ? "back" : kind == ModuleKind.Legs ? "bottom" : "left";
            foreach (BodySlot s in BodyLayout.OnFace(face))
                if (ModuleCatalog.Fits(kind, s)) return s;
            foreach (BodySlot s in BodyLayout.Slots)
                if (ModuleCatalog.Fits(kind, s)) return s;
            return null;
        }

        static void Draw(Entry e, float yaw)
        {
            e.Root.SetActive(true);
            e.Root.transform.localRotation = Quaternion.AngleAxis(yaw, Vector3.up);
            // 外接球が画角に収まる距離 (少し余白を残す)
            float dist = e.Radius / Mathf.Sin(Fov * 0.5f * Mathf.Deg2Rad) * 1.08f;
            cam.transform.localPosition = e.ViewDir * dist;
            cam.transform.localRotation = Quaternion.LookRotation(-e.ViewDir, Vector3.up);
            cam.backgroundColor = e.Bg;
            cam.targetTexture = e.Tex;
            cam.Render();
            cam.targetTexture = null;
            e.Root.SetActive(false);
        }
    }
}
