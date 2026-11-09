import * as THREE from "three";
import { Q, V3 } from "./math";
import { lit, unlit, meshFrom, emptyGroup } from "./gfx";
import { AidyColors, ModuleShape, buildModuleShape } from "./rig";
import { ModuleKind } from "../data/modules";

/**
 * トレイのプレビュー用に、モジュールの3Dを単体で組み立てる。
 * Kotlin版 ModulePreview.kt はCPUソフトラスタライザでBitmapを焼くが、
 * ここではユーザー承認済みの簡略化として react-three-fiber の小さな Canvas でそのまま描く。
 */
export async function buildPreviewGroup(kind: ModuleKind): Promise<THREE.Group> {
  const shape = await buildModuleShape(kind, null, 1.1);
  return groupFromShape(shape);
}

function groupFromShape(shape: ModuleShape): THREE.Group {
  const root = emptyGroup("PreviewRoot");
  const satin = lit(AidyColors.Satin, 0.55, 0.35);
  const lens = unlit(AidyColors.Lens);
  const pivotNodes: THREE.Group[] = shape.pivots.map(() => emptyGroup());
  for (let i = 0; i < shape.pivots.length; i++) {
    const p = shape.pivots[i];
    const n = pivotNodes[i];
    n.position.copy(p.p.toThree());
    n.quaternion.copy(p.r.toThree());
    n.scale.copy(p.s.toThree());
    root.add(n);
  }
  for (const part of shape.parts) {
    const parent = part.pivot < 0 ? root : pivotNodes[part.pivot];
    const mat = part.mat === "Lens" ? lens : satin;
    const mesh = meshFrom(part.mesh, mat);
    mesh.position.copy(part.local.p.toThree());
    mesh.quaternion.copy(part.local.r.toThree());
    mesh.scale.copy(part.local.s.toThree());
    parent.add(mesh);
  }
  return root;
}

/** 空の取り付け面に対する向き調整用 (slot なしの既定)。 */
export function previewSpin(group: THREE.Group, yawDeg: number): void {
  group.quaternion.copy(Q.axisAngle(V3.Up, (yawDeg * Math.PI) / 180).toThree());
}
