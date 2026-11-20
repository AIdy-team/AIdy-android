import React, { useEffect, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Label, AidySwitch } from "../components/widgets";
import { View3D } from "../components/View3D";
import { HomeUi } from "../app/appState";
import { ModuleConfig } from "../data/modules";
import { BodyColor } from "../data/bodyColor";
import { ColorPatternDialog, PatternDot } from "../components/colorPicker";
import { Pressed, boxStyle } from "../components/widgets";

const SummaryHeight = 58;
const SummaryMargin = 12;

/** ホーム画面。1画面で完結: 3D がページいっぱい、構成のまとめはその上に浮かべる。Pages.kt HomePage のポート。 */
export function HomePage({ ui, active, onEdit }: { ui: HomeUi; active: boolean; onEdit: () => void }) {
  const [, setTick] = useState(0);
  useEffect(() => ui.subscribe(() => setTick((t) => t + 1)), [ui]);
  const [moduleVersion, setModuleVersion] = useState(ModuleConfig.version);
  useEffect(() => {
    const l = () => setModuleVersion(ModuleConfig.version);
    ModuleConfig.changed.push(l);
    return () => {
      const i = ModuleConfig.changed.indexOf(l);
      if (i >= 0) ModuleConfig.changed.splice(i, 1);
    };
  }, []);
  void moduleVersion;
  const summary = ModuleConfig.summary(ModuleConfig.current);
  const [showColor, setShowColor] = useState(false);
  const [, setColorVersion] = useState(0);

  return (
    <View style={styles.root}>
      <View3D
        room={ui.room}
        active={active}
        statusText={ui.stateText}
        hint="タップでなでなで ・ ドラッグで回転 ・ ピンチでズーム"
        mascot={ui.mascot}
        toast={ui.toast}
        toastTop={52}
        bottomInset={SummaryHeight + SummaryMargin * 2}
      />

      {/* 右上: 本体の色を変える */}
      <View style={{ position: "absolute", top: 12, right: 12 }}>
        <Pressed
          onPress={() => setShowColor(true)}
          pressScale={0.95}
          style={[boxStyle("rgba(255,255,255,0.94)", 17, 4, Theme.Border), { height: 34, flexDirection: "row", alignItems: "center", gap: 6, paddingLeft: 6, paddingRight: 12 }]}
        >
          <PatternDot pattern={BodyColor.pattern} size={24} />
          <Label text="色" size={12} color={Theme.TextMain} bold />
        </Pressed>
      </View>

      <ColorPatternDialog
        visible={showColor}
        title="Aidyの色"
        initial={BodyColor.pattern}
        attached={ui.room.rig.attached}
        onPreview={(p) => ui.room.rig.applyPattern(p)}
        onConfirm={(p) => {
          BodyColor.pattern = p;
          ui.room.rig.applyPattern(p);
          setShowColor(false);
          setColorVersion((v) => v + 1);
          ui.toast.show("Happy", "新しい色になったよ！", 2.2);
        }}
        onDismiss={() => {
          ui.room.rig.applyPattern(BodyColor.pattern); // 選んでいた色を戻す
          setShowColor(false);
        }}
      />

      <View style={styles.summaryBar}>
        <Icon icon="Cubes" size={14} color={Theme.Primary} />
        <View style={{ flex: 1, gap: 1 }}>
          <Label text="いまのモジュール" size={10} color={Theme.TextSub} bold />
          <Label text={summary} size={12} color={Theme.TextMain} bold maxLines={1} />
        </View>
        <Label text="お散歩" size={10} color={Theme.Slate400} bold />
        <AidySwitch on={ui.aiEnabled} onChange={(v) => ui.toggleAIWalk(v)} scale={0.8} />
        <Pressable onPress={onEdit} style={styles.editButton}>
          <Text style={{ color: "#fff", fontWeight: "700", fontSize: 12 }}>付け替え</Text>
          <Icon icon="ChevronRight" size={10} color="#fff" />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: Theme.Device },
  summaryBar: {
    position: "absolute",
    left: 16,
    right: 16,
    bottom: SummaryMargin,
    height: SummaryHeight,
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    backgroundColor: "rgba(255,255,255,0.94)",
    borderRadius: 18,
    borderWidth: 1,
    borderColor: Theme.Border,
    paddingLeft: 14,
    paddingRight: 10,
  },
  editButton: {
    flexDirection: "row",
    alignItems: "center",
    gap: 4,
    backgroundColor: Theme.Primary,
    borderRadius: 15,
    paddingLeft: 12,
    paddingRight: 10,
    paddingVertical: 7,
  },
});
