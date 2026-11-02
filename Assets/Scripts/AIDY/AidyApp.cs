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

        const int PageHome = 0, PageDiary = 1, PageRemote = 2, PageAR = 3, PageSettings = 4;
        const int PageCount = 5;
        // 設定はタブバーに出さず、ヘッダーのギアアイコンからのみ開く
        static readonly string[] TabLabels = { "ホーム", "日記", "リモコン", "AR" };
        static readonly IconId[] TabIcons = { IconId.Aidy, IconId.Book, IconId.Gamepad, IconId.Camera };

        Canvas canvas;
        RectTransform safeRoot;
        Rect lastSafeArea;
        AidyPager pager;
        AidyHomeRoom home, editRoom;
        AidyARView ar;
        AidyDiaryPage diary;

        CanvasGroup prevGroup, nextGroup;
        Clickable prevClick, nextClick;
        readonly List<Dot> dots = new List<Dot>();
        readonly List<Tab> tabs = new List<Tab>();

        RectTransform canvasContainer;
        RawImage editImage;
        TMP_Text aiStateText, editStateText, moduleSummary;
        AidyMascot statusMascot, editMascot;
        AidyToast homeToast, arToast;
        AidyModuleTray moduleTray;
        AidyMarkerSheet markerSheet;
        Badge arStatus;

        // AR タブ: 「画面上」(付け替えプレビュー) と「AR」(カメラ) の切り替え
        bool arMode;
        GameObject arCameraUI, arOnScreenUI;
        Image segOnScreenBg, segArBg;
        TMP_Text segOnScreenLabel, segArLabel;

        class Dot { public LayoutElement Layout; public Image Image; public float T, From, To, P = 1f; }
        class Tab { public Image Bg, Icon; public TMP_Text Label; }

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
            Input.multiTouchEnabled = true; // 3D モデルのピンチズーム

            SetupRendering();
            BuildEventSystem();
            BuildBackgroundCamera();

            home = AidyHomeRoom.Create(transform, new Vector3(1000, 0, 0));
            ar = AidyARView.Create(transform, new Vector3(2000, 0, 0));
            editRoom = AidyHomeRoom.Create(transform, new Vector3(3000, 0, 0), true);

            BuildUI();
            markerSheet = AidyMarkerSheet.Build(safeRoot);
        }

        void Start()
        {
            home.StatusChanged += s => aiStateText.text = s;
            home.ModelBadgeChanged += (ok, text) => homeToast.Show(ok ? AidyFace.Happy : AidyFace.Sleepy, text, ok ? 2.8f : 0f);
            home.FaceReaction += (face, seconds) => statusMascot.React(face, seconds);
            home.ReportModelState();
            editRoom.StatusChanged += s => editStateText.text = s;
            editRoom.FaceReaction += (face, seconds) => editMascot.React(face, seconds);
            ModuleConfig.Changed += (slot, removed, added) => UpdateModuleSummary();
            UpdateModuleSummary();

            ar.SetStatus = (icon, color, size, text) => arStatus.Set(icon, color, size, text);
            ar.SetPosition = OnARPlaced;
            ar.Hint = text => arToast.Show(AidyFace.Normal, text, 2.8f);
            SetARMode(false);

            pager.PageChanged += OnPageChanged;
            ApplyPageUI(0, true);
            StartCoroutine(RefreshTextsNextFrame());

            string[] args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (args[i] == "-aidyShots") StartCoroutine(CaptureRoutine(args[i + 1]));
                if (args[i] == "-aidyMarkerTest" && i + 2 < args.Length) StartCoroutine(MarkerTestRoutine(args[i + 1], args[i + 2]));
                if (args[i] == "-aidyMarkerSheet") StartCoroutine(MarkerSheetRoutine(args[i + 1]));
                if (args[i] == "-aidyARPlacement") StartCoroutine(ARPlacementRoutine(args[i + 1]));
                if (args[i] == "-aidyFaces") StartCoroutine(FacesRoutine(args[i + 1]));
            }
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
            editRoom.BindTarget(editImage);
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

            var brand = UI.Rect("Brand", header);
            brand.Pin(new Vector2(0, 0.5f), new Vector2(16, 0), Vector2.zero);
            brand.HLayout(0, 0, 0, 0, 8);
            brand.FitContent();
            // ロゴ画像 (Resources/AIDY/aidy_logo_dark = 黒文字・明るい背景用)。無ければ従来のマスコット + 文字
            var logo = Resources.Load<Texture2D>("AIDY/aidy_logo_dark");
            if (logo != null)
            {
                var img = UI.Rect("Logo", brand).gameObject.AddComponent<RawImage>();
                img.texture = logo;
                img.raycastTarget = false;
                const float LogoHeight = 26f;
                img.Layout(LogoHeight * logo.width / logo.height, LogoHeight);
            }
            else
            {
                AidyMascot.Create(brand, 30, AidyFace.Normal, true, true);
                UI.Label(brand, "AIDY", 20, Theme.TextMain, true);
            }

            var right = UI.Rect("Right", header);
            right.Pin(new Vector2(1, 0.5f), new Vector2(-10, 0), Vector2.zero);
            right.HLayout(0, 0, 0, 0, 6, TextAnchor.MiddleRight);
            right.FitContent();

            var gear = UI.Rect("SettingsButton", right);
            gear.Layout(44, 44);
            UI.Image(gear, "Hit", new Color(0, 0, 0, 0), 22, true).rectTransform.Stretch();
            UI.Icon(gear, IconId.Gear, 24, Theme.TextSub).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(24, 24));
            gear.gameObject.AddComponent<Clickable>().OnClick = () => pager.GoTo(PageSettings);
        }

        /// <summary>本体の充電量 (本体との通信ができたら実際の値に置き換える)。</summary>
        const float BatteryLevel = 0.78f;

        /// <summary>スマホのステータスバーのような電池表示: 「78%」+ 電池アイコン。</summary>
        static void BuildBattery(RectTransform parent, float level)
        {
            var row = UI.Rect("Battery", parent);
            row.HLayout(0, 0, 0, 0, 3, TextAnchor.MiddleCenter);
            UI.Label(row, Mathf.RoundToInt(level * 100f) + "%", 12, Theme.Slate600, true);
            var glyph = UI.Rect("Glyph", row);
            glyph.Layout(26, 26);
            UI.Icon(glyph, IconId.Battery, 26, Theme.Slate600).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(26, 26));
            // アイコン (24 グリッド) の内側 x:3.1〜18.9, y:8.1〜15.9 を残量に応じて塗る
            float u = 26f / 24f;
            var fill = UI.Image(glyph, "Fill", level > 0.2f ? Theme.Green : Theme.Hex(0xef4444), 1.2f);
            fill.rectTransform.anchorMin = fill.rectTransform.anchorMax = new Vector2(0, 0.5f);
            fill.rectTransform.pivot = new Vector2(0, 0.5f);
            fill.rectTransform.anchoredPosition = new Vector2(3.6f * u, 0);
            fill.rectTransform.sizeDelta = new Vector2(14.8f * u * Mathf.Clamp01(level), 6.6f * u);
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

            for (int i = 0; i < TabLabels.Length; i++)
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
            // 背景はセーフエリアの下 (ホームバーの裏) まで伸ばす
            RectTransform barBg = UI.Image(bar, "Bg", Color.white, 0, true).rectTransform.Stretch();
            barBg.offsetMin = new Vector2(0, -200);
            var line = UI.Image(bar, "BorderTop", Theme.Border);
            line.rectTransform.anchorMin = new Vector2(0, 1);
            line.rectTransform.anchorMax = new Vector2(1, 1);
            line.rectTransform.pivot = new Vector2(0.5f, 1);
            line.rectTransform.sizeDelta = new Vector2(0, 1);
            line.rectTransform.anchoredPosition = Vector2.zero;

            var row = UI.Rect("Items", bar).Stretch(10, 0, 10, 0);
            var h = row.HLayout(0, 0, 0, 0, 0, TextAnchor.MiddleCenter);
            h.childForceExpandWidth = true;

            for (int i = 0; i < TabLabels.Length; i++)
            {
                int page = i;
                var slot = UI.Rect("Slot" + i, row);
                slot.Layout(-1, TabBarHeight, 1);

                var item = UI.Rect("Tab" + i, slot);
                item.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero);
                var bg = UI.Image(item, "Bg", new Color(1, 1, 1, 0), 16, true);
                bg.rectTransform.Stretch();
                bg.IgnoreLayout();
                item.VLayout(10, 10, 6, 6, 4, TextAnchor.MiddleCenter);
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
            var pages = new RectTransform[PageCount];
            for (int i = 0; i < pages.Length; i++) pages[i] = UI.Rect("Page" + i, content);

            pager = vp.gameObject.AddComponent<AidyPager>();
            pager.Viewport = vp;
            pager.Content = content;
            pager.Pages = pages;

            BuildHomePage(pages[PageHome]);
            BuildDiaryPage(pages[PageDiary]);
            BuildRemotePage(pages[PageRemote]);
            BuildARPage(pages[PageAR]);
            BuildSettingsPage(pages[PageSettings]);
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
            // 1 画面で完結 (スクロールなし): 3D はページいっぱい (余白・角丸なし)、構成のまとめはその上に浮かべる
            UI.Image(page, "Bg", Theme.Device, 0, true).rectTransform.Stretch();
            const float SummaryHeight = 58f, SummaryMargin = 12f;
            canvasContainer = View3D(page, home, out aiStateText, out statusMascot, "タップでなでなで ・ ドラッグで回転 ・ ピンチでズーム", out _,
                                     0f, SummaryHeight + SummaryMargin * 2f, 0f, true);
            canvasContainer.Stretch();
            home.ViewInsetBottom = SummaryHeight + SummaryMargin * 2f;
            homeToast = AidyToast.Create(canvasContainer, 52);

            // いまのモジュール + お散歩 + 付け替えへ
            var row = UI.Rect("ModuleSummary", page);
            row.anchorMin = new Vector2(0, 0);
            row.anchorMax = new Vector2(1, 0);
            row.pivot = new Vector2(0.5f, 0);
            row.offsetMin = new Vector2(16, SummaryMargin);
            row.offsetMax = new Vector2(-16, SummaryMargin + SummaryHeight);
            UI.Box(row, new Color(1, 1, 1, 0.94f), 18, new BoxShadow(0, 4, 16, new Color(0, 0, 0, 0.08f)), Theme.Border).raycastTarget = true;
            row.HLayout(14, 10, 8, 8, 8);
            UI.Icon(row, IconId.Cubes, 14, Theme.Primary);
            var info = UI.Rect("Info", row);
            info.VLayout(0, 0, 0, 0, 1).childForceExpandWidth = true;
            info.Layout(0, -1, 1);
            UI.Label(info, "いまのモジュール", 10, Theme.TextSub, true);
            moduleSummary = UI.Label(info, "", 12, Theme.TextMain, true);
            moduleSummary.overflowMode = TextOverflowModes.Ellipsis;
            UI.Label(row, "お散歩", 10, Theme.Slate400, true);
            AidySwitch.Create(row, home.AIEnabled, 0.8f).OnChanged = on => home.ToggleAIWalk(on);
            var edit = UI.Rect("EditButton", row);
            UI.Box(edit, Theme.Primary, 15).raycastTarget = true;
            edit.HLayout(12, 10, 7, 7, 4, TextAnchor.MiddleCenter);
            UI.Label(edit, "付け替え", 12, Color.white, true);
            UI.Icon(edit, IconId.ChevronRight, 10, Color.white);
            var ec = edit.gameObject.AddComponent<Clickable>();
            ec.PressScale = 0.95f;
            ec.OnClick = () =>
            {
                SetARMode(false);
                pager.GoTo(PageAR);
            };
        }

        /// <summary>
        /// 3D ビュー (角丸マスク + RawImage + 操作 + 状態の吹き出し + 操作ヒント + ズームボタン)。
        /// 状態の吹き出しの文字とデフォルメ Aidy を返す。radius 0 で角丸なし (全画面)、
        /// bottomInset / topInset は上下に重ねる UI の分だけヒント・ズームボタン / 吹き出しをずらす。
        /// battery なら吹き出しの横に本体の充電残量を出す (ホーム)。
        /// </summary>
        RectTransform View3D(RectTransform parent, AidyHomeRoom room, out TMP_Text stateText, out AidyMascot mascot, string hintText, out AidyHomeTouch touch,
                             float radius = 24f, float bottomInset = 0f, float topInset = 0f, bool battery = false)
        {
            var box = UI.Rect("CanvasContainer", parent);
            box.Layout(-1, -1, -1, 1).minHeight = 220;
            var mask = UI.Image(box, "Mask", Theme.Hex(0xf1f5f9), radius, true);
            mask.rectTransform.Stretch();
            mask.gameObject.AddComponent<Mask>().showMaskGraphic = true;
            var raw = UI.Rect("ThreeCanvas", mask.transform).Stretch().gameObject.AddComponent<RawImage>();
            raw.color = Color.white;
            touch = raw.gameObject.AddComponent<AidyHomeTouch>();
            touch.Room = room;

            // ai-status-bubble
            // 左上: いまの状態の吹き出し (+ 充電残量)
            var top = UI.Rect("TopRow", box);
            top.Pin(new Vector2(0, 1), new Vector2(12, -12 - topInset), Vector2.zero);
            top.HLayout(0, 0, 0, 0, 8);
            top.FitContent();
            var bub = UI.Rect("AIStatus", top);
            UI.Box(bub, new Color(1, 1, 1, 0.88f), 16, new BoxShadow(0, 4, 12, new Color(0, 0, 0, 0.05f)));
            bub.HLayout(12, 12, 6, 6, 6);
            mascot = AidyMascot.Create(bub, 16, AidyFace.Normal);
            stateText = UI.Label(bub, "こんにちは！", 11, Theme.Primary, true);
            if (battery)
            {
                var pill = UI.Rect("BatteryPill", top);
                UI.Box(pill, new Color(1, 1, 1, 0.88f), 16, new BoxShadow(0, 4, 12, new Color(0, 0, 0, 0.05f)));
                pill.HLayout(10, 8, 0, 0, 0, TextAnchor.MiddleCenter);
                pill.Layout(-1, 30);
                BuildBattery(pill, BatteryLevel);
            }

            // 操作ヒント
            var hint = UI.Rect("RotateHint", box);
            hint.Pin(new Vector2(0.5f, 0), new Vector2(0, bottomInset + 10), Vector2.zero);
            UI.Box(hint, new Color(1, 1, 1, 0.8f), 12);
            hint.HLayout(12, 12, 4, 4, 8);
            hint.FitContent();
            UI.Icon(hint, IconId.HandPointer, 11, Theme.TextSub);
            UI.Label(hint, hintText, 11, Theme.TextSub, true);

            // ズームボタン (+ / −)
            var zoom = UI.Rect("Zoom", box);
            zoom.Pin(new Vector2(1, 0), new Vector2(-10, bottomInset + 44), Vector2.zero);
            zoom.pivot = new Vector2(1, 0);
            zoom.VLayout(0, 0, 0, 0, 6);
            zoom.FitContent();
            foreach (bool zoomIn in new[] { true, false })
            {
                var b = UI.Rect(zoomIn ? "ZoomIn" : "ZoomOut", zoom);
                b.Layout(34, 34);
                UI.Box(b, new Color(1, 1, 1, 0.9f), 17, new BoxShadow(0, 3, 10, new Color(0, 0, 0, 0.1f))).raycastTarget = true;
                if (zoomIn) UI.Icon(b, IconId.Plus, 13, Theme.Slate600).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(13, 13));
                else UI.Image(b, "Minus", Theme.Slate600, 1.5f).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(12, 3));
                var c = b.gameObject.AddComponent<Clickable>();
                c.PressScale = 0.9f;
                c.OnClick = () => room.ZoomBy(zoomIn ? 1.25f : 0.8f);
            }
            if (room == editRoom) editImage = raw;
            return box;
        }

        void UpdateModuleSummary()
        {
            var counts = new Dictionary<ModuleKind, int>();
            foreach (ModuleKind k in ModuleConfig.Slots.Values)
            {
                counts.TryGetValue(k, out int n);
                counts[k] = n + 1;
            }
            var parts = new List<string>();
            foreach (ModuleKind k in ModuleCatalog.All)
                if (counts.TryGetValue(k, out int n)) parts.Add(n > 1 ? $"{ModuleCatalog.Name(k)}×{n}" : ModuleCatalog.Name(k));
            moduleSummary.text = parts.Count > 0 ? string.Join("・", parts) : "なし (本体だけ)";
        }

        // ---------------------------------------------------------------- PAGE 1: 日記

        void BuildDiaryPage(RectTransform page)
        {
            diary = AidyDiaryPage.Build(page, ScrollPage(page));
        }

        // ---------------------------------------------------------------- PAGE 2: リモコン・モジュール

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

        // ---------------------------------------------------------------- PAGE 3: AR

        void BuildARPage(RectTransform page)
        {
            // ---- AR (カメラ): 本体を床や机に置いて見る
            var camUI = UI.Rect("ARCameraMode", page).Stretch();
            arCameraUI = camUI.gameObject;
            var black = UI.Image(camUI, "Black", Color.black, 0, true);
            black.rectTransform.Stretch();

            var camArea = UI.Rect("WebcamArea", camUI).Stretch();
            camArea.gameObject.AddComponent<RectMask2D>();
            var webcam = UI.Rect("WebcamVideo", camArea).gameObject.AddComponent<RawImage>();
            webcam.rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero);
            webcam.raycastTarget = false;
            webcam.enabled = false;

            var overlay = UI.Rect("ARCanvas", camUI).Stretch().gameObject.AddComponent<RawImage>();
            overlay.raycastTarget = false;

            var band = UI.Rect("TouchBand", camUI).Stretch(0, 110, 0, 80);
            band.gameObject.AddComponent<Image>().color = new Color(0, 0, 0, 0);
            band.gameObject.AddComponent<AidyARTouch>().View = ar;

            var top = UI.Rect("AROverlay", camUI);
            top.anchorMin = new Vector2(0, 1);
            top.anchorMax = new Vector2(1, 1);
            top.pivot = new Vector2(0.5f, 1);
            top.offsetMin = new Vector2(20, 0);
            top.offsetMax = new Vector2(-20, -62);
            top.VLayout(0, 0, 0, 0, 8);
            top.FitContent(false, true);
            arStatus = ARBadge(top, Theme.Hex(0x0f172a, 0.8f), IconId.HandPointer, Color.white, "カメラ越しに机や床をタップして配置");
            arToast = AidyToast.Create(camUI, 110);

            var bottom = UI.Rect("ARBottomControls", camUI);
            bottom.anchorMin = new Vector2(0, 0);
            bottom.anchorMax = new Vector2(1, 0);
            bottom.pivot = new Vector2(0.5f, 0);
            bottom.offsetMin = new Vector2(20, 20);
            bottom.offsetMax = new Vector2(-20, 68);
            bottom.HLayout(0, 0, 0, 0, 12, TextAnchor.MiddleCenter);
            ARButton(bottom, IconId.Sliders, "表示方法", true).OnClick = () => ar.SetPlacement(AidyARView.Placement.Choose);
            ARButton(bottom, IconId.Crosshairs, "中央に再配置", false).OnClick = () => ar.ResetPosition();
            ARButton(bottom, IconId.Camera, "カメラ切替", false).OnClick = () =>
            {
                string cam = ar.SwitchCamera();
                if (cam == null) arToast.Show(AidyFace.Sleepy, "切り替えられるカメラがないよ", 2.2f);
                else
                {
                    UI.EnsureChars(cam);
                    arToast.Show(AidyFace.Happy, "カメラ: " + cam, 2.2f);
                }
            };

            BuildPlacementChooser(camUI);

            ar.Page = page;
            ar.Black = black;
            ar.WebcamArea = camArea;
            ar.WebcamImage = webcam;
            ar.OverlayImage = overlay;

            // ---- 画面上: 3D プレビューで面をタップ → モジュールをドラッグ&ドロップで付け替え
            var onScreen = UI.Rect("OnScreenMode", page).Stretch();
            arOnScreenUI = onScreen.gameObject;
            // ホームと同じく 3D はページいっぱい。上の切り替えと下のモジュール欄はその上に浮かべる
            UI.Image(onScreen, "Bg", Theme.Device, 0, true).rectTransform.Stretch();
            const float TrayHeight = 160f, TrayMargin = 12f, SwitchSpace = 56f;
            View3D(onScreen, editRoom, out editStateText, out editMascot, "面をタップで選択 ・ ドラッグで回転 ・ ピンチでズーム", out AidyHomeTouch editTouch,
                   0f, TrayHeight + TrayMargin * 2f, SwitchSpace).Stretch();
            editStateText.text = "面をタップして付け替えよう";
            editRoom.ViewInsetTop = SwitchSpace;
            editRoom.ViewInsetBottom = TrayHeight + TrayMargin * 2f;
            var tray = UI.Rect("ModuleTray", onScreen);
            tray.anchorMin = new Vector2(0, 0);
            tray.anchorMax = new Vector2(1, 0);
            tray.pivot = new Vector2(0.5f, 0);
            tray.offsetMin = new Vector2(16, TrayMargin);
            tray.offsetMax = new Vector2(-16, TrayMargin + TrayHeight);
            moduleTray = AidyModuleTray.Build(tray, editRoom, safeRoot);
            editTouch.Tray = moduleTray;
            moduleTray.OnMarkerButton = () => markerSheet.Open();

            // ---- 切り替え [画面上 | AR]
            var seg = UI.Rect("ModeSwitch", page);
            seg.Pin(new Vector2(0.5f, 1), new Vector2(0, -10), new Vector2(220, 38));
            UI.Box(seg, new Color(1, 1, 1, 0.95f), 19, new BoxShadow(0, 4, 14, new Color(0, 0, 0, 0.12f)), Theme.Border);
            seg.HLayout(3, 3, 3, 3, 3).childForceExpandWidth = true;
            SegmentButton(seg, IconId.Cubes, "画面上", out segOnScreenBg, out segOnScreenLabel).OnClick = () => SetARMode(false);
            SegmentButton(seg, IconId.Camera, "AR", out segArBg, out segArLabel).OnClick = () => SetARMode(true);
        }

        Clickable SegmentButton(RectTransform parent, IconId icon, string text, out Image bg, out TMP_Text label)
        {
            var b = UI.Rect("Segment_" + text, parent);
            b.Layout(0, 32, 1);
            bg = UI.Image(b, "Bg", Theme.Primary, 16, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            b.HLayout(0, 0, 0, 0, 6, TextAnchor.MiddleCenter);
            UI.Icon(b, icon, 12, Color.white).name = "Icon";
            label = UI.Label(b, text, 12, Color.white, true);
            var c = b.gameObject.AddComponent<Clickable>();
            c.PressScale = 0.96f;
            return c;
        }

        /// <summary>AR を始めたとき: マーカーで出すか、マーカーなしで机や床に置くかを選ぶカード。</summary>
        void BuildPlacementChooser(RectTransform camUI)
        {
            var root = UI.Rect("PlacementChooser", camUI).Stretch();
            UI.Image(root, "Dim", new Color(0, 0, 0, 0.35f), 0, true).rectTransform.Stretch();
            var card = UI.Rect("Card", root);
            card.anchorMin = new Vector2(0, 0.5f);
            card.anchorMax = new Vector2(1, 0.5f);
            card.pivot = new Vector2(0.5f, 0.5f);
            card.offsetMin = new Vector2(24, 0);
            card.offsetMax = new Vector2(-24, 0);
            UI.Box(card, Color.white, 22, new BoxShadow(0, 8, 24, new Color(0, 0, 0, 0.25f))).raycastTarget = true;
            card.VLayout(18, 18, 18, 18, 10).childForceExpandWidth = true;
            card.FitContent(false, true);

            UI.Label(card, "Aidyをどうやって表示する？", 15, Theme.TextMain, true, TextAnchor.MiddleCenter).Layout(-1, 24);
            PlacementOption(card, IconId.Crosshairs, "マーカーで表示", "マーカーを写すと、その上にAidyが出てくるよ")
                .OnClick = () => ChoosePlacement(AidyARView.Placement.Marker);
            PlacementOption(card, IconId.HandPointer, "マーカーなしで置く", "机や床をタップすると、そこにAidyを置くよ")
                .OnClick = () => ChoosePlacement(AidyARView.Placement.Free);

            ar.PlacementChanged += p => root.gameObject.SetActive(p == AidyARView.Placement.Choose);
        }

        static Clickable PlacementOption(RectTransform parent, IconId icon, string title, string sub)
        {
            var b = UI.Rect("Option", parent);
            b.Layout(-1, 64);
            var bg = UI.Image(b, "Bg", Theme.Slate100, 16, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            b.HLayout(14, 14, 0, 0, 12);
            var circle = UI.Rect("IconBg", b);
            circle.Layout(40, 40);
            UI.Image(circle, "Bg", Theme.PrimaryLight, 20).rectTransform.Stretch();
            UI.Icon(circle, icon, 16, Theme.Primary).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(16, 16));
            var text = UI.Rect("Text", b);
            text.VLayout(0, 0, 0, 0, 2, TextAnchor.MiddleLeft);
            text.Layout(-1, -1, 1);
            UI.Label(text, title, 14, Theme.TextMain, true).Layout(-1, 20);
            UI.Label(text, sub, 11, Theme.TextSub).Layout(-1, 16);
            UI.Icon(b, IconId.ChevronRight, 12, Theme.Slate400);
            var c = b.gameObject.AddComponent<Clickable>();
            c.PressScale = 0.98f;
            return c;
        }

        void ChoosePlacement(AidyARView.Placement p)
        {
            ar.StartCamera();
            ar.SetPlacement(p);
        }

        /// <summary>AR タブの表示切り替え。true: カメラで AR / false: 画面上の 3D で付け替えプレビュー。</summary>
        void SetARMode(bool on)
        {
            if (on && !arMode) ar.SetPlacement(AidyARView.Placement.Choose); // AR を開くたびに表示方法を選び直す
            arMode = on;
            arCameraUI.SetActive(on);
            arOnScreenUI.SetActive(!on);
            StyleSegment(segOnScreenBg, segOnScreenLabel, !on);
            StyleSegment(segArBg, segArLabel, on);
            if (on && pager.Current == PageAR) ar.StartCamera();
            if (!on) ar.StopCamera();
        }

        static void StyleSegment(Image bg, TMP_Text label, bool selected)
        {
            Color fg = selected ? Color.white : Theme.Slate600;
            bg.color = selected ? Theme.Primary : new Color(1, 1, 1, 0);
            label.color = fg;
            foreach (Image i in bg.transform.parent.GetComponentsInChildren<Image>())
                if (i.name == "Icon") i.color = fg;
        }

        void OnARPlaced(string text)
        {
            if (text.Contains("未配置")) arToast.Show(AidyFace.Normal, "床や机をタップして置いてね", 3f);
            else if (text.Contains("中央")) arToast.Show(AidyFace.Happy, "まんなかに戻ったよ！", 2.2f);
            else arToast.Show(AidyFace.Happy, "ここに置いたよ！", 2.2f);
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

        // ---------------------------------------------------------------- PAGE 4: 設定

        void BuildSettingsPage(RectTransform page)
        {
            var c = ScrollPage(page);
            SectionTitle(c, "ロボット設定", true);
            var card = Card(c, null, default, default, "アセンブリ構造", "本体v2_モデル.obj (透明化防止完全対応)");
            UI.Icon(card, IconId.Check, 14, Theme.Green);
            card = Card(c, null, default, default, "スピーカー音量", "効果音 & 音声応答");
            UI.Label(card, "60%", 13, Theme.Primary, true);

            SectionTitle(c, "日記", false);
            card = Card(c, null, default, default, "カレンダー表示", "週表示に切り替える");
            AidySwitch.Create(card, CalendarSettings.WeekView).OnChanged = on =>
            {
                CalendarSettings.WeekView = on;
                diary.SetWeekView(on);
            };

            SectionTitle(c, "操作 & ネットワーク", false);
            card = Card(c, null, default, default, "モジュール自動合体", "パーツSocketアタッチ機能 (ON)");
            AidySwitch.Create(card, true);
            card = Card(c, null, default, default, "Wi-Fi 接続", "AIDY-5G-2.4G (接続済み)");
            UI.Icon(card, IconId.Wifi, 16, Theme.Green);
            card = Card(c, IconId.Aidy, Theme.Primary, Theme.PrimaryLight, "Aidy本体の会話ログ", AidyDeviceLink.Host + " から Wi-Fi で日記を同期");
            UI.Icon(card, IconId.ChevronRight, 12, Theme.Slate400);
            card.gameObject.AddComponent<Image>().color = Color.clear;
            card.gameObject.AddComponent<Clickable>().OnClick = () => pager.GoTo(PageDiary);
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
            if (page == PageAR && arMode) ar.StartCamera();
            if (page == PageDiary) diary.OnShown();
        }

        void ApplyPageUI(int page, bool instant)
        {
            // ホーム・AR (3D の操作) と日記 (カレンダー端の日付) は矢印と重なるので隠す (タブ/スワイプで移動)
            bool hide = page == PageDiary || page == PageHome || page == PageAR;
            SetArrow(prevGroup, prevClick, page != 0, hide);
            SetArrow(nextGroup, nextClick, page != pager.PageCount - 1, hide);

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

        static void SetArrow(CanvasGroup g, Clickable c, bool enabled, bool hidden)
        {
            enabled &= !hidden;
            g.alpha = hidden ? 0f : enabled ? 1f : 0.3f;
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
            home.Cam.enabled = pager.IsVisible(PageHome);
            editRoom.Cam.enabled = pager.IsVisible(PageAR) && !arMode;
            ar.PageVisible = pager.IsVisible(PageAR) && arMode;

            // Android の戻るボタン
            if (Input.GetKeyDown(KeyCode.Escape))
            {
                if (pager.Current != PageHome) pager.GoTo(PageHome);
                else Application.Quit();
            }
        }

        // ================================================================ 検証用スクリーンショット (-aidyShots <dir>)

        IEnumerator CaptureRoutine(string dir)
        {
            Directory.CreateDirectory(dir);
            yield return new WaitForSeconds(2.5f);
            yield return Capture(dir, "page0_home");
            home.ToggleAIWalk(false); // 正面を向けて確認
            yield return new WaitForSeconds(2.6f);
            yield return Capture(dir, "page0_front");
            // 回しすぎて目を回す
            for (int i = 0; i < 30; i++)
            {
                home.DraggingModel = true;
                home.RotateBy(40f, 0f);
                yield return null;
            }
            home.DraggingModel = false;
            yield return new WaitForSeconds(0.6f);
            yield return Capture(dir, "page0_dizzy");
            yield return new WaitForSeconds(3.5f);
            home.Play("dance");
            yield return new WaitForSeconds(1.4f);
            yield return Capture(dir, "page0_dance");
            yield return new WaitForSeconds(2.2f);
            home.Play("sneeze");
            yield return new WaitForSeconds(0.72f);
            yield return Capture(dir, "page0_sneeze");
            yield return new WaitForSeconds(1f);
            home.Play("doze");
            yield return new WaitForSeconds(2f);
            yield return Capture(dir, "page0_doze");
            home.PetRobotDirectly(); // 起こす
            yield return new WaitForSeconds(1.6f);
            yield return Capture(dir, "page0_stretch");
            yield return new WaitForSeconds(1.5f);
            home.ZoomBy(1.8f);
            yield return new WaitForSeconds(1f);
            yield return Capture(dir, "page0_zoom");
            home.ResetZoom();

            // AR タブ「画面上」: 面をタップ → モジュールをドロップ
            pager.GoTo(PageAR);
            yield return new WaitForSeconds(1.2f);
            yield return Capture(dir, "page3_onscreen");
            editRoom.SelectFace(null); // 面を選ぶ前 = 全モジュールのカルーセル
            yield return new WaitForSeconds(0.5f);
            yield return Capture(dir, "page3_tray_all");
            moduleTray.ScrollBy(2);
            yield return new WaitForSeconds(0.08f);
            yield return Capture(dir, "page3_tray_swiping");
            yield return new WaitForSeconds(1.2f);
            yield return Capture(dir, "page3_tray_scrolled");
            editRoom.SelectFace("left");
            yield return new WaitForSeconds(1.2f);
            yield return Capture(dir, "page3_select_left");
            editRoom.SetDropState(ModuleKind.Hand, "left_2");
            yield return new WaitForSeconds(0.2f);
            yield return Capture(dir, "page3_drag_hand");
            editRoom.SetDropState(null, null);
            editRoom.Replace(ModuleKind.Hand, "left_2");
            yield return new WaitForSeconds(1.8f);
            yield return Capture(dir, "page3_hand_left");
            editRoom.SelectFace("right");
            editRoom.Replace(ModuleKind.HandLight, "right_2");
            yield return new WaitForSeconds(1.8f);
            yield return Capture(dir, "page3_light_right");
            editRoom.SelectFace(null);
            editRoom.ZoomBy(1.5f);
            yield return new WaitForSeconds(2f);
            yield return Capture(dir, "page3_zoom");
            editRoom.ResetZoom();
            pager.GoTo(PageHome);
            home.ToggleAIWalk(true);
            yield return new WaitForSeconds(2f);
            yield return Capture(dir, "page0_home_hands");
            ModuleConfig.ResetToDefault();
            foreach (int p in new[] { PageDiary, PageRemote, PageSettings }) // AR 画面はカメラ映像が写るので撮らない
            {
                pager.GoTo(p);
                yield return new WaitForSeconds(1f);
                yield return Capture(dir, "page" + p);
            }
            pager.GoTo(PageDiary);
            diary.ShowLatestWithLogs();
            yield return new WaitForSeconds(1f);
            yield return Capture(dir, "page1_diary_entry");
            pager.Pages[PageDiary].GetComponent<ScrollRect>().verticalNormalizedPosition = 0f;
            yield return new WaitForSeconds(0.5f);
            yield return Capture(dir, "page1_diary_logs");
            pager.GoTo(PageHome);
            yield return new WaitForSeconds(1f);
            Application.Quit();
        }

        /// <summary>検証用 (-aidyMarkerTest <画像> <出力先>): 静止画をカメラ映像として AR 画面に出し、マーカーの上のモデルを撮る。</summary>
        IEnumerator MarkerTestRoutine(string image, string dir)
        {
            Directory.CreateDirectory(dir);
            var tex = new Texture2D(2, 2);
            tex.LoadImage(File.ReadAllBytes(image));
            yield return new WaitForSeconds(1.5f);
            pager.GoTo(PageAR);
            ar.UseTestImage(tex);
            SetARMode(true);
            ar.SetPlacement(AidyARView.Placement.Marker);
            yield return new WaitForSeconds(2f);
            yield return Capture(dir, "marker_" + Path.GetFileNameWithoutExtension(image));
            Application.Quit();
        }

        /// <summary>検証用 (-aidyMarkerSheet <出力先>): 着せ替えマーカーを作って保存し、その画像をカメラ映像にして写るモデルを撮る。</summary>
        IEnumerator MarkerSheetRoutine(string dir)
        {
            Directory.CreateDirectory(dir);
            yield return new WaitForSeconds(1.5f);
            pager.GoTo(PageAR);
            yield return new WaitForSeconds(1.2f);
            editRoom.SelectFace("left");
            editRoom.Replace(ModuleKind.Hand, "left_2");
            editRoom.SelectFace(null);
            yield return new WaitForSeconds(1f);
            yield return Capture(dir, "sheet0_tray");
            markerSheet.Open();
            yield return new WaitForSeconds(0.4f);
            yield return Capture(dir, "sheet1_open");
            int? id = AidyMarkerStore.Find(ModuleConfig.Slots) ?? AidyMarkerStore.Create(ModuleConfig.Slots);
            markerSheet.Open();
            yield return new WaitForSeconds(0.4f);
            yield return Capture(dir, "sheet2_created");
            string png = AidyMarkerImage.SavePng(id.Value);
            Debug.Log("[AIDY] marker saved: " + png);
            markerSheet.Close();

            // 保存した画像をカメラに写す → 作ったときの着せ替え (手付き)。いまの着せ替えを戻しても変わらない
            ModuleConfig.ResetToDefault();
            var src = new Texture2D(2, 2);
            src.LoadImage(File.ReadAllBytes(png));
            // 少し離れて撮った写真のように、白い 1280x960 の中央に 1/8 の大きさで置く
            var tex = new Texture2D(1280, 960, TextureFormat.RGBA32, false);
            var px = new Color32[1280 * 960];
            for (int i = 0; i < px.Length; i++) px[i] = new Color32(235, 235, 230, 255);
            int m = src.width / 8, ox = (1280 - m) / 2, oy = (960 - m) / 2;
            for (int y = 0; y < m; y++)
                for (int x = 0; x < m; x++)
                    px[(oy + y) * 1280 + ox + x] = src.GetPixel(x * 8, y * 8);
            tex.SetPixels32(px);
            tex.Apply();
            ar.UseTestImage(tex);
            SetARMode(true);
            ar.SetPlacement(AidyARView.Placement.Marker);
            yield return new WaitForSeconds(2f);
            yield return Capture(dir, "sheet3_scan_saved");
            AidyMarkerStore.Delete(id.Value);
            yield return new WaitForSeconds(1f);
            yield return Capture(dir, "sheet4_deleted");
            Application.Quit();
        }

        /// <summary>検証用 (-aidyFaces <出力先>): 顔モニターの全表情を、画面の絵 (face_*.png) とホームの 3D (home_*.png) で撮る。</summary>
        IEnumerator FacesRoutine(string dir)
        {
            Directory.CreateDirectory(dir);
            yield return new WaitForSeconds(1.5f);
            pager.GoTo(PageHome);
            home.ToggleAIWalk(false); // 自分で表情を変えないように
            yield return new WaitForSeconds(1.5f);
            foreach (string name in AidyFaceScreen.Names)
            {
                foreach (AidyFaceScreen s in FindObjectsOfType<AidyFaceScreen>()) s.SetFace(name);
                yield return new WaitForSeconds(1.2f);
                foreach (AidyFaceScreen s in FindObjectsOfType<AidyFaceScreen>())
                {
                    if (!s.GetComponent<Renderer>().isVisible) continue;
                    File.WriteAllBytes(Path.Combine(dir, "face_" + name + ".png"), s.Texture.EncodeToPNG());
                    break;
                }
                yield return Capture(dir, "home_" + name);
            }
            Application.Quit();
        }

        /// <summary>検証用 (-aidyARPlacement <出力先>): 表示方法の選択 → マーカーなしで机の面に置く → マーカー待ち、を撮る。</summary>
        IEnumerator ARPlacementRoutine(string dir)
        {
            Directory.CreateDirectory(dir);
            yield return new WaitForSeconds(1.5f);
            pager.GoTo(PageAR);
            yield return new WaitForSeconds(1.2f);
            // 机を斜め上から撮ったような映像の代わり: 上が壁、下が木の天板
            var tex = new Texture2D(1280, 960, TextureFormat.RGBA32, false);
            var px = new Color32[1280 * 960];
            for (int y = 0; y < 960; y++)
                for (int x = 0; x < 1280; x++)
                {
                    float grain = Mathf.PerlinNoise(x * 0.004f, y * 0.06f) * 30f;
                    px[y * 1280 + x] = y > 640
                        ? new Color32(226, 228, 232, 255)
                        : new Color32((byte)(150 + grain), (byte)(105 + grain * 0.7f), (byte)(70 + grain * 0.4f), 255);
                }
            tex.SetPixels32(px);
            tex.Apply();
            ar.UseTestImage(tex);
            SetARMode(true);
            yield return new WaitForSeconds(1f);
            yield return Capture(dir, "place0_choose");
            ChoosePlacement(AidyARView.Placement.Free);
            yield return new WaitForSeconds(0.8f);
            yield return Capture(dir, "place1_free_reticle");
            ar.ResetPosition();
            yield return new WaitForSeconds(1.5f);
            yield return Capture(dir, "place2_center");
            ar.PointerDown();
            ar.PointerUp(new Vector2(Screen.width * 0.3f, Screen.height * 0.3f));
            yield return new WaitForSeconds(1.5f);
            yield return Capture(dir, "place3_tap_near");
            ChoosePlacement(AidyARView.Placement.Marker);
            yield return new WaitForSeconds(1f);
            yield return Capture(dir, "place4_marker_wait");
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
}
