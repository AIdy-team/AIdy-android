import { Asset } from "expo-asset";
import { File } from "expo-file-system";
import { ModuleKind, ModuleCatalog, MountAxis } from "../data/modules";
import { MeshData } from "./meshData";
import { parseModule, parseBodyModel } from "./modelLoader";
import { moduleModels, moduleModelFormats, bodyObj, bodyFaceObj } from "../assets/generated/manifest";

async function readAssetBytes(moduleId: number): Promise<Uint8Array> {
  const asset = Asset.fromModule(moduleId);
  await asset.downloadAsync();
  const uri = asset.localUri ?? asset.uri;
  return new File(uri).bytesSync();
}

const moduleCache = new Map<ModuleKind, Promise<MeshData | null>>();
/** 読み込みが終わったモジュールの結果 (同期で覗き見するため)。未読み込みなら undefined。 */
const resolvedModuleMeshes = new Map<ModuleKind, MeshData | null>();

/** モジュールのモデルを読み込む (STL / OBJ / 3MF、取り付け面ローカルのメッシュ)。結果はキャッシュする。 */
export function loadModuleModel(kind: ModuleKind): Promise<MeshData | null> {
  let p = moduleCache.get(kind);
  if (p) return p;
  p = (async () => {
    const key = ModuleCatalog.modelKey(kind);
    if (!key) return null;
    const moduleId = moduleModels[key];
    if (moduleId == null) return null;
    try {
      const bytes = await readAssetBytes(moduleId);
      const axis: MountAxis = ModuleCatalog.axis(kind);
      return parseModule(bytes, moduleModelFormats[key], axis);
    } catch (e) {
      console.warn(`[AIDY] モジュールのモデルの読込に失敗 ${kind}:`, e);
      return null;
    }
  })().then((data) => {
    resolvedModuleMeshes.set(kind, data);
    return data;
  });
  moduleCache.set(kind, p);
  return p;
}

/** 読み込み済みなら結果を同期で返す (undefined = まだ読み込み中/未着手)。 */
export function peekModuleModel(kind: ModuleKind): MeshData | null | undefined {
  return resolvedModuleMeshes.get(kind);
}

/** すべてのモジュールモデル・本体モデルを事前に読み込む (起動時に呼ぶ)。 */
export async function preloadAllModels(): Promise<void> {
  await Promise.all([...ModuleCatalog.All.map(loadModuleModel), loadBodyModel(false), loadBodyModel(true)]);
}

let bodyCache: Promise<MeshData | null> | null = null;
let bodyFaceCache: Promise<MeshData | null> | null = null;

/** 本体モデル (face=false: body.obj、true: body_face.obj)。 */
export function loadBodyModel(face: boolean): Promise<MeshData | null> {
  if (face) {
    if (!bodyFaceCache) bodyFaceCache = readAssetBytes(bodyFaceObj).then(parseBodyModel).catch((e) => {
      console.warn("[AIDY] 本体モデル(顔あり)の読込に失敗:", e);
      return null;
    });
    return bodyFaceCache;
  }
  if (!bodyCache) bodyCache = readAssetBytes(bodyObj).then(parseBodyModel).catch((e) => {
    console.warn("[AIDY] 本体モデルの読込に失敗:", e);
    return null;
  });
  return bodyCache;
}
