using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using UnityEngine;

namespace Aidy
{
    /// <summary>
    /// AR に使う ArUco マーカー (DICT_4X4_50)。ID0 = いまの着せ替え、ID1 = 名刺 (標準構成)、
    /// ID2〜 = アプリで作った着せ替えマーカー (AidyMarkerStore)。印刷用の ID0 / ID1 は docs/markers/ にもある。
    /// 寸法は黒い正方形 (外枠を含む) の一辺。印刷した大きさと違うと、モデルの距離・大きさがずれる。
    /// </summary>
    public static class AidyMarkers
    {
        /// <summary>アプリで着せ替えたいまの Aidy を出すマーカー。</summary>
        public const int MyId = 0;
        /// <summary>名刺などで配るマーカー。標準構成の Aidy を出す。</summary>
        public const int CardId = 1;

        public const float MySizeM = 0.08f;
        public const float CardSizeM = 0.03f;

        /// <summary>いま認識する ID (固定の 2 つ + 作った着せ替えマーカー)。</summary>
        public static List<int> ActiveIds()
        {
            var ids = new List<int> { MyId, CardId };
            ids.AddRange(AidyMarkerStore.Ids);
            return ids;
        }

        public static float Size(int id) => id == CardId ? CardSizeM : MySizeM;
        public static int SizeMm(int id) => Mathf.RoundToInt(Size(id) * 1000f);

        public static string Name(int id)
        {
            if (id == MyId) return "あなたのAidy";
            if (id == CardId) return "名刺のAidy (標準)";
            return "着せ替えAidy #" + id;
        }

        /// <summary>そのマーカーで出す構成。null = いまの着せ替え (ModuleConfig)。</summary>
        public static IReadOnlyDictionary<string, ModuleKind> Config(int id)
        {
            if (id == MyId) return null;
            if (id == CardId) return ModuleConfig.Default;
            return AidyMarkerStore.Get(id);
        }

        /// <summary>構成の一覧表示 (耳×2・尻尾…)。</summary>
        public static string Summary(IReadOnlyDictionary<string, ModuleKind> slots)
        {
            var counts = new Dictionary<ModuleKind, int>();
            foreach (ModuleKind k in slots.Values)
            {
                counts.TryGetValue(k, out int n);
                counts[k] = n + 1;
            }
            var parts = new List<string>();
            foreach (ModuleKind k in ModuleCatalog.All)
                if (counts.TryGetValue(k, out int n)) parts.Add(n > 1 ? $"{ModuleCatalog.Name(k)}×{n}" : ModuleCatalog.Name(k));
            return parts.Count > 0 ? string.Join("・", parts) : "なし (本体だけ)";
        }
    }

    /// <summary>
    /// アプリで作った着せ替えマーカー (ID → そのときのモジュール構成)。端末に保存される。
    /// マーカーと構成の対応はこの端末にしかないので、ほかの端末でかざしても出ない。
    /// </summary>
    public static class AidyMarkerStore
    {
        const string PrefKey = "aidy.markers.v1";
        const int FirstId = 2;
        static SortedDictionary<int, Dictionary<string, ModuleKind>> saved;

        /// <summary>作った・消したとき。</summary>
        public static event Action Changed;

        public static IEnumerable<int> Ids
        {
            get
            {
                Load();
                return new List<int>(saved.Keys);
            }
        }

        public static IReadOnlyDictionary<string, ModuleKind> Get(int id)
        {
            Load();
            return saved.TryGetValue(id, out var d) ? d : null;
        }

        /// <summary>同じ構成のマーカーがあればその ID。</summary>
        public static int? Find(IReadOnlyDictionary<string, ModuleKind> slots)
        {
            Load();
            foreach (var kv in saved)
            {
                if (kv.Value.Count != slots.Count) continue;
                bool same = true;
                foreach (var s in slots)
                    if (!kv.Value.TryGetValue(s.Key, out ModuleKind k) || k != s.Value) { same = false; break; }
                if (same) return kv.Key;
            }
            return null;
        }

        /// <summary>いまの構成を空いている ID に保存する。空きがなければ null。</summary>
        public static int? Create(IReadOnlyDictionary<string, ModuleKind> slots)
        {
            Load();
            for (int id = FirstId; id < ArucoDetector.Dict4x4_50.Length; id++)
            {
                if (saved.ContainsKey(id)) continue;
                saved[id] = new Dictionary<string, ModuleKind>(slots);
                Save();
                Changed?.Invoke();
                return id;
            }
            return null;
        }

