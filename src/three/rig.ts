import * as THREE from "three";
import { V3, Q, Pose, uPos, uRot } from "./math";
import { Meshes } from "./meshes";
import { MeshData } from "./meshData";
import { lit, unlit, meshFrom, emptyGroup, colorOf } from "./gfx";
import { loadModuleModel, loadBodyModel, peekModuleModel } from "./moduleAssets";
import { BodyLayout, BodySlot, ModuleCatalog, ModuleConfig, ModuleKind } from "../data/modules";
import { BodyColor, ColorPattern } from "../data/bodyColor";

/** 本体・モジュールの材質の色 (0xAARRGGBB)。Rig.kt の AidyColors と同じ。 */
export const AidyColors = {
  Satin: 0xffa0a0a0,
  SteelSatin: 0xffa0a0a0,
  EnamelWhite: 0xfff6f6f3,
  Lens: 0xfffef9c3,
};

export type ShapeMat = "Satin" | "Lens";
export class ShapePart {
  constructor(public mesh: MeshData, public pivot: number, public local: Pose, public mat: ShapeMat) {}
}
export class ModuleShape {
  constructor(public pivots: Pose[], public parts: ShapePart[]) {}
}

const fallbackCache = new Map<string, MeshData>();
function cached(key: string, make: () => MeshData): MeshData {
  let m = fallbackCache.get(key);
  if (!m) {
    m = make();
    fallbackCache.set(key, m);
  }
  return m;
}

/** Unity 版 AidyModuleRig.BuildModule と同じ形・向き。slot は向きの調整に使う (null なら背面スロット向き)。 */
export async function buildModuleShape(kind: ModuleKind, slot: BodySlot | null, size: number): Promise<ModuleShape> {
  const mm = size / 150;
  const k = size / 1.1;
  const pivots: Pose[] = [];
  const parts: ShapePart[] = [];

  switch (kind) {
    case "Hand":
    case "HandLight": {
      // 手: マグネットのくぼみを本体に当て、本体の側面に沿って正面へ伸びる。くぼみを軸に上下に振る
      pivots.push(new Pose());
      const hand = await loadModuleModel(kind);
      if (hand) {
        parts.push(new ShapePart(hand, 0, new Pose(V3.Zero, Q.Identity, new V3(mm, mm, mm)), "Satin"));
        if (kind === "HandLight") {
          // 懐中電灯のレンズ (長手方向の後ろ端) を光らせる
          const c = hand.min.add(hand.max).scale(0.5);
          const lens = cached("lens", () => Meshes.circle(5.5, 24));
          parts.push(
            new ShapePart(
              lens,
              0,
              new Pose(new V3(c.x, c.y, hand.min.z - 0.3).scale(mm), uRot(180, V3.Up), new V3(mm, mm, mm)),
              "Lens",
            ),
          );
        }
      } else {
        const cyl = cached("hand", () => Meshes.cylinder(15, 15, 80, 16));
        parts.push(new ShapePart(cyl, 0, new Pose(uPos(0, 15, 25).scale(mm), uRot(90, V3.Right), new V3(mm, mm, mm)), "Satin"));
      }
      break;
    }
    case "Hat":
    case "Tv": {
      // シルクハット・ブラウン管テレビ: 平らな底を上面に置くだけ (動かない)
      const hat = await loadModuleModel(kind);
      if (hat) parts.push(new ShapePart(hat, -1, new Pose(V3.Zero, Q.Identity, new V3(mm, mm, mm)), "Satin"));
      else {
        const cyl = cached("hat", () => Meshes.cylinder(25, 25, 35, 24));
        parts.push(new ShapePart(cyl, -1, new Pose(new V3(0, 17.5 * mm, 0), Q.Identity, new V3(mm, mm, mm)), "Satin"));
      }
      break;
    }
    case "Ear": {
      const ear = await loadModuleModel(kind);
      if (ear) parts.push(new ShapePart(ear, -1, new Pose(V3.Zero, Q.Identity, new V3(mm, mm, mm)), "Satin"));
      else {
        const cyl = cached("ear", () => Meshes.cylinder(0.08, 0.14, 0.3, 16));
        parts.push(new ShapePart(cyl, -1, new Pose(new V3(0, 0.15 * k, 0), Q.Identity, new V3(k, k, k)), "Satin"));
      }
      break;
    }
    case "Tail45":
    case "TailCurl": {
      // 根元で左右に振る (wag)。台座をスロットに当て、+Z (背面なら上) へ反る
      pivots.push(new Pose());
      const tail = await loadModuleModel(kind);
      if (tail) {
        // 差し込み部の長手 (モデルの X) をスロットの長手に合わせる。上面のスロットは前後向きなので横へ反る
        const longLocal = slot ? slot.mountRotation.inverse().rotate(slot.longAxis) : V3.Right;
        const r = Math.abs(longLocal.z) > 0.5 ? uRot(90, V3.Up) : Q.Identity;
        parts.push(new ShapePart(tail, 0, new Pose(V3.Zero, r, new V3(mm, mm, mm)), "Satin"));
      } else {
        const tilt = uRot(35, V3.Right);
        const cyl = cached("tail", () => Meshes.cylinder(0.03, 0.04, 0.45, 16));
        parts.push(new ShapePart(cyl, 0, new Pose(tilt.rotate(new V3(0, 0.2 * k, 0)), tilt, new V3(k, k, k)), "Satin"));
      }
      break;
    }
    case "Legs": {
      // 足v1.3mf: 1 スロットに 1 つ。上面 (本体に当たる面) を pivot にして前後に振る
      const foot = await loadModuleModel(kind);
      if (foot) {
        pivots.push(new Pose());
        parts.push(new ShapePart(foot, 0, new Pose(V3.Zero, Q.Identity, new V3(mm, mm, mm)), "Satin"));
      } else {
        const cyl = cached("leg", () => Meshes.cylinder(0.08, 0.1, 0.28, 16));
        for (const z of [0.35, -0.35]) {
          pivots.push(new Pose(new V3(0, 0, z * k)));
          parts.push(new ShapePart(cyl, pivots.length - 1, new Pose(new V3(0, 0.1 * k, 0), Q.Identity, new V3(k, k, k)), "Satin"));
        }
      }
      break;
    }
    case "Face":
      break;
  }
  return new ModuleShape(pivots, parts);
}

