import React, { useEffect, useRef, useState } from "react";
import { Modal, StyleSheet, View } from "react-native";
import { CameraView, useCameraPermissions } from "expo-camera";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Label, SmallButton } from "./widgets";
import { AidyDeviceLink, type QrInfo } from "../data/diary";

/**
 * 本体の QR ("http://host:port/?t=token&b=AIdy-xxxx" か "aidy://ble?b=AIdy-xxxx&t=token"、仕様書 AIdy_アプリ連携_仕様.md・本体Bluetooth対応_仕様.txt 参照) をカメラで読み取る
 * フルスクリーンの画面 (QrScanner.kt のポート)。読み取れたら onResult(住所・合言葉・Bluetooth の名前) を呼ぶ。端末の中だけで処理する (通信なし)。
 * expo-camera は Expo Go に入っているので、開発ビルドなしで使える (実機のみ)。
 */
export function QrScannerModal({
  visible,
  onResult,
  onDismiss,
}: {
  visible: boolean;
  onResult: (info: QrInfo) => void;
  onDismiss: () => void;
}) {
  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onDismiss}>
      {visible && <Scanner onResult={onResult} onDismiss={onDismiss} />}
    </Modal>
  );
}

function Scanner({ onResult, onDismiss }: { onResult: (info: QrInfo) => void; onDismiss: () => void }) {
  const [permission, requestPermission] = useCameraPermissions();
  const [message, setMessage] = useState("本体の QR を枠の中に写してね");
  const handled = useRef(false);
  const lastRaw = useRef("");
  const insets = useSafeAreaInsets();

  useEffect(() => {
    if (permission && !permission.granted && permission.canAskAgain) void requestPermission();
  }, [permission, requestPermission]);

  return (
    <View style={styles.root}>
      {permission?.granted ? (
        <>
          <CameraView
            style={StyleSheet.absoluteFill}
            facing="back"
            barcodeScannerSettings={{ barcodeTypes: ["qr"] }}
            onBarcodeScanned={(r) => {
              if (handled.current || r.data === lastRaw.current) return; // 同じ QR が写り続けても 1 回だけ
              lastRaw.current = r.data;
              const parsed = AidyDeviceLink.parseQr(r.data);
              if (parsed) {
                handled.current = true;
                onResult(parsed);
              } else setMessage("この QR コードは Aidy 本体のものじゃないみたい");
            }}
          />
          <View pointerEvents="none" style={styles.frame} />
        </>
      ) : (
        <View style={styles.center}>
          <Label text={permission == null ? "カメラを準備中…" : "カメラの権限が必要です"} size={14} color="#fff" bold />
          {permission != null && !permission.granted && (
            <View style={{ marginTop: 12 }}>
              <SmallButton text="権限を許可する" bg="rgba(255,255,255,0.2)" fg="#fff" onPress={() => void requestPermission()} />
            </View>
          )}
        </View>
      )}

      <View style={[styles.bottom, { paddingBottom: insets.bottom + 24 }]}>
        <Label text={message} size={13} color="#fff" bold align="center" />
        <View style={{ height: 12 }} />
        <SmallButton text="閉じる" bg="rgba(255,255,255,0.2)" fg="#fff" onPress={onDismiss} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: "#000" },
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
  frame: {
    position: "absolute",
    alignSelf: "center",
    top: "50%",
    marginTop: -120,
    width: 240,
    height: 240,
    borderWidth: 3,
    borderColor: "#fff",
    borderRadius: 16,
  },
  bottom: { position: "absolute", left: 0, right: 0, bottom: 0, alignItems: "center", paddingHorizontal: 24 },
});
