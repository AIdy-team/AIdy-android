import React, { useEffect, useMemo, useRef, useState } from "react";
import { Image, StyleSheet, View } from "react-native";
import { Gesture, GestureDetector } from "react-native-gesture-handler";
import { Theme } from "./theme";
import { Icon } from "./icons";
import { Label, boxStyle } from "./widgets";
import { HomeRoom } from "../three/homeRoom";
import { renderModulePreview } from "../three/modulePreview";
import { BodyLayout, ModuleCatalog, ModuleConfig, ModuleKind } from "../data/modules";
import { clamp01, inverseLerp, lerp, repeatRange, smoothStep } from "../three/math";

const CircleDp = 68; // 中央のプレビューの直径
const ItemW = CircleDp + 16;
const VisibleSlots = 4; // 表示幅に並ぶ間隔の数 (中央 + 左右 1 つずつ + 端に半分ずつ)
const Arc = 10; // 端へ行くほど下がる量 (円形の軌道)
const SpinSpeed = 45; // 中央のプレビューが回る速さ (度/秒)
const PreviewPx = 160; // 止まっているプレビューの画素数
const SpinPx = 128; // 回しているプレビューの画素数 (軽くするため少し小さく)
const DangerColor = Theme.hex(0xef4444);

// ---------------------------------------------------------------- プレビュー画像 (全モジュール共通、アプリで 1 つ)

/** モジュールのプレビュー画像 (PNG の data URI)。トレイとドラッグ中の丸で共有する。 */
class PreviewStore {
  readonly images = new Map<ModuleKind, string>();
  private listeners: Array<() => void> = [];
  private started = false;

  /** はじめに全部描く (1 つずつ、UI を止めないよう間をあける)。 */
  start(): void {
    if (this.started) return;
    this.started = true;
    void (async () => {
      for (const k of ModuleCatalog.All) {
        try {
          this.set(k, await renderModulePreview(k, 0, PreviewPx));
        } catch (e) {
          console.warn(`[AIDY] プレビューの描画に失敗 ${k}:`, e);
        }
        await new Promise((r) => setTimeout(r, 0));
      }
    })();
  }

  set(k: ModuleKind, uri: string): void {
    this.images.set(k, uri);
    for (const l of this.listeners.slice()) l();
  }

  subscribe(l: () => void): () => void {
    this.listeners.push(l);
    return () => {
      this.listeners = this.listeners.filter((x) => x !== l);
    };
  }
}

export const ModulePreviews = new PreviewStore();

function usePreviews(): Map<ModuleKind, string> {
  const [, setTick] = useState(0);
  useEffect(() => {
    ModulePreviews.start();
    return ModulePreviews.subscribe(() => setTick((t) => t + 1));
  }, []);
  return ModulePreviews.images;
}

/** モジュールの色の枠が付いた丸いプレビュー画像。 */
export function CirclePreview({ kind, size }: { kind: ModuleKind; size: number }) {
  const previews = usePreviews();
  const uri = previews.get(kind);
  return (
    <View style={{ width: size, height: size, borderRadius: size / 2, overflow: "hidden", backgroundColor: "#fff", borderWidth: 2, borderColor: ModuleCatalog.tint(kind) }}>
      {uri ? (
        <Image source={{ uri }} style={{ width: "100%", height: "100%" }} fadeDuration={0} />
      ) : (
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center" }}>
          <Icon icon={ModuleCatalog.icon(kind)} size={size * 0.35} color={ModuleCatalog.tint(kind)} />
        </View>
      )}
    </View>
  );
}

// ---------------------------------------------------------------- カルーセル

/** Unity の Mathf.SmoothDamp。velocity は [速度] (書き換える)。 */
function smoothDamp(current: number, target: number, velocity: number[], smoothTime: number, dt: number): number {
  const st = Math.max(0.0001, smoothTime);
  const omega = 2 / st;
  const x = omega * dt;
  const exp = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
  const change = current - target;
  const temp = (velocity[0] + omega * change) * dt;
  velocity[0] = (velocity[0] - omega * temp) * exp;
  let out = target + (change + temp) * exp;
  if (target - current > 0 === out > target) {
    out = target;
    velocity[0] = (out - target) / dt;
  }
  return out;
}

export interface TrayDragHandlers {
  /** トレイからモジュールを上へ引き出した (window 座標)。 */
  beginDrag: (kind: ModuleKind, x: number, y: number) => void;
  drag: (x: number, y: number) => void;
  endDrag: (x: number, y: number) => void;
}

