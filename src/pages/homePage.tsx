import React, { useEffect, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Label, AidySwitch } from "../components/widgets";
import { View3D } from "../components/View3D";
import { HomeUi } from "../app/appState";
import { ModuleConfig, ModuleKind } from "../data/modules";

function moduleSummaryText(slots: Map<string, ModuleKind>): string {
  const counts = new Map<ModuleKind, number>();
  for (const k of slots.values()) counts.set(k, (counts.get(k) ?? 0) + 1);
  const parts: string[] = [];
  for (const k of ["Ear", "Tail", "Tail2", "Legs", "Face", "Hand", "HandLight"] as ModuleKind[]) {
    const n = counts.get(k);
    if (n == null) continue;
    const name = { Ear: "耳", Tail: "尻尾", Tail2: "尻尾2", Legs: "足", Face: "顔", Hand: "手", HandLight: "ライト手" }[k];
    parts.push(n > 1 ? `${name}×${n}` : name);
  }
  return parts.length > 0 ? parts.join("・") : "なし (本体だけ)";
}

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
  const summary = moduleSummaryText(ModuleConfig.current);

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
