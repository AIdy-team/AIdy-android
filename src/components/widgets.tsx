import React, { useEffect, useRef, useState } from "react";
import { Animated, Easing, Pressable, ScrollView, StyleProp, Text, TextStyle, View, ViewStyle } from "react-native";
import { Theme } from "./theme";
import { Icon } from "./icons";
import { IconId } from "./iconIds";
import { AidyFace, AidyMascot, MascotState } from "./mascot";

export const EaseCss = Easing.bezier(0.25, 0.1, 0.25, 1);
export const PageSnapEasing = Easing.bezier(0.2, 0.9, 0.3, 1);

/** UI.Label 相当。 */
export function Label({
  text,
  size,
  color,
  bold = false,
  style,
  maxLines,
  align,
}: {
  text: string;
  size: number;
  color: string;
  bold?: boolean;
  style?: StyleProp<TextStyle>;
  maxLines?: number;
  align?: "left" | "center" | "right";
}) {
  return (
    <Text
      style={[
        { color, fontSize: size, fontWeight: bold ? "700" : "400", lineHeight: size * 1.3, textAlign: align },
        style,
      ]}
      numberOfLines={maxLines}
      ellipsizeMode="tail"
    >
      {text}
    </Text>
  );
}

/** UI.Box 相当: 角丸の背景 + 影 + 枠線。 */
export function boxStyle(bg: string, radius: number, shadow = 0, border?: string, borderWidth = 1): ViewStyle {
  return {
    backgroundColor: bg,
    borderRadius: radius,
    ...(border ? { borderWidth, borderColor: border } : null),
    ...(shadow > 0
      ? {
          shadowColor: "#000",
          shadowOpacity: 0.3,
          shadowRadius: shadow,
          shadowOffset: { width: 0, height: shadow * 0.5 },
          elevation: shadow,
        }
      : null),
  };
}

/** 押している間だけ縮むタップ可能View。Clickable + pressScale 相当。 */
export function Pressed({
  pressScale = 1,
  enabled = true,
  onPress,
  style,
  children,
}: {
  pressScale?: number;
  enabled?: boolean;
  onPress: () => void;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}) {
  const scale = useRef(new Animated.Value(1)).current;
  const animate = (toValue: number) => Animated.timing(scale, { toValue, duration: 150, useNativeDriver: true }).start();
  return (
    <Animated.View style={{ transform: [{ scale }] }}>
      <Pressable
        disabled={!enabled}
        onPressIn={() => animate(pressScale)}
        onPressOut={() => animate(1)}
        onPress={onPress}
        style={style}
      >
        {children}
      </Pressable>
    </Animated.View>
  );
}

/** WebDemo の .switch (44x24 トグル)。 */
export function AidySwitch({
  on,
  onChange,
  scale = 1,
}: {
  on: boolean;
  onChange: (v: boolean) => void;
  scale?: number;
}) {
  const t = useRef(new Animated.Value(on ? 1 : 0)).current;
  useEffect(() => {
    Animated.timing(t, { toValue: on ? 1 : 0, duration: 300, easing: EaseCss, useNativeDriver: true }).start();
  }, [on, t]);
  const knobX = t.interpolate({ inputRange: [0, 1], outputRange: [3, 23] });
  return (
    <Pressable onPress={() => onChange(!on)} style={{ width: 44, height: 24, justifyContent: "center" }}>
      <View
        style={{
          width: 44,
          height: 24,
          borderRadius: 12,
          backgroundColor: on ? Theme.Primary : Theme.Slate300,
          transform: [{ scale }],
        }}
      >
        <Animated.View
          style={{
            position: "absolute",
            top: 3,
            width: 18,
            height: 18,
            borderRadius: 9,
            backgroundColor: "#fff",
            transform: [{ translateX: knobX }],
          }}
        />
      </View>
    </Pressable>
  );
}

/** 状態を自分で持つスイッチ。 */
export function AidySwitchUncontrolled({
  initial,
  scale = 1,
  onChange,
}: {
  initial: boolean;
  scale?: number;
  onChange?: (v: boolean) => void;
}) {
  const [on, setOn] = useState(initial);
  return (
    <AidySwitch
      on={on}
      scale={scale}
      onChange={(v) => {
        setOn(v);
        onChange?.(v);
      }}
    />
  );
}

/** section-title。 */
export function SectionTitle({ text, first }: { text: string; first: boolean }) {
  return (
    <View style={{ marginTop: first ? 16 : 6, marginBottom: 8 }}>
      <Label text={text} size={14} color={Theme.TextSub} bold style={{ paddingLeft: 4 }} />
    </View>
  );
}

