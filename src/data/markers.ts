import { Directory, File, Paths } from "expo-file-system";
import * as MediaLibrary from "expo-media-library";
import { Prefs } from "./prefs";
import { ModuleConfig, ModuleConfigDefault, ModuleKind, formatModuleMap, parseModuleString } from "./modules";
import { Dict4x4_50 } from "../ar/arucoDict";
import { BodyColor, ColorPattern } from "./bodyColor";

/**
 * AR に使う ArUco マーカー (DICT_4X4_50)。ID0 = いまの着せ替え、ID1 = 名刺 (標準構成)、
 * ID2〜 = アプリで作った着せ替えマーカー (MarkerStore)。
 * 寸法は黒い正方形 (外枠を含む) の一辺。印刷した大きさと違うと、モデルの距離・大きさがずれる。
 */
export const AidyMarkers = {
  /** アプリで着せ替えたいまの Aidy を出すマーカー。 */
  MyId: 0,
  /** 名刺などで配るマーカー。標準構成の Aidy を出す。 */
  CardId: 1,

  MySizeM: 0.08,
  CardSizeM: 0.03,

  /** いま認識する ID (固定の 2 つ + 作った着せ替えマーカー)。 */
  activeIds(): Set<number> {
    const ids = new Set<number>([this.MyId, this.CardId]);
    for (const id of MarkerStore.ids) ids.add(id);
    return ids;
  },

  size(id: number): number {
    return id === this.CardId ? this.CardSizeM : this.MySizeM;
  },
  sizeMm(id: number): number {
    return Math.round(this.size(id) * 1000);
  },

  name(id: number): string {
    if (id === this.MyId) return "あなたのAidy";
    if (id === this.CardId) return "名刺のAidy (標準)";
    return `着せ替えAidy #${id}`;
  },

  /** そのマーカーで出す色のパターン。いまの着せ替えマーカーはいまの色、名刺は標準のグレー。 */
  color(id: number): ColorPattern {
    if (id === this.MyId) return BodyColor.pattern;
    if (id === this.CardId) return ColorPattern.Default;
    return MarkerStore.color(id) ?? ColorPattern.Default;
  },

  /** そのマーカーで出す構成。null = いまの着せ替え (ModuleConfig)。 */
  config(id: number): Map<string, ModuleKind> | null {
    if (id === this.MyId) return null;
    if (id === this.CardId) return new Map(ModuleConfigDefault);
    return MarkerStore.get(id);
  },
};

function mapsEqual(a: Map<string, ModuleKind>, b: Map<string, ModuleKind>): boolean {
  if (a.size !== b.size) return false;
  for (const [k, v] of a) if (b.get(k) !== v) return false;
  return true;
}

/**
 * アプリで作った着せ替えマーカー (ID → そのときのモジュール構成)。端末に保存される。
 * マーカーと構成の対応はこの端末にしかないので、ほかの端末でかざしても出ない。
 */
interface MarkerEntry {
  slots: Map<string, ModuleKind>;
  color: ColorPattern | null;
}

class MarkerStoreImpl {
  private static readonly PrefKey = "aidy.markers.v1";
  private static readonly FirstId = 2;
  private saved: Map<number, MarkerEntry> | null = null;

  /** 作った・消した・色を変えたとき。 */
  readonly changed: Array<() => void> = [];

  get ids(): number[] {
    return Array.from(this.load().keys()).sort((a, b) => a - b);
  }

  get(id: number): Map<string, ModuleKind> | null {
    return this.load().get(id)?.slots ?? null;
  }

  /** そのマーカーの色のパターン (作ったときの色)。古いマーカーなど色がなければ null。 */
  color(id: number): ColorPattern | null {
    return this.load().get(id)?.color ?? null;
  }

  /** 同じ構成・同じ色のマーカーがあればその ID。 */
  find(slots: Map<string, ModuleKind>, color: ColorPattern): number | null {
    for (const [id, e] of this.load()) if (mapsEqual(e.slots, slots) && (e.color ?? ColorPattern.Default).equals(color)) return id;
    return null;
  }

  /** 構成と色を空いている ID に保存する。空きがなければ null。 */
  create(slots: Map<string, ModuleKind>, color: ColorPattern): number | null {
    const map = this.load();
    for (let id = MarkerStoreImpl.FirstId; id < Dict4x4_50.length; id++) {
      if (map.has(id)) continue;
      map.set(id, { slots: new Map(slots), color });
      this.save();
      this.fire();
      return id;
    }
    return null;
  }

  /** 作ったマーカーの色を変える。 */
  setColor(id: number, color: ColorPattern): void {
    const e = this.load().get(id);
    if (!e || (e.color != null && e.color.equals(color))) return;
    e.color = color;
    this.save();
    this.fire();
  }

  delete(id: number): void {
    if (!this.load().delete(id)) return;
    this.save();
    this.fire();
  }

  private fire(): void {
    for (const l of this.changed.slice()) l();
  }

