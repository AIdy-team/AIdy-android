import React, { useState } from "react";
import { View } from "react-native";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { Card, Input, Label, ScrollPage, SectionTitle, SmallButton, AidySwitch, boxStyle } from "../components/widgets";
import { QrScannerModal } from "../components/qrScanner";
import { AidyDeviceLink, CalendarSettings, ScheduleStore, type Transport } from "../data/diary";
import { BleLink } from "../data/bleLink";
import { PhoneCalendar } from "../data/phoneCalendar";

const PhoneSyncOffMessage = "予定をiPhoneのカレンダーと同期する";

/** 設定画面。Pages.kt SettingsPage のポート (+ iPhone のカレンダー連動)。 */
export function SettingsPage({ active, onOpenDiary }: { active: boolean; onOpenDiary: () => void }) {
  const [weekView, setWeekView] = useState(CalendarSettings.weekView);
  const [phoneSync, setPhoneSync] = useState(PhoneCalendar.enabled);
  const [phoneSyncMessage, setPhoneSyncMessage] = useState(PhoneCalendar.enabled ? "iPhoneのカレンダーと連動しています" : PhoneSyncOffMessage);
  const [target, setTarget] = useState(AidyDeviceLink.targetLabel);
  const [configured, setConfigured] = useState(AidyDeviceLink.isConfigured);
  const refreshTarget = () => {
    setTarget(AidyDeviceLink.targetLabel);
    setConfigured(AidyDeviceLink.isConfigured);
  };
  // 日記ページ側で月/週を切り替えたとき・接続を変えたときに合わせる (ページを開いたときに読み直す)
  const [wasActive, setWasActive] = useState(active);
  if (active !== wasActive) {
    setWasActive(active);
    if (active) {
      setWeekView(CalendarSettings.weekView);
      refreshTarget();
    }
  }

  const targetLabel = configured ? target : "未設定（下で合言葉などを入れてね）";

  return (
    <ScrollPage style={{ backgroundColor: Theme.Device }}>
      <SectionTitle text="Aidy本体と接続" first />
      <Card
        icon="Aidy"
        iconColor={Theme.Primary}
        iconBg={Theme.PrimaryLight}
        title="Aidy本体の会話ログ"
        sub={`${targetLabel} から日記を同期`}
        onPress={onOpenDiary}
        trailing={<Icon icon="ChevronRight" size={12} color={Theme.Slate400} />}
      />
      <DeviceLinkCard onSaved={refreshTarget} />
      <Label text="音量・話す速さ・辞書はリモコンのページにあります" size={11} color={Theme.TextSub} style={{ paddingLeft: 4, marginBottom: 6 }} />

      <SectionTitle text="ロボット設定" first={false} />
      <Card title="アセンブリ構造" sub="本体v2_モデル.obj (透明化防止完全対応)" trailing={<Icon icon="Check" size={14} color={Theme.Green} />} />

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
      <Card
        title="iPhoneのカレンダーと連動"
        sub={phoneSyncMessage}
        trailing={
          <AidySwitch
            on={phoneSync}
            onChange={(on) => {
              if (!on) {
                PhoneCalendar.disable();
                setPhoneSync(false);
                setPhoneSyncMessage(PhoneSyncOffMessage);
                return;
              }
              setPhoneSync(true);
              void (async () => {
                const r = await PhoneCalendar.enable();
                setPhoneSync(r.ok);
                setPhoneSyncMessage(r.message);
                if (r.ok) await ScheduleStore.pushAllToPhone();
              })();
            }}
          />
        }
      />

      <SectionTitle text="操作 & ネットワーク" first={false} />
      <Card title="モジュール自動合体" sub="パーツSocketアタッチ機能 (ON)" trailing={<AidySwitch on={true} onChange={() => {}} />} />
      <Card title="Wi-Fi 接続" sub="AIDY-5G-2.4G (接続済み)" trailing={<Icon icon="Wifi" size={16} color={Theme.Green} />} />

      <SectionTitle text="今後の開発予定" first={false} />
      <FutureFeatureList />
    </ScrollPage>
  );
}

/**
 * つなぎ方 (Bluetooth / Wi-Fi)・Bluetooth の名前・住所 (IP:ポート)・合言葉を、カメラでの QR 読み取りか手入力で設定し、
 * /api/ping で接続を確かめるカード。
 */
