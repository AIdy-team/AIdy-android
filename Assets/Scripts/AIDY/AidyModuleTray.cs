using System;
using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// ホーム画面下のモジュールトレイ。モジュールを丸いプレビュー (3D モデルを描いた画像) で並べ、左右にスワイプして選ぶ。
    /// 4 つ以上あるときは円形にループするカルーセル (中央が大きく、端は小さく下がる)。面を選ぶ前は全モジュール、
    /// 3D モデルの面をタップするとその面に付けられるモジュールだけになる。
    /// モジュールを上へドラッグして 3D モデルのスロット (光る丸) へドロップで取り付け/差し替え、
    /// 3D モデル上のモジュールをトレイへドラッグで取り外し。
    /// </summary>
    public class AidyModuleTray : MonoBehaviour
    {
        const float SnapPx = 60f;      // ドロップ先スロットの吸着距離 (UI 単位)
        const float Circle = 68f;      // 中央のプレビューの直径
        const float VisibleSlots = 4f; // 表示幅に並ぶ間隔の数 (中央 + 左右 1 つずつ + 端に半分ずつ)
        const float Arc = 10f;         // 端へ行くほど下がる量 (円形の軌道)
        const float SpinSpeed = 45f;   // 中央のプレビューが回る速さ (度/秒)

        class Item
        {
            public ModuleKind Kind;
            public RectTransform Rt;
            public CanvasGroup Group;
            public TMP_Text Label;
            public float Offset;
        }

        AidyHomeRoom home;
        RectTransform overlay, tray, palette, carousel, removeZone;
        TMP_Text title, hint;
        Canvas canvas;
        readonly List<Item> items = new List<Item>();
        readonly List<ModuleKind> shown = new List<ModuleKind>();

        // カルーセル: pos = 中央にいるモジュールの番号 (連続値。ループするので n の倍数ずれても同じ)
        float pos, target, velocity, swipeVelocity;
        bool swiping;
        int spinning = -1;
        float spinYaw;

        ModuleKind dragKind;
        string dragFrom, hover;
        RectTransform ghost;
        bool dragging;

        bool Loop => items.Count >= 4;
        float Step => Mathf.Max(carousel.rect.width / VisibleSlots, Circle + 8f);

        /// <summary>見出しの「マーカー作成」が押されたとき。</summary>
        public Action OnMarkerButton;

        public static AidyModuleTray Build(RectTransform tray, AidyHomeRoom home, RectTransform overlay)
        {
            var t = tray.gameObject.AddComponent<AidyModuleTray>();
            t.home = home;
            t.overlay = overlay;
            t.tray = tray;
            t.BuildUI();
            home.FaceSelected += _ => t.Refresh();
            home.ModulesChanged += t.Refresh;
            t.Refresh();
            return t;
        }

        void BuildUI()
        {
            canvas = tray.GetComponentInParent<Canvas>();
            UI.Box(tray, Color.white, 18, new BoxShadow(0, 4, 16, new Color(0, 0, 0, 0.05f)), Theme.Border).raycastTarget = true;
            tray.VLayout(14, 14, 10, 8, 4).childForceExpandWidth = true;

            var head = UI.Rect("Head", tray);
            head.HLayout(0, 0, 0, 0, 6);
            head.Layout(-1, 22);
            UI.Icon(head, IconId.Cubes, 12, Theme.Primary);
            title = UI.Label(head, "", 12, Theme.TextSub, true);
            title.Layout(0, -1, 1);
            title.overflowMode = TextOverflowModes.Ellipsis;

            // いまの着せ替えのマーカーを作る画面を開く
            var marker = UI.Rect("MarkerButton", head);
            marker.HLayout(10, 10, 0, 0, 4, TextAnchor.MiddleCenter);
            marker.Layout(-1, 22);
            var mbg = UI.Image(marker, "Bg", Theme.PrimaryLight, 11, true);
            mbg.rectTransform.Stretch();
            mbg.IgnoreLayout();
            UI.Icon(marker, IconId.Camera, 10, Theme.Primary);
            UI.Label(marker, "マーカー作成", 11, Theme.Primary, true);
            var mc = marker.gameObject.AddComponent<Clickable>();
            mc.PressScale = 0.96f;
            mc.OnClick = () => OnMarkerButton?.Invoke();

            palette = UI.Rect("Palette", tray);
            palette.Layout(-1, Circle + 34);

            // スワイプを受ける透明な背景 + 左右の端で切る
            carousel = UI.Rect("Carousel", palette).Stretch();
            UI.Image(carousel, "SwipeArea", new Color(1, 1, 1, 0), 0, true).rectTransform.Stretch();
            carousel.gameObject.AddComponent<RectMask2D>();
            var swipe = carousel.gameObject.AddComponent<ModuleCarouselSwipe>();
            swipe.Tray = this;

            hint = UI.Label(tray, "", 11, Theme.Primary, true, TextAnchor.MiddleCenter);
            hint.Layout(-1, 14);

            // 3D モデルからモジュールを持ってきたときの「ここで離すと取り外し」
            removeZone = UI.Rect("RemoveZone", tray);
            removeZone.IgnoreLayout();
            removeZone.Stretch();
            UI.Box(removeZone, Theme.Hex(0xfef2f2, 0.97f), 18, null, Theme.Hex(0xef4444), 2f);
            var rz = UI.Rect("Row", removeZone);
            rz.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero);
            rz.HLayout(0, 0, 0, 0, 8);
            rz.FitContent();
            UI.Icon(rz, IconId.XMark, 14, Theme.Hex(0xef4444));
            UI.Label(rz, "ここで離すと取り外し", 13, Theme.Hex(0xef4444), true);
            removeZone.gameObject.SetActive(false);
        }

        void Refresh()
        {
            string face = home.SelectedFace;
            var kinds = face == null ? new List<ModuleKind>(ModuleCatalog.All) : ModuleCatalog.ForFace(face);
            UpdateTitle(face, kinds.Count);

            // 並びが同じなら作り直さない (取り付けのたびにカルーセルの位置が戻らないように)
            bool same = kinds.Count == shown.Count;
            for (int i = 0; same && i < kinds.Count; i++) same = kinds[i] == shown[i];
            if (same) return;

            ModuleKind? focused = items.Count > 0 ? items[Wrap(Mathf.RoundToInt(pos))].Kind : (ModuleKind?)null;
            foreach (Item it in items) Destroy(it.Rt.gameObject);
            items.Clear();
            shown.Clear();
            shown.AddRange(kinds);
            foreach (ModuleKind k in kinds) items.Add(CreateItem(k));

            // 前に中央にいたモジュールが残っていればそれを中央に
            int start = focused.HasValue ? Mathf.Max(0, kinds.IndexOf(focused.Value)) : 0;
            pos = target = Loop ? start : 0;
            velocity = 0f;
            StopSpin();
            Layout();
        }

        void UpdateTitle(string face, int count)
        {
            if (face == null)
            {
                title.text = $"モジュール {count}種 ・ 面をタップで取り付け";
                return;
            }
            var counts = new Dictionary<ModuleKind, int>();
            foreach (BodySlot s in BodyLayout.OnFace(face))
            {
                ModuleKind? k = home.ModuleAt(s.Id);
                if (!k.HasValue) continue;
                counts.TryGetValue(k.Value, out int n);
                counts[k.Value] = n + 1;
            }
            var parts = new List<string>();
            foreach (var kv in counts) parts.Add(kv.Value > 1 ? $"{ModuleCatalog.Name(kv.Key)}×{kv.Value}" : ModuleCatalog.Name(kv.Key));
            title.text = parts.Count > 0
                ? $"{BodyLayout.FaceLabel(face)} ・ 付いている: {string.Join("、", parts)}"
                : $"{BodyLayout.FaceLabel(face)}に付けられるモジュール";
        }

        Item CreateItem(ModuleKind k)
        {
            var rt = UI.Rect("Module_" + k, carousel);
            rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 1f);
            rt.pivot = new Vector2(0.5f, 1f);
            rt.sizeDelta = new Vector2(Circle + 16f, Circle + 22f);
            var hit = UI.Image(rt, "Hit", new Color(1, 1, 1, 0), 0, true);
            hit.rectTransform.Stretch();

            var circle = CirclePreview(rt, k, Circle);
            circle.Pin(new Vector2(0.5f, 1f), Vector2.zero, new Vector2(Circle, Circle));

            var label = UI.Label(rt, ModuleCatalog.Name(k), 11, Theme.TextMain, true, TextAnchor.MiddleCenter);
            label.rectTransform.Pin(new Vector2(0.5f, 0f), new Vector2(0, 2f), new Vector2(Circle + 16f, 16f));

            var d = rt.gameObject.AddComponent<ModuleDragHandle>();
            d.Tray = this;
            d.Kind = k;
            return new Item { Kind = k, Rt = rt, Group = rt.gameObject.AddComponent<CanvasGroup>(), Label = label };
        }

        /// <summary>モジュールの色の枠が付いた丸いプレビュー画像。</summary>
        static RectTransform CirclePreview(Transform parent, ModuleKind k, float size)
        {
            Color tint = ModuleCatalog.Tint(k);
            var mask = UI.Image(parent, "Circle", Color.white, size * 0.5f);
            mask.gameObject.AddComponent<Mask>().showMaskGraphic = true;
            var rt = mask.rectTransform;
            var img = UI.Rect("Preview", rt).Stretch().gameObject.AddComponent<RawImage>();
            img.texture = AidyModulePreview.Get(k);
            img.raycastTarget = false;
            var ring = UI.Image(rt, "Ring", tint);
            UI.SetRadius(ring, size * 0.5f, 2f);
            ring.rectTransform.Stretch();
            return rt;
        }

        // ---------------------------------------------------------------- カルーセル

        void Update()
        {
            if (items.Count == 0) return;
            if (!swiping && Loop)
            {
                pos = Mathf.SmoothDamp(pos, target, ref velocity, 0.16f, Mathf.Infinity, Time.unscaledDeltaTime);
                if (Mathf.Abs(pos - target) < 0.0005f)
                {
                    // 止まったら 0..n-1 に戻しておく (ループなので見た目は同じ)
                    target -= Mathf.Floor(target / items.Count) * items.Count;
                    pos = target;
                    velocity = 0f;
                }
            }
            Layout();
            Spin();
        }

        int Wrap(int i) => ((i % items.Count) + items.Count) % items.Count;

        void Layout()
        {
            int n = items.Count;
            if (n == 0) return;
            float step = Step, fadeEnd = Mathf.Min(2.4f, n * 0.5f - 0.05f), fadeStart = fadeEnd - 0.7f;
            for (int i = 0; i < n; i++)
            {
                Item it = items[i];
                float off;
                if (Loop)
                {
                    off = Mathf.Repeat(i - pos + n * 0.5f, n) - n * 0.5f;
                }
                else
                {
                    off = i - (n - 1) * 0.5f; // 3 つ以下は横に並べるだけ
                }
                it.Offset = off;
                float a = Mathf.Abs(off);
                float scale = Loop ? Mathf.Lerp(1f, 0.74f, Mathf.Clamp01(a)) * Mathf.Lerp(1f, 0.85f, Mathf.Clamp01(a - 1f)) : 0.92f;
                float y = Loop ? -Arc * Mathf.Min(a, 2.5f) * Mathf.Min(a, 2.5f) * 0.5f : 0f;
                it.Rt.anchoredPosition = new Vector2(off * step, -4f + y);
                it.Rt.localScale = Vector3.one * scale;
                float alpha = Loop ? 1f - Mathf.SmoothStep(0f, 1f, Mathf.InverseLerp(fadeStart, fadeEnd, a)) : 1f;
                it.Group.alpha = alpha;
                it.Group.blocksRaycasts = alpha > 0.3f;
                it.Label.alpha = Loop ? Mathf.Clamp01(1.4f - a) : 1f;
                it.Label.fontStyle = !Loop || a < 0.5f ? FontStyles.Bold : FontStyles.Normal;
            }
            // 中央に近いものを手前に
            var order = new List<Item>(items);
            order.Sort((p, q) => Mathf.Abs(q.Offset).CompareTo(Mathf.Abs(p.Offset)));
            for (int i = 0; i < order.Count; i++) order[i].Rt.SetSiblingIndex(i + 1); // 0 はスワイプ用の背景
        }

        /// <summary>中央のモジュールのプレビューをゆっくり回す (トレイが見えているときだけ)。</summary>
        void Spin()
        {
            int center = Loop && Mathf.Abs(pos - target) < 0.3f ? Wrap(Mathf.RoundToInt(pos)) : -1;
            if (!home.Cam.enabled || dragging) center = -1;
            if (center != spinning) StopSpin();
            if (center < 0) return;
            spinning = center;
            spinYaw += SpinSpeed * Time.unscaledDeltaTime;
            AidyModulePreview.Render(items[center].Kind, spinYaw);
        }

        void StopSpin()
        {
            if (spinning >= 0 && spinning < items.Count) AidyModulePreview.Render(items[spinning].Kind, 0f);
            spinning = -1;
            spinYaw = 0f;
        }

        // 横スワイプ。ループしない (3 つ以下) ときは送る先がないので、親のページャーへ渡してページを切り替える

        AidyPager Pager => pager != null ? pager : pager = GetComponentInParent<AidyPager>();
        AidyPager pager;
        bool toPager;

        public void BeginSwipe(PointerEventData e)
        {
            toPager = !Loop;
            if (toPager)
            {
                Pager?.OnBeginDrag(e);
                return;
            }
            swiping = true;
            swipeVelocity = 0f;
            Swipe(e);
        }

        public void Swipe(PointerEventData e)
        {
            if (toPager)
            {
                Pager?.OnDrag(e);
                return;
            }
            if (!swiping) return;
            float scale = canvas != null ? canvas.scaleFactor : 1f;
            float d = -e.delta.x / scale / Step;
            pos += d;
            target = pos;
            float dt = Mathf.Max(Time.unscaledDeltaTime, 0.001f);
            swipeVelocity = Mathf.Lerp(swipeVelocity, d / dt, 0.35f);
        }

        public void EndSwipe(PointerEventData e)
        {
            if (toPager)
            {
                Pager?.OnEndDrag(e);
                toPager = false;
                return;
            }
            if (!swiping) return;
            swiping = false;
            // はじいた勢いの分だけ先へ (1 回で最大 3 つ)
            float fling = Mathf.Clamp(swipeVelocity * 0.12f, -3f, 3f);
            target = Mathf.Round(pos + fling);
            velocity = swipeVelocity;
        }

        /// <summary>カルーセルを n 個ぶん送る (検証用スクリーンショット)。</summary>
        public void ScrollBy(int n)
        {
            if (Loop) target = Mathf.Round(target) + n;
        }

        /// <summary>横のモジュールをタップしたら中央へ回す。</summary>
        public void Tapped(ModuleKind kind)
        {
            if (!Loop) return;
            foreach (Item it in items)
                if (it.Kind == kind && Mathf.Abs(it.Offset) > 0.5f) target = Mathf.Round(pos + it.Offset);
        }

        void SetHint(string text, bool error = false)
        {
            hint.text = text;
            hint.color = error ? Theme.Hex(0xef4444) : Theme.Primary;
        }

        // ---------------------------------------------------------------- ドラッグ & ドロップ

        /// <summary>fromSlot: 3D モデル上のモジュールをつかんだときのスロット (トレイからなら null)。</summary>
        public void BeginDrag(ModuleKind kind, string fromSlot, PointerEventData e)
        {
            if (fromSlot == null && home.SelectedFace == null)
            {
                SetHint($"先に3Dモデルの面をタップしてね ({ModuleCatalog.Name(kind)}を付ける場所)", true);
                return;
            }
            dragKind = kind;
            dragFrom = fromSlot;
            dragging = true;
            hover = null;

            ghost = UI.Rect("DragGhost", overlay);
            ghost.sizeDelta = new Vector2(64, 64);
            UI.AddShadow(ghost, 32, new BoxShadow(0, 6, 16, new Color(0, 0, 0, 0.2f)));
            CirclePreview(ghost, kind, 64).Stretch();
            var cg = ghost.gameObject.AddComponent<CanvasGroup>();
            cg.blocksRaycasts = false;
            cg.alpha = 0.95f;
            ghost.SetAsLastSibling();

            removeZone.gameObject.SetActive(fromSlot != null);
            removeZone.SetAsLastSibling();
            home.SetDropState(kind, null);
            SetHint(fromSlot != null ? "別のスロットへ移動 / トレイで離すと取り外し" : $"{ModuleCatalog.Name(kind)}を光っている丸へ");
            Drag(e);
        }

        public void Drag(PointerEventData e)
        {
            if (!dragging) return;
            RectTransformUtility.ScreenPointToLocalPointInRectangle(overlay, e.position, null, out Vector2 local);
            // 指で隠れないよう、ゴーストは指の少し上に出す
            ghost.localPosition = new Vector3(local.x, local.y + 40f, 0);

            string over = TargetSlot(e.position);
            if (over != hover)
            {
                hover = over;
                home.SetDropState(dragKind, hover);
            }
        }

        public void EndDrag(PointerEventData e)
        {
            if (!dragging) return;
            dragging = false;
            if (ghost != null) Destroy(ghost.gameObject);
            removeZone.gameObject.SetActive(false);
            home.SetDropState(null, null);

            string target = TargetSlot(e.position);
            bool overTray = RectTransformUtility.RectangleContainsScreenPoint(tray, e.position, null);
            if (target != null && target != dragFrom)
            {
                BodySlot s = BodyLayout.Get(target);
                ModuleKind? old = home.ModuleAt(target);
                if (dragFrom != null)
                {
                    home.Detach(dragFrom);
                    // 移動で別のモジュールに重ねたときは入れ替える
                    if (old.HasValue) home.Attach(old.Value, dragFrom);
                }
                home.Replace(dragKind, target);
                SetHint(old.HasValue
                    ? $"{BodyLayout.FaceLabel(s.Face)}の{s.Label}を {ModuleCatalog.Name(old.Value)} → {ModuleCatalog.Name(dragKind)} に切り替えました"
                    : $"{BodyLayout.FaceLabel(s.Face)}の{s.Label}に{ModuleCatalog.Name(dragKind)}を取り付けました");
            }
            else if (dragFrom != null && overTray)
            {
                home.Detach(dragFrom);
                SetHint($"{ModuleCatalog.Name(dragKind)}を取り外しました");
            }
            else if (target == null && home.SlotAtScreen(e.position, SnapPx, null) != null)
            {
                SetHint($"{ModuleCatalog.Name(dragKind)}はそこには付けられません", true);
            }
            else
            {
                SetHint("");
            }
            dragFrom = null;
        }

        string TargetSlot(Vector2 screen) => home.SlotAtScreen(screen, SnapPx, s => ModuleCatalog.Fits(dragKind, s));
    }

    /// <summary>
    /// トレイのモジュールのドラッグを受ける。横に動かしたらカルーセルのスワイプ、上 (3D モデルの方) へ動かしたら取り付けのドラッグ。
    /// </summary>
    public class ModuleDragHandle : MonoBehaviour, IInitializePotentialDragHandler, IBeginDragHandler, IDragHandler, IEndDragHandler, IPointerClickHandler
    {
        internal AidyModuleTray Tray;
        internal ModuleKind Kind;
        bool swipe;

        public void OnInitializePotentialDrag(PointerEventData e) { e.useDragThreshold = true; }

        public void OnBeginDrag(PointerEventData e)
        {
            Vector2 d = e.position - e.pressPosition;
            swipe = Mathf.Abs(d.x) > Mathf.Abs(d.y);
            if (swipe) Tray.BeginSwipe(e);
            else Tray.BeginDrag(Kind, null, e);
        }

        public void OnDrag(PointerEventData e)
        {
            if (swipe) Tray.Swipe(e);
            else Tray.Drag(e);
        }

        public void OnEndDrag(PointerEventData e)
        {
            if (swipe) Tray.EndSwipe(e);
            else Tray.EndDrag(e);
            swipe = false;
        }

        public void OnPointerClick(PointerEventData e) { Tray.Tapped(Kind); }
    }

    /// <summary>カルーセルのすき間 (モジュールのない所) でのスワイプ。</summary>
    public class ModuleCarouselSwipe : MonoBehaviour, IBeginDragHandler, IDragHandler, IEndDragHandler
    {
        internal AidyModuleTray Tray;

        public void OnBeginDrag(PointerEventData e) { Tray.BeginSwipe(e); }
        public void OnDrag(PointerEventData e) { Tray.Swipe(e); }
        public void OnEndDrag(PointerEventData e) { Tray.EndSwipe(e); }
    }
}
