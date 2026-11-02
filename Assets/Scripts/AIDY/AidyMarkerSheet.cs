using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// 着せ替えマーカーの作成画面 (下から出るシート)。いまの着せ替えを空いている ID のマーカーとして保存し、
    /// マーカーの画像を表示・保存する。ID0 (いまの着せ替えにいつも追従) もここで表示・保存できる。
    /// </summary>
    public class AidyMarkerSheet : MonoBehaviour
    {
        RectTransform chips, info;
        RawImage markerImage;
        TMP_Text nameLabel, summaryLabel, noteLabel, message;
        GameObject deleteButton;
        readonly List<(int id, Image bg, TMP_Text label)> chipViews = new List<(int, Image, TMP_Text)>();
        Texture2D markerTex;
        int selected;

        public bool IsOpen => gameObject.activeSelf;

        public static AidyMarkerSheet Build(RectTransform parent)
        {
            var root = UI.Rect("MarkerSheet", parent).Stretch();
            var sheet = root.gameObject.AddComponent<AidyMarkerSheet>();
            sheet.BuildUI(root);
            root.gameObject.SetActive(false);
            return sheet;
        }

        void BuildUI(RectTransform root)
        {
            var dim = UI.Image(root, "Dim", new Color(0, 0, 0, 0.45f), 0, true);
            dim.rectTransform.Stretch();
            dim.gameObject.AddComponent<Clickable>().OnClick = Close;

            var panel = UI.Rect("Panel", root);
            panel.anchorMin = new Vector2(0, 0);
            panel.anchorMax = new Vector2(1, 0);
            panel.pivot = new Vector2(0.5f, 0);
            panel.offsetMin = new Vector2(12, 12);
            panel.offsetMax = new Vector2(-12, 12);
            UI.Box(panel, Color.white, 22, new BoxShadow(0, 8, 24, new Color(0, 0, 0, 0.2f))).raycastTarget = true;
            panel.VLayout(18, 18, 16, 16, 12).childForceExpandWidth = true;
            panel.FitContent(false, true);

            // 見出し
            var head = UI.Rect("Head", panel);
            head.HLayout(0, 0, 0, 0, 8);
            head.Layout(-1, 26);
            UI.Icon(head, IconId.Camera, 14, Theme.Primary);
            UI.Label(head, "着せ替えマーカー", 15, Theme.TextMain, true).Layout(-1, -1, 1);
            var close = UI.Rect("Close", head);
            close.Layout(26, 26);
            UI.Image(close, "Bg", Theme.Slate100, 13, true).rectTransform.Stretch();
            UI.Icon(close, IconId.XMark, 11, Theme.TextSub).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(11, 11));
            close.gameObject.AddComponent<Clickable>().OnClick = Close;

            // マーカーの一覧 (横スクロール)
            var scroll = UI.Rect("ChipScroll", panel);
            scroll.Layout(-1, 32);
            scroll.gameObject.AddComponent<RectMask2D>();
            UI.Image(scroll, "Hit", new Color(1, 1, 1, 0), 0, true).rectTransform.Stretch();
            chips = UI.Rect("Chips", scroll);
            chips.anchorMin = new Vector2(0, 0);
            chips.anchorMax = new Vector2(0, 1);
            chips.pivot = new Vector2(0, 0.5f);
            chips.HLayout(0, 0, 0, 0, 6);
            chips.FitContent(true, false);
            var sr = scroll.gameObject.AddComponent<ScrollRect>();
            sr.content = chips;
            sr.horizontal = true;
            sr.vertical = false;
            sr.movementType = ScrollRect.MovementType.Clamped;
            sr.viewport = scroll;

            // マーカーの画像と説明
            info = UI.Rect("Info", panel);
            info.HLayout(0, 0, 0, 0, 14, TextAnchor.MiddleLeft);
            info.Layout(-1, 136);
            var frame = UI.Rect("Frame", info);
            frame.Layout(136, 136);
            UI.Box(frame, Color.white, 8, null, Theme.Border);
            markerImage = UI.Rect("Marker", frame).Stretch(4, 4, 4, 4).gameObject.AddComponent<RawImage>();
            markerImage.raycastTarget = false;
            var text = UI.Rect("Text", info);
            text.VLayout(0, 0, 0, 0, 6);
            text.Layout(-1, -1, 1);
            nameLabel = UI.Label(text, "", 15, Theme.TextMain, true);
            summaryLabel = Wrapped(text, 12, Theme.TextSub);
            noteLabel = Wrapped(text, 11, Theme.Primary);

            // ボタン
            var buttons = UI.Rect("Buttons", panel);
            buttons.HLayout(0, 0, 0, 0, 8).childForceExpandWidth = true;
            buttons.Layout(-1, 42);
            Button(buttons, IconId.Plus, "いまの着せ替えで作成", true).OnClick = CreateFromCurrent;
            Button(buttons, IconId.Check, "画像を保存", false).OnClick = SaveImage;
            var del = Button(buttons, IconId.XMark, "削除", false);
            del.OnClick = DeleteSelected;
            del.GetComponent<LayoutElement>().flexibleWidth = 0.5f;
            deleteButton = del.gameObject;

            message = Wrapped(panel, 11, Theme.TextSub);
            message.alignment = TextAlignmentOptions.Center;
        }

        static TMP_Text Wrapped(Transform parent, float size, Color color)
        {
            TMP_Text t = UI.Label(parent, "", size, color);
            t.enableWordWrapping = true;
            t.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            return t;
        }

        static Clickable Button(RectTransform parent, IconId icon, string text, bool primary)
        {
            var b = UI.Rect("Button", parent);
            b.Layout(-1, 42, 1);
            var bg = UI.Image(b, "Bg", primary ? Theme.Primary : Theme.Slate100, 21, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            b.HLayout(8, 8, 0, 0, 6, TextAnchor.MiddleCenter);
            Color fg = primary ? Color.white : Theme.Slate700;
            UI.Icon(b, icon, 11, fg);
            UI.Label(b, text, 12, fg, true);
            var c = b.gameObject.AddComponent<Clickable>();
            c.PressScale = 0.97f;
            return c;
        }

        // ---------------------------------------------------------------- 開く / 閉じる

        /// <summary>開く。いまの着せ替えと同じマーカーがあればそれを、なければ ID0 を選ぶ。</summary>
        public void Open()
        {
            gameObject.SetActive(true);
            transform.SetAsLastSibling();
            message.text = "";
            int? same = AidyMarkerStore.Find(ModuleConfig.Slots);
            Select(same ?? AidyMarkers.MyId);
        }

        public void Close() => gameObject.SetActive(false);

        void Select(int id)
        {
            selected = id;
            RebuildChips();
            if (markerTex != null) Destroy(markerTex);
            markerTex = AidyMarkerImage.Create(id, 16);
            markerImage.texture = markerTex;

            bool live = id == AidyMarkers.MyId;
            nameLabel.text = AidyMarkers.Name(id) + $"  (ID{id})";
            summaryLabel.text = AidyMarkers.Summary(live ? ModuleConfig.Slots : AidyMarkers.Config(id));
            noteLabel.text = live
                ? $"いつもいまの着せ替えで出るマーカー。黒い四角を{AidyMarkers.SizeMm(id)}mmで印刷してね"
                : $"作ったときの着せ替えで出るマーカー (この端末だけ)。黒い四角を{AidyMarkers.SizeMm(id)}mmで印刷してね";
            deleteButton.SetActive(!live);
            LayoutRebuilder.MarkLayoutForRebuild((RectTransform)transform);
        }

        void RebuildChips()
        {
            foreach (var c in chipViews) Destroy(c.bg.transform.parent.gameObject);
            chipViews.Clear();
            Chip(AidyMarkers.MyId, "いまの着せ替え");
            foreach (int id in AidyMarkerStore.Ids) Chip(id, "#" + id);
        }

        void Chip(int id, string text)
        {
            bool on = id == selected;
            var c = UI.Rect("Chip_" + id, chips);
            c.HLayout(12, 12, 0, 0, 0, TextAnchor.MiddleCenter);
            c.Layout(-1, 30);
            var bg = UI.Image(c, "Bg", on ? Theme.Primary : Theme.Slate100, 15, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            var label = UI.Label(c, text, 12, on ? Color.white : Theme.Slate700, true);
            c.gameObject.AddComponent<Clickable>().OnClick = () =>
            {
                message.text = "";
                Select(id);
            };
            chipViews.Add((id, bg, label));
        }

        // ---------------------------------------------------------------- 操作

        void CreateFromCurrent()
        {
            int? same = AidyMarkerStore.Find(ModuleConfig.Slots);
            if (same.HasValue)
            {
                Select(same.Value);
                message.text = $"同じ着せ替えのマーカーがもうあるよ (#{same.Value})";
                return;
            }
            int? id = AidyMarkerStore.Create(ModuleConfig.Slots);
            if (!id.HasValue)
            {
                message.text = "マーカーがいっぱいです。使わないマーカーを削除してね";
                return;
            }
            Select(id.Value);
            message.text = $"マーカー #{id.Value} を作ったよ！ 画像を保存して印刷してね";
        }

        void SaveImage()
        {
            try
            {
                message.text = "保存しました: " + AidyMarkerImage.SavePng(selected);
            }
            catch (System.Exception e)
            {
                Debug.LogException(e);
                message.text = "保存に失敗しました";
            }
            UI.EnsureChars(message.text);
        }

        void DeleteSelected()
        {
            if (selected == AidyMarkers.MyId) return;
            int id = selected;
            AidyMarkerStore.Delete(id);
            Select(AidyMarkers.MyId);
            message.text = $"マーカー #{id} を削除しました";
        }

        void OnDestroy()
        {
            if (markerTex != null) Destroy(markerTex);
        }
    }
}
