using System;
using System.Collections.Generic;
using UnityEngine;

namespace Aidy
{
    /// <summary>
    /// 本体の顔モニター (LCD 480x320) に映る顔。本体の faces.py (Pillow で描く版) を移したもの。
    /// 黒い画面に白い目を 2 つ描き、表情ごとの目の形 (FACES)・瞬き・きょろきょろ・動き (fx) も同じにする。
    /// 顔モジュールを付けた本体 (本体v2_顔あり) のモニター開口の奥 (顔パネルの面) に貼る。
    /// </summary>
    public class AidyFaceScreen : MonoBehaviour
    {
        // ------------------------------------------------------------ faces.py の値 (画面は 480x320、y は下向き)

        const float W = 480f, H = 320f;
        static readonly float[] EyeX = { 146f, 334f };
        const float EyeY = 160f;
        static readonly Color EyeColor = Color.white;
        static readonly Color Cyan = new Color32(127, 224, 255, 255);
        static readonly Color Bg = Color.black;
        static readonly Vector2 BlinkDefault = new Vector2(5f, 11f);

        /// <summary>目の形。BASE からの変更を表情ごとに持つ。</summary>
        struct Shape
        {
            public float w, h, dx, dy, top, tilt, low, dim, tint;

            public static Shape Base => new Shape { w = 50f, h = 140f };

            public static Shape Lerp(Shape a, Shape b, float k, out bool moving)
            {
                moving = false;
                return new Shape
                {
                    w = Step(a.w, b.w, k, 0.3f, ref moving),
                    h = Step(a.h, b.h, k, 0.3f, ref moving),
                    dx = Step(a.dx, b.dx, k, 0.3f, ref moving),
                    dy = Step(a.dy, b.dy, k, 0.3f, ref moving),
                    tilt = Step(a.tilt, b.tilt, k, 0.3f, ref moving),
                    top = Step(a.top, b.top, k, 0.01f, ref moving),
                    low = Step(a.low, b.low, k, 0.01f, ref moving),
                    dim = Step(a.dim, b.dim, k, 0.01f, ref moving),
                    tint = Step(a.tint, b.tint, k, 0.01f, ref moving),
                };
            }

            static float Step(float c, float v, float k, float snap, ref bool moving)
            {
                float n = c + (v - c) * k;
                if (Mathf.Abs(n - v) < snap) n = v;
                moving |= n != c;
                return n;
            }
        }

        class FaceDef
        {
            public string Label;
            public Shape Shape = Shape.Base;
            public string Fx;
            public Vector2? Blink = BlinkDefault; // null = 瞬きしない
        }

        static readonly Dictionary<string, FaceDef> Faces = new Dictionary<string, FaceDef>
        {
            { "normal", new FaceDef { Label = "ふつう" } },
            { "happy", new FaceDef { Label = "うれしい", Shape = new Shape { w = 54, h = 100, dy = -10, low = 0.32f }, Fx = "bounce" } },
            { "sad", new FaceDef { Label = "かなしい", Shape = new Shape { w = 50, h = 112, dy = 16, top = 0.2f, tilt = -18 } } },
            { "angry", new FaceDef { Label = "おこる", Shape = new Shape { w = 56, h = 122, dy = 4, top = 0.3f, tilt = 24 } } },
            { "sleepy", new FaceDef { Label = "ねむい", Shape = new Shape { w = 50, h = 74, dy = 22, top = 0.38f }, Blink = new Vector2(3f, 6f) } },
            { "sleep", new FaceDef { Label = "ねてる", Shape = new Shape { w = 64, h = 9, dy = 22 }, Fx = "zz", Blink = null } },
            { "dazed", new FaceDef { Label = "ぼーっと", Shape = new Shape { w = 34, h = 36, dy = 12 }, Fx = "drift" } },
            { "lowbatt", new FaceDef { Label = "電池少ない", Shape = new Shape { w = 44, h = 100, dy = -26, top = 0.2f, tilt = -14, dim = 0.4f }, Fx = "battlow" } },
            { "scared", new FaceDef { Label = "おびえる", Shape = new Shape { w = 40, h = 104, dy = 8, top = 0.1f, tilt = -16 }, Fx = "shake" } },
            { "listen", new FaceDef { Label = "聞いている", Shape = new Shape { w = 54, h = 146, tint = 0.85f }, Fx = "level" } },
            { "speak", new FaceDef { Label = "話している", Shape = new Shape { w = 52, h = 124 }, Fx = "level" } },
        };

