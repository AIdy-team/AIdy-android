using System;
using System.Collections.Generic;
using UnityEngine;

namespace Aidy
{
    /// <summary>本体に取り付けるモジュールの種類。</summary>
    public enum ModuleKind { Ear, Tail, Tail2, Tail45, TailCurl, Hat, Legs, Face, Hand, HandLight }

    /// <summary>モジュールの名前・アイコン・色・モデルファイル・取り付けられる面。</summary>
    public static class ModuleCatalog
    {
        public static readonly ModuleKind[] All = { ModuleKind.Ear, ModuleKind.Tail, ModuleKind.Tail2, ModuleKind.Tail45, ModuleKind.TailCurl, ModuleKind.Hat, ModuleKind.Legs, ModuleKind.Face, ModuleKind.Hand, ModuleKind.HandLight };

        public static string Name(ModuleKind k)
        {
            switch (k)
            {
                case ModuleKind.Ear: return "耳";
                case ModuleKind.Tail: return "尻尾";
                case ModuleKind.Tail2: return "尻尾2";
                case ModuleKind.Tail45: return "尻尾45";
                case ModuleKind.TailCurl: return "巻き尻尾";
                case ModuleKind.Hat: return "シルクハット";
                case ModuleKind.Legs: return "足";
                case ModuleKind.Hand: return "手";
                case ModuleKind.HandLight: return "ライト手";
                default: return "顔";
            }
        }

        public static IconId Icon(ModuleKind k)
        {
            switch (k)
            {
                case ModuleKind.Ear: return IconId.Ear;
                case ModuleKind.Tail:
                case ModuleKind.Tail2:
                case ModuleKind.Tail45:
                case ModuleKind.TailCurl: return IconId.Tail;
                case ModuleKind.Hat: return IconId.Hat;
                case ModuleKind.Legs: return IconId.Leg;
                case ModuleKind.Hand: return IconId.Hand;
                case ModuleKind.HandLight: return IconId.Flashlight;
                default: return IconId.Aidy;
            }
        }

        public static Color Tint(ModuleKind k)
        {
            switch (k)
            {
                case ModuleKind.Ear: return Theme.Hex(0xf97316);
                case ModuleKind.Tail: return Theme.Hex(0x8b5cf6);
                case ModuleKind.Tail2: return Theme.Hex(0x6366f1);
                case ModuleKind.Tail45: return Theme.Hex(0xa855f7);
                case ModuleKind.TailCurl: return Theme.Hex(0x0ea5e9);
                case ModuleKind.Hat: return Theme.Hex(0x334155);
                case ModuleKind.Legs: return Theme.Hex(0x10b981);
                case ModuleKind.Hand: return Theme.Hex(0xec4899);
                case ModuleKind.HandLight: return Theme.Hex(0xeab308);
                default: return Theme.Primary;
            }
        }

        /// <summary>モジュールをそのスロットに付けられるか (空きかどうかは見ない)。</summary>
        public static bool Fits(ModuleKind k, BodySlot s)
        {
            switch (k)
            {
                case ModuleKind.Face: return s.IsMonitor;
                case ModuleKind.Ear: return !s.IsMonitor && (s.Face == "top" || s.Face == "left" || s.Face == "right");
                case ModuleKind.Tail:
                case ModuleKind.Tail2:
                case ModuleKind.Tail45:
                case ModuleKind.TailCurl: return !s.IsMonitor && (s.Face == "back" || s.Face == "top");
                case ModuleKind.Hat: return s.Id == "top_2"; // つばが 80mm あるので上面の真ん中だけ
                case ModuleKind.Legs: return !s.IsMonitor && s.Face == "bottom";
                case ModuleKind.Hand:
                case ModuleKind.HandLight: return !s.IsMonitor && (s.Face == "left" || s.Face == "right");
            }
            return false;
        }

        public static bool IsTail(ModuleKind k) => k == ModuleKind.Tail || k == ModuleKind.Tail2 || k == ModuleKind.Tail45 || k == ModuleKind.TailCurl;

        /// <summary>
        /// モデルファイル (Resources/AIDY/ 以下、拡張子なし)。AidyModelLoader が *_stl → *_obj → *_3mf の順に探す。
        /// 顔はモデルファイルを持たない (本体v2_顔ありに切り替え)。
        /// </summary>
        public static string ModelPath(ModuleKind k)
        {
            switch (k)
            {
                case ModuleKind.Ear: return "AIDY/mimi2";
                case ModuleKind.Tail: return "AIDY/tail";
                case ModuleKind.Tail2: return "AIDY/tail2";
                case ModuleKind.Tail45: return "AIDY/尻尾_45";
                case ModuleKind.TailCurl: return "AIDY/尻尾_巻き2";
                case ModuleKind.Hat: return "AIDY/シルクハット";
                case ModuleKind.Hand: return "AIDY/手_短い";
                case ModuleKind.HandLight: return "AIDY/手_短い_懐中電灯";
                case ModuleKind.Legs: return "AIDY/足v1";
                default: return null;
            }
        }

