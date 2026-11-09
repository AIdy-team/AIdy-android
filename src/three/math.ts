import * as THREE from "three";

/**
 * 3D の小さな数学 (右手系・Y 上・カメラは -Z を向く = three.js / ARCore と同じ)。Math.kt の直訳ポート。
 *
 * Unity 版は左手系 (+X = 正面から見て左) なので、Unity の座標で書かれたデータ (body_v2_layout.json、
 * モジュールの取り付け面ローカル) は X を反転して取り込む: 位置 (x, y, z) → (-x, y, z)。
 */
export class V3 {
  constructor(public x = 0, public y = 0, public z = 0) {}

  static readonly Zero = new V3(0, 0, 0);
  static readonly One = new V3(1, 1, 1);
  static readonly Right = new V3(1, 0, 0);
  static readonly Up = new V3(0, 1, 0);
  static readonly Forward = new V3(0, 0, 1);

  add(o: V3): V3 {
    return new V3(this.x + o.x, this.y + o.y, this.z + o.z);
  }
  sub(o: V3): V3 {
    return new V3(this.x - o.x, this.y - o.y, this.z - o.z);
  }
  scale(s: number): V3 {
    return new V3(this.x * s, this.y * s, this.z * s);
  }
  divide(s: number): V3 {
    return new V3(this.x / s, this.y / s, this.z / s);
  }
  negate(): V3 {
    return new V3(-this.x, -this.y, -this.z);
  }
  get(i: 0 | 1 | 2): number {
    return i === 0 ? this.x : i === 1 ? this.y : this.z;
  }
  dot(o: V3): number {
    return this.x * o.x + this.y * o.y + this.z * o.z;
  }
  cross(o: V3): V3 {
    return new V3(this.y * o.z - this.z * o.y, this.z * o.x - this.x * o.z, this.x * o.y - this.y * o.x);
  }
  get length(): number {
    return Math.sqrt(this.sqrLength);
  }
  get sqrLength(): number {
    return this.x * this.x + this.y * this.y + this.z * this.z;
  }
  normalized(): V3 {
    const l = this.length;
    return l > 1e-12 ? this.divide(l) : this;
  }
  mul(o: V3): V3 {
    return new V3(this.x * o.x, this.y * o.y, this.z * o.z);
  }
  /** Unity 座標 ⇔ この座標 (X 反転)。 */
  mirrorX(): V3 {
    return new V3(-this.x, this.y, this.z);
  }
  toThree(): THREE.Vector3 {
    return new THREE.Vector3(this.x, this.y, this.z);
  }
  equals(o: V3): boolean {
    return this.x === o.x && this.y === o.y && this.z === o.z;
  }

  static min(a: V3, b: V3): V3 {
    return new V3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
  }
  static max(a: V3, b: V3): V3 {
    return new V3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
  }
  static lerp(a: V3, b: V3, t: number): V3 {
    return a.add(b.sub(a).scale(t));
  }
  static distance(a: V3, b: V3): number {
    return a.sub(b).length;
  }
}

/** 回転 (単位クォータニオン)。 */
export class Q {
  constructor(public x = 0, public y = 0, public z = 0, public w = 1) {}

  static readonly Identity = new Q(0, 0, 0, 1);

  multiply(o: Q): Q {
    const { x, y, z, w } = this;
    return new Q(
      w * o.x + x * o.w + y * o.z - z * o.y,
      w * o.y - x * o.z + y * o.w + z * o.x,
      w * o.z + x * o.y - y * o.x + z * o.w,
      w * o.w - x * o.x - y * o.y - z * o.z,
    );
  }

  rotate(v: V3): V3 {
    // v + 2w(q×v) + 2 q×(q×v)
    const qv = new V3(this.x, this.y, this.z);
    const t = qv.cross(v).scale(2);
    return v.add(t.scale(this.w)).add(qv.cross(t));
  }

  inverse(): Q {
    return new Q(-this.x, -this.y, -this.z, this.w);
  }

