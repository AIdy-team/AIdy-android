using System.Collections.Generic;
using UnityEngine;

namespace Aidy
{
    /// <summary>WebDemo で使っている Font Awesome アイコンに対応する ID。</summary>
    public enum IconId
    {
        Gear, ChevronLeft, ChevronRight, House, Gamepad, Camera, Sliders, Brain, Check, XMark,
        Paw, Cubes, Heart, Snowflake, Lightbulb, Plug, Video, Microchip, Plus, Crosshairs,
        HandPointer, Circle, Wifi, MapPin, Aidy, Book, Ear, Tail, Leg, Hand, Flashlight, Battery, Hat
    }

    /// <summary>
    /// アイコンを 24x24 グリッド上の SDF 図形の合成で描き、白抜きスプライトとして生成する
    /// (Image.color で着色)。外部フォント/画像アセットに依存しない。
    /// </summary>
    public static class AidyIcons
    {
        static readonly Dictionary<(IconId, int), Sprite> cache = new Dictionary<(IconId, int), Sprite>();

        /// <summary>uiSize (Canvas 単位) で表示したときに 1 画面ピクセル = 1 テクセルになる解像度で生成する。</summary>
        public static Sprite Get(IconId id, float uiSize)
        {
            int size = Mathf.Max(8, Mathf.CeilToInt(uiSize * UI.TexScale));
            if (cache.TryGetValue((id, size), out Sprite s)) return s;

            // 1px 未満のエッジは 4x4 スーパーサンプリングで滑らかにする
            const int ss = 4;
            float scale = size / 24f;
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false)
            {
                wrapMode = TextureWrapMode.Clamp,
                filterMode = FilterMode.Bilinear,
                hideFlags = HideFlags.DontSave
            };
            var px = new Color32[size * size];
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    float a = 0f;
                    for (int sy = 0; sy < ss; sy++)
                    {
                        for (int sx = 0; sx < ss; sx++)
                        {
                            var p = new Vector2((x + (sx + 0.5f) / ss) / scale, (y + (sy + 0.5f) / ss) / scale);
                            a += Mathf.Clamp01(0.5f - Sdf(id, p) * scale * ss);
                        }
                    }
                    a /= ss * ss;
                    px[(size - 1 - y) * size + x] = new Color32(255, 255, 255, (byte)Mathf.RoundToInt(a * 255f));
                }
            }
            tex.SetPixels32(px);
            tex.Apply(false, true);
            s = Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect);
            s.hideFlags = HideFlags.DontSave;
            cache[(id, size)] = s;
            return s;
        }

        // ------------------------------------------------------------ 形状定義 (y は下向き)

        static float Sdf(IconId id, Vector2 p)
        {
            switch (id)
            {
                case IconId.Gear:
                {
                    float d = Circle(p, 12, 12, 7.2f);
                    for (int k = 0; k < 8; k++)
                    {
                        float a = k * 45f * Mathf.Deg2Rad;
                        var c = new Vector2(12 + Mathf.Cos(a) * 9f, 12 + Mathf.Sin(a) * 9f);
                        d = U(d, RotBox(p, c, new Vector2(2.0f, 1.9f), a, 0.6f));
                    }
                    return Sub(d, Circle(p, 12, 12, 3.1f));
                }
                case IconId.ChevronLeft:
                    return Polyline(p, 3.2f, V(15, 4.5f), V(7.5f, 12), V(15, 19.5f));
                case IconId.ChevronRight:
                    return Polyline(p, 3.2f, V(9, 4.5f), V(16.5f, 12), V(9, 19.5f));
                case IconId.House:
                    return Sub(Poly(p, V(12, 2.8f), V(22.5f, 12.2f), V(19.5f, 12.2f), V(19.5f, 21), V(4.5f, 21), V(4.5f, 12.2f), V(1.5f, 12.2f)),
                               Box(p, 10, 14.5f, 14, 22, 1));
                case IconId.Gamepad:
                {
                    float d = Box(p, 1.5f, 7, 22.5f, 18.5f, 5.2f);
                    d = Sub(d, U(Box(p, 5.2f, 11.7f, 9.8f, 13.3f, 0.4f), Box(p, 6.7f, 10.2f, 8.3f, 14.8f, 0.4f)));
                    d = Sub(d, Circle(p, 15.8f, 11.3f, 1.35f));
                    return Sub(d, Circle(p, 18.6f, 13.9f, 1.35f));
                }
                case IconId.Camera:
                {
                    float d = U(Box(p, 2, 7, 22, 20.5f, 2.6f), Poly(p, V(7.6f, 7.5f), V(9.3f, 4), V(14.7f, 4), V(16.4f, 7.5f)));
                    d = Sub(d, Circle(p, 12, 13.6f, 4.9f));
                    return U(d, Circle(p, 12, 13.6f, 3.0f));
                }
                case IconId.Sliders:
                {
                    float d = Seg(p, V(3, 6), V(21, 6), 2f);
                    d = U(d, Seg(p, V(3, 12), V(21, 12), 2f));
                    d = U(d, Seg(p, V(3, 18), V(21, 18), 2f));
                    d = U(d, Circle(p, 15, 6, 2.7f));
                    d = U(d, Circle(p, 8, 12, 2.7f));
                    return U(d, Circle(p, 17, 18, 2.7f));
                }
                case IconId.Brain:
                {
                    float d = Circle(p, 8.2f, 8.5f, 4.4f);
                    d = U(d, Circle(p, 15.8f, 8.5f, 4.4f));
                    d = U(d, Circle(p, 6.8f, 14.6f, 4.4f));
                    d = U(d, Circle(p, 17.2f, 14.6f, 4.4f));
                    d = U(d, Circle(p, 12, 7, 4));
                    d = U(d, Circle(p, 12, 17, 3.8f));
                    d = U(d, Circle(p, 9.5f, 18, 3));
                    d = U(d, Circle(p, 14.5f, 18, 3));
                    return Sub(d, Seg(p, V(12, 3), V(12, 21), 1.3f));
                }
                case IconId.Check:
                    return Polyline(p, 3f, V(4.5f, 12.5f), V(9.5f, 17.5f), V(19.5f, 7));
                case IconId.XMark:
                    return U(Seg(p, V(6, 6), V(18, 18), 3f), Seg(p, V(18, 6), V(6, 18), 3f));
                case IconId.Paw:
                {
                    float d = U(Circle(p, 12, 16.8f, 4.4f), U(Circle(p, 8.8f, 18.2f, 3f), Circle(p, 15.2f, 18.2f, 3f)));
                    d = U(d, Circle(p, 5.4f, 10.8f, 2.4f));
                    d = U(d, Circle(p, 9.4f, 6.4f, 2.5f));
                    d = U(d, Circle(p, 14.6f, 6.4f, 2.5f));
                    return U(d, Circle(p, 18.6f, 10.8f, 2.4f));
                }
                case IconId.Cubes:
                    return U(Box(p, 2.5f, 12.5f, 11, 21, 1.4f), U(Box(p, 13, 12.5f, 21.5f, 21, 1.4f), Box(p, 7.75f, 3, 16.25f, 11.5f, 1.4f)));
                case IconId.Heart:
                    return U(Circle(p, 8.3f, 9, 4.9f), U(Circle(p, 15.7f, 9, 4.9f), Poly(p, V(3.85f, 11.2f), V(20.15f, 11.2f), V(12, 20.6f))));
                case IconId.Snowflake:
                {
                    var c = V(12, 12);
                    float d = float.MaxValue;
                    for (int k = 0; k < 6; k++)
                    {
                        float a = (k * 60f + 90f) * Mathf.Deg2Rad;
                        var u = new Vector2(Mathf.Cos(a), Mathf.Sin(a));
                        d = U(d, Seg(p, c, c + u * 9.5f, 2f));
                        var q = c + u * 6.3f;
                        d = U(d, Seg(p, q, q + Rot(u, 45f) * 3f, 1.8f));
                        d = U(d, Seg(p, q, q + Rot(u, -45f) * 3f, 1.8f));
                    }
                    return d;
                }
                case IconId.Lightbulb:
                    return U(Circle(p, 12, 9.3f, 6.6f), U(Poly(p, V(7.6f, 12.5f), V(16.4f, 12.5f), V(14.8f, 17), V(9.2f, 17)), Box(p, 9, 18.2f, 15, 21.5f, 1.2f)));
                case IconId.Plug:
                {
                    float d = Box(p, 5.5f, 8, 18.5f, 15.5f, 3);
                    d = U(d, Box(p, 10.4f, 14, 13.6f, 21.5f, 0.6f));
                    d = U(d, Box(p, 7.8f, 2.5f, 10.2f, 8.5f, 1));
                    return U(d, Box(p, 13.8f, 2.5f, 16.2f, 8.5f, 1));
                }
                case IconId.Video:
                    return U(Box(p, 1.5f, 6, 15.5f, 18, 2.6f), Poly(p, V(16.8f, 10.2f), V(22.5f, 6.8f), V(22.5f, 17.2f), V(16.8f, 13.8f)));
                case IconId.Microchip:
                {
                    float d = Sub(Box(p, 6, 6, 18, 18, 1.8f), Box(p, 8.8f, 8.8f, 15.2f, 15.2f, 1));
                    d = U(d, Box(p, 10.3f, 10.3f, 13.7f, 13.7f, 0.6f));
                    foreach (float k in new[] { 9f, 12f, 15f })
                    {
                        d = U(d, Seg(p, V(k, 2.5f), V(k, 6), 1.6f));
                        d = U(d, Seg(p, V(k, 18), V(k, 21.5f), 1.6f));
                        d = U(d, Seg(p, V(2.5f, k), V(6, k), 1.6f));
                        d = U(d, Seg(p, V(18, k), V(21.5f, k), 1.6f));
                    }
                    return d;
                }
                case IconId.Plus:
                    return U(Seg(p, V(12, 4.5f), V(12, 19.5f), 3f), Seg(p, V(4.5f, 12), V(19.5f, 12), 3f));
                case IconId.Crosshairs:
                {
                    float d = Mathf.Abs((p - V(12, 12)).magnitude - 7.6f) - 1.1f;
                    d = U(d, Seg(p, V(12, 1.5f), V(12, 7.5f), 2.2f));
                    d = U(d, Seg(p, V(12, 16.5f), V(12, 22.5f), 2.2f));
                    d = U(d, Seg(p, V(1.5f, 12), V(7.5f, 12), 2.2f));
                    d = U(d, Seg(p, V(16.5f, 12), V(22.5f, 12), 2.2f));
                    return U(d, Circle(p, 12, 12, 1.7f));
                }
                case IconId.HandPointer:
                {
                    float d = Seg(p, V(9.8f, 3.2f), V(9.8f, 13), 3.6f);
                    d = U(d, Box(p, 7.6f, 10.5f, 19.6f, 21.8f, 3.4f));
                    d = U(d, Seg(p, V(13.6f, 10.2f), V(13.6f, 12.5f), 3.2f));
                    d = U(d, Seg(p, V(17.2f, 11.2f), V(17.2f, 13.5f), 3f));
                    return U(d, Seg(p, V(5, 13.5f), V(8.4f, 17.4f), 3f));
                }
                case IconId.Circle:
                    return Circle(p, 12, 12, 9);
                case IconId.Wifi:
                {
                    var c = V(12, 19.5f);
                    float wedge = Poly(p, c, V(-10, -2.5f), V(34, -2.5f));
                    float d = Circle(p, 12, 19.5f, 2.1f);
                    foreach (float r in new[] { 14.5f, 9.8f, 5.1f })
                        d = U(d, Mathf.Max(Mathf.Abs((p - c).magnitude - r) - 1.3f, wedge));
                    return d;
                }
                case IconId.MapPin:
                    return Sub(U(Circle(p, 12, 9, 6.8f), Poly(p, V(6.1f, 12.4f), V(17.9f, 12.4f), V(12, 22))), Circle(p, 12, 9, 2.7f));
                case IconId.Aidy:
                {
                    // デフォルメ Aidy の単色版 (枠 + ディスプレイ、目は抜き)
                    float outer = Box(p, 2.2f, 2.2f, 21.8f, 21.8f, 4.5f);
                    float d = Mathf.Max(outer, -(outer + 1.8f));
                    d = U(d, Sub(Box(p, 5.4f, 7.8f, 18.6f, 16.6f, 2f), U(Ellipse(p, 10, 12.2f, 1.25f, 2.1f), Ellipse(p, 14, 12.2f, 1.25f, 2.1f))));
                    d = U(d, Circle(p, 12, 5.2f, 1.05f));
                    return U(d, Seg(p, V(11, 19.2f), V(13, 19.2f), 1.4f));
                }
                case IconId.Ear:
                    // 耳モジュール: アーチ形 + 内側の溝
                    return Sub(U(Poly(p, V(3.5f, 20.5f), V(6, 9), V(12, 3), V(18, 9), V(20.5f, 20.5f)), Circle(p, 12, 8.5f, 5.6f)),
                               Poly(p, V(8.6f, 18.8f), V(10, 11), V(12, 8.2f), V(14, 11), V(15.4f, 18.8f)));
                case IconId.Tail:
                    return U(Polyline(p, 3.2f, V(4.5f, 20), V(8, 15.5f), V(13, 14), V(17, 10.5f), V(18.5f, 5.5f)), Circle(p, 18.6f, 4.8f, 2.4f));
                case IconId.Hat:
                    // シルクハット: つば + 筒
                    return U(Box(p, 2.5f, 15.5f, 21.5f, 19, 1.6f), Box(p, 6.5f, 4.5f, 17.5f, 17, 1.8f));
                case IconId.Leg:
                    return U(Box(p, 3.5f, 3.5f, 20.5f, 8, 2), U(Box(p, 5.5f, 6, 10, 20.5f, 2), Box(p, 14, 6, 18.5f, 20.5f, 2)));
                case IconId.Hand:
                    // 手モジュール: 本体から横に伸びる角丸の腕
                    return U(Box(p, 2.5f, 8.5f, 17.5f, 15.5f, 3.2f), Box(p, 16, 6.5f, 21.5f, 17.5f, 2.6f));
                case IconId.Flashlight:
                {
                    float d = U(Box(p, 2.5f, 8.5f, 14, 15.5f, 3f), Poly(p, V(13, 8.5f), V(17.5f, 5.5f), V(17.5f, 18.5f), V(13, 15.5f)));
                    d = U(d, Seg(p, V(19.5f, 7), V(22, 5.2f), 1.5f));
                    d = U(d, Seg(p, V(20, 12), V(22.8f, 12), 1.5f));
                    return U(d, Seg(p, V(19.5f, 17), V(22, 18.8f), 1.5f));
                }
                case IconId.Battery:
                {
                    float body = Box(p, 1.5f, 6.5f, 20.5f, 17.5f, 2.6f);
                    return U(Mathf.Max(body, -(body + 1.6f)), Box(p, 20.5f, 9.5f, 23, 14.5f, 1));
                }
                case IconId.Book:
                {
                    float d = Sub(Box(p, 4, 2.5f, 20, 18.5f, 2.2f), Box(p, 8, 6, 16, 9.2f, 0.6f));
                    d = U(d, Sub(Box(p, 4, 16.5f, 20, 21.5f, 2f), Box(p, 6.4f, 18f, 21, 20f, 0.6f)));
                    return d;
                }
            }
            return 1f;
        }

        // ------------------------------------------------------------ SDF プリミティブ

        internal static Vector2 V(float x, float y) => new Vector2(x, y);
        internal static float U(float a, float b) => Mathf.Min(a, b);
        internal static float Sub(float a, float b) => Mathf.Max(a, -b);

        static Vector2 Rot(Vector2 v, float deg)
        {
            float a = deg * Mathf.Deg2Rad, c = Mathf.Cos(a), s = Mathf.Sin(a);
            return new Vector2(v.x * c - v.y * s, v.x * s + v.y * c);
        }

        internal static float Ellipse(Vector2 p, float cx, float cy, float rx, float ry)
        {
            return (new Vector2((p.x - cx) / rx, (p.y - cy) / ry).magnitude - 1f) * Mathf.Min(rx, ry);
        }

        internal static float Circle(Vector2 p, float cx, float cy, float r) => (p - new Vector2(cx, cy)).magnitude - r;

        internal static float Seg(Vector2 p, Vector2 a, Vector2 b, float width)
        {
            Vector2 pa = p - a, ba = b - a;
            float h = Mathf.Clamp01(Vector2.Dot(pa, ba) / Vector2.Dot(ba, ba));
            return (pa - ba * h).magnitude - width * 0.5f;
        }

        internal static float Polyline(Vector2 p, float width, params Vector2[] pts)
        {
            float d = float.MaxValue;
            for (int i = 0; i + 1 < pts.Length; i++) d = U(d, Seg(p, pts[i], pts[i + 1], width));
            return d;
        }

        internal static float Box(Vector2 p, float x0, float y0, float x1, float y1, float r)
        {
            var c = new Vector2((x0 + x1) * 0.5f, (y0 + y1) * 0.5f);
            return RoundBox(p - c, new Vector2((x1 - x0) * 0.5f, (y1 - y0) * 0.5f), r);
        }

        static float RotBox(Vector2 p, Vector2 c, Vector2 half, float angle, float r)
        {
            Vector2 q = p - c;
            float cs = Mathf.Cos(-angle), sn = Mathf.Sin(-angle);
            q = new Vector2(q.x * cs - q.y * sn, q.x * sn + q.y * cs);
            return RoundBox(q, half, r);
        }

        internal static float RoundBox(Vector2 p, Vector2 half, float r)
        {
            float qx = Mathf.Abs(p.x) - half.x + r, qy = Mathf.Abs(p.y) - half.y + r;
            return new Vector2(Mathf.Max(qx, 0), Mathf.Max(qy, 0)).magnitude + Mathf.Min(Mathf.Max(qx, qy), 0) - r;
        }

        internal static float Poly(Vector2 p, params Vector2[] v)
        {
            float d = Vector2.Dot(p - v[0], p - v[0]);
            float s = 1f;
            for (int i = 0, j = v.Length - 1; i < v.Length; j = i, i++)
            {
                Vector2 e = v[j] - v[i], w = p - v[i];
                Vector2 b = w - e * Mathf.Clamp01(Vector2.Dot(w, e) / Vector2.Dot(e, e));
                d = Mathf.Min(d, Vector2.Dot(b, b));
                bool c1 = p.y >= v[i].y, c2 = p.y < v[j].y, c3 = e.x * w.y > e.y * w.x;
                if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s;
            }
            return s * Mathf.Sqrt(d);
        }
    }
}