        public static MountAxis Axis(ModuleKind k)
        {
            switch (k)
            {
                case ModuleKind.Ear:
                case ModuleKind.Hat: return MountAxis.ZUpFrontMinusY;
                case ModuleKind.Tail:
                case ModuleKind.Tail2:
                case ModuleKind.Tail45: return MountAxis.SlotTabPlusY;
                case ModuleKind.TailCurl: return MountAxis.EndPlusX;
                case ModuleKind.Legs: return MountAxis.TopPadZUp;
                default: return MountAxis.MagnetPlusX;
            }
        }

        /// <summary>その面に付けられるモジュール。</summary>
        public static List<ModuleKind> ForFace(string face)
        {
            var list = new List<ModuleKind>();
            foreach (ModuleKind k in All)
                foreach (BodySlot s in BodyLayout.OnFace(face))
                    if (Fits(k, s))
                    {
                        list.Add(k);
                        break;
                    }
            return list;
        }
    }

    /// <summary>
    /// 本体に付いているモジュールの構成 (スロット ID → モジュール)。ホーム・付け替えプレビュー・AR の
    /// すべての 3D モデルがこれを表示する。端末に保存される。
    /// </summary>
    public static class ModuleConfig
    {
        const string PrefKey = "aidy.modules.v1";
        static Dictionary<string, ModuleKind> slots;

        /// <summary>(スロット, 外れたモジュール, 付いたモジュール)。差し替えは両方入る。</summary>
        public static event Action<string, ModuleKind?, ModuleKind?> Changed;

        public static IReadOnlyDictionary<string, ModuleKind> Slots
        {
            get
            {
                Load();
                return slots;
            }
        }

        public static ModuleKind? At(string slotId)
        {
            Load();
            return slots.TryGetValue(slotId, out ModuleKind k) ? k : (ModuleKind?)null;
        }

        public static bool FaceOn => Has(ModuleKind.Face);
        public static bool HasLegs => Has(ModuleKind.Legs);

        public static bool Has(ModuleKind kind)
        {
            Load();
            foreach (ModuleKind k in slots.Values) if (k == kind) return true;
            return false;
        }

        public static int CountOnFace(string face)
        {
            Load();
            int n = 0;
            foreach (string id in slots.Keys) if (BodyLayout.Get(id)?.Face == face) n++;
            return n;
        }

        /// <summary>そのスロットに付けられるか (空きであること)。</summary>
        public static bool CanAttach(ModuleKind kind, string slotId)
        {
            BodySlot s = BodyLayout.Get(slotId);
            return s != null && ModuleCatalog.Fits(kind, s) && At(slotId) == null;
        }

        public static bool Attach(ModuleKind kind, string slotId)
        {
            if (!CanAttach(kind, slotId)) return false;
            slots[slotId] = kind;
            Save();
            Changed?.Invoke(slotId, null, kind);
            return true;
        }

        public static void Detach(string slotId)
        {
            ModuleKind? old = At(slotId);
            if (old == null) return;
            slots.Remove(slotId);
            Save();
            Changed?.Invoke(slotId, old, null);
        }

        /// <summary>差し替え (old → kind)。空きなら普通に取り付け。</summary>
        public static bool Replace(ModuleKind kind, string slotId)
        {
            BodySlot s = BodyLayout.Get(slotId);
            if (s == null || !ModuleCatalog.Fits(kind, s)) return false;
            ModuleKind? old = At(slotId);
            if (old == kind) return false;
            slots[slotId] = kind;
            Save();
            Changed?.Invoke(slotId, old, kind);
            return true;
        }

        /// <summary>標準構成 (初期状態・名刺マーカーの Aidy): 上面に耳2つ・背面に尻尾・底面に足2組・正面に顔。</summary>
        public static readonly IReadOnlyDictionary<string, ModuleKind> Default = new Dictionary<string, ModuleKind>
        {
            { "top_1", ModuleKind.Ear },
            { "top_3", ModuleKind.Ear },
            { "back_1", ModuleKind.Tail },
            { "bottom_1", ModuleKind.Legs },
            { "bottom_3", ModuleKind.Legs },
            { "monitor", ModuleKind.Face }
        };

