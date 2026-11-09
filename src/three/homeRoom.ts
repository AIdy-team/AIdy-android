import * as THREE from "three";
import { V3, Q, Pose, PI, lerp, clamp01, repeatRange, uPos, uRot } from "./math";
import { Meshes } from "./meshes";
import { lit, unlit, meshFrom, emptyGroup } from "./gfx";
import { AidyCamera } from "./camera";
import { ModuleRig } from "./rig";
import { BodyLayout, ModuleConfig, ModuleKind, ModuleCatalog } from "../data/modules";
import type { AidyFace } from "../components/mascot";

const BodySize = 1.1; // 本体の一辺 (シーン単位) = 150mm
const MmToScene = BodySize / 150;
const RobotBaseY = 0.14; // 足の先端が床に接地する高さ
const RestY = -0.1; // 足がないとき: 本体の底面が床 (-0.65) に着く高さ
const MoveSpeed = 0.015;
const Step = 1 / 60; // WebDemo は requestAnimationFrame (60fps) 基準の定数で動く
const DizzyTurns = 1.5; // この回転量 (回) を短時間に超えると目を回す
const DozeAfter = 30; // 何も触られないと居眠りする (秒)
const ZoomMin = 0.45;
const ZoomMax = 1.8;

type AIState = "Wander" | "Idle" | "Look";
type Reaction =
  | "None" | "Happy" | "Dizzy" | "Tickle" | "Snuggle" | "Surprised" | "Bob" | "Dance" | "Stretch" | "Sneeze" | "Doze" | "Wake";

/** three.js の Object3D 相当 (position / rotation (Euler XYZ) を three の数値で保持)。HomeRoom.kt の TNode のポート。 */
class TNode {
  p: V3 = V3.Zero;
  r: V3 = V3.Zero;
  s: V3 = V3.One;
  constructor(public obj: THREE.Object3D) {}

  apply(): void {
    this.obj.position.copy(this.p.toThree());
    this.obj.quaternion.copy(Q.eulerV(this.r).toThree());
    this.obj.scale.copy(this.s.toThree());
  }
  pose(): Pose {
    return new Pose(this.p, Q.eulerV(this.r), this.s);
  }
}

function rnd(): number {
  return Math.random();
}

/**
 * 3D ペットルーム + 本体v2 (ModuleConfig のモジュール付き) + 自律AI行動 + 操作への反応 (HomeRoom.kt のポート)。
 * ホーム画面と、AR タブ「画面上」の付け替えプレビュー (editable) の 2 か所で使う。
 * 顔モニターのテクスチャ表示 (FaceScreen) は Phase 4 で追加する。
 */
export class HomeRoom {
  onStatus: (s: string) => void = () => {};
  onModelBadge: (ok: boolean, text: string) => void = () => {};
  onFaceReaction: (face: AidyFace, seconds: number) => void = () => {};
  onModulesChanged: () => void = () => {};
  onFaceSelected: (face: string | null) => void = () => {};

  readonly root = new THREE.Group();
  readonly camera = new AidyCamera();
  readonly light: THREE.DirectionalLight;
  readonly clearColor = 0xf1f5f9;

  aiEnabled = true;
  draggingModel = false;
  visible = true;
  selectedFace: string | null = null;
  /** UI の 1 単位 (dp) の画素数。 */
  density = 1;

  private readonly robot: TNode;
  private readonly body: TNode;
  readonly rig: ModuleRig;

  private aiState: AIState = "Wander";
  private aiTimer = 0;
  private targetPos: V3 = V3.Zero;
  private animTime = 0;
  private accumulator = 0;

  private reaction: Reaction = "None";
  private reactionTimer = 0;
  private reactionTime = 0;
  private spinAccum = 0;
  private spinVel = 0;
  private lastTouch = 0;
  private tapTimes: number[] = [];
  private holding = false;
  private readonly stars: THREE.Group;
  private readonly starMeshes: THREE.Mesh[] = [];
  private readonly zzz: THREE.Group;
  private readonly zNodes: Array<{ mesh: THREE.Mesh; mat: THREE.MeshStandardMaterial }> = [];

  private focusTimer = 0;
  private focusRot: V3 = V3.Zero;

