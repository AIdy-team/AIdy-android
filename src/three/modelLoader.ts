import { unzipSync } from "fflate";
import { V3 } from "./math";
import { MeshData, MeshGroup } from "./meshData";
import { MountAxis } from "../data/modules";

const CreaseAngle = 40;

// ---------------------------------------------------------------- 読み込み (三角形ごとに 3 頂点、単位 mm)

function readFloatLE(b: Uint8Array, o: number): number {
  return new DataView(b.buffer, b.byteOffset, b.byteLength).getFloat32(o, true);
}
function readUint32LE(b: Uint8Array, o: number): number {
  return new DataView(b.buffer, b.byteOffset, b.byteLength).getUint32(o, true);
}

/** STL (バイナリ/ASCII)。 */
export function parseStl(b: Uint8Array): V3[] {
  const v: V3[] = [];
  if (b.length >= 84) {
    const n = readUint32LE(b, 80);
    if (84 + 50 * n === b.length) {
      for (let i = 0; i < n; i++) {
        let o = 84 + 50 * i + 12;
        for (let k = 0; k < 3; k++) {
          v.push(new V3(readFloatLE(b, o), readFloatLE(b, o + 4), readFloatLE(b, o + 8)));
          o += 12;
        }
      }
      return toMm(v);
    }
  }
  const text = new TextDecoder("ascii").decode(b);
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line.startsWith("vertex")) continue;
    const p = line.split(/\s+/);
    if (p.length < 4) continue;
    v.push(new V3(parseFloat(p[1]), parseFloat(p[2]), parseFloat(p[3])));
  }
  return toMm(v);
}

/** STL は単位を持たないので、全体が 1 未満ならメートルとみなして mm にする。 */
function toMm(v: V3[]): V3[] {
  if (v.length === 0) return v;
  let lo = v[0];
  let hi = v[0];
  for (const p of v) {
    lo = V3.min(lo, p);
    hi = V3.max(hi, p);
  }
  const size = hi.sub(lo);
  if (Math.max(size.x, Math.max(size.y, size.z)) >= 1) return v;
  return v.map((p) => p.scale(1000));
}

/**
 * OBJ (v と f だけ使う)。Blender の既定の書き出し (Y 上・-Z 前) を STL と同じ Z 上 (x, -z, y) に戻す。
 * 多角形の面は耳切り法で三角形に分ける。
 */
export function parseObj(b: Uint8Array): V3[] {
  const verts: V3[] = [];
  const tris: V3[] = [];
  const text = new TextDecoder("utf-8").decode(b);
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    const vertex = line.startsWith("v ");
    const face = line.startsWith("f ");
    if (!vertex && !face) continue;
    const p = line.split(/\s+/);
    if (vertex) {
      if (p.length >= 4) verts.push(new V3(parseFloat(p[1]), -parseFloat(p[3]), parseFloat(p[2])));
      continue;
    }
    const poly: V3[] = [];
    for (let i = 1; i < p.length; i++) {
      const s = p[i].split("/")[0];
      const idx = parseInt(s, 10);
      poly.push(verts[idx > 0 ? idx - 1 : verts.length + idx]); // 負の番号は末尾からの相対
    }
    triangulate(poly, tris);
  }
  return toMm(tris);
}

