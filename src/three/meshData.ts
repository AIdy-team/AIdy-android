import * as THREE from "three";
import { V3 } from "./math";

/**
 * 三角形メッシュ (頂点ごとに位置・法線、任意で UV)。indices は 3 つで 1 枚 (反時計回りが表)。
 * groups: 材質ごとの index の範囲 (start, count, 名前)。Kotlin版 ModelLoader.kt の MeshData と同じ構造。
 * three.js の BufferGeometry は頂点バッファそのものなので、ここでは「GPUに上げる前の中間データ」として
 * OBJ/STL/3MF パーサーと手続きジオメトリ (meshes.ts) の両方の出力形式を揃えるために使う。
 */
export class MeshGroup {
  constructor(public start: number, public count: number, public material: string) {}
}

export class MeshData {
  readonly groups: MeshGroup[];

  constructor(
    public positions: Float32Array,
    public normals: Float32Array,
    public indices: Uint32Array,
    public uvs?: Float32Array,
    groups?: MeshGroup[],
  ) {
    this.groups = groups ?? [new MeshGroup(0, indices.length, "")];
  }

  get vertexCount(): number {
    return this.positions.length / 3;
  }

  private boundsCache: { min: V3; max: V3 } | null = null;
  private bounds(): { min: V3; max: V3 } {
    if (this.boundsCache) return this.boundsCache;
    if (this.vertexCount === 0) {
      this.boundsCache = { min: V3.Zero, max: V3.Zero };
      return this.boundsCache;
    }
    let x0 = this.positions[0], y0 = this.positions[1], z0 = this.positions[2];
    let x1 = x0, y1 = y0, z1 = z0;
    for (let i = 0; i < this.vertexCount; i++) {
      const x = this.positions[i * 3], y = this.positions[i * 3 + 1], z = this.positions[i * 3 + 2];
      x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
      x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
    }
    this.boundsCache = { min: new V3(x0, y0, z0), max: new V3(x1, y1, z1) };
    return this.boundsCache;
  }

  get min(): V3 {
    return this.bounds().min;
  }
  get max(): V3 {
    return this.bounds().max;
  }

  /** three.js の BufferGeometry に変換する (複数グループは addGroup で多材質に対応)。 */
  toGeometry(): THREE.BufferGeometry {
    const geo = new THREE.BufferGeometry();
    geo.setAttribute("position", new THREE.BufferAttribute(this.positions, 3));
    geo.setAttribute("normal", new THREE.BufferAttribute(this.normals, 3));
    if (this.uvs) geo.setAttribute("uv", new THREE.BufferAttribute(this.uvs, 2));
    geo.setIndex(new THREE.BufferAttribute(this.indices, 1));
    if (this.groups.length > 1) {
      for (let i = 0; i < this.groups.length; i++) {
        const g = this.groups[i];
        geo.addGroup(g.start, g.count, i);
      }
    }
    return geo;
  }
}
