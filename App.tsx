import React, { useEffect, useState } from "react";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { StatusBar } from "expo-status-bar";
import { AidyApp } from "./src/app/AidyApp";
import { AppState } from "./src/app/appState";
import { Prefs } from "./src/data/prefs";
import { DiaryDemo } from "./src/data/diary";

export default function App() {
  // 保存データ (着せ替え・色など) を読み終えてから 3D の状態を作る (先に作ると標準構成で上書きしてしまう)
  const [app, setApp] = useState<AppState | null>(null);

  useEffect(() => {
    (async () => {
      await Prefs.preload();
      await DiaryDemo.seedIfNeeded();
      setApp(new AppState());
    })();
  }, []);

  if (!app) return null;

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <AidyApp app={app} />
        <StatusBar style="dark" />
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
