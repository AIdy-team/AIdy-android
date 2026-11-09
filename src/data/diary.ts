import { Directory, File, Paths } from "expo-file-system";
import { Prefs } from "./prefs";

/** Aidy 本体に残る会話ログの 1 行。time は "HH:mm"、speaker は "user" / "aidy"。 */
export class ChatLine {
  constructor(public time: string, public speaker: string, public text: string) {}

  get isUser(): boolean {
    return this.speaker === "user";
  }

  toJson(): Record<string, string> {
    return { time: this.time, speaker: this.speaker, text: this.text };
  }

  static fromJson(o: Record<string, unknown>): ChatLine {
    return new ChatLine(String(o.time ?? ""), String(o.speaker ?? ""), String(o.text ?? ""));
  }
}

/** 1 日分の日記。date は "yyyy-MM-dd"。source は "device" (本体から同期) / "demo" (サンプル)。 */
export class DiaryEntry {
  constructor(
    public date: string,
    public title: string,
    public summary: string,
    public mood: string,
    public source: string = "",
    public syncedAt: string = "",
    public logs: ChatLine[] = [],
  ) {}

  get userCount(): number {
    return this.logs.filter((l) => l.isUser).length;
  }

  toJson(): Record<string, unknown> {
    return {
      date: this.date,
      title: this.title,
      summary: this.summary,
      mood: this.mood,
      source: this.source,
      syncedAt: this.syncedAt,
      logs: this.logs.map((l) => l.toJson()),
    };
  }

  static fromJson(o: Record<string, unknown>): DiaryEntry {
    const arr = Array.isArray(o.logs) ? (o.logs as Record<string, unknown>[]) : [];
    return new DiaryEntry(
      String(o.date ?? ""),
      String(o.title ?? ""),
      String(o.summary ?? ""),
      String(o.mood ?? ""),
      String(o.source ?? ""),
      String(o.syncedAt ?? ""),
      arr.map(ChatLine.fromJson),
    );
  }
}

export function dateKey(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

export function addDays(d: Date, days: number): Date {
  const r = new Date(d);
  r.setDate(r.getDate() + days);
  return r;
}

/** 日記の保存先 (filesDir/diary/yyyy-MM-dd.json)。起動後の初回アクセスで全件読み込む。 */
class DiaryStoreImpl {
  private entries: Map<string, DiaryEntry> | null = null;
  private get dir(): Directory {
    return new Directory(Paths.document, "diary");
  }

  async get(d: Date): Promise<DiaryEntry | undefined> {
    return this.load().get(dateKey(d));
  }

  /** 日付の新しい順。 */
  async all(): Promise<DiaryEntry[]> {
    const map = this.load();
    return Array.from(map.values()).sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
  }

  async save(e: DiaryEntry): Promise<void> {
    const map = this.load();
    map.set(e.date, e);
    try {
      const dir = this.dir;
      if (!dir.exists) dir.create({ intermediates: true });
      const file = new File(dir, e.date + ".json");
      if (!file.exists) file.create();
      file.writeSync(JSON.stringify(e.toJson(), null, 2));
    } catch (ex) {
      console.warn(`[AIDY] 日記の保存に失敗 ${e.date}:`, ex);
    }
  }

  private load(): Map<string, DiaryEntry> {
    if (this.entries) return this.entries;
    const map = new Map<string, DiaryEntry>();
    this.entries = map;
    const dir = this.dir;
    if (!dir.exists) return map;
    for (const entry of dir.list()) {
      if (!(entry instanceof File) || !entry.name.endsWith(".json")) continue;
      try {
        const e = DiaryEntry.fromJson(JSON.parse(entry.textSync()));
        if (e.date.length > 0) map.set(e.date, e);
      } catch (ex) {
        console.warn(`[AIDY] 日記の読込に失敗 ${entry.name}:`, ex);
      }
    }
    return map;
  }
}

export const DiaryStore = new DiaryStoreImpl();

const HappyWords = ["楽しい", "たのしい", "楽しかった", "うれしい", "嬉しい", "ありがとう", "好き", "すき", "おいしい", "美味しい", "やった", "最高", "面白", "おもしろ"];
const TiredWords = ["疲れ", "つかれ", "眠い", "ねむい", "しんどい", "だるい", "つらい", "大変"];

/**
 * 会話ログから「Aidy が書いた一日日記」を組み立てる。
 * 本体側でタイトル/本文/気分を生成して送ってきた場合はそちらを優先する。
 */
export const DiaryComposer = {
  compose(date: string, logs: ChatLine[] | null, title?: string, summary?: string, mood?: string): DiaryEntry {
    const sorted = (logs ?? []).filter((l) => l.text.length > 0).map((l) => new ChatLine(normalizeTime(l.time), l.speaker, l.text));
    sorted.sort((a, b) => (a.time < b.time ? -1 : a.time > b.time ? 1 : 0));

    const user = sorted.filter((l) => l.isUser);
    const m = mood && mood.length > 0 ? mood : detectMood(user);
    const t = title && title.length > 0 ? title : titleFor(m, user.length);
    const s = summary && summary.length > 0 ? summary : summarize(user, m);

    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, "0");
    const syncedAt = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
    return new DiaryEntry(date, t, s, m, "", syncedAt, sorted);
  },

  faceFor(mood: string): "Happy" | "Sleepy" | "Surprised" | "Normal" {
    switch (mood) {
      case "happy": return "Happy";
      case "tired":
      case "sleepy": return "Sleepy";
      case "surprised": return "Surprised";
      default: return "Normal";
    }
  },
};

