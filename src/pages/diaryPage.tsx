import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AppState, Pressable, ScrollView, TextInput, View } from "react-native";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Label, Pressed, boxStyle } from "../components/widgets";
import { AidyMascot, AidyFace, MascotState } from "../components/mascot";
import {
  AidyDeviceLink,
  ChatLine,
  DiaryComposer,
  DiaryEntry,
  DiaryStore,
  ScheduleItem,
  ScheduleStore,
  WeekDay,
  addDays,
  dateKey,
  CalendarSettings,
} from "../data/diary";

const SyncDays = 7;

const MaxChips = 3;
const CellHeight = 50;
const ChipHeight = 13;
const WeekLabels = ["日", "月", "火", "水", "木", "金", "土"];
const Sunday = Theme.hex(0xef4444);
const Saturday = Theme.Primary;
const Amber = Theme.hex(0xf59e0b);
const ChipBg = Theme.hex(0xfef3c7);
const ChipFg = Theme.hex(0xb45309);
const PhoneChipBg = Theme.hex(0xe0e7ff);
const PhoneChipFg = Theme.hex(0x4338ca);
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

/**
 * 月/週カレンダー (日記のある日にミニAidy、予定のある日にタイトル、本体にだけ会話がある日に点) + 本体の今週の予定
 * + 選択日の日記カード + 予定カード。初めて開いたときに本体と自動で同期する (DiaryPage.kt のポート)。
 */
