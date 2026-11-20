import { PermissionsAndroid, Platform } from "react-native";
import type { BleManager as BleManagerType, Device, Subscription } from "react-native-ble-plx";
import { Prefs } from "./prefs";

/**
 * Bluetooth (BLE) で Aidy 本体と話す (Kotlin 版 BleLink.kt 相当)。形は「本体Bluetooth対応_仕様.txt」のとおり。
 *   依頼 (RX に Write):   {"id","m","p","t","b"} + "\n"
 *   返事 (TX の Notify):  {"id","s","b"} + "\n"
 * 1 行が長いときは (MTU-3) バイトずつに分けて書き、届いた断片は "\n" までバイトのままつなげてから UTF-8 に直す。
 * 同時に 1 件だけ送る。最後の依頼から 30 秒たったら自分から切る (次の依頼でまたつなぐ)。
 * react-native-ble-plx はネイティブのモジュールなので、Expo Go では動かない (開発ビルドが必要)。
 */

export const ServiceUuid = "a1d70001-8c4f-4b6e-9d2a-5f3e7c1b0a90";
export const RxUuid = "a1d70002-8c4f-4b6e-9d2a-5f3e7c1b0a90"; // アプリ → 本体 (Write)
export const TxUuid = "a1d70003-8c4f-4b6e-9d2a-5f3e7c1b0a90"; // 本体 → アプリ (Notify)

const ScanTimeoutMs = 8000;
const ConnectTimeoutMs = 10000;
const ReplyTimeoutMs = 10000;
const IdleCloseMs = 30000;
const WantMtu = 517;
const DefaultMtu = 23;
const MaxLine = 256 * 1024;

const DeviceIdKey = "aidy.ble.deviceId"; // 一度つながった本体の Bluetooth の ID (iOS は端末ごとの UUID、Android はアドレス)
const DeviceIdNameKey = "aidy.ble.deviceIdName"; // その ID を覚えたときに探していた名前 ("" = 名前指定なし)

/** BLE で通信できなかった (見つからない・つながらない・時間切れ…)。message はそのまま画面に出せる文。 */
export class BleLinkError extends Error {}

export interface BleReply {
  status: number;
  body: unknown;
}

// ------------------------------------------------------------------ UTF-8 / Base64 (ble-plx の値は Base64 の文字列)

function utf8Encode(s: string): Uint8Array {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
      const d = s.charCodeAt(i + 1);
      if (d >= 0xdc00 && d <= 0xdfff) {
        c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00);
        i++;
      }
    }
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return Uint8Array.from(out);
}

function utf8Decode(b: Uint8Array): string {
  let s = "";
  let i = 0;
  const at = (k: number) => (b[k] ?? 0) & 63;
  while (i < b.length) {
    const c = b[i++];
    let cp: number;
    if (c < 0x80) cp = c;
    else if (c >= 0xf0) {
      cp = ((c & 7) << 18) | (at(i) << 12) | (at(i + 1) << 6) | at(i + 2);
      i += 3;
    } else if (c >= 0xe0) {
      cp = ((c & 15) << 12) | (at(i) << 6) | at(i + 1);
      i += 2;
    } else if (c >= 0xc0) {
      cp = ((c & 31) << 6) | at(i);
      i += 1;
    } else cp = 0xfffd;
    s += String.fromCodePoint(cp <= 0x10ffff ? cp : 0xfffd);
  }
  return s;
}

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const B64Index = new Map<string, number>([...B64].map((ch, i) => [ch, i]));

function toBase64(b: Uint8Array): string {
  let s = "";
  for (let i = 0; i < b.length; i += 3) {
    const n = (b[i] << 16) | ((b[i + 1] ?? 0) << 8) | (b[i + 2] ?? 0);
    s += B64[(n >> 18) & 63] + B64[(n >> 12) & 63];
    s += i + 1 < b.length ? B64[(n >> 6) & 63] : "=";
    s += i + 2 < b.length ? B64[n & 63] : "=";
  }
  return s;
}

function fromBase64(s: string): Uint8Array {
  const clean = s.replace(/[^A-Za-z0-9+/]/g, "");
  const out: number[] = [];
  for (let i = 0; i < clean.length; i += 4) {
    const a = B64Index.get(clean[i]) ?? 0;
    const b = B64Index.get(clean[i + 1]) ?? 0;
    const c = B64Index.get(clean[i + 2]);
    const d = B64Index.get(clean[i + 3]);
    out.push((a << 2) | (b >> 4));
    if (c != null) out.push(((b & 15) << 4) | (c >> 2));
    if (c != null && d != null) out.push(((c & 3) << 6) | d);
  }
  return Uint8Array.from(out);
}

