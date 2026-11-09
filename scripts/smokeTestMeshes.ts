import { Meshes } from "../src/three/meshes";

const checks: Array<[string, () => ReturnType<typeof Meshes.plane>]> = [
  ["plane", () => Meshes.plane(1, 1)],
  ["circle", () => Meshes.circle(1, 16)],
  ["ring", () => Meshes.ring(0.4, 0.48, 32)],
  ["cylinder", () => Meshes.cylinder(0.5, 0.5, 1, 16)],
  ["cube", () => Meshes.cube()],
  ["star", () => Meshes.star(1, 0.5, 5)],
  ["zGlyph", () => Meshes.zGlyph()],
];

for (const [name, fn] of checks) {
  const d = fn();
  console.log(name, "verts=", d.vertexCount, "tris=", d.indices.length / 3, "min=", d.min, "max=", d.max);
}