  private readonly camLook = new V3(0, 0.2, 0);
  private readonly camBase: V3;
  private zoom = 1;
  private zoomTarget = 1;

  constructor(public readonly editable: boolean) {
    this.camBase = editable ? new V3(0, 1.7, 3.6) : new V3(0, 2.2, 4.2);
    this.camera.fov = (a) => AidyCamera.homeFov(a);
    this.camera.nearPlane = 0.1;
    this.camera.farPlane = 1000;
    this.applyCamera();

    this.light = new THREE.DirectionalLight(0xffffff, 0.85 * Math.PI); // three.js の物理ベースライトとの強さ合わせ
    const d = new V3(4, 8, 5).normalized();
    this.light.position.set(d.x, d.y, d.z);
    this.light.castShadow = true;
    this.root.add(this.light);

    // 床・ラグ・壁
    const floor = meshFrom(Meshes.plane(8, 8), lit(0xffe2e8f0, 0, 0.4), "Floor");
    floor.position.set(0, -0.65, 0);
    floor.quaternion.copy(Q.euler(-PI / 2, 0, 0).toThree());
    floor.receiveShadow = true;
    this.root.add(floor);

    const rug = meshFrom(Meshes.circle(1.6, 32), lit(editable ? 0xffe0e7ff : 0xffdbeafe, 0, 0.8), "Rug");
    rug.position.set(0, -0.64, 0);
    rug.quaternion.copy(Q.euler(-PI / 2, 0, 0).toThree());
    rug.receiveShadow = true;
    this.root.add(rug);

    const wall = meshFrom(Meshes.plane(8, 4), lit(0xfff8fafc, 0, 0.9), "Wall");
    wall.position.set(0, 1.35, -2.5);
    wall.receiveShadow = true;
    this.root.add(wall);

    // ロボットモデルアセンブリ
    const robotGroup = emptyGroup("RobotGroup");
    this.root.add(robotGroup);
    this.robot = new TNode(robotGroup);
    this.robot.p = new V3(0, RobotBaseY, 0);
    this.robot.apply();

    const bodyGroup = emptyGroup("BodyGroup");
    robotGroup.add(bodyGroup);
    this.body = new TNode(bodyGroup);
    this.body.apply();

    this.rig = new ModuleRig(bodyGroup, BodySize, editable);

    this.stars = this.buildStars();
    this.zzz = this.buildZzz();

    ModuleConfig.changed.push(this.moduleListener);
    this.lastTouch = now();
    if (editable) this.toggleAIWalk(false);
  }

  destroy(): void {
    const idx = ModuleConfig.changed.indexOf(this.moduleListener);
    if (idx >= 0) ModuleConfig.changed.splice(idx, 1);
  }

  private moduleListener = (slot: string, removed: ModuleKind | null, added: ModuleKind | null) => {
    void this.onModuleChanged(slot, removed, added);
  };

  /** UI 側のイベント登録後に、読み込み結果を通知する。 */
  async reportModelState(): Promise<void> {
    await this.rig.whenReady();
    await this.rig.sync();
    if (!this.rig.hasModel) this.onModelBadge(false, "本体の3Dモデルを読み込めなかった…");
    else this.onModelBadge(true, "準備OK！ 本体とおなじ形だよ");
  }

  // ------------------------------------------------------------ モジュール

  private async onModuleChanged(slot: string, removed: ModuleKind | null, added: ModuleKind | null): Promise<void> {
    await this.rig.sync();
    this.lastTouch = now();
    if (added != null) this.startReaction("Happy", 1.6, `♥ ${ModuleCatalog.name(added)}モジュールを付けてもらった！`, "Happy");
    else if (removed != null) this.startReaction("Surprised", 1.2, `${ModuleCatalog.name(removed)}モジュールが外れた！`, "Surprised");
    this.onModulesChanged();
  }

  // ------------------------------------------------------------ 面の選択 (editable のときだけ)

  selectFace(face: string | null): void {
    if (!this.editable) return;
    this.selectedFace = face;
    if (face != null) this.focusFace(face, Number.MAX_VALUE);
    else {
      this.focusTimer = 0;
      this.aiTimer = 0.8;
    }
    this.rig.setSelectedFace(face);
    this.onFaceSelected(face);
  }