/** 顔あり/顔なしの両モデル。顔パーツの着脱で表示を切り替える。 */
class BodyVariants {
  withFace: THREE.Group | null = null;
  plain: THREE.Group | null = null;
  private ready: Promise<void>;
  /** 外装 (色を変える部分) の材質。 */
  private readonly shellMats: THREE.MeshStandardMaterial[] = [];

  constructor(private parent: THREE.Group, private targetSize: number, private color: number) {
    this.ready = this.build();
  }

  /** 外装の色を変える (0xffRRGGBB)。 */
  setColor(argb: number): void {
    this.color = argb;
    const { color } = colorOf(argb);
    for (const m of this.shellMats) m.color.copy(color);
  }

  get any(): boolean {
    return this.withFace != null || this.plain != null;
  }

  async whenReady(): Promise<void> {
    await this.ready;
  }

  private async build(): Promise<void> {
    const [face, plain] = await Promise.all([loadBodyModel(true), loadBodyModel(false)]);
    this.withFace = face ? buildBodyVariant(face, this.targetSize, this.color, this.shellMats) : null;
    this.plain = plain ? buildBodyVariant(plain, this.targetSize, this.color, this.shellMats) : null;
    if (this.withFace) this.parent.add(this.withFace);
    if (this.plain) this.parent.add(this.plain);
  }

  /** 戻り値は顔ありモデルを表示したか。 */
  setFace(on: boolean): boolean {
    const useFace = on && this.withFace != null;
    if (this.withFace) this.withFace.visible = useFace;
    if (this.plain) this.plain.visible = !useFace;
    return useFace;
  }
}

