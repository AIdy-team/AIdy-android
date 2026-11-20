import React, { useState } from "react";
import { View } from "react-native";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Card, ScrollPage, SectionTitle } from "../components/widgets";
import { VoiceSettingsCard } from "../components/remoteVoice";
import { DictManagerModal } from "../components/dictManager";

/** リモコン: 本体の声 (音量・話す速さ)・辞書 + 拡張モジュールのカード (Pages.kt RemotePage のポート)。 */
export function RemotePage({ visible }: { visible: boolean }) {
  const [showDict, setShowDict] = useState(false);
  return (
    <ScrollPage style={{ backgroundColor: Theme.Device }}>
      <SectionTitle text="Aidy本体" first />
      <VoiceSettingsCard visible={visible} />
      <Card
        icon="Book"
        iconColor={Theme.hex(0x8b5cf6)}
        iconBg={Theme.hex(0xf3e8ff)}
        title="辞書 (よくある質問・聞き間違い・読み)"
        sub="本体の返事をカスタマイズする"
        onPress={() => setShowDict(true)}
        trailing={<Icon icon="ChevronRight" size={12} color={Theme.Slate400} />}
      />

      <SectionTitle text="拡張モジュール (追加3Dモデル連携)" first={false} />
      <Card icon="Video" iconColor={Theme.hex(0x8b5cf6)} iconBg={Theme.hex(0xf3e8ff)} title="カメラフィードモジュール" sub="拡張OBJモデル接続可能" trailing={<Icon icon="Plus" size={16} color={Theme.Primary} />} />
      <Card icon="Microchip" iconColor={Theme.hex(0xec4899)} iconBg={Theme.hex(0xfce7f3)} title="センサーモジュール" sub="温度/湿度 拡張モジュール" trailing={<Icon icon="Plus" size={16} color={Theme.Primary} />} />
      <View style={{ height: 1 }} />

      <DictManagerModal visible={showDict} onDismiss={() => setShowDict(false)} />
    </ScrollPage>
  );
}