/** 平面の多角形を耳切り法で三角形に分けて output に足す (巻き順は元の多角形のまま)。 */
export function triangulate(poly: V3[], output: V3[]): void {
  const n = poly.length;
  if (n < 3) return;
  if (n === 3) {
    output.push(...poly);
    return;
  }
  // Newell 法で面の法線を求め、その平面に投影した 2D で耳を探す
  let normal = V3.Zero;
  for (let i = 0; i < n; i++) {
    const a = poly[i];
    const c = poly[(i + 1) % n];
    normal = normal.add(new V3((a.y - c.y) * (a.z + c.z), (a.z - c.z) * (a.x + c.x), (a.x - c.x) * (a.y + c.y)));
  }
  normal = normal.sqrLength > 0 ? normal.normalized() : V3.Forward;
  const u = normal.cross(Math.abs(normal.x) < 0.9 ? V3.Right : V3.Up).normalized();
  const w = normal.cross(u);
  const px = poly.map((p) => p.dot(u));
  const py = poly.map((p) => p.dot(w));
  const cross = (a: number, b: number, c: number) => (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a]);
  const crossP = (a: number, b: number, x: number, y: number) => (px[b] - px[a]) * (y - py[a]) - (py[b] - py[a]) * (x - px[a]);
  const inTri = (p: number, a: number, b: number, c: number) =>
    crossP(a, b, px[p], py[p]) >= 0 && crossP(b, c, px[p], py[p]) >= 0 && crossP(c, a, px[p], py[p]) >= 0;

  const rest: number[] = [];
  for (let i = 0; i < n; i++) rest.push(i);
  while (rest.length > 3) {
    let clipped = false;
    let i = 0;
    while (i < rest.length && !clipped) {
      const ia = rest[(i + rest.length - 1) % rest.length];
      const ib = rest[i];
      const ic = rest[(i + 1) % rest.length];
      if (cross(ia, ib, ic) > 0) {
        // 凹んだ頂点は耳にならない
        let inside = false;
        for (const j of rest) {
          if (j !== ia && j !== ib && j !== ic && inTri(j, ia, ib, ic)) {
            inside = true;
            break;
          }
        }
        if (!inside) {
          output.push(poly[ia], poly[ib], poly[ic]);
          rest.splice(i, 1);
          clipped = true;
        }
      }
      i++;
    }
    if (!clipped) break; // 退化した面: 残りは扇形で埋める
  }
  for (let i = 1; i < rest.length - 1; i++) output.push(poly[rest[0]], poly[rest[i]], poly[rest[i + 1]]);
}

const VertexRe = /<vertex\s+x="([^"]+)"\s+y="([^"]+)"\s+z="([^"]+)"/g;
const TriangleRe = /<triangle\s+v1="(\d+)"\s+v2="(\d+)"\s+v3="(\d+)"/g;
const MeshRe = /<mesh>([\s\S]*?)<\/mesh>/g;

/** 3MF (zip 内の 3D/3dmodel.model) の全オブジェクトのメッシュをまとめて読む。 */
export function parse3mf(b: Uint8Array): V3[] {
  const files = unzipSync(b);
  let xml: string | null = null;
  for (const name of Object.keys(files)) {
    if (name.toLowerCase().endsWith(".model")) {
      xml = new TextDecoder("utf-8").decode(files[name]);
      break;
    }
  }
  if (xml == null) throw new Error("3dmodel.model がありません");
  const unitMatch = /unit="(\w+)"/.exec(xml);
  const unit = (() => {
    switch (unitMatch?.[1]) {
      case "micron": return 0.001;
      case "centimeter": return 10;
      case "inch": return 25.4;
      case "foot": return 304.8;
      case "meter": return 1000;
      default: return 1;
    }
  })();
  const tris: V3[] = [];
  for (const meshMatch of xml.matchAll(MeshRe)) {
    const body = meshMatch[1];
    const verts: V3[] = [];
    for (const vm of body.matchAll(VertexRe)) {
      verts.push(new V3(parseFloat(vm[1]), parseFloat(vm[2]), parseFloat(vm[3])).scale(unit));
    }
    for (const tm of body.matchAll(TriangleRe)) {
      tris.push(verts[parseInt(tm[1], 10)], verts[parseInt(tm[2], 10)], verts[parseInt(tm[3], 10)]);
    }
  }
  return tris;
}

// ---------------------------------------------------------------- 取り付け面ローカルへの変換 + 法線