function buildBodyVariant(data: MeshData, targetSize: number, color: number, shellMats: THREE.MeshStandardMaterial[]): THREE.Group {
  const pivot = emptyGroup("BodyPivot");
  pivot.scale.setScalar(targetSize);
  const shell = () => {
    const m = lit(color, 0.35, 0.4);
    shellMats.push(m);
    return m;
  };
  const materials = data.groups.map((g) => (/EnamelWhite/i.test(g.material) ? lit(AidyColors.EnamelWhite, 0, 0.15) : shell()));
  pivot.add(meshFrom(data, data.groups.length > 1 ? materials : materials[0] ?? shell()));
  // 外装 (外形150mm・厚さ5mm) の内側の空洞を黒い箱で埋め、穴から中身が見えないようにする
  const box = meshFrom(cached("cube", () => Meshes.cube()), unlit(0xff000000), "InteriorOccluder");
  const s = 139.8 / 150;
  box.scale.setScalar(s);
  pivot.add(box);
  return pivot;
}

interface Mount {
  kind: ModuleKind;
  root: THREE.Group;
  swing: THREE.Group[];
  swingBase: Pose[];
  swingNow: Pose[];
  /** 見た目の外形 (各部品の箱の角)。pivot = 振る部分の番号 (-1 は取り付け面)、v = その座標系での点。 */
  hull: Array<{ pivot: number; v: V3 }>;
}

export interface SlotFrame {
  slot: BodySlot;
  node: THREE.Group;
  marker?: THREE.Mesh;
  markerMat?: THREE.MeshBasicMaterial;
}

/**
 * 本体 (顔あり/顔なし) + ModuleConfig のモジュールを 3D で組み立てる。ホーム・付け替えプレビュー・AR で共通 (Rig.kt の ModuleRig)。
 * 寸法は body_v2_layout.json: 本体 150mm 角 = size (シーン単位)。
 * editable のときは、面の選択ハイライトとスロットの目印 (ドロップ先) も作る。
 */
export class ModuleRig {
  private readonly k: number;
  private readonly lens = unlit(AidyColors.Lens);
  readonly bodies: BodyVariants;
  /** いま表示している色のパターン。 */
  pattern: ColorPattern;
  /** 取り付け口ごとのパーツの材質 (パーツを付け替えても使い回す)。 */
  private readonly slotMats = new Map<string, THREE.MeshStandardMaterial>();
  /** いまの色に従うとき: BodyColor が変わったら外装の色を変える。release で外す。 */
  private readonly colorListener: ((p: ColorPattern) => void) | null;

  private readonly mounts = new Map<string, Mount>();
  private readonly frames = new Map<string, SlotFrame>();

  faceOn = false;
  private syncGeneration = 0;
  private selectedFace: string | null = null;
  private dropKind: ModuleKind | null = null;
  private dropHover: string | null = null;
  private highlight: THREE.Mesh | null = null;

  constructor(
    public body: THREE.Group,
    private size: number,
    private editable: boolean,
    /** 決まった構成で組む (名刺マーカーの標準 Aidy)。null なら ModuleConfig (いまの着せ替え) に従う。 */
    private fixedSlots: Map<string, ModuleKind> | null = null,
    /** 決まった色のパターン (着せ替えマーカー)。null なら BodyColor.pattern (いまの色) に従う。 */
    fixedColor: ColorPattern | null = null,
  ) {
    this.k = size / 1.1;
    this.pattern = fixedColor ?? BodyColor.pattern;
    this.bodies = new BodyVariants(body, size, this.pattern.body);
    this.buildFrames();
    if (fixedColor == null) {
      this.colorListener = (p) => this.applyPattern(p);
      BodyColor.changed.push(this.colorListener);
    } else this.colorListener = null;
  }

  private slotMat(id: string): THREE.MeshStandardMaterial {
    let m = this.slotMats.get(id);
    if (!m) {
      m = lit(this.pattern.partColor(id), 0.55, 0.35);
      this.slotMats.set(id, m);
    }
    return m;
  }

  /** 本体とパーツの色を変える (選んでいる途中の見た目にも使う)。 */
  applyPattern(p: ColorPattern): void {
    this.pattern = p;
    this.bodies.setColor(p.body);
    for (const [id, m] of this.slotMats) m.color.copy(colorOf(p.partColor(id)).color);
  }

  /** このリグを捨てるとき (色の通知を外す)。 */
  release(): void {
    if (!this.colorListener) return;
    const i = BodyColor.changed.indexOf(this.colorListener);
    if (i >= 0) BodyColor.changed.splice(i, 1);
  }

