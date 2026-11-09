// Unity 側のモデル・ロゴを react-native/src/assets/models/ に ASCII の名前でコピーする。
// kotlin/app/build.gradle.kts の syncAidyAssets タスクと同じ発想・同じ探索順 (stl → obj → 3mf)。
// 見つかった拡張子を記録して src/assets/generated/manifest.ts (Metro の require() は静的パスが必要なため) を生成する。
//
// Windows の CMake/ninja パス長制限 (react-native-worklets 等) を避けるため、本プロジェクトは
// Unity 本体 (Assets/, Modules/ がある C:\iostest\AIdy-android-Kotlin) とは別の短いパス (C:\AIdy-rn) に
// 置いている。Unity 側のルートは環境変数 AIDY_UNITY_ROOT で上書きできる (既定値は開発時の実際の場所)。
const fs = require("fs");
const path = require("path");

const reactNativeRoot = path.resolve(__dirname, "..");
const unityRoot = process.env.AIDY_UNITY_ROOT
  ? path.resolve(process.env.AIDY_UNITY_ROOT)
  : "C:\\iostest\\AIdy-android-Kotlin";
const modulesDir = path.join(unityRoot, "Modules");
const resourcesDir = path.join(unityRoot, "Assets", "Resources", "AIDY");
const outDir = path.join(reactNativeRoot, "src", "assets", "models");
const generatedDir = path.join(reactNativeRoot, "src", "assets", "generated");

const FORMATS = ["stl", "obj", "3mf"];

// モジュール名 (assets/models/<name>.<ext>) → 元ファイルの検索候補 (base名、Modules/ と Assets/Resources/AIDY/ の両方を探す)
const moduleModels = {
  ear: ["耳"],
  tail: ["tail"],
  tail2: ["tail2"],
  hand: ["手_短い"],
  hand_light: ["手_短い_懐中電灯"],
  legs: ["足v1"],
};

// 本体・ロゴなど、モジュールではない固定ファイル
const fixedAssets = [
  { src: "本体v2_モデル.obj", dst: "models/body.obj" },
  { src: "本体v2_顔あり.obj", dst: "models/body_face.obj" },
  { src: "body_v2_layout.json", dst: "models/body_v2_layout.json" },
  { src: "aidy_logo_dark.png", dst: "aidy_logo_dark.png" },
  { src: "aidy_logo_light.png", dst: "aidy_logo_light.png" },
];

function rmrf(p) {
  if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true });
}

function copy(src, dst) {
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.copyFileSync(src, dst);
}

function findModuleFile(base) {
  for (const ext of FORMATS) {
    const candidates = [
      path.join(modulesDir, `${base}.${ext}`),
      path.join(resourcesDir, `${base}_${ext}.bytes`),
      path.join(resourcesDir, `${base}.${ext}`),
    ];
    const found = candidates.find((f) => fs.existsSync(f));
    if (found) return { file: found, ext };
  }
  return null;
}

function main() {
  const assetsRoot = path.join(reactNativeRoot, "src", "assets");
  rmrf(assetsRoot);
  fs.mkdirSync(outDir, { recursive: true });
  fs.mkdirSync(generatedDir, { recursive: true });

  for (const { src, dst } of fixedAssets) {
    const file = path.join(resourcesDir, src);
    if (!fs.existsSync(file)) {
      console.warn(`[AIDY] ${src} が見つかりません`);
      continue;
    }
    copy(file, path.join(reactNativeRoot, "src", "assets", dst));
  }

  /** @type {Record<string, string>} モジュール名 → 見つかった拡張子 */
  const foundExt = {};
  for (const [name, bases] of Object.entries(moduleModels)) {
    let hit = null;
    for (const base of bases) {
      hit = findModuleFile(base);
      if (hit) break;
    }
    if (!hit) {
      console.warn(`[AIDY] モジュールのモデルがありません: ${bases.join(" / ")} (.stl / .obj / .3mf)`);
      continue;
    }
    copy(hit.file, path.join(outDir, `${name}.${hit.ext}`));
    foundExt[name] = hit.ext;
  }

  writeManifest(foundExt);
  console.log("[AIDY] assets synced:", Object.keys(foundExt).join(", "));
}

// Metro の require() は静的な文字列パスが必要なので、見つかった拡張子ごとに require() 呼び出しを生成する。
function writeManifest(foundExt) {
  const lines = [
    "// このファイルは scripts/syncAssets.js が自動生成します。手で編集しないでください。",
    "export const bodyObj = require('../models/body.obj');",
    "export const bodyFaceObj = require('../models/body_face.obj');",
    "export const bodyLayoutJson = require('../models/body_v2_layout.json');",
    "",
    "export const moduleModels: Record<string, number> = {};",
    "export const moduleModelFormats: Record<string, 'stl' | 'obj' | '3mf'> = {};",
  ];
  for (const [name, ext] of Object.entries(foundExt)) {
    lines.push(`moduleModels['${name}'] = require('../models/${name}.${ext}');`);
    lines.push(`moduleModelFormats['${name}'] = '${ext}';`);
  }
  fs.writeFileSync(path.join(generatedDir, "manifest.ts"), lines.join("\n") + "\n");
}

main();
