import * as Calendar from "expo-calendar";
import { Prefs } from "./prefs";

/** iPhone のカレンダーにある予定 1 件 (日ごとに分けたもの)。date は "yyyy-MM-dd"、time は "HH:mm" (空なら終日)。 */
export type PhoneEvent = {
  eventId: string;
  date: string;
  time: string;
  title: string;
  memo: string;
};

const CalendarTitle = "AIdy";
const CalendarColor = "#3b82f6";

function pad(n: number): string {
  return String(n).padStart(2, "0");
}
function keyOf(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
function parseKey(date: string, time: string): Date {
  const [y, m, d] = date.split("-").map((s) => parseInt(s, 10));
  const [h, min] = time.length > 0 ? time.split(":").map((s) => parseInt(s, 10)) : [0, 0];
  return new Date(y, m - 1, d, h, min);
}

/**
 * iPhone のカレンダーとの連動 (expo-calendar)。
 * アプリで作った予定は専用カレンダー「AIdy」に書き込み、iPhone 側の全カレンダーの予定は読み取って日記カレンダーに表示する。
 * expo-calendar は Expo Go では動かない (開発ビルドが必要) ので、失敗はすべて握りつぶしてアプリ側の予定だけで動くようにする。
 */
export const PhoneCalendar = {
  EnabledKey: "aidy.calendar.phoneSync",
  CalendarIdKey: "aidy.calendar.phoneCalendarId",

  get enabled(): boolean {
    return Prefs.getInt(this.EnabledKey, 0) === 1;
  },

  /** 許可を求めて「AIdy」カレンダーを用意する。失敗したら連動はオフのまま。 */
  async enable(): Promise<{ ok: boolean; message: string }> {
    try {
      const { status } = await Calendar.requestCalendarPermissions();
      if (status !== "granted") {
        return { ok: false, message: "カレンダーへのアクセスが許可されていません (設定アプリ > AIDY から許可できます)" };
      }
      if (!(await this.aidyCalendar())) return { ok: false, message: "「AIdy」カレンダーを作れませんでした" };
    } catch (ex) {
      console.warn("[AIDY] カレンダー連動の開始に失敗:", ex);
      return { ok: false, message: "この環境ではカレンダー連動を使えません (Expo Go 不可・開発ビルドが必要)" };
    }
    Prefs.putInt(this.EnabledKey, 1);
    return { ok: true, message: "iPhoneのカレンダーと連動しています" };
  },

  disable(): void {
    Prefs.putInt(this.EnabledKey, 0);
  },

  /** アプリの予定を「AIdy」カレンダーに書き込む。eventId があれば上書き (iPhone 側で消されていたら作り直す)。新しい eventId を返す。 */
  async push(eventId: string, date: string, time: string, title: string, memo: string): Promise<string> {
    if (!this.enabled) return eventId;
    const allDay = time.length === 0;
    const start = parseKey(date, time);
    const end = allDay ? parseKey(date, "23:59") : new Date(start.getTime() + 60 * 60 * 1000);
    const details = { title, notes: memo, startDate: start, endDate: end, allDay };
    try {
      if (eventId.length > 0) {
        try {
          const ev = await Calendar.ExpoCalendarEvent.get(eventId);
          await ev.update(details);
          return eventId;
        } catch {
          // iPhone 側で消されている → 作り直す
        }
      }
      const cal = await this.aidyCalendar();
      if (!cal) return "";
      const ev = await cal.createEvent(details);
      return ev.id;
    } catch (ex) {
      console.warn("[AIDY] iPhoneカレンダーへの書き込みに失敗:", ex);
      return eventId;
    }
  },

  async remove(eventId: string): Promise<void> {
    if (!this.enabled || eventId.length === 0) return;
    try {
      const ev = await Calendar.ExpoCalendarEvent.get(eventId);
      await ev.delete();
    } catch (ex) {
      console.warn("[AIDY] iPhoneカレンダーからの削除に失敗:", ex);
    }
  },

  /**
   * アプリから入れた予定の、iPhone 側での今の状態。iPhone で編集されていれば新しい内容、消されていれば null。
   * 連動オフや読み取りエラーのときは undefined (= 何もしない)。
   */
  async lookup(eventId: string): Promise<PhoneEvent | null | undefined> {
    if (!this.enabled || eventId.length === 0) return undefined;
    let ev: Calendar.ExpoCalendarEvent;
    try {
      ev = await Calendar.ExpoCalendarEvent.get(eventId);
    } catch {
      return null;
    }
    const start = new Date(ev.startDate);
    return {
      eventId,
      date: keyOf(start),
      time: ev.allDay ? "" : `${pad(start.getHours())}:${pad(start.getMinutes())}`,
      title: ev.title,
      memo: ev.notes ?? "",
    };
  },

  /** from 〜 to (その日を含む) の iPhone の予定を、日ごとに分けて返す。何日にもまたがる予定は 2 日目以降を終日扱いにする。 */
  async eventsBetween(from: Date, to: Date): Promise<PhoneEvent[]> {
    if (!this.enabled) return [];
    const rangeStart = new Date(from.getFullYear(), from.getMonth(), from.getDate());
    const rangeEnd = new Date(to.getFullYear(), to.getMonth(), to.getDate() + 1);
    let events: Calendar.ExpoCalendarEvent[];
    try {
      const calendars = await Calendar.getCalendars(Calendar.EntityTypes.EVENT);
      if (calendars.length === 0) return [];
      events = await Calendar.listEvents(calendars, rangeStart, rangeEnd);
    } catch (ex) {
      console.warn("[AIDY] iPhoneカレンダーの読み込みに失敗:", ex);
      return [];
    }

    const out: PhoneEvent[] = [];
    for (const ev of events) {
      const start = new Date(ev.startDate);
      const end = new Date(ev.endDate);
      const day = new Date(Math.max(start.getTime(), rangeStart.getTime()));
      day.setHours(0, 0, 0, 0);
      // 終了時刻ちょうどの日は含めない (0:00 終わりの予定が翌日にも出ないように)。ただし最低 1 日は出す。
      const last = new Date(Math.max(start.getTime(), end.getTime() - 1));
      while (day.getTime() < rangeEnd.getTime() && day.getTime() <= last.getTime()) {
        const first = keyOf(day) === keyOf(start);
        out.push({
          eventId: ev.id,
          date: keyOf(day),
          time: ev.allDay || !first ? "" : `${pad(start.getHours())}:${pad(start.getMinutes())}`,
          title: ev.title,
          memo: ev.notes ?? "",
        });
        day.setDate(day.getDate() + 1);
      }
    }
    return out;
  },

  /** 専用カレンダー「AIdy」。保存済みの id で見つからなければ (iPhone 側で消された等) 新しく作る。 */
  async aidyCalendar(): Promise<Calendar.ExpoCalendar | null> {
    const savedId = Prefs.getString(this.CalendarIdKey, "");
    if (savedId.length > 0) {
      try {
        return await Calendar.ExpoCalendar.get(savedId);
      } catch {
        // 消されている → 作り直す
      }
    }
    const source = Calendar.getDefaultCalendarSync().source;
    const cal = await Calendar.createCalendar({
      title: CalendarTitle,
      color: CalendarColor,
      entityType: Calendar.EntityTypes.EVENT,
      sourceId: source.id,
      source,
      name: CalendarTitle,
      ownerAccount: "personal",
      accessLevel: Calendar.CalendarAccessLevel.OWNER,
    });
    Prefs.putString(this.CalendarIdKey, cal.id);
    return cal;
  },
};