  /** 取り付け済みのパーツ (取り付け口 → 種類)。 */
  get attached(): Map<string, ModuleKind> {
    return new Map(Array.from(this.mounts.entries()).map(([id, m]) => [id, m.kind] as [string, ModuleKind]));
  }

  /**
   * 取り付け済みモジュールの外形の点 (取り付け面ローカル)。付いていなければ空。
   * 尻尾・手・足は今振っている角度で返す (画面でつかむ判定が動きについていくように)。
   */
  hullOf(slotId: string): V3[] {
    const m = this.mounts.get(slotId);
    if (!m) return [];
    return m.hull.map(({ pivot, v }) => (pivot < 0 ? v : m.swingNow[pivot].point(v)));
  }

  get hasModel(): boolean {
    return this.bodies.any;
  }
  get frameList(): SlotFrame[] {
    return Array.from(this.frames.values());
  }
  get hasLegs(): boolean {
    for (const m of this.mounts.values()) if (m.kind === "Legs") return true;
    return false;
  }

  /** 足の先が本体の底面からどれだけ下に出ているか。モデル読み込み前は Kotlin版と同じ暫定値を返す。 */
  get legReach(): number {
    if (!this.hasLegs) return 0;
    const leg = peekModuleModel("Legs");
    return leg ? (leg.max.y * this.size) / 150 : 0.24 * this.k;
  }

  async whenReady(): Promise<void> {
    await this.bodies.whenReady();
  }

  private buildFrames(): void {
    const disc = this.editable ? Meshes.circle(0.065 * this.k, 32) : null;
    for (const s of BodyLayout.slots) {
      const t = emptyGroup(`Slot_${s.id}`);
      t.position.copy(s.position.scale(this.size).toThree());
      t.quaternion.copy(s.mountRotation.toThree());
      const frame: SlotFrame = { slot: s, node: t };
      if (this.editable && disc) {
        const mat = unlit(0xffffffff);
        const marker = meshFrom(disc, mat, "Marker");
        marker.position.set(0, (s.isMonitor ? 0.012 : 0.006) * this.k, 0);
        marker.quaternion.copy(uRot(-90, V3.Right).toThree()); // 円 (+Z 向き) を外向き (+Y) に
        marker.visible = false;
        t.add(marker);
        frame.marker = marker;
        frame.markerMat = mat;
      }
      this.frames.set(s.id, frame);
      this.body.add(t);
    }
    if (this.editable) {
      const h = meshFrom(Meshes.plane(this.size * 0.97, this.size * 0.97), unlit(0x383b82f6), "FaceHighlight");
      h.visible = false;
      this.body.add(h);
      this.highlight = h;
    }
  }

  setSelectedFace(face: string | null): void {
    this.selectedFace = face;
    if (this.highlight) {
      this.highlight.visible = face != null;
      if (face != null) {
        const n = BodyLayout.faceNormal(face);
        this.highlight.position.copy(n.scale(this.size * 0.5 + 0.004 * this.k).toThree());
        this.highlight.quaternion.copy(Q.look(n, Math.abs(n.y) > 0.5 ? V3.Forward : V3.Up).toThree());
      }
    }
    this.updateMarkers();
  }

  setDrop(kind: ModuleKind | null, hover: string | null): void {
    this.dropKind = kind;
    this.dropHover = hover;
    this.updateMarkers();
  }

  private updateMarkers(): void {
    if (!this.editable) return;
    for (const fr of this.frames.values()) {
      const onFace = this.selectedFace != null && fr.slot.face === this.selectedFace;
      const occupied = this.mounts.has(fr.slot.id);
      const candidate = this.dropKind != null && ModuleCatalog.fits(this.dropKind, fr.slot);
      const hovered = candidate && fr.slot.id === this.dropHover;
      // 空きスロットは常に、取り付け済みは差し替え候補のときだけ目印を出す
      const show = onFace && (!occupied || candidate);
      const marker = fr.marker;
      if (!marker || !fr.markerMat) continue;
      marker.visible = show;
      if (!show) continue;
      const { color } = colorOf(hovered ? 0xffec4899 : candidate ? 0xff3b82f6 : 0xffffffff);
      fr.markerMat.color.copy(color);
      const s = hovered ? 1.5 : candidate ? 1.15 : 0.8;
      marker.scale.setScalar(s);
    }
  }

