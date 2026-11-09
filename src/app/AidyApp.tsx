import React, { useCallback, useRef, useState } from "react";
import { Animated, BackHandler, Image, Pressable, StyleSheet, View } from "react-native";
import PagerView from "react-native-pager-view";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Theme } from "../components/theme";
import { Icon } from "../components/icons";
import { IconId } from "../components/iconIds";
import { Label, EaseCss } from "../components/widgets";
import { AppState, PageAR, PageCount, PageDiary, PageHome, PageRemote, PageSettings } from "./appState";
import { HomePage } from "../pages/homePage";
import { RemotePage } from "../pages/remotePage";
import { SettingsPage } from "../pages/settingsPage";
import { DiaryPage } from "../pages/diaryPage";
import { ARPage } from "../pages/arPage";

const TabLabels = ["ホーム", "日記", "リモコン", "AR"];
const TabIcons: IconId[] = ["Aidy", "Book", "Gamepad", "Camera"];

const logoDark = require("../assets/aidy_logo_dark.png");

/** Unity 版 AidyApp: ヘッダー + 横スワイプのページ + ページの点 + 下のタブバー。AidyApp.kt のポート。 */
export function AidyApp({ app }: { app: AppState }) {
  const pagerRef = useRef<PagerView>(null);
  const [current, setCurrent] = useState(PageHome);
  const insets = useSafeAreaInsets();

  const goTo = useCallback((page: number) => {
    pagerRef.current?.setPage(page);
  }, []);

  // Android の戻るボタン
  React.useEffect(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      if (app.markerSheet.isOpen) {
        app.markerSheet.close();
        return true;
      }
      if (current !== PageHome) {
        goTo(PageHome);
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [app.markerSheet, current, goTo]);

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      <Header onSettings={() => goTo(PageSettings)} />
      <View style={styles.pagerWrap}>
        <PagerView
          ref={pagerRef}
          style={styles.pager}
          initialPage={PageHome}
          onPageSelected={(e) => setCurrent(e.nativeEvent.position)}
        >
          <View key="home" style={styles.page}>
            <HomePage
              ui={app.home}
              active={current === PageHome}
              onEdit={() => {
                app.ar.arMode = false;
                goTo(PageAR);
              }}
            />
          </View>
          <View key="diary" style={styles.page}>
            <DiaryPage active={current === PageDiary} />
          </View>
          <View key="remote" style={styles.page}>
            <RemotePage />
          </View>
          <View key="ar" style={styles.page}>
            <ARPage ui={app.ar} active={current === PageAR} onMarkerButton={() => app.markerSheet.open()} />
          </View>
          <View key="settings" style={styles.page}>
            <SettingsPage onOpenDiary={() => goTo(PageDiary)} />
          </View>
        </PagerView>
        {current !== PageDiary && current !== PageHome && current !== PageAR && (
          <>
            <NavArrow icon="ChevronLeft" enabled={current !== 0} side="left" onPress={() => goTo(current - 1)} />
            <NavArrow icon="ChevronRight" enabled={current !== PageCount - 1} side="right" onPress={() => goTo(current + 1)} />
          </>
        )}
      </View>
      <Dots current={current} onPress={goTo} />
      <TabBar current={current} onPress={goTo} />
    </View>
  );
}

function Header({ onSettings }: { onSettings: () => void }) {
  return (
    <View style={styles.header}>
      <Image source={logoDark} style={{ width: 26 * 3, height: 26 }} resizeMode="contain" />
      <View style={{ flex: 1 }} />
      <Pressable onPress={onSettings} style={{ width: 44, height: 44, alignItems: "center", justifyContent: "center" }}>
        <Icon icon="Gear" size={24} color={Theme.TextSub} />
      </Pressable>
    </View>
  );
}

function NavArrow({
  icon,
  enabled,
  side,
  onPress,
}: {
  icon: IconId;
  enabled: boolean;
  side: "left" | "right";
  onPress: () => void;
}) {
  return (
    <Pressable
      disabled={!enabled}
      onPress={onPress}
      style={[
        styles.navArrow,
        side === "left" ? { left: 10 } : { right: 10 },
        { opacity: enabled ? 1 : 0.3, backgroundColor: "rgba(255,255,255,0.85)", borderColor: "rgba(255,255,255,0.9)" },
      ]}
    >
      <Icon icon={icon} size={16} color={Theme.Slate600} />
    </Pressable>
  );
}

function Dots({ current, onPress }: { current: number; onPress: (i: number) => void }) {
  return (
    <View style={styles.dots}>
      {TabLabels.map((_, i) => (
        <Dot key={i} active={i === current} onPress={() => onPress(i)} />
      ))}
    </View>
  );
}

function Dot({ active, onPress }: { active: boolean; onPress: () => void }) {
  const t = useRef(new Animated.Value(active ? 1 : 0)).current;
  React.useEffect(() => {
    Animated.timing(t, { toValue: active ? 1 : 0, duration: 300, easing: EaseCss, useNativeDriver: false }).start();
  }, [active, t]);
  const width = t.interpolate({ inputRange: [0, 1], outputRange: [8, 24] });
  return (
    <Pressable onPress={onPress}>
      <Animated.View style={{ height: 8, borderRadius: 4, width, backgroundColor: active ? Theme.Primary : Theme.Slate300 }} />
    </Pressable>
  );
}

function TabBar({ current, onPress }: { current: number; onPress: (i: number) => void }) {
  const insets = useSafeAreaInsets();
  return (
    <View style={{ backgroundColor: "#fff", paddingBottom: insets.bottom }}>
      <View style={{ height: 1, backgroundColor: Theme.Border }} />
      <View style={styles.tabBar}>
        {TabLabels.map((label, i) => {
          const active = i === current;
          return (
            <Pressable key={label} onPress={() => onPress(i)} style={styles.tabItemWrap}>
              <View style={[styles.tabItem, active && { backgroundColor: Theme.PrimaryLight }]}>
                <Icon icon={TabIcons[i]} size={18} color={active ? Theme.Primary : Theme.Slate400} />
                <Label text={label} size={11} color={active ? Theme.Primary : Theme.Slate400} bold />
              </View>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: Theme.Device },
  header: {
    height: 50,
    flexDirection: "row",
    alignItems: "center",
    paddingLeft: 16,
    paddingRight: 10,
    backgroundColor: Theme.Device,
  },
  pagerWrap: { flex: 1 },
  pager: { flex: 1 },
  page: { flex: 1 },
  navArrow: {
    position: "absolute",
    top: "50%",
    marginTop: -20,
    width: 40,
    height: 40,
    borderRadius: 20,
    borderWidth: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  dots: { height: 24, flexDirection: "row", gap: 8, alignItems: "center", justifyContent: "center", backgroundColor: Theme.Device },
  tabBar: { height: 63, flexDirection: "row", paddingHorizontal: 10, alignItems: "center" },
  tabItemWrap: { flex: 1, alignItems: "center" },
  tabItem: { alignItems: "center", gap: 4, paddingHorizontal: 10, paddingVertical: 6, borderRadius: 16 },
});
