using System;
using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// 日記ページ: 月カレンダー (日記のある日にミニ Aidy、予定のある日にタイトル) + 選択日の日記カード + 会話ログ + 予定カード。
    /// 本体との同期は AidyDeviceLink、日記の保存は DiaryStore、予定の保存は ScheduleStore。
    /// </summary>
    public class AidyDiaryPage : MonoBehaviour
    {
        const int SyncDays = 7;
        const float BubbleMaxWidth = 220f;
        const float CellHeight = 50f; // 日付 + ミニ Aidy または予定タイトル 1 件
        const int MaxChips = 3;       // 1 日に並べる予定タイトルの数
        const float ChipTop = 29f, ChipHeight = 12f, ChipPitch = 13f;

        static readonly string[] WeekLabels = { "日", "月", "火", "水", "木", "金", "土" };
        static readonly Color Sunday = Theme.Hex(0xef4444);
        static readonly Color Saturday = Theme.Hex(0x3b82f6);
        static readonly Color Amber = Theme.Hex(0xf59e0b);
        static readonly Color ChipBg = Theme.Hex(0xfef3c7);
        static readonly Color ChipFg = Theme.Hex(0xb45309);
        static readonly Color Danger = Theme.Hex(0xef4444);

        DateTime month;     // 表示中の月 (1日)
        DateTime weekAnchor; // 表示中の週の日曜日
        DateTime selected;
        bool weekView;
        bool syncing, autoSynced, logsOpen;
        string shownMinute; // 予定の並び (過ぎた予定を後ろへ) を更新した時刻 "yyyy-MM-dd HH:mm"
        string editingId; // 予定フォーム: null=閉じている / ""=新規 / それ以外=編集中の予定 id
        bool confirmDelete;

        TMP_Text monthLabel, syncText, syncButtonText;
        AidyMascot headerMascot;
        RectTransform entryRoot;
        readonly List<RectTransform> weekRows = new List<RectTransform>();
        readonly List<DayCell> cells = new List<DayCell>();

        class DayCell { public Image Bg, Ring, Face; public TMP_Text Num, More; public Image[] Chips; public TMP_Text[] ChipTexts; public Clickable Click; public DateTime Date; }

        public static AidyDiaryPage Build(RectTransform page, RectTransform content)
        {
            DiaryDemo.SeedIfNeeded();
            var p = page.gameObject.AddComponent<AidyDiaryPage>();
            p.selected = DateTime.Today;
            p.weekView = CalendarSettings.WeekView;
            p.month = new DateTime(p.selected.Year, p.selected.Month, 1);
            p.weekAnchor = p.selected.AddDays(-(int)p.selected.DayOfWeek);

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

        /// <summary>分が変わったら並べ直す (時刻を過ぎた予定を後ろへ回し、次の予定を先頭に出す)。</summary>
        void Update()
        {
            string minute = DateTime.Now.ToString("yyyy-MM-dd HH:mm");
            if (minute == shownMinute) return;
            bool first = shownMinute == null;
            shownMinute = minute;
            if (first) return;
            RefreshCalendar();
            if (editingId == null) RefreshEntry(); // 入力中のフォームは作り直さない
        }

        /// <summary>設定画面から月表示/週表示を切り替える。</summary>
        public void SetWeekView(bool on)
        {
            if (weekView == on) return;
            weekView = on;
            month = new DateTime(selected.Year, selected.Month, 1);
            weekAnchor = selected.AddDays(-(int)selected.DayOfWeek);
            RefreshCalendar();
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
            MonthButton(head, IconId.ChevronLeft).OnClick = () => ShiftPeriod(-1);
            monthLabel = UI.Label(head, "", 15, Theme.TextMain, true, TextAnchor.MiddleCenter);
            monthLabel.Layout(-1, -1, 1);
            MonthButton(head, IconId.ChevronRight).OnClick = () => ShiftPeriod(1);

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
                row.Layout(-1, CellHeight);
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
            rt.Layout(0, CellHeight, 1);
            rt.gameObject.AddComponent<Image>().color = Color.clear; // タップ領域

            var cell = new DayCell();
            cell.Bg = UI.Image(rt, "Bg", Color.clear, 12);
            cell.Bg.rectTransform.Stretch(1, 1, 1, 1);
            // 選択中は塗りつぶさず枠線で示す (予定タイトルの色を隠さないため)
            cell.Ring = UI.Image(rt, "SelectedRing", Theme.Primary);
            UI.SetRadius(cell.Ring, 12, 2);
            cell.Ring.rectTransform.Stretch(1, 1, 1, 1);
            cell.Num = UI.Label(rt, "", 13, Theme.TextMain, true, TextAnchor.MiddleCenter);
            // 予定タイトルの数で行の高さが変わるので、すべて上端基準で置く
            cell.Num.rectTransform.Pin(new Vector2(0.5f, 1), new Vector2(0, -7), new Vector2(40, 18));
            cell.Face = UI.Image(rt, "Face", Color.white);
            cell.Face.preserveAspect = true;
            cell.Face.rectTransform.Pin(new Vector2(0.5f, 1), new Vector2(0, -27), new Vector2(16, 16));

            // 予定タイトル (最大 MaxChips 件を縦に並べる。入りきらない文字は … で省略)。
            // 予定を入れるのはふつう未来の日 (日記=ミニ Aidy がない日) なので、ミニ Aidy と同じ位置から並べる
            cell.Chips = new Image[MaxChips];
            cell.ChipTexts = new TMP_Text[MaxChips];
            for (int i = 0; i < MaxChips; i++)
            {
                var chip = UI.Image(rt, "ScheduleChip", ChipBg, 4);
                var crt = chip.rectTransform;
                float top = ChipTop + i * ChipPitch;
                crt.anchorMin = new Vector2(0, 1);
                crt.anchorMax = new Vector2(1, 1);
                crt.offsetMin = new Vector2(3, -(top + ChipHeight));
                crt.offsetMax = new Vector2(-3, -top);
                var text = UI.Label(crt, "", 8f, ChipFg, true, TextAnchor.MiddleCenter);
                text.rectTransform.Stretch(2, 0, 2, 0);
                text.overflowMode = TextOverflowModes.Ellipsis;
                cell.Chips[i] = chip;
                cell.ChipTexts[i] = text;
            }

            // 表示しきれない予定の件数 (右上の「+1」)
            cell.More = UI.Label(rt, "", 8, Amber, true, TextAnchor.MiddleCenter);
            cell.More.rectTransform.Pin(new Vector2(1, 1), new Vector2(-2, -4), new Vector2(16, 10));
            cell.Click = rt.gameObject.AddComponent<Clickable>();
            cell.Click.OnClick = () => Select(cell.Date);
            return cell;
        }

        void ShiftPeriod(int delta)
        {
            if (weekView) weekAnchor = weekAnchor.AddDays(7 * delta);
            else month = month.AddMonths(delta);
            RefreshCalendar();
        }

        void Select(DateTime date)
        {
            if (date.Date != selected)
            {
                editingId = null;
                confirmDelete = false;
            }
            selected = date.Date;
            month = new DateTime(selected.Year, selected.Month, 1);
            weekAnchor = selected.AddDays(-(int)selected.DayOfWeek);
            RefreshCalendar();
            RefreshEntry();
        }

        void RefreshCalendar()
        {
            if (weekView) RefreshWeekView();
            else RefreshMonthView();
        }

        void RefreshMonthView()
        {
            monthLabel.text = $"{month.Year}年{month.Month}月";
            int offset = (int)month.DayOfWeek;
            int days = DateTime.DaysInMonth(month.Year, month.Month);
            int rows = Mathf.CeilToInt((offset + days) / 7f);
            for (int r = 0; r < weekRows.Count; r++) weekRows[r].gameObject.SetActive(r < rows);

            var chips = new int[weekRows.Count];
            for (int i = 0; i < cells.Count; i++)
            {
                int day = i - offset + 1;
                int n = day < 1 || day > days ? ClearCell(cells[i]) : ApplyCell(cells[i], new DateTime(month.Year, month.Month, day));
                chips[i / 7] = Mathf.Max(chips[i / 7], n);
            }
            for (int r = 0; r < weekRows.Count; r++) SetRowHeight(r, chips[r]);
        }

        void RefreshWeekView()
        {
            DateTime weekEnd = weekAnchor.AddDays(6);
            monthLabel.text = WeekRangeLabel(weekAnchor, weekEnd);
            for (int r = 0; r < weekRows.Count; r++) weekRows[r].gameObject.SetActive(r == 0);

            int chips = 0;
            for (int i = 0; i < cells.Count; i++)
            {
                if (i >= 7) ClearCell(cells[i]);
                else chips = Mathf.Max(chips, ApplyCell(cells[i], weekAnchor.AddDays(i)));
            }
            SetRowHeight(0, chips);
        }

        /// <summary>行の中でいちばん多い予定タイトル数に合わせて、その週の行だけ縦に伸ばす。</summary>
        void SetRowHeight(int row, int chips)
        {
            float h = CellHeight + Mathf.Max(0, chips - 1) * ChipPitch;
            weekRows[row].Layout(-1, h);
            for (int i = 0; i < 7; i++) cells[row * 7 + i].Click.Layout(0, h, 1);
        }

        static string WeekRangeLabel(DateTime start, DateTime end)
        {
            if (start.Year != end.Year) return $"{start.Year}/{start.Month}/{start.Day} 〜 {end.Year}/{end.Month}/{end.Day}";
            if (start.Month != end.Month) return $"{start.Year}年{start.Month}月{start.Day}日 〜 {end.Month}月{end.Day}日";
            return $"{start.Year}年{start.Month}月{start.Day}日 〜 {end.Day}日";
        }

        /// <summary>戻り値はセルに並べた予定タイトルの数。</summary>
        static int ClearCell(DayCell cell)
        {
            cell.Num.text = "";
            cell.Bg.color = Color.clear;
            cell.Ring.enabled = false;
            cell.Face.enabled = false;
            cell.More.enabled = false;
            for (int i = 0; i < MaxChips; i++) cell.Chips[i].enabled = cell.ChipTexts[i].enabled = false;
            cell.Click.Interactable = false;
            return 0;
        }

        /// <summary>戻り値はセルに並べた予定タイトルの数。</summary>
        int ApplyCell(DayCell cell, DateTime date)
        {
            cell.Date = date;
            DateTime today = DateTime.Today;
            cell.Click.Interactable = true; // 未来の日も選んで予定を入れられる
            DiaryEntry entry = DiaryStore.Get(date);
            bool sel = date == selected, isToday = date == today;
            int dow = (int)date.DayOfWeek;

            cell.Bg.color = sel || isToday ? Theme.PrimaryLight : Color.clear;
            cell.Ring.enabled = sel;
            cell.Num.text = date.Day.ToString();
            cell.Num.color = sel || isToday ? Theme.Primary
                : dow == 0 ? Sunday
                : dow == 6 ? Saturday
                : Theme.TextMain;
            cell.Face.enabled = entry != null;
            if (entry != null) cell.Face.sprite = AidyMascotArt.Get(DiaryComposer.FaceFor(entry.mood), 16);

            List<ScheduleItem> plans = ScheduleStore.For(date);
            int shown = entry == null ? Mathf.Min(plans.Count, MaxChips) : 0; // 日記のある日はミニ Aidy を優先
            int hidden = plans.Count - shown;
            cell.More.enabled = hidden > 0;
            cell.More.text = "+" + hidden;
            cell.More.color = Amber;
            for (int i = 0; i < MaxChips; i++)
            {
                bool on = i < shown;
                cell.Chips[i].enabled = cell.ChipTexts[i].enabled = on;
                if (!on) continue;
                UI.EnsureChars(plans[i].title);
                cell.ChipTexts[i].text = plans[i].title;
            }
            return shown;
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
            UI.Spacer(entryRoot, 12);
            BuildSchedule();
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
            string sub = future ? "下の「予定」から、この日の予定を入れておけるよ" : "おしゃべりすると、会話ログから日記ができるよ";
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

        // ---------------------------------------------------------------- Schedule

        void BuildSchedule()
        {
            var card = CardRoot("ScheduleCard");
            card.VLayout(16, 16, 14, 16, 0).childForceExpandWidth = true;

            var head = UI.Rect("Head", card);
            head.HLayout(0, 0, 0, 0, 6);
            head.Layout(-1, 32);
            UI.Icon(head, IconId.MapPin, 13, Amber);
            UI.Label(head, "予定", 14, Theme.TextMain, true).Layout(-1, -1, 1);
            if (editingId == null)
                SmallButton(head, "＋ 追加", Theme.PrimaryLight, Theme.Primary, () => OpenEditor(""));

            List<ScheduleItem> list = ScheduleStore.For(selected);
            if (list.Count == 0 && editingId == null)
            {
                UI.Spacer(card, 6);
                UI.Label(card, "この日の予定はまだないよ", 12, Theme.Slate400);
            }

            foreach (ScheduleItem item in list)
            {
                UI.Spacer(card, 8);
                if (item.id == editingId) BuildEditor(card, item);
                else ScheduleRow(card, item);
            }

            if (editingId == "")
            {
                UI.Spacer(card, 8);
                BuildEditor(card, new ScheduleItem { date = DiaryStore.Key(selected) });
            }
        }

        void ScheduleRow(RectTransform parent, ScheduleItem item)
        {
            var row = UI.Rect("ScheduleRow", parent);
            UI.Box(row, Theme.Slate100, 12).raycastTarget = true;
            row.HLayout(12, 12, 10, 10, 10);

            bool past = item.IsPast(DateTime.Now);
            UI.Label(row, item.AllDay ? "終日" : item.time, 12, past ? Theme.Slate400 : item.AllDay ? Amber : Theme.Primary, true).Layout(44);

            var body = UI.Rect("Body", row);
            body.VLayout(0, 0, 0, 0, 2).childForceExpandWidth = true;
            body.Layout(-1, -1, 1);
            Paragraph(body, item.title, 13, Theme.TextMain, true);
            if (!string.IsNullOrEmpty(item.memo)) Paragraph(body, item.memo, 11, Theme.TextSub, false);

            UI.Label(row, "編集", 11, Theme.Slate400);
            var click = row.gameObject.AddComponent<Clickable>();
            click.PressScale = 0.98f;
            click.OnClick = () => OpenEditor(item.id);
        }

        void OpenEditor(string id)
        {
            editingId = id;
            confirmDelete = false;
            RefreshEntry();
        }

        void CloseEditor()
        {
            editingId = null;
            confirmDelete = false;
            RefreshEntry();
        }

        void BuildEditor(RectTransform parent, ScheduleItem item)
        {
            bool isNew = string.IsNullOrEmpty(item.id);
            var form = UI.Rect("ScheduleForm", parent);
            UI.Box(form, Color.white, 12, null, Theme.Hex(0xbfdbfe), 1.5f);
            form.VLayout(12, 12, 12, 12, 6).childForceExpandWidth = true;

            UI.Label(form, isNew ? "予定を追加" : "予定を編集", 12, Theme.Primary, true);
            FieldLabel(form, "タイトル");
            TMP_InputField title = Input(form, item.title, "例: 病院、友だちとおでかけ", false);
            FieldLabel(form, "時刻 (空欄なら終日)");
            TMP_InputField time = Input(form, item.time, "例: 10:00", false);
            time.characterLimit = 5;
            FieldLabel(form, "メモ");
            TMP_InputField memo = Input(form, item.memo, "なくてもOK", true);

            var error = UI.Label(form, "", 11, Danger);
            error.gameObject.SetActive(false);

            UI.Spacer(form, 4);
            var buttons = UI.Rect("Buttons", form);
            buttons.HLayout(0, 0, 0, 0, 8);
            if (!isNew)
            {
                // 誤タップ防止: 1 回目で確認表示、2 回目で削除
                SmallButton(buttons, confirmDelete ? "本当に削除する？" : "削除",
                    confirmDelete ? Danger : Theme.Hex(0xfee2e2), confirmDelete ? Color.white : Danger, () =>
                    {
                        if (!confirmDelete)
                        {
                            confirmDelete = true;
                            RefreshEntry();
                            return;
                        }
                        ScheduleStore.Delete(item.id);
                        headerMascot.React(AidyFace.Surprised, 1f);
                        CloseEditor();
                        RefreshCalendar();
                    });
            }
            UI.Rect("Flex", buttons).Layout(-1, -1, 1);
            SmallButton(buttons, "キャンセル", Theme.Slate100, Theme.Slate600, CloseEditor);
            SmallButton(buttons, "保存", Theme.Primary, Color.white, () =>
            {
                string t = title.text.Trim();
                string tm = ScheduleStore.ParseTime(time.text);
                string msg = t.Length == 0 ? "タイトルを入れてね" : tm == null ? "時刻は 10:00 のように入れてね" : null;
                if (msg != null)
                {
                    error.text = msg;
                    error.gameObject.SetActive(true);
                    return;
                }
                ScheduleStore.Save(new ScheduleItem { id = item.id, date = item.date, time = tm, title = t, memo = memo.text.Trim() });
                headerMascot.React(AidyFace.Happy, 1.2f);
                CloseEditor();
                RefreshCalendar();
            });
        }

        static void FieldLabel(RectTransform parent, string text)
        {
            UI.Label(parent, text, 11, Theme.TextSub, true);
        }

        /// <summary>角丸の 1 行 / 複数行テキスト入力。</summary>
        static TMP_InputField Input(RectTransform parent, string value, string placeholder, bool multiline)
        {
            var root = UI.Rect("Input", parent);
            root.Layout(-1, multiline ? 64 : 38);
            var bg = UI.Box(root, Theme.Device, 10, null, Theme.Border);
            bg.raycastTarget = true;

            var area = UI.Rect("TextArea", root).Stretch(12, 8, 12, 8);
            area.gameObject.AddComponent<RectMask2D>();
            TextAlignmentOptions align = multiline ? TextAlignmentOptions.TopLeft : TextAlignmentOptions.Left;

            var ph = UI.Label(area, placeholder, 13, Theme.Slate400);
            ph.rectTransform.Stretch();
            ph.alignment = align;
            ph.enableWordWrapping = multiline;

            var text = UI.Label(area, "", 13, Theme.TextMain);
            text.rectTransform.Stretch();
            text.alignment = align;
            text.enableWordWrapping = multiline;

            // 参照をそろえてから有効化しないとキャレットが作られない
            root.gameObject.SetActive(false);
            var f = root.gameObject.AddComponent<TMP_InputField>();
            f.targetGraphic = bg;
            f.textViewport = area;
            f.textComponent = text;
            f.placeholder = ph;
            if (UI.Font != null) f.fontAsset = UI.Font;
            f.pointSize = 13;
            f.richText = false;
            f.lineType = multiline ? TMP_InputField.LineType.MultiLineNewline : TMP_InputField.LineType.SingleLine;
            f.characterLimit = multiline ? 200 : 40;
            f.customCaretColor = true;
            f.caretColor = Theme.Primary;
            f.selectionColor = Theme.Hex(0x3b82f6, 0.25f);
            f.onValueChanged.AddListener(UI.EnsureChars);
            f.text = value ?? "";
            root.gameObject.SetActive(true);
            return f;
        }

        static void SmallButton(RectTransform parent, string text, Color bg, Color fg, Action onClick)
        {
            var b = UI.Rect("Button", parent);
            UI.Box(b, bg, 14).raycastTarget = true;
            b.HLayout(14, 14, 7, 7, 0, TextAnchor.MiddleCenter);
            UI.Label(b, text, 12, fg, true);
            var click = b.gameObject.AddComponent<Clickable>();
            click.PressScale = 0.95f;
            click.OnClick = onClick;
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
