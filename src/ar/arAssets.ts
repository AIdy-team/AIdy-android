import * as THREE from "three";
import { Directory, File, Paths } from "expo-file-system";
import { AlphaType, ColorType, Skia } from "@shopify/react-native-skia";
import { ModuleRig } from "../three/rig";
import { exportGlb } from "../three/glbExport";
import { ModuleConfig, ModuleKind, formatModuleMap } from "../data/modules";
import { BodyColor, ColorPattern } from "../data/bodyColor";
import { AidyMarkers, MarkerImage } from "../data/markers";

/**
 * Viro の AR で使うファイルを作る。
 *  - Aidy の 3D: ModuleRig (ホーム・付け替えと同じ組み立て) を GLB にして、キャッシュに置く。構成と色が同じなら作り直さない。
 *  - マーカー: ArUco の画像 (黒い正方形だけ) を PNG にして、Viro の画像ターゲットとして登録する。
 * 長さの単位はメートル。本体 150mm = 0.15。
 */

/** AR での本体の一辺 (m)。実物と同じ 15cm。 */
export const BodySizeM = 0.15;

const CacheDir = "AidyAR";

function cacheDir(): Directory {
  const dir = new Directory(Paths.cache, CacheDir);
  if (!dir.exists) dir.create({ intermediates: true });
  return dir;
}

/** 文字列の短いハッシュ (ファイル名用)。 */
function hash(s: string): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193) >>> 0;
    h2 = Math.imul(h2 + c, 0x5bd1e995) >>> 0;
  }
  return h1.toString(16).padStart(8, "0") + h2.toString(16).padStart(8, "0");
}

const modelJobs = new Map<string, Promise<string>>();

/**
 * その構成・色の Aidy の GLB を作り、ファイルの URI ("file:///…/aidy_xxxx.glb") を返す。
 * slots / color が null なら、いまの着せ替え (ModuleConfig・BodyColor)。
 */
export function aidyModelUri(slots: Map<string, ModuleKind> | null, color: ColorPattern | null): Promise<string> {
  const s = new Map(slots ?? ModuleConfig.current);
  const c = color ?? BodyColor.pattern;
  // 形を変えたら v を上げる (古いキャッシュを使わないように)
  const key = `v1|${BodySizeM}|${formatModuleMap(s)}|${c.format()}`;
  let job = modelJobs.get(key);
  if (!job) {
    job = buildModel(s, c, `aidy_${hash(key)}.glb`);
    modelJobs.set(key, job);
    job.catch(() => modelJobs.delete(key)); // 失敗したら次はやり直す
  }
  return job;
}

async function buildModel(slots: Map<string, ModuleKind>, color: ColorPattern, fileName: string): Promise<string> {
  const file = new File(cacheDir(), fileName);
  if (file.exists && file.size > 0) return file.uri;
  const root = new THREE.Group();
  const rig = new ModuleRig(root, BodySizeM, false, slots, color);
  try {
    await rig.whenReady();
    await rig.sync();
    if (!rig.hasModel) throw new Error("本体のモデルを読み込めませんでした");
    const glb = exportGlb(root, true);
    if (!file.exists) file.create({ overwrite: true });
    file.writeSync(glb);
    return file.uri;
  } finally {
    rig.release();
  }
}

/** そのマーカーで出す Aidy の GLB。 */
export function markerModelUri(id: number): Promise<string> {
  return aidyModelUri(AidyMarkers.config(id), AidyMarkers.color(id));
}

// ------------------------------------------------------------------ マーカー (画像ターゲット)

/** Viro に登録するターゲットの名前。 */
export function markerTargetName(id: number): string {
  return `aidy_marker_${id}`;
}

/**
 * ArUco マーカーの黒い正方形 (外枠を含む・白い余白なし) を PNG にして URI を返す。
 * 実物の黒い正方形の一辺が AidyMarkers.size(id) なので、physicalWidth にはそれをそのまま使える。
 */
function markerPngUri(id: number): string {
  const file = new File(cacheDir(), `target_id${id}.png`);
  if (file.exists && file.size > 0) return file.uri;
  const cell = 80; // 1 マス 80px → 黒い正方形 480px
  const { n, pixels } = MarkerImage.createPixels(id, cell);
  const side = n - cell * 2;
  const crop = new Uint8Array(side * side * 4);
  for (let y = 0; y < side; y++) {
    const from = ((y + cell) * n + cell) * 4;
    crop.set(pixels.subarray(from, from + side * 4), y * side * 4);
  }
  const image = Skia.Image.MakeImage(
    { width: side, height: side, colorType: ColorType.RGBA_8888, alphaType: AlphaType.Unpremul },
    Skia.Data.fromBytes(crop),
    side * 4,
  );
  if (!image) throw new Error("マーカーの画像を作れませんでした");
  const png = image.encodeToBytes();
  if (!file.exists) file.create({ overwrite: true });
  file.writeSync(png);
  return file.uri;
}

let registered = "";
let registeredIds: number[] = [];

/**
 * いま認識するマーカー (AidyMarkers.activeIds) を Viro の画像ターゲットとして登録し、その ID を返す。
 * 同じ組み合わせなら登録し直さない。
 */
export function registerMarkerTargets(
  createTargets: (targets: Record<string, { source: { uri: string }; orientation: string; physicalWidth: number; type: string }>) => void,
): number[] {
  const ids = Array.from(AidyMarkers.activeIds()).sort((a, b) => a - b);
  const key = ids.join(",");
  if (key === registered) return registeredIds;
  const targets: Record<string, { source: { uri: string }; orientation: string; physicalWidth: number; type: string }> = {};
  const ok: number[] = [];
  for (const id of ids) {
    try {
      targets[markerTargetName(id)] = { source: { uri: markerPngUri(id) }, orientation: "Up", physicalWidth: AidyMarkers.size(id), type: "Image" };
      ok.push(id);
    } catch (e) {
      console.warn(`[AIDY] マーカー ${id} の画像を作れません:`, e);
    }
  }
  createTargets(targets);
  registered = key;
  registeredIds = ok;
  return ok;
}
