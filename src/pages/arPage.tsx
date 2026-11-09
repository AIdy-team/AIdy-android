import React, { useCallback, useEffect, useRef, useState } from "react";
import { Animated, StyleSheet, View } from "react-native";
import { Gesture, GestureDetector } from "react-native-gesture-handler";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Label, boxStyle } from "../components/widgets";
import { View3D, ModuleDragTarget } from "../components/View3D";
import { ARPageUi } from "../app/appState";
import { BodyLayout, ModuleCatalog, ModuleConfig, ModuleKind } from "../data/modules";

const SlotPickDp = 45;
const TrayHeight = 92;
const TrayMargin = 12;
const GhostSize = 48;

/** AR 画面。画面上プレビューでのモジュール付け替え (Phase 3) を実装。カメラAR (Phase 6/7) はスタブのまま。 */
export function ARPage({ ui, active, onMarkerButton }: { ui: ARPageUi; active: boolean; onMarkerButton: () => void }) {
  void onMarkerButton;
  const [, setTick] = useState(0);
  useEffect(() => ui.subscribe(() => setTick((t) => t + 1)), [ui]);

  const room = ui.room;
  const viewOrigin = useRef({ x: 0, y: 0 });
  const trayRef = useRef<React.ComponentRef<typeof View>>(null);
  const trayTop = useRef(Number.MAX_VALUE);
  const dragging = useRef<{ kind: ModuleKind; fromSlot: string | null } | null>(null);

  const [ghostKind, setGhostKind] = useState<ModuleKind | null>(null);
  const ghostVisible = useRef(new Animated.Value(0)).current;
  const ghostX = useRef(new Animated.Value(0)).current;
  const ghostY = useRef(new Animated.Value(0)).current;

  const moveGhost = useCallback(
    (x: number, y: number) => {
      ghostX.setValue(x - viewOrigin.current.x - GhostSize / 2);
      ghostY.setValue(y - viewOrigin.current.y - GhostSize / 2);
    },
    [ghostX, ghostY],
  );

  const hoverSlotAt = useCallback(
    (kind: ModuleKind, x: number, y: number): string | null => {
      if (y >= trayTop.current) return null;
      const local = { x: x - viewOrigin.current.x, y: y - viewOrigin.current.y };
      return room.slotAtScreen(local.x, local.y, SlotPickDp, (s) => ModuleCatalog.fits(kind, s));
    },
    [room],
  );

  const beginDragging = useCallback(
    (kind: ModuleKind, fromSlot: string | null, x: number, y: number) => {
      dragging.current = { kind, fromSlot };
      setGhostKind(kind);
      moveGhost(x, y);
      Animated.timing(ghostVisible, { toValue: 1, duration: 120, useNativeDriver: true }).start();
      room.setDropState(kind, hoverSlotAt(kind, x, y));
    },
    [room, moveGhost, ghostVisible, hoverSlotAt],
  );

  const updateDragging = useCallback(
    (x: number, y: number) => {
      const d = dragging.current;
      if (!d) return;
      moveGhost(x, y);
      room.setDropState(d.kind, hoverSlotAt(d.kind, x, y));
    },
    [room, moveGhost, hoverSlotAt],
  );

  const endDragging = useCallback(
    (x: number, y: number) => {
      const d = dragging.current;
      dragging.current = null;
      Animated.timing(ghostVisible, { toValue: 0, duration: 120, useNativeDriver: true }).start();
      room.setDropState(null, null);
      if (!d) return;
      const hover = hoverSlotAt(d.kind, x, y);
      if (hover == null) {
        if (d.fromSlot) ModuleConfig.detach(d.fromSlot);
        return;
      }
      if (hover === d.fromSlot) return;
      ModuleConfig.replace(d.kind, hover);
      if (d.fromSlot) ModuleConfig.detach(d.fromSlot);
    },
    [room, ghostVisible, hoverSlotAt],
  );

  const tray: ModuleDragTarget = {
    beginDrag: (kind, fromSlot, w) => beginDragging(kind, fromSlot, w.x, w.y),
    drag: (w) => updateDragging(w.x, w.y),
    endDrag: (w) => endDragging(w.x, w.y),
  };

  const ghostScale = ghostVisible.interpolate({ inputRange: [0, 1], outputRange: [0.85, 1] });
  const ghostStyle = {
    opacity: ghostVisible,
    transform: [{ translateX: ghostX }, { translateY: ghostY }, { scale: ghostScale }],
  };

  if (ui.arMode) {
    return (
      <View style={styles.stub}>
        <Label text="AR カメラ (Phase 6/7 で実装)" size={14} color={Theme.Slate400} />
      </View>
    );
  }

  const fitting = room.selectedFace ? new Set(ModuleCatalog.forFace(room.selectedFace)) : null;

  return (
    <View style={styles.root}>
      <View3D
        room={room}
        active={active}
        statusText={ui.editStateText}
        hint="面をタップで選択 ・ モジュールをドラッグして付け替え"
        mascot={ui.editMascot}
        toast={ui.arToast}
        tray={tray}
        onViewOrigin={(x, y) => {
          viewOrigin.current = { x, y };
        }}
        bottomInset={TrayHeight + TrayMargin * 2}
      />

      <View
        ref={trayRef}
        style={styles.trayBar}
        onLayout={() => trayRef.current?.measureInWindow((_x, y) => (trayTop.current = y))}
      >
        <Label
          text={room.selectedFace ? `${BodyLayout.faceLabel(room.selectedFace)}に付けられるモジュール` : "本体をタップして面を選んでね"}
          size={11}
          color={Theme.TextSub}
          bold
          style={styles.trayHint}
        />
        <View style={styles.trayRow}>
          {ModuleCatalog.All.map((kind) => (
            <TrayChip
              key={kind}
              kind={kind}
              enabled={fitting != null && fitting.has(kind)}
              onBegin={beginDragging}
              onUpdate={updateDragging}
              onEnd={endDragging}
            />
          ))}
        </View>
      </View>

      {ghostKind && (
        <Animated.View pointerEvents="none" style={[styles.ghost, ghostStyle]}>
          <View style={[styles.ghostIcon, { backgroundColor: ModuleCatalog.tint(ghostKind) }]}>
            <Icon icon={ModuleCatalog.icon(ghostKind)} size={20} color="#fff" />
          </View>
        </Animated.View>
      )}
    </View>
  );
}

