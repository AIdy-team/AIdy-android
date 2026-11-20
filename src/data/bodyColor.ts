import { Prefs } from "./prefs";

const Opaque = 0xff000000;

/** 0xRRGGBB に不透明のアルファを付けて 0xffRRGGBB (符号なし) にする。 */
function opaque(c: number): number {
  return ((c & 0xffffff) | Opaque) >>> 0;
}

/**
 * 色のパターン: 本体の色 + パーツの色。Kotlin版 BodyColor.kt の ColorPattern のポート。
 * パーツの色は「取り付け口ごとの色 (slots)」→「パーツ全部の色 (parts)」→ 標準のグレーの順に決まる。
 * 色は 0xffRRGGBB。
 */
export class ColorPattern {
  readonly body: number;
  readonly parts: number | null;
  readonly slots: ReadonlyMap<string, number>;

  constructor(body: number, parts: number | null = null, slots: ReadonlyMap<string, number> = new Map()) {
    this.body = opaque(body);
    this.parts = parts == null ? null : opaque(parts);
    this.slots = slots;
  }

  static readonly Default = new ColorPattern(0xffa0a0a0);

  /** その取り付け口のパーツの色。 */
  partColor(slotId: string): number {
    return this.slots.get(slotId) ?? this.parts ?? BodyColor.PartDefault;
  }

  withBody(c: number): ColorPattern {
    return new ColorPattern(c, this.parts, this.slots);
  }

  /** パーツ全部をこの色にする (取り付け口ごとの色は消す)。 */
  withParts(c: number): ColorPattern {
    return new ColorPattern(this.body, c, new Map());
  }

  withSlot(slotId: string, c: number): ColorPattern {
    const m = new Map(this.slots);
    m.set(slotId, opaque(c));
    return new ColorPattern(this.body, this.parts, m);
  }

  /** 文字にする: "#本体,*#パーツ,top_1#色,..."。マーカーの保存形式に入るので ; = | は使わない。 */
  format(): string {
    let s = BodyColor.hex(this.body);
    if (this.parts != null) s += ",*" + BodyColor.hex(this.parts);
    for (const id of Array.from(this.slots.keys()).sort()) s += "," + id + BodyColor.hex(this.slots.get(id)!);
    return s;
  }

  equals(o: ColorPattern | null | undefined): boolean {
    return o != null && o.format() === this.format();
  }

  /** format の逆。昔の "#RRGGBB" (本体だけ) も読める。読めなければ null。 */
  static parse(s: string): ColorPattern | null {
    const tokens = s.trim().split(",").filter((t) => t.trim().length > 0);
    if (tokens.length === 0) return null;
    const body = BodyColor.parse(tokens[0]);
    if (body == null) return null;
    let parts: number | null = null;
    const slots = new Map<string, number>();
    for (const t of tokens.slice(1)) {
      const i = t.indexOf("#");
      if (i < 0) continue;
      const c = BodyColor.parse(t.substring(i));
      if (c == null) continue;
      const key = t.substring(0, i);
      if (key === "*") parts = c;
      else if (key.length > 0) slots.set(key, c);
    }
    return new ColorPattern(body, parts, slots);
  }
}

const PatternKey = "aidy.color.pattern";
const PatternPresetsKey = "aidy.color.patternPresets";
const OldCurrentKey = "aidy.body.color";
const OldPresetsKey = "aidy.body.colorPresets";

/**
 * いまの色のパターン (ホームで選ぶ) と、パターンのプリセット (用意したもの + ユーザーが保存したもの)。端末に保存する。
 */
class BodyColorStore {
  /** 3D モデルのもとの色 (グレー)。 */
  readonly Default = 0xffa0a0a0;
  /** パーツのもとの色。 */
  readonly PartDefault = 0xffa0a0a0;
  readonly MaxPresets = 12;

  /** 色を選ぶときの見本の色。 */
  readonly BuiltIn: number[] = [
    0xffa0a0a0,
    0xfff5f5f0, // ホワイト
    0xff3a3d44, // チャコール
    0xff7cc4f0, // スカイ
    0xfff4a7c0, // ピンク
    0xff8fd6b4, // ミント
    0xfff6d36b, // イエロー
    0xfff08a5d, // オレンジ
    0xffa78bfa, // ラベンダー
    0xff2f4a8a, // ネイビー
    0xffe5484d, // レッド
    0xff8b5e3c, // ブラウン
  ];