/**
 * 付け替えプレビュー画面下のモジュールトレイ (ModuleTray.kt のポート)。モジュールを丸い 3D プレビューで並べ、左右にスワイプして選ぶ。
 * 4 つ以上あるときは円形にループするカルーセル (中央が大きく、端は小さく下がる。中央のモジュールはゆっくり回る)。
 * 面を選ぶ前は全モジュール、3D モデルの面をタップするとその面に付けられるモジュールだけになる。
 * モジュールを上へドラッグして 3D モデルのスロット (光る丸) へドロップで取り付け/差し替え。
 */
export function ModuleTray({
  room,
  active,
  hint,
  hintError,
  removing,
  handlers,
  onPagerLock,
  onTrayLayout,
}: {
  room: HomeRoom;
  active: boolean;
  hint: string;
  hintError: boolean;
  /** 3D モデルから持ってきたモジュールをドラッグ中 (「ここで離すと取り外し」を出す)。 */
  removing: boolean;
  handlers: TrayDragHandlers;
  /** トレイに触れている間はページの横スワイプを止める。 */
  onPagerLock?: (locked: boolean) => void;
  onTrayLayout?: (rect: { x: number; y: number; width: number; height: number }) => void;
}) {
  usePreviews();
  const face = room.selectedFace;
  const shown = useMemo(() => (face == null ? ModuleCatalog.All : ModuleCatalog.forFace(face)), [face]);
  const n = shown.length;
  const loop = n >= 4;

  // pos = 中央にいるモジュールの番号 (連続値。ループするので n の倍数ずれても同じ)
  const [pos, setPos] = useState(0);
  const anim = useRef({ pos: 0, target: 0, velocity: [0], swiping: false, swipeVelocity: 0, lastMove: 0, shown: shown as ModuleKind[] });
  const [width, setWidth] = useState(1);
  const stepPx = Math.max(width / VisibleSlots, CircleDp + 8);
  const trayRef = useRef<React.ComponentRef<typeof View>>(null);

  /** モジュール i の中央からのずれ (間隔の数)。 */
  const offsetOf = (i: number, p = anim.current.pos) =>
    loop ? repeatRange(i - p + n * 0.5, n) - n * 0.5 : i - (n - 1) * 0.5; // 3 つ以下は横に並べるだけ

  // 並びが変わったら、前に中央にいたモジュールを中央に (取り付けのたびに位置が戻らないように)
  useEffect(() => {
    const a = anim.current;
    const prev = a.shown;
    if (prev.length === shown.length && prev.every((k, i) => k === shown[i])) return;
    const focused = prev.length > 0 ? prev[((Math.round(a.pos) % prev.length) + prev.length) % prev.length] : null;
    const start = focused != null ? Math.max(0, shown.indexOf(focused)) : 0;
    a.shown = shown;
    a.target = shown.length >= 4 ? start : 0;
    a.pos = a.target;
    a.velocity[0] = 0;
    setPos(a.pos);
  }, [shown]);

  // スナップのアニメーション
  useEffect(() => {
    if (!active) return;
    let raf = 0;
    let last = 0;
    const step = (now: number) => {
      const dt = last === 0 ? 1 / 60 : Math.min(0.1, (now - last) / 1000);
      last = now;
      const a = anim.current;
      if (a.shown.length >= 4 && !a.swiping && a.pos !== a.target) {
        a.pos = smoothDamp(a.pos, a.target, a.velocity, 0.16, Math.max(dt, 0.0001));
        if (Math.abs(a.pos - a.target) < 0.0005) {
          // 止まったら 0..n-1 に戻しておく (ループなので見た目は同じ)
          a.target -= Math.floor(a.target / a.shown.length) * a.shown.length;
          a.pos = a.target;
          a.velocity[0] = 0;
        }
        setPos(a.pos);
      }
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [active]);

  // 中央のモジュールをゆっくり回す (止まっていて、ページが見えていて、ドラッグしていないとき)
  const spinBlocked = !active || removing;
  useEffect(() => {
    if (spinBlocked) return;
    let alive = true;
    let spinning: ModuleKind | null = null;
    let yaw = 0;
    void (async () => {
      let last = Date.now();
      while (alive) {
        await new Promise((r) => setTimeout(r, 60));
        const now = Date.now();
        const dt = (now - last) / 1000;
        last = now;
        const a = anim.current;
        const len = a.shown.length;
        const settled = len >= 4 && !a.swiping && Math.abs(a.pos - a.target) < 0.3;
        const center = settled ? a.shown[((Math.round(a.pos) % len) + len) % len] : null;
        if (center !== spinning) {
          // 回していたものは正面に戻す
          if (spinning) ModulePreviews.set(spinning, await renderModulePreview(spinning, 0, PreviewPx));
          spinning = center;
          yaw = 0;
        }
        if (!center || !alive) continue;
        yaw += SpinSpeed * dt;
        const uri = await renderModulePreview(center, yaw, SpinPx);
        if (alive && spinning === center) ModulePreviews.set(center, uri);
      }
      if (spinning) ModulePreviews.set(spinning, await renderModulePreview(spinning, 0, PreviewPx));
    })();
    return () => {
      alive = false;
    };
  }, [spinBlocked]);

  const fadeEnd = Math.min(2.4, n * 0.5 - 0.05);
  const fadeStart = fadeEnd - 0.7;
  const scaleOf = (a: number) => (loop ? lerp(1, 0.74, clamp01(a)) * lerp(1, 0.85, clamp01(a - 1)) : 0.92);
  const alphaOf = (a: number) => (loop ? 1 - smoothStep(0, 1, inverseLerp(fadeStart, fadeEnd, a)) : 1);

  /** 押した所のモジュール (中央に近いものを優先)。 */
  const hitAt = (x: number): number => {
    let hit = -1;
    let best = Infinity;
    for (let i = 0; i < n; i++) {
      const off = offsetOf(i);
      const a = Math.abs(off);
      if (alphaOf(a) <= 0.3) continue;
      const cx = width * 0.5 + off * stepPx;
      if (Math.abs(x - cx) <= ItemW * 0.5 * scaleOf(a) && a < best) {
        best = a;
        hit = i;
      }
    }
    return hit;
  };

  // ---- ジェスチャー: 横 = スワイプ、縦 = モジュールを引き出して取り付け、タップ = 横のモジュールを中央へ
  const mode = useRef<"none" | "swipe" | "drag">("none");
  const lastX = useRef(0);

  const pan = Gesture.Pan()
    .runOnJS(true)
    .minDistance(8)
    .onTouchesDown(() => onPagerLock?.(true))
    .onStart((e) => {
      const a = anim.current;
      const horizontal = Math.abs(e.translationX) > Math.abs(e.translationY);
      const hit = hitAt(e.x - e.translationX);
      lastX.current = e.translationX;
      a.lastMove = Date.now();
      if (horizontal || hit < 0) {
        if (!loop) {
          mode.current = "none"; // 3 つ以下は送る先がない
          return;
        }
        mode.current = "swipe";
        a.swiping = true;
        a.swipeVelocity = 0;
      } else {
        mode.current = "drag";
        handlers.beginDrag(shown[hit], e.absoluteX, e.absoluteY);
      }
    })
    .onUpdate((e) => {
      if (mode.current === "drag") {
        handlers.drag(e.absoluteX, e.absoluteY);
        return;
      }
      if (mode.current !== "swipe") return;
      const a = anim.current;
      const now = Date.now();
      const dt = Math.max(0.001, (now - a.lastMove) / 1000);
      a.lastMove = now;
      const d = -(e.translationX - lastX.current) / stepPx;
      lastX.current = e.translationX;
      a.pos += d;
      a.target = a.pos;
      a.swipeVelocity = lerp(a.swipeVelocity, d / dt, 0.35);
      setPos(a.pos);
    })
    .onEnd((e) => {
      if (mode.current === "drag") handlers.endDrag(e.absoluteX, e.absoluteY);
      if (mode.current === "swipe") {
        const a = anim.current;
        a.swiping = false;
        // はじいた勢いの分だけ先へ (1 回で最大 3 つ)
        const fling = Math.min(3, Math.max(-3, a.swipeVelocity * 0.12));
        a.target = Math.round(a.pos + fling);
        a.velocity[0] = a.swipeVelocity;
      }
    })
    .onFinalize(() => {
      const a = anim.current;
      if (a.swiping) {
        a.swiping = false;
        a.target = Math.round(a.pos);
      }
      mode.current = "none";
      onPagerLock?.(false);
    });

  const tap = Gesture.Tap()
    .runOnJS(true)
    .maxDistance(8)
    .onTouchesDown(() => onPagerLock?.(true))
    .onEnd((e) => {
      // 横のモジュールをタップしたら中央へ回す
      const i = hitAt(e.x);
      if (!loop || i < 0) return;
      const off = offsetOf(i);
      if (Math.abs(off) > 0.5) anim.current.target = Math.round(anim.current.pos + off);
    })
    .onFinalize(() => onPagerLock?.(false));

  // 見出し (着せ替えが変わったら作り直す)
  const [, setModuleVersion] = useState(ModuleConfig.version);
  useEffect(() => {
    const l = () => setModuleVersion(ModuleConfig.version);
    ModuleConfig.changed.push(l);
    return () => {
      const i = ModuleConfig.changed.indexOf(l);
      if (i >= 0) ModuleConfig.changed.splice(i, 1);
    };
  }, []);
  let title: string;
  if (face == null) title = `モジュール ${n}種 ・ 面をタップで取り付け`;
  else {
    const counts = new Map<ModuleKind, number>();
    for (const s of BodyLayout.onFace(face)) {
      const k = room.moduleAt(s.id);
      if (k) counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    const parts = Array.from(counts.entries()).map(([k, c]) => (c > 1 ? `${ModuleCatalog.name(k)}×${c}` : ModuleCatalog.name(k)));
    title = parts.length > 0 ? `${BodyLayout.faceLabel(face)} ・ 付いている: ${parts.join("、")}` : `${BodyLayout.faceLabel(face)}に付けられるモジュール`;
  }

  // 中央に近いものを手前に
  const order = Array.from({ length: n }, (_, i) => i).sort((a, b) => Math.abs(offsetOf(b, pos)) - Math.abs(offsetOf(a, pos)));

  return (
    <View
      ref={trayRef}
      style={styles.tray}
      onLayout={() => trayRef.current?.measureInWindow((x, y, w, h) => onTrayLayout?.({ x, y, width: w, height: h }))}
    >
      <View style={styles.header}>
        <Icon icon="Cubes" size={12} color={Theme.Primary} />
        <Label text={title} size={12} color={Theme.TextSub} bold maxLines={1} style={{ flex: 1 }} />
      </View>

      <GestureDetector gesture={Gesture.Exclusive(pan, tap)}>
        <View style={styles.carousel} onLayout={(e) => setWidth(Math.max(1, e.nativeEvent.layout.width))}>
          {order.map((i) => {
            const k = shown[i];
            const off = offsetOf(i, pos);
            const a = Math.abs(off);
            const scale = scaleOf(a);
            const y = loop ? -Arc * Math.min(a, 2.5) * Math.min(a, 2.5) * 0.5 : 0;
            const labelAlpha = loop ? clamp01(1.4 - a) : 1;
            const bold = !loop || a < 0.5;
            const x = width * 0.5 + off * stepPx - ItemW * 0.5;
            return (
              <View
                key={k}
                pointerEvents="none"
                style={{
                  position: "absolute",
                  left: x,
                  top: 4 - y,
                  width: ItemW,
                  height: CircleDp + 22,
                  alignItems: "center",
                  opacity: alphaOf(a),
                  // 上端を基準に縮める
                  transform: [{ translateY: -(CircleDp + 22) * (1 - scale) * 0.5 }, { scale }],
                }}
              >
                <CirclePreview kind={k} size={CircleDp} />
                <View style={{ flex: 1 }} />
                <Label text={ModuleCatalog.name(k)} size={11} color={Theme.TextMain} bold={bold} maxLines={1} align="center" style={{ opacity: labelAlpha, marginBottom: 2 }} />
              </View>
            );
          })}
        </View>
      </GestureDetector>

      <Label text={hint} size={11} color={hintError ? DangerColor : Theme.Primary} bold maxLines={1} align="center" style={{ height: 14 }} />

      {/* 3D モデルからモジュールを持ってきたときの「ここで離すと取り外し」 */}
      {removing && (
        <View pointerEvents="none" style={[StyleSheet.absoluteFill, styles.remove]}>
          <Icon icon="XMark" size={14} color={DangerColor} />
          <Label text="ここで離すと取り外し" size={13} color={DangerColor} bold />
        </View>
      )}
    </View>
  );
}

/** ドラッグ中のモジュール (指で隠れないよう、指の少し上に出す)。 */
export function ModuleDragGhost({ kind, x, y }: { kind: ModuleKind; x: number; y: number }) {
  return (
    <View pointerEvents="none" style={[styles.ghost, { left: x - 32, top: y - 40 - 32 }]}>
      <CirclePreview kind={kind} size={64} />
    </View>
  );
}

const styles = StyleSheet.create({
  tray: {
    ...boxStyle("#fff", 18, 4, Theme.Border),
    paddingHorizontal: 14,
    paddingTop: 10,
    paddingBottom: 8,
    gap: 4,
  },
  header: { height: 22, flexDirection: "row", alignItems: "center", gap: 6 },
  carousel: { height: CircleDp + 34, overflow: "hidden" },
  remove: {
    ...boxStyle(Theme.hex(0xfef2f2, 0.97), 18, 0, DangerColor, 2),
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 8,
  },
  ghost: {
    position: "absolute",
    opacity: 0.95,
    shadowColor: "#000",
    shadowOpacity: 0.3,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 4 },
    elevation: 8,
  },
});