function build(src0: V3[], axis: MountAxis): MeshData | null {
  let src = src0;
  if (src.length < 3) return null;
  let lo = src[0];
  let hi = src[0];
  for (const p of src) {
    lo = V3.min(lo, p);
    hi = V3.max(hi, p);
  }
  if (axis === "TopPadZUp") {
    src = firstPart(src, lo, hi);
    lo = src[0];
    hi = src[0];
    for (const p of src) {
      lo = V3.min(lo, p);
      hi = V3.max(hi, p);
    }
  }
  let pocket: [number, number] = [0, 0];
  if (axis === "MagnetPlusX") pocket = findPocket(src, lo, hi);
  else if (axis === "TopPadZUp") pocket = topPad(src, hi.z);

  const count = src.length - (src.length % 3);
  const pos: V3[] = new Array(count);
  for (let i = 0; i < count; i++) {
    const p = src[i];
    let u: V3;
    switch (axis) {
      case "ZUpFrontMinusY":
        u = new V3(-p.x, p.z - lo.z, -p.y);
        break;
      case "OutwardMinusY": {
        const cx = (lo.x + hi.x) * 0.5;
        const cz = (lo.z + hi.z) * 0.5;
        u = new V3(-(p.x - cx), hi.y - p.y, p.z - cz);
        break;
      }
      case "SlotTabPlusY":
        u = new V3(-p.x, p.y - lo.y, p.z);
        break;
      case "TopPadZUp":
        u = new V3(p.x - pocket[0], hi.z - p.z, -(p.y - pocket[1]));
        break;
      case "MagnetPlusX":
        u = new V3(-(p.z - pocket[1]), hi.x - p.x, p.y - pocket[0]);
        break;
    }
    pos[i] = u.mirrorX();
  }
  return withNormals(pos);
}

/** 三角形の並び (3 頂点ずつ) から、外向きの巻き順と折り目付きの法線を持つメッシュを作る。 */
export function withNormals(pos0: V3[]): MeshData {
  const pos = pos0.slice();
  const count = pos.length - (pos.length % 3);
  // 外向きの面が表 (反時計回り) になるよう巻き順をそろえる (符号付き体積が負なら反転)
  let vol = 0;
  for (let i = 0; i < count; i += 3) vol += pos[i].dot(pos[i + 1].cross(pos[i + 2]));
  if (vol < 0) {
    for (let i = 0; i < count; i += 3) {
      const t = pos[i + 1];
      pos[i + 1] = pos[i + 2];
      pos[i + 2] = t;
    }
  }

  // 面法線を求め、同じ位置で角度が CreaseAngle 以内の面だけ平均してなめらかにする (角は立てたまま)
  const tris = count / 3;
  const faceN: V3[] = new Array(tris);
  const faceU: V3[] = new Array(tris);
  for (let t = 0; t < tris; t++) {
    faceN[t] = pos[t * 3 + 1].sub(pos[t * 3]).cross(pos[t * 3 + 2].sub(pos[t * 3])); // 長さ = 面積の 2 倍
    faceU[t] = faceN[t].normalized();
  }
  // 位置をキーにして同じ頂点を共有する三角形を集める (JSの安全整数範囲を超えないよう文字列キーにする)
  const shared = new Map<string, number[]>();
  const key = (p: V3): string => `${Math.round(p.x * 1000)}_${Math.round(p.y * 1000)}_${Math.round(p.z * 1000)}`;
  for (let t = 0; t < tris; t++) {
    for (let k = 0; k < 3; k++) {
      const kk = key(pos[t * 3 + k]);
      const list = shared.get(kk);
      if (list) list.push(t);
      else shared.set(kk, [t]);
    }
  }
  const cosA = Math.cos((CreaseAngle * Math.PI) / 180);
  const positions = new Float32Array(count * 3);
  const normals = new Float32Array(count * 3);
  for (let t = 0; t < tris; t++) {
    const nt = faceU[t];
    for (let k = 0; k < 3; k++) {
      const i = t * 3 + k;
      let sx = 0, sy = 0, sz = 0;
      for (const o of shared.get(key(pos[i]))!) {
        if (faceU[o].dot(nt) >= cosA) {
          sx += faceN[o].x; sy += faceN[o].y; sz += faceN[o].z;
        }
      }
      let n = new V3(sx, sy, sz);
      n = n.sqrLength > 0 ? n.normalized() : nt;
      positions[i * 3] = pos[i].x; positions[i * 3 + 1] = pos[i].y; positions[i * 3 + 2] = pos[i].z;
      normals[i * 3] = n.x; normals[i * 3 + 1] = n.y; normals[i * 3 + 2] = n.z;
    }
  }
  const indices = new Uint32Array(count);
  for (let i = 0; i < count; i++) indices[i] = i;
  return new MeshData(positions, normals, indices);
}