// ------------------------------------------------------------------ 本体

let managerSingleton: BleManagerType | null | undefined;

/** BleManager を 1 つだけ作る。ネイティブのモジュールが無い (Expo Go・web) ときは null。 */
function manager(): BleManagerType | null {
  if (managerSingleton !== undefined) return managerSingleton;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { BleManager } = require("react-native-ble-plx") as typeof import("react-native-ble-plx");
    managerSingleton = new BleManager();
  } catch (e) {
    console.warn("[AIDY] BLE が使えません:", e);
    managerSingleton = null;
  }
  return managerSingleton;
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

function withTimeout<T>(p: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new BleLinkError(message)), ms);
    p.then(
      (v) => {
        clearTimeout(timer);
        resolve(v);
      },
      (e) => {
        clearTimeout(timer);
        reject(e);
      },
    );
  });
}

class BleLinkImpl {
  private device: Device | null = null;
  private mtu = DefaultMtu;
  private rxBuf: number[] = [];
  private nextId = 1;
  private waiting: { id: number; resolve: (r: BleReply) => void; reject: (e: Error) => void } | null = null;
  private subs: Subscription[] = [];
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private chain: Promise<unknown> = Promise.resolve();
  /** 最後につながった本体の広告名 (画面に出す用)。 */
  connectedName = "";

  /** この端末で BLE が使えるか (開発ビルドに入っているか)。 */
  get available(): boolean {
    return manager() != null;
  }

  /** 依頼を 1 件送って返事を待つ。前の依頼が終わるまで待ってから送る。失敗は BleLinkError。 */
  request(method: "GET" | "POST", path: string, token: string, body: unknown, wantName: string): Promise<BleReply> {
    const run = async () => {
      this.cancelIdle();
      try {
        await this.ensureConnected(wantName);
        return await this.exchange(method, path, token, body);
      } finally {
        this.scheduleIdle();
      }
    };
    const p = this.chain.then(run, run);
    this.chain = p.catch(() => undefined);
    return p;
  }

  /** 名前の指定を変えたときなど、覚えている本体を忘れて切る。 */
  async forget(): Promise<void> {
    Prefs.putString(DeviceIdKey, "");
    Prefs.putString(DeviceIdNameKey, "");
    await this.disconnect();
  }

  async disconnect(): Promise<void> {
    this.cancelIdle();
    const d = this.device;
    this.cleanup(new BleLinkError("本体との Bluetooth が切れました"));
    const m = manager();
    if (d && m) {
      try {
        await m.cancelDeviceConnection(d.id);
      } catch {
        // もう切れている
      }
    }
  }

  // ---------------------------------------------------------------- 接続

  private async ensureConnected(wantName: string): Promise<void> {
    const m = manager();
    if (!m) throw new BleLinkError("このアプリでは Bluetooth が使えません (開発ビルドが必要です)");
    if (this.device) {
      const still = await m.isDeviceConnected(this.device.id).catch(() => false);
      if (still) return;
      this.cleanup(new BleLinkError("本体との Bluetooth が切れました"));
    }

    await this.ensurePermission();
    await this.ensurePoweredOn(m);

    // 前につながった本体 (同じ名前の指定のとき) には、探さずに直接つなぐ
    const savedId = Prefs.getString(DeviceIdKey, "");
    if (savedId.length > 0 && Prefs.getString(DeviceIdNameKey, "") === wantName) {
      try {
        await this.connect(m, savedId);
        return;
      } catch (e) {
        console.warn("[AIDY] 覚えている本体に直接つながらない。探し直す:", e instanceof Error ? e.message : e);
        await m.cancelDeviceConnection(savedId).catch(() => undefined);
      }
    }

    const found = await this.scan(m, wantName);
    await this.connect(m, found.id);
    Prefs.putString(DeviceIdKey, found.id);
    Prefs.putString(DeviceIdNameKey, wantName);
  }