  normalized(): Q {
    const l = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z + this.w * this.w);
    return l > 1e-12 ? new Q(this.x / l, this.y / l, this.z / l, this.w / l) : Q.Identity;
  }

  toThree(): THREE.Quaternion {
    return new THREE.Quaternion(this.x, this.y, this.z, this.w);
  }

  static axisAngle(axis: V3, angle: number): Q {
    const a = axis.normalized();
    const s = Math.sin(angle * 0.5);
    return new Q(a.x * s, a.y * s, a.z * s, Math.cos(angle * 0.5));
  }

  /** three.js の Euler (XYZ 順、ラジアン) = Rx · Ry · Rz。 */
  static euler(rx: number, ry: number, rz: number): Q {
    return Q.axisAngle(V3.Right, rx).multiply(Q.axisAngle(V3.Up, ry)).multiply(Q.axisAngle(V3.Forward, rz));
  }

  static eulerV(r: V3): Q {
    return Q.euler(r.x, r.y, r.z);
  }

  /** 列ベクトル (x, y, z 軸) から。正規直交であること。 */
  static fromBasis(xa: V3, ya: V3, za: V3): Q {
    const m00 = xa.x, m10 = xa.y, m20 = xa.z;
    const m01 = ya.x, m11 = ya.y, m21 = ya.z;
    const m02 = za.x, m12 = za.y, m22 = za.z;
    const tr = m00 + m11 + m22;
    let q: Q;
    if (tr > 0) {
      const s = Math.sqrt(tr + 1) * 2;
      q = new Q((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s);
    } else if (m00 > m11 && m00 > m22) {
      const s = Math.sqrt(1 + m00 - m11 - m22) * 2;
      q = new Q(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s);
    } else if (m11 > m22) {
      const s = Math.sqrt(1 + m11 - m00 - m22) * 2;
      q = new Q((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s);
    } else {
      const s = Math.sqrt(1 + m22 - m00 - m11) * 2;
      q = new Q((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s);
    }
    return q.normalized();
  }

  /** +Z を forward に、+Y をできるだけ up に向ける回転 (Unity の LookRotation と同じ役割、右手系)。 */
  static look(forward: V3, up: V3): Q {
    const z = forward.normalized();
    let xa = up.cross(z);
    if (xa.sqrLength < 1e-10) xa = (Math.abs(z.y) < 0.9 ? V3.Up : V3.Right).cross(z);
    xa = xa.normalized();
    const ya = z.cross(xa);
    return Q.fromBasis(xa, ya, z);
  }

  /** from を to に向ける最小の回転。 */
  static fromTo(from: V3, to: V3): Q {
    const a = from.normalized();
    const b = to.normalized();
    const d = a.dot(b);
    if (d > 0.999999) return Q.Identity;
    if (d < -0.999999) {
      let axis = V3.Right.cross(a);
      if (axis.sqrLength < 1e-6) axis = V3.Up.cross(a);
      return Q.axisAngle(axis, Math.PI);
    }
    const c = a.cross(b);
    return new Q(c.x, c.y, c.z, 1 + d).normalized();
  }

  static slerp(a: Q, b0: Q, t: number): Q {
    let b = b0;
    let d = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
    if (d < 0) {
      b = new Q(-b.x, -b.y, -b.z, -b.w);
      d = -d;
    }
    if (d > 0.9995) {
      return new Q(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.z + (b.z - a.z) * t,
        a.w + (b.w - a.w) * t,
      ).normalized();
    }
    const th = Math.acos(clampNum(d, -1, 1));
    const s = Math.sin(th);
    const wa = Math.sin((1 - t) * th) / s;
    const wb = Math.sin(t * th) / s;
    return new Q(a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb, a.w * wa + b.w * wb);
  }

  /** 2 つの回転の角度 (度)。 */
  static angleDeg(a: Q, b: Q): number {
    const d = Math.min(Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w), 1);
    return rad2deg * (2 * Math.acos(d));
  }
}

/** 位置・回転・拡大 (親子の合成とその逆)。 */
export class Pose {
  constructor(public p: V3 = V3.Zero, public r: Q = Q.Identity, public s: V3 = V3.One) {}

  point(v: V3): V3 {
    return this.p.add(this.r.rotate(v.mul(this.s)));
  }
  dir(v: V3): V3 {
    return this.r.rotate(v);
  }
  inversePoint(v: V3): V3 {
    const l = this.r.inverse().rotate(v.sub(this.p));
    return new V3(l.x / this.s.x, l.y / this.s.y, l.z / this.s.z);
  }
  inverseDir(v: V3): V3 {
    return this.r.inverse().rotate(v);
  }
  /** this (親) の子 child を合成 (拡大は一様とみなす)。 */
  combine(c: Pose): Pose {
    return new Pose(this.point(c.p), this.r.multiply(c.r), this.s.mul(c.s));
  }
}

function clampNum(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), hi);
}

export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}
export function clamp01(v: number): number {
  return clampNum(v, 0, 1);
}
export function smoothStep(a: number, b: number, t: number): number {
  const x = clamp01((t - a) / (b - a));
  return x * x * (3 - 2 * x);
}
export function inverseLerp(a: number, b: number, v: number): number {
  return a !== b ? clamp01((v - a) / (b - a)) : 0;
}
export function repeatRange(t: number, length: number): number {
  return clampNum(t - Math.floor(t / length) * length, 0, length);
}

export const PI = Math.PI;
export const Deg2Rad = Math.PI / 180;
export const Rad2Deg = 180 / Math.PI;
const rad2deg = Rad2Deg;

// ---------------------------------------------------------------- Unity 版のコードを移すための変換

/** Unity の位置 → この座標。 */
export function uPos(x: number, y: number, z: number): V3 {
  return new V3(-x, y, z);
}

/** Unity の Quaternion.AngleAxis(deg, axis) → この座標の回転。 */
export function uRot(deg: number, axis: V3): Q {
  return Q.axisAngle(axis.mirrorX(), -(deg * Deg2Rad));
}

/** Unity の Quaternion.LookRotation(forward, up) (どちらも Unity 座標) → この座標の回転。 */
export function uLook(forward: V3, up: V3 = V3.Up): Q {
  return Q.look(forward.mirrorX(), up.mirrorX());
}

/** Unity の回転 → この座標の回転 (鏡映で挟む)。 */
export function mirrorXQ(q: Q): Q {
  return new Q(q.x, -q.y, -q.z, q.w);
}
