import { zlibSync } from "fflate";
import { V3, Pose, Q, uRot, Deg2Rad } from "./math";
import { Meshes } from "./meshes";
import { MeshData } from "./meshData";
import { AidyColors, buildModuleShape } from "./rig";
import { loadBodyModel } from "./moduleAssets";
import { BodyLayout, BodySlot, ModuleCatalog, ModuleKind } from "../data/modules";

/**
 * モジュール一覧の丸いプレビュー画像 (ModulePreview.kt のポート)。モジュールの形を CPU で描く (Z バッファ + 平行光)。
 * 顔は本体v2_顔ありを正面寄りから、ほかは本体に付いたときと同じ向きで斜め横から見る。
 * 結果は PNG の data URI (Image の source にそのまま渡せる)。GL のコンテキストを増やさないので Expo Go でも軽い。
 */
const Fov = 28;

interface Entry {
  /** 三角形ごとに 3 頂点 × (位置 xyz)。 */
  pos: Float32Array;
  /** 三角形ごとに 3 頂点 × (法線 xyz)。 */
  nrm: Float32Array;
  /** 三角形ごとの材質 (mats の番号)。 */
  mat: Uint8Array;
  count: number;
  center: V3;
  radius: number;
  viewDir: V3;
  bg: [number, number, number];
}

interface Mat {
  color: [number, number, number];
  metallic: number;
  roughness: number;
  unlit: boolean;
}

const mats: Mat[] = [];
function matIndex(argb: number, metallic: number, roughness: number, unlit = false): number {
  const color: [number, number, number] = [((argb >> 16) & 0xff) / 255, ((argb >> 8) & 0xff) / 255, (argb & 0xff) / 255];
  const i = mats.findIndex(
    (m) => m.color[0] === color[0] && m.color[1] === color[1] && m.color[2] === color[2] && m.metallic === metallic && m.roughness === roughness && m.unlit === unlit,
  );
  if (i >= 0) return i;
  mats.push({ color, metallic, roughness, unlit });
  return mats.length - 1;
}

const entries = new Map<ModuleKind, Promise<Entry>>();
const lightDir = new V3(4, 8, 5).normalized();

/** 代表のスロット: 耳・帽子・テレビ=上面, 尻尾=背面, 足=底面, 手=左面。 */
function sampleSlot(kind: ModuleKind): BodySlot | null {
  const face =
    kind === "Ear" || kind === "Hat" || kind === "Tv" ? "top" : ModuleCatalog.isTail(kind) ? "back" : kind === "Legs" ? "bottom" : "left";
  return (
    BodyLayout.onFace(face).find((s) => ModuleCatalog.fits(kind, s)) ?? BodyLayout.slots.find((s) => ModuleCatalog.fits(kind, s)) ?? null
  );
}