/** card-item (アイコン + タイトル + 説明 + 右側の部品)。 */
export function Card({
  icon,
  iconColor,
  iconBg,
  title,
  sub,
  onPress,
  trailing,
}: {
  icon?: IconId | null;
  iconColor?: string;
  iconBg?: string;
  title: string;
  sub: string;
  onPress?: () => void;
  trailing?: React.ReactNode;
}) {
  const Wrapper = onPress ? Pressable : View;
  return (
    <View style={{ marginBottom: 10 }}>
      <Wrapper
        onPress={onPress}
        style={[
          boxStyle("#fff", 16, 1, Theme.Slate100),
          { flexDirection: "row", alignItems: "center", paddingHorizontal: 16, paddingVertical: 14, gap: 14 },
        ]}
      >
        {icon != null && (
          <View
            style={{
              width: 42,
              height: 42,
              borderRadius: 12,
              backgroundColor: iconBg ?? Theme.Slate100,
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <Icon icon={icon} size={18} color={iconColor ?? Theme.TextMain} />
          </View>
        )}
        <View style={{ flex: 1, gap: 2 }}>
          <Label text={title} size={14} color={Theme.TextMain} bold />
          <Label text={sub} size={12} color={Theme.TextSub} />
        </View>
        {trailing}
      </Wrapper>
    </View>
  );
}

/** デフォルメ Aidy がしゃべる吹き出し型のお知らせ。ToastState.kt 相当。 */
export class ToastState {
  text = "";
  face: AidyFace = "Happy";
  shownAt = 0;
  seconds = 2.6;
  serial = 0;
  readonly mascot = new MascotState();
  private listeners: Array<() => void> = [];

  /** seconds <= 0 なら出しっぱなし。 */
  show(face: AidyFace, text: string, seconds = 2.6): void {
    this.face = face;
    this.text = text;
    this.seconds = seconds;
    this.shownAt = Date.now();
    this.serial++;
    this.mascot.react(face, 1.2);
    for (const l of this.listeners.slice()) l();
  }

  subscribe(l: () => void): () => void {
    this.listeners.push(l);
    return () => {
      this.listeners = this.listeners.filter((x) => x !== l);
    };
  }
}

export function AidyToast({ state, style }: { state: ToastState; style?: StyleProp<ViewStyle> }) {
  const [, setTick] = useState(0);
  const [visible, setVisible] = useState(false);
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(1)).current;
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () =>
      state.subscribe(() => {
        setTick((t) => t + 1);
        setVisible(true);
        Animated.timing(opacity, { toValue: 1, duration: 200, useNativeDriver: true }).start();
        Animated.timing(scale, { toValue: 1.08, duration: 200, useNativeDriver: true }).start(() => {
          Animated.timing(scale, { toValue: 1, duration: 150, useNativeDriver: true }).start();
        });
        if (timer.current) clearTimeout(timer.current);
        if (state.seconds > 0) {
          timer.current = setTimeout(() => {
            Animated.timing(opacity, { toValue: 0, duration: 250, useNativeDriver: true }).start();
            setTimeout(() => setVisible(false), 260);
          }, state.seconds * 1000);
        }
      }),
    [state, opacity, scale],
  );

  if (!visible) return null;
  return (
    <Animated.View
      style={[
        boxStyle("#fff", 22, 6, Theme.hex(0xe0e7ff), 1.5),
        { flexDirection: "row", alignItems: "center", gap: 8, paddingVertical: 6, paddingLeft: 8, paddingRight: 16 },
        { opacity, transform: [{ scale }] },
        style,
      ]}
    >
      <AidyMascot size={30} face={state.face} state={state.mascot} />
      <Label text={state.text} size={13} color={Theme.TextMain} bold />
    </Animated.View>
  );
}

/** 角丸のピル (半透明白 + 影)。 */
export function pillStyle(bg = "rgba(255,255,255,0.88)", radius = 16, shadow = 3): ViewStyle {
  return boxStyle(bg, radius, shadow);
}

/** ページ本体 (縦スクロール、左右 16dp の余白、下は 24dp)。 */
export function ScrollPage({ style, children }: { style?: StyleProp<ViewStyle>; children: React.ReactNode }) {
  return (
    <ScrollView
      style={[{ flex: 1 }, style]}
      contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 24 }}
    >
      {children}
    </ScrollView>
  );
}