  moduleAt(slotId: string): ModuleKind | null {
    return ModuleConfig.at(slotId);
  }

  /** 3D ビュー上のタップ (ビュー内の画素)。editable: 本体の面を選ぶ (外れたら解除)。ホーム: なでなで。 */
  tapAt(x: number, y: number): void {
    if (this.wakeIfDozing()) return;
    const face = this.pickFace(x, y);
    if (!this.editable) {
      if (face != null) this.petRobotDirectly();
      return;
    }
    if (face == null) {
      if (this.selectedFace != null) {
        this.selectFace(null);
        this.onStatus("面の選択を解除しました");
      }
      return;
    }
    if (this.countTaps()) return;
    if (face !== this.selectedFace) this.selectFace(face);
    this.startReaction("Bob", 0.4, `${BodyLayout.faceLabel(face)}を選んだよ ・ モジュールをドラッグしてね`, "Happy");
  }

  /** body の世界姿勢 (root はシーンの原点)。 */
  private bodyWorld(): Pose {
    return this.robot.pose().combine(this.body.pose());
  }

  /** タップ位置の本体の面 (立方体とレイの交差)。外れたら、近くに見えている取り付け済みモジュールの面。 */
  private pickFace(x: number, y: number): string | null {
    const { origin: ro, dir: rd } = this.camera.ray(x, y);
    const bw = this.bodyWorld();
    const o = bw.inversePoint(ro);
    const dw = bw.inverseDir(rd);
    const d = new V3(dw.x / bw.s.x, dw.y / bw.s.y, dw.z / bw.s.z);
    const h = BodySize * 0.5;
    let tNear = -Infinity;
    let tFar = Infinity;
    let axis = -1;
    for (let i = 0; i < 3; i++) {
      const di = d.get(i as 0 | 1 | 2);
      const oi = o.get(i as 0 | 1 | 2);
      if (Math.abs(di) < 1e-6) {
        if (oi < -h || oi > h) {
          tNear = Infinity;
          break;
        }
        continue;
      }
      let t1 = (-h - oi) / di;
      let t2 = (h - oi) / di;
      if (t1 > t2) {
        const t = t1;
        t1 = t2;
        t2 = t;
      }
      if (t1 > tNear) {
        tNear = t1;
        axis = i;
      }
      tFar = Math.min(tFar, t2);
    }
    if (axis >= 0 && tNear <= tFar && tNear > 0) {
      const side = Math.sign(o.get(axis as 0 | 1 | 2) + d.get(axis as 0 | 1 | 2) * tNear);
      const n = axis === 0 ? new V3(side, 0, 0) : axis === 1 ? new V3(0, side, 0) : new V3(0, 0, side);
      for (const f of BodyLayout.Faces) if (BodyLayout.faceNormal(f).dot(n) > 0.9) return f;
    }
    const slot = this.slotAtScreen(x, y, 70, (s) => ModuleConfig.at(s.id) != null, false, false);
    return slot ? (BodyLayout.get(slot)?.face ?? null) : null;
  }

  /**
   * 画面上で一番近いスロット (maxDp は UI 単位)。面を選んでいればその面だけ、
   * onlyFacing なら手前を向いている面だけを候補にする。
   */
  slotAtScreen(
    x: number,
    y: number,
    maxDp: number,
    filter: ((s: import("../data/modules").BodySlot) => boolean) | null,
    onlyFacing = true,
    onlySelected = true,
  ): string | null {
    let best = maxDp * this.density;
    let hit: string | null = null;
    const bw = this.bodyWorld();
    for (const fr of this.rig.frameList) {
      if (onlySelected && this.selectedFace != null && fr.slot.face !== this.selectedFace) continue;
      if (filter && !filter(fr.slot)) continue;
      const local = new Pose(fr.slot.position.scale(BodySize), fr.slot.mountRotation);
      const world = bw.point(local.point(new V3(0, 0.04, 0)));
      const up = bw.dir(local.dir(V3.Up)).normalized();
      if (onlyFacing && up.dot(this.camEye().sub(world).normalized()) < 0.15) continue;
      const sp = this.camera.worldToScreen(world);
      if (sp.z <= 0) continue;
      const dist = Math.hypot(sp.x - x, sp.y - y);
      if (dist < best) {
        best = dist;
        hit = fr.slot.id;
      }
    }
    return hit;
  }