        public static void Delete(int id)
        {
            Load();
            if (!saved.Remove(id)) return;
            Save();
            Changed?.Invoke();
        }

        // 形式: "2=top_1:Ear,back_1:Tail;3=..."
        static void Load()
        {
            if (saved != null) return;
            saved = new SortedDictionary<int, Dictionary<string, ModuleKind>>();
            string text = PlayerPrefs.GetString(PrefKey, "");
            foreach (string entry in text.Split(new[] { ';' }, StringSplitOptions.RemoveEmptyEntries))
            {
                string[] head = entry.Split('=');
                if (head.Length != 2 || !int.TryParse(head[0], out int id)) continue;
                var slots = new Dictionary<string, ModuleKind>();
                foreach (string pair in head[1].Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries))
                {
                    string[] kv = pair.Split(':');
                    if (kv.Length == 2 && Enum.TryParse(kv[1], out ModuleKind k) && BodyLayout.Get(kv[0]) != null) slots[kv[0]] = k;
                }
                saved[id] = slots;
            }
        }

        static void Save()
        {
            var entries = new List<string>();
            foreach (var kv in saved)
            {
                var pairs = new List<string>();
                foreach (var s in kv.Value) pairs.Add(s.Key + ":" + s.Value);
                entries.Add(kv.Key + "=" + string.Join(",", pairs));
            }
            PlayerPrefs.SetString(PrefKey, string.Join(";", entries));
            PlayerPrefs.Save();
        }
    }

    /// <summary>マーカーの画像を作る・保存する。</summary>
    public static class AidyMarkerImage
    {
        const int PrintDpi = 300;

        /// <summary>白い余白 (1 マス) 付きのマーカー画像。cellPx = 1 マスの画素数。</summary>
        public static Texture2D Create(int id, int cellPx)
        {
            int n = 8 * cellPx; // 余白 1 + 黒枠 1 + 4 ビット + 黒枠 1 + 余白 1
            var tex = new Texture2D(n, n, TextureFormat.RGBA32, false) { filterMode = FilterMode.Point, wrapMode = TextureWrapMode.Clamp, name = "Marker_" + id };
            var px = new Color32[n * n];
            int code = ArucoDetector.Dict4x4_50[id];
            for (int y = 0; y < n; y++)
            {
                int row = 7 - y / cellPx; // テクスチャは下の行から
                for (int x = 0; x < n; x++)
                {
                    int col = x / cellPx;
                    bool white;
                    if (row == 0 || row == 7 || col == 0 || col == 7) white = true;              // 余白
                    else if (row == 1 || row == 6 || col == 1 || col == 6) white = false;        // 黒枠
                    else white = (code >> (15 - ((row - 2) * 4 + (col - 2))) & 1) == 1;         // 1 = 白
                    byte v = white ? (byte)255 : (byte)0;
                    px[y * n + x] = new Color32(v, v, v, 255);
                }
            }
            tex.SetPixels32(px);
            tex.Apply();
            return tex;
        }

        /// <summary>
        /// 印刷用 PNG を保存し、保存先を返す。黒い正方形が AidyMarkers.Size の大きさになるよう 300dpi で作り、PNG に解像度を書き込む。
        /// Android はギャラリー (Pictures/AIDY)、それ以外はエディタならプロジェクトの Markers/、アプリならデータフォルダ。
        /// </summary>
        public static string SavePng(int id)
        {
            int squarePx = Mathf.RoundToInt(AidyMarkers.SizeMm(id) / 25.4f * PrintDpi);
            Texture2D tex = Create(id, Mathf.Max(8, squarePx / 6));
            byte[] png = WithDpi(tex.EncodeToPNG(), PrintDpi);
            UnityEngine.Object.Destroy(tex);
            string file = $"aidy_marker_id{id}_{AidyMarkers.SizeMm(id)}mm.png";
#if UNITY_ANDROID && !UNITY_EDITOR
            string gallery = SaveToGallery(file, png);
            if (gallery != null) return gallery;
#endif
#if UNITY_EDITOR
            string dir = Path.GetFullPath(Path.Combine(Application.dataPath, "..", "Markers"));
#else
            string dir = Path.Combine(Application.persistentDataPath, "Markers");
#endif
            Directory.CreateDirectory(dir);
            string path = Path.Combine(dir, file);
            File.WriteAllBytes(path, png);
            return path;
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        /// <summary>Android 10 以降: MediaStore で Pictures/AIDY に保存 (権限不要)。それより前は null (アプリのフォルダに保存)。</summary>
        static string SaveToGallery(string file, byte[] png)
        {
            try
            {
                using (var version = new AndroidJavaClass("android.os.Build$VERSION"))
                    if (version.GetStatic<int>("SDK_INT") < 29) return null;
                using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var resolver = activity.Call<AndroidJavaObject>("getContentResolver"))
                using (var media = new AndroidJavaClass("android.provider.MediaStore$Images$Media"))
                using (var values = new AndroidJavaObject("android.content.ContentValues"))
                {
                    values.Call("put", "_display_name", file);
                    values.Call("put", "mime_type", "image/png");
                    values.Call("put", "relative_path", "Pictures/AIDY");
                    using (var collection = media.GetStatic<AndroidJavaObject>("EXTERNAL_CONTENT_URI"))
                    using (var uri = resolver.Call<AndroidJavaObject>("insert", collection, values))
                    {
                        if (uri == null) return null;
                        using (var stream = resolver.Call<AndroidJavaObject>("openOutputStream", uri))
                        {
                            var data = new sbyte[png.Length];
                            Buffer.BlockCopy(png, 0, data, 0, png.Length);
                            stream.Call("write", data);
                            stream.Call("close");
                        }
                    }
                }
                return "ギャラリー (Pictures/AIDY/" + file + ")";
            }
            catch (Exception e)
            {
                Debug.LogWarning("[AIDY] ギャラリーへの保存に失敗: " + e.Message);
                return null;
            }
        }
#endif

        /// <summary>PNG の IHDR の後ろに pHYs (解像度) を足す。印刷ソフトがこの dpi で原寸に印刷する。</summary>
        static byte[] WithDpi(byte[] png, int dpi)
        {
            const int IhdrEnd = 8 + 4 + 4 + 13 + 4; // シグネチャ + IHDR (長さ・種類・データ・CRC)
            if (png.Length < IhdrEnd) return png;
            uint ppm = (uint)Mathf.RoundToInt(dpi / 0.0254f);
            var chunk = new byte[4 + 4 + 9 + 4];
            WriteU32(chunk, 0, 9);
            chunk[4] = (byte)'p'; chunk[5] = (byte)'H'; chunk[6] = (byte)'Y'; chunk[7] = (byte)'s';
            WriteU32(chunk, 8, ppm);
            WriteU32(chunk, 12, ppm);
            chunk[16] = 1; // 単位: メートル
            WriteU32(chunk, 17, Crc(chunk, 4, 13));
            var outBytes = new byte[png.Length + chunk.Length];
            Array.Copy(png, outBytes, IhdrEnd);
            Array.Copy(chunk, 0, outBytes, IhdrEnd, chunk.Length);
            Array.Copy(png, IhdrEnd, outBytes, IhdrEnd + chunk.Length, png.Length - IhdrEnd);
            return outBytes;
        }

        static void WriteU32(byte[] b, int o, uint v)
        {
            b[o] = (byte)(v >> 24);
            b[o + 1] = (byte)(v >> 16);
            b[o + 2] = (byte)(v >> 8);
            b[o + 3] = (byte)v;
        }

        static uint Crc(byte[] b, int offset, int count)
        {
            uint c = 0xFFFFFFFF;
            for (int i = offset; i < offset + count; i++)
            {
                c ^= b[i];
                for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
            }
            return c ^ 0xFFFFFFFF;
        }
    }

    /// <summary>マーカーの姿勢 (カメラ座標、または AR のワールド座標)。モデルを立たせる向き: 上 = マーカーの面の法線、正面 = マーカーの下辺の方。</summary>
    public struct MarkerSighting
    {
        public int Id;
        public Vector3 Position;
        public Quaternion Rotation;
    }

    /// <summary>
    /// カメラ画像からマーカーを探す。検出は別スレッドで行い、終わったら次の画像を受け付ける。
    /// 画像は撮像素子の向き (横長) のまま渡し、画面に対する向き (imageRight / imageDown: 画像の右・下が画面上のどちら向きか)
    /// で Unity のカメラ座標 (x 右, y 上, z 前) に直す。
    /// </summary>
    public class AidyMarkerTracker
    {
        readonly ArucoDetector detector = new ArucoDetector();
        Task<List<ArucoMarker>> task;
        byte[] buffer;
        int width, height;
        double fx, fy, cx, cy;
        Vector2 right, down;
        Matrix4x4 camToWorld;

        public bool Busy => task != null && !task.IsCompleted;

        /// <summary>輝度画像を書き込むバッファ (Submit まで使える)。</summary>
        public byte[] Buffer(int w, int h)
        {
            if (buffer == null || buffer.Length != w * h) buffer = new byte[w * h];
            return buffer;
        }

        /// <summary>
        /// Buffer に書いた画像の検出を始める。f/c は画像の画素単位の内部パラメータ。
        /// imageRight / imageDown: 画像の +x / +y が画面 (x 右, y 下) のどちら向きか。camToWorld: 撮影時のカメラの姿勢。
        /// </summary>
        public void Submit(int w, int h, double fx, double fy, double cx, double cy, Vector2 imageRight, Vector2 imageDown, Matrix4x4 camToWorld)
        {
            if (Busy) return;
            width = w;
            height = h;
            this.fx = fx;
            this.fy = fy;
            this.cx = cx;
            this.cy = cy;
            right = imageRight;
            down = imageDown;
            this.camToWorld = camToWorld;
            byte[] img = buffer;
            var ids = new HashSet<int>(AidyMarkers.ActiveIds());
            task = Task.Run(() => detector.Detect(img, w, h, ids));
        }

        /// <summary>検出が終わっていれば結果を results に入れて true。</summary>
        public bool TryCollect(List<MarkerSighting> results)
        {
            results.Clear();
            if (task == null || !task.IsCompleted) return false;
            var t = task;
            task = null;
            if (t.IsFaulted)
            {
                Debug.LogWarning("[AIDY] マーカー検出に失敗: " + t.Exception?.GetBaseException().Message);
                return true;
            }
            foreach (ArucoMarker m in t.Result)
            {
                if (!ArucoPose.Solve(m.Corners, AidyMarkers.Size(m.Id), fx, fy, cx, cy, out double[] R, out double[] tv)) continue;
                // OpenCV のカメラ座標 (画像の x 右, y 下, z 前) → Unity のカメラ座標
                Vector3 pos = ToUnity(tv[0], tv[1], tv[2]);
                Vector3 axisY = ToUnity(R[1], R[4], R[7]);
                Vector3 axisZ = ToUnity(R[2], R[5], R[8]);
                Quaternion rot = Quaternion.LookRotation(-axisY, axisZ);
                results.Add(new MarkerSighting
                {
                    Id = m.Id,
                    Position = camToWorld.MultiplyPoint3x4(pos),
                    Rotation = camToWorld.rotation * rot
                });
            }
            return true;
        }

        Vector3 ToUnity(double x, double y, double z)
        {
            float sx = (float)(x * right.x + y * down.x);
            float sy = (float)(x * right.y + y * down.y);
            return new Vector3(sx, -sy, (float)z);
        }

        /// <summary>画面を時計回りに deg 度回して表示している画像の、画像の右・下の向き (画面座標、y 下)。</summary>
        public static void RotatedAxes(int deg, out Vector2 imageRight, out Vector2 imageDown)
        {
            float a = deg * Mathf.Deg2Rad;
            imageRight = Snap(new Vector2(Mathf.Cos(a), Mathf.Sin(a)));
            imageDown = Snap(new Vector2(-Mathf.Sin(a), Mathf.Cos(a)));
        }

        /// <summary>
        /// ARCore の表示変換 (画面の正規化座標 (左上原点) → カメラ画像の正規化座標) から、画像の右・下の向きを求める。
        /// 変換の 2x2 部分の逆行列の列が、画像の各軸が画面上で向く方向。
        /// </summary>
        public static void AxesFromDisplayMatrix(Matrix4x4 m, out Vector2 imageRight, out Vector2 imageDown)
        {
            float det = m.m00 * m.m11 - m.m01 * m.m10;
            if (Mathf.Abs(det) < 1e-6f)
            {
                RotatedAxes(90, out imageRight, out imageDown);
                return;
            }
            imageRight = Snap(new Vector2(m.m11, -m.m10) / det);
            imageDown = Snap(new Vector2(-m.m01, m.m00) / det);
        }

        static Vector2 Snap(Vector2 v) => Mathf.Abs(v.x) >= Mathf.Abs(v.y) ? new Vector2(Mathf.Sign(v.x), 0) : new Vector2(0, Mathf.Sign(v.y));
    }
}