  /** 最初から選べるパターン (消せない)。 */
  get BuiltInPatterns(): ColorPattern[] {
    return (this.builtInPatterns ??= [
      ColorPattern.Default,
      new ColorPattern(0xfff5f5f0, 0xff2f4a8a), // 白 × ネイビー
      new ColorPattern(0xff3a3d44, 0xfff08a5d), // チャコール × オレンジ
      new ColorPattern(0xff7cc4f0, 0xfff5f5f0), // スカイ × 白
      new ColorPattern(0xfff4a7c0, 0xfff5f5f0), // ピンク × 白
      new ColorPattern(0xff8fd6b4, 0xfff6d36b), // ミント × イエロー
      new ColorPattern(0xfff6d36b, 0xff3a3d44), // イエロー × チャコール
      new ColorPattern(0xffa78bfa, 0xfff4a7c0), // ラベンダー × ピンク
      new ColorPattern(0xff2f4a8a, 0xff7cc4f0), // ネイビー × スカイ
      new ColorPattern(0xfff5f5f0, 0xffe5484d), // 白 × レッド
    ]);
  }
  private builtInPatterns: ColorPattern[] | null = null;

  /** 変わったら増える (React の再描画用)。 */
  version = 0;

  /** パターンが変わったとき (3D の材質の更新)。 */
  readonly changed: Array<(p: ColorPattern) => void> = [];

  private cur: ColorPattern | null = null;
  private presets: ColorPattern[] | null = null;

  /** いまのパターン。 */
  get pattern(): ColorPattern {
    if (!this.cur) {
      this.cur =
        ColorPattern.parse(Prefs.getString(PatternKey, "")) ??
        new ColorPattern(Prefs.getInt(OldCurrentKey, this.Default)); // 本体の色だけだった頃の保存
    }
    return this.cur;
  }
  set pattern(value: ColorPattern) {
    if (value.equals(this.pattern)) return;
    this.cur = value;
    Prefs.putString(PatternKey, value.format());
    this.version++;
    for (const l of this.changed.slice()) l(value);
  }

  /** いまの本体の色。 */
  get current(): number {
    return this.pattern.body;
  }

  /** 保存したパターン (新しい順)。 */
  get saved(): ColorPattern[] {
    return this.loadPresets().slice();
  }

  /** プリセットに保存する。同じものがあれば先頭へ移す。いっぱいなら一番古いものを消す。 */
  savePreset(p: ColorPattern): void {
    const list = this.loadPresets().filter((x) => !x.equals(p));
    list.unshift(p);
    while (list.length > this.MaxPresets) list.pop();
    this.presets = list;
    this.storePresets();
  }

  deletePreset(p: ColorPattern): void {
    const list = this.loadPresets();
    const next = list.filter((x) => !x.equals(p));
    if (next.length === list.length) return;
    this.presets = next;
    this.storePresets();
  }

  private loadPresets(): ColorPattern[] {
    if (this.presets) return this.presets;
    const stored = Prefs.getString(PatternPresetsKey, "");
    let list: ColorPattern[];
    if (stored.length > 0) {
      list = stored.split(";").map((t) => ColorPattern.parse(t)).filter((p): p is ColorPattern => p != null);
    } else {
      // 本体の色だけだった頃の保存 ("#RRGGBB,#RRGGBB,...")
      list = Prefs.getString(OldPresetsKey, "")
        .split(",")
        .map((t) => this.parse(t))
        .filter((c): c is number => c != null)
        .map((c) => new ColorPattern(c));
      if (list.length > 0) {
        Prefs.putString(PatternPresetsKey, list.map((p) => p.format()).join(";"));
        Prefs.putString(OldPresetsKey, "");
      }
    }
    this.presets = list;
    return list;
  }

  private storePresets(): void {
    Prefs.putString(PatternPresetsKey, this.loadPresets().map((p) => p.format()).join(";"));
    this.version++;
  }

  /** "#RRGGBB"。 */
  hex(c: number): string {
    return "#" + (c & 0xffffff).toString(16).toUpperCase().padStart(6, "0");
  }

  /** "#RRGGBB" / "RRGGBB" → 0xffRRGGBB。読めなければ null。 */
  parse(s: string): number | null {
    let t = s.trim();
    if (t.startsWith("#")) t = t.substring(1);
    if (t.length !== 6 || !/^[0-9a-fA-F]{6}$/.test(t)) return null;
    return opaque(parseInt(t, 16));
  }

  /** 0xffRRGGBB → CSS の "#rrggbb"。 */
  css(c: number): string {
    return "#" + (c & 0xffffff).toString(16).padStart(6, "0");
  }
}

export const BodyColor = new BodyColorStore();
