import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Pressable, ScrollView, TextInput, View } from "react-native";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Label, Pressed, boxStyle } from "../components/widgets";
import { AidyMascot, AidyFace, MascotState } from "../components/mascot";
import { ChatLine, DiaryComposer, DiaryEntry, DiaryStore, ScheduleItem, ScheduleStore, addDays, dateKey, CalendarSettings } from "../data/diary";

const MaxChips = 3;
const CellHeight = 50;
const ChipHeight = 13;
const WeekLabels = ["日", "月", "火", "水", "木", "金", "土"];
const Sunday = Theme.hex(0xef4444);
const Saturday = Theme.Primary;
const Amber = Theme.hex(0xf59e0b);
const ChipBg = Theme.hex(0xfef3c7);
const ChipFg = Theme.hex(0xb45309);
const Danger = Theme.hex(0xef4444);

function startOfWeek(d: Date): Date {
  const r = new Date(d);
  r.setDate(r.getDate() - r.getDay());
  r.setHours(0, 0, 0, 0);
  return r;
}
function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}
function daysInMonth(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
}
/** 月グリッド (その月の日だけ、前後月の穴は null)。週表示は 1 行だけ。 */
function monthWeeks(month: Date): (Date | null)[][] {
  const offset = month.getDay();
  const days = daysInMonth(month);
  const rows = Math.ceil((offset + days) / 7);
  const weeks: (Date | null)[][] = [];
  let day = 1 - offset;
  for (let r = 0; r < rows; r++) {
    const row: (Date | null)[] = [];
    for (let c = 0; c < 7; c++) {
      row.push(day >= 1 && day <= days ? new Date(month.getFullYear(), month.getMonth(), day) : null);
      day++;
    }
    weeks.push(row);
  }
  return weeks;
}
function weekRow(weekAnchor: Date): (Date | null)[][] {
  return [Array.from({ length: 7 }, (_, i) => addDays(weekAnchor, i))];
}
function dateLabel(d: Date): string {
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 (${WeekLabels[d.getDay()]})`;
}
function weekRangeLabel(start: Date, end: Date): string {
  if (start.getFullYear() !== end.getFullYear())
    return `${start.getFullYear()}/${start.getMonth() + 1}/${start.getDate()} 〜 ${end.getFullYear()}/${end.getMonth() + 1}/${end.getDate()}`;
  if (start.getMonth() !== end.getMonth()) return `${start.getFullYear()}年${start.getMonth() + 1}月${start.getDate()}日 〜 ${end.getMonth() + 1}月${end.getDate()}日`;
  return `${start.getFullYear()}年${start.getMonth() + 1}月${start.getDate()}日 〜 ${end.getDate()}日`;
}

/**
 * 日記画面。PagerView は全ページを起動時に一括マウントするため、カレンダー本体を
 * 最初から描画するとホーム画面の 3D (View3D/Canvas) 初期化とタイミングが競合する。
 * このタブを一度も開くまでは何も描画せず (lazy mount)、それを避ける。
 */
export function DiaryPage({ active }: { active: boolean }) {
  const [everActive, setEverActive] = useState(active);
  useEffect(() => {
    if (active) setEverActive(true);
  }, [active]);
  if (!everActive) return <View style={{ flex: 1, backgroundColor: Theme.Device }} />;
  return <DiaryPageContent active={active} />;
}

/** 月/週カレンダー(日記のある日にミニAidy、予定のある日にタイトル) + 選択日の日記カード + 予定カード。本体との同期は含まない。 */
function DiaryPageContent({ active }: { active: boolean }) {
  const [weekView, setWeekView] = useState(CalendarSettings.weekView);
  useEffect(() => {
    if (active) setWeekView(CalendarSettings.weekView);
  }, [active]);

  const today = new Date();
  const [selected, setSelected] = useState(today);
  const [month, setMonth] = useState(() => startOfMonth(today));
  const [weekAnchor, setWeekAnchor] = useState(() => startOfWeek(today));

  const headerMascot = useRef(new MascotState()).current;

  const [entries, setEntries] = useState<Map<string, DiaryEntry>>(new Map());
  const reloadEntries = useCallback(async () => {
    const list = await DiaryStore.all();
    setEntries(new Map(list.map((e) => [e.date, e])));
  }, []);

  const weeks = useMemo(() => (weekView ? weekRow(weekAnchor) : monthWeeks(month)), [weekView, weekAnchor, month]);
  const [scheduleByDate, setScheduleByDate] = useState<Map<string, ScheduleItem[]>>(new Map());
  const reloadSchedule = useCallback(async () => {
    const dates = weeks.flat().filter((d): d is Date => d != null);
    const pairs = await Promise.all(dates.map(async (d) => [dateKey(d), await ScheduleStore.forDate(d)] as const));
    setScheduleByDate(new Map(pairs));
  }, [weeks]);

  useEffect(() => {
    void reloadEntries();
  }, [reloadEntries]);
  useEffect(() => {
    void reloadSchedule();
  }, [reloadSchedule]);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [logsOpen, setLogsOpen] = useState(false);

  const select = (d: Date) => {
    if (dateKey(d) !== dateKey(selected)) {
      setEditingId(null);
      setConfirmDelete(false);
      setLogsOpen(false);
    }
    setSelected(d);
    setMonth(startOfMonth(d));
    setWeekAnchor(startOfWeek(d));
  };

  const shiftPeriod = (delta: number) => {
    if (weekView) setWeekAnchor((d) => addDays(d, 7 * delta));
    else setMonth((d) => new Date(d.getFullYear(), d.getMonth() + delta, 1));
  };

  const afterScheduleChanged = () => {
    void reloadSchedule();
  };

  const entry = entries.get(dateKey(selected));
  const selectedSchedule = scheduleByDate.get(dateKey(selected)) ?? [];
  const monthLabel = weekView ? weekRangeLabel(weekAnchor, addDays(weekAnchor, 6)) : `${month.getFullYear()}年${month.getMonth() + 1}月`;

  return (
    <ScrollView style={{ flex: 1, backgroundColor: Theme.Device }} contentContainerStyle={{ paddingHorizontal: 16, paddingTop: 14, paddingBottom: 24 }}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 10 }}>
        <AidyMascot size={44} face="Normal" state={headerMascot} interactive />
        <View style={{ flex: 1, gap: 2 }}>
          <Label text="Aidyの日記" size={18} color={Theme.TextMain} bold />
        </View>
      </View>

      <View style={{ height: 14 }} />
      <CalendarCard
        monthLabel={monthLabel}
        weeks={weeks}
        selected={selected}
        today={today}
        entries={entries}
        scheduleByDate={scheduleByDate}
        onPrev={() => shiftPeriod(-1)}
        onNext={() => shiftPeriod(1)}
        onSelect={select}
      />

      <View style={{ height: 12 }} />
      {entry ? (
        <DiaryEntryCard entry={entry} selected={selected} logsOpen={logsOpen} onToggleLogs={() => setLogsOpen((v) => !v)} />
      ) : (
        <EmptyEntryCard selected={selected} today={today} />
      )}

      <View style={{ height: 12 }} />
      <ScheduleCard
        selected={selected}
        items={selectedSchedule}
        editingId={editingId}
        confirmDelete={confirmDelete}
        onOpenEditor={(id) => {
          setEditingId(id);
          setConfirmDelete(false);
        }}
        onCloseEditor={() => {
          setEditingId(null);
          setConfirmDelete(false);
        }}
        onConfirmDelete={() => setConfirmDelete(true)}
        onChanged={afterScheduleChanged}
        onReact={(face, sec) => headerMascot.react(face, sec)}
      />
    </ScrollView>
  );
}

function cardStyle() {
  return boxStyle("#fff", 18, 4, Theme.Border);
}

// ---------------------------------------------------------------- Calendar

function CalendarCard({
  monthLabel,
  weeks,
  selected,
  today,
  entries,
  scheduleByDate,
  onPrev,
  onNext,
  onSelect,
}: {
  monthLabel: string;
  weeks: (Date | null)[][];
  selected: Date;
  today: Date;
  entries: Map<string, DiaryEntry>;
  scheduleByDate: Map<string, ScheduleItem[]>;
  onPrev: () => void;
  onNext: () => void;
  onSelect: (d: Date) => void;
}) {
  return (
    <View style={[cardStyle(), { padding: 10, paddingBottom: 12 }]}>
      <View style={{ flexDirection: "row", alignItems: "center", height: 36 }}>
        <MonthButton icon="ChevronLeft" onPress={onPrev} />
        <View style={{ flex: 1, alignItems: "center" }}>
          <Label text={monthLabel} size={15} color={Theme.TextMain} bold />
        </View>
        <MonthButton icon="ChevronRight" onPress={onNext} />
      </View>

      <View style={{ height: 6 }} />
      <View style={{ flexDirection: "row" }}>
        {WeekLabels.map((w, i) => (
          <View key={w} style={{ flex: 1, alignItems: "center" }}>
            <Label text={w} size={11} color={i === 0 ? Sunday : i === 6 ? Saturday : Theme.TextSub} bold />
          </View>
        ))}
      </View>
      <View style={{ height: 4 }} />

      {weeks.map((row, ri) => {
        const maxChips = Math.max(0, ...row.map((d) => (d == null ? 0 : rowChipCount(d, entries, scheduleByDate))));
        const rowHeight = CellHeight + Math.max(0, maxChips - 1) * ChipHeight;
        return (
          <View key={ri} style={{ flexDirection: "row", height: rowHeight }}>
            {row.map((d, ci) => (
              <DayCellView
                key={ci}
                date={d}
                selected={selected}
                today={today}
                entry={d ? entries.get(dateKey(d)) : undefined}
                schedule={d ? scheduleByDate.get(dateKey(d)) ?? [] : []}
                onSelect={onSelect}
              />
            ))}
          </View>
        );
      })}
    </View>
  );
}

function rowChipCount(d: Date, entries: Map<string, DiaryEntry>, scheduleByDate: Map<string, ScheduleItem[]>): number {
  if (entries.has(dateKey(d))) return 0; // 日記のある日はミニAidyを優先 (チップなし)
  return Math.min(scheduleByDate.get(dateKey(d))?.length ?? 0, MaxChips);
}

function MonthButton({ icon, onPress }: { icon: "ChevronLeft" | "ChevronRight"; onPress: () => void }) {
  return (
    <Pressed onPress={onPress} pressScale={0.92} style={{ width: 36, height: 36, borderRadius: 18, backgroundColor: Theme.Slate100, alignItems: "center", justifyContent: "center" }}>
      <Icon icon={icon} size={12} color={Theme.Slate600} />
    </Pressed>
  );
}

function DayCellView({
  date,
  selected,
  today,
  entry,
  schedule,
  onSelect,
}: {
  date: Date | null;
  selected: Date;
  today: Date;
  entry: DiaryEntry | undefined;
  schedule: ScheduleItem[];
  onSelect: (d: Date) => void;
}) {
  if (!date) return <View style={{ flex: 1 }} />;

  const sel = dateKey(date) === dateKey(selected);
  const isToday = dateKey(date) === dateKey(today);
  const dow = date.getDay();
  const numColor = sel || isToday ? Theme.Primary : dow === 0 ? Sunday : dow === 6 ? Saturday : Theme.TextMain;
  const shown = entry ? 0 : Math.min(schedule.length, MaxChips);
  const hidden = entry ? 0 : schedule.length - shown;

  return (
    <Pressable onPress={() => onSelect(date)} style={{ flex: 1, alignItems: "center", paddingTop: 4 }}>
      <View
        style={{
          width: "92%",
          height: "100%",
          borderRadius: 12,
          backgroundColor: sel || isToday ? Theme.PrimaryLight : "transparent",
          borderWidth: sel ? 2 : 0,
          borderColor: Theme.Primary,
          alignItems: "center",
          paddingTop: 2,
        }}
      >
        <View style={{ height: 18, alignItems: "center" }}>
          <Label text={String(date.getDate())} size={13} color={numColor} bold />
        </View>
        {entry ? (
          <View style={{ marginTop: 2 }}>
            <AidyMascot size={16} face={DiaryComposer.faceFor(entry.mood)} animate={false} />
          </View>
        ) : (
          <View style={{ width: "100%", paddingHorizontal: 3, gap: 2, marginTop: 2 }}>
            {schedule.slice(0, shown).map((it) => (
              <View key={it.id} style={{ backgroundColor: ChipBg, borderRadius: 4, paddingHorizontal: 3, paddingVertical: 1 }}>
                <Label text={it.title} size={8} color={ChipFg} bold maxLines={1} />
              </View>
            ))}
          </View>
        )}
        {hidden > 0 && (
          <View style={{ position: "absolute", top: 2, right: 2 }}>
            <Label text={`+${hidden}`} size={8} color={Amber} bold />
          </View>
        )}
      </View>
    </Pressable>
  );
}

// ---------------------------------------------------------------- Entry

function DiaryEntryCard({
  entry,
  selected,
  logsOpen,
  onToggleLogs,
}: {
  entry: DiaryEntry;
  selected: Date;
  logsOpen: boolean;
  onToggleLogs: () => void;
}) {
  return (
    <View style={[cardStyle(), { padding: 16 }]}>
      <View style={{ flexDirection: "row", alignItems: "flex-start", gap: 12 }}>
        <AidyMascot size={48} face={DiaryComposer.faceFor(entry.mood)} interactive />
        <View style={{ flex: 1, gap: 3 }}>
          <Label text={dateLabel(selected)} size={12} color={Theme.TextSub} bold />
          <Label text={entry.title} size={16} color={Theme.TextMain} bold />
        </View>
        {entry.source === "demo" && <Pill text="サンプル" bg={ChipBg} fg={ChipFg} />}
      </View>

      <View style={{ height: 14 }} />
      <Label text={entry.summary} size={13} color={Theme.Slate700} style={{ lineHeight: 21 }} />

      <View style={{ height: 14 }} />
      <View style={{ flexDirection: "row", gap: 8, flexWrap: "wrap" }}>
        <Pill text={`おしゃべり ${entry.userCount}回`} bg={Theme.PrimaryLight} fg={Theme.Primary} />
        {entry.logs.length > 0 && <Pill text={`${entry.logs[0].time} 〜 ${entry.logs[entry.logs.length - 1].time}`} bg={Theme.Slate100} fg={Theme.Slate600} />}
      </View>

      {entry.logs.length > 0 && (
        <>
          <View style={{ height: 14 }} />
          <View style={{ height: 1, backgroundColor: Theme.Slate100 }} />
          <Pressable onPress={onToggleLogs} style={{ flexDirection: "row", alignItems: "center", gap: 6, paddingVertical: 10 }}>
            <Icon icon="Aidy" size={13} color={Theme.TextSub} />
            <Label text="会話ログ" size={13} color={Theme.TextSub} bold style={{ flex: 1 }} />
            <Label text={`${entry.logs.length}件`} size={11} color={Theme.Slate400} />
            <View style={{ transform: [{ rotate: logsOpen ? "-90deg" : "0deg" }] }}>
              <Icon icon="ChevronRight" size={11} color={Theme.Slate400} />
            </View>
          </Pressable>
          {logsOpen && (
            <View style={{ gap: 10 }}>
              {entry.logs.map((l, i) => (
                <ChatRow key={i} line={l} />
              ))}
            </View>
          )}
        </>
      )}
    </View>
  );
}

function ChatRow({ line }: { line: ChatLine }) {
  const user = line.isUser;
  return (
    <View style={{ flexDirection: "row", alignItems: "flex-end", justifyContent: user ? "flex-end" : "flex-start", gap: 6 }}>
      {!user && <AidyMascot size={24} face="Normal" animate={false} />}
      {user && <Label text={line.time} size={10} color={Theme.Slate400} />}
      <View style={[boxStyle(user ? Theme.Primary : Theme.Slate100, 14), { maxWidth: "72%", paddingHorizontal: 12, paddingVertical: 8 }]}>
        <Label text={line.text} size={13} color={user ? "#fff" : Theme.TextMain} />
      </View>
      {!user && <Label text={line.time} size={10} color={Theme.Slate400} />}
    </View>
  );
}

function EmptyEntryCard({ selected, today }: { selected: Date; today: Date }) {
  const future = selected.getTime() > today.getTime() && dateKey(selected) !== dateKey(today);
  const isToday = dateKey(selected) === dateKey(today);
  const face: AidyFace = future ? "Normal" : "Sleepy";
  const main = future ? "まだ来ていない日だよ" : isToday ? "今日の日記はまだないよ" : "この日の日記はないみたい";
  const sub = future ? "下の「予定」から、この日の予定を入れておけるよ" : "おしゃべりすると、会話ログから日記ができるよ";

  return (
    <View style={[cardStyle(), { padding: 22, alignItems: "center", gap: 6 }]}>
      <AidyMascot size={64} face={face} interactive />
      <View style={{ height: 4 }} />
      <Label text={dateLabel(selected)} size={12} color={Theme.TextSub} bold align="center" />
      <Label text={main} size={15} color={Theme.TextMain} bold align="center" />
      <Label text={sub} size={12} color={Theme.TextSub} align="center" />
    </View>
  );
}

function Pill({ text, bg, fg }: { text: string; bg: string; fg: string }) {
  return (
    <View style={[boxStyle(bg, 10), { paddingHorizontal: 10, paddingVertical: 4 }]}>
      <Label text={text} size={11} color={fg} bold />
    </View>
  );
}

// ---------------------------------------------------------------- Schedule

function ScheduleCard({
  selected,
  items,
  editingId,
  confirmDelete,
  onOpenEditor,
  onCloseEditor,
  onConfirmDelete,
  onChanged,
  onReact,
}: {
  selected: Date;
  items: ScheduleItem[];
  editingId: string | null;
  confirmDelete: boolean;
  onOpenEditor: (id: string) => void;
  onCloseEditor: () => void;
  onConfirmDelete: () => void;
  onChanged: () => void;
  onReact: (face: AidyFace, seconds: number) => void;
}) {
  return (
    <View style={[cardStyle(), { padding: 16, paddingTop: 14 }]}>
      <View style={{ flexDirection: "row", alignItems: "center", height: 32, gap: 6 }}>
        <Icon icon="MapPin" size={13} color={Amber} />
        <Label text="予定" size={14} color={Theme.TextMain} bold style={{ flex: 1 }} />
        {editingId == null && <SmallButton text="＋ 追加" bg={Theme.PrimaryLight} fg={Theme.Primary} onPress={() => onOpenEditor("")} />}
      </View>

      {items.length === 0 && editingId == null && (
        <>
          <View style={{ height: 6 }} />
          <Label text="この日の予定はまだないよ" size={12} color={Theme.Slate400} />
        </>
      )}

      {items.map((item) => (
        <View key={item.id} style={{ marginTop: 8 }}>
          {item.id === editingId ? (
            <ScheduleEditor
              item={item}
              confirmDelete={confirmDelete}
              onCancel={onCloseEditor}
              onConfirmDelete={onConfirmDelete}
              onDelete={async () => {
                await ScheduleStore.delete(item.id);
                onReact("Surprised", 1);
                onCloseEditor();
                onChanged();
              }}
              onSave={async (next) => {
                await ScheduleStore.save(next);
                onReact("Happy", 1.2);
                onCloseEditor();
                onChanged();
              }}
            />
          ) : (
            <ScheduleRow item={item} onPress={() => onOpenEditor(item.id)} />
          )}
        </View>
      ))}

      {editingId === "" && (
        <View style={{ marginTop: 8 }}>
          <ScheduleEditor
            item={new ScheduleItem("", dateKey(selected), "", "", "")}
            confirmDelete={false}
            onCancel={onCloseEditor}
            onConfirmDelete={() => {}}
            onDelete={async () => {}}
            onSave={async (next) => {
              await ScheduleStore.save(next);
              onReact("Happy", 1.2);
              onCloseEditor();
              onChanged();
            }}
          />
        </View>
      )}
    </View>
  );
}

function ScheduleRow({ item, onPress }: { item: ScheduleItem; onPress: () => void }) {
  const past = item.isPast(new Date());
  return (
    <Pressed onPress={onPress} pressScale={0.98} style={[boxStyle(Theme.Slate100, 12), { flexDirection: "row", alignItems: "center", paddingHorizontal: 12, paddingVertical: 10, gap: 10 }]}>
      <Label text={item.allDay ? "終日" : item.time} size={12} bold color={past ? Theme.Slate400 : item.allDay ? Amber : Theme.Primary} style={{ width: 44 }} />
      <View style={{ flex: 1, gap: 2 }}>
        <Label text={item.title} size={13} color={Theme.TextMain} bold />
        {item.memo.length > 0 && <Label text={item.memo} size={11} color={Theme.TextSub} />}
      </View>
      <Label text="編集" size={11} color={Theme.Slate400} />
    </Pressed>
  );
}

function ScheduleEditor({
  item,
  confirmDelete,
  onCancel,
  onConfirmDelete,
  onDelete,
  onSave,
}: {
  item: ScheduleItem;
  confirmDelete: boolean;
  onCancel: () => void;
  onConfirmDelete: () => void;
  onDelete: () => Promise<void>;
  onSave: (next: ScheduleItem) => Promise<void>;
}) {
  const isNew = item.id.length === 0;
  const [title, setTitle] = useState(item.title);
  const [time, setTime] = useState(item.time);
  const [memo, setMemo] = useState(item.memo);
  const [error, setError] = useState("");

  const submit = async () => {
    const t = title.trim();
    const parsed = ScheduleStore.parseTime(time);
    if (t.length === 0) {
      setError("タイトルを入れてね");
      return;
    }
    if (parsed == null) {
      setError("時刻は 10:00 のように入れてね");
      return;
    }
    await onSave(item.copy({ time: parsed, title: t, memo: memo.trim() }));
  };

  return (
    <View style={[boxStyle("#fff", 12, 0, Theme.hex(0xbfdbfe), 1.5), { padding: 12, gap: 6 }]}>
      <Label text={isNew ? "予定を追加" : "予定を編集"} size={12} color={Theme.Primary} bold />
      <Label text="タイトル" size={11} color={Theme.TextSub} bold />
      <TextInput value={title} onChangeText={setTitle} placeholder="例: 病院、友だちとおでかけ" placeholderTextColor={Theme.Slate400} style={inputStyle} />
      <Label text="時刻 (空欄なら終日)" size={11} color={Theme.TextSub} bold />
      <TextInput value={time} onChangeText={setTime} placeholder="例: 10:00" placeholderTextColor={Theme.Slate400} maxLength={5} style={inputStyle} />
      <Label text="メモ" size={11} color={Theme.TextSub} bold />
      <TextInput
        value={memo}
        onChangeText={setMemo}
        placeholder="なくてもOK"
        placeholderTextColor={Theme.Slate400}
        multiline
        style={[inputStyle, { height: 56, textAlignVertical: "top" }]}
      />
      {error.length > 0 && <Label text={error} size={11} color={Danger} />}

      <View style={{ height: 4 }} />
      <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
        {!isNew &&
          (confirmDelete ? (
            <SmallButton text="本当に削除する？" bg={Danger} fg="#fff" onPress={() => void onDelete()} />
          ) : (
            <SmallButton text="削除" bg={Theme.hex(0xfee2e2)} fg={Danger} onPress={onConfirmDelete} />
          ))}
        <View style={{ flex: 1 }} />
        <SmallButton text="キャンセル" bg={Theme.Slate100} fg={Theme.Slate600} onPress={onCancel} />
        <SmallButton text="保存" bg={Theme.Primary} fg="#fff" onPress={() => void submit()} />
      </View>
    </View>
  );
}

function SmallButton({ text, bg, fg, onPress }: { text: string; bg: string; fg: string; onPress: () => void }) {
  return (
    <Pressed onPress={onPress} pressScale={0.95} style={[boxStyle(bg, 14), { paddingHorizontal: 14, paddingVertical: 7 }]}>
      <Label text={text} size={12} color={fg} bold />
    </Pressed>
  );
}

const inputStyle = {
  borderWidth: 1,
  borderColor: Theme.Border,
  backgroundColor: Theme.Device,
  borderRadius: 10,
  paddingHorizontal: 12,
  paddingVertical: 9,
  fontSize: 13,
  color: Theme.TextMain,
};