  /** ModuleConfig に合わせてモジュールを作り直す。 */
  async sync(): Promise<void> {
    const generation = ++this.syncGeneration;
    const want = this.fixedSlots ?? ModuleConfig.current;
    for (const id of Array.from(this.mounts.keys())) {
      const m = this.mounts.get(id)!;
      if (want.get(id) === m.kind) continue;
      m.root.parent?.remove(m.root);
      disposeTree(m.root);
      this.mounts.delete(id);
    }
    for (const [id, kind] of want) {
      if (this.mounts.has(id)) continue;
      const f = this.frames.get(id);
      if (!f) continue;
      const root = emptyGroup(kind);
      f.node.add(root);
      const shape = await buildModuleShape(kind, f.slot, this.size);
      if (generation !== this.syncGeneration) return; // 読み込み中に構成が変わった
      const swing: THREE.Group[] = [];
      const swingBase: Pose[] = [];
      const hull: Mount["hull"] = [];
      for (const p of shape.pivots) {
        const n = emptyGroup();
        applyPose(n, p);
        root.add(n);
        swing.push(n);
        swingBase.push(p);
      }
      for (const part of shape.parts) {
        const parent = part.pivot < 0 ? root : swing[part.pivot];
        const mat = part.mat === "Lens" ? this.lens : this.slotMat(id);
        const mesh = meshFrom(part.mesh, mat);
        applyPose(mesh, part.local);
        parent.add(mesh);
        const lo = part.mesh.min;
        const hi = part.mesh.max;
        for (let c = 0; c < 8; c++) {
          const v = new V3((c & 1) === 0 ? lo.x : hi.x, (c & 2) === 0 ? lo.y : hi.y, (c & 4) === 0 ? lo.z : hi.z);
          hull.push({ pivot: part.pivot, v: part.local.point(v) });
        }
      }
      this.mounts.set(id, { kind, root, swing, swingBase, swingNow: swingBase.slice(), hull });
    }
    this.faceOn = Array.from(want.values()).some((k) => k === "Face");
    this.bodies.setFace(this.faceOn);
  }

  // ------------------------------------------------------------ アニメーション

  private setSwing(m: Mount, j: number, r: Q): void {
    const b = m.swingBase[j];
    const now = new Pose(b.p, b.r.multiply(r), b.s);
    m.swingNow[j] = now;
    applyPose(m.swing[j], now);
  }

  swingLegs(amount: number, phase: number): void {
    let slot = 0;
    for (const m of this.mounts.values()) {
      if (m.kind !== "Legs") continue;
      for (let j = 0; j < m.swing.length; j++) {
        // 同じスロットの前後、隣のスロットどうしは逆位相
        const a = Math.sin(phase + ((j + slot) % 2 === 0 ? 0 : Math.PI)) * amount;
        this.setSwing(m, j, uRot(a * (180 / Math.PI), V3.Right));
      }
      slot++;
    }
  }

  /** 手を上下に振る (取り付け面の法線まわり = 腕の先が上下に動く)。 */
  waveHands(angle: number): void {
    for (const m of this.mounts.values())
      if (m.kind === "Hand" || m.kind === "HandLight")
        for (let j = 0; j < m.swing.length; j++) this.setSwing(m, j, uRot(angle * (180 / Math.PI), V3.Up));
  }

  wagTails(angle: number): void {
    for (const m of this.mounts.values())
      if (ModuleCatalog.isTail(m.kind) && m.kind !== "TailCurl") // 巻き尻尾は面に沿わせているので振らない
        for (let j = 0; j < m.swing.length; j++) this.setSwing(m, j, uRot(angle * (180 / Math.PI), V3.Forward));
  }
}

function applyPose(obj: THREE.Object3D, p: Pose): void {
  obj.position.copy(p.p.toThree());
  obj.quaternion.copy(p.r.toThree());
  obj.scale.copy(p.s.toThree());
}

function disposeTree(obj: THREE.Object3D): void {
  obj.traverse((o) => {
    if (o instanceof THREE.Mesh) {
      o.geometry.dispose();
      const mats = Array.isArray(o.material) ? o.material : [o.material];
      for (const m of mats) m.dispose();
    }
  });
}