/** X の真ん中に隙間があれば (左右 2 つ入りのモデル)、X が小さい側の三角形だけ返す。 */
function firstPart(src: V3[], lo: V3, hi: V3): V3[] {
  const mid = (lo.x + hi.x) * 0.5;
  const part: V3[] = [];
  let i = 0;
  while (i + 2 < src.length) {
    const x = Math.max(src[i].x, Math.max(src[i + 1].x, src[i + 2].x));
    if (Math.min(src[i].x, Math.min(src[i + 1].x, src[i + 2].x)) < mid && x >= mid) return src; // 真ん中をまたぐ = 1 つのモデル
    if (x < mid) part.push(src[i], src[i + 1], src[i + 2]);
    i += 3;
  }
  return part.length >= 3 ? part : src;
}

/** 上面 (Z = top) の平らな面の中心 (x, y)。 */
function topPad(src: V3[], top: number): [number, number] {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const p of src) {
    if (p.z < top - 0.05) continue;
    x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y);
  }
  return [(x0 + x1) * 0.5, (y0 + y1) * 0.5];
}

/** +X 面のくぼみ (面から 3〜6mm 奥の底面) の中心 (y, z)。見つからなければ全体の中心。 */
function findPocket(src: V3[], lo: V3, hi: V3): [number, number] {
  let y0 = Infinity, y1 = -Infinity, z0 = Infinity, z1 = -Infinity;
  for (const p of src) {
    if (p.x < hi.x - 6 || p.x > hi.x - 3 || p.z < lo.z + 3 || p.z > hi.z - 3) continue;
    y0 = Math.min(y0, p.y); y1 = Math.max(y1, p.y); z0 = Math.min(z0, p.z); z1 = Math.max(z1, p.z);
  }
  if (y0 > y1) return [(lo.y + hi.y) * 0.5, (lo.z + hi.z) * 0.5];
  return [(y0 + y1) * 0.5, (z0 + z1) * 0.5];
}

export type ModelFormat = "stl" | "obj" | "3mf";

/** モジュールのモデル (STL / OBJ / 3MF) を読み、取り付け面ローカル (+Y=外向き, +Z=正面、単位 mm) のメッシュにする。 */
export function parseModule(bytes: Uint8Array, format: ModelFormat, axis: MountAxis): MeshData | null {
  const tris = format === "obj" ? parseObj(bytes) : format === "3mf" ? parse3mf(bytes) : parseStl(bytes);
  return build(tris, axis);
}

// ---------------------------------------------------------------- 本体モデル (.obj、usemtl で材質を分ける)

/**
 * 本体モデル (.obj、usemtl で材質を分ける) の読み込み。WebDemo の buildScaledInstance と同じく
 * 「中心を原点へ → 最大辺が 1 になるよう一括スケール → X 軸 -90° 回転 (前面を +Z へ)」した形で返す。
 * OBJ の座標 (右手系) をそのまま使う。
 */