function DeviceLinkCard({ onSaved }: { onSaved: () => void }) {
  const [transport, setTransport] = useState<Transport>(AidyDeviceLink.transport);
  const [host, setHost] = useState(AidyDeviceLink.host);
  const [bleName, setBleName] = useState(AidyDeviceLink.bleName);
  const [token, setToken] = useState(AidyDeviceLink.token);
  const [checking, setChecking] = useState(false);
  const [status, setStatus] = useState(AidyDeviceLink.isConfigured ? "保存済み。「保存して接続を試す」で確かめてね" : "まだ設定されていません");
  const [statusColor, setStatusColor] = useState(Theme.TextSub);
  const [showScanner, setShowScanner] = useState(false);

  const testConnect = async (tr: Transport, h: string, b: string, t: string) => {
    if (checking) return;
    AidyDeviceLink.transport = tr;
    AidyDeviceLink.host = h;
    AidyDeviceLink.bleName = b;
    AidyDeviceLink.token = t;
    onSaved();
    setChecking(true);
    setStatusColor(Theme.Slate600);
    setStatus(tr === "ble" ? (b.length > 0 ? `Bluetooth で ${b} を探しています…` : "Bluetooth で近くの Aidy を探しています…") : "接続を確認中…");
    const r = await AidyDeviceLink.ping();
    setChecking(false);
    setStatusColor(r.ok ? Theme.Green : Theme.hex(0xef4444));
    setStatus(r.message);
  };

  const ble = transport === "ble";
  return (
    <View style={[boxStyle("#fff", 16, 1, Theme.Slate100), { paddingHorizontal: 16, paddingVertical: 14, gap: 10, marginBottom: 10 }]}>
      <Label text="つなぎ方" size={11} color={Theme.TextSub} bold />
      <View style={{ flexDirection: "row", gap: 8 }}>
        <TransportButton text="Bluetooth" on={ble} onPress={() => setTransport("ble")} />
        <TransportButton text="Wi-Fi" on={!ble} onPress={() => setTransport("wifi")} />
      </View>
      {ble ? (
        <>
          {!BleLink.available && (
            <Label text="このアプリには Bluetooth の部品が入っていません (Expo Go では使えません。開発ビルドで試してね)" size={11} color={Theme.hex(0xef4444)} />
          )}
          <Label text="本体の Bluetooth の名前 (QR の b= の後ろ。空なら近くの Aidy を探す)" size={11} color={Theme.TextSub} bold />
          <Input value={bleName} onChange={(v) => setBleName(v.slice(0, 32))} placeholder="例: AIdy-1A2B" />
        </>
      ) : (
        <>
          <Label text="本体のアドレス (IP:ポート)" size={11} color={Theme.TextSub} bold />
          <Input value={host} onChange={(v) => setHost(v.slice(0, 64))} placeholder="例: 192.168.1.23:8090" />
        </>
      )}
      <Label text="合言葉 (本体の QR の t= の後ろ)" size={11} color={Theme.TextSub} bold />
      <Input value={token} onChange={(v) => setToken(v.slice(0, 64))} placeholder="QRコードの合言葉" password />
      <Label text={status} size={12} color={statusColor} bold />
      <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
        <SmallButton text="QRコードを読み取る" bg={Theme.Slate100} fg={Theme.Slate600} onPress={() => setShowScanner(true)} />
        <View style={{ flex: 1 }} />
        <SmallButton text={checking ? "確認中…" : "保存して接続を試す"} bg={Theme.Primary} fg="#fff" onPress={() => void testConnect(transport, host, bleName, token)} />
      </View>

      <QrScannerModal
        visible={showScanner}
        onDismiss={() => setShowScanner(false)}
        onResult={(q) => {
          // Bluetooth だけの QR (住所なし) なら Bluetooth に切り替える。それ以外は今のつなぎ方のまま
          const tr: Transport = q.host.length === 0 ? "ble" : transport;
          const h = q.host.length > 0 ? q.host : host;
          const b = q.bleName;
          setTransport(tr);
          setHost(h);
          setBleName(b);
          setToken(q.token);
          setShowScanner(false);
          void testConnect(tr, h, b, q.token);
        }}
      />
    </View>
  );
}

function TransportButton({ text, on, onPress }: { text: string; on: boolean; onPress: () => void }) {
  return <SmallButton text={on ? `● ${text}` : text} bg={on ? Theme.PrimaryLight : Theme.Slate100} fg={on ? Theme.Primary : Theme.Slate600} onPress={onPress} />;
}

/**
 * 仕様書 (AIdy_アプリ連携_仕様.md) にあっても、まだこのアプリ (または本体) にない機能をまとめたリスト。
 * 口ができたときにすぐ実装できるよう、どの API に対応するかをここに残しておく。
 */
function FutureFeatureList() {
  return (
    <View style={{ backgroundColor: Theme.Slate100, borderRadius: 16, paddingHorizontal: 16, paddingVertical: 14, gap: 10, marginBottom: 10 }}>
      <Label text="下は本体側にまだ口が無いので、このアプリからは作れません。本体が対応したら実装してください。" size={11} color={Theme.TextSub} />
      <FutureRow title="本体の今のようす (表情・人・曲・タイマー)" sub="GET /api/status (本体側が指示書の段階2で対応予定)" />
      <FutureRow title="予定をまとめて見る・やることを足す" sub="GET /api/schedule ・ POST /api/todo (本体側が指示書の段階3で対応予定)" />
      <FutureRow title="文字で話させる・話しかける" sub="POST /api/say ・ POST /api/talk (本体側が指示書の段階4で対応予定)" />
      <FutureRow title="スマホのマイクで話しかける" sub="POST /api/speech (本体側が指示書の段階5で対応予定)" />
    </View>
  );
}

function FutureRow({ title, sub }: { title: string; sub: string }) {
  return (
    <View style={{ gap: 2 }}>
      <Label text={`・${title}`} size={12} color={Theme.TextMain} bold />
      <Label text={sub} size={11} color={Theme.TextSub} />
    </View>
  );
}