        public static void ResetToDefault()
        {
            Load();
            foreach (string id in new List<string>(slots.Keys)) Detach(id);
            foreach (var kv in Default) Attach(kv.Value, kv.Key);
        }

        static void Load()
        {
            if (slots != null) return;
            slots = new Dictionary<string, ModuleKind>();
            string saved = PlayerPrefs.GetString(PrefKey, null);
            if (string.IsNullOrEmpty(saved))
            {
                ResetToDefault();
                return;
            }
            foreach (string pair in saved.Split(','))
            {
                string[] kv = pair.Split(':');
                if (kv.Length == 2 && Enum.TryParse(kv[1], out ModuleKind k) && BodyLayout.Get(kv[0]) != null) slots[kv[0]] = k;
            }
        }

        static void Save()
        {
            var parts = new List<string>();
            foreach (var kv in slots) parts.Add(kv.Key + ":" + kv.Value);
            PlayerPrefs.SetString(PrefKey, string.Join(",", parts));
            PlayerPrefs.Save();
        }
    }

    /// <summary>本体の取り付け口 (接続スロット13本 + 顔モニター開口)。座標は本体の一辺=1 に正規化 (Unity 軸、+X=正面から見て左)。</summary>
    public class BodySlot
    {
        public string Id, Face, Label;
        public Vector3 Position, Normal, LongAxis;
        public bool IsMonitor;
        public float WidthMm, HeightMm;

        /// <summary>取り付け面のローカル座標系 (+Y=外向き法線, +Z=正面 or 上)。</summary>
        public Quaternion MountRotation
        {
            get
            {
                Vector3 forward = Mathf.Abs(Normal.z) > 0.5f ? Vector3.up : Vector3.forward;
                return Quaternion.LookRotation(forward, Normal);
            }
        }
    }

    /// <summary>
    /// Resources/AIDY/body_v2_layout.json (本体v2.3mf から測った寸法) から取り付け口を読み込む。
    /// </summary>
    public static class BodyLayout
    {
        public static readonly string[] Faces = { "top", "front", "back", "left", "right", "bottom" };

        [Serializable] class V3 { public float x, y, z; public Vector3 V => new Vector3(x, y, z); }

        [Serializable]
        class Feature
        {
            public string id, face, type;
            public V3 position, normal, longAxis;
            public float widthMm, heightMm, lengthMm;
        }

        [Serializable] class LayoutFile { public List<Feature> features; }

        static List<BodySlot> slots;

        public static IReadOnlyList<BodySlot> Slots
        {
            get
            {
                if (slots == null) Load();
                return slots;
            }
        }

        public static BodySlot Get(string id)
        {
            foreach (BodySlot s in Slots) if (s.Id == id) return s;
            return null;
        }

        public static List<BodySlot> OnFace(string face)
        {
            var list = new List<BodySlot>();
            foreach (BodySlot s in Slots) if (s.Face == face) list.Add(s);
            return list;
        }

        public static string FaceLabel(string face)
        {
            switch (face)
            {
                case "top": return "上面";
                case "front": return "正面";
                case "back": return "背面";
                case "left": return "左面";
                case "right": return "右面";
                default: return "底面";
            }
        }

        public static Vector3 FaceNormal(string face)
        {
            switch (face)
            {
                case "top": return Vector3.up;
                case "front": return Vector3.forward;
                case "back": return Vector3.back;
                case "left": return Vector3.right; // +X = 正面から見て左
                case "right": return Vector3.left;
                default: return Vector3.down;
            }
        }

        /// <summary>その面を外から見たときの 2D 座標 (右, 上)。上面/底面は正面が画面下/上になる向き。</summary>
        public static Vector2 Project(string face, Vector3 p)
        {
            switch (face)
            {
                case "front": return new Vector2(-p.x, p.y);
                case "back": return new Vector2(p.x, p.y);
                case "left": return new Vector2(p.z, p.y);
                case "right": return new Vector2(-p.z, p.y);
                case "top": return new Vector2(-p.x, -p.z);
                default: return new Vector2(-p.x, p.z);
            }
        }

