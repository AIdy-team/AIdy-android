// 仮実装: Kotlin版 Icons.kt は24x24グリッドのSDFで本格的なアイコンを描くが、
// ここではPhase 1向けの簡易版 (文字1〜2文字のグリフ) を用意する。Phase 4でSkiaによるSDF描画に置き換える。
import React from "react";
import { Text, View } from "react-native";
import { IconId } from "./iconIds";

const Glyph: Record<IconId, string> = {
  Gear: "⚙", ChevronLeft: "‹", ChevronRight: "›", House: "⌂", Gamepad: "🎮", Camera: "📷",
  Sliders: "☰", Brain: "🧠", Check: "✓", XMark: "✕", Paw: "🐾", Cubes: "▦", Heart: "♥",
  Snowflake: "❄", Lightbulb: "💡", Plug: "🔌", Video: "🎥", Microchip: "🔧", Plus: "+",
  Crosshairs: "✛", HandPointer: "☝", Circle: "●", Wifi: "📶", MapPin: "📍", Aidy: "A",
  Book: "📖", Ear: "👂", Tail: "〜", Leg: "🦵", Hand: "✋", Flashlight: "🔦", Battery: "🔋",
  Trash: "🗑",
};

export function Icon({ icon, size, color }: { icon: IconId; size: number; color: string }) {
  return (
    <View style={{ width: size, height: size, alignItems: "center", justifyContent: "center" }}>
      <Text style={{ fontSize: size * 0.85, color, lineHeight: size }}>{Glyph[icon]}</Text>
    </View>
  );
}
