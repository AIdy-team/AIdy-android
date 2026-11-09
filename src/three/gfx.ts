import * as THREE from "three";
import { MeshData } from "./meshData";

/**
 * three.js のメッシュ・材質の生成ヘルパー (Kotlin版 Gfx.kt に相当)。
 * Filament 版は頂点バッファのGPUアップロードやマテリアルインスタンスのキャッシュを自分で管理する必要があったが、
 * three.js の BufferGeometry/Material はそれ自体がGPUリソースのラッパーなので、ここでは単純な生成関数で十分。
 */

/** 0xAARRGGBB or 0xRRGGBB → THREE.Color + 不透明度。 */
export function colorOf(argb: number): { color: THREE.Color; opacity: number } {
  const a = ((argb >>> 24) & 0xff) / 255;
  const rgb = argb & 0xffffff;
  return { color: new THREE.Color(rgb), opacity: a === 0 ? 1 : a };
}

/** MeshStandardMaterial(color, metalness, roughness)。argb のアルファが 1 未満なら半透明。 */
export function lit(argb: number, metallic: number, roughness: number): THREE.MeshStandardMaterial {
  const { color, opacity } = colorOf(argb);
  return new THREE.MeshStandardMaterial({
    color,
    metalness: metallic,
    roughness,
    transparent: opacity < 1,
    opacity,
  });
}

export function litRgb(r: number, g: number, b: number, metallic: number, roughness: number): THREE.MeshStandardMaterial {
  return new THREE.MeshStandardMaterial({ color: new THREE.Color(r, g, b), metalness: metallic, roughness });
}

/** 照明なしの単色 (MeshBasicMaterial)。 */
export function unlit(argb: number): THREE.MeshBasicMaterial {
  const { color, opacity } = colorOf(argb);
  return new THREE.MeshBasicMaterial({ color, transparent: opacity < 1, opacity });
}

/** 照明なしのテクスチャ材質。 */
export function unlitTexture(tex: THREE.Texture): THREE.MeshBasicMaterial {
  return new THREE.MeshBasicMaterial({ map: tex, transparent: true });
}

/** w x h の RGBA テクスチャ (DataTexture)。 */
export function makeTexture(w: number, h: number): THREE.DataTexture {
  const data = new Uint8Array(w * h * 4);
  const tex = new THREE.DataTexture(data, w, h, THREE.RGBAFormat);
  tex.needsUpdate = true;
  return tex;
}

/** ARGB (上の行から) を DataTexture に書き込む。上の行が v=0 になるよう上下反転する (three.js は下の行が v=0)。 */
export function uploadArgb(tex: THREE.DataTexture, w: number, h: number, argb: Int32Array | number[]): void {
  const data = tex.image.data as Uint8Array;
  for (let y = 0; y < h; y++) {
    const srcRow = y * w;
    const dstRow = (h - 1 - y) * w;
    for (let x = 0; x < w; x++) {
      const c = argb[srcRow + x];
      const o = (dstRow + x) * 4;
      data[o] = (c >> 16) & 0xff;
      data[o + 1] = (c >> 8) & 0xff;
      data[o + 2] = c & 0xff;
      data[o + 3] = (c >>> 24) & 0xff;
    }
  }
  tex.needsUpdate = true;
}

export function meshFrom(data: MeshData, material: THREE.Material | THREE.Material[], name?: string): THREE.Mesh {
  const mesh = new THREE.Mesh(data.toGeometry(), material);
  if (name) mesh.name = name;
  return mesh;
}

export function emptyGroup(name?: string): THREE.Group {
  const g = new THREE.Group();
  if (name) g.name = name;
  return g;
}