        /// <summary>前の名前や使わなくなった表情は近いものにする (faces.py の ALIAS)。</summary>
        static readonly Dictionary<string, string> Alias = new Dictionary<string, string>
        {
            { "love", "happy" }, { "laugh", "happy" }, { "smug", "happy" }, { "wink", "happy" }, { "surprised", "normal" },
            { "confused", "normal" }, { "tilt", "normal" }, { "noticed", "normal" }, { "lookaround", "normal" }, { "waiting", "normal" },
            { "wake", "normal" }, { "stare", "normal" }, { "nod", "normal" }, { "doubletake", "normal" }, { "lookup", "normal" },
            { "lookdown", "normal" }, { "cry", "sad" }, { "shy", "normal" }, { "suspicious", "angry" }, { "dizzy", "dazed" },
            { "charging", "normal" }, { "error", "sad" }, { "attach", "normal" }, { "fall", "scared" }, { "hot", "dazed" }, { "cold", "scared" },
        };

        /// <summary>アプリの表情 (デフォルメ Aidy と共通) → 本体の表情の名前。</summary>
        public static string FromApp(AidyFace? face)
        {
            switch (face)
            {
                case AidyFace.Happy: return "happy";
                case AidyFace.Sleepy: return "sleepy";
                case AidyFace.Dizzy: return "dazed";
                case AidyFace.Surprised: return Alias["surprised"];
                default: return "normal";
            }
        }

        public static IEnumerable<string> Names => Faces.Keys;
        public static string Label(string name) => Faces.TryGetValue(name, out FaceDef f) ? f.Label : name;

        // ------------------------------------------------------------ 状態 (faces.py の Renderer)

        const int TexW = 240, TexH = 160;      // 480x320 の半分で描く
        const float Tick = 0.05f;              // lcd.py の 20fps と同じ間隔で目の形をなめらかに変える

        public string Face { get; private set; } = "normal";
        /// <summary>画面の絵 (240x160、検証用)。</summary>
        public Texture2D Texture => tex;
        /// <summary>声の大きさ 0〜1 (聞いている・話している間、目が少し動く)。</summary>
        public float Level;

        Shape cur = Shape.Base, target = Shape.Base;
        float lookX, lookY, lookT = -99f, gx, gy, lv;
        Vector3 glance;                        // (x, y, 終わる時刻)
        float nextGlance, tFace, nextBlink, blinkT = -1f, tickAcc;
        bool doubleBlink, dirty = true;
        string lastSig;

        Texture2D tex;
        Color32[] px;
        Renderer screenRenderer;

        /// <summary>
        /// parent (本体の中心・一辺 bodySize) のモニター開口の奥に画面を作る。寸法は body_v2_layout.json の monitor
        /// (74.8x51mm、顔パネルは正面から 5mm 奥)。
        /// </summary>
        public static AidyFaceScreen Create(Transform parent, float bodySize, int layer)
        {
            BodySlot monitor = BodyLayout.Get("monitor");
            float mm = bodySize / 150f;
            float w = (monitor != null ? monitor.WidthMm : 74.8f) * mm;
            float h = (monitor != null ? monitor.HeightMm : 51f) * mm;
            const float PanelDepthMm = 5f; // 外装の厚み
            Vector3 c = monitor != null ? monitor.Position * bodySize - monitor.Normal * (PanelDepthMm * mm) : new Vector3(0, 12.5f * mm, 70f * mm);
            var go = AidyMeshes.Create("FaceScreen", parent, AidyMeshes.Plane(w, h), AidyMaterials.Basic(Color.black));
            go.transform.localPosition = c + new Vector3(0, 0, 0.3f * mm); // 顔パネル (白) の少し手前
            go.layer = layer;
            var r = go.GetComponent<MeshRenderer>();
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            r.receiveShadows = false;
            return go.AddComponent<AidyFaceScreen>();
        }

