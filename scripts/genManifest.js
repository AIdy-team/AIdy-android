// src/assets/models/ (サブフォルダ含む) に置かれているファイルをそのまま読み取って、
// src/assets/generated/manifest.ts (Metro の require() は静的パスが必要なため) を生成する。
//
// 以前は Unity 側プロジェクトから都度ファイルをコピーしてくる仕組みだったが、
// パーツが増えていくにつれて手間になるため廃止した。モデルを追加/差し替えたいときは
// 本体側 (modules.ts の ModuleCatalog.modelKey() が返す名前) に合わせたファイル名で、
// パーツ種別のフォルダ (ear/ tail/ hand/ legs/ など) の下に直接置けばよい。
// 本体は src/assets/models/ 直下に body.obj / body_face.obj / body_v2_layout.json 固定。
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const modelsDir = path.join(root, "src", "assets", "models");
const generatedDir = path.join(root, "src", "assets", "generated");

// 同じ名前で拡張子違いが複数あるときに優先する順番。
const FORMAT_PRIORITY = ["stl", "obj", "3mf"];

const FixedFiles = {
  bodyObj: "body.obj",
  bodyFaceObj: "body_face.obj",
  bodyLayoutJson: "body_v2_layout.json",
};

/** dir 以下 (サブフォルダ含む) の全ファイルを、modelsDir からの相対パス (posix 区切り) で列挙する。 */
function listFilesRecursive(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listFilesRecursive(full));
    else out.push(path.relative(modelsDir, full).split(path.sep).join("/"));
  }
  return out;
}

function main() {
  fs.mkdirSync(modelsDir, { recursive: true });
  fs.mkdirSync(generatedDir, { recursive: true });

  for (const file of Object.values(FixedFiles)) {
    if (!fs.existsSync(path.join(modelsDir, file))) {
      console.warn(`[AIDY] src/assets/models/${file} が見つかりません`);
    }
  }

  const fixedNames = new Set(Object.values(FixedFiles));
  /** @type {Map<string, {ext: string, relPath: string}[]>} モジュール名 (拡張子抜き) → 見つかった候補の一覧 */
  const byName = new Map();
  for (const relPath of listFilesRecursive(modelsDir)) {
    if (fixedNames.has(relPath)) continue; // 本体ファイル (ルート直下) は対象外
    const ext = path.extname(relPath).slice(1).toLowerCase();
    if (!FORMAT_PRIORITY.includes(ext)) continue;
    const name = path.basename(relPath, path.extname(relPath));
    const list = byName.get(name) ?? [];
    list.push({ ext, relPath });
    byName.set(name, list);
  }

  /** @type {Record<string, {ext: string, relPath: string}>} モジュール名 → 採用したファイル */
  const moduleModels = {};
  for (const [name, candidates] of byName) {
    if (candidates.length > 1) {
      console.warn(`[AIDY] ${name} に複数の候補 (${candidates.map((c) => c.relPath).join(", ")}) があります。優先度の高い拡張子を使います。`);
    }
    const ext = FORMAT_PRIORITY.find((e) => candidates.some((c) => c.ext === e));
    moduleModels[name] = candidates.find((c) => c.ext === ext);
  }

  writeManifest(moduleModels);
  console.log("[AIDY] manifest generated:", Object.keys(moduleModels).join(", "));
}

function writeManifest(moduleModels) {
  const lines = [
    "// このファイルは scripts/genManifest.js が自動生成します。手で編集しないでください。",
    `export const bodyObj = require('../models/${FixedFiles.bodyObj}');`,
    `export const bodyFaceObj = require('../models/${FixedFiles.bodyFaceObj}');`,
    `export const bodyLayoutJson = require('../models/${FixedFiles.bodyLayoutJson}');`,
    "",
    "export const moduleModels: Record<string, number> = {};",
    "export const moduleModelFormats: Record<string, 'stl' | 'obj' | '3mf'> = {};",
  ];
  for (const [name, { ext, relPath }] of Object.entries(moduleModels)) {
    lines.push(`moduleModels['${name}'] = require('../models/${relPath}');`);
    lines.push(`moduleModelFormats['${name}'] = '${ext}';`);
  }
  fs.writeFileSync(path.join(generatedDir, "manifest.ts"), lines.join("\n") + "\n");
}

main();
