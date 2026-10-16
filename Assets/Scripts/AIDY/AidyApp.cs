using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.Rendering;
using TMPro;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// AIDY アプリ本体。WebDemo/index.html の画面構成・寸法・挙動をすべてコードで組み立てる
    /// (シーンにはこのコンポーネントを置くだけでよい)。UI の寸法は CSS px = Canvas 単位 (幅390基準)。
    /// </summary>
    public class AidyApp : MonoBehaviour
    {
        const float HeaderHeight = 50f;
        const float DotsHeight = 24f;
        const float TabBarHeight = 64f;

        static readonly string[] TabLabels = { "ホーム", "リモコン", "AR", "設定" };
        static readonly IconId[] TabIcons = { IconId.House, IconId.Gamepad, IconId.Camera, IconId.Sliders };

        Canvas canvas;
        RectTransform safeRoot;
        Rect lastSafeArea;
        AidyPager pager;
        AidyHomeRoom home;
        AidyARView ar;

        CanvasGroup prevGroup, nextGroup;
        Clickable prevClick, nextClick;
        readonly List<Dot> dots = new List<Dot>();
        readonly List<Tab> tabs = new List<Tab>();

        RectTransform canvasContainer;
        TMP_Text aiStateText;
        Image modelTagBg, modelTagIcon;
        TMP_Text modelTagText;
        readonly Dictionary<string, Chip> chips = new Dictionary<string, Chip>();
        readonly Dictionary<string, bool> partsState = new Dictionary<string, bool> { { "ears", true }, { "tail", true }, { "legs", true }, { "face", true } };
        Badge arStatus, arPos;

        class Dot { public LayoutElement Layout; public Image Image; public float T, From, To, P = 1f; }
        class Tab { public Image Bg, Icon; public TMP_Text Label; }
        class Chip { public Image Bg, Border, Icon; public TMP_Text Text; }

        class Badge
        {
            public Image Icon;
            public TMP_Text Text;

            public void Set(IconId id, Color color, float size, string text)
            {
                Icon.sprite = AidyIcons.Get(id, size);
                Icon.color = color;
                Icon.rectTransform.sizeDelta = new Vector2(size, size);
                Icon.Layout(size, size);
                Text.text = text;
            }
        }

        // ================================================================ 起動

        void Awake()
        {
            Application.targetFrameRate = 60;
            QualitySettings.vSyncCount = 0;
            Screen.sleepTimeout = SleepTimeout.NeverSleep;
            Input.multiTouchEnabled = false;

            SetupRendering();
            BuildEventSystem();
            BuildBackgroundCamera();

            home = AidyHomeRoom.Create(transform, new Vector3(1000, 0, 0));
            ar = AidyARView.Create(transform, new Vector3(2000, 0, 0));

            BuildUI();
        }

        void Start()
        {
            home.StatusChanged += s => aiStateText.text = s;
            home.ModelBadgeChanged += SetModelBadge;
            home.ReportModelState();

            ar.SetStatus = (icon, color, size, text) => arStatus.Set(icon, color, size, text);
            ar.SetPosition = text => arPos.Text.text = text;

            pager.PageChanged += OnPageChanged;
            ApplyPageUI(0, true);
            StartCoroutine(RefreshTextsNextFrame());

            string[] args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
                if (args[i] == "-aidyShots") StartCoroutine(CaptureRoutine(args[i + 1]));
        }

        // フォントアトラスの更新後に全テキストのメッシュを作り直す
        IEnumerator RefreshTextsNextFrame()
        {
            yield return null;
            foreach (var t in canvas.GetComponentsInChildren<TMP_Text>(true)) t.ForceMeshUpdate(true, true);
        }

        static void SetupRendering()
        {
            // AmbientLight(0.95) + HemisphereLight(0xffffff, 0xcbd5e1, 0.5) 相当
            RenderSettings.ambientMode = AmbientMode.Trilight;
            Color ground = Theme.Hex(0xcbd5e1);
            var sky = new Color(1.45f, 1.45f, 1.45f);
            var gnd = new Color(0.95f + 0.5f * ground.r, 0.95f + 0.5f * ground.g, 0.95f + 0.5f * ground.b);
            RenderSettings.ambientSkyColor = sky;
            RenderSettings.ambientEquatorColor = Color.Lerp(sky, gnd, 0.5f);
            RenderSettings.ambientGroundColor = gnd;
            RenderSettings.reflectionIntensity = 0f; // three.js 側は環境マップなし
            RenderSettings.fog = false;
            RenderSettings.skybox = null;

            QualitySettings.shadows = ShadowQuality.All;
            QualitySettings.shadowResolution = ShadowResolution.High;
            QualitySettings.shadowProjection = ShadowProjection.StableFit;
            QualitySettings.shadowDistance = 12f;
            QualitySettings.shadowCascades = 1;
            QualitySettings.antiAliasing = 0;
        }

        void BuildEventSystem()
        {
            if (EventSystem.current != null) return;
            var go = new GameObject("EventSystem");
            go.transform.SetParent(transform, false);
            var es = go.AddComponent<EventSystem>();
            es.pixelDragThreshold = Mathf.Max(10, Mathf.RoundToInt(Screen.dpi * 0.04f));
            go.AddComponent<StandaloneInputModule>();
        }

        void BuildBackgroundCamera()
        {
            var go = new GameObject("Background_Camera");
            go.transform.SetParent(transform, false);
            var cam = go.AddComponent<Camera>();
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = Theme.Device;
            cam.cullingMask = 0;
            cam.depth = -100;
        }

        // ================================================================ UI 構築

        void BuildUI()
        {
            var cgo = new GameObject("UI_Canvas", typeof(RectTransform));
            cgo.layer = 5;
            cgo.transform.SetParent(transform, false);
            canvas = cgo.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            var scaler = cgo.AddComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(390, 844);
            scaler.screenMatchMode = CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
            scaler.matchWidthOrHeight = 0f;
            cgo.AddComponent<GraphicRaycaster>();

            // CanvasScaler (幅390基準) と同じ倍率で角丸・影・アイコンを生成し、画面ピクセルと 1:1 にする
            UI.TexScale = Mathf.Max(1f, Screen.width / scaler.referenceResolution.x);

            safeRoot = UI.Rect("SafeArea", cgo.transform);
            ApplySafeArea();

            BuildViewport();
            BuildDots();
            BuildTabBar();
            BuildHeader();
            BuildNavArrows();

            home.BindTarget(canvasContainer.GetComponentInChildren<RawImage>());
            ar.Bind(canvas);
        }

        void ApplySafeArea()
        {
            Rect s = Screen.safeArea;
            lastSafeArea = s;
            safeRoot.anchorMin = new Vector2(s.xMin / Screen.width, s.yMin / Screen.height);
            safeRoot.anchorMax = new Vector2(s.xMax / Screen.width, s.yMax / Screen.height);
            safeRoot.offsetMin = safeRoot.offsetMax = Vector2.zero;
        }

        // ---------------------------------------------------------------- Header

        void BuildHeader()
        {
            var header = UI.Rect("Header", safeRoot);
            header.anchorMin = new Vector2(0, 1);
            header.anchorMax = new Vector2(1, 1);
            header.pivot = new Vector2(0.5f, 1);
            header.sizeDelta = new Vector2(0, HeaderHeight);
            header.anchoredPosition = Vector2.zero;
            UI.Image(header, "Bg", Theme.Device, 0, true).rectTransform.Stretch();

            var title = UI.Label(header, "AIDY", 20, Theme.TextMain, true);
            title.rectTransform.Pin(new Vector2(0, 0.5f), new Vector2(20, 0), new Vector2(120, 30));

            var gear = UI.Rect("SettingsButton", header);
            gear.Pin(new Vector2(1, 0.5f), new Vector2(-20, 0), new Vector2(36, 36));
            UI.Image(gear, "Hit", new Color(0, 0, 0, 0), 18, true).rectTransform.Stretch();
            UI.Icon(gear, IconId.Gear, 16, Theme.TextSub).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(16, 16));
            gear.gameObject.AddComponent<Clickable>().OnClick = () => pager.GoTo(3);
        }

        // ---------------------------------------------------------------- Nav arrows

        void BuildNavArrows()
        {
            prevClick = NavArrow("PrevButton", new Vector2(0, 0.5f), new Vector2(10, 0), IconId.ChevronLeft, out prevGroup);
            prevClick.OnClick = () => pager.Prev();
            nextClick = NavArrow("NextButton", new Vector2(1, 0.5f), new Vector2(-10, 0), IconId.ChevronRight, out nextGroup);
            nextClick.OnClick = () => pager.Next();
        }

        Clickable NavArrow(string name, Vector2 anchor, Vector2 pos, IconId icon, out CanvasGroup group)
        {
            var rt = UI.Rect(name, safeRoot);
            rt.Pin(anchor, pos, new Vector2(40, 40));
            UI.Box(rt, new Color(1, 1, 1, 0.85f), 20, new BoxShadow(0, 4, 12, new Color(0, 0, 0, 0.1f)), new Color(1, 1, 1, 0.9f)).raycastTarget = true;
            UI.Icon(rt, icon, 16, Theme.Slate600).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(16, 16));
            group = rt.gameObject.AddComponent<CanvasGroup>();
            return rt.gameObject.AddComponent<Clickable>();
        }

        // ---------------------------------------------------------------- Page dots

        void BuildDots()
        {
            var bar = UI.Rect("PageDots", safeRoot);
            bar.anchorMin = new Vector2(0, 0);
            bar.anchorMax = new Vector2(1, 0);
            bar.pivot = new Vector2(0.5f, 0);
            bar.sizeDelta = new Vector2(0, DotsHeight);
            bar.anchoredPosition = new Vector2(0, TabBarHeight);
            var bg = UI.Image(bar, "Bg", Theme.Device, 0, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            bar.HLayout(0, 0, 8, 8, 8, TextAnchor.MiddleCenter);

            for (int i = 0; i < 4; i++)
            {
                int page = i;
                var img = UI.Image(bar, "Dot" + i, Theme.Slate300, 4, true);
                var dot = new Dot { Image = img, Layout = img.Layout(8, 8) };
                img.gameObject.AddComponent<Clickable>().OnClick = () => pager.GoTo(page);
                dots.Add(dot);
            }
        }

        // ---------------------------------------------------------------- Bottom tab bar

        void BuildTabBar()
        {
            var bar = UI.Rect("BottomTabBar", safeRoot);
            bar.anchorMin = new Vector2(0, 0);
            bar.anchorMax = new Vector2(1, 0);
            bar.pivot = new Vector2(0.5f, 0);
            bar.sizeDelta = new Vector2(0, TabBarHeight);
            bar.anchoredPosition = Vector2.zero;
            UI.Image(bar, "Bg", Color.white, 0, true).rectTransform.Stretch();
            var line = UI.Image(bar, "BorderTop", Theme.Border);
            line.rectTransform.anchorMin = new Vector2(0, 1);
            line.rectTransform.anchorMax = new Vector2(1, 1);
            line.rectTransform.pivot = new Vector2(0.5f, 1);
            line.rectTransform.sizeDelta = new Vector2(0, 1);
            line.rectTransform.anchoredPosition = Vector2.zero;

            var row = UI.Rect("Items", bar).Stretch(10, 0, 10, 0);
            var h = row.HLayout(0, 0, 0, 0, 0, TextAnchor.MiddleCenter);
            h.childForceExpandWidth = true;

            for (int i = 0; i < 4; i++)
            {
                int page = i;
                var slot = UI.Rect("Slot" + i, row);
                slot.Layout(-1, TabBarHeight, 1);

                var item = UI.Rect("Tab" + i, slot);
                item.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero);
                var bg = UI.Image(item, "Bg", new Color(1, 1, 1, 0), 16, true);
                bg.rectTransform.Stretch();
                bg.IgnoreLayout();
                item.VLayout(16, 16, 6, 6, 4, TextAnchor.MiddleCenter);
                item.FitContent();
                var icon = UI.Icon(item, TabIcons[i], 18, Theme.Slate400);
                var label = UI.Label(item, TabLabels[i], 11, Theme.Slate400, false, TextAnchor.MiddleCenter);
                item.gameObject.AddComponent<Clickable>().OnClick = () => pager.GoTo(page);
                tabs.Add(new Tab { Bg = bg, Icon = icon, Label = label });
            }
        }

        // ---------------------------------------------------------------- Viewport + pages

        void BuildViewport()
        {
            var vp = UI.Rect("Viewport", safeRoot).Stretch(0, HeaderHeight, 0, DotsHeight + TabBarHeight);
            var hit = vp.gameObject.AddComponent<Image>();
            hit.color = new Color(0, 0, 0, 0);
            vp.gameObject.AddComponent<RectMask2D>();

            var content = UI.Rect("ScrollContent", vp);
            var pages = new RectTransform[4];
            for (int i = 0; i < 4; i++) pages[i] = UI.Rect("Page" + i, content);

            pager = vp.gameObject.AddComponent<AidyPager>();
            pager.Viewport = vp;
            pager.Content = content;
            pager.Pages = pages;

            BuildHomePage(pages[0]);
            BuildRemotePage(pages[1]);
            BuildARPage(pages[2]);
            BuildSettingsPage(pages[3]);
        }

        RectTransform ScrollPage(RectTransform page)
        {
            UI.Image(page, "Bg", Theme.Device, 0, true).rectTransform.Stretch();
            page.gameObject.AddComponent<RectMask2D>();
            var content = UI.Rect("Content", page);
            content.anchorMin = new Vector2(0, 1);
            content.anchorMax = new Vector2(1, 1);
            content.pivot = new Vector2(0.5f, 1);
            content.sizeDelta = Vector2.zero;
            var v = content.VLayout(20, 20, 10, 20, 0);
            v.childForceExpandWidth = true;
            content.FitContent(false, true);

            var sr = page.gameObject.AddComponent<NestedScrollRect>();
            sr.content = content;
            sr.viewport = page;
            sr.horizontal = false;
            sr.vertical = true;
            sr.movementType = ScrollRect.MovementType.Elastic;
            sr.scrollSensitivity = 20f;
            return content;
        }

        // ---------------------------------------------------------------- PAGE 0: ホーム (3D ペットルーム)

        void BuildHomePage(RectTransform page)
        {
            var c = ScrollPage(page);

            // battery-widget
            var row = UI.Rect("BatteryRow", c);
            row.HLayout(0, 0, 0, 0, 0, TextAnchor.MiddleCenter);
            var bw = UI.Rect("BatteryWidget", row);
            UI.Box(bw, new Color(1, 1, 1, 0.9f), 20, new BoxShadow(0, 4, 15, new Color(0, 0, 0, 0.04f)), Theme.Hex(0xe2e8f0, 0.8f));
            bw.HLayout(18, 18, 8, 8, 12, TextAnchor.MiddleCenter);
            var bar = UI.Rect("BatteryBar", bw);
            bar.Layout(110, 14);
            UI.Image(bar, "Track", Theme.Border, 7).rectTransform.Stretch();
            var fill = UI.Image(bar, "Fill", Color.white, 7);
            fill.rectTransform.anchorMin = Vector2.zero;
            fill.rectTransform.anchorMax = new Vector2(0.78f, 1);
            fill.rectTransform.offsetMin = fill.rectTransform.offsetMax = Vector2.zero;
            var g = fill.gameObject.AddComponent<UIGradient>();
            g.From = Theme.Hex(0x10b981);
            g.To = Theme.Hex(0x34d399);
            g.CssAngle = 90;
            UI.Label(bw, "78%", 13, Theme.Slate600, true);

            UI.Spacer(c, 6 + 4);

            // canvas-container (3D)
            canvasContainer = UI.Rect("CanvasContainer", c);
            canvasContainer.Layout(-1, 330);
            var mask = UI.Image(canvasContainer, "Mask", Theme.Hex(0xf1f5f9), 24, true);
            mask.rectTransform.Stretch();
            mask.gameObject.AddComponent<Mask>().showMaskGraphic = true;
            var raw = UI.Rect("ThreeCanvas", mask.transform).Stretch().gameObject.AddComponent<RawImage>();
            raw.color = Color.white;
            var touch = raw.gameObject.AddComponent<AidyHomeTouch>();
            touch.Room = home;
            touch.OnTap = PetRobot;

            // ai-status-bubble
            var bub = UI.Rect("AIStatus", canvasContainer);
            bub.Pin(new Vector2(0, 1), new Vector2(12, -12), Vector2.zero);
            UI.Box(bub, new Color(1, 1, 1, 0.88f), 16, new BoxShadow(0, 4, 12, new Color(0, 0, 0, 0.05f)));
            bub.HLayout(12, 12, 6, 6, 6);
            bub.FitContent();
            UI.Icon(bub, IconId.Brain, 11, Theme.Primary);
            aiStateText = UI.Label(bub, "本体v1モデル 描画完了", 11, Theme.Primary, true);

            // model-tag
            var tag = UI.Rect("ModelTag", canvasContainer);
            tag.Pin(new Vector2(1, 1), new Vector2(-12, -12), Vector2.zero);
            modelTagBg = UI.Box(tag, Theme.Hex(0x10b981, 0.9f), 12);
            tag.HLayout(10, 10, 4, 4, 4);
            tag.FitContent();
            modelTagIcon = UI.Icon(tag, IconId.Check, 10, Color.white);
            modelTagText = UI.Label(tag, "本体v1_モデル (鋼サテン)", 10, Color.white, true);

            // rotate-hint
            var hint = UI.Rect("RotateHint", canvasContainer);
            hint.Pin(new Vector2(0.5f, 0), new Vector2(0, 10), Vector2.zero);
            UI.Box(hint, new Color(1, 1, 1, 0.8f), 12);
            hint.HLayout(12, 12, 4, 4, 8);
            hint.FitContent();
            UI.Icon(hint, IconId.Paw, 11, Theme.TextSub);
            UI.Label(hint, "タップしてナデナデ / ドラッグ回転", 11, Theme.TextSub, true);

            UI.Spacer(c, 4);

            // debug-panel (モジュールパーツ合体・着脱コントロール)
            var dp = UI.Rect("DebugPanel", c);
            UI.Box(dp, Color.white, 18, new BoxShadow(0, 4, 16, new Color(0, 0, 0, 0.04f)), Theme.Border);
            dp.VLayout(14, 14, 12, 12, 0).childForceExpandWidth = true;

            var title = UI.Rect("Title", dp);
            title.HLayout(0, 0, 0, 0, 0);
            title.Layout(-1, 24);
            var tl = UI.Rect("Label", title);
            tl.HLayout(0, 0, 0, 0, 4);
            tl.Layout(-1, -1, 1);
            UI.Icon(tl, IconId.Cubes, 12, Theme.Primary);
            UI.Label(tl, "モジュールモデル合体設計", 12, Theme.TextSub, true);
            var aiToggle = AidySwitch.Create(title, true, 0.85f);
            aiToggle.OnChanged = on => home.ToggleAIWalk(on);

            UI.Spacer(dp, 8);
            var row1 = UI.Rect("Row1", dp);
            row1.HLayout(0, 0, 0, 0, 8).childForceExpandWidth = true;
            AddChip(row1, "ears", "耳モジュール");
            AddChip(row1, "tail", "尻尾モジュール");
            UI.Spacer(dp, 8);
            var row2 = UI.Rect("Row2", dp);
            row2.HLayout(0, 0, 0, 0, 8).childForceExpandWidth = true;
            AddChip(row2, "legs", "足モジュール");
            AddChip(row2, "face", "カメラディスプレイ");

            UI.Spacer(dp, 8);
            var btn = UI.Rect("PetButton", dp);
            btn.Layout(-1, 38);
            UI.AddShadow(btn, 12, new BoxShadow(0, 4, 12, Theme.Hex(0xec4899, 0.3f)));
            var bbg = UI.Image(btn, "Bg", Color.white, 12, true);
            bbg.rectTransform.Stretch();
            bbg.IgnoreLayout();
            var pg = bbg.gameObject.AddComponent<UIGradient>();
            pg.From = Theme.Hex(0xec4899);
            pg.To = Theme.Hex(0xf43f5e);
            pg.CssAngle = 135;
            btn.HLayout(0, 0, 0, 0, 6, TextAnchor.MiddleCenter);
            UI.Icon(btn, IconId.Heart, 12, Color.white);
            UI.Label(btn, "本体v1ロボとなでなで遊ぶ", 12, Color.white, true);
            var bc = btn.gameObject.AddComponent<Clickable>();
            bc.PressScale = 0.97f;
            bc.OnClick = PetRobot;
        }

        void AddChip(RectTransform row, string key, string label)
        {
            var ch = UI.Rect("Chip_" + key, row);
            ch.Layout(0, 36, 1); // grid-template-columns: 1fr 1fr (文字数に関係なく等幅)
            var bg = UI.Image(ch, "Bg", Theme.PrimaryLight, 12, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            var border = UI.Image(ch, "Border", Theme.Primary);
            UI.SetRadius(border, 12, 1.5f);
            border.rectTransform.Stretch();
            border.IgnoreLayout();
            ch.HLayout(12, 12, 8, 8, 0);
            var text = UI.Label(ch, label, 12, Theme.Primary, true);
            text.Layout(-1, -1, 1);
            var icon = UI.Icon(ch, IconId.Check, 12, Theme.Primary);
            chips[key] = new Chip { Bg = bg, Border = border, Icon = icon, Text = text };
            ch.gameObject.AddComponent<Clickable>().OnClick = () => TogglePart(key);
        }

        void TogglePart(string part)
        {
            bool on = partsState[part] = !partsState[part];
            home.SetPart(part, on);
            Chip c = chips[part];
            ColorTween.To(c.Bg, on ? Theme.PrimaryLight : Theme.Slate100, 0.2f);
            ColorTween.To(c.Border, on ? Theme.Primary : new Color(Theme.Primary.r, Theme.Primary.g, Theme.Primary.b, 0f), 0.2f);
            ColorTween.To(c.Text, on ? Theme.Primary : Theme.Slate700, 0.2f);
            ColorTween.To(c.Icon, on ? Theme.Primary : Theme.Slate700, 0.2f);
            c.Icon.sprite = AidyIcons.Get(on ? IconId.Check : IconId.XMark, 12);
        }

        void PetRobot()
        {
            home.PetRobotDirectly();
            var heart = UI.Icon(canvasContainer, IconId.Heart, 24, Theme.Hex(0xf472b6));
            Vector2 size = canvasContainer.rect.size;
            heart.rectTransform.anchorMin = heart.rectTransform.anchorMax = new Vector2(0, 1);
            heart.rectTransform.pivot = new Vector2(0.5f, 0.5f);
            heart.rectTransform.anchoredPosition = new Vector2(size.x / 2f, -(size.y / 2f - 20f + 16f));
            heart.gameObject.AddComponent<HeartPop>();
        }

        void SetModelBadge(bool ok, string text)
        {
            modelTagBg.color = ok ? Theme.Hex(0x10b981, 0.9f) : Theme.Hex(0xef4444, 0.92f);
            modelTagIcon.sprite = AidyIcons.Get(ok ? IconId.Check : IconId.XMark, 10);
            modelTagText.text = text;
        }

        // ---------------------------------------------------------------- PAGE 1: リモコン・モジュール

        void BuildRemotePage(RectTransform page)
        {
            var c = ScrollPage(page);
            SectionTitle(c, "標準モジュール (モデル合体済み)", true);
            var card = Card(c, IconId.Snowflake, Theme.Primary, Theme.Slate100, "リビングエアコン", "温度: 24°C / 自動");
            AidySwitch.Create(card, true);
            card = Card(c, IconId.Lightbulb, Theme.Primary, Theme.Slate100, "メイン照明", "オン / 100% 輝度");
            AidySwitch.Create(card, true);
            card = Card(c, IconId.Plug, Theme.Primary, Theme.Slate100, "スマートプラグ", "オフ / タイマー設定");
            AidySwitch.Create(card, false);

            SectionTitle(c, "拡張モジュール (追加3Dモデル連携)", false);
            card = Card(c, IconId.Video, Theme.Hex(0x8b5cf6), Theme.Hex(0xf3e8ff), "カメラフィードモジュール", "拡張OBJモデル接続可能");
            UI.Icon(card, IconId.Plus, 16, Theme.Primary);
            card = Card(c, IconId.Microchip, Theme.Hex(0xec4899), Theme.Hex(0xfce7f3), "センサーモジュール", "温度/湿度 拡張モジュール");
            UI.Icon(card, IconId.Plus, 16, Theme.Primary);
        }

        // ---------------------------------------------------------------- PAGE 2: AR

        void BuildARPage(RectTransform page)
        {
            var black = UI.Image(page, "Black", Color.black, 0, true);
            black.rectTransform.Stretch();

            var camArea = UI.Rect("WebcamArea", page).Stretch();
            camArea.gameObject.AddComponent<RectMask2D>();
            var webcam = UI.Rect("WebcamVideo", camArea).gameObject.AddComponent<RawImage>();
            webcam.rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero);
            webcam.raycastTarget = false;
            webcam.enabled = false;

            var overlay = UI.Rect("ARCanvas", page).Stretch().gameObject.AddComponent<RawImage>();
            overlay.raycastTarget = false;

            var band = UI.Rect("TouchBand", page).Stretch(0, 70, 0, 80);
            band.gameObject.AddComponent<Image>().color = new Color(0, 0, 0, 0);
            band.gameObject.AddComponent<AidyARTouch>().View = ar;

            var top = UI.Rect("AROverlay", page);
            top.anchorMin = new Vector2(0, 1);
            top.anchorMax = new Vector2(1, 1);
            top.pivot = new Vector2(0.5f, 1);
            top.offsetMin = new Vector2(20, 0);
            top.offsetMax = new Vector2(-20, -20);
            top.VLayout(0, 0, 0, 0, 8);
            top.FitContent(false, true);
            arStatus = ARBadge(top, Theme.Hex(0x0f172a, 0.8f), IconId.HandPointer, Color.white, "カメラ越しに机や床をクリックして配置");
            arPos = ARBadge(top, Theme.Hex(0x3b82f6, 0.85f), IconId.MapPin, Theme.Hex(0xef4444), "基準位置: (0.0, 0.0) 本体v1_モデル配置完了");

            var bottom = UI.Rect("ARBottomControls", page);
            bottom.anchorMin = new Vector2(0, 0);
            bottom.anchorMax = new Vector2(1, 0);
            bottom.pivot = new Vector2(0.5f, 0);
            bottom.offsetMin = new Vector2(20, 20);
            bottom.offsetMax = new Vector2(-20, 68);
            bottom.HLayout(0, 0, 0, 0, 12, TextAnchor.MiddleCenter);
            ARButton(bottom, IconId.Video, "Webカメラ起動", true).OnClick = () => ar.StartCamera();
            ARButton(bottom, IconId.Crosshairs, "中央に再配置", false).OnClick = () => ar.ResetPosition();

            ar.Page = page;
            ar.Black = black;
            ar.WebcamArea = camArea;
            ar.WebcamImage = webcam;
            ar.OverlayImage = overlay;
        }

        Badge ARBadge(RectTransform parent, Color bg, IconId icon, Color iconColor, string text)
        {
            var b = UI.Rect("ARBadge", parent);
            UI.Box(b, bg, 20, null, new Color(1, 1, 1, 0.2f)).raycastTarget = true;
            b.HLayout(14, 14, 8, 8, 8);
            var badge = new Badge
            {
                Icon = UI.Icon(b, icon, 12, iconColor),
                Text = UI.Label(b, text, 12, Color.white, true)
            };
            return badge;
        }

        Clickable ARButton(RectTransform parent, IconId icon, string text, bool primary)
        {
            var b = UI.Rect("ARButton", parent);
            b.Layout(-1, 48, 1);
            UI.AddShadow(b, 24, new BoxShadow(0, 8, 20, new Color(0, 0, 0, 0.3f)));
            var bg = UI.Image(b, "Bg", primary ? Theme.Primary : new Color(1, 1, 1, 0.9f), 24, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            b.HLayout(0, 0, 0, 0, 8, TextAnchor.MiddleCenter);
            Color fg = primary ? Color.white : Theme.Slate900;
            UI.Icon(b, icon, 13, fg);
            UI.Label(b, text, 13, fg, true);
            var c = b.gameObject.AddComponent<Clickable>();
            c.PressScale = 0.97f;
            return c;
        }

        // ---------------------------------------------------------------- PAGE 3: 設定

        void BuildSettingsPage(RectTransform page)
        {
            var c = ScrollPage(page);
            SectionTitle(c, "ロボット設定", true);
            var card = Card(c, null, default, default, "アセンブリ構造", "本体v1_モデル.obj (透明化防止完全対応)");
            UI.Icon(card, IconId.Check, 14, Theme.Green);
            card = Card(c, null, default, default, "スピーカー音量", "効果音 & 音声応答");
            UI.Label(card, "60%", 13, Theme.Primary, true);

            SectionTitle(c, "操作 & ネットワーク", false);
            card = Card(c, null, default, default, "モジュール自動合体", "パーツSocketアタッチ機能 (ON)");
            AidySwitch.Create(card, true);
            card = Card(c, null, default, default, "Wi-Fi 接続", "AIDY-5G-2.4G (接続済み)");
            UI.Icon(card, IconId.Wifi, 16, Theme.Green);
        }

        // ---------------------------------------------------------------- 共通: section-title / card-item

        static void SectionTitle(RectTransform parent, string text, bool first)
        {
            // margin: 16px 0 8px 4px (直前の card の margin-bottom 10px と相殺)
            UI.Spacer(parent, first ? 16 : 6);
            var row = UI.Rect("SectionTitle", parent);
            row.HLayout(4, 0, 0, 0, 0);
            UI.Label(row, text, 14, Theme.TextSub, true);
            UI.Spacer(parent, 8);
        }

        static RectTransform Card(RectTransform parent, IconId? icon, Color iconColor, Color iconBg, string title, string sub)
        {
            var card = UI.Rect("CardItem", parent);
            UI.Box(card, Color.white, 16, new BoxShadow(0, 2, 8, new Color(0, 0, 0, 0.03f)), Theme.Slate100);
            card.HLayout(16, 16, 14, 14, 14);

            if (icon.HasValue)
            {
                var ic = UI.Rect("CardIcon", card);
                ic.Layout(42, 42);
                UI.Image(ic, "Bg", iconBg, 12).rectTransform.Stretch();
                UI.Icon(ic, icon.Value, 18, iconColor).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(18, 18));
            }

            var info = UI.Rect("CardInfo", card);
            info.VLayout(0, 0, 0, 0, 2);
            info.Layout(-1, -1, 1);
            UI.Label(info, title, 14, Theme.TextMain, true);
            UI.Label(info, sub, 12, Theme.TextSub);

            UI.Spacer(parent, 10);
            return card;
        }

        // ================================================================ ページ切替 (updatePageUI)

        void OnPageChanged(int page)
        {
            ApplyPageUI(page, false);
            if (page == 2) ar.StartCamera();
        }

        void ApplyPageUI(int page, bool instant)
        {
            SetArrow(prevGroup, prevClick, page != 0);
            SetArrow(nextGroup, nextClick, page != pager.PageCount - 1);

            for (int i = 0; i < dots.Count; i++)
            {
                Dot d = dots[i];
                d.From = d.T;
                d.To = i == page ? 1f : 0f;
                d.P = 0f;
                if (instant)
                {
                    d.T = d.To;
                    d.P = 1f;
                    ApplyDot(d);
                }
            }

            for (int i = 0; i < tabs.Count; i++)
            {
                bool active = i == page;
                Tab t = tabs[i];
                Color fg = active ? Theme.Primary : Theme.Slate400;
                Color bg = active ? Theme.PrimaryLight : new Color(1, 1, 1, 0);
                if (instant)
                {
                    t.Bg.color = bg;
                    t.Icon.color = fg;
                    t.Label.color = fg;
                }
                else
                {
                    ColorTween.To(t.Bg, bg, 0.2f);
                    ColorTween.To(t.Icon, fg, 0.2f);
                    ColorTween.To(t.Label, fg, 0.2f);
                }
            }
        }

        static void SetArrow(CanvasGroup g, Clickable c, bool enabled)
        {
            g.alpha = enabled ? 1f : 0.3f;
            g.blocksRaycasts = enabled;
            c.Interactable = enabled;
        }

        static void ApplyDot(Dot d)
        {
            d.Layout.preferredWidth = d.Layout.minWidth = 8f + 16f * d.T;
            d.Image.color = Color.Lerp(Theme.Slate300, Theme.Primary, d.T);
        }

        // ================================================================ 毎フレーム

        void Update()
        {
            if (Screen.safeArea != lastSafeArea) ApplySafeArea();

            foreach (Dot d in dots)
            {
                if (d.P >= 1f) continue;
                d.P = Mathf.Min(1f, d.P + Time.unscaledDeltaTime / 0.3f);
                d.T = Mathf.Lerp(d.From, d.To, CubicBezier.Ease.Evaluate(d.P));
                ApplyDot(d);
            }

            // 見えているページだけカメラを回す (AI/アニメーションは常に動作)
            home.Cam.enabled = pager.IsVisible(0);
            ar.PageVisible = pager.IsVisible(2);

            // Android の戻るボタン
            if (Input.GetKeyDown(KeyCode.Escape))
            {
                if (pager.Current != 0) pager.GoTo(0);
                else Application.Quit();
            }
        }

        // ================================================================ 検証用スクリーンショット (-aidyShots <dir>)

        IEnumerator CaptureRoutine(string dir)
        {
            Directory.CreateDirectory(dir);
            yield return new WaitForSeconds(2.5f);
            yield return Capture(dir, "page0_home");
            PetRobot();
            yield return new WaitForSeconds(0.45f);
            yield return Capture(dir, "page0_pet");
            TogglePart("ears");
            TogglePart("legs");
            yield return new WaitForSeconds(0.5f);
            yield return Capture(dir, "page0_parts_off");
            TogglePart("ears");
            TogglePart("legs");
            foreach (int p in new[] { 1, 3 }) // AR 画面はカメラ映像が写るので撮らない
            {
                pager.GoTo(p);
                yield return new WaitForSeconds(p == 2 ? 3f : 1f);
                yield return Capture(dir, "page" + p);
            }
            pager.GoTo(0);
            yield return new WaitForSeconds(1f);
            Application.Quit();
        }

        static IEnumerator Capture(string dir, string name)
        {
            yield return new WaitForEndOfFrame();
            ScreenCapture.CaptureScreenshot(Path.Combine(dir, name + ".png"));
            yield return null;
            yield return null;
        }
    }

    /// <summary>.heart-popup { animation: floatUp 1.2s ease-out }</summary>
    public class HeartPop : MonoBehaviour
    {
        float t;
        RectTransform rt;
        Graphic g;
        Vector2 basePos;

        void Start()
        {
            rt = (RectTransform)transform;
            g = GetComponent<Graphic>();
            basePos = rt.anchoredPosition;
            Apply();
        }

        void Update()
        {
            t += Time.deltaTime / 1.2f;
            if (t >= 1f)
            {
                Destroy(gameObject);
                return;
            }
            Apply();
        }

        void Apply()
        {
            float y, s, a;
            if (t < 0.5f)
            {
                float p = CubicBezier.EaseOut.Evaluate(t / 0.5f);
                y = Mathf.Lerp(0, 30, p);
                s = Mathf.Lerp(0.5f, 1.2f, p);
                a = 1f;
            }
            else
            {
                float p = CubicBezier.EaseOut.Evaluate((t - 0.5f) / 0.5f);
                y = Mathf.Lerp(30, 60, p);
                s = Mathf.Lerp(1.2f, 1f, p);
                a = 1f - p;
            }
            rt.anchoredPosition = basePos + new Vector2(0, y);
            rt.localScale = new Vector3(s, s, 1);
            var c = g.color;
            c.a = a;
            g.color = c;
        }
    }
}