        static void Load()
        {
            slots = new List<BodySlot>();
            var asset = Resources.Load<TextAsset>("AIDY/body_v2_layout");
            if (asset == null)
            {
                Debug.LogWarning("[AIDY] body_v2_layout.json が見つかりません");
                return;
            }
            var file = JsonUtility.FromJson<LayoutFile>(asset.text);
            var perFace = new Dictionary<string, int>();
            foreach (Feature f in file.features)
            {
                bool monitor = f.id == "monitor";
                if (f.type != "slot" && !monitor) continue; // カメラ穴・マイク穴は取り付け口ではない
                perFace.TryGetValue(f.face, out int n);
                perFace[f.face] = ++n;
                slots.Add(new BodySlot
                {
                    Id = f.id,
                    Face = f.face,
                    Label = monitor ? "モニター" : "スロット" + n,
                    Position = f.position.V,
                    Normal = f.normal.V,
                    LongAxis = f.longAxis != null ? f.longAxis.V : Vector3.zero,
                    IsMonitor = monitor,
                    WidthMm = f.widthMm,
                    HeightMm = f.heightMm
                });
            }
        }
    }

    /// <summary>モデルファイルの向き。取り付け面ローカル (+Y=外向き, +Z=正面) へどう変換するか。</summary>
    public enum MountAxis
    {
        /// <summary>Z 上・-Y 正面・原点 (x,y) が取り付け位置、底面を取り付け面に置く (mimi2.stl)。</summary>
        ZUpFrontMinusY,
        /// <summary>-Y 方向へ突き出す。+Y 側の端 (XZ の中心) を取り付け面に置く。</summary>
        OutwardMinusY,
        /// <summary>
        /// +X 側の面にあるマグネット用のくぼみ (スロットと同じ 28.8mm の溝) を本体に当てる。
        /// くぼみの中心を取り付け位置に、+Y 側の端を正面 (+Z) に向ける (手_短い*.3mf)。
        /// </summary>
        MagnetPlusX,
        /// <summary>
        /// Z 上・+Y 外向き。-Y 側の台座の底面 (Y 最小の面) を取り付け面に置き、その中心を取り付け位置にして
        /// +Z 側へ反らせる (tail.stl, tail2.stl, 尻尾_45.stl)。
        /// </summary>
        SlotTabPlusY,
        /// <summary>Z 上・-X 外向き。+X 端の面 (X 最大の面) を取り付け面に置き、その中心を取り付け位置にする (尻尾_巻き2.stl)。</summary>
        EndPlusX,
        /// <summary>
        /// Z 上・-Y 正面。上面の平らな面 (Z 最大) を本体の底面に当て、その中心を取り付け位置にする。
        /// 足v1.3mf は左右 2 つ入りなので、X が小さい方の 1 つだけ使う (1 スロットに 1 つ)。
        /// </summary>
        TopPadZUp
    }

    /// <summary>
    /// モジュールのモデル (.stl / .obj、旧形式の .3mf) を読み込むランタイムローダー。Unity の取り込みを通さず生のファイルを読むため、
    /// エディタのセットアップで Modules/ (と Resources/AIDY/) の *.stl, *.obj, *.3mf を Resources/AIDY/*_stl.bytes, *_obj.bytes, *_3mf.bytes
    /// にコピーしたものを TextAsset として読む。座標系は STL (Z 上) が基準で、OBJ (Blender の既定の Y 上で書き出し) は Z 上へ戻す。
    /// 返すメッシュは取り付け面ローカル (単位 mm)。
    /// </summary>
    public static class AidyModelLoader
    {
        const float CreaseAngle = 40f;
        static readonly string[] Formats = { "stl", "obj", "3mf" };
        static readonly Dictionary<string, Mesh> cache = new Dictionary<string, Mesh>();

        public static Mesh Load(ModuleKind kind)
        {
            string path = ModuleCatalog.ModelPath(kind);
            return path != null ? Load(path, ModuleCatalog.Axis(kind)) : null;
        }

        /// <summary>basePath: 拡張子なしのパス ("AIDY/tail2")。*_stl → *_obj → *_3mf の順に探す。</summary>
        public static Mesh Load(string basePath, MountAxis axis)
        {
            if (cache.TryGetValue(basePath, out Mesh m)) return m;
            TextAsset asset = null;
            string format = null;
            foreach (string f in Formats)
            {
                asset = Resources.Load<TextAsset>(basePath + "_" + f);
                if (asset == null) continue;
                format = f;
                break;
            }
            if (asset == null) Debug.LogWarning("[AIDY] モデルが見つかりません: " + basePath + " (.stl / .obj)");
            else
            {
                try
                {
                    byte[] b = asset.bytes;
                    List<Vector3> tris = format == "obj" ? ParseObj(b) : format == "3mf" ? Parse3mf(b) : ParseStl(b);
                    m = Build(tris, axis, basePath);
                }
                catch (Exception e)
                {
                    Debug.LogWarning($"[AIDY] モデルの読込に失敗 {basePath}_{format}: {e.Message}");
                }
            }
            cache[basePath] = m;
            return m;
        }