  private camEye(): V3 {
    return this.camera.eye;
  }

  /** ドラッグ中のモジュール (null で終了) と、いま指が重なっているスロット。目印の色を変える。 */
  setDropState(kind: ModuleKind | null, hoverSlot: string | null): void {
    this.rig.setDrop(kind, hoverSlot);
  }

  /** 面をカメラへ向ける。 */
  focusFace(face: string, seconds = 4): void {
    let fr: V3;
    switch (face) {
      case "top": fr = new V3(1.13, 0, 0); break;
      case "bottom": fr = new V3(-2.0, 0, 0); break;
      case "back": fr = new V3(0, PI, 0); break;
      case "left": fr = new V3(0, PI / 2, 0); break;
      case "right": fr = new V3(0, -PI / 2, 0); break;
      default: fr = V3.Zero;
    }
    // R.y は今の向きから一番近い回り方にする
    fr = new V3(fr.x, fr.y + Math.round((this.robot.r.y - fr.y) / (PI * 2)) * PI * 2, fr.z);
    this.focusRot = fr;
    this.focusTimer = seconds;
  }

  // ------------------------------------------------------------ ズーム

  /** factor > 1 で近づく。 */
  zoomBy(factor: number): void {
    this.zoomTarget = Math.min(Math.max(this.zoomTarget / factor, ZoomMin), ZoomMax);
    this.lastTouch = now();
  }

  resetZoom(): void {
    this.zoomTarget = 1;
  }

  private applyCamera(): void {
    this.camera.lookAt(this.camLook.add(this.camBase.sub(this.camLook).scale(this.zoom)), this.camLook);
  }

  /** 3D ビューの上下に重なっている UI の高さ (画素)。モデルが見えている部分の中央に来るよう写る範囲をずらす。 */
  setViewInsets(topPx: number, bottomPx: number, viewHeightPx: number): void {
    if (viewHeightPx <= 0) return;
    this.camera.shiftY = (bottomPx - topPx) / viewHeightPx;
  }

  setViewSize(width: number, height: number): void {
    this.camera.setSize(width, height);
  }

  // ------------------------------------------------------------ 操作への反応

  /** タップ (なでなで)。短時間に3回以上ならくすぐったがる。 */
  petRobotDirectly(): void {
    if (this.wakeIfDozing() || this.countTaps()) return;
    if (this.reaction !== "Dizzy") this.startReaction("Happy", 2.5, "♥ なでなでしてもらってうれしい！", "Happy");
  }

  private countTaps(): boolean {
    const t = now();
    this.tapTimes.push(t);
    this.tapTimes = this.tapTimes.filter((x) => t - x <= 1.2);
    if (this.tapTimes.length < 3) return false;
    this.tapTimes = [];
    this.startReaction("Tickle", 1.6, "くすぐったい〜！ やめて〜", "Happy");
    return true;
  }

  /** 指が触れた (居眠りしていたら起きる)。 */
  touched(): void {
    this.lastTouch = now();
  }

  private wakeIfDozing(): boolean {
    this.lastTouch = now();
    if (this.reaction !== "Doze") return false;
    this.startReaction("Wake", 0.8, "はっ！ 寝てないよ！", "Surprised");
    return true;
  }

  /** 長押し (指を置いたまま) で撫でられてうっとりする。 */
  holdStart(): void {
    if (this.wakeIfDozing()) return;
    this.holding = true;
    this.startReaction("Snuggle", 999, "なでなで… うっとり", "Sleepy");
  }

  holdEnd(): void {
    if (!this.holding) return;
    this.holding = false;
    this.body.s = V3.One;
    this.startReaction("Happy", 1.2, "♥ もっとなでて！", "Happy");
  }

