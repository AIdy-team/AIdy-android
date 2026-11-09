import * as THREE from "three";
import { V3, Deg2Rad, Rad2Deg } from "./math";

/**
 * 縦の画角 (度) と上下のずらし (NDC) を自分で決めるカメラ。Camera.kt (AidyCamera) のポート。
 * 位置と向き (eye → target) も持ち、タップ判定用のレイ・画面への投影を自前で計算する (描画と同じ式)。
 * three.js の PerspectiveCamera をラップする。
 */
export class AidyCamera {
  readonly three = new THREE.PerspectiveCamera(45, 1, 0.1, 1000);

  /** 縦横比 → 縦の画角 (度)。 */
  fov: (aspect: number) => number = () => 45;
  nearPlane = 0.1;
  farPlane = 1000;

  eye: V3 = new V3(0, 0, 1);
  target: V3 = V3.Zero;
  width = 1;
  height = 1;

  private _shiftY = 0;
  get shiftY(): number {
    return this._shiftY;
  }
  set shiftY(v: number) {
    this._shiftY = v;
    this.updateProjection();
  }

  get aspect(): number {
    return this.width / Math.max(1, this.height);
  }
  get fovDeg(): number {
    return this.fov(this.aspect);
  }

  /** ビューポートの大きさが変わったら呼ぶ。 */
  setSize(width: number, height: number): void {
    this.width = Math.max(1, width);
    this.height = Math.max(1, height);
    this.updateProjection();
  }

  updateProjection(): void {
    this.three.fov = this.fovDeg;
    this.three.aspect = this.aspect;
    this.three.near = this.nearPlane;
    this.three.far = this.farPlane;
    this.three.updateProjectionMatrix();
    if (this._shiftY !== 0) {
      // Unity/Filament の setShift(0, shiftY) と同じく、射影行列の [row1, col2] (= elements[9]、列優先配列) に加える
      this.three.projectionMatrix.elements[9] += this._shiftY;
    }
  }

  lookAt(eye: V3, target: V3): void {
    this.eye = eye;
    this.target = target;
    this.three.position.set(eye.x, eye.y, eye.z);
    this.three.up.set(0, 1, 0);
    this.three.lookAt(target.x, target.y, target.z);
  }

  private basis(): { r: V3; u: V3; f: V3 } {
    const f = this.target.sub(this.eye).normalized();
    let r = f.cross(V3.Up);
    if (r.sqrLength < 1e-8) r = V3.Right;
    r = r.normalized();
    const u = r.cross(f);
    return { r, u, f };
  }

  /** 画面の画素 (左上原点) → ワールドのレイ (始点, 単位方向)。 */
  ray(px: number, py: number): { origin: V3; dir: V3 } {
    const { r, u, f } = this.basis();
    const t = Math.tan(this.fovDeg * 0.5 * Deg2Rad);
    const nx = (px / this.width) * 2 - 1;
    const ny = 1 - (py / this.height) * 2 - this._shiftY;
    const d = f.add(r.scale(nx * t * this.aspect)).add(u.scale(ny * t)).normalized();
    return { origin: this.eye, dir: d };
  }

  /** ワールド → 画面の画素 (左上原点)。z = カメラからの奥行き (負なら後ろ)。 */
  worldToScreen(p: V3): V3 {
    const { r, u, f } = this.basis();
    const d = p.sub(this.eye);
    const z = d.dot(f);
    const t = Math.tan(this.fovDeg * 0.5 * Deg2Rad);
    const nx = d.dot(r) / (z * t * this.aspect);
    const ny = d.dot(u) / (z * t) + this._shiftY;
    return new V3((nx + 1) * 0.5 * this.width, (1 - ny) * 0.5 * this.height, z);
  }

  /** 縦の画角 45° は横幅 0.7 (縦長のカード) を想定。それより細長い画面では横が見切れないよう広げる。 */
  static homeFov(aspect: number): number {
    const designAspect = 0.7;
    const designFov = 45;
    if (aspect >= designAspect) return designFov;
    const halfTan = (Math.tan(designFov * 0.5 * Deg2Rad) * designAspect) / aspect;
    return 2 * Math.atan(halfTan) * Rad2Deg;
  }
}
