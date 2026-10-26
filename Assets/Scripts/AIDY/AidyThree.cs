using System.Collections.Generic;
using UnityEngine;

namespace Aidy
{
    /// <summary>
    /// WebDemo (three.js, 右手系) の数値をそのまま使えるようにする変換。
    /// Unity の OBJ インポーターは X を反転して取り込むため、シーン全体も X 反転で揃えると
    /// three.js と同じ見た目になる: 位置 (x,y,z)→(-x,y,z)、回転 Euler XYZ (rx,ry,rz)→Rx(rx)·Ry(-ry)·Rz(-rz)。
    /// </summary>
    public static class Three
    {
        public static Vector3 Pos(float x, float y, float z) => new Vector3(-x, y, z);
        public static Vector3 Pos(Vector3 p) => new Vector3(-p.x, p.y, p.z);

        public static Quaternion Rot(Vector3 r)
        {
            return Quaternion.AngleAxis(r.x * Mathf.Rad2Deg, Vector3.right)
                 * Quaternion.AngleAxis(-r.y * Mathf.Rad2Deg, Vector3.up)
                 * Quaternion.AngleAxis(-r.z * Mathf.Rad2Deg, Vector3.forward);
        }

        public static void Set(Transform t, Vector3 pos, Vector3 rot)
        {
            t.localPosition = Pos(pos);
            t.localRotation = Rot(rot);
        }

        public static void SetLayer(GameObject go, int layer)
        {
            go.layer = layer;
            foreach (Transform c in go.transform) SetLayer(c.gameObject, layer);
        }
    }

    /// <summary>three.js の Object3D 相当 (position / rotation を three の数値で保持)。</summary>
    public class TNode
    {
        public readonly Transform T;
        public Vector3 P;
        public Vector3 R;

        public TNode(Transform t) { T = t; }

        public TNode(string name, Transform parent)
        {
            T = new GameObject(name).transform;
            T.SetParent(parent, false);
            T.gameObject.layer = parent.gameObject.layer;
        }

        public void Apply()
        {
            T.localPosition = Three.Pos(P);
            T.localRotation = Three.Rot(R);
        }
    }

    public static class AidyMaterials
    {
        static Shader satin, unlit;

        /// <summary>MeshStandardMaterial(color, metalness, roughness, side: DoubleSide)</summary>
        public static Material Standard(Color color, float metalness, float roughness)
        {
            if (satin == null) satin = Resources.Load<Shader>("AIDY/AidySatin");
            var m = new Material(satin != null ? satin : Shader.Find("Standard"));
            m.SetColor("_Color", color);
            m.SetFloat("_Metallic", metalness);
            m.SetFloat("_Glossiness", 1f - roughness);
            return m;
        }

        /// <summary>テクスチャを貼ったライティングなしマテリアル。</summary>
        public static Material Textured(Texture tex)
        {
            Material m = Basic(Color.white);
            m.SetTexture("_MainTex", tex);
            return m;
        }

        /// <summary>MeshBasicMaterial(color)。alpha &lt; 1 なら半透明。</summary>
        public static Material Basic(Color color)
        {
            if (unlit == null) unlit = Resources.Load<Shader>("AIDY/AidyUnlit");
            var m = new Material(unlit);
            m.SetColor("_Color", color);
            if (color.a < 1f)
            {
                m.SetFloat("_SrcBlend", (float)UnityEngine.Rendering.BlendMode.SrcAlpha);
                m.SetFloat("_DstBlend", (float)UnityEngine.Rendering.BlendMode.OneMinusSrcAlpha);
                m.SetFloat("_ZWrite", 0f);
                m.renderQueue = 3000;
            }
            return m;
        }
    }

    /// <summary>three.js の各 Geometry と同じ寸法・向きのメッシュ生成。</summary>
    public static class AidyMeshes
    {
        public static GameObject Create(string name, Transform parent, Mesh mesh, Material mat, bool castShadows = false, bool receiveShadows = false)
        {
            var go = new GameObject(name);
            go.layer = parent.gameObject.layer;
            go.transform.SetParent(parent, false);
            go.AddComponent<MeshFilter>().sharedMesh = mesh;
            var mr = go.AddComponent<MeshRenderer>();
            mr.sharedMaterial = mat;
            mr.shadowCastingMode = castShadows ? UnityEngine.Rendering.ShadowCastingMode.On : UnityEngine.Rendering.ShadowCastingMode.Off;
            mr.receiveShadows = receiveShadows;
            return go;
        }