async function build(kind: ModuleKind): Promise<Entry> {
  const P: number[] = [];
  const N: number[] = [];
  const M: number[] = [];
  const add = (mesh: MeshData, pose: Pose, mi: number, start = 0, count = mesh.indices.length) => {
    const sInv = new V3(1 / pose.s.x, 1 / pose.s.y, 1 / pose.s.z);
    for (let t = start; t + 2 < start + count; t += 3) {
      for (let k = 0; k < 3; k++) {
        const i = mesh.indices[t + k];
        const p = pose.point(new V3(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]));
        const n = pose.dir(new V3(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2]).mul(sInv)).normalized();
        P.push(p.x, p.y, p.z);
        N.push(n.x, n.y, n.z);
      }
      M.push(mi);
    }
  };

  if (kind === "Face") {
    // 顔モジュールは本体v2_顔ありに切り替わるので、その本体を見せる
    const body = await loadBodyModel(true);
    if (body) {
      for (const g of body.groups) {
        const enamel = /EnamelWhite/i.test(g.material);
        add(body, new Pose(), enamel ? matIndex(AidyColors.EnamelWhite, 0, 0.15) : matIndex(AidyColors.SteelSatin, 0.35, 0.4), g.start, g.count);
      }
    } else add(Meshes.circle(0.3, 32), new Pose(), matIndex(0xff3b82f6, 0, 1, true));
  } else {
    // 本体に付いたときと同じ向きにする
    const slot = sampleSlot(kind);
    const mount = new Pose(V3.Zero, slot?.mountRotation ?? Q.Identity);
    const shape = await buildModuleShape(kind, slot, 1);
    for (const part of shape.parts) {
      const pivot = part.pivot >= 0 ? shape.pivots[part.pivot] : new Pose();
      const pose = mount.combine(pivot).combine(part.local);
      add(part.mesh, pose, part.mat === "Lens" ? matIndex(AidyColors.Lens, 0, 1, true) : matIndex(AidyColors.Satin, 0.55, 0.35));
    }
  }

  const pos = new Float32Array(P);
  let lx = Infinity, ly = Infinity, lz = Infinity, hx = -Infinity, hy = -Infinity, hz = -Infinity;
  for (let i = 0; i < pos.length; i += 3) {
    lx = Math.min(lx, pos[i]); ly = Math.min(ly, pos[i + 1]); lz = Math.min(lz, pos[i + 2]);
    hx = Math.max(hx, pos[i]); hy = Math.max(hy, pos[i + 1]); hz = Math.max(hz, pos[i + 2]);
  }
  const empty = pos.length === 0;
  const center = empty ? V3.Zero : new V3((lx + hx) / 2, (ly + hy) / 2, (lz + hz) / 2);
  const radius = empty ? 0.2 : Math.max(new V3((hx - lx) / 2, (hy - ly) / 2, (hz - lz) / 2).length, 0.01);
  // 背景はモジュールの色をうすく混ぜた白
  const tint = (ModuleCatalog.tint(kind).match(/\d+/g) ?? ["255", "255", "255"]).map(Number); // "rgb(r, g, b)"
  const mix = (c: number) => Math.round(255 + (c - 255) * 0.12);
  const bg: [number, number, number] = [mix(tint[0]), mix(tint[1]), mix(tint[2])];
  // 顔は正面から、ほかは斜め横から (尻尾の反り・手の長さが分かる向き)
  const viewDir = (kind === "Face" ? new V3(-0.45, 0.3, 1) : new V3(-1, 0.55, 0.75)).normalized();
  return { pos, nrm: new Float32Array(N), mat: new Uint8Array(M), count: M.length, center, radius, viewDir, bg };
}

function ensure(kind: ModuleKind): Promise<Entry> {
  let e = entries.get(kind);
  if (!e) {
    e = build(kind);
    entries.set(kind, e);
  }
  return e;
}

const srgbToLinear = (c: number) => Math.pow(c, 2.2);
const linearToSrgb = (c: number) => Math.pow(Math.min(1, Math.max(0, c)), 1 / 2.2);

/** 平行光 (強さ 0.95) + 環境光 + 鏡面反射 (金属感)。返す色は sRGB (0〜1)。out に書く。 */
function shade(m: Mat, nx: number, ny: number, nz: number, vx: number, vy: number, vz: number, out: Float32Array, o: number): void {
  if (m.unlit) {
    out[o] = m.color[0]; out[o + 1] = m.color[1]; out[o + 2] = m.color[2];
    return;
  }
  if (nx * vx + ny * vy + nz * vz < 0) {
    nx = -nx; ny = -ny; nz = -nz;
  }
  const L = lightDir;
  const diff = Math.max(0, nx * L.x + ny * L.y + nz * L.z) * 0.95;
  const ambient = 0.45;
  let hx = L.x + vx, hy = L.y + vy, hz = L.z + vz;
  const hl = Math.hypot(hx, hy, hz) || 1;
  hx /= hl; hy /= hl; hz /= hl;
  const shininess = 2 / Math.max(0.02, m.roughness * m.roughness) - 2;
  const spec = (Math.pow(Math.max(0, nx * hx + ny * hy + nz * hz), shininess) * (0.04 + 0.6 * m.metallic) * (shininess + 8)) / 25;
  const kd = 1 - m.metallic * 0.6;
  const k = (diff + ambient) * kd;
  const s = spec * (0.5 + 0.5 * diff);
  out[o] = linearToSrgb(srgbToLinear(m.color[0]) * k + s);
  out[o + 1] = linearToSrgb(srgbToLinear(m.color[1]) * k + s);
  out[o + 2] = linearToSrgb(srgbToLinear(m.color[2]) * k + s);
}

