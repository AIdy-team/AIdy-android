import React, { useState } from "react";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Card, Label, ScrollPage, SectionTitle, AidySwitch } from "../components/widgets";
import { AidyDeviceLink, CalendarSettings } from "../data/diary";

/** 設定画面。Pages.kt SettingsPage のポート。 */
export function SettingsPage({ onOpenDiary }: { onOpenDiary: () => void }) {
  const [weekView, setWeekView] = useState(CalendarSettings.weekView);

  return (
    <ScrollPage style={{ backgroundColor: Theme.Device }}>
      <SectionTitle text="ロボット設定" first />
      <Card title="アセンブリ構造" sub="本体v2_モデル.obj (透明化防止完全対応)" trailing={<Icon icon="Check" size={14} color={Theme.Green} />} />
      <Card title="スピーカー音量" sub="効果音 & 音声応答" trailing={<Label text="60%" size={13} color={Theme.Primary} bold />} />

      <SectionTitle text="日記" first={false} />
      <Card
        title="カレンダー表示"
        sub="週表示に切り替える"
        trailing={
          <AidySwitch
            on={weekView}
            onChange={(on) => {
              setWeekView(on);
              CalendarSettings.weekView = on;
            }}
          />
        }
      />

      <SectionTitle text="操作 & ネットワーク" first={false} />
      <Card title="モジュール自動合体" sub="パーツSocketアタッチ機能 (ON)" trailing={<AidySwitch on={true} onChange={() => {}} />} />
      <Card title="Wi-Fi 接続" sub="AIDY-5G-2.4G (接続済み)" trailing={<Icon icon="Wifi" size={16} color={Theme.Green} />} />
      <Card
        icon="Aidy"
        iconColor={Theme.Primary}
        iconBg={Theme.PrimaryLight}
        title="Aidy本体の会話ログ"
        sub={`${AidyDeviceLink.host} から Wi-Fi で日記を同期`}
        onPress={onOpenDiary}
        trailing={<Icon icon="ChevronRight" size={12} color={Theme.Slate400} />}
      />
    </ScrollPage>
  );
}