export function parseBodyModel(b: Uint8Array): MeshData | null {
  const verts: V3[] = [];
  const vns: V3[] = [];
  const groups = new Map<string, { ps: V3[]; ns: (V3 | null)[] }>();
  let current = { ps: [] as V3[], ns: [] as (V3 | null)[] };
  groups.set("", current);
  const text = new TextDecoder("utf-8").decode(b);
  const polyP: V3[] = [];
  const polyN: (V3 | null)[] = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line.length === 0 || line[0] === "#") continue;
    const p = line.split(/\s+/);
    switch (p[0]) {
      case "v":
        verts.push(new V3(parseFloat(p[1]), parseFloat(p[2]), parseFloat(p[3])));
        break;
      case "vn":
        vns.push(new V3(parseFloat(p[1]), parseFloat(p[2]), parseFloat(p[3])));
        break;
      case "usemtl": {
        const name = p.length > 1 ? p[1] : "";
        let g = groups.get(name);
        if (!g) {
          g = { ps: [], ns: [] };
          groups.set(name, g);
        }
        current = g;
        break;
      }
      case "f": {
        polyP.length = 0;
        polyN.length = 0;
        for (let i = 1; i < p.length; i++) {
          const parts = p[i].split("/");
          const vi = parseInt(parts[0], 10);
          polyP.push(verts[vi > 0 ? vi - 1 : verts.length + vi]);
          const ni = parts.length >= 3 && parts[2].length > 0 ? parseInt(parts[2], 10) : 0;
          polyN.push(ni !== 0 ? vns[ni > 0 ? ni - 1 : vns.length + ni] : null);
        }
        // 扇形で分割 (本体は凸な面だけ)。凹んだ面があれば耳切り法
        if (polyP.length === 3 || polyN.some((n) => n == null)) {
          const out: V3[] = [];
          triangulate(polyP, out);
          current.ps.push(...out);
          for (let i = 0; i < out.length; i++) current.ns.push(null);
        } else {
          for (let i = 1; i < polyP.length - 1; i++) {
            current.ps.push(polyP[0], polyP[i], polyP[i + 1]);
            current.ns.push(polyN[0], polyN[i], polyN[i + 1]);
          }
        }
        break;
      }
    }
  }
  if (verts.length === 0) return null;
  let lo = verts[0];
  let hi = verts[0];
  for (const v of verts) {
    lo = V3.min(lo, v);
    hi = V3.max(hi, v);
  }
  const center = lo.add(hi).scale(0.5);
  const size = hi.sub(lo);
  let maxDim = Math.max(size.x, Math.max(size.y, size.z));
  if (maxDim <= 0) maxDim = 1;
  // rot = -90° まわり X 軸 (Q.euler(-PI/2, 0, 0) と同値、直接計算して誤差を避ける)
  const rot = (v: V3): V3 => new V3(v.x, v.z, -v.y);

  let total = 0;
  for (const g of groups.values()) total += g.ps.length;
  const positions = new Float32Array(total * 3);
  const normals = new Float32Array(total * 3);
  const outGroups: MeshGroup[] = [];
  let o = 0;
  for (const [name, g] of groups) {
    const { ps, ns } = g;
    if (ps.length === 0) continue;
    const start = o;
    for (let t = 0; t < ps.length / 3; t++) {
      const a = ps[t * 3], bb = ps[t * 3 + 1], c = ps[t * 3 + 2];
      const fn = bb.sub(a).cross(c.sub(a)).normalized();
      for (let k = 0; k < 3; k++) {
        const pp = rot(ps[t * 3 + k].sub(center).divide(maxDim));
        const nn = rot(ns[t * 3 + k] ?? fn);
        positions[o * 3] = pp.x; positions[o * 3 + 1] = pp.y; positions[o * 3 + 2] = pp.z;
        normals[o * 3] = nn.x; normals[o * 3 + 1] = nn.y; normals[o * 3 + 2] = nn.z;
        o++;
      }
    }
    outGroups.push(new MeshGroup(start, o - start, name));
  }
  const indices = new Uint32Array(total);
  for (let i = 0; i < total; i++) indices[i] = i;
  return new MeshData(positions, normals, indices, undefined, outGroups);
}