  /** モーションを再生する: dance / stretch / sneeze / doze / dizzy。 */
  play(motion: "dance" | "stretch" | "sneeze" | "doze" | "dizzy"): void {
    switch (motion) {
      case "dance": this.startReaction("Dance", 3.2, "♪ ごきげんダンス！", "Happy"); break;
      case "stretch": this.startReaction("Stretch", 2, "うーん… のびのび〜", "Sleepy"); break;
      case "sneeze": this.startReaction("Sneeze", 1.3, "は… はっ…", "Surprised"); break;
      case "doze": this.startReaction("Doze", 999, "すやすや… Zzz", "Sleepy"); break;
      case "dizzy": this.startReaction("Dizzy", 3.5, "@_@ 目が回った〜", "Dizzy"); break;
    }
  }

  private startReaction(r: Reaction, seconds: number, status: string, face: AidyFace): void {
    if (this.reaction === "Snuggle" && r !== "Happy") this.body.s = V3.One;
    this.reaction = r;
    this.reactionTimer = seconds;
    this.reactionTime = 0;
    this.onStatus(status);
    this.onFaceReaction(face, Math.min(seconds, 4));
    this.stars.visible = r === "Dizzy";
    this.zzz.visible = r === "Doze";
  }

  private endReaction(): void {
    const was = this.reaction;
    this.reaction = "None";
    this.body.s = V3.One;
    this.stars.visible = false;
    this.zzz.visible = false;
    this.rig.wagTails(0);
    this.rig.waveHands(0);
    if (was === "Dizzy") this.onStatus("ふぅ… やっと目が回らなくなった");
    else if (was === "Wake") {
      this.startReaction("Stretch", 2, "うーん… のびのび〜", "Sleepy");
      return;
    } else if (was === "Sneeze") this.onStatus("ずずっ… 失礼しました");

    if (this.aiEnabled && was !== "Dizzy" && this.selectedFace == null) this.chooseNextAIState();
    else this.aiTimer = 1.5;
  }

  private buildStars(): THREE.Group {
    const s = emptyGroup("DizzyStars");
    s.position.set(0, BodySize * 0.5 + 0.42, 0);
    this.body.obj.add(s);
    const star = Meshes.star(0.07, 0.03);
    const mat = unlit(0xfffacc15);
    for (let i = 0; i < 3; i++) {
      const m = meshFrom(star, mat, `Star${i}`);
      this.starMeshes.push(m);
      s.add(m);
    }
    s.visible = false;
    return s;
  }

  /** 居眠り中に頭の上へ浮かぶ「Z」。 */
  private buildZzz(): THREE.Group {
    const z = emptyGroup("Zzz");
    z.position.copy(uPos(-0.25, BodySize * 0.5 + 0.25, 0).toThree());
    this.body.obj.add(z);
    const mesh = Meshes.zGlyph(0.22);
    for (let i = 0; i < 3; i++) {
      const mat = lit(0x806366f1, 0, 1);
      const m = meshFrom(mesh, mat, `Z${i}`);
      this.zNodes.push({ mesh: m, mat });
      z.add(m);
    }
    z.visible = false;
    return z;
  }

  // ------------------------------------------------------------ 自律AIエージェント

  toggleAIWalk(enabled: boolean): void {
    this.aiEnabled = enabled;
    if (!enabled) {
      this.aiState = "Idle";
      this.robot.p = new V3(0, this.robot.p.y, 0);
      this.robot.r = V3.Zero;
      this.robot.apply();
      if (!this.editable) this.onStatus("お散歩はお休み中");
    } else {
      this.chooseNextAIState();
    }
  }

  private chooseNextAIState(): void {
    if (!this.aiEnabled) return;
    this.aiTimer = rnd() * 3 + 2;

    const m = rnd();
    if (m < 0.08) { this.play("dance"); return; }
    if (m < 0.14) { this.play("stretch"); return; }
    if (m < 0.18) { this.play("sneeze"); return; }

    const r = rnd();
    if (r < 0.5 && this.rig.hasLegs) {
      this.aiState = "Wander";
      this.targetPos = new V3((rnd() - 0.5) * 2.2, 0, (rnd() - 0.5) * 1.2);
      this.onStatus("気ままにお散歩中…");
    } else if (r < 0.75) {
      this.aiState = "Idle";
      this.onStatus(this.rig.hasLegs ? "日向ぼっこ中" : "足がないのでお散歩できない…");
    } else {
      this.aiState = "Look";
      this.onStatus("周りをきょろきょろ");
    }
  }

