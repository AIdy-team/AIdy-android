import React, { useLayoutEffect, useMemo, useRef, useState } from "react";
import { Modal, Pressable, ScrollView, View } from "react-native";
import { LinearGradient } from "expo-linear-gradient";
import { Gesture, GestureDetector, GestureHandlerRootView } from "react-native-gesture-handler";
import { Theme } from "./theme";
import { Label, Pressed, SmallButton, boxStyle } from "./widgets";
import { BodyColor, ColorPattern } from "../data/bodyColor";
import { BodyLayout, ModuleCatalog, ModuleKind } from "../data/modules";

/** 0xffRRGGBB → [色相 0〜360, 彩度 0〜1, 明るさ 0〜1]。 */
function toHsv(c: number): [number, number, number] {
  const r = ((c >> 16) & 0xff) / 255;
  const g = ((c >> 8) & 0xff) / 255;
  const b = (c & 0xff) / 255;
  const max = Math.max(r, g, b);
  const d = max - Math.min(r, g, b);
  let h = 0;
  if (d > 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return [h, max === 0 ? 0 : d / max, max];
}

function fromHsv(h: number, s: number, v: number): number {
  const c = v * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = v - c;
  let [r, g, b] = [0, 0, 0];
  if (h < 60) [r, g, b] = [c, x, 0];
  else if (h < 120) [r, g, b] = [x, c, 0];
  else if (h < 180) [r, g, b] = [0, c, x];
  else if (h < 240) [r, g, b] = [0, x, c];
  else if (h < 300) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];
  const to = (n: number) => Math.round((n + m) * 255);
  return ((0xff << 24) | (to(r) << 16) | (to(g) << 8) | to(b)) >>> 0;
}

const css = (c: number) => BodyColor.css(c);

/** パターンの見た目: 左半分が本体の色、右半分がパーツの色 (取り付け口ごとの色があれば小さな点で)。 */
export function PatternDot({ pattern, size }: { pattern: ColorPattern; size: number }) {
  const inner = size * 0.84;
  const extra = Array.from(new Set(pattern.slots.values())).slice(0, 3);
  return (
    <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: "#fff", borderWidth: 1, borderColor: Theme.Border, alignItems: "center", justifyContent: "center" }}>
      <View style={{ width: inner, height: inner, borderRadius: inner / 2, overflow: "hidden", flexDirection: "row" }}>
        <View style={{ flex: 1, backgroundColor: css(pattern.body) }} />
        <View style={{ flex: 1, backgroundColor: css(pattern.parts ?? BodyColor.PartDefault) }} />
      </View>
      {extra.map((c, i) => {
        const r = inner * 0.22;
        return (
          <View
            key={i}
            style={{
              position: "absolute",
              left: size / 2 + inner * 0.45 - r,
              top: size / 2 - inner * 0.45 + i * inner * 0.45 - r,
              width: r * 2,
              height: r * 2,
              borderRadius: r,
              backgroundColor: "#fff",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <View style={{ width: r * 1.45, height: r * 1.45, borderRadius: r, backgroundColor: css(c) }} />
          </View>
        );
      })}
    </View>
  );
}

const TargetBody = "body";
const TargetAllParts = "*";

/**
 * 色のパターンを選ぶダイアログ (ColorPicker.kt の ColorPatternDialog)。上で対象 (本体 / パーツ全部 / 付いているパーツ) を選び、
 * 彩度・明るさの四角と色相のバーでその色を変える。下にパターンのプリセット (保存したもの + 用意したもの)。
 * attached = いま付いているパーツ (取り付け口 → 種類)。onPreview は選んでいる途中のパターン (3D にすぐ映す用)。
 */
export function ColorPatternDialog({
  visible,
  title,
  initial,
  attached,
  onPreview,
  onConfirm,
  onDismiss,
}: {
  visible: boolean;
  title: string;
  initial: ColorPattern;
  attached: Map<string, ModuleKind>;
  onPreview?: (p: ColorPattern) => void;
  onConfirm: (p: ColorPattern) => void;
  onDismiss: () => void;
}) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onDismiss}>
      {visible && <DialogBody title={title} initial={initial} attached={attached} onPreview={onPreview} onConfirm={onConfirm} onDismiss={onDismiss} />}
    </Modal>
  );
}

