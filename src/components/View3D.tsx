import React, { useEffect, useMemo, useRef } from "react";
import { Pressable, StyleSheet, View, ViewStyle } from "react-native";
import { Canvas, advance, useFrame, useThree } from "@react-three/fiber";
import { Gesture, GestureDetector } from "react-native-gesture-handler";
import { HomeRoom } from "../three/homeRoom";
import { ModuleKind } from "../data/modules";
import { Label, AidyToast, ToastState, boxStyle } from "./widgets";
import { Icon } from "./icons";
import { Theme } from "./theme";
import { AidyMascot, MascotState } from "./mascot";

const LongPressMs = 350;
const TapSlopDp = 10;
const SlotPickDp = 45;

export const BatteryLevel = 0.78;

/** 3D ビュー上のモジュールのドラッグ (つかんだモジュール, スロット, 指の位置 (window 座標)) を受ける側。View3D.kt の ModuleDragTarget。 */
export interface ModuleDragTarget {
  beginDrag(kind: ModuleKind, fromSlot: string | null, window: { x: number; y: number }): void;
  drag(window: { x: number; y: number }): void;
  endDrag(window: { x: number; y: number }): void;
}

/** HomeRoom を毎フレーム進め、three.js のカメラ/シーンを R3F に接続する。 */
function RoomScene({ room, active }: { room: HomeRoom; active: boolean }) {
  const { set } = useThree();
  useEffect(() => {
    set({ camera: room.camera.three });
  }, [room, set]);

  useFrame((_, delta) => {
    room.visible = active;
    if (active) room.frame(Math.min(delta, 0.1));
  });

  // React Native (New Architecture) では r3f の内部 requestAnimationFrame ループが、
  // この Canvas が一度フォーカスを失う (他ページの裏に回る/アプリがバックグラウンドになる) と
  // 正しく再開しないことがある。frameloop="never" にして setInterval から advance() で手動駆動する。
  useEffect(() => {
    // frameloop="never" の delta 計算は渡した timestamp をそのまま引き算するので、
    // room.frame() が期待する「秒」単位に合わせる (ミリ秒のままだと毎フレーム 0.1 秒クランプされ早送りになる)。
    const id = setInterval(() => advance(performance.now() / 1000), 16);
    return () => clearInterval(id);
  }, []);

  return (
    <>
      <primitive object={room.camera.three} />
      <hemisphereLight args={[0xffffff, 0x444444, 0.6]} />
      <primitive object={room.root} />
    </>
  );
}

/**
 * 3D ビュー (ホーム / AR 画面上プレビュー 共通)。View3D.kt のポート。
 * タップ (なでなで/面選択)・長押し (うっとり)・ドラッグ (回転、または面選択中にモジュールをつかんで移動)・ピンチ (ズーム)。
 */