        void Awake()
        {
            tex = new Texture2D(TexW, TexH, TextureFormat.RGBA32, false)
            {
                wrapMode = TextureWrapMode.Clamp,
                filterMode = FilterMode.Bilinear,
                hideFlags = HideFlags.DontSave
            };
            px = new Color32[TexW * TexH];
            screenRenderer = GetComponent<Renderer>();
            screenRenderer.sharedMaterial = AidyMaterials.Textured(tex);
            float now = Time.time;
            nextGlance = now + 6f;
            nextBlink = now + UnityEngine.Random.Range(1f, 3f); // 並んだ本体どうしで瞬きがそろわないように
            tFace = now;
            Draw(now);
        }

        void OnDestroy()
        {
            if (tex != null) Destroy(tex);
        }

        /// <summary>表情を変える (名前は faces.py の FACES。ALIAS の名前も使える)。</summary>
        public void SetFace(string name)
        {
            name = (name ?? "normal").Trim();
            if (name == "thinking") return; // 本体でも考え中は表情を変えない
            if (Alias.TryGetValue(name, out string a)) name = a;
            if (!Faces.ContainsKey(name)) name = "normal";
            if (name == Face) return;
            Face = name;
            tFace = Time.time;
            target = Faces[name].Shape;
            if (!screenRenderer.isVisible) cur = target; // 映っていない間はなめらかに変えず、すぐその形にする
            dirty = true;
        }

        /// <summary>その方向を見る (x: -60〜60, y: -30〜30)。3 秒たつと前に戻る。</summary>
        public void Look(float x, float y = 0f)
        {
            lookX = x;
            lookY = y;
            lookT = Time.time;
        }

        /// <summary>すぐに瞬きする。</summary>
        public void Blink()
        {
            if (Faces[Face].Blink == null) return;
            blinkT = Time.time;
            doubleBlink = false;
        }

        void Update()
        {
            float now = Time.time;
            tickAcc += Time.deltaTime;
            if (tickAcc < Tick) return;
            tickAcc = Mathf.Min(tickAcc - Tick, Tick);
            if (!screenRenderer.isVisible && !dirty) return; // 映っていない本体 (ほかのページ) は描かない
            Draw(now);
        }

        // ------------------------------------------------------------ 描く

        float BlinkK(float now)
        {
            Vector2? rng = Faces[Face].Blink;
            if (rng == null) return 1f;
            if (blinkT < 0f && now >= nextBlink)
            {
                blinkT = now;
                doubleBlink = UnityEngine.Random.value < 0.15f;
            }
            if (blinkT < 0f) return 1f;
            float x = (now - blinkT) / 0.16f;
            if (x >= 1f)
            {
                if (doubleBlink)
                {
                    doubleBlink = false;
                    blinkT = now + 0.12f;
                }
                else
                {
                    blinkT = -1f;
                    nextBlink = now + UnityEngine.Random.Range(rng.Value.x, rng.Value.y);
                }
                return 1f;
            }
            return x < 0f ? 1f : Mathf.Max(0.08f, Mathf.Abs(1f - 2f * x));
        }

