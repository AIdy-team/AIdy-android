import React, { useEffect, useState } from "react";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { StatusBar } from "expo-status-bar";
import { AidyApp } from "./src/app/AidyApp";
import { AppState } from "./src/app/appState";
import { Prefs } from "./src/data/prefs";
import { DiaryDemo } from "./src/data/diary";

export default function App() {
  const [ready, setReady] = useState(false);
  const [app] = useState(() => new AppState());

  useEffect(() => {
    (async () => {
      await Prefs.preload();
      await DiaryDemo.seedIfNeeded();
      setReady(true);
    })();
  }, []);

  if (!ready) return null;

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <AidyApp app={app} />
        <StatusBar style="dark" />
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