  // 形式: "2=top_1:Ear,back_1:Tail45|#A0A0A0,*#2F4A8A,top_1#FFFFFF;3=..." (Unity 版の形式 + "|色のパターン")
  private load(): Map<number, MarkerEntry> {
    if (this.saved) return this.saved;
    const map = new Map<number, MarkerEntry>();
    this.saved = map;
    for (const entry of Prefs.getString(MarkerStoreImpl.PrefKey, "").split(";")) {
      if (entry.length === 0) continue;
      const eq = entry.indexOf("=");
      if (eq < 0) continue;
      const id = parseInt(entry.slice(0, eq), 10);
      if (Number.isNaN(id)) continue;
      const body = entry.slice(eq + 1).split("|");
      map.set(id, { slots: parseModuleString(body[0]), color: body.length > 1 ? ColorPattern.parse(body[1]) : null });
    }
    return map;
  }

  private save(): void {
    const parts: string[] = [];
    for (const [id, e] of this.load()) parts.push(`${id}=${formatModuleMap(e.slots)}` + (e.color ? "|" + e.color.format() : ""));
    Prefs.putString(MarkerStoreImpl.PrefKey, parts.join(";"));
  }
}

export const MarkerStore = new MarkerStoreImpl();

/** マーカーの画像を作る・保存する。 */
export const MarkerImage = {
  PrintDpi: 300,

  /**
   * 白い余白 (1 マス) 付きのマーカー画像を RGBA の Uint8ClampedArray (n*n*4) として返す。
   * cellPx = 1 マスの画素数。n = 8 * cellPx (余白1 + 黒枠1 + 4ビット + 黒枠1 + 余白1)。
   */
  createPixels(id: number, cellPx: number): { n: number; pixels: Uint8ClampedArray } {
    const n = 8 * cellPx;
    const pixels = new Uint8ClampedArray(n * n * 4);
    const code = Dict4x4_50[id];
    for (let y = 0; y < n; y++) {
      const row = Math.floor(y / cellPx);
      for (let x = 0; x < n; x++) {
        const col = Math.floor(x / cellPx);
        let white: boolean;
        if (row === 0 || row === 7 || col === 0 || col === 7) white = true; // 余白
        else if (row === 1 || row === 6 || col === 1 || col === 6) white = false; // 黒枠
        else white = ((code >> (15 - ((row - 2) * 4 + (col - 2)))) & 1) === 1; // 1 = 白
        const o = (y * n + x) * 4;
        const v = white ? 255 : 0;
        pixels[o] = v;
        pixels[o + 1] = v;
        pixels[o + 2] = v;
        pixels[o + 3] = 255;
      }
    }
    return { n, pixels };
  },

  /**
   * 印刷用 PNG を保存し、保存先を返す。黒い正方形が AidyMarkers.size の大きさになるよう 300dpi で作り、PNG に解像度を書き込む。
   * PNG エンコードは呼び出し側 (Skia) で行い、ここでは最終バイト列への DPI 埋め込みと保存だけを行う。
   */
  async savePng(id: number, pngBytes: Uint8Array): Promise<string> {
    const withDpi = embedDpi(pngBytes, this.PrintDpi);
    const fileName = `aidy_marker_id${id}_${AidyMarkers.sizeMm(id)}mm.png`;
    const dir = new Directory(Paths.cache, "AidyMarkers");
    if (!dir.exists) dir.create({ intermediates: true });
    const file = new File(dir, fileName);
    if (!file.exists) file.create({ overwrite: true });
    file.writeSync(withDpi);
    const path = file.uri;

    const perm = await MediaLibrary.requestPermissionsAsync();
    if (perm.granted) {
      try {
        const asset = await MediaLibrary.createAssetAsync(path);
        const album = await MediaLibrary.getAlbumAsync("AIDY");
        if (album) await MediaLibrary.addAssetsToAlbumAsync([asset], album, false);
        else await MediaLibrary.createAlbumAsync("AIDY", asset, false);
        return "アルバム (AIDY)";
      } catch {
        // フォールバック: アプリ内保存のまま
      }
    }
    return path;
  },
};

/** PNG の IHDR の後ろに pHYs (解像度) を足す。印刷ソフトがこの dpi で原寸に印刷する。 */
function embedDpi(png: Uint8Array, dpi: number): Uint8Array {
  const ihdrEnd = 8 + 4 + 4 + 13 + 4; // シグネチャ + IHDR (長さ・種類・データ・CRC)
  if (png.length < ihdrEnd) return png;
  const ppm = Math.round(dpi / 0.0254);
  const chunk = new Uint8Array(4 + 4 + 9 + 4);
  writeU32(chunk, 0, 9);
  chunk[4] = 0x70; chunk[5] = 0x48; chunk[6] = 0x59; chunk[7] = 0x73; // "pHYs"
  writeU32(chunk, 8, ppm);
  writeU32(chunk, 12, ppm);
  chunk[16] = 1; // 単位: メートル
  writeU32(chunk, 17, crc32(chunk, 4, 13));
  const out = new Uint8Array(png.length + chunk.length);
  out.set(png.subarray(0, ihdrEnd), 0);
  out.set(chunk, ihdrEnd);
  out.set(png.subarray(ihdrEnd), ihdrEnd + chunk.length);
  return out;
}

function writeU32(b: Uint8Array, o: number, v: number): void {
  b[o] = (v >>> 24) & 0xff;
  b[o + 1] = (v >>> 16) & 0xff;
  b[o + 2] = (v >>> 8) & 0xff;
  b[o + 3] = v & 0xff;
}

function crc32(b: Uint8Array, offset: number, count: number): number {
  let c = 0xffffffff;
  for (let i = offset; i < offset + count; i++) {
    c ^= b[i];
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
  }
  return (c ^ 0xffffffff) >>> 0;
}