        void Draw(float now)
        {
            FaceDef def = Faces[Face];
            string fx = def.Fx;
            cur = Shape.Lerp(cur, target, 0.35f, out _);
            Shape p = cur;

            // 目線: 指示があればそれ、なければふつうの顔のときだけ、ときどき自分で少し見る
            float tx = 0f, ty = 0f;
            if (now - lookT > 3f)
            {
                if (Face == "normal")
                {
                    if (now > nextGlance)
                    {
                        glance = new Vector3(new[] { -16f, 16f, 0f }[UnityEngine.Random.Range(0, 3)], 0f, now + 1.2f);
                        nextGlance = now + UnityEngine.Random.Range(5f, 10f);
                    }
                    if (now < glance.z)
                    {
                        tx = glance.x;
                        ty = glance.y;
                    }
                }
            }
            else
            {
                tx = lookX;
                ty = lookY;
            }
            gx = Mathf.Abs(gx + (tx - gx) * 0.3f - tx) < 0.5f ? tx : gx + (tx - gx) * 0.3f;
            gy = Mathf.Abs(gy + (ty - gy) * 0.3f - ty) < 0.5f ? ty : gy + (ty - gy) * 0.3f;
            lv += (Mathf.Clamp01(Level) - lv) * 0.5f;

            // 動き
            float ox = 0f, oy = 0f, tf = now - tFace;
            switch (fx)
            {
                case "shake":
                    ox = UnityEngine.Random.Range(-2f, 2f);
                    oy = UnityEngine.Random.Range(-1.5f, 1.5f);
                    break;
                case "drift":
                    ox = Mathf.Sin(now * 0.7f) * 12f;
                    oy = Mathf.Sin(now * 0.45f) * 6f;
                    break;
                case "bounce":
                    if (tf < 0.6f) oy = -Mathf.Sin(tf / 0.6f * Mathf.PI) * 8f;
                    break;
                case "level":
                    p.h *= 1f + 0.06f * lv;
                    break;
            }
            p.h *= BlinkK(now);

            int anim = fx == "zz" || fx == "battlow" ? (int)(now * 12f) : 0;
            string sig = string.Format("{0}|{1:F1}|{2:F1}|{3:F1}|{4:F1}|{5:F1}|{6:F3}|{7:F1}|{8:F3}|{9:F2}|{10:F2}|{11}",
                Face, p.w, p.h, p.dy + oy, ox + gx, gy, p.top, p.tilt, p.low, p.tint, p.dim, anim);
            if (sig == lastSig && !dirty) return;
            lastSig = sig;
            dirty = false;

            Color32 bg = Bg;
            for (int i = 0; i < px.Length; i++) px[i] = bg;

            Color col = Color.Lerp(EyeColor, Cyan, p.tint) * (1f - p.dim);
            col.a = 1f;
            float bx = ox + gx + p.dx, by = oy + gy * 0.8f + p.dy;
            for (int side = 0; side < 2; side++) DrawEye(p, side, EyeX[side] + bx, EyeY + by, col);

            if (fx == "zz")
            {
                for (int i = 0; i < 3; i++)
                {
                    float ph = (now / 2.4f + i / 3f) % 1f;
                    float c = 200f * (1f - ph) / 255f;
                    DrawZ(372f + i * 16f + ph * 10f, 118f - i * 22f - ph * 26f, 14f + i * 4f, new Color(c, c, c));
                }
            }
            if (fx == "battlow" && (int)(now * 1.6f) % 2 == 0) DrawBattery(240f, 272f, new Color32(255, 91, 91, 255), 1, 0.9f);

            tex.SetPixels32(px);
            tex.Apply(false);
        }

        /// <summary>
        /// 1 つの目 (角の丸い縦長の四角) を描く。上まぶたは斜めに切り (tilt&gt;0 は内側が下がる = おこる)、
        /// 下まぶたは目の下を平らに持ち上げる。
        /// </summary>
        void DrawEye(Shape p, int side, float cx, float cy, Color col)
        {
            float w = Mathf.Max(2f, p.w), h = Mathf.Max(2f, p.h);
            float hw = w * 0.5f, hh = h * 0.5f, r = Mathf.Min(w, h) * 0.5f;
            float y0 = cy - hh, y1 = cy + hh;
            bool lid = p.top > 0.001f || Mathf.Abs(p.tilt) > 0.5f;
            float lidBase = y0 + p.top * h;
            float k = Mathf.Tan(p.tilt * Mathf.Deg2Rad) * (side == 0 ? 1f : -1f); // 左目は右が内側
            float lowY = y1 - p.low * h;
            Fill(cx - hw - 2f, cy - hh - 2f, cx + hw + 2f, cy + hh + 2f, col, (x, y) =>
            {
                float d = RoundBox(x - cx, y - cy, hw, hh, r);
                if (lid) d = Mathf.Max(d, lidBase + k * (x - cx) - y);
                if (p.low > 0.01f) d = Mathf.Max(d, y - lowY);
                return d;
            });
        }

