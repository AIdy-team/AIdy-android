using System;
using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// 日記ページ: 月カレンダー (日記のある日にミニ Aidy) + 選択日の日記カード + 会話ログ。
    /// 本体との同期は AidyDeviceLink、保存は DiaryStore。
    /// </summary>
    public class AidyDiaryPage : MonoBehaviour
    {
        const int SyncDays = 7;
        const float BubbleMaxWidth = 220f;

        static readonly string[] WeekLabels = { "日", "月", "火", "水", "木", "金", "土" };
        static readonly Color Sunday = Theme.Hex(0xef4444);
        static readonly Color Saturday = Theme.Hex(0x3b82f6);

        DateTime month;     // 表示中の月 (1日)
        DateTime selected;
        bool syncing, autoSynced, logsOpen;

        TMP_Text monthLabel, syncText, syncButtonText;
        AidyMascot headerMascot;
        RectTransform entryRoot;
        readonly List<RectTransform> weekRows = new List<RectTransform>();
        readonly List<DayCell> cells = new List<DayCell>();

        class DayCell { public Image Bg, Face; public TMP_Text Num; public Clickable Click; public DateTime Date; }

        public static AidyDiaryPage Build(RectTransform page, RectTransform content)
        {
            DiaryDemo.SeedIfNeeded();
            var p = page.gameObject.AddComponent<AidyDiaryPage>();
            p.selected = DateTime.Today;
            p.month = new DateTime(p.selected.Year, p.selected.Month, 1);

            p.BuildHeader(content);
            UI.Spacer(content, 14);
            p.BuildCalendar(content);
            UI.Spacer(content, 12);
            p.entryRoot = UI.Rect("Entry", content);
            p.entryRoot.VLayout(0, 0, 0, 0, 0).childForceExpandWidth = true;

            p.RefreshCalendar();
            p.RefreshEntry();
            return p;
        }

        /// <summary>ページが表示されたとき。初回だけ本体と自動同期する。</summary>
        public void OnShown()
        {
            if (autoSynced) return;
            autoSynced = true;
            StartSync(true);
        }

        /// <summary>検証用: いちばん新しい日記を選んで会話ログを開く。</summary>
        public void ShowLatestWithLogs()
        {
            List<DiaryEntry> all = DiaryStore.All();
            if (all.Count == 0) return;
            DateTime d = DateTime.ParseExact(all[0].date, "yyyy-MM-dd", null);
            logsOpen = true;
            Select(d);
        }

        // ---------------------------------------------------------------- Header

        void BuildHeader(RectTransform c)
        {
            UI.Spacer(c, 6);
            var row = UI.Rect("DiaryHeader", c);
            row.HLayout(4, 0, 0, 0, 10);
            headerMascot = AidyMascot.Create(row, 44, AidyFace.Normal, true, true);

            var info = UI.Rect("Info", row);
            info.VLayout(0, 0, 0, 0, 2);
            info.Layout(-1, -1, 1);
            UI.Label(info, "Aidyの日記", 18, Theme.TextMain, true);
            syncText = UI.Label(info, AidyDeviceLink.LastSyncLabel, 11, Theme.TextSub);

            var btn = UI.Rect("SyncButton", row);
            UI.Box(btn, Theme.PrimaryLight, 16, null, Theme.Hex(0xbfdbfe)).raycastTarget = true;
            btn.HLayout(12, 12, 8, 8, 6, TextAnchor.MiddleCenter);
            UI.Icon(btn, IconId.Wifi, 12, Theme.Primary);
            syncButtonText = UI.Label(btn, "同期", 12, Theme.Primary, true);
            var click = btn.gameObject.AddComponent<Clickable>();
            click.PressScale = 0.95f;
            click.OnClick = () => StartSync(false);
        }

        // ---------------------------------------------------------------- Calendar

        void BuildCalendar(RectTransform c)
        {
            var cal = UI.Rect("Calendar", c);
            UI.Box(cal, Color.white, 18, new BoxShadow(0, 4, 16, new Color(0, 0, 0, 0.04f)), Theme.Border);
            cal.VLayout(10, 10, 10, 12, 0).childForceExpandWidth = true;

            var head = UI.Rect("MonthHeader", cal);
            head.HLayout(0, 0, 0, 0, 0, TextAnchor.MiddleCenter);
            head.Layout(-1, 36);
            MonthButton(head, IconId.ChevronLeft).OnClick = () => ShiftMonth(-1);
            monthLabel = UI.Label(head, "", 15, Theme.TextMain, true, TextAnchor.MiddleCenter);
            monthLabel.Layout(-1, -1, 1);
            MonthButton(head, IconId.ChevronRight).OnClick = () => ShiftMonth(1);

            UI.Spacer(cal, 6);
            var week = UI.Rect("WeekLabels", cal);
            week.HLayout(0, 0, 0, 0, 2).childForceExpandWidth = true;
            for (int i = 0; i < 7; i++)
            {
                Color col = i == 0 ? Sunday : i == 6 ? Saturday : Theme.TextSub;
                UI.Label(week, WeekLabels[i], 11, col, true, TextAnchor.MiddleCenter).Layout(0, 18, 1);
            }
            UI.Spacer(cal, 4);

            for (int r = 0; r < 6; r++)
            {
                var row = UI.Rect("Week" + r, cal);
                row.HLayout(0, 0, 0, 0, 2).childForceExpandWidth = true;
                row.Layout(-1, 50);
                weekRows.Add(row);
                for (int i = 0; i < 7; i++) cells.Add(CreateCell(row));
            }
        }

        static Clickable MonthButton(RectTransform parent, IconId icon)
        {
            var b = UI.Rect("MonthButton", parent);
            b.Layout(36, 36);
            UI.Image(b, "Bg", Theme.Slate100, 18, true).rectTransform.Stretch();
            UI.Icon(b, icon, 12, Theme.Slate600).rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(12, 12));
            var c = b.gameObject.AddComponent<Clickable>();
            c.PressScale = 0.92f;
            return c;
        }

        DayCell CreateCell(RectTransform row)
        {
            var rt = UI.Rect("Day", row);
            rt.Layout(0, 50, 1);
            rt.gameObject.AddComponent<Image>().color = Color.clear; // タップ領域

            var cell = new DayCell();
            cell.Bg = UI.Image(rt, "Bg", Color.clear, 12);
            cell.Bg.rectTransform.Pin(new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(40, 48));
            cell.Num = UI.Label(rt, "", 13, Theme.TextMain, true, TextAnchor.MiddleCenter);
            cell.Num.rectTransform.Pin(new Vector2(0.5f, 0.5f), new Vector2(0, 9), new Vector2(40, 18));
            cell.Face = UI.Image(rt, "Face", Color.white);
            cell.Face.preserveAspect = true;
            cell.Face.rectTransform.Pin(new Vector2(0.5f, 0.5f), new Vector2(0, -10), new Vector2(16, 16));
            cell.Click = rt.gameObject.AddComponent<Clickable>();
            cell.Click.OnClick = () => Select(cell.Date);
            return cell;
        }

        void ShiftMonth(int delta)
        {
            month = month.AddMonths(delta);
            RefreshCalendar();
        }

        void Select(DateTime date)
        {
            selected = date.Date;
            month = new DateTime(selected.Year, selected.Month, 1);
            RefreshCalendar();
            RefreshEntry();
        }

        void RefreshCalendar()
        {
            monthLabel.text = $"{month.Year}年{month.Month}月";
            int offset = (int)month.DayOfWeek;
            int days = DateTime.DaysInMonth(month.Year, month.Month);
            int rows = Mathf.CeilToInt((offset + days) / 7f);
            for (int r = 0; r < weekRows.Count; r++) weekRows[r].gameObject.SetActive(r < rows);

            DateTime today = DateTime.Today;
            for (int i = 0; i < cells.Count; i++)
            {
                DayCell cell = cells[i];
                int day = i - offset + 1;
                if (day < 1 || day > days)
                {
                    cell.Num.text = "";
                    cell.Bg.color = Color.clear;
                    cell.Face.enabled = false;
                    cell.Click.Interactable = false;
                    continue;
                }

                DateTime date = new DateTime(month.Year, month.Month, day);
                cell.Date = date;
                cell.Click.Interactable = true;
                DiaryEntry entry = DiaryStore.Get(date);
                bool sel = date == selected, isToday = date == today;
                int dow = (int)date.DayOfWeek;

                cell.Bg.color = sel ? Theme.Primary : isToday ? Theme.PrimaryLight : Color.clear;
                cell.Num.text = day.ToString();
                cell.Num.color = sel ? Color.white
                    : isToday ? Theme.Primary
                    : date > today ? Theme.Slate300
                    : dow == 0 ? Sunday
                    : dow == 6 ? Saturday
                    : Theme.TextMain;
                cell.Face.enabled = entry != null;
                if (entry != null) cell.Face.sprite = AidyMascotArt.Get(DiaryComposer.FaceFor(entry.mood), 16);
            }
        }

        // ---------------------------------------------------------------- Entry

        void RefreshEntry()
        {
            foreach (Transform child in entryRoot)
            {
                child.gameObject.SetActive(false);
                Destroy(child.gameObject);
            }

            DiaryEntry e = DiaryStore.Get(selected);
            if (e != null) BuildEntry(e);
            else BuildEmpty();
        }

        static string DateLabel(DateTime d) => $"{d.Year}年{d.Month}月{d.Day}日 ({WeekLabels[(int)d.DayOfWeek]})";

        RectTransform CardRoot(string name)
        {
            var card = UI.Rect(name, entryRoot);
            UI.Box(card, Color.white, 18, new BoxShadow(0, 4, 16, new Color(0, 0, 0, 0.04f)), Theme.Border);
            return card;
        }

        void BuildEntry(DiaryEntry e)
        {
            var card = CardRoot("DiaryCard");
            card.VLayout(16, 16, 16, 16, 0).childForceExpandWidth = true;

            var head = UI.Rect("Head", card);
            head.HLayout(0, 0, 0, 0, 12);
            AidyMascot.Create(head, 48, DiaryComposer.FaceFor(e.mood), true, true);
            var info = UI.Rect("Info", head);
            info.VLayout(0, 0, 0, 0, 3).childForceExpandWidth = true;
            info.Layout(-1, -1, 1);
            UI.Label(info, DateLabel(selected), 12, Theme.TextSub, true);
            Paragraph(info, e.title, 16, Theme.TextMain, true);
            if (e.source == "demo") Pill(head, "サンプル", Theme.Hex(0xfef3c7), Theme.Hex(0xb45309));

            UI.Spacer(card, 14);
            Paragraph(card, e.summary, 13, Theme.Slate700, false).lineSpacing = 12;

            UI.Spacer(card, 14);
            var stats = UI.Rect("Stats", card);
            stats.HLayout(0, 0, 0, 0, 8);
            Pill(stats, $"おしゃべり {e.UserCount}回", Theme.PrimaryLight, Theme.Primary);
            if (e.logs.Count > 0) Pill(stats, $"{e.logs[0].time} 〜 {e.logs[e.logs.Count - 1].time}", Theme.Slate100, Theme.Slate600);
            if (e.logs.Count == 0) return;

            UI.Spacer(card, 14);
            var line = UI.Image(card, "Divider", Theme.Slate100);
            line.Layout(-1, 1);
            UI.Spacer(card, 4);

            var toggle = UI.Rect("LogToggle", card);
            toggle.gameObject.AddComponent<Image>().color = Color.clear;
            toggle.HLayout(0, 0, 8, 4, 6);
            UI.Icon(toggle, IconId.Aidy, 13, Theme.TextSub);
            UI.Label(toggle, "会話ログ", 13, Theme.TextSub, true).Layout(-1, -1, 1);
            UI.Label(toggle, $"{e.logs.Count}件", 11, Theme.Slate400);
            var chevron = UI.Icon(toggle, IconId.ChevronRight, 11, Theme.Slate400);

            var logRoot = UI.Rect("Logs", card);
            logRoot.VLayout(0, 0, 10, 0, 10).childForceExpandWidth = true;
            foreach (ChatLine l in e.logs) ChatRow(logRoot, l);

            void Apply()
            {
                logRoot.gameObject.SetActive(logsOpen);
                chevron.rectTransform.localEulerAngles = new Vector3(0, 0, logsOpen ? -90 : 0);
            }
            Apply();
            toggle.gameObject.AddComponent<Clickable>().OnClick = () =>
            {
                logsOpen = !logsOpen;
                Apply();
            };
        }

        void ChatRow(RectTransform parent, ChatLine l)
        {
            bool user = l.IsUser;
            var row = UI.Rect("Chat", parent);
            row.HLayout(0, 0, 0, 0, 6, user ? TextAnchor.LowerRight : TextAnchor.LowerLeft);

            if (!user) AidyMascot.Create(row, 24, AidyFace.Normal, false);
            if (user) UI.Label(row, l.time, 10, Theme.Slate400);

            var bubble = UI.Rect("Bubble", row);
            UI.Box(bubble, user ? Theme.Primary : Theme.Slate100, 14);
            bubble.HLayout(12, 12, 8, 8, 0);
            UI.EnsureChars(l.text);
            var t = UI.Label(bubble, l.text, 13, user ? Color.white : Theme.TextMain);
            t.enableWordWrapping = true;
            Vector2 s = t.GetPreferredValues(l.text, BubbleMaxWidth, 0);
            t.Layout(Mathf.Ceil(Mathf.Min(s.x, BubbleMaxWidth)) + 1, Mathf.Ceil(t.GetPreferredValues(l.text, Mathf.Min(s.x, BubbleMaxWidth) + 1, 0).y));

            if (!user) UI.Label(row, l.time, 10, Theme.Slate400);
        }

        void BuildEmpty()
        {
            var card = CardRoot("EmptyCard");
            card.VLayout(20, 20, 22, 22, 6, TextAnchor.UpperCenter);

            DateTime today = DateTime.Today;
            bool future = selected > today, isToday = selected == today;
            AidyMascot.Create(card, 64, future ? AidyFace.Normal : AidyFace.Sleepy, true, true);
            UI.Spacer(card, 4);
            UI.Label(card, DateLabel(selected), 12, Theme.TextSub, true, TextAnchor.MiddleCenter);
            string main = future ? "まだ来ていない日だよ" : isToday ? "今日の日記はまだないよ" : "この日の日記はないみたい";
            UI.Label(card, main, 15, Theme.TextMain, true, TextAnchor.MiddleCenter);
            string sub = future ? "その日になったら、いっしょにお話ししようね" : "おしゃべりすると、会話ログから日記ができるよ";
            UI.Label(card, sub, 12, Theme.TextSub, false, TextAnchor.MiddleCenter);

            if (!isToday) return;
            UI.Spacer(card, 8);
            var btn = UI.Rect("FetchButton", card);
            UI.AddShadow(btn, 20, new BoxShadow(0, 4, 12, Theme.Hex(0x3b82f6, 0.3f)));
            var bg = UI.Image(btn, "Bg", Theme.Primary, 20, true);
            bg.rectTransform.Stretch();
            bg.IgnoreLayout();
            btn.HLayout(18, 18, 10, 10, 8, TextAnchor.MiddleCenter);
            UI.Icon(btn, IconId.Wifi, 12, Color.white);
            UI.Label(btn, "Aidyから会話ログを受け取る", 13, Color.white, true);
            var click = btn.gameObject.AddComponent<Clickable>();
            click.PressScale = 0.96f;
            click.OnClick = () => StartSync(false);
        }

        static TMP_Text Paragraph(RectTransform parent, string text, float size, Color color, bool bold)
        {
            UI.EnsureChars(text);
            var t = UI.Label(parent, text, size, color, bold);
            t.enableWordWrapping = true;
            t.Layout(-1, -1, 1);
            return t;
        }

        static void Pill(RectTransform parent, string text, Color bg, Color fg)
        {
            var p = UI.Rect("Pill", parent);
            UI.Box(p, bg, 10);
            p.HLayout(10, 10, 4, 4, 0, TextAnchor.MiddleCenter);
            UI.Label(p, text, 11, fg, true);
        }

        // ---------------------------------------------------------------- Sync

        void StartSync(bool silent)
        {
            if (syncing) return;
            syncing = true;
            syncButtonText.text = "同期中…";
            headerMascot.React(AidyFace.Surprised, 0.6f);
            StartCoroutine(AidyDeviceLink.SyncRecent(SyncDays, (ok, message, updated) =>
            {
                syncing = false;
                syncButtonText.text = "同期";
                syncText.text = ok || !silent ? message : "未接続 ・ " + AidyDeviceLink.LastSyncLabel;
                headerMascot.React(ok ? AidyFace.Happy : AidyFace.Sleepy, 1.6f);
                if (updated > 0)
                {
                    RefreshCalendar();
                    RefreshEntry();
                }
            }));
        }
    }
}
