import * as THREE from "three";

/**
 * three.js のシーン (組み立てた Aidy) を、1 つの GLB (glTF 2.0 のバイナリ) にする。Viro の Viro3DObject (type "GLB") で読む用。
 * - 見えているメッシュだけを、ワールド座標に焼き込んで 1 つのメッシュにまとめる (材質ごとに 1 プリミティブ)。
 * - 色・金属っぽさ・ざらつき・不透明度だけを書く (テクスチャは使っていないので書かない)。
 * - ground = true なら、いちばん下が y=0、左右・前後の中心が 0 になるように動かす (床・机・マーカーの上に立たせる)。
 * GLTFExporter は Blob / FileReader を使うので React Native では動かない。そのため自分で書く。
 */
export function exportGlb(root: THREE.Object3D, ground = true): Uint8Array {
  root.updateMatrixWorld(true);

  interface Batch {
    mat: MatInfo;
    pos: number[];
    nrm: number[];
    idx: number[];
  }
  const batches = new Map<string, Batch>();
  const v = new THREE.Vector3();
  const n = new THREE.Vector3();
  const normalMat = new THREE.Matrix3();

  root.traverseVisible((o) => {
    const mesh = o as THREE.Mesh;
    if (!mesh.isMesh) return;
    const geo = mesh.geometry as THREE.BufferGeometry;
    const posAttr = geo.getAttribute("position") as THREE.BufferAttribute | undefined;
    if (!posAttr) return;
    const nrmAttr = geo.getAttribute("normal") as THREE.BufferAttribute | undefined;
    const index = geo.getIndex();
    const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
    const groups = geo.groups.length > 0 ? geo.groups : [{ start: 0, count: index ? index.count : posAttr.count, materialIndex: 0 }];
    const m = mesh.matrixWorld;
    normalMat.getNormalMatrix(m);
    const flip = m.determinant() < 0; // 鏡写しのときは三角形の向きを逆にする

    for (const g of groups) {
      const mat = mats[g.materialIndex ?? 0] ?? mats[0];
      if (!mat || !mat.visible) continue;
      const info = matInfo(mat);
      let b = batches.get(info.key);
      if (!b) {
        b = { mat: info, pos: [], nrm: [], idx: [] };
        batches.set(info.key, b);
      }
      // このグループで使う頂点だけを写す (頂点番号 → 新しい番号)
      const remap = new Map<number, number>();
      const take = (i: number): number => {
        let r = remap.get(i);
        if (r != null) return r;
        r = b.pos.length / 3;
        remap.set(i, r);
        v.fromBufferAttribute(posAttr, i).applyMatrix4(m);
        b.pos.push(v.x, v.y, v.z);
        if (nrmAttr) n.fromBufferAttribute(nrmAttr, i).applyMatrix3(normalMat).normalize();
        else n.set(0, 1, 0);
        b.nrm.push(n.x, n.y, n.z);
        return r;
      };
      const end = Math.min(g.start + g.count, index ? index.count : posAttr.count);
      for (let t = g.start; t + 2 < end; t += 3) {
        const a = take(index ? index.getX(t) : t);
        const c1 = take(index ? index.getX(t + 1) : t + 1);
        const c2 = take(index ? index.getX(t + 2) : t + 2);
        if (flip) b.idx.push(a, c2, c1);
        else b.idx.push(a, c1, c2);
      }
    }
  });

  // 床に立たせる
  if (ground) {
    let minX = Infinity, minY = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
    for (const b of batches.values()) {
      for (let i = 0; i < b.pos.length; i += 3) {
        minX = Math.min(minX, b.pos[i]);
        maxX = Math.max(maxX, b.pos[i]);
        minY = Math.min(minY, b.pos[i + 1]);
        minZ = Math.min(minZ, b.pos[i + 2]);
        maxZ = Math.max(maxZ, b.pos[i + 2]);
      }
    }
    if (Number.isFinite(minY)) {
      const cx = (minX + maxX) / 2;
      const cz = (minZ + maxZ) / 2;
      for (const b of batches.values()) {
        for (let i = 0; i < b.pos.length; i += 3) {
          b.pos[i] -= cx;
          b.pos[i + 1] -= minY;
          b.pos[i + 2] -= cz;
        }
      }
    }
  }

  // ---------------------------------------------------------------- glTF の JSON とバイナリ
  const bin = new BinWriter();
  const accessors: unknown[] = [];
  const bufferViews: unknown[] = [];
  const materials: unknown[] = [];
  const primitives: unknown[] = [];
  let usesUnlit = false;

  const addView = (bytes: Uint8Array, target: number): number => {
    const offset = bin.append(bytes);
    bufferViews.push({ buffer: 0, byteOffset: offset, byteLength: bytes.byteLength, target });
    return bufferViews.length - 1;
  };

  for (const b of batches.values()) {
    if (b.idx.length === 0) continue;
    const pos = new Float32Array(b.pos);
    const nrm = new Float32Array(b.nrm);
    const idx = new Uint32Array(b.idx);
    const min = [Infinity, Infinity, Infinity];
    const max = [-Infinity, -Infinity, -Infinity];
    for (let i = 0; i < pos.length; i += 3) {
      for (let k = 0; k < 3; k++) {
        min[k] = Math.min(min[k], pos[i + k]);
        max[k] = Math.max(max[k], pos[i + k]);
      }
    }
    const vPos = addView(new Uint8Array(pos.buffer), 34962);
    const vNrm = addView(new Uint8Array(nrm.buffer), 34962);
    const vIdx = addView(new Uint8Array(idx.buffer), 34963);
    accessors.push({ bufferView: vPos, componentType: 5126, count: pos.length / 3, type: "VEC3", min, max });
    const aPos = accessors.length - 1;
    accessors.push({ bufferView: vNrm, componentType: 5126, count: nrm.length / 3, type: "VEC3" });
    const aNrm = accessors.length - 1;
    accessors.push({ bufferView: vIdx, componentType: 5125, count: idx.length, type: "SCALAR" });
    const aIdx = accessors.length - 1;

    const mt = b.mat;
    const material: Record<string, unknown> = {
      pbrMetallicRoughness: {
        baseColorFactor: [mt.r, mt.g, mt.b, mt.opacity],
        metallicFactor: mt.metalness,
        roughnessFactor: mt.roughness,
      },
      doubleSided: true, // STL などで面の向きがそろっていない部品があっても抜けて見えないように
    };
    if (mt.opacity < 1) material.alphaMode = "BLEND";
    if (mt.unlit) {
      material.extensions = { KHR_materials_unlit: {} };
      usesUnlit = true;
    }
    materials.push(material);
    primitives.push({ attributes: { POSITION: aPos, NORMAL: aNrm }, indices: aIdx, material: materials.length - 1, mode: 4 });
  }

  const gltf: Record<string, unknown> = {
    asset: { version: "2.0", generator: "AIDY" },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes: [{ mesh: 0 }],
    meshes: [{ primitives }],
    materials,
    accessors,
    bufferViews,
    buffers: [{ byteLength: bin.length }],
  };
  if (usesUnlit) gltf.extensionsUsed = ["KHR_materials_unlit"];
  return packGlb(asciiJson(gltf), bin.bytes());
}