        /// <summary>PlaneGeometry(w, h): XY 平面、+Z 向き。</summary>
        public static Mesh Plane(float w, float h)
        {
            float x = w * 0.5f, y = h * 0.5f;
            var m = new Mesh { name = "Plane" };
            m.vertices = new[] { new Vector3(-x, -y, 0), new Vector3(x, -y, 0), new Vector3(x, y, 0), new Vector3(-x, y, 0) };
            m.normals = new[] { Vector3.forward, Vector3.forward, Vector3.forward, Vector3.forward };
            m.uv = new[] { new Vector2(0, 0), new Vector2(1, 0), new Vector2(1, 1), new Vector2(0, 1) };
            m.triangles = new[] { 0, 1, 2, 0, 2, 3 };
            m.RecalculateBounds();
            return m;
        }

        /// <summary>CircleGeometry(r, segments): XY 平面、+Z 向き。</summary>
        public static Mesh Circle(float r, int seg)
        {
            var v = new Vector3[seg + 2];
            var n = new Vector3[seg + 2];
            var tri = new int[seg * 3];
            v[0] = Vector3.zero;
            for (int i = 0; i <= seg; i++)
            {
                float a = i / (float)seg * Mathf.PI * 2f;
                v[i + 1] = new Vector3(Mathf.Cos(a) * r, Mathf.Sin(a) * r, 0);
            }
            for (int i = 0; i < n.Length; i++) n[i] = Vector3.forward;
            for (int i = 0; i < seg; i++)
            {
                tri[i * 3] = 0;
                tri[i * 3 + 1] = i + 1;
                tri[i * 3 + 2] = i + 2;
            }
            var m = new Mesh { name = "Circle", vertices = v, normals = n, triangles = tri };
            m.RecalculateBounds();
            return m;
        }

        /// <summary>RingGeometry(inner, outer, segments): XY 平面、+Z 向き。</summary>
        public static Mesh Ring(float inner, float outer, int seg)
        {
            var v = new Vector3[(seg + 1) * 2];
            var n = new Vector3[v.Length];
            var tri = new int[seg * 6];
            for (int i = 0; i <= seg; i++)
            {
                float a = i / (float)seg * Mathf.PI * 2f;
                var d = new Vector3(Mathf.Cos(a), Mathf.Sin(a), 0);
                v[i * 2] = d * inner;
                v[i * 2 + 1] = d * outer;
                n[i * 2] = n[i * 2 + 1] = Vector3.forward;
            }
            for (int i = 0; i < seg; i++)
            {
                int a = i * 2, b = a + 1, c = a + 3, d = a + 2;
                tri[i * 6] = a; tri[i * 6 + 1] = b; tri[i * 6 + 2] = c;
                tri[i * 6 + 3] = a; tri[i * 6 + 4] = c; tri[i * 6 + 5] = d;
            }
            var m = new Mesh { name = "Ring", vertices = v, normals = n, triangles = tri };
            m.RecalculateBounds();
            return m;
        }

