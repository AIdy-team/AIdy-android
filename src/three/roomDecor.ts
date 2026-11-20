import * as THREE from "three";
import { V3, Q, PI } from "./math";
import { Meshes } from "./meshes";
import { lit, unlit, meshFrom, emptyGroup } from "./gfx";
import { loadRoomModel, RoomModel } from "./moduleAssets";

const Floor = -0.65;
const BackZ = -2.8;
const S = 2.2;
const WallColor = 0xfff3ece2;
const FloorColor = 0xffdcb48a;
const SkyColor = 0xffbfe3fb;

/** 使う家具 (先読み用)。 */
export const RoomDecorNames = [
  "wall", "wallWindow", "loungeSofa", "pillowBlue", "bear", "lampRoundFloor", "pottedPlant",
  "rugRounded", "sideTable", "plantSmall1", "books", "bookcaseOpen",
];

interface PutOptions {
  y?: number;
  s?: number;
  yaw?: number;
  override?: Record<string, number>;
}

/**
 * ホームの部屋: 床・壁・窓と家具 (Kenney Furniture Kit, CC0 / assets/room)。Kotlin版 RoomDecor.kt のポート。
 * 家具は 1 = 床 1 枚の寸法で、正面が -Z 向き。S 倍して 180° 回し、奥の壁 (BackZ) に背を付けて並べる。
 * 本体 (1.1) がお散歩する範囲 (x ±1.1, z ±0.6) とラグの上は空けておく。家具は影を落とさない (受けるだけ)。
 * 家具のモデルは非同期で読み込み、読めたものから root に足していく。
 */
export class RoomDecor {
  private readonly mats = new Map<number, THREE.MeshStandardMaterial>();
  private readonly models = new Map<string, RoomModel | null>();
  readonly ready: Promise<void>;

  constructor(private readonly root: THREE.Group) {
    // 奥の壁 (タイルの外も同じ色)・床
    const wall = meshFrom(Meshes.plane(16, 7), this.mat(WallColor), "Wall");
    wall.position.set(0, Floor + 3.5, BackZ - 0.03);
    wall.receiveShadow = true;
    root.add(wall);
    const floor = meshFrom(Meshes.plane(16, 12), lit(FloorColor, 0, 0.55), "Floor");
    floor.position.set(0, Floor, -1);
    floor.quaternion.copy(Q.euler(-PI / 2, 0, 0).toThree());
    floor.receiveShadow = true;
    root.add(floor);

    this.ready = this.build();
  }

  private mat(argb: number): THREE.MeshStandardMaterial {
    let m = this.mats.get(argb);
    if (!m) {
      m = lit(argb, 0, 0.75);
      this.mats.set(argb, m);
    }
    return m;
  }

  private async build(): Promise<void> {
    const loaded = await Promise.all(RoomDecorNames.map((n) => loadRoomModel(n)));
    RoomDecorNames.forEach((n, i) => this.models.set(n, loaded[i]));

    // 壁: 真ん中に窓、左右に壁
    for (let i = -2; i <= 2; i++) {
      const name = i === 0 ? "wallWindow" : "wall";
      this.put(name, i * S, this.backed(name), { override: { _defaultMat: WallColor } });
    }
    this.sky();

    // ソファ (窓の下) + クッション + くま
    this.put("loungeSofa", 0, this.backed("loungeSofa"));
    const seat = Floor + 0.23 * S;
    this.put("pillowBlue", -0.65, BackZ + 0.3, { y: seat, yaw: PI * 0.92 });
    this.put("bear", 0.62, BackZ + 0.42, { y: seat, s: 1.5, yaw: PI * 1.1 });

    // ソファの左右: スタンドライト・観葉植物
    this.put("lampRoundFloor", -1.5, this.backed("lampRoundFloor") + 0.1);
    this.put("pottedPlant", 1.55, this.backed("pottedPlant") + 0.15);

    // ラグ (お散歩する場所)
    this.put("rugRounded", 0, 0.05, { y: Floor + 0.002, override: { carpet: 0xffb9d3ef, carpetDarker: 0xff8fb2dc } });

    // 外側 (引くと見える): 左にサイドテーブル + 小物、右に本棚
    this.put("sideTable", -2.6, this.backed("sideTable"));
    const table = Floor + 0.38 * S;
    this.put("plantSmall1", -2.9, BackZ + 0.25, { y: table });
    this.put("books", -2.35, BackZ + 0.25, { y: table, yaw: PI * 0.9 });
    this.put("bookcaseOpen", 2.65, this.backed("bookcaseOpen"));
  }

  /** 奥の壁に背を付けたときの中心の z。 */
  private backed(name: string, s = S): number {
    const m = this.models.get(name);
    if (!m) return BackZ + 0.5;
    return BackZ + (m.mesh.max.z - m.mesh.min.z) * s * 0.5 + 0.01;
  }

  /** 家具を置く。(x, z) = 底面の中心、y = 底の高さ、yaw = 向き (π で正面がカメラ側)。override = 材質ごとの色の差し替え。 */
  private put(name: string, x: number, z: number, o: PutOptions = {}): void {
    const m = this.models.get(name);
    if (!m) return;
    const { y = Floor, s = S, yaw = PI, override = {} } = o;
    const lo = m.mesh.min;
    const hi = m.mesh.max;
    const pivot = emptyGroup(name);
    pivot.position.set(x, y, z);
    pivot.quaternion.copy(Q.euler(0, yaw, 0).toThree());
    pivot.scale.setScalar(s);
    const materials = m.mesh.groups.map((g) => {
      const color = override[g.material] ?? m.colors[g.material] ?? 0xffcccccc;
      return g.material === "glass" ? lit(((color & 0xffffff) | 0x55000000) >>> 0, 0, 0.1) : this.mat(color);
    });
    const mesh = meshFrom(m.mesh, materials.length > 1 ? materials : materials[0] ?? this.mat(0xffcccccc), name);
    mesh.position.copy(new V3(-(lo.x + hi.x) * 0.5, -lo.y, -(lo.z + hi.z) * 0.5).toThree());
    mesh.receiveShadow = true;
    pivot.add(mesh);
    this.root.add(pivot);
  }

  /** 窓の向こうの空 (窓ガラスの範囲より少し大きい板を壁の裏に置く)。 */
  private sky(): void {
    const m = this.models.get("wallWindow");
    if (!m) return;
    const g = m.mesh.groups.find((x) => x.material === "glass");
    if (!g) return;
    let lx = Infinity, hx = -Infinity, ly = Infinity, hy = -Infinity;
    for (let k = g.start; k < g.start + g.count; k++) {
      const v = m.mesh.indices[k];
      lx = Math.min(lx, m.mesh.positions[v * 3]); hx = Math.max(hx, m.mesh.positions[v * 3]);
      ly = Math.min(ly, m.mesh.positions[v * 3 + 1]); hy = Math.max(hy, m.mesh.positions[v * 3 + 1]);
    }
    if (hx < lx) return;
    const cx = -((lx + hx) * 0.5 - (m.mesh.min.x + m.mesh.max.x) * 0.5) * S; // 180° 回すので左右反転
    const cy = Floor + (ly + hy) * 0.5 * S;
    const sky = meshFrom(Meshes.plane((hx - lx) * S + 0.3, (hy - ly) * S + 0.3), unlit(SkyColor), "Sky");
    sky.position.set(cx, cy, BackZ - 0.015);
    this.root.add(sky);
  }
}
