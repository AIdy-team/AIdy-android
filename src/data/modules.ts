import { V3, Q } from "../three/math";
import { IconId } from "../components/iconIds";
import { Theme } from "../components/theme";
import { Prefs } from "./prefs";
import { bodyLayoutJson } from "../assets/generated/manifest";

/** 本体に取り付けるモジュールの種類。Kotlin版 Modules.kt の ModuleKind と同じ。 */
export type ModuleKind = "Ear" | "Tail" | "Tail2" | "Legs" | "Face" | "Hand" | "HandLight";

/** モデルファイルの向き。取り付け面ローカル (+Y=外向き, +Z=正面) へどう変換するか。MountAxis と同じ。 */
export type MountAxis = "ZUpFrontMinusY" | "OutwardMinusY" | "MagnetPlusX" | "SlotTabPlusY" | "TopPadZUp";

type ModuleEvent = (slotId: string, removed: ModuleKind | null, added: ModuleKind | null) => void;

/** モジュールの名前・アイコン・色・モデルファイル・取り付けられる面。 */
export const ModuleCatalog = {
  All: ["Ear", "Tail", "Tail2", "Legs", "Face", "Hand", "HandLight"] as ModuleKind[],

  name(k: ModuleKind): string {
    switch (k) {
      case "Ear": return "耳";
      case "Tail": return "尻尾";
      case "Tail2": return "尻尾2";
      case "Legs": return "足";
      case "Hand": return "手";
      case "HandLight": return "ライト手";
      case "Face": return "顔";
    }
  },

  icon(k: ModuleKind): IconId {
    switch (k) {
      case "Ear": return "Ear";
      case "Tail":
      case "Tail2": return "Tail";
      case "Legs": return "Leg";
      case "Hand": return "Hand";
      case "HandLight": return "Flashlight";
      case "Face": return "Aidy";
    }
  },

  tint(k: ModuleKind): string {
    switch (k) {
      case "Ear": return Theme.hex(0xf97316);
      case "Tail": return Theme.hex(0x8b5cf6);
      case "Tail2": return Theme.hex(0x6366f1);
      case "Legs": return Theme.hex(0x10b981);
      case "Hand": return Theme.hex(0xec4899);
      case "HandLight": return Theme.hex(0xeab308);
      case "Face": return Theme.Primary;
    }
  },

  /** モジュールをそのスロットに付けられるか (空きかどうかは見ない)。 */
  fits(k: ModuleKind, s: BodySlot): boolean {
    switch (k) {
      case "Face": return s.isMonitor;
      case "Ear": return !s.isMonitor && (s.face === "top" || s.face === "left" || s.face === "right");
      case "Tail":
      case "Tail2": return !s.isMonitor && (s.face === "back" || s.face === "top");
      case "Legs": return !s.isMonitor && s.face === "bottom";
      case "Hand":
      case "HandLight": return !s.isMonitor && (s.face === "left" || s.face === "right");
    }
  },

  isTail(k: ModuleKind): boolean {
    return k === "Tail" || k === "Tail2";
  },

  /** モデルファイル (assets/models/ 以下、manifestのキー)。顔はモデルを持たない (本体v2_顔ありに切り替え)。 */
  modelKey(k: ModuleKind): string | null {
    switch (k) {
      case "Ear": return "ear";
      case "Tail": return "tail";
      case "Tail2": return "tail2";
      case "Hand": return "hand";
      case "HandLight": return "hand_light";
      case "Legs": return "legs";
      case "Face": return null;
    }
  },

  axis(k: ModuleKind): MountAxis {
    switch (k) {
      case "Ear": return "ZUpFrontMinusY";
      case "Tail":
      case "Tail2": return "SlotTabPlusY";
      case "Legs": return "TopPadZUp";
      default: return "MagnetPlusX";
    }
  },

  /** その面に付けられるモジュール。 */
  forFace(face: string): ModuleKind[] {
    return ModuleCatalog.All.filter((k) => BodyLayout.onFace(face).some((s) => ModuleCatalog.fits(k, s)));
  },
};

