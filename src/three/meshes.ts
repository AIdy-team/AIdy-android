import { V3, PI } from "./math";
import { MeshData } from "./meshData";

/** メッシュを頂点ごとに組み立てる内部ビルダー。Gfx.kt の private class B のポート。 */
class Builder {
  p: number[] = [];
  n: number[] = [];
  uv: number[] = [];
  idx: number[] = [];

  v(x: number, y: number, z: number, nx: number, ny: number, nz: number, u = 0, w = 0): number {
    this.p.push(x, y, z);
    this.n.push(nx, ny, nz);
    this.uv.push(u, w);
    return this.p.length / 3 - 1;
  }

  t(a: number, b: number, c: number): void {
    this.idx.push(a, b, c);
  }

  build(): MeshData {
    return new MeshData(new Float32Array(this.p), new Float32Array(this.n), new Uint32Array(this.idx), new Float32Array(this.uv));
  }
}

/** three.js の各 Geometry と同じ寸法・向きのメッシュ (右手系、反時計回りが表)。Gfx.kt の Meshes object のポート。 */
export const Meshes = {
  /** PlaneGeometry(w, h): XY 平面、+Z 向き。UV は左上が (0,0) (画像の上の行が上)。 */
  plane(w: number, h: number): MeshData {
    const x = w * 0.5;
    const y = h * 0.5;
    const b = new Builder();
    const a = b.v(-x, -y, 0, 0, 0, 1, 0, 1);
    const c = b.v(x, -y, 0, 0, 0, 1, 1, 1);
    const d = b.v(x, y, 0, 0, 0, 1, 1, 0);
    const e = b.v(-x, y, 0, 0, 0, 1, 0, 0);
    b.t(a, c, d);
    b.t(a, d, e);
    return b.build();
  },

  /** CircleGeometry(r, segments): XY 平面、+Z 向き。 */
  circle(r: number, seg: number): MeshData {
    const b = new Builder();
    const c = b.v(0, 0, 0, 0, 0, 1, 0.5, 0.5);
    for (let i = 0; i <= seg; i++) {
      const a = (i / seg) * PI * 2;
      b.v(Math.cos(a) * r, Math.sin(a) * r, 0, 0, 0, 1, 0.5 + Math.cos(a) * 0.5, 0.5 - Math.sin(a) * 0.5);
    }
    for (let i = 0; i < seg; i++) b.t(c, c + 1 + i, c + 2 + i);
    return b.build();
  },

  /** RingGeometry(inner, outer, segments): XY 平面、+Z 向き。 */
  ring(inner: number, outer: number, seg: number): MeshData {
    const b = new Builder();
    for (let i = 0; i <= seg; i++) {
      const a = (i / seg) * PI * 2;
      b.v(Math.cos(a) * inner, Math.sin(a) * inner, 0, 0, 0, 1);
      b.v(Math.cos(a) * outer, Math.sin(a) * outer, 0, 0, 0, 1);
    }
    for (let i = 0; i < seg; i++) {
      const a = i * 2;
      b.t(a, a + 1, a + 3);
      b.t(a, a + 3, a + 2);
    }
    return b.build();
  },

  /** CylinderGeometry(radiusTop, radiusBottom, height, segments): Y 軸、中心原点、上下キャップ付き。 */
  cylinder(rTop: number, rBottom: number, height: number, seg: number): MeshData {
    const hy = height * 0.5;
    const slope = (rBottom - rTop) / height;
    const b = new Builder();
    const top0 = b.p.length / 3;
    for (let i = 0; i <= seg; i++) {
      const a = (i / seg) * PI * 2;
      const s = Math.sin(a);
      const c = Math.cos(a);
      const nn = new V3(s, slope, c).normalized();
      b.v(rTop * s, hy, rTop * c, nn.x, nn.y, nn.z);
      b.v(rBottom * s, -hy, rBottom * c, nn.x, nn.y, nn.z);
    }
    for (let i = 0; i < seg; i++) {
      const t0 = top0 + i * 2;
      const b0 = t0 + 1;
      const t1 = t0 + 2;
      const b1 = t0 + 3;
      // 外から見て反時計回り
      b.t(t0, b0, b1);
      b.t(t0, b1, t1);
    }
    const tc = b.v(0, hy, 0, 0, 1, 0);
    const bc = b.v(0, -hy, 0, 0, -1, 0);
    const tr = b.p.length / 3;
    for (let i = 0; i <= seg; i++) {
      const a = (i / seg) * PI * 2;
      b.v(rTop * Math.sin(a), hy, rTop * Math.cos(a), 0, 1, 0);
    }
    const br = b.p.length / 3;
    for (let i = 0; i <= seg; i++) {
      const a = (i / seg) * PI * 2;
      b.v(rBottom * Math.sin(a), -hy, rBottom * Math.cos(a), 0, -1, 0);
    }
    for (let i = 0; i < seg; i++) {
      b.t(tc, tr + i, tr + i + 1);
      b.t(bc, br + i + 1, br + i);
    }
    return b.build();
  },

  /** 立方体 (一辺 1、中心原点)。 */
  cube(): MeshData {
    const b = new Builder();
    const faces = [V3.Right, V3.Right.negate(), V3.Up, V3.Up.negate(), V3.Forward, V3.Forward.negate()];
    for (const nrm of faces) {
      const u = Math.abs(nrm.y) > 0.5 ? V3.Right : V3.Up;
      const t1 = u.cross(nrm).normalized();
      const t2 = nrm.cross(t1);
      const c = nrm.scale(0.5);
      const corners = [
        c.sub(t1.scale(0.5)).sub(t2.scale(0.5)),
        c.add(t1.scale(0.5)).sub(t2.scale(0.5)),
        c.add(t1.scale(0.5)).add(t2.scale(0.5)),
        c.sub(t1.scale(0.5)).add(t2.scale(0.5)),
      ];
      const i0 = corners.map((p) => b.v(p.x, p.y, p.z, nrm.x, nrm.y, nrm.z));
      // (t1, t2, n) が右手系なので t1→t2 の順は n から見て反時計回り
      b.t(i0[0], i0[1], i0[2]);
      b.t(i0[0], i0[2], i0[3]);
    }
    return b.build();
  },

  /** 星形 (XY 平面、+Z 向き、両面)。 */
  star(outer: number, inner: number, points = 5): MeshData {
    const b = new Builder();
    for (let side = 0; side < 2; side++) {
      const nz = side === 0 ? 1 : -1;
      const c = b.v(0, 0, 0, 0, 0, nz);
      const first = b.p.length / 3;
      for (let i = 0; i <= points * 2; i++) {
        const a = PI / 2 + (i * PI) / points;
        const r = i % 2 === 0 ? outer : inner;
        b.v(Math.cos(a) * r, Math.sin(a) * r, 0, 0, 0, nz);
      }
      for (let i = 0; i < points * 2; i++) {
        if (side === 0) b.t(c, first + i, first + i + 1);
        else b.t(c, first + i + 1, first + i);
      }
    }
    return b.build();
  },

  /** Z の字 (幅 1、太さ t の 3 本の帯、XY 平面、両面)。 */
  zGlyph(t = 0.18): MeshData {
    const b = new Builder();
    const bar = (ax: number, ay: number, bx: number, by: number) => {
      const d = new V3(bx - ax, by - ay, 0).normalized();
      const nx = -d.y * t * 0.5;
      const ny = d.x * t * 0.5;
      for (let side = 0; side < 2; side++) {
        const nz = side === 0 ? 1 : -1;
        const p0 = b.v(ax - nx, ay - ny, 0, 0, 0, nz);
        const p1 = b.v(bx - nx, by - ny, 0, 0, 0, nz);
        const p2 = b.v(bx + nx, by + ny, 0, 0, 0, nz);
        const p3 = b.v(ax + nx, ay + ny, 0, 0, 0, nz);
        if (side === 0) {
          b.t(p0, p1, p2);
          b.t(p0, p2, p3);
        } else {
          b.t(p0, p2, p1);
          b.t(p0, p3, p2);
        }
      }
    };
    bar(-0.5, 0.5, 0.5, 0.5);
    bar(0.5, 0.5, -0.5, -0.5);
    bar(-0.5, -0.5, 0.5, -0.5);
    return b.build();
  },
};
