import React, { useEffect, useRef, useState } from "react";
import { StyleSheet, View } from "react-native";
import { Theme } from "../components/theme";
import { SmallButton } from "../components/widgets";
import { ArCameraView } from "../components/arCamera";
import { View3D, ModuleDragTarget } from "../components/View3D";
import { ModuleDragGhost, ModuleTray } from "../components/moduleTray";
import { ARPageUi } from "../app/appState";
import { BodyLayout, ModuleCatalog, ModuleConfig, ModuleKind } from "../data/modules";

const SnapDp = 60; // ドロップ先スロットの吸着距離
const TrayHeight = 164;
const TrayMargin = 12;

interface Drag {
  kind: ModuleKind;
  /** 3D モデル上のモジュールをつかんだときのスロット (トレイからなら null)。 */
  from: string | null;
  hover: string | null;
}

/**
 * AR 画面。
 *  「画面上」: 3D プレビューで面をタップ → 下のトレイからモジュールをドラッグして付け替え。
 *  「カメラAR」: ViroReact で、床・机に置く / 印刷したマーカーの上に出す (arCamera.tsx)。
 */
export function ARPage({
  ui,
  active,
  onMarkerButton,
  onPagerLock,
}: {
  ui: ARPageUi;
  active: boolean;
  onMarkerButton: () => void;
  onPagerLock?: (locked: boolean) => void;
}) {
  void onMarkerButton;
  const [, setTick] = useState(0);
  useEffect(() => ui.subscribe(() => setTick((t) => t + 1)), [ui]);

  const room = ui.room;
  const viewOrigin = useRef({ x: 0, y: 0 });
  const trayRect = useRef({ x: 0, y: Number.MAX_VALUE, width: 0, height: 0 });
  const dragRef = useRef<Drag | null>(null);
  const [drag, setDrag] = useState<{ kind: ModuleKind; from: string | null } | null>(null);
  const [ghost, setGhost] = useState<{ x: number; y: number } | null>(null);
  const [hint, setHintText] = useState("");
  const [hintError, setHintError] = useState(false);

  const setHint = (text: string, error = false) => {
    setHintText(text);
    setHintError(error);
  };

  const toLocal = (x: number, y: number) => ({ x: x - viewOrigin.current.x, y: y - viewOrigin.current.y });

  const targetSlot = (kind: ModuleKind, x: number, y: number): string | null => {
    const l = toLocal(x, y);
    return room.slotAtScreen(l.x, l.y, SnapDp, (s) => ModuleCatalog.fits(kind, s));
  };

  const overTray = (x: number, y: number) => {
    const r = trayRect.current;
    return x >= r.x && x <= r.x + r.width && y >= r.y && y <= r.y + r.height;
  };

  const beginDrag = (kind: ModuleKind, fromSlot: string | null, x: number, y: number) => {
    if (fromSlot == null && room.selectedFace == null) {
      setHint(`先に3Dモデルの面をタップしてね (${ModuleCatalog.name(kind)}を付ける場所)`, true);
      return;
    }
    dragRef.current = { kind, from: fromSlot, hover: null };
    setDrag({ kind, from: fromSlot });
    room.setDropState(kind, null);
    setHint(fromSlot != null ? "別のスロットへ移動 / Aidyの外かトレイで離すと取り外し" : `${ModuleCatalog.name(kind)}を光っている丸へ`);
    moveDrag(x, y);
  };

  const moveDrag = (x: number, y: number) => {
    const d = dragRef.current;
    if (!d) return;
    setGhost(toLocal(x, y));
    const over = targetSlot(d.kind, x, y);
    if (over !== d.hover) {
      d.hover = over;
      room.setDropState(d.kind, over);
    }
  };

  const endDrag = (x: number, y: number) => {
    const d = dragRef.current;
    if (!d) return;
    dragRef.current = null;
    setDrag(null);
    setGhost(null);
    room.setDropState(null, null);
    const { kind, from } = d;
    const target = targetSlot(kind, x, y);
    const l = toLocal(x, y);
    if (target != null && target !== from) {
      const s = BodyLayout.get(target)!;
      const old = room.moduleAt(target);
      if (from != null) {
        ModuleConfig.detach(from);
        // 移動で別のモジュールに重ねたときは入れ替える
        if (old != null) ModuleConfig.attach(old, from);
      }
      ModuleConfig.replace(kind, target);
      setHint(
        old != null
          ? `${BodyLayout.faceLabel(s.face)}の${s.label}を ${ModuleCatalog.name(old)} → ${ModuleCatalog.name(kind)} に切り替えました`
          : `${BodyLayout.faceLabel(s.face)}の${s.label}に${ModuleCatalog.name(kind)}を取り付けました`,
      );
    } else if (from != null && (overTray(x, y) || (target == null && !room.isOverRobot(l.x, l.y)))) {
      // トレイの上か、Aidy から離れた所で指を離したら取り外し
      ModuleConfig.detach(from);
      setHint(`${ModuleCatalog.name(kind)}を取り外しました`);
    } else if (target == null && room.slotAtScreen(l.x, l.y, SnapDp, null) != null) {
      setHint(`${ModuleCatalog.name(kind)}はそこには付けられません`, true);
    } else setHint("");
  };

  // 3D モデル上のモジュールをつかんだとき (View3D から)
  const fromModel: ModuleDragTarget = {
    beginDrag: (kind, fromSlot, w) => beginDrag(kind, fromSlot, w.x, w.y),
    drag: (w) => moveDrag(w.x, w.y),
    endDrag: (w) => endDrag(w.x, w.y),
  };

  // カメラ AR 中は、ページの横スワイプと AR の指の操作がぶつからないようにページ送りを止める
  const arOn = ui.arMode && active;
  useEffect(() => {
    if (!arOn) return;
    onPagerLock?.(true);
    return () => onPagerLock?.(false);
  }, [arOn, onPagerLock]);

  if (ui.arMode) {
    // ほかのページにいる間はカメラを止める
    if (!active) return <View style={styles.stub} />;
    return <ArCameraView onClose={() => ui.setArMode(false)} />;
  }

  return (
    <View style={styles.root}>
      <View3D
        room={room}
        active={active}
        statusText={ui.editStateText}
        hint="面をタップで選択 ・ ドラッグで回転 ・ ピンチでズーム"
        mascot={ui.editMascot}
        toast={ui.arToast}
        tray={fromModel}
        onViewOrigin={(x, y) => {
          viewOrigin.current = { x, y };
        }}
        bottomInset={TrayHeight + TrayMargin * 2}
      />

      <View style={styles.trayWrap}>
        <ModuleTray
          room={room}
          active={active}
          hint={hint}
          hintError={hintError}
          removing={drag != null && drag.from != null}
          handlers={{
            beginDrag: (kind, x, y) => beginDrag(kind, null, x, y),
            drag: moveDrag,
            endDrag,
          }}
          onPagerLock={onPagerLock}
          onTrayLayout={(r) => (trayRect.current = r)}
        />
      </View>

      <View style={styles.cameraButton}>
        <SmallButton text="カメラARで見る" bg={Theme.Primary} fg="#fff" onPress={() => ui.setArMode(true)} />
      </View>

      {drag && ghost && <ModuleDragGhost kind={drag.kind} x={ghost.x} y={ghost.y} />}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: Theme.Device },
  stub: { flex: 1, backgroundColor: "#000" },
  cameraButton: { position: "absolute", right: 12, top: 10 },
  trayWrap: { position: "absolute", left: 16, right: 16, bottom: TrayMargin, height: TrayHeight },
});