/** 縦軸まわりに yaw 度回した向きで描き、RGB の画素 (px * px * 3) を返す。 */
async function renderRgb(kind: ModuleKind, yawDeg: number, px: number): Promise<Uint8Array> {
  const e = await ensure(kind);
  const rgb = new Uint8Array(px * px * 3);
  for (let i = 0; i < px * px; i++) {
    rgb[i * 3] = e.bg[0]; rgb[i * 3 + 1] = e.bg[1]; rgb[i * 3 + 2] = e.bg[2];
  }
  const depth = new Float32Array(px * px).fill(Infinity);
  const rot = uRot(yawDeg, V3.Up);
  // 外接球が画角に収まる距離 (少し余白を残す)
  const dist = (e.radius / Math.sin(Fov * 0.5 * Deg2Rad)) * 1.08;
  const eye = e.viewDir.scale(dist);
  const f = e.viewDir.negate().normalized();
  const r = f.cross(V3.Up).normalized();
  const u = r.cross(f);
  const t = Math.tan(Fov * 0.5 * Deg2Rad);
  const half = px * 0.5;
  const sx = new Float32Array(3), sy = new Float32Array(3), sz = new Float32Array(3);
  const col = new Float32Array(9);
  // 頂点を回して画面へ写す (三角形ごとに同じ頂点を何度も回さないよう、先にまとめて計算)
  const n = e.count * 3;
  const wx = new Float32Array(n), wy = new Float32Array(n), wz = new Float32Array(n);
  const rx = new Float32Array(n), ry = new Float32Array(n), rz = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const w = rot.rotate(new V3(e.pos[i * 3] - e.center.x, e.pos[i * 3 + 1] - e.center.y, e.pos[i * 3 + 2] - e.center.z));
    wx[i] = w.x; wy[i] = w.y; wz[i] = w.z;
    const nn = rot.rotate(new V3(e.nrm[i * 3], e.nrm[i * 3 + 1], e.nrm[i * 3 + 2]));
    rx[i] = nn.x; ry[i] = nn.y; rz[i] = nn.z;
  }
  for (let tri = 0; tri < e.count; tri++) {
    let behind = false;
    for (let k = 0; k < 3; k++) {
      const i = tri * 3 + k;
      const dx = wx[i] - eye.x, dy = wy[i] - eye.y, dz = wz[i] - eye.z;
      const z = dx * f.x + dy * f.y + dz * f.z;
      if (z <= 0.001) behind = true;
      sz[k] = z;
      sx[k] = half + ((dx * r.x + dy * r.y + dz * r.z) / (z * t)) * half;
      sy[k] = half - ((dx * u.x + dy * u.y + dz * u.z) / (z * t)) * half;
    }
    if (behind) continue;
    // 裏面は描かない (画面座標は y 下向きなので、表 = 時計回り)
    const area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sy[1] - sy[0]) * (sx[2] - sx[0]);
    if (area >= 0) continue;
    const minX = Math.max(0, Math.floor(Math.min(sx[0], sx[1], sx[2])));
    const maxX = Math.min(px - 1, Math.ceil(Math.max(sx[0], sx[1], sx[2])));
    const minY = Math.max(0, Math.floor(Math.min(sy[0], sy[1], sy[2])));
    const maxY = Math.min(px - 1, Math.ceil(Math.max(sy[0], sy[1], sy[2])));
    if (minX > maxX || minY > maxY) continue;
    // 頂点ごとに明るさを計算 (グーロー)
    const m = mats[e.mat[tri]];
    for (let k = 0; k < 3; k++) {
      const i = tri * 3 + k;
      let vx = eye.x - wx[i], vy = eye.y - wy[i], vz = eye.z - wz[i];
      const vl = Math.hypot(vx, vy, vz) || 1;
      vx /= vl; vy /= vl; vz /= vl;
      shade(m, rx[i], ry[i], rz[i], vx, vy, vz, col, k * 3);
    }
    const inv = 1 / area;
    for (let y = minY; y <= maxY; y++) {
      const py = y + 0.5;
      for (let x = minX; x <= maxX; x++) {
        const pxf = x + 0.5;
        const w0 = ((sx[1] - pxf) * (sy[2] - py) - (sy[1] - py) * (sx[2] - pxf)) * inv;
        const w1 = ((sx[2] - pxf) * (sy[0] - py) - (sy[2] - py) * (sx[0] - pxf)) * inv;
        const w2 = 1 - w0 - w1;
        if (w0 < 0 || w1 < 0 || w2 < 0) continue;
        const z = w0 * sz[0] + w1 * sz[1] + w2 * sz[2];
        const idx = y * px + x;
        if (z >= depth[idx]) continue;
        depth[idx] = z;
        const o = idx * 3;
        rgb[o] = Math.round((w0 * col[0] + w1 * col[3] + w2 * col[6]) * 255);
        rgb[o + 1] = Math.round((w0 * col[1] + w1 * col[4] + w2 * col[7]) * 255);
        rgb[o + 2] = Math.round((w0 * col[2] + w1 * col[5] + w2 * col[8]) * 255);
      }
    }
  }
  return rgb;
}

