// Phase 2 動作確認用のスモークテスト (RN実機なしで三角形数・向きを検算する)。
// data/modules.ts は AsyncStorage 等RN依存を持つため読み込まず、対応表をここに直接書く。
// 使い方: npx tsx scripts/smokeTestModelLoader.ts
import * as fs from "fs";
import * as path from "path";
import { parseModule, parseBodyModel } from "../src/three/modelLoader";
import type { ModelFormat } from "../src/three/modelLoader";
import type { MountAxis } from "../src/data/modules";

const modelsDir = path.join(__dirname, "..", "src", "assets", "models");

// modules.ts の ModuleCatalog.modelKey() / axis() と同じ対応 (ファイルはサブフォルダも探す)
const modules: Array<{ name: string; key: string; axis: MountAxis }> = [
  { name: "耳", key: "mimi2", axis: "ZUpFrontMinusY" },
  { name: "尻尾45", key: "尻尾_45", axis: "SlotTabPlusY" },
  { name: "巻き尻尾", key: "尻尾_巻き2", axis: "FlatHangY" },
  { name: "シルクハット", key: "シルクハット", axis: "ZUpFrontMinusY" },
  { name: "ブラウン管テレビ", key: "ブラウン管テレビ", axis: "ZUpFrontMinusYCentered" },
  { name: "足", key: "legs", axis: "TopPadZUp" },
  { name: "手", key: "hand", axis: "MagnetPlusX" },
  { name: "ライト手", key: "hand_light", axis: "MagnetPlusX" },
];

function listFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? listFiles(path.join(dir, e.name)).map((f) => path.join(e.name, f)) : [e.name],
  );
}

for (const m of modules) {
  const files = listFiles(modelsDir).filter((f) => path.basename(f).startsWith(`${m.key}.`));
  if (files.length === 0) {
    console.log(`${m.name}: ファイルが見つかりません (${m.key})`);
    continue;
  }
  const file = files[0];
  const ext = path.extname(file).slice(1) as ModelFormat;
  const bytes = new Uint8Array(fs.readFileSync(path.join(modelsDir, file)));
  const data = parseModule(bytes, ext, m.axis);
  if (!data) {
    console.log(`${m.name}: 解析失敗 (${file})`);
    continue;
  }
  console.log(
    `${m.name} (${file}): OK 頂点=${data.vertexCount} 三角形=${data.indices.length / 3} ` +
      `min=(${data.min.x.toFixed(2)},${data.min.y.toFixed(2)},${data.min.z.toFixed(2)}) ` +
      `max=(${data.max.x.toFixed(2)},${data.max.y.toFixed(2)},${data.max.z.toFixed(2)})`,
  );
}

for (const [label, file] of [["body", "body.obj"], ["body_face", "body_face.obj"]] as const) {
  const bytes = new Uint8Array(fs.readFileSync(path.join(modelsDir, file)));
  const data = parseBodyModel(bytes);
  if (!data) {
    console.log(`${label}: 解析失敗`);
    continue;
  }
  console.log(
    `${label}: OK 頂点=${data.vertexCount} 三角形=${data.indices.length / 3} groups=${data.groups.map((g) => g.material || "(default)").join(",")}`,
  );
}