/** 本体の取り付け口 (接続スロット13本 + 顔モニター開口)。座標は本体の一辺=1 に正規化。 */
export class BodySlot {
  constructor(
    public id: string,
    public face: string,
    public label: string,
    public position: V3,
    public normal: V3,
    public longAxis: V3,
    public isMonitor: boolean,
    public widthMm: number,
    public heightMm: number,
  ) {}

  /** 取り付け面のローカル座標系 (+Y=外向き法線, +Z=正面 or 上)。 */
  get mountRotation(): Q {
    const forward = Math.abs(this.normal.z) > 0.5 ? V3.Up : V3.Forward;
    return Q.look(forward, this.normal);
  }
}

/** assets/models/body_v2_layout.json (本体v2.3mf から測った寸法) から取り付け口を読み込む。 */
export const BodyLayout = {
  Faces: ["top", "front", "back", "left", "right", "bottom"] as const,

  _slots: null as BodySlot[] | null,

  get slots(): BodySlot[] {
    if (!this._slots) this._slots = loadBodyLayout();
    return this._slots;
  },

  get(id: string): BodySlot | undefined {
    return this.slots.find((s) => s.id === id);
  },

  onFace(face: string): BodySlot[] {
    return this.slots.filter((s) => s.face === face);
  },

  faceLabel(face: string): string {
    switch (face) {
      case "top": return "上面";
      case "front": return "正面";
      case "back": return "背面";
      case "left": return "左面";
      case "right": return "右面";
      default: return "底面";
    }
  },

  /** 面の外向き法線 (この座標。左面 = 正面から見て左 = -X)。 */
  faceNormal(face: string): V3 {
    switch (face) {
      case "top": return V3.Up;
      case "front": return V3.Forward;
      case "back": return V3.Forward.negate();
      case "left": return V3.Right.negate();
      case "right": return V3.Right;
      default: return V3.Up.negate();
    }
  },

  /** その面を外から見たときの 2D 座標 (右, 上)。上面/底面は正面が画面下/上になる向き。 */
  project(face: string, p: V3): [number, number] {
    switch (face) {
      case "front": return [p.x, p.y];
      case "back": return [-p.x, p.y];
      case "left": return [p.z, p.y];
      case "right": return [-p.z, p.y];
      case "top": return [p.x, -p.z];
      default: return [p.x, p.z];
    }
  },
};

function loadBodyLayout(): BodySlot[] {
  const list: BodySlot[] = [];
  const json = bodyLayoutJson as { features?: Array<Record<string, unknown>> };
  const features = json.features ?? [];
  const v = (o: { x?: number; y?: number; z?: number } | undefined) =>
    o ? new V3(o.x ?? 0, o.y ?? 0, o.z ?? 0).mirrorX() : V3.Zero;
  const perFace = new Map<string, number>();
  for (const f of features) {
    const id = String(f.id);
    const monitor = id === "monitor";
    if (f.type !== "slot" && !monitor) continue; // カメラ穴・マイク穴は取り付け口ではない
    const face = String(f.face);
    const n = (perFace.get(face) ?? 0) + 1;
    perFace.set(face, n);
    list.push(
      new BodySlot(
        id,
        face,
        monitor ? "モニター" : `スロット${n}`,
        v(f.position as any),
        v(f.normal as any),
        v(f.longAxis as any),
        monitor,
        Number(f.widthMm ?? 0),
        Number(f.heightMm ?? 0),
      ),
    );
  }
  return list;
}

/**
 * 本体に付いているモジュールの構成 (スロット ID → モジュール)。ホーム・付け替えプレビュー・AR の
 * すべての 3D モデルがこれを表示する。端末に保存される。
 */
class ModuleConfigStore {
  private static readonly PrefKey = "aidy.modules.v1";
  private slots: Map<string, ModuleKind> | null = null;

  /** (スロット, 外れたモジュール, 付いたモジュール)。差し替えは両方入る。 */
  readonly changed: ModuleEvent[] = [];

  /** React の再描画用 (変わるたびに増える)。 */
  version = 0;

  get current(): Map<string, ModuleKind> {
    return this.load();
  }

  at(slotId: string): ModuleKind | null {
    return this.load().get(slotId) ?? null;
  }

