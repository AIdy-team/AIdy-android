import { Directory, File, Paths } from "expo-file-system";
import { Prefs } from "./prefs";
import { PhoneCalendar } from "./phoneCalendar";
import { BleLink, BleLinkError } from "./bleLink";

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
 * Aidy 本体と話す。通り道は Bluetooth (BLE, bleLink.ts・本体Bluetooth対応_仕様.txt) か Wi-Fi (http://{host}) のどちらか。
 * 口・中身・合言葉のルールはどちらも同じ。
 * Wi-Fi 版:口の形は AIdy_アプリ連携_仕様.md のとおり (Kotlin版 Diary.kt の AidyDeviceLink)。
 * すべての通信に合言葉 (ヘッダー X-Aidy-Token) が必要。401・403 はどちらも「合言葉が違います」として扱う。
 * まだ本体にない口 (ようす・予定の書き込み・話しかけ) は設定画面の「今後の開発予定」に載せるだけで、ここには実装しない。
 */
export interface DeviceResult {
  ok: boolean;
  message: string;
  updated: number;
}
export interface WeekEvent {
  time: string;
  end: string;
  title: string;
}
export interface WeekTodo {
  label: string;
  text: string;
}
export interface WeekDay {
  date: string;
  events: WeekEvent[];
  todo: WeekTodo[];
}
export interface WeekResult {
  ok: boolean;
  days: WeekDay[];
  synced: number;
  error: string | null;
}
export interface DaysResult {
  ok: boolean;
  days: Set<string>;
  error: string | null;
}
export interface FaqEntry {
  q: string;
  a: string;
}
export interface DictData {
  faq: FaqEntry[];
  asr: Array<[string, string]>;
  yomi: Array<[string, string]>;
}
export interface DictResult {
  ok: boolean;
  dict: DictData | null;
  error: string | null;
}
/** 本体の声の設定。volume = 音量 0〜100 (%)、speed = 話す速さ (1.0 がふつう、0.5〜1.5)。 */
export interface VoiceSettings {
  volume: number;
  speed: number;
}
/** unsupported = 本体がまだ /api/settings を持っていない (404)。 */
export interface SettingsResult {
  ok: boolean;
  settings: VoiceSettings | null;
  error: string | null;
  unsupported?: boolean;
}

/** 通信できなかった (タイムアウト・つながらない)。 */
class NetworkError extends Error {}

type Json = Record<string, unknown>;

function str(v: unknown): string {
  return v == null ? "" : String(v);
}
function arr(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}
function obj(v: unknown): Json | null {
  return v != null && typeof v === "object" && !Array.isArray(v) ? (v as Json) : null;
}

const HostKey = "aidy.device.host";
const TokenKey = "aidy.device.token";
const LastSyncKey = "aidy.device.lastSync";
const TransportKey = "aidy.device.transport";
const BleNameKey = "aidy.device.bleName";
const TimeoutMs = 4000;

/** 本体とのつなぎ方。 */
export type Transport = "ble" | "wifi";

/** QR から読んだ中身。host は Bluetooth だけの QR では空、bleName は b= が無ければ空。 */
export interface QrInfo {
  host: string;
  token: string;
  bleName: string;
}

export const AidyDeviceLink = {
  /** 本体のアドレス ("192.168.1.23:8090" など、QR の host の部分)。未設定なら空文字。 */
  get host(): string {
    return Prefs.getString(HostKey, "");
  },
  set host(value: string) {
    Prefs.putString(HostKey, value.trim());
  },

  /** 合言葉 (QR の "t=" の後ろ)。未設定なら空文字。画面には伏せ字でしか出さないこと。 */
  get token(): string {
    return Prefs.getString(TokenKey, "");
  },
  set token(value: string) {
    Prefs.putString(TokenKey, value.trim());
  },

  /** つなぎ方。既定は Bluetooth (設定で Wi-Fi に戻せる)。 */
  get transport(): Transport {
    const t = Prefs.getString(TransportKey, "");
    return t === "wifi" ? "wifi" : "ble";
  },
  set transport(value: Transport) {
    if (value !== this.transport) void BleLink.disconnect();
    Prefs.putString(TransportKey, value);
  },

  /** 本体の Bluetooth の広告名 ("AIdy-1A2B"、QR の "b=")。空なら近くの AIdy を探す。 */
  get bleName(): string {
    return Prefs.getString(BleNameKey, "");
  },
  set bleName(value: string) {
    const v = value.trim();
    if (v !== this.bleName) void BleLink.forget();
    Prefs.putString(BleNameKey, v);
  },

  /** 画面に出す「どこにつなぐか」。 */
  get targetLabel(): string {
    if (this.transport === "ble") return this.bleName.length > 0 ? `${this.bleName} (Bluetooth)` : "近くの Aidy (Bluetooth)";
    return this.host;
  },

  /** 直前に /api/ping が答えた展示モードかどうか。 */
  exhibit: false,

  /** Bluetooth で最後に失敗した理由 (画面に出す文)。 */
  lastBleError: "",

  get isConfigured(): boolean {
    if (this.token.length === 0) return false;
    return this.transport === "ble" ? true : this.host.length > 0;
  },

  get lastSyncLabel(): string {
    const t = Prefs.getString(LastSyncKey, "");
    return t.length === 0 ? "まだ本体と同期していません" : `最終同期: ${t}`;
  },

  /** 接続エラー・401・403 の言い回しをアプリ全体でそろえる。 */
  errorMessage(code: number | null): string {
    if (code === 401 || code === 403) return "合言葉が違います。本体のQRを読み直してください";
    if (code == null) {
      if (this.transport === "ble") return this.lastBleError.length > 0 ? this.lastBleError : "Bluetooth で Aidy本体につながりません";
      return `Aidy本体につながりません (${this.host})`;
    }
    return `本体でエラーが起きました (${code})`;
  },

  /** GET/POST して (status, 本文) を返す。つながらなければ NetworkError。 */
  async request(path: string, body?: Json): Promise<{ status: number; text: string }> {
    if (this.transport === "ble") return this.requestBle(path, body);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TimeoutMs);
    try {
      const headers: Record<string, string> = { "X-Aidy-Token": this.token };
      if (body) headers["Content-Type"] = "application/json; charset=utf-8";
      const res = await fetch(`http://${this.host}${path}`, {
        method: body ? "POST" : "GET",
        headers,
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });
      const text = res.status === 200 ? await res.text() : "";
      return { status: res.status, text };
    } catch {
      throw new NetworkError();
    } finally {
      clearTimeout(timer);
    }
  },

  /** Bluetooth で 1 件送る。返事の形は Wi-Fi 版にそろえる (200 のときだけ本文の JSON を文字で返す)。 */
  async requestBle(path: string, body?: Json): Promise<{ status: number; text: string }> {
    try {
      const r = await BleLink.request(body ? "POST" : "GET", path, this.token, body, this.bleName);
      this.lastBleError = "";
      return { status: r.status, text: r.status === 200 ? JSON.stringify(r.body ?? {}) : "" };
    } catch (e) {
      this.lastBleError = e instanceof BleLinkError ? e.message : "Bluetooth で Aidy本体につながりません";
      console.warn("[AIDY] BLE:", this.lastBleError);
      throw new NetworkError();
    }
  },

  /** GET /api/ping。設定画面の「保存して接続を試す」で使う。 */
  async ping(): Promise<{ ok: boolean; message: string }> {
    if (!this.isConfigured) return { ok: false, message: this.transport === "ble" ? "合言葉を入れてください" : "住所と合言葉を入れてください" };
    try {
      const r = await this.request("/api/ping");
      if (r.status !== 200) return { ok: false, message: this.errorMessage(r.status) };
      let name = "";
      try {
        const o = JSON.parse(r.text) as Json;
        this.exhibit = o.exhibit === true;
        name = str(o.host ?? o.hostname ?? o.name ?? "");
      } catch {
        this.exhibit = false;
      }
      if (this.transport === "ble") {
        const n = name.length > 0 ? name : BleLink.connectedName;
        return { ok: true, message: n.length > 0 ? `Aidy本体 (${n}) につながりました (Bluetooth)` : "Aidy本体につながりました (Bluetooth)" };
      }
      return { ok: true, message: name.length > 0 ? `Aidy本体 (${name}) につながりました (Wi-Fi)` : "Aidy本体につながりました (Wi-Fi)" };
    } catch {
      return { ok: false, message: this.errorMessage(null) };
    }
  },

  /**
   * 今日から days 日分さかのぼってログを取得し、日記を作り直して保存する。
   * 本体側 API: GET /api/logs?date=yyyy-MM-dd → {"logs":[{"time","speaker","text"}...], "title","summary","mood" (任意)}、404 = その日のログなし
   */
  async syncRecent(days: number): Promise<DeviceResult> {
    if (!this.isConfigured) return { ok: false, message: "本体と接続していません (設定 > Aidy本体と接続)", updated: 0 };
    let updated = 0;
    for (let i = 0; i < days; i++) {
      const date = dateKey(addDays(new Date(), -i));
      let text: string;
      try {
        const r = await this.request(`/api/logs?date=${date}`);
        if (r.status === 401 || r.status === 403) return { ok: false, message: this.errorMessage(r.status), updated };
        if (r.status !== 200) continue; // 404 等 = その日のログなし
        text = r.text;
      } catch {
        return { ok: false, message: this.errorMessage(null), updated };
      }

      let entry: DiaryEntry;
      try {
        const o = JSON.parse(text);
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
    Prefs.putString(LastSyncKey, `${now.getMonth() + 1}/${now.getDate()} ${pad(now.getHours())}:${pad(now.getMinutes())}`);
    return { ok: true, message: updated > 0 ? `${updated}日分の日記を更新しました` : "新しい会話ログはありませんでした", updated };
  },

  /**
   * 本体の QR の中身から住所・合言葉・Bluetooth の名前を取り出す。読めなければ null。
   *   "http://192.168.1.23:8090/?t=AbCdEf123456&b=AIdy-1A2B"  (b= は無いこともある)
   *   "aidy://ble?b=AIdy-1A2B&t=AbCdEf123456"                   (Wi-Fi が無いときの Bluetooth だけの形。host は空)
   */
  parseQr(raw: string): QrInfo | null {
    const m = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)[^?#]*\?([^#]*)/i.exec(raw.trim());
    if (!m) return null;
    const bleOnly = m[1].toLowerCase() === "aidy";
    const host = bleOnly ? "" : m[2];
    let token = "";
    let bleName = "";
    for (const part of m[3].split("&")) {
      const eq = part.indexOf("=");
      if (eq <= 0) continue;
      const k = part.substring(0, eq);
      let v = part.substring(eq + 1);
      try {
        v = decodeURIComponent(v);
      } catch {
        // そのまま使う
      }
      if (k === "t" && token.length === 0) token = v;
      else if (k === "b" && bleName.length === 0) bleName = v;
    }
    if (token.length === 0) return null;
    if (host.length === 0 && bleName.length === 0) return null;
    return { host, token, bleName };
  },

  /** GET /api/week (読むだけ)。今日から7日分の予定・やることを取得する。 */
  async fetchWeek(): Promise<WeekResult> {
    if (!this.isConfigured) return { ok: false, days: [], synced: 0, error: "本体と接続していません" };
    try {
      const r = await this.request("/api/week");
      if (r.status !== 200) return { ok: false, days: [], synced: 0, error: this.errorMessage(r.status) };
      try {
        const o = JSON.parse(r.text) as Json;
        const days: WeekDay[] = arr(o.days).map((d0) => {
          const d = obj(d0) ?? {};
          return {
            date: str(d.date),
            events: arr(d.events).map((e0) => {
              const e = obj(e0) ?? {};
              return { time: str(e.time), end: str(e.end), title: str(e.title) };
            }),
            todo: arr(d.todo).map((t0) => {
              const t = obj(t0) ?? {};
              return { label: str(t.label), text: str(t.text) };
            }),
          };
        });
        return { ok: true, days, synced: Number(o.synced ?? 0) || 0, error: null };
      } catch (e) {
        console.warn("[AIDY] week の解析に失敗:", e);
        return { ok: false, days: [], synced: 0, error: "本体からの返事を読み取れませんでした" };
      }
    } catch {
      return { ok: false, days: [], synced: 0, error: this.errorMessage(null) };
    }
  },

  /** GET /api/days。会話・記録がある日の一覧 ("yyyyMMdd") を "yyyy-MM-dd" に揃えて返す。 */
  async fetchDays(): Promise<DaysResult> {
    if (!this.isConfigured) return { ok: false, days: new Set(), error: "本体と接続していません" };
    try {
      const r = await this.request("/api/days");
      if (r.status !== 200) return { ok: false, days: new Set(), error: this.errorMessage(r.status) };
      try {
        const days = new Set<string>();
        for (const v of arr((JSON.parse(r.text) as Json).days)) {
          const s = str(v);
          if (s.length === 8) days.add(`${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}`);
        }
        return { ok: true, days, error: null };
      } catch (e) {
        console.warn("[AIDY] days の解析に失敗:", e);
        return { ok: false, days: new Set(), error: "本体からの返事を読み取れませんでした" };
      }
    } catch {
      return { ok: false, days: new Set(), error: this.errorMessage(null) };
    }
  },

  // ---------------------------------------------------------------- 辞書 (FAQ・聞き間違い・読み)

  parseDict(o: Json): DictData {
    const first = (v: unknown) => str(arr(v)[0] ?? "");
    const pairs = (v: unknown): Array<[string, string]> =>
      arr(v)
        .map((p) => arr(p))
        .filter((p) => p.length >= 2)
        .map((p) => [str(p[0]), str(p[1])] as [string, string]);
    return {
      faq: arr(o.faq).map((f0) => {
        const f = obj(f0) ?? {};
        return { q: first(f.q), a: first(f.a) };
      }),
      asr: pairs(o.asr),
      yomi: pairs(o.yomi),
    };
  },

  /** GET /api/dict。 */
  async fetchDict(): Promise<DictResult> {
    if (!this.isConfigured) return { ok: false, dict: null, error: "本体と接続していません" };
    try {
      const r = await this.request("/api/dict");
      if (r.status !== 200) return { ok: false, dict: null, error: this.errorMessage(r.status) };
      try {
        return { ok: true, dict: this.parseDict(JSON.parse(r.text)), error: null };
      } catch (e) {
        console.warn("[AIDY] dict の解析に失敗:", e);
        return { ok: false, dict: null, error: "本体からの返事を読み取れませんでした" };
      }
    } catch {
      return { ok: false, dict: null, error: this.errorMessage(null) };
    }
  },

  /** POST /api/add。kind は "faq" ・ "asr" ・ "yomi"。成功なら更新後の辞書 (dict) が返る。 */
  async addDictEntry(kind: string, a: string, b: string): Promise<DictResult> {
    if (!this.isConfigured) return { ok: false, dict: null, error: "本体と接続していません" };
    return this.postDict("/api/add", { kind, a, b });
  },

  /** POST /api/del ("asr" ・ "yomi" は key、"faq" は idx)。成功なら更新後の辞書 (dict) が返る。 */
  async deleteDictEntry(kind: string, key: string | null, idx: number | null): Promise<DictResult> {
    if (!this.isConfigured) return { ok: false, dict: null, error: "本体と接続していません" };
    const body: Json = { kind };
    if (key != null) body.key = key;
    if (idx != null) body.idx = idx;
    return this.postDict("/api/del", body);
  },

  async postDict(path: string, body: Json): Promise<DictResult> {
    try {
      const r = await this.request(path, body);
      if (r.status !== 200) return { ok: false, dict: null, error: this.errorMessage(r.status) };
      try {
        const res = JSON.parse(r.text) as Json;
        if (res.ok !== true) return { ok: false, dict: null, error: res.error != null ? str(res.error) : "できませんでした" };
        const d = obj(res.dict);
        return { ok: true, dict: d ? this.parseDict(d) : null, error: null };
      } catch (e) {
        console.warn("[AIDY] 辞書の更新に失敗:", e);
        return { ok: false, dict: null, error: "本体からの返事を読み取れませんでした" };
      }
    } catch {
      return { ok: false, dict: null, error: this.errorMessage(null) };
    }
  },

  // ------------------------------------------------------------ 音量・話す速さ (/api/settings、本体側の仕様が決まるまでの仮の形)

  /** 本体の返事から設定を読む。{"settings": {...}} でも {...} 直下でも、speed の代わりに rate でも読める。 */
  parseSettings(o: Json): VoiceSettings | null {
    const s = obj(o.settings) ?? o;
    if (!("volume" in s) && !("speed" in s) && !("rate" in s)) return null;
    const vol = Number(s.volume ?? VoiceStore.volume);
    const sp = Number("speed" in s ? s.speed : s.rate ?? VoiceStore.speed);
    return {
      volume: Math.min(100, Math.max(0, Math.round(Number.isFinite(vol) ? vol : VoiceStore.volume))),
      speed: Math.min(VoiceStore.MaxSpeed, Math.max(VoiceStore.MinSpeed, Number.isFinite(sp) ? sp : VoiceStore.speed)),
    };
  },

  settingsError(code: number | null): SettingsResult {
    if (code === 404) return { ok: false, settings: null, error: "本体がまだ音量・速さの設定に対応していません", unsupported: true };
    return { ok: false, settings: null, error: this.errorMessage(code) };
  },

  /** GET /api/settings。読めたら端末にも覚えておく。 */
  async fetchSettings(): Promise<SettingsResult> {
    if (!this.isConfigured) return { ok: false, settings: null, error: "本体と接続していません" };
    try {
      const r = await this.request("/api/settings");
      if (r.status !== 200) return this.settingsError(r.status);
      try {
        const v = this.parseSettings(JSON.parse(r.text));
        if (!v) return { ok: false, settings: null, error: "本体からの返事を読み取れませんでした" };
        VoiceStore.remember(v);
        return { ok: true, settings: v, error: null };
      } catch (e) {
        console.warn("[AIDY] settings の解析に失敗:", e);
        return { ok: false, settings: null, error: "本体からの返事を読み取れませんでした" };
      }
    } catch {
      return { ok: false, settings: null, error: this.errorMessage(null) };
    }
  },

  /** POST /api/settings {"volume": 60, "speed": 1.0}。返事に設定があればそれを、なければ送った値を覚える。 */
  async saveSettings(v: VoiceSettings): Promise<SettingsResult> {
    VoiceStore.remember(v);
    if (!this.isConfigured) return { ok: false, settings: null, error: "本体と接続していません (この端末に保存しました)" };
    try {
      const r = await this.request("/api/settings", { volume: v.volume, speed: Math.round(v.speed * 100) / 100 });
      if (r.status !== 200) return this.settingsError(r.status);
      let res: Json | null = null;
      try {
        res = JSON.parse(r.text);
      } catch {
        res = null;
      }
      if (res && "ok" in res && res.ok !== true) return { ok: false, settings: null, error: res.error != null ? str(res.error) : "できませんでした" };
      const now = (res && this.parseSettings(res)) ?? v;
      VoiceStore.remember(now);
      return { ok: true, settings: now, error: null };
    } catch {
      return { ok: false, settings: null, error: this.errorMessage(null) };
    }
  },
};

/** 最後に知っている声の設定 (本体とつながっていなくてもスライダーに出す用)。 */
export const VoiceStore = {
  MinSpeed: 0.5,
  MaxSpeed: 1.5,
  VolumeKey: "aidy.voice.volume",
  SpeedKey: "aidy.voice.speed100",

  get volume(): number {
    return Prefs.getInt(this.VolumeKey, 60);
  },
  get speed(): number {
    return Prefs.getInt(this.SpeedKey, 100) / 100;
  },
  remember(v: VoiceSettings): void {
    Prefs.putInt(this.VolumeKey, v.volume);
    Prefs.putInt(this.SpeedKey, Math.round(v.speed * 100));
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

/**
 * カレンダーに登録する予定 1 件。date は "yyyy-MM-dd"、time は "HH:mm" (空なら終日)。
 * eventId は iPhone のカレンダーに書き込んだ予定の id (連動オフなら空)。
 * source が "phone" のものは iPhone のカレンダーから読み込んだ予定で、保存はせずアプリからは編集できない。
 */
export class ScheduleItem {
  constructor(
    public id: string = "",
    public date: string = "",
    public time: string = "",
    public title: string = "",
    public memo: string = "",
    public eventId: string = "",
    public source: string = "",
  ) {}

  get allDay(): boolean {
    return this.time.length === 0;
  }

  get fromPhone(): boolean {
    return this.source === "phone";
  }

  /** 時刻つきの予定で、その時刻をもう過ぎているか (終日の予定は過ぎない扱い)。 */
  isPast(now: Date): boolean {
    if (this.allDay) return false;
    const pad = (n: number) => String(n).padStart(2, "0");
    const nowKey = `${dateKey(now)} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
    return `${this.date} ${this.time}` < nowKey;
  }

  toJson(): Record<string, string> {
    return { id: this.id, date: this.date, time: this.time, title: this.title, memo: this.memo, eventId: this.eventId };
  }

  static fromJson(o: Record<string, unknown>): ScheduleItem {
    return new ScheduleItem(
      String(o.id ?? ""),
      String(o.date ?? ""),
      String(o.time ?? ""),
      String(o.title ?? ""),
      String(o.memo ?? ""),
      String(o.eventId ?? ""),
    );
  }

  copy(partial: Partial<Pick<ScheduleItem, "id" | "date" | "time" | "title" | "memo" | "eventId">>): ScheduleItem {
    return new ScheduleItem(
      partial.id ?? this.id,
      partial.date ?? this.date,
      partial.time ?? this.time,
      partial.title ?? this.title,
      partial.memo ?? this.memo,
      partial.eventId ?? this.eventId,
      this.source,
    );
  }
}

/** 終日 → 時刻順。時刻を過ぎた予定は後ろに回し、次の予定が先頭に来るようにする。 */
function sortSchedule(items: ScheduleItem[], now: Date): ScheduleItem[] {
  return items.sort((a, b) => {
    const pa = a.isPast(now);
    const pb = b.isPast(now);
    if (pa !== pb) return pa ? 1 : -1;
    if (a.allDay !== b.allDay) return a.allDay ? -1 : 1;
    return a.time < b.time ? -1 : a.time > b.time ? 1 : 0;
  });
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

  /**
   * 日付ごとの予定 (並びは sortSchedule)。iPhone のカレンダーと連動中なら、
   * アプリから入れた予定に iPhone 側での編集・削除を反映してから、iPhone の予定も混ぜて返す。
   */
  async forDates(dates: Date[]): Promise<Map<string, ScheduleItem[]>> {
    const keys = dates.map(dateKey);
    const result = new Map<string, ScheduleItem[]>(keys.map((k) => [k, []]));
    if (dates.length === 0) return result;
    await this.pullPhoneChanges(new Set(keys));

    const items = await this.load();
    for (const it of items) result.get(it.date)?.push(it);

    const first = dates.reduce((a, b) => (a < b ? a : b));
    const last = dates.reduce((a, b) => (a > b ? a : b));
    const mine = new Set(items.map((it) => it.eventId).filter((id) => id.length > 0));
    for (const ev of await PhoneCalendar.eventsBetween(first, last)) {
      if (mine.has(ev.eventId)) continue; // アプリから入れた予定 (上で表示済み)
      result.get(ev.date)?.push(new ScheduleItem(`phone:${ev.eventId}:${ev.date}`, ev.date, ev.time, ev.title, ev.memo, ev.eventId, "phone"));
    }

    const now = new Date();
    for (const list of result.values()) sortSchedule(list, now);
    return result;
  }

  /** id が空なら新規追加、既存 id なら上書き。連動中は iPhone のカレンダーにも書き込む。 */
  async save(item0: ScheduleItem): Promise<void> {
    const list = await this.load();
    let item = item0.id.length === 0 ? item0.copy({ id: uuid() }) : item0;
    const eventId = await PhoneCalendar.push(item.eventId, item.date, item.time, item.title, item.memo);
    if (eventId !== item.eventId) item = item.copy({ eventId });
    const idx = list.findIndex((it) => it.id === item.id);
    if (idx >= 0) list[idx] = item;
    else list.push(item);
    await this.write();
  }

  async delete(id: string): Promise<void> {
    const list = await this.load();
    const idx = list.findIndex((it) => it.id === id);
    if (idx < 0) return;
    const [removed] = list.splice(idx, 1);
    await this.write();
    await PhoneCalendar.remove(removed.eventId);
  }

  /** 連動を始めたとき、まだ iPhone に書き込んでいない予定をまとめて書き込む。 */
  async pushAllToPhone(): Promise<void> {
    const list = await this.load();
    let changed = false;
    for (let i = 0; i < list.length; i++) {
      if (list[i].eventId.length > 0) continue;
      const eventId = await PhoneCalendar.push("", list[i].date, list[i].time, list[i].title, list[i].memo);
      if (eventId.length === 0) continue;
      list[i] = list[i].copy({ eventId });
      changed = true;
    }
    if (changed) await this.write();
  }

  /**
   * 表示する日 (と、そこから移動された可能性のある予定) について、iPhone 側の今の状態を取り込む。
   * iPhone で消された予定は、うっかり消えないようアプリには残して連動だけ外す。
   */
  private async pullPhoneChanges(keys: Set<string>): Promise<void> {
    if (!PhoneCalendar.enabled) return;
    const list = await this.load();
    let changed = false;
    for (let i = 0; i < list.length; i++) {
      const it = list[i];
      if (it.eventId.length === 0 || !keys.has(it.date)) continue;
      const ev = await PhoneCalendar.lookup(it.eventId);
      if (ev === undefined) continue;
      const next = ev === null ? it.copy({ eventId: "" }) : it.copy({ date: ev.date, time: ev.time, title: ev.title, memo: ev.memo });
      if (JSON.stringify(next.toJson()) === JSON.stringify(it.toJson())) continue;
      list[i] = next;
      changed = true;
    }
    if (changed) await this.write();
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