function detectMood(user: ChatLine[]): string {
  let happy = 0;
  let tired = 0;
  for (const l of user) {
    for (const w of HappyWords) if (l.text.includes(w)) happy++;
    for (const w of TiredWords) if (l.text.includes(w)) tired++;
  }
  if (happy === 0 && tired === 0) return "normal";
  return happy >= tired ? "happy" : "tired";
}

function titleFor(mood: string, count: number): string {
  if (count === 0) return "しずかな一日";
  switch (mood) {
    case "happy": return "たのしい一日";
    case "tired": return "ちょっとおつかれの一日";
    default: return count >= 8 ? "たくさんおしゃべりした一日" : "いつもの一日";
  }
}

function summarize(user: ChatLine[], mood: string): string {
  if (user.length === 0) return "今日はあまりおしゃべりできなかったね。明日はいっぱい話そうね。";

  let s = `今日は${clock(user[0].time)}から${clock(user[user.length - 1].time)}まで、${user.length}回おしゃべりしたよ。`;
  const labels = ["朝", "昼", "夜"];
  for (let b = 0; b < 3; b++) {
    let best: ChatLine | null = null;
    for (const l of user) if (bucket(l.time) === b && (best == null || l.text.length > best.text.length)) best = l;
    if (best) s += `\n${labels[b]}は「${trim(best.text, 24)}」って話してくれたね。`;
  }
  if (mood === "happy") s += "\nたくさん笑ってくれて、ぼくもうれしかった！";
  else if (mood === "tired") s += "\nおつかれさま。今日はゆっくり休んでね。";
  else s += "\n明日もいっぱいお話ししようね。";
  return s;
}

function bucket(time: string): number {
  const h = parseInt(time.split(":")[0], 10) || 12;
  return h < 11 ? 0 : h < 17 ? 1 : 2;
}

function normalizeTime(t: string | undefined): string {
  if (!t || t.length === 0) return "00:00";
  const parts = t.split(":");
  const h = parseInt((parts[0] ?? "").trim(), 10) || 0;
  const m = parseInt((parts[1] ?? "").trim(), 10) || 0;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

function clock(time: string): string {
  return time.startsWith("0") ? time.substring(1) : time; // "07:42" → "7:42"
}

function trim(s: string, max: number): string {
  return s.length <= max ? s : s.substring(0, max) + "…";
}

/**
 * Wi-Fi 経由で Aidy 本体から会話ログを取得する。
 * 本体側 API: GET http://{host}/api/logs?date=yyyy-MM-dd
 *   200 → {"date":"2026-10-23","logs":[{"time":"08:12","speaker":"user","text":"おはよう"}, ...],
 *          "title":"(任意)","summary":"(任意)","mood":"(任意: happy/normal/tired)"}
 *   404 → その日のログなし
 */
export const AidyDeviceLink = {
  DefaultHost: "aidy.local",
  HostKey: "aidy.device.host",
  LastSyncKey: "aidy.device.lastSync",
  TimeoutMs: 4000,

  get host(): string {
    return Prefs.getString(this.HostKey, this.DefaultHost);
  },
  set host(value: string) {
    Prefs.putString(this.HostKey, value);
  },

  get lastSyncLabel(): string {
    const t = Prefs.getString(this.LastSyncKey, "");
    return t.length === 0 ? "まだ本体と同期していません" : `最終同期: ${t}`;
  },

  /** 今日から days 日分さかのぼってログを取得し、日記を作り直して保存する。 */
  async syncRecent(days: number): Promise<{ ok: boolean; message: string; updated: number }> {
    let updated = 0;
    for (let i = 0; i < days; i++) {
      const date = dateKey(addDays(new Date(), -i));
      let body: string;
      try {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), this.TimeoutMs);
        let res: Response;
        try {
          res = await fetch(`http://${this.host}/api/logs?date=${date}`, { signal: controller.signal });
        } finally {
          clearTimeout(timer);
        }
        if (res.status !== 200) continue; // 404 等 = その日のログなし
        body = await res.text();
      } catch {
        return { ok: false, message: `Aidy本体に接続できませんでした (${this.host})`, updated };
      }

      let entry: DiaryEntry;
      try {
        const o = JSON.parse(body);
        const logsRaw = Array.isArray(o.logs) ? o.logs : null;
        if (!logsRaw || logsRaw.length === 0) continue;
        const logs = logsRaw.map(ChatLine.fromJson);
        entry = DiaryComposer.compose(date, logs, o.title, o.summary, o.mood);
      } catch (ex) {
        console.warn(`[AIDY] ログの解析に失敗 ${date}:`, ex);
        continue;
      }
      entry.source = "device";
      await DiaryStore.save(entry);
      updated++;
    }
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, "0");
    Prefs.putString(this.LastSyncKey, `${now.getMonth() + 1}/${now.getDate()} ${pad(now.getHours())}:${pad(now.getMinutes())}`);
    return { ok: true, message: updated > 0 ? `${updated}日分の日記を更新しました` : "新しい会話ログはありませんでした", updated };
  },
};