        // ------------------------------------------------------------ 読み込み (三角形ごとに 3 頂点、単位 mm)

        static List<Vector3> ParseStl(byte[] b)
        {
            var v = new List<Vector3>();
            if (b.Length >= 84)
            {
                uint n = BitConverter.ToUInt32(b, 80);
                if (84 + 50L * n == b.Length)
                {
                    for (int i = 0; i < n; i++)
                    {
                        int o = 84 + 50 * i + 12;
                        for (int k = 0; k < 3; k++, o += 12)
                            v.Add(new Vector3(BitConverter.ToSingle(b, o), BitConverter.ToSingle(b, o + 4), BitConverter.ToSingle(b, o + 8)));
                    }
                    return ToMm(v);
                }
            }
            var ci = System.Globalization.CultureInfo.InvariantCulture;
            foreach (string raw in System.Text.Encoding.ASCII.GetString(b).Split('\n'))
            {
                string line = raw.Trim();
                if (!line.StartsWith("vertex")) continue;
                string[] p = line.Split((char[])null, StringSplitOptions.RemoveEmptyEntries);
                if (p.Length < 4) continue;
                v.Add(new Vector3(float.Parse(p[1], ci), float.Parse(p[2], ci), float.Parse(p[3], ci)));
            }
            return ToMm(v);
        }

        /// <summary>STL は単位を持たないので、全体が 1 未満ならメートルとみなして mm にする。</summary>
        static List<Vector3> ToMm(List<Vector3> v)
        {
            if (v.Count == 0) return v;
            Vector3 min = v[0], max = v[0];
            foreach (Vector3 p in v)
            {
                min = Vector3.Min(min, p);
                max = Vector3.Max(max, p);
            }
            Vector3 size = max - min;
            if (Mathf.Max(size.x, Mathf.Max(size.y, size.z)) >= 1f) return v;
            for (int i = 0; i < v.Count; i++) v[i] *= 1000f;
            return v;
        }

        /// <summary>
        /// OBJ (v と f だけ使う)。Blender の既定の書き出し (Y 上・-Z 前) を STL と同じ Z 上 (x, -z, y) に戻す。
        /// 多角形の面は耳切り法で三角形に分ける (凹んだ面もそのまま扱える)。
        /// </summary>
        static List<Vector3> ParseObj(byte[] b)
        {
            var ci = System.Globalization.CultureInfo.InvariantCulture;
            var verts = new List<Vector3>();
            var tris = new List<Vector3>();
            var poly = new List<Vector3>();
            foreach (string raw in System.Text.Encoding.UTF8.GetString(b).Split('\n'))
            {
                string line = raw.Trim();
                bool vertex = line.StartsWith("v "), face = line.StartsWith("f ");
                if (!vertex && !face) continue;
                string[] p = line.Split((char[])null, StringSplitOptions.RemoveEmptyEntries);
                if (vertex)
                {
                    if (p.Length >= 4) verts.Add(new Vector3(float.Parse(p[1], ci), -float.Parse(p[3], ci), float.Parse(p[2], ci)));
                    continue;
                }
                poly.Clear();
                for (int i = 1; i < p.Length; i++)
                {
                    int slash = p[i].IndexOf('/');
                    int idx = int.Parse(slash >= 0 ? p[i].Substring(0, slash) : p[i], ci);
                    poly.Add(verts[idx > 0 ? idx - 1 : verts.Count + idx]); // 負の番号は末尾からの相対
                }
                Triangulate(poly, tris);
            }
            return ToMm(tris);
        }

