import React, { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { StyleSheet, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Label, SmallButton, pillStyle } from "./widgets";
import { Theme } from "./theme";
import { AidyMarkers } from "../data/markers";
import { aidyModelUri, markerModelUri, markerTargetName, registerMarkerTargets } from "../ar/arAssets";

/**
 * カメラ AR (ViroReact)。2 つの出し方を同時に使える:
 *  1. 面に置く: ARKit / ARCore が見つけた床・机の面をタップすると、いまの着せ替えの Aidy を置く。
 *     置いた後はドラッグで移動・ピンチで大きさ・2 本指で回転。「置き直す」で選び直し。
 *  2. マーカー: 印刷した ArUco マーカー (ID0 = いまの着せ替え、ID1 = 名刺の標準 Aidy、ID2〜 = 作った着せ替え) を写すと、その上に出す。
 * Viro はネイティブのモジュールなので Expo Go では動かない (開発ビルドが必要)。読み込めないときは案内だけ出す。
 */

type ViroModule = any;

let viroCache: ViroModule | null | undefined;
function viro(): ViroModule | null {
  if (viroCache !== undefined) return viroCache;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    viroCache = require("@reactvision/react-viro");
  } catch (e) {
    console.warn("[AIDY] ViroReact が使えません:", e);
    viroCache = null;
  }
  return viroCache;
}

// ------------------------------------------------------------------ シーンと画面の上の UI で分け合う状態

type Tracking = "init" | "normal" | "limited" | "unavailable";
type LimitReason = "none" | "motion" | "features" | "light";

interface ArState {
  tracking: Tracking;
  reason: LimitReason;
  placed: boolean;
  /** いま見えているマーカーの名前 (無ければ空)。 */
  markerName: string;
  error: string;
  loading: boolean;
  /** 増えたら置き直す。 */
  resetCount: number;
}

const initialState: ArState = { tracking: "init", reason: "none", placed: false, markerName: "", error: "", loading: true, resetCount: 0 };
let state: ArState = initialState;
const listeners = new Set<() => void>();
function setArState(patch: Partial<ArState>): void {
  state = { ...state, ...patch };
  for (const l of listeners) l();
}
function useArState(): ArState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

// ------------------------------------------------------------------ 画面

export function ArCameraView({ onClose }: { onClose: () => void }) {
  const v = viro();
  const insets = useSafeAreaInsets();
  const s = useArState();

  useEffect(() => {
    setArState({ ...initialState });
    return () => setArState({ ...initialState });
  }, []);

  if (!v) {
    return (
      <View style={styles.stub}>
        <Label text="このアプリにはカメラ AR の部品が入っていません" size={14} color={Theme.Slate600} bold align="center" />
        <Label text="Expo Go では使えません。開発ビルドで試してね" size={12} color={Theme.TextSub} align="center" />
        <View style={{ height: 12 }} />
        <SmallButton text="画面上の付け替えに戻る" bg={Theme.Slate100} fg={Theme.Slate600} onPress={onClose} />
      </View>
    );
  }
  const { ViroARSceneNavigator } = v;

  return (
    <View style={styles.root}>
      <ViroARSceneNavigator style={StyleSheet.absoluteFill} initialScene={{ scene: AidyArScene }} autofocus numberOfTrackedImages={4} />

      <View pointerEvents="box-none" style={[styles.top, { paddingTop: insets.top + 8 }]}>
        <View style={[pillStyle("rgba(15,23,42,0.6)", 14, 0), styles.status]}>
          <Label text={statusText(s)} size={12} color={s.error ? "#fecaca" : "#fff"} bold align="center" />
        </View>
      </View>

      <View pointerEvents="box-none" style={[styles.bottom, { paddingBottom: 16 }]}>
        {s.placed && <SmallButton text="置き直す" bg="rgba(255,255,255,0.9)" fg={Theme.Slate600} onPress={() => setArState({ placed: false, resetCount: state.resetCount + 1 })} />}
        <View style={{ flex: 1 }} />
        <SmallButton text="付け替えに戻る" bg={Theme.Primary} fg="#fff" onPress={onClose} />
      </View>
    </View>
  );
}

function statusText(s: ArState): string {
  if (s.error) return s.error;
  if (s.loading) return "Aidy を準備中…";
  if (s.markerName) return `マーカーを見つけました: ${s.markerName}`;
  if (s.tracking === "init") return "カメラを準備中…";
  if (s.tracking === "unavailable") return "まわりを読み取れません。ゆっくり動かしてね";
  if (s.tracking === "limited") {
    if (s.reason === "motion") return "動かすのが速すぎるみたい。ゆっくり動かしてね";
    if (s.reason === "light") return "暗くて読み取れません。明るい所で使ってね";
    if (s.reason === "features") return "模様の少ない面です。物のある机や床を写してね";
    return "まわりを読み取っています…";
  }
  if (!s.placed) return "床や机が光ったらタップして Aidy を置こう (マーカーを写しても出るよ)";
  return "ドラッグで移動 ・ ピンチで大きさ ・ 2本指で回転";
}

// ------------------------------------------------------------------ AR シーン (ViroARSceneNavigator の中で描かれる)

const MinScale = 0.3;
const MaxScale = 6;