/** 日記カレンダーの表示設定 (月表示 / 週表示)。設定画面から切り替える。 */
export const CalendarSettings = {
  WeekViewKey: "aidy.calendar.weekView",
  get weekView(): boolean {
    return Prefs.getInt(this.WeekViewKey, 0) === 1;
  },
  set weekView(value: boolean) {
    Prefs.putInt(this.WeekViewKey, value ? 1 : 0);
  },
};

/** カレンダーに登録する予定 1 件。date は "yyyy-MM-dd"、time は "HH:mm" (空なら終日)。 */
export class ScheduleItem {
  constructor(
    public id: string = "",
    public date: string = "",
    public time: string = "",
    public title: string = "",
    public memo: string = "",
  ) {}

  get allDay(): boolean {
    return this.time.length === 0;
  }

  /** 時刻つきの予定で、その時刻をもう過ぎているか (終日の予定は過ぎない扱い)。 */
  isPast(now: Date): boolean {
    if (this.allDay) return false;
    const pad = (n: number) => String(n).padStart(2, "0");
    const nowKey = `${dateKey(now)} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
    return `${this.date} ${this.time}` < nowKey;
  }

  toJson(): Record<string, string> {
    return { id: this.id, date: this.date, time: this.time, title: this.title, memo: this.memo };
  }

  static fromJson(o: Record<string, unknown>): ScheduleItem {
    return new ScheduleItem(String(o.id ?? ""), String(o.date ?? ""), String(o.time ?? ""), String(o.title ?? ""), String(o.memo ?? ""));
  }

  copy(partial: Partial<Pick<ScheduleItem, "id" | "date" | "time" | "title" | "memo">>): ScheduleItem {
    return new ScheduleItem(
      partial.id ?? this.id,
      partial.date ?? this.date,
      partial.time ?? this.time,
      partial.title ?? this.title,
      partial.memo ?? this.memo,
    );
  }
}

function uuid(): string {
  return "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));
}

/** 予定の保存先 (filesDir/schedule.json)。過去・未来どの日にも登録できる。 */
class ScheduleStoreImpl {
  private items: ScheduleItem[] | null = null;
  private get file(): File {
    return new File(Paths.document, "schedule.json");
  }

  /** その日の予定 (終日 → 時刻順)。時刻を過ぎた予定は後ろに回し、次の予定が先頭に来るようにする。 */
  async forDate(d: Date): Promise<ScheduleItem[]> {
    const key = dateKey(d);
    const now = new Date();
    const items = await this.load();
    return items
      .filter((it) => it.date === key)
      .sort((a, b) => {
        const pa = a.isPast(now);
        const pb = b.isPast(now);
        if (pa !== pb) return pa ? 1 : -1;
        if (a.allDay !== b.allDay) return a.allDay ? -1 : 1;
        return a.time < b.time ? -1 : a.time > b.time ? 1 : 0;
      });
  }

  /** id が空なら新規追加、既存 id なら上書き。 */
  async save(item0: ScheduleItem): Promise<void> {
    const list = await this.load();
    const item = item0.id.length === 0 ? item0.copy({ id: uuid() }) : item0;
    const idx = list.findIndex((it) => it.id === item.id);
    if (idx >= 0) list[idx] = item;
    else list.push(item);
    await this.write();
  }

  async delete(id: string): Promise<void> {
    const list = await this.load();
    const idx = list.findIndex((it) => it.id === id);
    if (idx < 0) return;
    list.splice(idx, 1);
    await this.write();
  }

  /** "9:5" "0930" "9" などを "HH:mm" に揃える。空なら ""(終日)、読めなければ null。 */
  parseTime(s0: string | null | undefined): string | null {
    const s = (s0 ?? "").trim().replace("：", ":");
    if (s.length === 0) return "";
    let h: number;
    let m = 0;
    if (s.includes(":")) {
      const p = s.split(":");
      if (p.length !== 2) return null;
      h = parseInt(p[0], 10);
      if (Number.isNaN(h)) return null;
      if (p[1].length > 0) {
        m = parseInt(p[1], 10);
        if (Number.isNaN(m)) return null;
      }
    } else {
      h = parseInt(s, 10);
      if (Number.isNaN(h)) return null;
      if (s.length >= 3) {
        m = h % 100;
        h = Math.floor(h / 100);
      }
    }
    if (h < 0 || h > 23 || m < 0 || m > 59) return null;
    return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
  }

  private async load(): Promise<ScheduleItem[]> {
    if (this.items) return this.items;
    const list: ScheduleItem[] = [];
    this.items = list;
    const file = this.file;
    if (!file.exists) return list;
    try {
      const arr = JSON.parse(file.textSync()).items ?? [];
      for (const raw of arr) {
        const it = ScheduleItem.fromJson(raw);
        if (it.date.length > 0) list.push(it);
      }
    } catch (ex) {
      console.warn("[AIDY] 予定の読込に失敗:", ex);
    }
    return list;
  }

  private async write(): Promise<void> {
    try {
      const items = await this.load();
      const file = this.file;
      if (!file.exists) file.create({ intermediates: true });
      file.writeSync(JSON.stringify({ items: items.map((it) => it.toJson()) }, null, 2));
    } catch (ex) {
      console.warn("[AIDY] 予定の保存に失敗:", ex);
    }
  }
}

export const ScheduleStore = new ScheduleStoreImpl();

/** 本体連携が完成するまでカレンダーの見た目を確認するためのサンプル日記 (「サンプル」表示付き)。 */
export const DiaryDemo = {
  /** 本体からの同期が動くようになったら false にする。 */
  enabled: true,
  SeededKey: "aidy.diary.demoSeeded",

  Conversations: [
    [
      ["07:42", "user", "おはよう、Aidy"],
      ["07:42", "aidy", "おはよう！今日はいい天気だよ"],
      ["12:15", "user", "お昼のパスタがすごくおいしかった"],
      ["12:16", "aidy", "いいなあ、何味だったの？"],
      ["12:16", "user", "トマトとバジル。また作りたい"],
      ["21:30", "user", "今日は楽しかった、ありがとう"],
      ["21:30", "aidy", "ぼくも楽しかったよ。おやすみ！"],
    ],
    [
      ["08:05", "user", "今日は朝から会議が3つある"],
      ["08:05", "aidy", "がんばってね！応援してるよ"],
      ["19:48", "user", "ただいま。さすがに疲れた"],
      ["19:48", "aidy", "おかえり！おつかれさま"],
      ["22:10", "user", "もう眠いから寝るね"],
      ["22:10", "aidy", "ゆっくり休んでね。おやすみ"],
    ],
    [
      ["10:20", "user", "Aidy、今日の予定を教えて"],
      ["10:20", "aidy", "15時に歯医者さんの予約があるよ"],
      ["16:02", "user", "歯医者おわった、意外と平気だった"],
      ["16:02", "aidy", "えらい！ちゃんと行けたね"],
      ["20:40", "user", "明日は友だちと出かける予定"],
      ["20:41", "aidy", "どこに行くの？楽しみだね"],
    ],
    [
      ["09:12", "user", "週末だからのんびりする"],
      ["09:12", "aidy", "いいね、ぼくもいっしょにのんびりする"],
      ["14:30", "user", "新しい本を読み始めた。面白い"],
      ["14:31", "aidy", "どんなお話？あとで教えてね"],
      ["18:55", "user", "夕焼けがきれいだった"],
      ["18:55", "aidy", "見たかったなあ。写真ある？"],
    ],
  ] as const,

  DaysAgo: [1, 2, 4, 5, 8, 11, 12, 15, 19, 23, 27, 33, 38],

  async seedIfNeeded(): Promise<void> {
    if (!this.enabled || Prefs.getInt(this.SeededKey, 0) === 1) return;
    for (let i = 0; i < this.DaysAgo.length; i++) {
      const conv = this.Conversations[i % this.Conversations.length];
      const logs = conv.map((l) => new ChatLine(l[0], l[1], l[2]));
      const e = DiaryComposer.compose(dateKey(addDays(new Date(), -this.DaysAgo[i])), logs);
      e.source = "demo";
      await DiaryStore.save(e);
    }
    Prefs.putInt(this.SeededKey, 1);
  },
};