        /// <summary>平面の多角形を耳切り法で三角形に分けて output に足す (巻き順は元の多角形のまま)。</summary>
        static void Triangulate(List<Vector3> poly, List<Vector3> output)
        {
            int n = poly.Count;
            if (n < 3) return;
            if (n == 3)
            {
                output.AddRange(poly);
                return;
            }
            // Newell 法で面の法線を求め、その平面に投影した 2D で耳を探す
            Vector3 normal = Vector3.zero;
            for (int i = 0; i < n; i++)
            {
                Vector3 a = poly[i], c = poly[(i + 1) % n];
                normal += new Vector3((a.y - c.y) * (a.z + c.z), (a.z - c.z) * (a.x + c.x), (a.x - c.x) * (a.y + c.y));
            }
            normal = normal.sqrMagnitude > 0f ? normal.normalized : Vector3.forward;
            Vector3 u = Vector3.Cross(normal, Mathf.Abs(normal.x) < 0.9f ? Vector3.right : Vector3.up).normalized;
            Vector3 w = Vector3.Cross(normal, u);
            var p2 = new Vector2[n];
            for (int i = 0; i < n; i++) p2[i] = new Vector2(Vector3.Dot(poly[i], u), Vector3.Dot(poly[i], w));

            var rest = new List<int>(n);
            for (int i = 0; i < n; i++) rest.Add(i);
            while (rest.Count > 3)
            {
                bool clipped = false;
                for (int i = 0; i < rest.Count && !clipped; i++)
                {
                    int ia = rest[(i + rest.Count - 1) % rest.Count], ib = rest[i], ic = rest[(i + 1) % rest.Count];
                    if (Cross(p2[ia], p2[ib], p2[ic]) <= 0f) continue; // 凹んだ頂点は耳にならない
                    bool inside = false;
                    foreach (int j in rest)
                        if (j != ia && j != ib && j != ic && InTriangle(p2[j], p2[ia], p2[ib], p2[ic])) { inside = true; break; }
                    if (inside) continue;
                    output.Add(poly[ia]);
                    output.Add(poly[ib]);
                    output.Add(poly[ic]);
                    rest.RemoveAt(i);
                    clipped = true;
                }
                if (!clipped) break; // 退化した面: 残りは扇形で埋める
            }
            for (int i = 1; i + 1 < rest.Count; i++)
            {
                output.Add(poly[rest[0]]);
                output.Add(poly[rest[i]]);
                output.Add(poly[rest[i + 1]]);
            }
        }

        static float Cross(Vector2 a, Vector2 b, Vector2 c) => (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);

        static bool InTriangle(Vector2 p, Vector2 a, Vector2 b, Vector2 c) => Cross(a, b, p) >= 0f && Cross(b, c, p) >= 0f && Cross(c, a, p) >= 0f;

        static readonly System.Text.RegularExpressions.Regex VertexRe =new System.Text.RegularExpressions.Regex(
            "<vertex\\s+x=\"([^\"]+)\"\\s+y=\"([^\"]+)\"\\s+z=\"([^\"]+)\"");
        static readonly System.Text.RegularExpressions.Regex TriangleRe = new System.Text.RegularExpressions.Regex(
            "<triangle\\s+v1=\"(\\d+)\"\\s+v2=\"(\\d+)\"\\s+v3=\"(\\d+)\"");
        static readonly System.Text.RegularExpressions.Regex MeshRe = new System.Text.RegularExpressions.Regex(
            "<mesh>(.*?)</mesh>", System.Text.RegularExpressions.RegexOptions.Singleline);

        /// <summary>3MF (zip 内の 3D/3dmodel.model) の全オブジェクトのメッシュをまとめて読む。</summary>
        static List<Vector3> Parse3mf(byte[] b)
        {
            string xml;
            using (var zip = new System.IO.Compression.ZipArchive(new System.IO.MemoryStream(b)))
            {
                System.IO.Compression.ZipArchiveEntry entry = null;
                foreach (var e in zip.Entries)
                    if (e.FullName.EndsWith(".model", StringComparison.OrdinalIgnoreCase)) { entry = e; break; }
                if (entry == null) throw new Exception("3dmodel.model がありません");
                using (var r = new System.IO.StreamReader(entry.Open())) xml = r.ReadToEnd();
            }

            float unit = 1f;
            var um = System.Text.RegularExpressions.Regex.Match(xml, "unit=\"(\\w+)\"");
            if (um.Success)
            {
                switch (um.Groups[1].Value)
                {
                    case "micron": unit = 0.001f; break;
                    case "centimeter": unit = 10f; break;
                    case "inch": unit = 25.4f; break;
                    case "foot": unit = 304.8f; break;
                    case "meter": unit = 1000f; break;
                }
            }

            var ci = System.Globalization.CultureInfo.InvariantCulture;
            var tris = new List<Vector3>();
            foreach (System.Text.RegularExpressions.Match mesh in MeshRe.Matches(xml))
            {
                string body = mesh.Groups[1].Value;
                var verts = new List<Vector3>();
                foreach (System.Text.RegularExpressions.Match v in VertexRe.Matches(body))
                    verts.Add(new Vector3(float.Parse(v.Groups[1].Value, ci), float.Parse(v.Groups[2].Value, ci), float.Parse(v.Groups[3].Value, ci)) * unit);
                foreach (System.Text.RegularExpressions.Match t in TriangleRe.Matches(body))
                {
                    tris.Add(verts[int.Parse(t.Groups[1].Value)]);
                    tris.Add(verts[int.Parse(t.Groups[2].Value)]);
                    tris.Add(verts[int.Parse(t.Groups[3].Value)]);
                }
            }
            return tris;
        }

