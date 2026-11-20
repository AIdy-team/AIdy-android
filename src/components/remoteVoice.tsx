import React, { useCallback, useEffect, useRef, useState } from "react";
import { View } from "react-native";
import { Theme } from "./theme";
import { Icon } from "./icons";
import { AidySlider, Label, SmallButton, boxStyle } from "./widgets";
import { AidyDeviceLink, SettingsResult, VoiceStore } from "../data/diary";

/** 話す速さの言葉 (1.0 = ふつう)。 */
function speedLabel(s: number): string {
  if (s < 0.8) return "ゆっくり";
  if (s < 0.95) return "少しゆっくり";
  if (s <= 1.05) return "ふつう";
  if (s <= 1.3) return "少しはやい";
  return "はやい";
}

/**
 * リモコン: 本体の音量・話す速さ (GET/POST /api/settings)。RemoteVoice.kt のポート。
 * ページが見えたら本体から読み込み、スライダーを離したら本体へ送る。つながっていなくても端末には覚えておく。
 */
export function VoiceSettingsCard({ visible }: { visible: boolean }) {
  const [volume, setVolume] = useState(VoiceStore.volume);
  const [speed, setSpeed] = useState(VoiceStore.speed);
  const [status, setStatus] = useState("");
  const [statusColor, setStatusColor] = useState(Theme.TextSub);
  const busy = useRef(false);

  const show = (r: SettingsResult, okText: string) => {
    if (r.settings) {
      setVolume(r.settings.volume);
      setSpeed(r.settings.speed);
    }
    setStatus(r.ok ? okText : r.error ?? "できませんでした");
    setStatusColor(r.ok ? Theme.Green : r.unsupported || !AidyDeviceLink.isConfigured ? Theme.TextSub : Theme.hex(0xef4444));
  };

  const load = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    setStatus("本体の設定を読み込み中…");
    setStatusColor(Theme.Slate600);
    show(await AidyDeviceLink.fetchSettings(), "本体の設定を読み込みました");
    busy.current = false;
  }, []);

  const send = async (v: { volume: number; speed: number }) => {
    busy.current = true;
    setStatus("本体に送信中…");
    setStatusColor(Theme.Slate600);
    show(await AidyDeviceLink.saveSettings({ volume: Math.round(v.volume), speed: Math.round(v.speed * 20) / 20 }), "本体に反映しました");
    busy.current = false;
  };

  useEffect(() => {
    if (visible) void load();
  }, [visible, load]);

  return (
    <View style={[boxStyle("#fff", 16, 1, Theme.Slate100), { paddingHorizontal: 16, paddingVertical: 14, gap: 6, marginBottom: 10 }]}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 14 }}>
        <View style={{ width: 42, height: 42, borderRadius: 12, backgroundColor: Theme.PrimaryLight, alignItems: "center", justifyContent: "center" }}>
          <Icon icon="Sliders" size={18} color={Theme.Primary} />
        </View>
        <View style={{ flex: 1, gap: 2 }}>
          <Label text="Aidyの声" size={14} color={Theme.TextMain} bold />
          <Label text="音量と話す速さを本体に送る" size={12} color={Theme.TextSub} />
        </View>
        <SmallButton text="読み込む" bg={Theme.Slate100} fg={Theme.Slate600} onPress={() => void load()} />
      </View>

      <SliderRow title="音量" value={`${Math.round(volume)}%`}>
        <AidySlider value={volume} min={0} max={100} steps={19} onChange={setVolume} onDone={(v) => void send({ volume: v, speed })} />
      </SliderRow>
      <SliderRow title="話す速さ" value={`${speedLabel(speed)} (×${speed.toFixed(2)})`}>
        <AidySlider
          value={speed}
          min={VoiceStore.MinSpeed}
          max={VoiceStore.MaxSpeed}
          steps={19}
          onChange={setSpeed}
          onDone={(v) => void send({ volume, speed: v })}
        />
      </SliderRow>
      <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
        <Label text="ゆっくり" size={10} color={Theme.Slate400} />
        <Label text="ふつう ×1.0" size={10} color={Theme.Slate400} />
        <Label text="はやい" size={10} color={Theme.Slate400} />
      </View>
      {status.length > 0 && <Label text={status} size={11} color={statusColor} bold />}
    </View>
  );
}

function SliderRow({ title, value, children }: { title: string; value: string; children: React.ReactNode }) {
  return (
    <View>
      <View style={{ flexDirection: "row", alignItems: "center", paddingTop: 6 }}>
        <Label text={title} size={13} color={Theme.TextMain} bold style={{ flex: 1 }} />
        <Label text={value} size={13} color={Theme.Primary} bold />
      </View>
      {children}
    </View>
  );
}