  get faceOn(): boolean {
    return this.has("Face");
  }
  get hasLegs(): boolean {
    return this.has("Legs");
  }

  has(kind: ModuleKind): boolean {
    for (const v of this.load().values()) if (v === kind) return true;
    return false;
  }

  /** そのスロットに付けられるか (空きであること)。 */
  canAttach(kind: ModuleKind, slotId: string): boolean {
    const s = BodyLayout.get(slotId);
    if (!s) return false;
    return ModuleCatalog.fits(kind, s) && this.at(slotId) == null;
  }

  attach(kind: ModuleKind, slotId: string): boolean {
    if (!this.canAttach(kind, slotId)) return false;
    this.load().set(slotId, kind);
    this.save();
    this.fire(slotId, null, kind);
    return true;
  }

  detach(slotId: string): void {
    const old = this.at(slotId);
    if (old == null) return;
    this.load().delete(slotId);
    this.save();
    this.fire(slotId, old, null);
  }

  /** 差し替え (old → kind)。空きなら普通に取り付け。 */
  replace(kind: ModuleKind, slotId: string): boolean {
    const s = BodyLayout.get(slotId);
    if (!s || !ModuleCatalog.fits(kind, s)) return false;
    const old = this.at(slotId);
    if (old === kind) return false;
    this.load().set(slotId, kind);
    this.save();
    this.fire(slotId, old, kind);
    return true;
  }

  resetToDefault(): void {
    for (const id of Array.from(this.load().keys())) this.detach(id);
    for (const [k, v] of ModuleConfigDefault) this.attach(v, k);
  }

  private fire(slot: string, removed: ModuleKind | null, added: ModuleKind | null): void {
    this.version++;
    for (const l of this.changed.slice()) l(slot, removed, added);
  }

  private load(): Map<string, ModuleKind> {
    if (this.slots) return this.slots;
    const map = new Map<string, ModuleKind>();
    this.slots = map;
    const saved = Prefs.getString(ModuleConfigStore.PrefKey, "");
    if (saved.length === 0) {
      this.resetToDefault();
      return map;
    }
    for (const [k, v] of parseModuleString(saved)) map.set(k, v);
    return map;
  }

  private save(): void {
    Prefs.putString(ModuleConfigStore.PrefKey, formatModuleMap(this.load()));
  }

  /** 構成の一覧表示 (耳×2・尻尾…)。 */
  summary(slots: Map<string, ModuleKind>): string {
    const counts = new Map<ModuleKind, number>();
    for (const k of slots.values()) counts.set(k, (counts.get(k) ?? 0) + 1);
    const parts: string[] = [];
    for (const k of ModuleCatalog.All) {
      const n = counts.get(k);
      if (n == null) continue;
      parts.push(n > 1 ? `${ModuleCatalog.name(k)}×${n}` : ModuleCatalog.name(k));
    }
    return parts.length > 0 ? parts.join("・") : "なし (本体だけ)";
  }
}

/** "top_1:Ear,back_1:Tail" (Unity 版の PlayerPrefs と同じ形式)。 */
export function formatModuleMap(m: Map<string, ModuleKind>): string {
  return Array.from(m.entries()).map(([k, v]) => `${k}:${v}`).join(",");
}

export function parseModuleString(text: string): Map<string, ModuleKind> {
  const map = new Map<string, ModuleKind>();
  for (const pair of text.split(",")) {
    const kv = pair.split(":");
    if (kv.length !== 2) continue;
    const k = kv[1] as ModuleKind;
    if (!ModuleCatalog.All.includes(k)) continue;
    if (BodyLayout.get(kv[0])) map.set(kv[0], k);
  }
  return map;
}

/** 標準構成 (初期状態・名刺マーカーの Aidy): 上面に耳2つ・背面に尻尾・底面に足2組・正面に顔。 */
export const ModuleConfigDefault: ReadonlyMap<string, ModuleKind> = new Map<string, ModuleKind>([
  ["top_1", "Ear"],
  ["top_3", "Ear"],
  ["back_1", "Tail"],
  ["bottom_1", "Legs"],
  ["bottom_3", "Legs"],
  ["monitor", "Face"],
]);

export const ModuleConfig = new ModuleConfigStore();