        // ------------------------------------------------------------ 取り付け面ローカルへの変換 + 法線

        static Mesh Build(List<Vector3> src, MountAxis axis, string name)
        {
            if (src.Count < 3) return null;
            Vector3 min = src[0], max = src[0];
            foreach (Vector3 p in src)
            {
                min = Vector3.Min(min, p);
                max = Vector3.Max(max, p);
            }

            if (axis == MountAxis.TopPadZUp)
            {
                src = FirstPart(src, min, max);
                min = max = src[0];
                foreach (Vector3 p in src)
                {
                    min = Vector3.Min(min, p);
                    max = Vector3.Max(max, p);
                }
            }
            Vector2 pocket;
            switch (axis)
            {
                case MountAxis.MagnetPlusX: pocket = FindPocket(src, min, max); break;
                case MountAxis.TopPadZUp: pocket = TopPad(src, max.z); break;
                case MountAxis.SlotTabPlusY: pocket = FaceCenter(src, 1, min.y); break;
                case MountAxis.EndPlusX: pocket = FaceCenter(src, 0, max.x); break;
                default: pocket = Vector2.zero; break;
            }
            int count = src.Count - src.Count % 3;
            var pos = new Vector3[count];
            for (int i = 0; i < count; i++)
            {
                Vector3 p = src[i];
                switch (axis)
                {
                    case MountAxis.ZUpFrontMinusY:
                        // 右手系 (Z上, -Y正面, +X=正面から見て右) → Unity (+X=正面から見て左, +Y=上, +Z=正面)
                        pos[i] = new Vector3(-p.x, p.z - min.z, -p.y);
                        break;
                    case MountAxis.OutwardMinusY:
                    {
                        float cx = (min.x + max.x) * 0.5f, cz = (min.z + max.z) * 0.5f;
                        pos[i] = new Vector3(-(p.x - cx), max.y - p.y, p.z - cz);
                        break;
                    }
                    case MountAxis.SlotTabPlusY:
                        // 右手系 (Z上) → Unity。台座の底面の中心を原点 (本体の表面) にする
                        pos[i] = new Vector3(-(p.x - pocket.x), p.y - min.y, p.z - pocket.y);
                        break;
                    case MountAxis.EndPlusX:
                        // +X 端の面の中心を原点に、-X 方向を外向き (+Y) へ
                        pos[i] = new Vector3(-(p.y - pocket.x), max.x - p.x, p.z - pocket.y);
                        break;
                    case MountAxis.TopPadZUp:
                        // 底面の取り付け面ローカル (+Y=下, +Z=正面, +X=正面から見て右) へ。上面の中心を原点にする
                        pos[i] = new Vector3(p.x - pocket.x, max.z - p.z, -(p.y - pocket.y));
                        break;
                    default:
                        // くぼみのある +X 面が本体側 (外向き = -X)、長手方向 +Y を正面へ
                        pos[i] = new Vector3(-(p.z - pocket.y), max.x - p.x, p.y - pocket.x);
                        break;
                }
            }

            // 外向きの面が表になるよう巻き順をそろえる (符号付き体積が負なら反転)
            double vol = 0;
            for (int i = 0; i < count; i += 3) vol += Vector3.Dot(pos[i], Vector3.Cross(pos[i + 1], pos[i + 2]));
            if (vol < 0)
                for (int i = 0; i < count; i += 3) (pos[i + 1], pos[i + 2]) = (pos[i + 2], pos[i + 1]);

            // 面法線を求め、同じ位置で角度が CreaseAngle 以内の面だけ平均してなめらかにする (角は立てたまま)
            int tris = count / 3;
            var faceN = new Vector3[tris];
            var shared = new Dictionary<Vector3Int, List<int>>();
            for (int t = 0; t < tris; t++)
            {
                Vector3 a = pos[t * 3], b = pos[t * 3 + 1], c = pos[t * 3 + 2];
                faceN[t] = Vector3.Cross(b - a, c - a); // 長さ = 面積の2倍 (面積で重み付け)
                for (int k = 0; k < 3; k++)
                {
                    Vector3Int key = Key(pos[t * 3 + k]);
                    if (!shared.TryGetValue(key, out List<int> list)) shared[key] = list = new List<int>();
                    list.Add(t);
                }
            }
            float cos = Mathf.Cos(CreaseAngle * Mathf.Deg2Rad);
            var normals = new Vector3[count];
            for (int t = 0; t < tris; t++)
            {
                Vector3 nt = faceN[t].normalized;
                for (int k = 0; k < 3; k++)
                {
                    Vector3 sum = Vector3.zero;
                    foreach (int o in shared[Key(pos[t * 3 + k])])
                        if (Vector3.Dot(faceN[o].normalized, nt) >= cos) sum += faceN[o];
                    normals[t * 3 + k] = sum.sqrMagnitude > 0f ? sum.normalized : nt;
                }
            }

            var idx = new int[count];
            for (int i = 0; i < count; i++) idx[i] = i;
            var mesh = new Mesh { name = System.IO.Path.GetFileName(name) };
            if (count > 65000) mesh.indexFormat = UnityEngine.Rendering.IndexFormat.UInt32;
            mesh.vertices = pos;
            mesh.normals = normals;
            mesh.triangles = idx;
            mesh.RecalculateBounds();
            return mesh;
        }