function DialogBody({
  title,
  initial,
  attached,
  onPreview,
  onConfirm,
  onDismiss,
}: {
  title: string;
  initial: ColorPattern;
  attached: Map<string, ModuleKind>;
  onPreview?: (p: ColorPattern) => void;
  onConfirm: (p: ColorPattern) => void;
  onDismiss: () => void;
}) {
  const [pattern, setPattern] = useState(initial);
  const [target, setTarget] = useState(TargetBody);
  const [message, setMessage] = useState("");
  const [, setVersion] = useState(0);
  const partSlots = useMemo(() => Array.from(attached.entries()).filter(([, k]) => k !== "Face"), [attached]);
  const [hsv, setHsv] = useState(() => toHsv(initial.body));
  // ジェスチャーのコールバックから最新の値を読むため
  const state = useRef({ pattern, target, hsv });
  useLayoutEffect(() => {
    state.current = { pattern, target, hsv };
  });

  const colorOf = (t: string, p: ColorPattern = pattern): number =>
    t === TargetBody ? p.body : t === TargetAllParts ? p.parts ?? BodyColor.PartDefault : p.partColor(t);

  const update = (p: ColorPattern) => {
    setPattern(p);
    onPreview?.(p);
  };

  /** 灰色 (彩度 0) のときは色相を保つ (バーの位置が飛ばないように)。 */
  const hsvFor = (c: number, prevHue: number): [number, number, number] => {
    const h = toHsv(c);
    return [h[1] > 0.001 ? h[0] : prevHue, h[1], h[2]];
  };

  const applyHsv = (next: [number, number, number]) => {
    setHsv(next);
    state.current = { ...state.current, hsv: next };
    const { pattern: p, target: t } = state.current;
    const c = fromHsv(next[0], next[1], next[2]);
    update(t === TargetBody ? p.withBody(c) : t === TargetAllParts ? p.withParts(c) : p.withSlot(t, c));
  };

  const select = (t: string) => {
    setTarget(t);
    setHsv((h) => hsvFor(colorOf(t), h[0]));
  };

  const use = (p: ColorPattern) => {
    update(p);
    setHsv((h) => hsvFor(colorOf(target, p), h[0]));
  };

  // 彩度 (横) ・ 明るさ (縦)
  const svSize = useRef({ w: 1, h: 1 });
  const svAt = (x: number, y: number) => {
    const s = Math.min(1, Math.max(0, x / svSize.current.w));
    const v = 1 - Math.min(1, Math.max(0, y / svSize.current.h));
    applyHsv([state.current.hsv[0], s, v]);
  };
  const svPan = Gesture.Pan()
    .runOnJS(true)
    .minDistance(0)
    .onBegin((e) => svAt(e.x, e.y))
    .onUpdate((e) => svAt(e.x, e.y));

  // 色相
  const hueWidth = useRef(1);
  const hueAt = (x: number) => {
    const hue = Math.min(1, Math.max(0, x / hueWidth.current)) * 359.9;
    applyHsv([hue, state.current.hsv[1], state.current.hsv[2]]);
  };
  const huePan = Gesture.Pan()
    .runOnJS(true)
    .minDistance(0)
    .onBegin((e) => hueAt(e.x))
    .onUpdate((e) => hueAt(e.x));

  const [hue, sat, val] = hsv;
  const current = fromHsv(hue, sat, val);
  const pure = css(fromHsv(hue, 1, 1));
  const saved = BodyColor.saved;

  return (
    // Modal の中はジェスチャーのルートが別になるので、もう一度包む
    <GestureHandlerRootView style={{ flex: 1 }}>
      <Pressable onPress={onDismiss} style={{ flex: 1, backgroundColor: "rgba(15,23,42,0.45)", justifyContent: "center", padding: 20 }}>
        <Pressable onPress={() => {}} style={[boxStyle("#fff", 22, 12), { padding: 16, gap: 10 }]}>
          {/* 見出し + いまのパターン */}
          <View style={{ flexDirection: "row", alignItems: "center", gap: 10 }}>
            <PatternDot pattern={pattern} size={36} />
            <View style={{ flex: 1 }}>
              <Label text={title} size={15} color={Theme.TextMain} bold />
              <Label text={`本体 ${BodyColor.hex(pattern.body)} ・ パーツ ${BodyColor.hex(pattern.parts ?? BodyColor.PartDefault)}`} size={11} color={Theme.TextSub} />
            </View>
          </View>

          {/* 色を変える対象 */}
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
            <TargetChip text="本体" color={colorOf(TargetBody)} selected={target === TargetBody} onPress={() => select(TargetBody)} />
            <TargetChip text="パーツ全部" color={colorOf(TargetAllParts)} selected={target === TargetAllParts} onPress={() => select(TargetAllParts)} />
            {partSlots.map(([id, kind]) => {
              const slot = BodyLayout.get(id);
              return (
                <TargetChip
                  key={id}
                  text={ModuleCatalog.name(kind) + (slot ? ` (${BodyLayout.faceLabel(slot.face)}${slot.label})` : "")}
                  color={colorOf(id)}
                  selected={target === id}
                  onPress={() => select(id)}
                />
              );
            })}
          </ScrollView>

          {/* 彩度・明るさ */}
          <GestureDetector gesture={svPan}>
            <View
              style={{ height: 140, borderRadius: 12, overflow: "hidden" }}
              onLayout={(e) => (svSize.current = { w: Math.max(1, e.nativeEvent.layout.width), h: Math.max(1, e.nativeEvent.layout.height) })}
            >
              <LinearGradient colors={["#ffffff", pure]} start={{ x: 0, y: 0 }} end={{ x: 1, y: 0 }} style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0 }} />
              <LinearGradient colors={["rgba(0,0,0,0)", "#000000"]} start={{ x: 0, y: 0 }} end={{ x: 0, y: 1 }} style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0 }} />
              <View
                pointerEvents="none"
                style={{
                  position: "absolute",
                  left: `${sat * 100}%`,
                  top: `${(1 - val) * 100}%`,
                  marginLeft: -11,
                  marginTop: -11,
                  width: 22,
                  height: 22,
                  borderRadius: 11,
                  borderWidth: 3,
                  borderColor: "#fff",
                  shadowColor: "#000",
                  shadowOpacity: 0.4,
                  shadowRadius: 1,
                }}
              />
            </View>
          </GestureDetector>

          {/* 色相 */}
          <GestureDetector gesture={huePan}>
            <View style={{ height: 24, justifyContent: "center" }} onLayout={(e) => (hueWidth.current = Math.max(1, e.nativeEvent.layout.width))}>
              <LinearGradient
                colors={["#ff0000", "#ffff00", "#00ff00", "#00ffff", "#0000ff", "#ff00ff", "#ff0000"]}
                start={{ x: 0, y: 0 }}
                end={{ x: 1, y: 0 }}
                style={{ height: 24, borderRadius: 12 }}
              />
              <View
                pointerEvents="none"
                style={{ position: "absolute", left: `${(hue / 360) * 100}%`, marginLeft: -11, width: 22, height: 22, borderRadius: 11, borderWidth: 3, borderColor: "#fff" }}
              />
            </View>
          </GestureDetector>

          {/* 見本の色 */}
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 6 }}>
            {BodyColor.BuiltIn.map((c) => (
              <Swatch key={c} color={c} selected={c === current} onPress={() => applyHsv(hsvFor(c, hue))} />
            ))}
          </ScrollView>

          {/* パターンのプリセット */}
          <View style={{ flexDirection: "row", alignItems: "center" }}>
            <Label text="パターン (本体 + パーツ)" size={11} color={Theme.TextSub} bold style={{ flex: 1 }} />
            <SmallButton
              text="＋ 保存"
              bg={Theme.PrimaryLight}
              fg={Theme.Primary}
              onPress={() => {
                BodyColor.savePreset(pattern);
                setVersion((v) => v + 1);
                setMessage("パターンを保存したよ");
              }}
            />
          </View>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 4, alignItems: "center" }}>
            {saved.map((p) => (
              <PatternSwatch
                key={"s" + p.format()}
                pattern={p}
                selected={p.equals(pattern)}
                onPress={() => use(p)}
                onLongPress={() => {
                  BodyColor.deletePreset(p);
                  setVersion((v) => v + 1);
                  setMessage("保存したパターンを削除したよ");
                }}
              />
            ))}
            {saved.length > 0 && <View style={{ width: 1, height: 40, marginHorizontal: 2, backgroundColor: Theme.Border }} />}
            {BodyColor.BuiltInPatterns.map((p) => (
              <PatternSwatch key={"b" + p.format()} pattern={p} selected={p.equals(pattern)} onPress={() => use(p)} />
            ))}
          </ScrollView>
          <Label
            text={message.length > 0 ? message : "保存したパターンは長押しで削除"}
            size={11}
            color={message.length > 0 ? Theme.Primary : Theme.Slate400}
            align="center"
          />

          <View style={{ flexDirection: "row", gap: 8, marginTop: 2 }}>
            <DialogButton text="キャンセル" primary={false} onPress={onDismiss} />
            <DialogButton text="決定" primary onPress={() => onConfirm(pattern)} />
          </View>
        </Pressable>
      </Pressable>
    </GestureHandlerRootView>
  );
}