        /// <summary>Z の字 (線で描く。ねてる)。</summary>
        void DrawZ(float x, float y, float s, Color col)
        {
            float lw = Mathf.Max(2f, s / 6f) * 0.5f;
            var a = new Vector2(x, y);
            var b = new Vector2(x + s, y);
            var c = new Vector2(x, y + s);
            var e = new Vector2(x + s, y + s);
            Fill(x - lw - 2f, y - lw - 2f, x + s + lw + 2f, y + s + lw + 2f, col, (px_, py_) =>
            {
                var q = new Vector2(px_, py_);
                return Mathf.Min(Segment(q, a, b), Mathf.Min(Segment(q, b, c), Segment(q, c, e))) - lw;
            });
        }

        /// <summary>電池のアイコン (枠・出っ張り・残量の棒)。電池少ない。</summary>
        void DrawBattery(float cx, float cy, Color col, int level, float s)
        {
            float w = 74f * s, h = 38f * s, x0 = cx - w * 0.5f, y0 = cy - h * 0.5f;
            float lw = Mathf.Max(2f, (int)(4f * s));
            Fill(x0 - 2f, y0 - 2f, x0 + w + 9f * s, y0 + h + 2f, col, (x, y) =>
            {
                // 枠 (内側に lw の太さ)
                float box = RoundBox(x - cx, y - cy, w * 0.5f, h * 0.5f, 8f * s);
                float d = Mathf.Max(box, -(box + lw));
                // 出っ張り
                float nubCx = x0 + w + 4.5f * s;
                d = Mathf.Min(d, RoundBox(x - nubCx, y - cy, 2.5f * s, 8f * s, 2f * s));
                // 残量の棒
                for (int i = 0; i < Mathf.Clamp(level, 0, 3); i++)
                {
                    float bx0 = x0 + 7f * s + i * 21f * s;
                    d = Mathf.Min(d, RoundBox(x - (bx0 + 8.5f * s), y - cy, 8.5f * s, h * 0.5f - 7f * s, 3f * s));
                }
                return d;
            });
        }

        /// <summary>
        /// 画面座標 (480x320、y 下向き) の範囲を塗る。dist は図形までの距離 (内側が負)。
        /// テクスチャは下の行から並び、正面から見て左が +X (u=1) なので、左右を入れ替えて書く。
        /// </summary>
        void Fill(float xMin, float yMin, float xMax, float yMax, Color col, Func<float, float, float> dist)
        {
            float sx = W / TexW, sy = H / TexH;
            int i0 = Mathf.Max(0, Mathf.FloorToInt(xMin / sx)), i1 = Mathf.Min(TexW - 1, Mathf.CeilToInt(xMax / sx));
            int j0 = Mathf.Max(0, Mathf.FloorToInt(yMin / sy)), j1 = Mathf.Min(TexH - 1, Mathf.CeilToInt(yMax / sy));
            for (int j = j0; j <= j1; j++)
            {
                float y = (j + 0.5f) * sy;
                int row = (TexH - 1 - j) * TexW;
                for (int i = i0; i <= i1; i++)
                {
                    float a = Mathf.Clamp01(0.5f - dist((i + 0.5f) * sx, y) / sx);
                    if (a <= 0f) continue;
                    int k = row + (TexW - 1 - i);
                    px[k] = Color.Lerp(px[k], col, a);
                }
            }
        }

        static float RoundBox(float x, float y, float hw, float hh, float r)
        {
            float qx = Mathf.Abs(x) - (hw - r), qy = Mathf.Abs(y) - (hh - r);
            float outside = new Vector2(Mathf.Max(qx, 0f), Mathf.Max(qy, 0f)).magnitude;
            return outside + Mathf.Min(Mathf.Max(qx, qy), 0f) - r;
        }

        static float Segment(Vector2 p, Vector2 a, Vector2 b)
        {
            Vector2 ab = b - a;
            float t = Mathf.Clamp01(Vector2.Dot(p - a, ab) / ab.sqrMagnitude);
            return (p - (a + ab * t)).magnitude;
        }
    }
}