export function View3D({
  room,
  active,
  statusText,
  hint,
  mascot,
  style,
  topInset = 0,
  bottomInset = 0,
  battery = false,
  tray,
  toast,
  toastTop = 52,
  onViewOrigin,
}: {
  room: HomeRoom;
  active: boolean;
  statusText: string;
  hint: string;
  mascot?: MascotState;
  style?: ViewStyle;
  topInset?: number;
  bottomInset?: number;
  battery?: boolean;
  tray?: ModuleDragTarget;
  toast?: ToastState;
  toastTop?: number;
  /** この View3D の window 座標上の原点 (x, y) をレイアウト時に通知する。tray がウィンドウ座標でドラッグ位置を扱うための基準。 */
  onViewOrigin?: (x: number, y: number) => void;
}) {
  const pinchStartZoom = useRef(1);
  const lastTranslation = useRef({ x: 0, y: 0 });
  const dragMode = useRef<"none" | "rotate" | "module">("none");
  const moduleDragSlot = useRef<string | null>(null);

  const tap = useMemo(
    () =>
      Gesture.Tap()
        .runOnJS(true)
        .maxDistance(TapSlopDp)
        .onEnd((e) => {
          room.tapAt(e.x, e.y);
        }),
    [room],
  );

  const longPress = useMemo(
    () =>
      Gesture.LongPress()
        .runOnJS(true)
        .minDuration(LongPressMs)
        .onStart(() => room.holdStart())
        .onEnd(() => room.holdEnd())
        .onFinalize(() => room.holdEnd()),
    [room],
  );

  const pan = useMemo(
    () =>
      Gesture.Pan()
        .runOnJS(true)
        .onStart((e) => {
          lastTranslation.current = { x: 0, y: 0 };
          room.touched();
          room.draggingModel = true;
          dragMode.current = "rotate";
          // 面を選んでいるとき、取り付け済みモジュールをつかんだらモジュールのドラッグ (移動/取り外し) にする
          if (tray && room.editable && room.selectedFace != null) {
            const slot = room.slotAtScreen(e.x, e.y, SlotPickDp, (s) => room.moduleAt(s.id) != null);
            if (slot != null) {
              const kind = room.moduleAt(slot);
              if (kind != null) {
                dragMode.current = "module";
                moduleDragSlot.current = slot;
                room.draggingModel = false;
                tray.beginDrag(kind, slot, { x: e.absoluteX, y: e.absoluteY });
              }
            }
          }
        })
        .onUpdate((e) => {
          if (dragMode.current === "module") {
            tray?.drag({ x: e.absoluteX, y: e.absoluteY });
            return;
          }
          const dx = e.translationX - lastTranslation.current.x;
          const dy = e.translationY - lastTranslation.current.y;
          lastTranslation.current = { x: e.translationX, y: e.translationY };
          room.rotateBy(dx, dy);
        })
        .onEnd((e) => {
          if (dragMode.current === "module") tray?.endDrag({ x: e.absoluteX, y: e.absoluteY });
        })
        .onFinalize(() => {
          room.draggingModel = false;
          dragMode.current = "none";
        }),
    [room, tray],
  );

  const pinch = useMemo(
    () =>
      Gesture.Pinch()
        .runOnJS(true)
        .onStart(() => {
          pinchStartZoom.current = 1;
          room.touched();
        })
        .onUpdate((e) => {
          const factor = e.scale / pinchStartZoom.current;
          pinchStartZoom.current = e.scale;
          room.zoomBy(factor);
        }),
    [room],
  );

  const composed = Gesture.Simultaneous(Gesture.Race(tap, longPress), pan, pinch);
  const rootRef = useRef<React.ComponentRef<typeof View>>(null);

  return (
    <View
      ref={rootRef}
      style={[styles.root, style]}
      onLayout={(e) => {
        const { width, height } = e.nativeEvent.layout;
        room.setViewSize(width, height);
        room.setViewInsets(topInset, bottomInset, height);
        rootRef.current?.measureInWindow((x, y) => onViewOrigin?.(x, y));
      }}
    >
      <GestureDetector gesture={composed}>
        <View style={StyleSheet.absoluteFill}>
          <Canvas style={StyleSheet.absoluteFill} gl={{ preserveDrawingBuffer: false }} frameloop="never">
            <RoomScene room={room} active={active} />
          </Canvas>
        </View>
      </GestureDetector>

      <View pointerEvents="none" style={[styles.statusBar, { top: topInset + 10 }]}>
        <View style={styles.pill}>
          {mascot && <AidyMascot size={16} face="Normal" state={mascot} />}
          <Label text={statusText} size={11} color={Theme.Primary} bold />
        </View>
        {battery && (
          <View style={[styles.pill, { height: 30 }]}>
            <Label text={`${Math.round(BatteryLevel * 100)}%`} size={12} color={Theme.Slate600} bold />
            <Icon icon="Battery" size={22} color={BatteryLevel > 0.2 ? Theme.Green : Theme.hex(0xef4444)} />
          </View>
        )}
      </View>

      <View pointerEvents="none" style={[styles.hint, { bottom: bottomInset + 10 }]}>
        <Icon icon="HandPointer" size={11} color={Theme.TextSub} />
        <Label text={hint} size={11} color={Theme.TextSub} bold />
      </View>

      <View style={[styles.zoomButtons, { bottom: bottomInset + 44 }]}>
        <Pressable style={styles.zoomButton} onPress={() => room.zoomBy(1.25)}>
          <Icon icon="Plus" size={13} color={Theme.Slate600} />
        </Pressable>
        <Pressable style={styles.zoomButton} onPress={() => room.zoomBy(0.8)}>
          <View style={{ width: 12, height: 3, borderRadius: 1.5, backgroundColor: Theme.Slate600 }} />
        </Pressable>
      </View>

      {toast && <AidyToast state={toast} style={[styles.toast, { top: toastTop }]} />}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, overflow: "hidden", backgroundColor: Theme.hex(0xf1f5f9) },
  statusBar: { position: "absolute", left: 12, flexDirection: "row", gap: 8 },
  pill: {
    ...boxStyle("rgba(255,255,255,0.88)", 16, 3),
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  hint: {
    position: "absolute",
    alignSelf: "center",
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    backgroundColor: "rgba(255,255,255,0.8)",
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 4,
  },
  zoomButtons: { position: "absolute", right: 10, gap: 6 },
  zoomButton: {
    width: 34,
    height: 34,
    alignItems: "center",
    justifyContent: "center",
    ...boxStyle("rgba(255,255,255,0.9)", 17, 3),
  },
  toast: { position: "absolute", alignSelf: "center" },
});
