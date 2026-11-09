import React from "react";
import { View } from "react-native";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Card, ScrollPage, SectionTitle, AidySwitchUncontrolled } from "../components/widgets";

/** リモコン・モジュール画面。静的なデモカード (Pages.kt RemotePage のポート)。 */
export function RemotePage() {
  return (
    <ScrollPage style={{ backgroundColor: Theme.Device }}>
      <SectionTitle text="標準モジュール (モデル合体済み)" first />
      <Card icon="Snowflake" iconColor={Theme.Primary} iconBg={Theme.Slate100} title="リビングエアコン" sub="温度: 24°C / 自動" trailing={<AidySwitchUncontrolled initial />} />
      <Card icon="Lightbulb" iconColor={Theme.Primary} iconBg={Theme.Slate100} title="メイン照明" sub="オン / 100% 輝度" trailing={<AidySwitchUncontrolled initial />} />
      <Card icon="Plug" iconColor={Theme.Primary} iconBg={Theme.Slate100} title="スマートプラグ" sub="オフ / タイマー設定" trailing={<AidySwitchUncontrolled initial={false} />} />

      <SectionTitle text="拡張モジュール (追加3Dモデル連携)" first={false} />
      <Card icon="Video" iconColor={Theme.hex(0x8b5cf6)} iconBg={Theme.hex(0xf3e8ff)} title="カメラフィードモジュール" sub="拡張OBJモデル接続可能" trailing={<Icon icon="Plus" size={16} color={Theme.Primary} />} />
      <Card icon="Microchip" iconColor={Theme.hex(0xec4899)} iconBg={Theme.hex(0xfce7f3)} title="センサーモジュール" sub="温度/湿度 拡張モジュール" trailing={<Icon icon="Plus" size={16} color={Theme.Primary} />} />
      <View style={{ height: 1 }} />
    </ScrollPage>
  );
}