  /** ドラッグ回転 (dx, dy は dp、y は下向き正)。勢いよく回し続けると目を回す。 */
  rotateBy(dx: number, dy: number): void {
    if (this.wakeIfDozing()) return;
    this.robot.r = new V3(this.robot.r.x + dy * 0.01, this.robot.r.y + dx * 0.01, this.robot.r.z);
    this.robot.apply();
    this.focusTimer = 0;
    if (this.selectedFace != null) this.selectFace(null);

    this.spinAccum += (Math.abs(dx) + Math.abs(dy)) * 0.01;
    this.spinVel = dx * 0.01;
    if (this.reaction !== "Dizzy" && this.spinAccum > DizzyTurns * PI * 2) {
      this.spinAccum = 0;
      this.play("dizzy");
    }
  }

  /** 毎フレーム (dt は秒)。 */
  frame(dt: number): void {
    this.accumulator = Math.min(this.accumulator + dt, 0.25);
    while (this.accumulator >= Step) {
      this.accumulator -= Step;
      this.tick();
    }
    if (Math.abs(this.zoom - this.zoomTarget) > 0.0005) {
      this.zoom = lerp(this.zoom, this.zoomTarget, 1 - Math.exp(-12 * dt));
      this.applyCamera();
    }
    if (this.stars.visible) this.orbitStars();
    if (this.zzz.visible) this.floatZzz();
  }

  /** 子 (body の下の local) を、カメラに向けた世界回転 world にするための local 回転。 */
  private billboard(parentWorld: Pose, localPos: V3, extra: Q): Q {
    const world = parentWorld.point(localPos);
    const worldRot = Q.look(world.sub(this.camera.eye), V3.Up).multiply(extra);
    return parentWorld.r.inverse().multiply(worldRot);
  }

  private orbitStars(): void {
    const parent = this.bodyWorld().combine(new Pose(new V3(0, BodySize * 0.5 + 0.42, 0)));
    for (let i = 0; i < this.starMeshes.length; i++) {
      const a = this.reactionTime * 4 + (i * PI * 2) / 3;
      const p = uPos(Math.cos(a) * 0.32, Math.sin(a * 2) * 0.04, Math.sin(a) * 0.32);
      const m = this.starMeshes[i];
      m.position.copy(p.toThree());
      m.quaternion.copy(this.billboard(parent, p, uRot(this.reactionTime * 200, V3.Forward)).toThree());
    }
  }

  private floatZzz(): void {
    const parent = this.bodyWorld().combine(new Pose(uPos(-0.25, BodySize * 0.5 + 0.25, 0)));
    for (let i = 0; i < this.zNodes.length; i++) {
      const p = repeatRange(this.reactionTime * 0.45 + i / 3, 1); // 0→1 で下から上へ
      const pos = uPos(-p * 0.25 + Math.sin(p * 6) * 0.03, p * 0.45, 0);
      const sc = 0.1 * (0.6 + p * 0.9);
      const { mesh, mat } = this.zNodes[i];
      mesh.position.copy(pos.toThree());
      mesh.quaternion.copy(this.billboard(parent, pos, Q.Identity).toThree());
      mesh.scale.setScalar(sc);
      const alpha = clamp01(Math.sin(p * PI));
      mat.opacity = alpha;
      mat.transparent = true;
    }
  }

