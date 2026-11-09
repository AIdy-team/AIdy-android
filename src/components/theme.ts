/** WebDemo の CSS 変数・配色 (Kotlin版 Theme.kt と同じ)。RNではColor文字列("#rrggbb"/"rgba(...)")で表現する。 */
function hex(rgb: number, a = 1): string {
  const r = (rgb >> 16) & 0xff;
  const g = (rgb >> 8) & 0xff;
  const b = rgb & 0xff;
  return a >= 1 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${a})`;
}

export const Theme = {
  hex,
  Device: hex(0xf8fafc),
  Primary: hex(0x3b82f6),
  PrimaryLight: hex(0xeff6ff),
  TextMain: hex(0x1e293b),
  TextSub: hex(0x64748b),
  Border: hex(0xe2e8f0),
  Green: hex(0x10b981),
  Slate100: hex(0xf1f5f9),
  Slate300: hex(0xcbd5e1),
  Slate400: hex(0x94a3b8),
  Slate600: hex(0x475569),
  Slate700: hex(0x334155),
  Slate900: hex(0x0f172a),
};

/** CSS の cubic-bezier() と同じイージング。 */
export class CubicBezier {
  constructor(private x1: number, private y1: number, private x2: number, private y2: number) {}

  static readonly Ease = new CubicBezier(0.25, 0.1, 0.25, 1);
  static readonly EaseOut = new CubicBezier(0, 0, 0.58, 1);
  static readonly PageSnap = new CubicBezier(0.2, 0.9, 0.3, 1);

  private bez(a: number, b: number, s: number): number {
    const u = 1 - s;
    return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s;
  }

  evaluate(t: number): number {
    if (t <= 0) return 0;
    if (t >= 1) return 1;
    let lo = 0;
    let hi = 1;
    let s = t;
    for (let i = 0; i < 24; i++) {
      const x = this.bez(this.x1, this.x2, s);
      if (Math.abs(x - t) < 1e-5) break;
      if (x < t) lo = s;
      else hi = s;
      s = (lo + hi) * 0.5;
    }
    return this.bez(this.y1, this.y2, s);
  }
}
