using System;
using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.TextCore.LowLevel;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>WebDemo の CSS 変数・配色をそのまま定義したもの。</summary>
    public static class Theme
    {
        public static Color Hex(uint rgb, float a = 1f)
        {
            return new Color(((rgb >> 16) & 0xff) / 255f, ((rgb >> 8) & 0xff) / 255f, (rgb & 0xff) / 255f, a);
        }

        public static readonly Color Device = Hex(0xf8fafc);
        public static readonly Color Primary = Hex(0x3b82f6);
        public static readonly Color PrimaryLight = Hex(0xeff6ff);
        public static readonly Color TextMain = Hex(0x1e293b);
        public static readonly Color TextSub = Hex(0x64748b);
        public static readonly Color Border = Hex(0xe2e8f0);
        public static readonly Color Green = Hex(0x10b981);
        public static readonly Color Slate100 = Hex(0xf1f5f9);
        public static readonly Color Slate300 = Hex(0xcbd5e1);
        public static readonly Color Slate400 = Hex(0x94a3b8);
        public static readonly Color Slate600 = Hex(0x475569);
        public static readonly Color Slate700 = Hex(0x334155);
        public static readonly Color Slate900 = Hex(0x0f172a);
    }

    /// <summary>CSS の cubic-bezier() と同じイージング。</summary>
    public readonly struct CubicBezier
    {
        readonly float x1, y1, x2, y2;

        public CubicBezier(float x1, float y1, float x2, float y2)
        {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }

        public static readonly CubicBezier Ease = new CubicBezier(0.25f, 0.1f, 0.25f, 1f);
        public static readonly CubicBezier EaseOut = new CubicBezier(0f, 0f, 0.58f, 1f);
        public static readonly CubicBezier PageSnap = new CubicBezier(0.2f, 0.9f, 0.3f, 1f);

        static float Bez(float a, float b, float s)
        {
            float u = 1f - s;
            return 3f * u * u * s * a + 3f * u * s * s * b + s * s * s;
        }

        public float Evaluate(float t)
        {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float lo = 0f, hi = 1f, s = t;
            for (int i = 0; i < 24; i++)
            {
                float x = Bez(x1, x2, s);
                if (Mathf.Abs(x - t) < 1e-5f) break;
                if (x < t) lo = s; else hi = s;
                s = (lo + hi) * 0.5f;
            }
            return Bez(y1, y2, s);
        }
    }

    public struct BoxShadow
    {
        public float X, Y, Blur;
        public Color Color;

        public BoxShadow(float x, float y, float blur, Color color)
        {
            X = x; Y = y; Blur = blur; Color = color;
        }
    }

    /// <summary>uGUI をコードで組み立てるためのヘルパー群 (角丸・影・枠線・テキスト・レイアウト)。</summary>
    public static class UI
    {
        /// <summary>
        /// 生成スプライトの解像度 (テクスチャpx / UI単位)。起動時に画面の実倍率を設定し、
        /// 角丸・影・アイコンの輪郭を 1 画面ピクセル単位でアンチエイリアスする。
        /// </summary>
        public static float TexScale = 3f;

        static TMP_FontAsset font;
        static bool fontTried;
        static readonly Dictionary<string, Sprite> spriteCache = new Dictionary<string, Sprite>();

        /// <summary>端末の日本語フォント (Android: Noto Sans CJK / Windows: Yu Gothic) から作る SDF フォント。</summary>
        public static TMP_FontAsset Font
        {
            get
            {
                if (!fontTried)
                {
                    fontTried = true;
                    font = CreateFont();
                }
                return font;
            }
        }

        /// <summary>
        /// 起動時にアトラスへ登録する文字 (ASCII + アプリ内の全文言)。文言を追加したらここにも足す。
        /// </summary>
        public static string Preload =
            " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~°♥…・" +
            "AIDY ホーム リモコン AR 設定 78% 本体v2モデル 描画完了 本体v2_モデル (鋼サテン) (.obj+.mtl) 読込完了 本体v2_顔あり OBJ読込失敗 " +
            "タップしてナデナデ / ドラッグ回転 モジュールモデル合体設計 耳モジュール 尻尾モジュール 足モジュール カメラディスプレイ " +
            "本体v2ロボとなでなで遊ぶ 本体v2 AI歩行停止中 本体v2_モデルが気ままにお散歩中... 本体v2_モデルが日向ぼっこ中 周囲の状況を確認中 " +
            "♥ 本体v2_モデルが嬉しくて跳ねています！ 標準モジュール (モデル合体済み) リビングエアコン 温度: 24°C / 自動 メイン照明 " +
            "オン / 100% 輝度 スマートプラグ オフ / タイマー設定 拡張モジュール (追加3Dモデル連携) カメラフィードモジュール " +
            "拡張OBJモデル接続可能 センサーモジュール 温度/湿度 拡張モジュール カメラ越しに机や床をクリックして配置 " +
            "基準位置: (0.0, 0.0) 本体v2_モデル配置完了 本体v2配置完了 本体v2中央配置 未配置 (床や机をタップ) Webカメラ起動 中央に再配置 " +
            "Webカメラ動作中 (本体v2配置) クリックした場所を基準に本体v2配置 ARカメラ起動中… ARカメラ動作中 (床や机をタップして本体v2配置) " +
            "端末をゆっくり動かして床や机を認識中… ロボット設定 アセンブリ構造 本体v2_モデル.obj (透明化防止完全対応) スピーカー音量 " +
            "効果音 & 音声応答 60% 操作 & ネットワーク モジュール自動合体 パーツSocketアタッチ機能 (ON) Wi-Fi 接続 AIDY-5G-2.4G (接続済み) " +
            "日記 Aidyの日記 同期 同期中… 日月火水木金土 年月日 おしゃべり 回 件 〜 会話ログ サンプル 未接続 最終同期: まだ本体と同期していません " +
            "まだ来ていない日だよ 今日の日記はまだないよ この日の日記はないみたい その日になったら、いっしょにお話ししようね " +
            "おしゃべりすると、会話ログから日記ができるよ Aidyから会話ログを受け取る Aidy本体に接続できませんでした 日分の日記を更新しました " +
            "新しい会話ログはありませんでした Aidy本体の会話ログ から Wi-Fi で日記を同期 aidy.local " +
            "たのしい一日 ちょっとおつかれの一日 たくさんおしゃべりした一日 いつもの一日 しずかな一日 今日はからまで、回おしゃべりしたよ。" +
            "上面正面背面左面右面底面 モジュール合体 (面ごとのスロット) ドラッグして取り付け (外すときは配置図の外へ) 耳尻尾足顔 モニター スロット123 " +
            "を光っているスロットへ を付けられる空きスロットがありません に を取り付けました そのスロットはもう使われています は には付けられません " +
            "を取り外しました 下のパレットからモジュールをドラッグしてください タップ/長押しでナデナデ ・ ドラッグで回転 " +
            "モジュールを付けてもらった！ モジュールが外れた！ くすぐったい〜！ やめて〜 なでなで… うっとり もっとなでて！ @_@ 目が回った〜 " +
            "ふぅ… やっと目が回らなくなった 足がないのでお散歩できない… " +
            "準備OK！ 本体とおなじ形だよ 本体の3Dモデルを読み込めなかった… こんにちは！ いまのモジュール 付け替え なし (本体だけ) 画面上 カメラ起動 " +
            "タップでなでなで ・ ドラッグで回転 ・ ピンチでズーム 面をタップで選択 面をタップして付け替えよう 床や机をタップして置いてね まんなかに戻ったよ！ ここに置いたよ！ " +
            "カメラ越しに机や床をタップして配置 なでなでしてもらってうれしい！ はっ！ 寝てないよ！ うーん… のびのび〜 ♪ ごきげんダンス！ は… はっ… くしゅんっ！ " +
            "ずずっ… 失礼しました すやすや… Zzz お散歩はお休み中 気ままにお散歩中… 日向ぼっこ中 周りをきょろきょろ " +
            "に付けられるモジュール 手 ライト手 お散歩 3Dモデルの面をタップしてね その面に付けられるモジュールが出てくるよ ここで離すと取り外し のモジュール ・ 付いている: 、× " +
            "別のスロットへ移動 / トレイで離すと取り外し を光っている丸へ を → に切り替えました はそこには付けられません 面をタップで選択 ・ ドラッグで回転 ・ 長押しでなでなで " +
            "を選んだよ ・ モジュールをドラッグしてね 面の選択を解除しました " +
            "朝昼夜は「」って話してくれたね。たくさん笑ってくれて、ぼくもうれしかった！おつかれさま。今日はゆっくり休んでね。明日もいっぱいお話ししようね。" +
            "予定 ＋ 追加 この日の予定はまだないよ 終日 編集 予定を追加 予定を編集 タイトル 例: 病院、友だちとおでかけ 時刻 (空欄なら終日) 例: 10:00 " +
            "メモ なくてもOK 削除 本当に削除する？ キャンセル 保存 タイトルを入れてね 時刻は 10:00 のように入れてね 下の「予定」から、この日の予定を入れておけるよ";

        /// <summary>本体から届いた文章など、Preload に無い文字を描画前にアトラスへ追加する。</summary>
        public static void EnsureChars(string text)
        {
            if (Font != null && !string.IsNullOrEmpty(text)) Font.TryAddCharacters(text, out string _);
        }

        static TMP_FontAsset CreateFont()
        {
            string[] prefs = { "notosanscjkjpregular", "notosanscjkregular", "notosansjpregular", "notosanscjkjp", "notosanscjk", "notosansjp", "yugothm", "yugothr", "meiryo", "msgothic", "droidsansfallback" };
            string[] paths = UnityEngine.Font.GetPathsToOSFonts();
            foreach (string pref in prefs)
            {
                foreach (string path in paths)
                {
                    string file = System.IO.Path.GetFileNameWithoutExtension(path).Replace(" ", "").Replace("-", "").Replace("_", "").ToLowerInvariant();
                    if (!file.Contains(pref)) continue;
                    try
                    {
                        var src = new Font(path);
                        var fa = TMP_FontAsset.CreateFontAsset(src, 64, 8, GlyphRenderMode.SDFAA, 2048, 2048, AtlasPopulationMode.Dynamic, true);
                        if (fa != null)
                        {
                            fa.name = "AIDY_" + System.IO.Path.GetFileNameWithoutExtension(path);
                            // 画面で使う文字を先にアトラスへ登録 (描画途中の動的追加による欠けを防ぐ)
                            fa.TryAddCharacters(Preload, out string missing);
                            if (!string.IsNullOrEmpty(missing)) Debug.LogWarning("[AIDY] フォントに無い文字: " + missing);
                            // 低倍率 (PC プレビュー等) では「ー」「_」の細い横線が 1px 未満で消えるため少し太らせる
                            if (TexScale < 2f) fa.material.SetFloat(ShaderUtilities.ID_FaceDilate, 0.25f * (2f - TexScale));
                            return fa;
                        }
                    }
                    catch (Exception e)
                    {
                        Debug.LogWarning($"[AIDY] フォント読込失敗 {path}: {e.Message}");
                    }
                }
            }
            Debug.LogWarning("[AIDY] 日本語フォントが見つからないため既定フォントを使用します");
            return TMP_Settings.defaultFontAsset;
        }

        // ---------------------------------------------------------------- RectTransform

        public static RectTransform Rect(string name, Transform parent)
        {
            var go = new GameObject(name, typeof(RectTransform));
            go.layer = 5;
            var rt = (RectTransform)go.transform;
            rt.SetParent(parent, false);
            return rt;
        }

        public static RectTransform Stretch(this RectTransform rt, float left = 0, float top = 0, float right = 0, float bottom = 0)
        {
            rt.anchorMin = Vector2.zero;
            rt.anchorMax = Vector2.one;
            rt.offsetMin = new Vector2(left, bottom);
            rt.offsetMax = new Vector2(-right, -top);
            return rt;
        }

        /// <summary>anchor/pivot を同じ点にして配置 (CSS の position:absolute 的な使い方)。</summary>
        public static RectTransform Pin(this RectTransform rt, Vector2 anchor, Vector2 pos, Vector2 size)
        {
            rt.anchorMin = rt.anchorMax = rt.pivot = anchor;
            rt.anchoredPosition = pos;
            rt.sizeDelta = size;
            return rt;
        }

        public static LayoutElement Layout(this Component c, float w = -1, float h = -1, float flexW = -1, float flexH = -1)
        {
            if (!c.TryGetComponent(out LayoutElement le)) le = c.gameObject.AddComponent<LayoutElement>();
            le.preferredWidth = w;
            le.preferredHeight = h;
            if (w >= 0) le.minWidth = w;
            if (h >= 0) le.minHeight = h;
            le.flexibleWidth = flexW;
            le.flexibleHeight = flexH;
            return le;
        }

        public static void IgnoreLayout(this Component c)
        {
            if (!c.TryGetComponent(out LayoutElement le)) le = c.gameObject.AddComponent<LayoutElement>();
            le.ignoreLayout = true;
        }

        public static HorizontalLayoutGroup HLayout(this RectTransform rt, float l, float r, float t, float b, float spacing, TextAnchor align = TextAnchor.MiddleLeft)
        {
            var g = rt.gameObject.AddComponent<HorizontalLayoutGroup>();
            g.padding = new RectOffset(Mathf.RoundToInt(l), Mathf.RoundToInt(r), Mathf.RoundToInt(t), Mathf.RoundToInt(b));
            g.spacing = spacing;
            g.childAlignment = align;
            g.childControlWidth = g.childControlHeight = true;
            g.childForceExpandWidth = g.childForceExpandHeight = false;
            return g;
        }

        public static VerticalLayoutGroup VLayout(this RectTransform rt, float l, float r, float t, float b, float spacing, TextAnchor align = TextAnchor.UpperLeft)
        {
            var g = rt.gameObject.AddComponent<VerticalLayoutGroup>();
            g.padding = new RectOffset(Mathf.RoundToInt(l), Mathf.RoundToInt(r), Mathf.RoundToInt(t), Mathf.RoundToInt(b));
            g.spacing = spacing;
            g.childAlignment = align;
            g.childControlWidth = g.childControlHeight = true;
            g.childForceExpandWidth = g.childForceExpandHeight = false;
            return g;
        }

        public static void FitContent(this RectTransform rt, bool horizontal = true, bool vertical = true)
        {
            var f = rt.gameObject.AddComponent<ContentSizeFitter>();
            f.horizontalFit = horizontal ? ContentSizeFitter.FitMode.PreferredSize : ContentSizeFitter.FitMode.Unconstrained;
            f.verticalFit = vertical ? ContentSizeFitter.FitMode.PreferredSize : ContentSizeFitter.FitMode.Unconstrained;
        }

        public static RectTransform Spacer(Transform parent, float height, float width = 0)
        {
            var rt = Rect("Spacer", parent);
            rt.Layout(width, height);
            return rt;
        }

        // ---------------------------------------------------------------- Graphics

        public static Image Image(Transform parent, string name, Color color, float radius = 0, bool raycast = false)
        {
            var rt = Rect(name, parent);
            var img = rt.gameObject.AddComponent<Image>();
            img.color = color;
            img.raycastTarget = raycast;
            SetRadius(img, radius);
            return img;
        }

        public static void SetRadius(Image img, float radius, float stroke = 0)
        {
            Sprite s = RoundedSprite(radius, stroke);
            img.sprite = s;
            if (s != null)
            {
                img.type = UnityEngine.UI.Image.Type.Sliced;
                img.pixelsPerUnitMultiplier = TexScale;
                img.fillCenter = true;
            }
        }

        /// <summary>
        /// host の背面に「影 → 背景 → 枠線」を敷く (CSS の background / border / box-shadow)。
        /// host 自身にはレイアウトグループを付けられるよう、全て ignoreLayout の子として作る。
        /// </summary>
        public static Image Box(RectTransform host, Color bg, float radius, BoxShadow? shadow = null, Color? border = null, float borderWidth = 1f)
        {
            if (shadow.HasValue) AddShadow(host, radius, shadow.Value);

            var bgImg = Image(host, "Bg", bg, radius);
            bgImg.rectTransform.Stretch();
            bgImg.IgnoreLayout();

            if (border.HasValue)
            {
                var b = Image(host, "Border", border.Value);
                SetRadius(b, radius, borderWidth);
                b.rectTransform.Stretch();
                b.IgnoreLayout();
            }
            return bgImg;
        }

        public static Image AddShadow(RectTransform host, float radius, BoxShadow s)
        {
            var img = Image(host, "Shadow", s.Color);
            img.sprite = ShadowSprite(radius, s.Blur, out float expand);
            img.type = UnityEngine.UI.Image.Type.Sliced;
            img.pixelsPerUnitMultiplier = TexScale;
            var rt = img.rectTransform;
            rt.anchorMin = Vector2.zero;
            rt.anchorMax = Vector2.one;
            rt.offsetMin = new Vector2(-expand + s.X, -expand - s.Y);
            rt.offsetMax = new Vector2(expand + s.X, expand - s.Y);
            img.IgnoreLayout();
            return img;
        }

        public static TMP_Text Label(Transform parent, string text, float size, Color color, bool bold = false, TextAnchor align = TextAnchor.MiddleLeft)
        {
            var rt = Rect("Text", parent);
            var t = rt.gameObject.AddComponent<TextMeshProUGUI>();
            if (Font != null) t.font = Font;
            t.text = text;
            t.fontSize = size;
            t.fontStyle = bold ? FontStyles.Bold : FontStyles.Normal;
            t.color = color;
            t.alignment = align == TextAnchor.MiddleCenter ? TextAlignmentOptions.Center : TextAlignmentOptions.Left;
            t.enableWordWrapping = false;
            t.overflowMode = TextOverflowModes.Overflow;
            t.richText = false;
            t.raycastTarget = false;
            t.margin = Vector4.zero;
            return t;
        }

        public static Image Icon(Transform parent, IconId id, float size, Color color)
        {
            var img = Image(parent, "Icon_" + id, color);
            img.sprite = AidyIcons.Get(id, size);
            img.type = UnityEngine.UI.Image.Type.Simple;
            img.preserveAspect = true;
            img.rectTransform.sizeDelta = new Vector2(size, size);
            img.Layout(size, size);
            return img;
        }

        // ---------------------------------------------------------------- Sprite generation

        static float RoundBoxSdf(float px, float py, float hx, float hy, float r)
        {
            float qx = Mathf.Abs(px) - (hx - r);
            float qy = Mathf.Abs(py) - (hy - r);
            float ox = Mathf.Max(qx, 0f), oy = Mathf.Max(qy, 0f);
            return Mathf.Sqrt(ox * ox + oy * oy) + Mathf.Min(Mathf.Max(qx, qy), 0f) - r;
        }

        static Texture2D NewTexture(int n)
        {
            return new Texture2D(n, n, TextureFormat.RGBA32, false)
            {
                wrapMode = TextureWrapMode.Clamp,
                filterMode = FilterMode.Bilinear,
                hideFlags = HideFlags.DontSave
            };
        }

        /// <summary>9スライス用の角丸スプライト。stroke>0 なら枠線のみ。</summary>
        public static Sprite RoundedSprite(float radius, float stroke = 0)
        {
            if (radius <= 0 && stroke <= 0) return null;
            string key = $"r{radius:F2}s{stroke:F2}";
            if (spriteCache.TryGetValue(key, out Sprite cached)) return cached;

            int R = Mathf.Max(1, Mathf.CeilToInt(radius * TexScale));
            float sw = stroke * TexScale;
            int B = Mathf.Max(R, Mathf.CeilToInt(sw) + 1);
            int N = 2 * B + 2;
            float h = N * 0.5f;
            var tex = NewTexture(N);
            var px = new Color32[N * N];
            for (int y = 0; y < N; y++)
            {
                for (int x = 0; x < N; x++)
                {
                    float d = RoundBoxSdf(x + 0.5f - h, y + 0.5f - h, h, h, Mathf.Min(R, h));
                    float a = Mathf.Clamp01(0.5f - d);
                    if (sw > 0) a *= Mathf.Clamp01(d + sw + 0.5f);
                    px[y * N + x] = new Color32(255, 255, 255, (byte)Mathf.RoundToInt(a * 255f));
                }
            }
            tex.SetPixels32(px);
            tex.Apply(false, true);
            var sprite = Sprite.Create(tex, new Rect(0, 0, N, N), new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect, new Vector4(B, B, B, B));
            sprite.hideFlags = HideFlags.DontSave;
            spriteCache[key] = sprite;
            return sprite;
        }

        /// <summary>CSS box-shadow のぼかしをガウス近似したスプライト。expand は外側へのはみ出し量 (UI単位)。</summary>
        public static Sprite ShadowSprite(float radius, float blur, out float expand)
        {
            int P = Mathf.CeilToInt(blur * TexScale) + 1;
            expand = P / TexScale;
            string key = $"sh{radius:F2}b{blur:F2}";
            if (spriteCache.TryGetValue(key, out Sprite cached)) return cached;

            int R = Mathf.Max(1, Mathf.CeilToInt(radius * TexScale));
            int B = R + P;
            int N = 2 * B + 2;
            float h = N * 0.5f;
            float inner = h - P;
            float sigma = Mathf.Max(0.5f, blur * TexScale * 0.5f);
            var tex = NewTexture(N);
            var px = new Color32[N * N];
            for (int y = 0; y < N; y++)
            {
                for (int x = 0; x < N; x++)
                {
                    float d = RoundBoxSdf(x + 0.5f - h, y + 0.5f - h, inner, inner, Mathf.Min(R, inner));
                    float a = 1f - 1f / (1f + Mathf.Exp(-1.702f * d / sigma));
                    px[y * N + x] = new Color32(255, 255, 255, (byte)Mathf.RoundToInt(Mathf.Clamp01(a) * 255f));
                }
            }
            tex.SetPixels32(px);
            tex.Apply(false, true);
            var sprite = Sprite.Create(tex, new Rect(0, 0, N, N), new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect, new Vector4(B, B, B, B));
            sprite.hideFlags = HideFlags.DontSave;
            spriteCache[key] = sprite;
            return sprite;
        }
    }

    /// <summary>CSS linear-gradient(角度, from, to) を頂点カラーで再現する。</summary>
    public class UIGradient : BaseMeshEffect
    {
        public Color From = Color.white;
        public Color To = Color.white;
        public float CssAngle = 90f; // 0deg=上向き, 90deg=右向き, 135deg=右下

        public override void ModifyMesh(VertexHelper vh)
        {
            if (!IsActive()) return;
            Rect r = graphic.rectTransform.rect;
            float a = CssAngle * Mathf.Deg2Rad;
            var dir = new Vector2(Mathf.Sin(a), Mathf.Cos(a));
            float min = float.MaxValue, max = float.MinValue;
            foreach (var c in new[] { r.min, r.max, new Vector2(r.xMin, r.yMax), new Vector2(r.xMax, r.yMin) })
            {
                float d = Vector2.Dot(c, dir);
                min = Mathf.Min(min, d);
                max = Mathf.Max(max, d);
            }
            var v = new UIVertex();
            for (int i = 0; i < vh.currentVertCount; i++)
            {
                vh.PopulateUIVertex(ref v, i);
                float t = Mathf.InverseLerp(min, max, Vector2.Dot(v.position, dir));
                Color col = Color.Lerp(From, To, t) * (Color)v.color;
                v.color = col;
                vh.SetUIVertex(v, i);
            }
        }
    }

    /// <summary>onclick + :active の scale 演出。</summary>
    public class Clickable : MonoBehaviour, IPointerClickHandler, IPointerDownHandler, IPointerUpHandler, IPointerExitHandler
    {
        public Action OnClick;
        public float PressScale = 1f;
        public bool Interactable = true;
        bool pressed;

        public void OnPointerClick(PointerEventData e)
        {
            if (Interactable && e.button == PointerEventData.InputButton.Left) OnClick?.Invoke();
        }

        public void OnPointerDown(PointerEventData e) { pressed = Interactable; }
        public void OnPointerUp(PointerEventData e) { pressed = false; }
        public void OnPointerExit(PointerEventData e) { pressed = false; }

        void Update()
        {
            if (PressScale == 1f) return;
            float target = pressed ? PressScale : 1f;
            float s = Mathf.MoveTowards(transform.localScale.x, target, Time.unscaledDeltaTime * 0.6f);
            transform.localScale = new Vector3(s, s, 1f);
        }
    }

    /// <summary>WebDemo の .switch (44x24 トグル)。</summary>
    public class AidySwitch : MonoBehaviour, IPointerClickHandler
    {
        public Action<bool> OnChanged;
        public bool IsOn { get; private set; }

        Image track;
        RectTransform knob;
        float t, from, to;
        float progress = 1f;

        public static AidySwitch Create(Transform parent, bool on, float scale = 1f)
        {
            // レイアウト上は 44x24 を占有し、見た目だけ scale する (CSS の scale と同じ)
            var holder = UI.Rect("Switch", parent);
            holder.Layout(44, 24);
            holder.sizeDelta = new Vector2(44, 24);

            var body = UI.Rect("Track", holder);
            body.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(44, 24));
            body.localScale = new Vector3(scale, scale, 1);

            var sw = holder.gameObject.AddComponent<AidySwitch>();
            sw.track = UI.Image(body, "Bg", Theme.Slate300, 12, true);
            sw.track.rectTransform.Stretch();
            var k = UI.Image(body, "Knob", Color.white, 9);
            sw.knob = k.rectTransform;
            sw.knob.Pin(new Vector2(0, 0.5f), new Vector2(3, 0), new Vector2(18, 18));
            sw.knob.pivot = new Vector2(0, 0.5f);
            sw.IsOn = on;
            sw.t = sw.from = sw.to = on ? 1f : 0f;
            sw.Apply();

            // holder 全体をタップ領域にする
            var hit = holder.gameObject.AddComponent<Image>();
            hit.color = Color.clear;
            return sw;
        }

        public void Set(bool on, bool notify)
        {
            if (on == IsOn) return;
            IsOn = on;
            from = t;
            to = on ? 1f : 0f;
            progress = 0f;
            if (notify) OnChanged?.Invoke(on);
        }

        public void OnPointerClick(PointerEventData e) { Set(!IsOn, true); }

        void Update()
        {
            if (progress >= 1f) return;
            progress = Mathf.Min(1f, progress + Time.unscaledDeltaTime / 0.3f);
            t = Mathf.Lerp(from, to, CubicBezier.Ease.Evaluate(progress));
            Apply();
        }

        void Apply()
        {
            track.color = Color.Lerp(Theme.Slate300, Theme.Primary, t);
            knob.anchoredPosition = new Vector2(3 + 20 * t, 0);
        }
    }

    /// <summary>色のトランジション (CSS transition: all 0.2s〜0.3s)。</summary>
    public class ColorTween : MonoBehaviour
    {
        Graphic g;
        Color from, to;
        float p = 1f, dur = 0.2f;

        public static void To(Graphic g, Color c, float duration)
        {
            if (!g.TryGetComponent(out ColorTween tw)) tw = g.gameObject.AddComponent<ColorTween>();
            tw.g = g;
            tw.from = g.color;
            tw.to = c;
            tw.dur = duration;
            tw.p = 0f;
        }

        void Update()
        {
            if (p >= 1f || g == null) return;
            p = Mathf.Min(1f, p + Time.unscaledDeltaTime / dur);
            g.color = Color.Lerp(from, to, CubicBezier.Ease.Evaluate(p));
        }
    }
}