  private tick(): void {
    const dt = 0.016;
    this.spinAccum *= Math.exp(-0.6 * dt); // 回し続けないと溜まらない

    // しばらく触られないと居眠り (見えているときだけ)
    if (this.reaction === "None" && this.selectedFace == null && !this.draggingModel && this.visible && now() - this.lastTouch > DozeAfter)
      this.play("doze");

    if (this.reaction !== "None") {
      if (!this.draggingModel || this.reaction === "Snuggle") this.tickReaction(dt);
      // 取り付け直後の喜びジャンプ中も、選んだ面へ向き続ける
      if (this.focusTimer > 0 && (this.reaction === "Happy" || this.reaction === "Surprised" || this.reaction === "Bob")) {
        this.focusTimer -= dt;
        this.robot.r = V3.lerp(this.robot.r, this.focusRot, 0.12);
      }
    } else if (this.focusTimer > 0 && !this.draggingModel) {
      this.focusTimer -= dt;
      this.robot.r = V3.lerp(this.robot.r, this.focusRot, 0.12);
      this.robot.p = V3.lerp(this.robot.p, new V3(0, this.robot.p.y, 0), 0.08);
      this.rig.swingLegs(0, 0);
      if (this.focusTimer <= 0) this.aiTimer = 0.5;
    } else if (this.aiEnabled && !this.draggingModel) {
      this.tickAI(dt);
    } else if (!this.draggingModel) {
      // お散歩なし: その場で呼吸するように上下
      this.animTime += 0.03;
      this.body.p = new V3(this.body.p.x, Math.sin(this.animTime) * 0.03, this.body.p.z);
    }

    // ドラッグで傾いたまま/面を見せた後は、ゆっくり水平に戻る
    if (!this.draggingModel && this.focusTimer <= 0 && this.reaction !== "Dizzy" && this.reaction !== "Sneeze" && this.reaction !== "Tickle") {
      this.robot.r = new V3(lerp(this.robot.r.x, 0, 0.08), this.robot.r.y, lerp(this.robot.r.z, 0, 0.08));
    }
    this.robot.p = new V3(this.robot.p.x, lerp(this.robot.p.y, this.rig.hasLegs ? RobotBaseY : RestY, 0.1), this.robot.p.z);

    this.robot.apply();
    this.body.apply();
  }

  private tickAI(dt: number): void {
    this.aiTimer -= dt;
    switch (this.aiState) {
      case "Wander": {
        let dir = this.targetPos.sub(this.robot.p);
        dir = new V3(dir.x, 0, dir.z);
        const dist = dir.length;
        if (dist > 0.05) {
          dir = dir.normalized();
          this.robot.p = this.robot.p.add(dir.scale(MoveSpeed));
          const targetAngle = Math.atan2(dir.x, dir.z);
          this.robot.r = new V3(this.robot.r.x, lerp(this.robot.r.y, targetAngle, 0.1), this.robot.r.z);

          this.animTime += 0.18;
          this.rig.swingLegs(0.4, this.animTime);
          this.rig.waveHands(Math.sin(this.animTime) * 0.15);
          this.body.p = new V3(this.body.p.x, Math.abs(Math.sin(this.animTime * 2)) * 0.06, this.body.p.z);
        } else {
          this.chooseNextAIState();
        }
        break;
      }
      case "Idle": {
        this.animTime += 0.03;
        this.body.p = new V3(this.body.p.x, Math.sin(this.animTime) * 0.03, this.body.p.z);
        this.rig.swingLegs(0, 0);
        break;
      }
      case "Look": {
        this.animTime += 0.05;
        this.robot.r = new V3(this.robot.r.x, Math.sin(this.animTime) * 0.5, this.robot.r.z);
        break;
      }
    }
    if (this.aiTimer <= 0) this.chooseNextAIState();
  }