function AidyArScene() {
  const v = viro()!;
  const { ViroARScene, ViroARPlaneSelector, ViroARImageMarker, ViroARTrackingTargets, Viro3DObject, ViroNode, ViroAmbientLight, ViroDirectionalLight } = v;
  const s = useArState();
  const selector = useRef<{ reset: () => void } | null>(null);
  const [modelUri, setModelUri] = useState<string | null>(null);
  const [markers, setMarkers] = useState<{ id: number; uri: string }[]>([]);
  const [scale, setScale] = useState(1);
  const [rotY, setRotY] = useState(0);
  const pinchBase = useRef(1);
  const rotateBase = useRef(0);

  // Aidy の GLB とマーカーを用意する
  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const uri = await aidyModelUri(null, null);
        if (!alive) return;
        setModelUri(uri);
        setArState({ loading: false });
      } catch (e) {
        console.warn("[AIDY] AR の Aidy を作れません:", e);
        if (alive) setArState({ loading: false, error: "Aidy の 3D を作れませんでした" });
      }
      try {
        const ids = registerMarkerTargets((t) => ViroARTrackingTargets.createTargets(t));
        const list: { id: number; uri: string }[] = [];
        for (const id of ids) {
          try {
            list.push({ id, uri: await markerModelUri(id) });
          } catch (e) {
            console.warn(`[AIDY] マーカー ${id} の Aidy を作れません:`, e);
          }
        }
        if (alive) setMarkers(list);
      } catch (e) {
        console.warn("[AIDY] マーカーを登録できません:", e);
      }
    })();
    return () => {
      alive = false;
    };
  }, [ViroARTrackingTargets]);

  // 「置き直す」
  const lastReset = useRef(s.resetCount);
  useEffect(() => {
    if (s.resetCount === lastReset.current) return;
    lastReset.current = s.resetCount;
    selector.current?.reset();
    setScale(1);
    setRotY(0);
  }, [s.resetCount]);

  const onTracking = (st: number, reason: number) => {
    // ViroTrackingStateConstants: 1 = UNAVAILABLE, 2 = LIMITED, 3 = NORMAL
    // ViroARTrackingReasonConstants: 1 = NONE, 2 = EXCESSIVE_MOTION, 3 = INSUFFICIENT_FEATURES, 4 = INSUFFICIENT_LIGHT
    const tracking: Tracking = st === 3 ? "normal" : st === 2 ? "limited" : "unavailable";
    const r: LimitReason = reason === 2 ? "motion" : reason === 3 ? "features" : reason === 4 ? "light" : "none";
    if (tracking !== state.tracking || r !== state.reason) setArState({ tracking, reason: r });
  };

  const model = (uri: string, key: string) => (
    <Viro3DObject
      key={key}
      source={{ uri }}
      type="GLB"
      onError={(e: { nativeEvent?: { error?: string } }) => {
        console.warn("[AIDY] Viro3DObject:", e?.nativeEvent?.error);
        setArState({ error: "Aidy の 3D を表示できませんでした" });
      }}
    />
  );

  return (
    <ViroARScene onTrackingUpdated={onTracking} anchorDetectionTypes={["PlanesHorizontal"]}>
      <ViroAmbientLight color="#ffffff" intensity={350} />
      <ViroDirectionalLight color="#ffffff" direction={[0.3, -1, -0.4]} intensity={700} />

      {modelUri && (
        <ViroARPlaneSelector
          ref={selector}
          alignment="Horizontal"
          minWidth={0.1}
          minHeight={0.1}
          onPlaneSelected={() => setArState({ placed: true })}
        >
          <ViroNode
            scale={[scale, scale, scale]}
            rotation={[0, rotY, 0]}
            dragType="FixedToWorld"
            onDrag={() => {}}
            onPinch={(pinchState: number, factor: number) => {
              // 1 = 始まり、2 = 途中、3 = 終わり。factor は始まりからの倍率
              if (pinchState === 1) pinchBase.current = scale;
              else setScale(Math.min(MaxScale, Math.max(MinScale, pinchBase.current * factor)));
            }}
            onRotate={(rotateState: number, factor: number) => {
              if (rotateState === 1) rotateBase.current = rotY;
              else setRotY(rotateBase.current - factor);
            }}
          >
            {model(modelUri, "plane")}
          </ViroNode>
        </ViroARPlaneSelector>
      )}

      {markers.map(({ id, uri }) => (
        <ViroARImageMarker
          key={`m${id}`}
          target={markerTargetName(id)}
          onAnchorFound={() => setArState({ markerName: AidyMarkers.name(id) })}
          onAnchorRemoved={() => setArState({ markerName: "" })}
        >
          {model(uri, `m${id}`)}
        </ViroARImageMarker>
      ))}
    </ViroARScene>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: "#000" },
  stub: { flex: 1, backgroundColor: Theme.Device, alignItems: "center", justifyContent: "center", gap: 6, paddingHorizontal: 24 },
  top: { position: "absolute", left: 0, right: 0, top: 0, alignItems: "center", paddingHorizontal: 16 },
  status: { paddingHorizontal: 14, paddingVertical: 8, maxWidth: 360 },
  bottom: { position: "absolute", left: 16, right: 16, bottom: 0, flexDirection: "row", alignItems: "center" },
});