        /// <summary>CylinderGeometry(radiusTop, radiusBottom, height, segments): Y 軸、中心原点、上下キャップ付き。</summary>
        public static Mesh Cylinder(float rTop, float rBottom, float height, int seg)
        {
            float hy = height * 0.5f;
            int ring = seg + 1;
            var v = new Vector3[ring * 2 + (ring + 1) * 2];
            var n = new Vector3[v.Length];
            var tri = new System.Collections.Generic.List<int>();
            float slope = (rBottom - rTop) / height;

            for (int i = 0; i <= seg; i++)
            {
                float a = i / (float)seg * Mathf.PI * 2f;
                float s = Mathf.Sin(a), c = Mathf.Cos(a);
                v[i] = new Vector3(rTop * s, hy, rTop * c);
                v[ring + i] = new Vector3(rBottom * s, -hy, rBottom * c);
                n[i] = n[ring + i] = new Vector3(s, slope, c).normalized;
            }
            for (int i = 0; i < seg; i++)
            {
                int t0 = i, t1 = i + 1, b0 = ring + i, b1 = ring + i + 1;
                tri.AddRange(new[] { b0, t1, t0, b0, b1, t1 });
            }

            int topC = ring * 2, top0 = topC + 1;
            int botC = top0 + ring, bot0 = botC + 1;
            v[topC] = new Vector3(0, hy, 0);
            v[botC] = new Vector3(0, -hy, 0);
            n[topC] = Vector3.up;
            n[botC] = Vector3.down;
            for (int i = 0; i <= seg; i++)
            {
                v[top0 + i] = v[i];
                n[top0 + i] = Vector3.up;
                v[bot0 + i] = v[ring + i];
                n[bot0 + i] = Vector3.down;
            }
            for (int i = 0; i < seg; i++)
            {
                tri.AddRange(new[] { topC, top0 + i, top0 + i + 1 });
                tri.AddRange(new[] { botC, bot0 + i + 1, bot0 + i });
            }

            var m = new Mesh { name = "Cylinder", vertices = v, normals = n, triangles = tri.ToArray() };
            m.RecalculateBounds();
            return m;
        }
    }

    /// <summary>
    /// 本体モデル (.obj + .mtl) の読み込み。WebDemo の buildScaledInstance と同じく
    /// 「中心を原点へ → 最大辺が targetSize になるよう一括スケール → X軸 -90° 回転」で配置する。
    /// 本体v1_顔あり (顔パーツ装着時のメイン) と 本体v1_モデル (顔なし) の2種類がある。
    /// </summary>
    public static class AidyModel
    {
        public const string BodyPath = "AIDY/本体v1_モデル";
        public const string BodyWithFacePath = "AIDY/本体v1_顔あり";

        static readonly Dictionary<string, GameObject> sources = new Dictionary<string, GameObject>();

        static GameObject Source(string path)
        {
            if (!sources.TryGetValue(path, out GameObject go))
            {
                go = Resources.Load<GameObject>(path);
                sources[path] = go;
            }
            return go;
        }

        public static bool IsAvailable(string path) => Source(path) != null;

        /// <summary>parent 配下に pivot を作って返す。モデルがなければ null。</summary>
        public static Transform BuildScaledInstance(Transform parent, float targetSize, int layer, string path = BodyPath)
        {
            GameObject source = Source(path);
            if (source == null) return null;

            var inner = Object.Instantiate(source);
            inner.name = source.name;
            inner.transform.localPosition = Vector3.zero;
            inner.transform.localRotation = Quaternion.identity;
            inner.transform.localScale = Vector3.one;

            var renderers = inner.GetComponentsInChildren<MeshRenderer>();
            if (renderers.Length == 0)
            {
                Object.Destroy(inner);
                return null;
            }
            Bounds b = renderers[0].bounds;
            foreach (var r in renderers) b.Encapsulate(r.bounds);
            float maxDim = Mathf.Max(b.size.x, Mathf.Max(b.size.y, b.size.z));
            if (maxDim <= 0f) maxDim = 1f;

            // MTL の拡散色 (Kd) を引き継いだ不透明マテリアル。サブメッシュ (材質) ごとに置き換える
            foreach (var r in renderers)
            {
                Material[] mats = r.sharedMaterials;
                for (int i = 0; i < mats.Length; i++) mats[i] = SolidMaterial(mats[i]);
                r.sharedMaterials = mats;
                r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.On;
                r.receiveShadows = true;
            }

            var pivot = new GameObject("BodyPivot_" + source.name).transform;
            pivot.SetParent(parent, false);
            inner.transform.SetParent(pivot, false);
            inner.transform.localPosition = -b.center;
            pivot.localScale = Vector3.one * (targetSize / maxDim);
            pivot.localRotation = Three.Rot(new Vector3(-Mathf.PI / 2f, 0, 0)); // 前面になるはずの面(底面)を前面(+Z)へ
            Three.SetLayer(pivot.gameObject, layer);
            return pivot;
        }