        /// <summary>X の真ん中に隙間があれば (左右 2 つ入りのモデル)、X が小さい側の三角形だけ返す。</summary>
        static List<Vector3> FirstPart(List<Vector3> src, Vector3 min, Vector3 max)
        {
            float mid = (min.x + max.x) * 0.5f;
            var part = new List<Vector3>();
            for (int i = 0; i + 2 < src.Count; i += 3)
            {
                float x = Mathf.Max(src[i].x, Mathf.Max(src[i + 1].x, src[i + 2].x));
                if (Mathf.Min(src[i].x, Mathf.Min(src[i + 1].x, src[i + 2].x)) < mid && x >= mid) return src; // 真ん中をまたぐ = 1 つのモデル
                if (x < mid) part.AddRange(new[] { src[i], src[i + 1], src[i + 2] });
            }
            return part.Count >= 3 ? part : src;
        }

        /// <summary>上面 (Z = top) の平らな面の中心 (x, y)。</summary>
        static Vector2 TopPad(List<Vector3> src, float top)
        {
            Vector2 lo = new Vector2(float.MaxValue, float.MaxValue), hi = -lo;
            foreach (Vector3 p in src)
            {
                if (p.z < top - 0.05f) continue;
                lo = Vector2.Min(lo, p);
                hi = Vector2.Max(hi, p);
            }
            return (lo + hi) * 0.5f;
        }

        /// <summary>axis (0=X, 1=Y) の座標が plane の面の中心。残りの 2 軸を (小さい軸, 大きい軸) の順で返す。</summary>
        static Vector2 FaceCenter(List<Vector3> src, int axis, float plane)
        {
            Vector2 lo = new Vector2(float.MaxValue, float.MaxValue), hi = -lo;
            foreach (Vector3 p in src)
            {
                if (Mathf.Abs(p[axis] - plane) > 0.3f) continue;
                var q = axis == 0 ? new Vector2(p.y, p.z) : new Vector2(p.x, p.z);
                lo = Vector2.Min(lo, q);
                hi = Vector2.Max(hi, q);
            }
            return lo.x <= hi.x ? (lo + hi) * 0.5f : Vector2.zero;
        }

        /// <summary>
        /// +X 面のくぼみ (面から 3〜6mm 奥の底面) の中心 (y, z)。見つからなければ全体の中心。
        /// </summary>
        static Vector2 FindPocket(List<Vector3> src, Vector3 min, Vector3 max)
        {
            float y0 = float.MaxValue, y1 = float.MinValue, z0 = float.MaxValue, z1 = float.MinValue;
            foreach (Vector3 p in src)
            {
                if (p.x < max.x - 6f || p.x > max.x - 3f || p.z < min.z + 3f || p.z > max.z - 3f) continue;
                y0 = Mathf.Min(y0, p.y);
                y1 = Mathf.Max(y1, p.y);
                z0 = Mathf.Min(z0, p.z);
                z1 = Mathf.Max(z1, p.z);
            }
            if (y0 > y1) return new Vector2((min.y + max.y) * 0.5f, (min.z + max.z) * 0.5f);
            return new Vector2((y0 + y1) * 0.5f, (z0 + z1) * 0.5f);
        }

        static Vector3Int Key(Vector3 p) => new Vector3Int(Mathf.RoundToInt(p.x * 1000f), Mathf.RoundToInt(p.y * 1000f), Mathf.RoundToInt(p.z * 1000f));
    }
}