  private tickReaction(dt: number): void {
    this.reactionTime += dt;
    this.reactionTimer -= dt;
    const t = this.reactionTime;
    const b = this.body;
    const r = this.robot;

    switch (this.reaction) {
      case "Happy":
        b.p = new V3(b.p.x, Math.abs(Math.sin(t * 15.6)) * (this.selectedFace != null ? 0.1 : 0.25), b.p.z);
        this.rig.wagTails(Math.sin(t * 31) * 0.4);
        this.rig.waveHands(Math.sin(t * 12) * 0.5);
        break;
      case "Bob":
        b.p = new V3(b.p.x, Math.sin(clamp01(t / 0.4) * PI) * 0.06, b.p.z);
        break;
      case "Tickle":
        r.r = new V3(r.r.x, r.r.y, Math.sin(t * 28) * 0.12);
        b.p = new V3(b.p.x, Math.abs(Math.sin(t * 20)) * 0.08, b.p.z);
        this.rig.wagTails(Math.sin(t * 40) * 0.6);
        this.rig.waveHands(Math.sin(t * 30) * 0.35);
        this.rig.swingLegs(0.3, t * 30);
        break;
      case "Snuggle": {
        const squish = 1 - (0.5 + 0.5 * Math.sin(t * 3)) * 0.06;
        b.s = new V3(1 / Math.sqrt(squish), squish, 1 / Math.sqrt(squish));
        b.p = new V3(b.p.x, lerp(b.p.y, -0.02, 0.1), b.p.z);
        this.rig.wagTails(Math.sin(t * 2.5) * 0.25);
        break;
      }
      case "Dizzy": {
        const fade = clamp01(this.reactionTimer / 3.5);
        this.spinVel *= 0.96;
        r.r = new V3(Math.cos(t * 6) * 0.12 * fade, r.r.y + this.spinVel, Math.sin(t * 6) * 0.2 * fade);
        b.p = new V3(b.p.x, Math.abs(Math.sin(t * 3)) * 0.03, b.p.z);
        this.rig.swingLegs(0.2 * fade, t * 6);
        this.rig.waveHands(Math.sin(t * 6) * 0.3 * fade);
        break;
      }
      case "Surprised":
      case "Wake":
        b.p = new V3(b.p.x, Math.sin(clamp01(t / 0.35) * PI) * 0.18, b.p.z);
        break;
      case "Dance": {
        const p = t / 3.2;
        r.r = new V3(r.r.x, r.r.y + (p < 0.3 ? 0.33 : 0), p >= 0.3 && p < 0.8 ? Math.sin(t * 9) * 0.18 : 0);
        b.p = new V3(b.p.x, Math.abs(Math.sin(t * 9)) * (p > 0.8 ? 0.3 : 0.1), b.p.z);
        this.rig.waveHands(Math.sin(t * 9) * 0.6);
        this.rig.wagTails(Math.sin(t * 18) * 0.5);
        this.rig.swingLegs(0.35, t * 18);
        break;
      }
      case "Stretch": {
        const s = Math.sin(clamp01(t / 2) * PI);
        const sy = 1 + s * 0.14;
        b.s = new V3(1 / Math.sqrt(sy), sy, 1 / Math.sqrt(sy));
        b.p = new V3(b.p.x, s * 0.08, b.p.z);
        this.rig.waveHands(s * 0.7);
        break;
      }
      case "Sneeze":
        if (t < 0.6) {
          r.r = new V3(lerp(r.r.x, -0.18, 0.1), r.r.y, r.r.z);
        } else {
          if (t - dt < 0.6) {
            this.onStatus("くしゅんっ！");
            this.onFaceReaction("Blink", 0.6);
          }
          const q = clamp01((t - 0.6) / 0.25);
          r.r = new V3(lerp(0.3, 0, q * q), r.r.y, r.r.z);
          b.p = new V3(b.p.x, Math.sin(q * PI) * 0.1, b.p.z);
        }
        break;
      case "Doze": {
        const bb = 0.5 + 0.5 * Math.sin(t * 1.6);
        b.s = new V3(1 + bb * 0.02, 1 - bb * 0.03, 1 + bb * 0.02);
        b.p = new V3(b.p.x, lerp(b.p.y, -0.02, 0.05), b.p.z);
        r.r = new V3(r.r.x, r.r.y, Math.sin(t * 0.8) * 0.04);
        this.rig.swingLegs(0, 0);
        break;
      }
      case "None":
        break;
    }
    if (this.reactionTimer <= 0) this.endReaction();
  }

  /** 画面上の点 (ビュー内の画素) → スロットの世界座標を画面に投影した位置 (トレイのドラッグ用)。 */
  slotScreen(slotId: string): V3 | null {
    const fr = this.rig.frameList.find((f) => f.slot.id === slotId);
    if (!fr) return null;
    const local = new Pose(fr.slot.position.scale(BodySize), fr.slot.mountRotation);
    return this.camera.worldToScreen(this.bodyWorld().point(local.point(new V3(0, 0.04, 0))));
  }
}

function now(): number {
  return Date.now() / 1000;
}