function TrayChip({
  kind,
  enabled,
  onBegin,
  onUpdate,
  onEnd,
}: {
  kind: ModuleKind;
  enabled: boolean;
  onBegin: (kind: ModuleKind, fromSlot: string | null, x: number, y: number) => void;
  onUpdate: (x: number, y: number) => void;
  onEnd: (x: number, y: number) => void;
}) {
  const pan = Gesture.Pan()
    .runOnJS(true)
    .enabled(enabled)
    .onStart((e) => onBegin(kind, null, e.absoluteX, e.absoluteY))
    .onUpdate((e) => onUpdate(e.absoluteX, e.absoluteY))
    .onEnd((e) => onEnd(e.absoluteX, e.absoluteY));

  return (
    <GestureDetector gesture={pan}>
      <View style={[styles.chip, { opacity: enabled ? 1 : 0.35 }]}>
        <View style={[styles.chipIcon, { backgroundColor: ModuleCatalog.tint(kind) }]}>
          <Icon icon={ModuleCatalog.icon(kind)} size={16} color="#fff" />
        </View>
        <Label text={ModuleCatalog.name(kind)} size={10} color={Theme.TextSub} bold />
      </View>
    </GestureDetector>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: Theme.Device },
  stub: { flex: 1, backgroundColor: Theme.Device, alignItems: "center", justifyContent: "center" },
  trayBar: {
    position: "absolute",
    left: 12,
    right: 12,
    bottom: TrayMargin,
    height: TrayHeight,
    paddingHorizontal: 10,
    paddingTop: 8,
    ...boxStyle("rgba(255,255,255,0.94)", 18, 3, Theme.Border),
  },
  trayHint: { paddingHorizontal: 2, paddingBottom: 4 },
  trayRow: { flex: 1, flexDirection: "row", justifyContent: "space-around", alignItems: "center" },
  chip: { alignItems: "center", gap: 4, width: 48 },
  chipIcon: { width: 34, height: 34, borderRadius: 17, alignItems: "center", justifyContent: "center" },
  ghost: { position: "absolute", left: 0, top: 0, width: GhostSize, height: GhostSize },
  ghostIcon: {
    width: GhostSize,
    height: GhostSize,
    borderRadius: GhostSize / 2,
    alignItems: "center",
    justifyContent: "center",
    shadowColor: "#000",
    shadowOpacity: 0.3,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
    elevation: 6,
  },
});