  private async ensurePermission(): Promise<void> {
    if (Platform.OS !== "android") return; // iOS は初めて Bluetooth を使うときに OS が自動で聞く
    const api = typeof Platform.Version === "number" ? Platform.Version : parseInt(String(Platform.Version), 10);
    const perms =
      api >= 31
        ? [PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN, PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT]
        : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION];
    const r = await PermissionsAndroid.requestMultiple(perms);
    if (!perms.every((p) => r[p] === PermissionsAndroid.RESULTS.GRANTED))
      throw new BleLinkError("「付近のデバイス」の許可が必要です (端末の設定から許可してください)");
  }

  private async ensurePoweredOn(m: BleManagerType): Promise<void> {
    // 起動直後は Unknown / Resetting のことがあるので少し待つ
    for (let i = 0; i < 15; i++) {
      const s = await m.state();
      if (s === "PoweredOn") return;
      if (s === "PoweredOff") throw new BleLinkError("Bluetooth がオフになっています。オンにしてください");
      if (s === "Unauthorized") throw new BleLinkError("Bluetooth の使用が許可されていません (設定 > AIDY > Bluetooth)");
      if (s === "Unsupported") throw new BleLinkError("この端末は Bluetooth Low Energy に対応していません");
      await sleep(200);
    }
    throw new BleLinkError("Bluetooth の準備ができませんでした");
  }

  /** 近くの Aidy 本体を探す。名前の指定があれば同じ名前だけ、無ければサービス UUID か "AIdy" で始まる名前の最初の 1 台。 */
  private scan(m: BleManagerType, wantName: string): Promise<Device> {
    return new Promise<Device>((resolve, reject) => {
      let done = false;
      const finish = (d: Device | null, err?: Error) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        void m.stopDeviceScan().catch(() => undefined);
        if (d) resolve(d);
        else reject(err ?? new BleLinkError("Bluetooth で近くに Aidy 本体が見つかりません"));
      };
      const timer = setTimeout(() => finish(null), ScanTimeoutMs);
      // サービス UUID で絞ると、スキャン応答にだけ UUID を入れている本体を取りこぼす端末があるので、絞らずに自分で選ぶ
      m.startDeviceScan(null, { allowDuplicates: false }, (error, d) => {
        if (error) {
          finish(null, new BleLinkError(`Bluetooth で探せませんでした (${error.message})`));
          return;
        }
        if (!d) return;
        const names = [d.localName, d.name].filter((n): n is string => !!n);
        const hasService = (d.serviceUUIDs ?? []).some((u) => u.toLowerCase() === ServiceUuid);
        const ok = wantName.length > 0 ? names.includes(wantName) : hasService || names.some((n) => n.startsWith("AIdy"));
        if (ok) finish(d);
      }).catch((e: unknown) => finish(null, new BleLinkError(`Bluetooth で探せませんでした (${e instanceof Error ? e.message : String(e)})`)));
    });
  }

  private async connect(m: BleManagerType, id: string): Promise<void> {
    let d: Device;
    try {
      d = await withTimeout(
        m.connectToDevice(id, { timeout: ConnectTimeoutMs, requestMTU: WantMtu }),
        ConnectTimeoutMs + 1000,
        "Aidy本体に Bluetooth でつながりません (時間切れ)",
      );
    } catch (e) {
      if (e instanceof BleLinkError) throw e;
      throw new BleLinkError(`Aidy本体に Bluetooth でつながりません (${e instanceof Error ? e.message : String(e)})`);
    }
    try {
      d = await d.discoverAllServicesAndCharacteristics();
      // Android は connectToDevice の requestMTU で広がるが、念のためもう一度 (iOS は OS が自動で決める)
      if (Platform.OS === "android" && d.mtu < WantMtu) d = await d.requestMTU(WantMtu).catch(() => d);
      const services = await d.services();
      const svc = services.find((s) => s.uuid.toLowerCase() === ServiceUuid);
      const label = d.localName ?? d.name ?? "この機械";
      if (!svc) throw new BleLinkError(`${label} は Aidy の通信に対応していません`);
      const chars = await svc.characteristics();
      const rx = chars.find((c) => c.uuid.toLowerCase() === RxUuid);
      const tx = chars.find((c) => c.uuid.toLowerCase() === TxUuid);
      if (!rx || !rx.isWritableWithResponse) throw new BleLinkError("本体に受け口 (RX) がありません");
      if (!tx || !tx.isNotifiable) throw new BleLinkError("本体に送り口 (TX) がありません");

      this.device = d;
      this.mtu = d.mtu > 0 ? d.mtu : DefaultMtu;
      this.rxBuf = [];
      this.connectedName = d.localName ?? d.name ?? "";
      this.subs.push(
        m.onDeviceDisconnected(d.id, () => {
          if (this.device?.id === d.id) this.cleanup(new BleLinkError("本体との Bluetooth が切れました"));
        }),
      );
      await this.startNotify(m, d);
    } catch (e) {
      this.cleanup(e instanceof Error ? e : new BleLinkError(String(e)));
      await m.cancelDeviceConnection(d.id).catch(() => undefined);
      if (e instanceof BleLinkError) throw e;
      throw new BleLinkError(`Aidy本体に Bluetooth でつながりません (${e instanceof Error ? e.message : String(e)})`);
    }
  }

  /** TX の通知を受け始める (ble-plx が CCCD に 0x0001 を書く)。書けたかどうかは最初の通知か失敗の知らせで分かる。 */
  private async startNotify(m: BleManagerType, d: Device): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const sub = m.monitorCharacteristicForDevice(d.id, ServiceUuid, TxUuid, (error, ch) => {
        if (error) {
          if (!settled) {
            settled = true;
            reject(new BleLinkError("本体からの通知を受け取れるようにできませんでした"));
          } else if (this.device?.id === d.id) this.cleanup(new BleLinkError("本体との Bluetooth が切れました"));
          return;
        }
        if (ch?.value) this.onChunk(fromBase64(ch.value));
      });
      this.subs.push(sub);
      // 通知の登録の成功は知らせが来ないので、少し待ってエラーが無ければ成功とみなす
      setTimeout(() => {
        if (!settled) {
          settled = true;
          resolve();
        }
      }, 300);
    });
  }

  // ---------------------------------------------------------------- 送受信

  private async exchange(method: "GET" | "POST", path: string, token: string, body: unknown): Promise<BleReply> {
    const d = this.device;
    const m = manager();
    if (!d || !m) throw new BleLinkError("本体との Bluetooth が切れました");
    const id = this.nextId++;
    if (this.nextId > 1_000_000) this.nextId = 1;
    const req: Record<string, unknown> = { id, m: method, p: path, t: token };
    if (method === "POST") req.b = body ?? {};
    const bytes = utf8Encode(JSON.stringify(req) + "\n");

    const reply = new Promise<BleReply>((resolve, reject) => {
      this.waiting = { id, resolve, reject };
    });
    reply.catch(() => undefined); // 書き込みで失敗したときの未処理の警告を出さない
    const timer = setTimeout(() => this.failWaiting(new BleLinkError("本体から返事がありません (時間切れ)")), ReplyTimeoutMs);
    try {
      const chunk = Math.max(20, this.mtu - 3);
      for (let i = 0; i < bytes.length; i += chunk) {
        await m.writeCharacteristicWithResponseForDevice(d.id, ServiceUuid, RxUuid, toBase64(bytes.subarray(i, i + chunk)));
      }
    } catch (e) {
      clearTimeout(timer);
      this.waiting = null;
      throw new BleLinkError(`本体に送れませんでした (${e instanceof Error ? e.message : String(e)})`);
    }
    try {
      return await reply;
    } finally {
      clearTimeout(timer);
    }
  }

  private onChunk(b: Uint8Array): void {
    for (const x of b) {
      if (x !== 0x0a) {
        this.rxBuf.push(x);
        continue;
      }
      const line = Uint8Array.from(this.rxBuf);
      this.rxBuf = [];
      if (line.length > 0) this.onLine(line);
    }
    if (this.rxBuf.length > MaxLine) this.rxBuf = [];
  }

  private onLine(line: Uint8Array): void {
    let msg: { id?: unknown; s?: unknown; b?: unknown };
    try {
      msg = JSON.parse(utf8Decode(line));
    } catch {
      this.failWaiting(new BleLinkError("本体からの返事を読み取れませんでした"));
      return;
    }
    const w = this.waiting;
    if (!w) return;
    const id = Number(msg.id ?? 0);
    // id=0 は本体が依頼を読めなかったとき (400)。それ以外は id が合うものだけ受け取る
    if (id !== w.id && id !== 0) return;
    this.waiting = null;
    const status = Number(msg.s);
    w.resolve({ status: Number.isFinite(status) ? status : 500, body: msg.b ?? null });
  }

  private failWaiting(e: Error): void {
    const w = this.waiting;
    this.waiting = null;
    w?.reject(e);
  }

  private cleanup(e: Error): void {
    for (const s of this.subs) {
      try {
        s.remove();
      } catch {
        // 気にしない
      }
    }
    this.subs = [];
    this.device = null;
    this.rxBuf = [];
    this.failWaiting(e);
  }

  private scheduleIdle(): void {
    this.cancelIdle();
    this.idleTimer = setTimeout(() => void this.disconnect(), IdleCloseMs);
  }

  private cancelIdle(): void {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = null;
  }
}

export const BleLink = new BleLinkImpl();