/** モジュールのプレビュー (PNG の data URI)。px = 一辺の画素数。 */
export async function renderModulePreview(kind: ModuleKind, yawDeg: number, px: number): Promise<string> {
  return "data:image/png;base64," + base64(encodePng(await renderRgb(kind, yawDeg, px), px, px));
}

// ---------------------------------------------------------------- PNG (RGB 8bit、フィルタなし)

const crcTable = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(b: Uint8Array, start: number, end: number): number {
  let c = 0xffffffff;
  for (let i = start; i < end; i++) c = crcTable[(c ^ b[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function encodePng(rgb: Uint8Array, w: number, h: number): Uint8Array {
  const raw = new Uint8Array((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0; // フィルタなし
    raw.set(rgb.subarray(y * w * 3, (y + 1) * w * 3), y * (w * 3 + 1) + 1);
  }
  const idat = zlibSync(raw, { level: 3 });
  const chunks: Array<[string, Uint8Array]> = [
    ["IHDR", new Uint8Array([w >>> 24, (w >>> 16) & 0xff, (w >>> 8) & 0xff, w & 0xff, h >>> 24, (h >>> 16) & 0xff, (h >>> 8) & 0xff, h & 0xff, 8, 2, 0, 0, 0])],
    ["IDAT", idat],
    ["IEND", new Uint8Array(0)],
  ];
  let size = 8;
  for (const [, d] of chunks) size += 12 + d.length;
  const out = new Uint8Array(size);
  out.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 0);
  let o = 8;
  for (const [type, d] of chunks) {
    const len = d.length;
    out[o] = len >>> 24; out[o + 1] = (len >>> 16) & 0xff; out[o + 2] = (len >>> 8) & 0xff; out[o + 3] = len & 0xff;
    for (let i = 0; i < 4; i++) out[o + 4 + i] = type.charCodeAt(i);
    out.set(d, o + 8);
    const c = crc32(out, o + 4, o + 8 + len);
    out[o + 8 + len] = c >>> 24; out[o + 9 + len] = (c >>> 16) & 0xff; out[o + 10 + len] = (c >>> 8) & 0xff; out[o + 11 + len] = c & 0xff;
    o += 12 + len;
  }
  return out;
}

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function base64(b: Uint8Array): string {
  const parts: string[] = [];
  let s = "";
  for (let i = 0; i < b.length; i += 3) {
    const n = (b[i] << 16) | ((b[i + 1] ?? 0) << 8) | (b[i + 2] ?? 0);
    s += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + (i + 1 < b.length ? B64[(n >> 6) & 63] : "=") + (i + 2 < b.length ? B64[n & 63] : "=");
    if (s.length > 4096) {
      parts.push(s);
      s = "";
    }
  }
  parts.push(s);
  return parts.join("");
}