        /// <summary>顔あり/顔なしの両モデルを parent 配下に作り、顔パーツの着脱で表示を切り替える。</summary>
        public class Variants
        {
            public readonly Transform WithFace;
            public readonly Transform Plain;
            public bool Any => WithFace != null || Plain != null;
            public bool HasFaceModel => WithFace != null;

            public Variants(Transform parent, float targetSize, int layer)
            {
                WithFace = BuildScaledInstance(parent, targetSize, layer, BodyWithFacePath);
                Plain = BuildScaledInstance(parent, targetSize, layer, BodyPath);
                // 外装の内側は空洞なので、モニター開口・カメラ穴・マイク穴・スロットから中身が見えないよう黒で埋める
                if (WithFace != null) AddInteriorOccluder(WithFace, targetSize, layer);
                if (Plain != null) AddInteriorOccluder(Plain, targetSize, layer);
            }

            /// <summary>顔あり: 本体v1_顔あり / 顔なし: 本体v1_モデル。戻り値は顔ありモデルを表示したか。</summary>
            public bool SetFace(bool on)
            {
                bool useFace = on && WithFace != null;
                if (WithFace != null) WithFace.gameObject.SetActive(useFace);
                if (Plain != null) Plain.gameObject.SetActive(!useFace);
                return useFace;
            }
        }

        /// <summary>
        /// 外装 (外形150mm・厚さ5mm) の内側の空洞 (140mm) を黒い箱で埋め、穴から中身が見えないようにする。
        /// 寸法は docs/本体モデルv1解析.txt: 内壁 (±70mm) とのちらつきを防ぐため 139.8mm の箱を中心に置く。
        /// </summary>
        public static void AddInteriorOccluder(Transform pivot, float targetSize, int layer)
        {
            const float BodySizeMm = 150f, OccluderSizeMm = 139.8f;
            var box = GameObject.CreatePrimitive(PrimitiveType.Cube);
            box.name = "InteriorOccluder";
            Object.Destroy(box.GetComponent<Collider>());
            box.layer = layer;
            box.transform.SetParent(pivot, false);
            box.transform.localPosition = Vector3.zero; // pivot = 外形の中心
            box.transform.localRotation = Quaternion.identity;
            // pivot のローカル単位では本体の一辺 = targetSize / pivot のスケール
            float bodyLocal = targetSize / pivot.localScale.x;
            box.transform.localScale = Vector3.one * (bodyLocal * OccluderSizeMm / BodySizeMm);
            var r = box.GetComponent<MeshRenderer>();
            r.sharedMaterial = AidyMaterials.Basic(Color.black);
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            r.receiveShadows = false;
        }

        /// <summary>
        /// 鋼サテン: metalness 0.35 / roughness 0.4 (WebDemo の toSolidMaterial)。
        /// エナメル塗装 (顔パーツ): 非金属の光沢塗装として metalness 0 / roughness 0.15。
        /// </summary>
        static Material SolidMaterial(Material src)
        {
            // Unity の OBJ インポーターは日本語名の .mtl を読めないことがあるため、
            // 材質名が分かるものは .mtl と同じ Kd をここで指定する
            string name = src != null ? src.name : "";
            if (name.IndexOf("EnamelWhite", System.StringComparison.OrdinalIgnoreCase) >= 0)
                return AidyMaterials.Standard(new Color(0.964706f, 0.964706f, 0.952941f), 0f, 0.15f);
            if (name.IndexOf("SteelSatin", System.StringComparison.OrdinalIgnoreCase) >= 0)
                return AidyMaterials.Standard(new Color(0.627451f, 0.627451f, 0.627451f), 0.35f, 0.4f);

            Color kd = (src != null && src.HasProperty("_Color")) ? src.color : new Color(0.627451f, 0.627451f, 0.627451f);
            kd.a = 1f;
            return AidyMaterials.Standard(kd, 0.35f, 0.4f);
        }
    }
}