function TargetChip({ text, color, selected, onPress }: { text: string; color: number; selected: boolean; onPress: () => void }) {
  return (
    <Pressed
      onPress={onPress}
      pressScale={0.95}
      style={{
        height: 32,
        flexDirection: "row",
        alignItems: "center",
        gap: 6,
        paddingLeft: 6,
        paddingRight: 10,
        borderRadius: 16,
        backgroundColor: selected ? Theme.PrimaryLight : Theme.Slate100,
        borderWidth: selected ? 2 : 0,
        borderColor: Theme.Primary,
      }}
    >
      <View style={{ width: 20, height: 20, borderRadius: 10, backgroundColor: css(color), borderWidth: 1, borderColor: Theme.Border }} />
      <Label text={text} size={12} color={selected ? Theme.Primary : Theme.Slate700} bold />
    </Pressed>
  );
}

function Swatch({ color, selected, onPress }: { color: number; selected: boolean; onPress: () => void }) {
  return (
    <Pressed
      onPress={onPress}
      pressScale={0.92}
      style={{
        width: 32,
        height: 32,
        borderRadius: 16,
        borderWidth: selected ? 3 : 1,
        borderColor: selected ? Theme.Primary : Theme.Border,
        padding: selected ? 3 : 2,
      }}
    >
      <View style={{ flex: 1, borderRadius: 16, backgroundColor: css(color) }} />
    </Pressed>
  );
}

function PatternSwatch({ pattern, selected, onPress, onLongPress }: { pattern: ColorPattern; selected: boolean; onPress: () => void; onLongPress?: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      style={{ width: 40, height: 40, borderRadius: 20, borderWidth: selected ? 3 : 0, borderColor: Theme.Primary, alignItems: "center", justifyContent: "center" }}
    >
      <PatternDot pattern={pattern} size={selected ? 30 : 34} />
    </Pressable>
  );
}

function DialogButton({ text, primary, onPress }: { text: string; primary: boolean; onPress: () => void }) {
  return (
    <View style={{ flex: 1 }}>
      <Pressed
        onPress={onPress}
        pressScale={0.97}
        style={{ height: 44, borderRadius: 22, alignItems: "center", justifyContent: "center", backgroundColor: primary ? Theme.Primary : Theme.Slate100 }}
      >
        <Label text={text} size={13} color={primary ? "#fff" : Theme.Slate700} bold />
      </Pressed>
    </View>
  );
}