interface MatInfo {
  key: string;
  r: number;
  g: number;
  b: number;
  opacity: number;
  metalness: number;
  roughness: number;
  unlit: boolean;
}

function matInfo(mat: THREE.Material): MatInfo {
  const anyMat = mat as THREE.Material & { color?: THREE.Color; metalness?: number; roughness?: number };
  // three.js の色は Linear。glTF の baseColorFactor も Linear なのでそのまま使う
  const c = anyMat.color ?? new THREE.Color(1, 1, 1);
  const unlit = (mat as THREE.MeshBasicMaterial).isMeshBasicMaterial === true;
  const metalness = unlit ? 0 : clamp01(anyMat.metalness ?? 0);
  const roughness = unlit ? 1 : clamp01(anyMat.roughness ?? 1);
  const opacity = clamp01(mat.transparent ? mat.opacity : 1);
  const r = round(c.r), g = round(c.g), b = round(c.b);
  return { key: [r, g, b, opacity, metalness, roughness, unlit].join(","), r, g, b, opacity, metalness, roughness, unlit };
}

function clamp01(x: number): number {
  return Math.min(1, Math.max(0, x));
}
function round(x: number): number {
  return Math.round(clamp01(x) * 10000) / 10000;
}

/** JSON を ASCII だけの文字列にしてバイトにする (英数字しか入れていないが、念のため \uXXXX にする)。 */
function asciiJson(o: unknown): Uint8Array {
  const s = JSON.stringify(o).replace(/[\u007f-￿]/g, (ch) => "\\u" + ch.charCodeAt(0).toString(16).padStart(4, "0"));
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

class BinWriter {
  private parts: Uint8Array[] = [];
  length = 0;
  /** 4 バイト境界にそろえて足し、その位置を返す。 */
  append(b: Uint8Array): number {
    const pad = (4 - (this.length % 4)) % 4;
    if (pad > 0) {
      this.parts.push(new Uint8Array(pad));
      this.length += pad;
    }
    const offset = this.length;
    this.parts.push(b);
    this.length += b.byteLength;
    return offset;
  }
  bytes(): Uint8Array {
    const out = new Uint8Array(this.length);
    let o = 0;
    for (const p of this.parts) {
      out.set(p, o);
      o += p.byteLength;
    }
    return out;
  }
}

function packGlb(json: Uint8Array, bin: Uint8Array): Uint8Array {
  const jsonPad = (4 - (json.length % 4)) % 4;
  const binPad = (4 - (bin.length % 4)) % 4;
  const jsonLen = json.length + jsonPad;
  const binLen = bin.length + binPad;
  const total = 12 + 8 + jsonLen + 8 + binLen;
  const out = new Uint8Array(total);
  const dv = new DataView(out.buffer);
  dv.setUint32(0, 0x46546c67, true); // "glTF"
  dv.setUint32(4, 2, true);
  dv.setUint32(8, total, true);
  dv.setUint32(12, jsonLen, true);
  dv.setUint32(16, 0x4e4f534a, true); // "JSON"
  out.set(json, 20);
  out.fill(0x20, 20 + json.length, 20 + jsonLen); // JSON の詰め物は空白
  const binStart = 20 + jsonLen;
  dv.setUint32(binStart, binLen, true);
  dv.setUint32(binStart + 4, 0x004e4942, true); // "BIN\0"
  out.set(bin, binStart + 8);
  return out;
}
