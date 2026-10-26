using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// ホーム画面下のモジュールトレイ。3D モデルの面をタップすると、その面に付けられるモジュールが並ぶ。
    /// モジュールを 3D モデルのスロット (光る丸) へドラッグ&ドロップで取り付け/差し替え、
    /// 3D モデル上のモジュールをトレイへドラッグで取り外し。
    /// </summary>
    public class AidyModuleTray : MonoBehaviour
    {
        const float SnapPx = 60f; // ドロップ先スロットの吸着距離 (UI 単位)

        AidyHomeRoom home;
        RectTransform overlay, tray, palette, removeZone;
        TMP_Text title, hint;
        GameObject emptyState;
        readonly List<GameObject> cards = new List<GameObject>();

        ModuleKind dragKind;
        string dragFrom, hover;
        RectTransform ghost;
        bool dragging;

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
            UI.Box(tray, Color.white, 18, new BoxShadow(0, 4, 16, new Color(0, 0, 0, 0.05f)), Theme.Border).raycastTarget = true;
            tray.VLayout(14, 14, 10, 8, 6).childForceExpandWidth = true;

            var head = UI.Rect("Head", tray);
            head.HLayout(0, 0, 0, 0, 6);
            head.Layout(-1, 24);
            UI.Icon(head, IconId.Cubes, 12, Theme.Primary);
            title = UI.Label(head, "", 12, Theme.TextSub, true);
            title.Layout(0, -1, 1);
            title.overflowMode = TextOverflowModes.Ellipsis;

            palette = UI.Rect("Palette", tray);
            palette.HLayout(0, 0, 0, 0, 8).childForceExpandWidth = true;
            palette.Layout(-1, 76);

            // 面を選ぶ前の案内
            var empty = UI.Rect("Empty", palette);
            empty.Layout(0, 76, 1);
            UI.Box(empty, Theme.Hex(0xf8fafc), 14, null, Theme.Slate100);
            empty.HLayout(14, 14, 0, 0, 12, TextAnchor.MiddleLeft);
            AidyMascot.Create(empty, 40, AidyFace.Normal);
            var info = UI.Rect("Info", empty);
            info.VLayout(0, 0, 0, 0, 2);
            info.Layout(-1, -1, 1);
            UI.Label(info, "3Dモデルの面をタップしてね", 13, Theme.TextMain, true);
            UI.Label(info, "その面に付けられるモジュールが出てくるよ", 11, Theme.TextSub);
            emptyState = empty.gameObject;

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
            foreach (GameObject c in cards) Destroy(c);
            cards.Clear();

            string face = home.SelectedFace;
            emptyState.SetActive(face == null);
            if (face == null)
            {
                title.text = "モジュール合体";
                return;
            }

            // タイトル: 面の名前 + いま付いているもの
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

            foreach (ModuleKind k in ModuleCatalog.ForFace(face)) cards.Add(Card(k));
        }

        GameObject Card(ModuleKind k)
        {
            var item = UI.Rect("Module_" + k, palette);
            item.Layout(0, 76, 1);
            Color tint = ModuleCatalog.Tint(k);
            UI.Box(item, Color.white, 14, new BoxShadow(0, 2, 8, new Color(0, 0, 0, 0.05f)), new Color(tint.r, tint.g, tint.b, 0.5f), 1.5f).raycastTarget = true;
            item.VLayout(0, 0, 10, 8, 4, TextAnchor.MiddleCenter);
            UI.Icon(item, ModuleCatalog.Icon(k), 24, tint);
            UI.Label(item, ModuleCatalog.Name(k), 12, Theme.TextMain, true, TextAnchor.MiddleCenter);
            var d = item.gameObject.AddComponent<ModuleDragHandle>();
            d.Tray = this;
            d.Kind = k;
            return item.gameObject;
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
            dragKind = kind;
            dragFrom = fromSlot;
            dragging = true;
            hover = null;

            ghost = UI.Rect("DragGhost", overlay);
            ghost.sizeDelta = new Vector2(60, 60);
            Color tint = ModuleCatalog.Tint(kind);
            UI.Box(ghost, Color.white, 18, new BoxShadow(0, 6, 16, new Color(0, 0, 0, 0.2f)), tint, 2f);
            UI.Icon(ghost, ModuleCatalog.Icon(kind), 28, tint).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(28, 28));
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

    /// <summary>トレイのモジュールカードのドラッグを受ける。</summary>
    public class ModuleDragHandle : MonoBehaviour, IInitializePotentialDragHandler, IBeginDragHandler, IDragHandler, IEndDragHandler
    {
        internal AidyModuleTray Tray;
        internal ModuleKind Kind;

        public void OnInitializePotentialDrag(PointerEventData e) { e.useDragThreshold = true; }
        public void OnBeginDrag(PointerEventData e) { Tray.BeginDrag(Kind, null, e); }
        public void OnDrag(PointerEventData e) { Tray.Drag(e); }
        public void OnEndDrag(PointerEventData e) { Tray.EndDrag(e); }
    }
}