function DiaryPageContent({ active }: { active: boolean }) {
  const [weekView, setWeekViewState] = useState(CalendarSettings.weekView);
  useEffect(() => {
    if (active) setWeekViewState(CalendarSettings.weekView);
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
    setScheduleByDate(await ScheduleStore.forDates(dates));
  }, [weeks]);

  useEffect(() => {
    void reloadEntries();
  }, [reloadEntries]);
  // iPhone のカレンダー側の変更を拾うため、タブを開いたときとアプリに戻ってきたときにも読み直す。
  useEffect(() => {
    if (!active) return;
    void reloadSchedule();
    const sub = AppState.addEventListener("change", (s) => {
      if (s === "active") void reloadSchedule();
    });
    return () => sub.remove();
  }, [active, reloadSchedule]);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [logsOpen, setLogsOpen] = useState(false);

  // ---- 本体との同期 (会話ログ → 日記、/api/week の今週の予定、/api/days の会話がある日)
  const [syncing, setSyncing] = useState(false);
  const [syncText, setSyncText] = useState(AidyDeviceLink.lastSyncLabel);
  const [deviceWeekDays, setDeviceWeekDays] = useState<WeekDay[]>([]);
  const [deviceWeekStatus, setDeviceWeekStatus] = useState("「同期」を押すと本体の今週の予定を取得します");
  const [deviceDays, setDeviceDays] = useState<Set<string>>(new Set());
  const syncingRef = useRef(false);
  const autoSynced = useRef(false);

  const startSync = useCallback(
    (silent: boolean) => {
      if (syncingRef.current) return;
      syncingRef.current = true;
      setSyncing(true);
      headerMascot.react("Surprised", 0.6);
      void (async () => {
        const r = await AidyDeviceLink.syncRecent(SyncDays);
        syncingRef.current = false;
        setSyncing(false);
        setSyncText(r.ok || !silent ? r.message : "未接続 ・ " + AidyDeviceLink.lastSyncLabel);
        headerMascot.react(r.ok ? "Happy" : "Sleepy", 1.6);
        if (r.updated > 0) await reloadEntries();
      })();
      void (async () => {
        const w = await AidyDeviceLink.fetchWeek();
        if (w.ok) {
          setDeviceWeekDays(w.days);
          if (w.synced > 0) {
            const d = new Date(w.synced * 1000);
            const pad = (n: number) => String(n).padStart(2, "0");
            setDeviceWeekStatus(`Googleカレンダー同期: ${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`);
          } else setDeviceWeekStatus("本体がGoogleカレンダーにまだつながっていません");
        } else setDeviceWeekStatus(w.error ?? "本体からの返事を読み取れませんでした");
      })();
      void (async () => {
        const d = await AidyDeviceLink.fetchDays();
        if (d.ok) setDeviceDays(d.days);
      })();
    },
    [headerMascot, reloadEntries],
  );

  // ページが表示されたとき。初回だけ本体と自動同期する
  useEffect(() => {
    if (active && !autoSynced.current) {
      autoSynced.current = true;
      startSync(true);
    }
  }, [active, startSync]);

  /** 月表示/週表示を切り替える (カレンダー上のボタン・設定画面)。端末に覚えておく。 */
  const applyWeekView = (on: boolean) => {
    if (weekView === on) return;
    setWeekViewState(on);
    CalendarSettings.weekView = on;
    setMonth(startOfMonth(selected));
    setWeekAnchor(startOfWeek(selected));
  };

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
          <Label text={syncText} size={11} color={Theme.TextSub} />
        </View>
        <Pressed
          onPress={() => startSync(false)}
          pressScale={0.95}
          style={[boxStyle(Theme.PrimaryLight, 16, 0, Theme.hex(0xbfdbfe)), { flexDirection: "row", alignItems: "center", gap: 6, paddingHorizontal: 12, paddingVertical: 8 }]}
        >
          <Icon icon="Wifi" size={12} color={Theme.Primary} />
          <Label text={syncing ? "同期中…" : "同期"} size={12} color={Theme.Primary} bold />
        </Pressed>
      </View>

      <View style={{ height: 14 }} />
      <CalendarModeToggle weekView={weekView} onChange={applyWeekView} />
      <View style={{ height: 8 }} />
      <CalendarCard
        monthLabel={monthLabel}
        weeks={weeks}
        selected={selected}
        today={today}
        entries={entries}
        scheduleByDate={scheduleByDate}
        deviceDays={deviceDays}
        onPrev={() => shiftPeriod(-1)}
        onNext={() => shiftPeriod(1)}
        onSelect={select}
      />

      <View style={{ height: 12 }} />
      <DeviceWeekCard status={deviceWeekStatus} days={deviceWeekDays} />

      <View style={{ height: 12 }} />
      {entry ? (
        <DiaryEntryCard entry={entry} selected={selected} logsOpen={logsOpen} onToggleLogs={() => setLogsOpen((v) => !v)} />
      ) : (
        <EmptyEntryCard selected={selected} today={today} onFetch={() => startSync(false)} />
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
  deviceDays,
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
  deviceDays: Set<string>;
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
                onDevice={d != null && deviceDays.has(dateKey(d))}
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
  onDevice,
  onSelect,
}: {
  date: Date | null;
  selected: Date;
  today: Date;
  entry: DiaryEntry | undefined;
  schedule: ScheduleItem[];
  /** 本体にはあるがまだ同期していない会話がある日 (/api/days)。同期すると日記になる。 */
  onDevice: boolean;
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
            {onDevice && shown === 0 && <View style={{ alignSelf: "center", marginTop: 6, width: 6, height: 6, borderRadius: 3, backgroundColor: Theme.Slate300 }} />}
            {schedule.slice(0, shown).map((it) => (
              <View key={it.id} style={{ backgroundColor: it.fromPhone ? PhoneChipBg : ChipBg, borderRadius: 4, paddingHorizontal: 3, paddingVertical: 1 }}>
                <Label text={it.title} size={8} color={it.fromPhone ? PhoneChipFg : ChipFg} bold maxLines={1} />
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

function EmptyEntryCard({ selected, today, onFetch }: { selected: Date; today: Date; onFetch: () => void }) {
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
      {isToday && (
        <View style={{ marginTop: 8 }}>
          <Pressed
            onPress={onFetch}
            pressScale={0.96}
            style={[boxStyle(Theme.Primary, 20, 4), { flexDirection: "row", alignItems: "center", gap: 8, paddingHorizontal: 18, paddingVertical: 10 }]}
          >
            <Icon icon="Wifi" size={12} color="#fff" />
            <Label text="Aidyから会話ログを受け取る" size={13} color="#fff" bold />
          </Pressed>
        </View>
      )}
    </View>
  );
}

/** カレンダーの上: 月表示 / 週表示の切り替え (アイコン付きの 2 つ割りボタン)。 */
function CalendarModeToggle({ weekView, onChange }: { weekView: boolean; onChange: (week: boolean) => void }) {
  return (
    <View style={{ flexDirection: "row", alignItems: "center" }}>
      <Label text="カレンダー" size={13} color={Theme.TextSub} bold style={{ flex: 1, paddingLeft: 4 }} />
      <View style={{ flexDirection: "row", backgroundColor: Theme.Slate100, borderRadius: 18, padding: 3 }}>
        {[false, true].map((week) => {
          const on = weekView === week;
          const color = on ? Theme.Primary : Theme.Slate400;
          return (
            <Pressed
              key={String(week)}
              onPress={() => onChange(week)}
              pressScale={0.95}
              style={[boxStyle(on ? "#fff" : "transparent", 15, on ? 2 : 0), { height: 32, flexDirection: "row", alignItems: "center", gap: 6, paddingHorizontal: 12 }]}
            >
              <CalendarGlyph week={week} color={color} />
              <Label text={week ? "週" : "月"} size={12} color={on ? Theme.Primary : Theme.Slate600} bold />
            </Pressed>
          );
        })}
      </View>
    </View>
  );
}

/** カレンダーの絵: 月 = 3 段のマス、週 = 1 段のマス。 */
function CalendarGlyph({ week, color }: { week: boolean; color: string }) {
  const w = 16;
  const rows = week ? 1 : 3;
  const cell = w * 0.13;
  const cells: React.ReactNode[] = [];
  for (let r = 0; r < rows; r++)
    for (let c = 0; c < 4; c++)
      cells.push(
        <View
          key={`${r}-${c}`}
          style={{
            position: "absolute",
            left: w * 0.16 + c * w * 0.19 - 0.75,
            top: (week ? w * 0.55 : w * 0.42 + r * w * 0.16) - w * 0.14 - 0.75,
            width: cell,
            height: cell * (week ? 1.6 : 0.8),
            backgroundColor: color,
          }}
        />,
      );
  return (
    <View style={{ width: w, height: w }}>
      <View style={{ position: "absolute", left: 0, top: w * 0.14, width: w, height: w * 0.82, borderRadius: w * 0.15, borderWidth: 1.5, borderColor: color, overflow: "hidden" }}>
        <View style={{ height: w * 0.18, backgroundColor: color }} />
        {cells}
      </View>
    </View>
  );
}

/** 本体 (Google カレンダーの控え + 本体のやることリスト) と同期する今週の予定。書き込みはまだない。 */
function DeviceWeekCard({ status, days }: { status: string; days: WeekDay[] }) {
  return (
    <View style={[cardStyle(), { paddingHorizontal: 14, paddingVertical: 12, gap: 8 }]}>
      <Label text="今週の予定（本体と同期）" size={13} color={Theme.TextMain} bold />
      <Label text={status} size={11} color={Theme.TextSub} />
      {days.length === 0 ? (
        <Label text="予定はありませんでした" size={12} color={Theme.Slate400} />
      ) : (
        days.map((d, i) => <DeviceWeekRow key={d.date + i} day={d} />)
      )}
    </View>
  );
}

function DeviceWeekRow({ day }: { day: WeekDay }) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day.date);
  const label = m ? (() => {
    const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
    return `${d.getMonth() + 1}/${d.getDate()}（${WeekLabels[d.getDay()]}）`;
  })() : day.date;
  const parts = [...day.events.map((e) => `${e.time} ${e.title}`), ...day.todo.map((t) => `${t.label} ${t.text}`)];
  return (
    <View style={{ flexDirection: "row", gap: 8 }}>
      <Label text={label} size={12} color={Theme.TextMain} bold style={{ minWidth: 56 }} />
      <Label text={parts.length > 0 ? parts.join(" ・ ") : "予定なし"} size={12} color={parts.length > 0 ? Theme.TextMain : Theme.Slate400} style={{ flex: 1 }} />
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
            <ScheduleRow item={item} onPress={item.fromPhone ? undefined : () => onOpenEditor(item.id)} />
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

/** onPress がない (= iPhone のカレンダーから読み込んだ) 予定は編集できないので、押せない行にする。 */
function ScheduleRow({ item, onPress }: { item: ScheduleItem; onPress?: () => void }) {
  const past = item.isPast(new Date());
  const rowStyle = [boxStyle(Theme.Slate100, 12), { flexDirection: "row" as const, alignItems: "center" as const, paddingHorizontal: 12, paddingVertical: 10, gap: 10 }];
  const content = (
    <>
      <Label text={item.allDay ? "終日" : item.time} size={12} bold color={past ? Theme.Slate400 : item.allDay ? Amber : Theme.Primary} style={{ width: 44 }} />
      <View style={{ flex: 1, gap: 2 }}>
        <Label text={item.title} size={13} color={Theme.TextMain} bold />
        {item.memo.length > 0 && <Label text={item.memo} size={11} color={Theme.TextSub} />}
      </View>
      {onPress ? <Label text="編集" size={11} color={Theme.Slate400} /> : <Pill text="iPhone" bg={PhoneChipBg} fg={PhoneChipFg} />}
    </>
  );
  if (!onPress) return <View style={rowStyle}>{content}</View>;
  return (
    <Pressed onPress={onPress} pressScale={0.98} style={rowStyle}>
      {content}
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
