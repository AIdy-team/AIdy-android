using System.Collections.Generic;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>デフォルメ Aidy の表情。ディスプレイに映る目の形だけが変わる。</summary>
    public enum AidyFace { Normal, Blink, Happy, Sleepy, Surprised, Dizzy }

    /// <summary>
    /// デフォルメ Aidy (角丸ボディ + 黒ディスプレイ + 縦長の目) をフルカラーで描くスプライト生成器。
    /// 100x100 グリッド上の SDF を重ね塗りする。小さいサイズでは線を太らせ、側面の刻みを省く。
    /// </summary>
    public static class AidyMascotArt
    {
        static readonly Color Body = Color.white;
        static readonly Color Outline = Theme.Hex(0x334155);
        static readonly Color Display = Theme.Hex(0x0f172a);
        static readonly Color Eye = Theme.Hex(0xf8fafc);
        static readonly Color Cheek = Theme.Hex(0xf472b6, 0.8f);

        static readonly Dictionary<(AidyFace, int), Sprite> cache = new Dictionary<(AidyFace, int), Sprite>();

        public static Sprite Get(AidyFace face, float uiSize)
        {
            int size = Mathf.Max(12, Mathf.CeilToInt(uiSize * UI.TexScale));
            if (cache.TryGetValue((face, size), out Sprite s)) return s;

            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false)
            {
                wrapMode = TextureWrapMode.Clamp,
                filterMode = FilterMode.Bilinear,
                hideFlags = HideFlags.DontSave
            };
            tex.SetPixels32(Render(face, size, uiSize));
            tex.Apply(false, true);
            s = Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect);
            s.hideFlags = HideFlags.DontSave;
            cache[(face, size)] = s;
            return s;
        }

        /// <summary>size x size のピクセル (Texture2D.SetPixels32 の並び = 下の行から)。uiSize は線の太さの調整に使う。</summary>
        public static Color32[] Render(AidyFace face, int size, float uiSize)
        {
            const int ss = 4;
            float scale = size / 100f;
            float stroke = Mathf.Max(4f, 110f / uiSize);
            float line = Mathf.Max(2.8f, 70f / uiSize);
            bool details = uiSize >= 20f;

            var px = new Color32[size * size];
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    Color acc = Color.clear; // 乗算済みアルファで平均する
                    for (int sy = 0; sy < ss; sy++)
                    {
                        for (int sx = 0; sx < ss; sx++)
                        {
                            var p = new Vector2((x + (sx + 0.5f) / ss) / scale, (y + (sy + 0.5f) / ss) / scale);
                            float aa = scale * ss;
                            Color c = Color.clear;

                            float outer = AidyIcons.Box(p, 6, 6, 94, 94, 15);
                            c = Over(c, Body, outer, aa);
                            c = Over(c, Outline, Mathf.Max(outer, -(outer + stroke)), aa);
                            c = Over(c, Outline, AidyIcons.Circle(p, 50, 18, Mathf.Max(3.2f, line * 1.1f)), aa);
                            c = Over(c, Outline, AidyIcons.Seg(p, V(46, 81), V(54, 81), line * 1.1f), aa);
                            if (details)
                            {
                                foreach (float sx2 in new[] { 13f, 87f })
                                {
                                    c = Over(c, Outline, AidyIcons.Seg(p, V(sx2, 35), V(sx2, 41), 2.4f), aa);
                                    c = Over(c, Outline, AidyIcons.Seg(p, V(sx2, 59), V(sx2, 65), 2.4f), aa);
                                }
                            }
                            c = Over(c, Display, AidyIcons.Box(p, 20, 29, 80, 71, 8), aa);
                            c = Over(c, Eye, Eyes(face, p, line), aa);
                            if (face == AidyFace.Happy)
                                c = Over(c, Cheek, AidyIcons.U(AidyIcons.Circle(p, 29, 59, 3.6f), AidyIcons.Circle(p, 71, 59, 3.6f)), aa);

                            acc += new Color(c.r * c.a, c.g * c.a, c.b * c.a, c.a);
                        }
                    }
                    acc /= ss * ss;
                    Color o = acc.a > 0f ? new Color(acc.r / acc.a, acc.g / acc.a, acc.b / acc.a, acc.a) : Color.clear;
                    px[(size - 1 - y) * size + x] = o;
                }
            }
            return px;
        }

        static float Eyes(AidyFace face, Vector2 p, float line)
        {
            switch (face)
            {
                case AidyFace.Blink:
                    return AidyIcons.U(AidyIcons.Seg(p, V(36.5f, 51), V(43.5f, 51), line), AidyIcons.Seg(p, V(56.5f, 51), V(63.5f, 51), line));
                case AidyFace.Happy:
                    return AidyIcons.U(AidyIcons.Polyline(p, line, V(35.5f, 53), V(40, 47), V(44.5f, 53)),
                                       AidyIcons.Polyline(p, line, V(55.5f, 53), V(60, 47), V(64.5f, 53)));
                case AidyFace.Sleepy:
                    return AidyIcons.U(AidyIcons.Polyline(p, line, V(35.5f, 49), V(40, 53), V(44.5f, 49)),
                                       AidyIcons.Polyline(p, line, V(55.5f, 49), V(60, 53), V(64.5f, 49)));
                case AidyFace.Surprised:
                    return AidyIcons.U(AidyIcons.Circle(p, 40, 50, 4.8f), AidyIcons.Circle(p, 60, 50, 4.8f));
                case AidyFace.Dizzy:
                    return AidyIcons.U(Spiral(p, 40, 50, 7.5f, line * 0.8f), Spiral(p, 60, 50, 7.5f, line * 0.8f));
                default:
                    return AidyIcons.U(AidyIcons.Ellipse(p, 40, 50, 3.8f, 7.2f), AidyIcons.Ellipse(p, 60, 50, 3.8f, 7.2f));
            }
        }

        /// <summary>ぐるぐる目 (アルキメデスの渦巻き)。</summary>
        static float Spiral(Vector2 p, float cx, float cy, float maxR, float line)
        {
            Vector2 d = p - new Vector2(cx, cy);
            float r = d.magnitude;
            float th = Mathf.Atan2(d.y, d.x);
            if (th < 0f) th += Mathf.PI * 2f;
            float a = maxR / (Mathf.PI * 2f * 1.75f);
            float best = float.MaxValue;
            for (int k = 0; k < 3; k++)
            {
                float rr = a * (th + Mathf.PI * 2f * k);
                if (rr > maxR) break;
                best = Mathf.Min(best, Mathf.Abs(r - rr));
            }
            return best - line * 0.5f;
        }

        /// <summary>
        /// 本体の顔モニターに貼る表情テクスチャ (w x h)。デフォルメ Aidy のディスプレイ部分と同じ目の形を
        /// bg の上に fg で描く。行の並びは Texture2D と同じ (下から)。
        /// </summary>
        public static Texture2D RenderScreen(AidyFace face, int w, int h, Color bg, Color fg)
        {
            const int ss = 3;
            var px = new Color32[w * h];
            float unitsPerPx = 60f / w; // ディスプレイ部分 x:20..80, y:29..71 に対応させる
            float vScale = 42f / h;
            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    float a = 0f;
                    for (int sy = 0; sy < ss; sy++)
                    {
                        for (int sx = 0; sx < ss; sx++)
                        {
                            var p = new Vector2(20f + (x + (sx + 0.5f) / ss) * unitsPerPx, 71f - (y + (sy + 0.5f) / ss) * vScale);
                            a += Mathf.Clamp01(0.5f - Eyes(face, p, 3.4f) / unitsPerPx);
                        }
                    }
                    a /= ss * ss;
                    px[y * w + x] = Color.Lerp(bg, fg, a);
                }
            }
            var tex = new Texture2D(w, h, TextureFormat.RGBA32, false)
            {
                wrapMode = TextureWrapMode.Clamp,
                filterMode = FilterMode.Bilinear,
                hideFlags = HideFlags.DontSave
            };
            tex.SetPixels32(px);
            tex.Apply(false, true);
            return tex;
        }

        static Vector2 V(float x, float y) => new Vector2(x, y);

        static Color Over(Color under, Color over, float d, float aa)
        {
            float a = Mathf.Clamp01(0.5f - d * aa) * over.a;
            if (a <= 0f) return under;
            float outA = a + under.a * (1f - a);
            Color rgb = (over * a + under * under.a * (1f - a)) / outA;
            rgb.a = outA;
            return rgb;
        }
    }

    /// <summary>
    /// UI のワンポイントとして置くデフォルメ Aidy。まばたき・ふわふわ揺れ、タップで喜んで跳ねる。
    /// レイアウト上は holder が size x size を占有し、絵 (子) だけを動かす。
    /// </summary>
    public class AidyMascot : MonoBehaviour, IPointerClickHandler
    {
        Image art;
        float size;
        bool animate;
        AidyFace face, shown, reactFace;
        float blinkAt, blinkUntil, reactUntil, hop = 1f, phase;

        public static AidyMascot Create(Transform parent, float size, AidyFace face, bool animate = true, bool interactive = false)
        {
            var holder = UI.Rect("Aidy", parent);
            holder.sizeDelta = new Vector2(size, size);
            holder.Layout(size, size);

            var img = UI.Image(holder, "Art", Color.white, 0, interactive);
            img.type = Image.Type.Simple;
            img.preserveAspect = true;
            img.rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(size, size));

            var m = holder.gameObject.AddComponent<AidyMascot>();
            m.art = img;
            m.size = size;
            m.animate = animate;
            m.phase = Random.value * 6.28f;
            m.blinkAt = Time.unscaledTime + Random.Range(1f, 4f);
            m.face = face;
            m.Show(face);
            return m;
        }

        public AidyFace Face => face;

        public void SetFace(AidyFace f)
        {
            face = f;
            if (!animate) Show(f);
        }

        /// <summary>一時的に表情を変えてぴょんと跳ねる。</summary>
        public void React(AidyFace f, float duration = 1.2f)
        {
            reactFace = f;
            reactUntil = Time.unscaledTime + duration;
            hop = 0f;
            if (!animate) Show(f);
        }

        public void OnPointerClick(PointerEventData e) { React(AidyFace.Happy); }

        void Show(AidyFace f)
        {
            shown = f;
            art.sprite = AidyMascotArt.Get(f, size);
        }

        void Update()
        {
            if (!animate) return;
            float now = Time.unscaledTime;

            AidyFace want = now < reactUntil ? reactFace : face;
            if (want == AidyFace.Normal)
            {
                if (now >= blinkAt)
                {
                    blinkUntil = now + 0.12f;
                    blinkAt = now + Random.Range(2.5f, 5.5f);
                }
                if (now < blinkUntil) want = AidyFace.Blink;
            }
            if (want != shown) Show(want);

            float y = Mathf.Sin(now * 2.2f + phase) * size * 0.03f;
            if (hop < 1f)
            {
                hop = Mathf.Min(1f, hop + Time.unscaledDeltaTime / 0.45f);
                y += Mathf.Sin(hop * Mathf.PI) * size * 0.18f;
            }
            art.rectTransform.anchoredPosition = new Vector2(0, y);
        }
    }
}
