// 仮実装: Kotlin版 Mascot.kt は100x100グリッドのSDFで本格的なイラストを描くが、
// ここではPhase 1向けの簡易版 (角丸ボディ+目) を用意する。Phase 4でSkiaによるSDF描画に置き換える。
import React, { useEffect, useRef } from "react";
import { Animated, Easing, Pressable, View } from "react-native";
import { Theme } from "./theme";

/** デフォルメ Aidy の表情。ディスプレイに映る目の形だけが変わる。 */
export type AidyFace = "Normal" | "Blink" | "Happy" | "Sleepy" | "Surprised" | "Dizzy";

/** 表情の一時変化 (なでなで等) を保持する状態。MascotState.kt 相当。 */
export class MascotState {
  reactFace: AidyFace = "Happy";
  reactMs = 0;
  reactAt = 0;
  private listeners: Array<() => void> = [];

  /** 一時的に表情を変えてぴょんと跳ねる。 */
  react(face: AidyFace, durationSec = 1.2): void {
    this.reactFace = face;
    this.reactMs = durationSec * 1000;
    this.reactAt = Date.now();
    for (const l of this.listeners.slice()) l();
  }

  subscribe(l: () => void): () => void {
    this.listeners.push(l);
    return () => {
      this.listeners = this.listeners.filter((x) => x !== l);
    };
  }
}

function eyeShape(face: AidyFace): { w: number; h: number; tilt: number } {
  switch (face) {
    case "Blink": return { w: 0.5, h: 0.08, tilt: 0 };
    case "Happy": return { w: 0.42, h: 0.42, tilt: 0 };
    case "Sleepy": return { w: 0.5, h: 0.18, tilt: 0 };
    case "Surprised": return { w: 0.6, h: 0.6, tilt: 0 };
    case "Dizzy": return { w: 0.5, h: 0.5, tilt: 20 };
    default: return { w: 0.4, h: 0.55, tilt: 0 };
  }
}

/**
 * UI のワンポイントとして置くデフォルメ Aidy。タップで喜んで跳ねる (interactive)。
 * レイアウト上は size x size を占有する。
 */
export function AidyMascot({
  size,
  face,
  animate = true,
  interactive = false,
  state,
}: {
  size: number;
  face: AidyFace;
  animate?: boolean;
  interactive?: boolean;
  state?: MascotState;
}) {
  const localState = useRef(state ?? new MascotState()).current;
  const bounce = useRef(new Animated.Value(0)).current;
  const [shown, setShown] = React.useState<AidyFace>(face);

  useEffect(() => {
    if (!animate) return;
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(bounce, { toValue: 1, duration: 1400, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        Animated.timing(bounce, { toValue: 0, duration: 1400, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [animate, bounce]);

  useEffect(() => {
    const unsub = localState.subscribe(() => {
      setShown(localState.reactFace);
      const t = setTimeout(() => setShown(face), localState.reactMs);
      return () => clearTimeout(t);
    });
    return unsub;
  }, [localState, face]);

  useEffect(() => setShown(face), [face]);

  const translateY = bounce.interpolate({ inputRange: [0, 1], outputRange: [0, -size * 0.04] });

  const eye = eyeShape(shown);
  const bodyColor = "#ffffff";

  const content = (
    <Animated.View style={[{ width: size, height: size }, { transform: [{ translateY }] }]}>
      <View
        style={{
          width: size,
          height: size,
          borderRadius: size * 0.3,
          backgroundColor: bodyColor,
          borderWidth: Math.max(1, size * 0.03),
          borderColor: Theme.Slate700,
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <View
          style={{
            width: size * 0.7,
            height: size * 0.5,
            borderRadius: size * 0.1,
            backgroundColor: Theme.Slate900,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <View
            style={{
              width: size * eye.w * 0.5,
              height: size * eye.h * 0.5,
              borderRadius: size * 0.1,
              backgroundColor: "#f8fafc",
              transform: [{ rotate: `${eye.tilt}deg` }],
            }}
          />
        </View>
      </View>
    </Animated.View>
  );

  if (!interactive) return content;
  return <Pressable onPress={() => localState.react("Happy")}>{content}</Pressable>;
}
