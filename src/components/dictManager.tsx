import React, { useEffect, useRef, useState } from "react";
import { Animated, KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Theme } from "./theme";
import { Icon } from "./icons";
import { Input, Label, Pressed, SmallButton, boxStyle } from "./widgets";
import { AidyFace, AidyMascot } from "./mascot";
import { AidyDeviceLink, DictData, DictResult } from "../data/diary";

/** 辞書の種類ごとの見た目と説明。 */
interface DictKind {
  kind: "faq" | "asr" | "yomi";
  title: string;
  short: string;
  help: string;
  labelA: string;
  labelB: string;
  hintA: string;
  hintB: string;
  color: string;
  bg: string;
}

const Kinds: DictKind[] = [
  {
    kind: "faq", title: "よくある質問", short: "質問",
    help: "聞かれたら、決まった答えをそのまま話します",
    labelA: "質問", labelB: "答え", hintA: "例: 何ができるの？", hintB: "例: お話ししたり、予定を教えたりできるよ (400文字まで)",
    color: Theme.hex(0x8b5cf6), bg: Theme.hex(0xf3e8ff),
  },
  {
    kind: "asr", title: "聞き間違い", short: "聞き間違い",
    help: "よく聞き間違える言葉を、正しい言葉に直してから考えます",
    labelA: "聞き間違えた言葉", labelB: "正しい言葉", hintA: "例: あいでぃ", hintB: "例: Aidy",
    color: Theme.hex(0xf97316), bg: Theme.hex(0xffedd5),
  },
  {
    kind: "yomi", title: "読み", short: "読み",
    help: "漢字や英語を、ここで決めた読み方で話します",
    labelA: "書き方", labelB: "読み (ひらがな・カタカナ)", hintA: "例: 明日", hintB: "例: あした",
    color: Theme.hex(0x10b981), bg: Theme.hex(0xd1fae5),
  },
];

/** 1 件分 (表示用)。key は削除に使う (faq は番号、ほかは書き方)。 */
interface DictItem {
  a: string;
  b: string;
  key: string | null;
  idx: number | null;
}

function itemsOf(dict: DictData | null, i: number): DictItem[] {
  if (!dict) return [];
  if (i === 0) return dict.faq.map((f, n) => ({ a: f.q, b: f.a, key: null, idx: n }));
  const list = i === 1 ? dict.asr : dict.yomi;
  return list.map(([a, b]) => ({ a, b, key: a, idx: null }));
}

/**
 * 辞書 (よくある質問・聞き間違い・読み) を見て・探して・足して・消せるフルスクリーンの画面 (DictManager.kt のポート)。
 * GET /api/dict ・ POST /api/add ・ POST /api/del を使う。
 */
export function DictManagerModal({ visible, onDismiss }: { visible: boolean; onDismiss: () => void }) {
  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onDismiss}>
      {visible && <DictManager onDismiss={onDismiss} />}
    </Modal>
  );
}

function DictManager({ onDismiss }: { onDismiss: () => void }) {
  const insets = useSafeAreaInsets();
  const [tab, setTab] = useState(0);
  const [dict, setDict] = useState<DictData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState("");
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState("");
  const [adding, setAdding] = useState(false);
  const [confirmKey, setConfirmKey] = useState<string | null>(null);

  const applyDict = (r: DictResult) => {
    setLoading(false);
    if (r.ok) setDict(r.dict);
    else setError(r.error ?? "読み込めませんでした");
  };
  const reload = async () => {
    setLoading(true);
    setError(null);
    applyDict(await AidyDeviceLink.fetchDict());
  };
  useEffect(() => {
    void AidyDeviceLink.fetchDict().then(applyDict);
  }, []);
  const [addSerial, setAddSerial] = useState(0);

  useEffect(() => {
    if (toast.length === 0) return;
    const t = setTimeout(() => setToast(""), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  const k = Kinds[tab];
  const all = itemsOf(dict, tab);
  const q = query.trim().toLowerCase();
  const shown = q.length === 0 ? all : all.filter((it) => it.a.toLowerCase().includes(q) || it.b.toLowerCase().includes(q));

  let body: React.ReactNode;
  if (loading && dict == null) body = <CenterNote face="Normal" text="本体から辞書を読み込み中…" />;
  else if (error != null && dict == null) body = <CenterNote face="Sleepy" text={error} button="もう一度読み込む" onButton={() => void reload()} />;
  else if (all.length === 0) body = <CenterNote face="Happy" text={`まだ${k.title}はありません\n右下の「＋ 追加」から登録してね`} />;
  else
    body = (
      <ScrollView contentContainerStyle={{ paddingHorizontal: 16, paddingTop: 8, paddingBottom: 100 }} keyboardShouldPersistTaps="handled">
        {shown.length === 0 && <Label text={`「${query.trim()}」に当てはまるものはありません`} size={12} color={Theme.Slate400} style={{ padding: 8 }} />}
        {shown.map((item) => {
          const id = `${k.kind}:${item.key ?? item.idx}`;
          return (
            <DictRow
              key={id}
              k={k}
              item={item}
              confirming={confirmKey === id}
              busy={busy}
              onAskDelete={() => setConfirmKey(confirmKey === id ? null : id)}
              onDelete={async () => {
                setBusy(true);
                const r = await AidyDeviceLink.deleteDictEntry(k.kind, item.key, item.idx);
                setBusy(false);
                setConfirmKey(null);
                if (r.ok && r.dict) {
                  setDict(r.dict);
                  setToast(`「${item.a}」を削除しました`);
                } else setToast(r.error ?? "削除できませんでした");
              }}
            />
          );
        })}
      </ScrollView>
    );

  return (
    <View style={{ flex: 1, backgroundColor: Theme.Device, paddingTop: insets.top }}>
      {/* ヘッダー */}
      <View style={{ flexDirection: "row", alignItems: "center", gap: 8, paddingLeft: 8, paddingRight: 16, paddingTop: 8, paddingBottom: 4 }}>
        <Pressed onPress={onDismiss} pressScale={0.9} style={{ width: 40, height: 40, alignItems: "center", justifyContent: "center" }}>
          <Icon icon="ChevronLeft" size={16} color={Theme.Slate600} />
        </Pressed>
        <AidyMascot size={36} face={error != null ? "Sleepy" : "Normal"} />
        <View style={{ flex: 1 }}>
          <Label text="Aidyの辞書" size={17} color={Theme.TextMain} bold />
          <Label text="本体の聞き取り・話し方・答えをカスタマイズ" size={11} color={Theme.TextSub} />
        </View>
        <Pressed onPress={() => void reload()} pressScale={0.9} style={{ width: 36, height: 36, borderRadius: 18, backgroundColor: Theme.Slate100, alignItems: "center", justifyContent: "center" }}>
          <Icon icon="Wifi" size={14} color={Theme.Slate600} />
        </Pressed>
      </View>

      {/* 種類のタブ (件数つき) */}
      <View style={{ flexDirection: "row", gap: 8, paddingHorizontal: 16, paddingVertical: 8 }}>
        {Kinds.map((kk, i) => {
          const sel = tab === i;
          return (
            <View key={kk.kind} style={{ flex: 1 }}>
              <Pressed
                onPress={() => {
                  setTab(i);
                  setQuery("");
                  setConfirmKey(null);
                }}
                pressScale={0.95}
                style={{ alignItems: "center", gap: 2, paddingVertical: 8, borderRadius: 14, borderWidth: 1, backgroundColor: sel ? kk.color : "#fff", borderColor: sel ? kk.color : Theme.Slate100 }}
              >
                <Label text={kk.title} size={12} color={sel ? "#fff" : Theme.Slate700} bold />
                <Label text={dict ? `${itemsOf(dict, i).length}件` : "–"} size={10} color={sel ? "rgba(255,255,255,0.85)" : Theme.Slate400} bold />
              </Pressed>
            </View>
          );
        })}
      </View>

      {/* 説明 + 検索 */}
      <View style={{ paddingHorizontal: 16, gap: 8 }}>
        <View style={{ flexDirection: "row", alignItems: "center", gap: 8, backgroundColor: k.bg, borderRadius: 12, paddingHorizontal: 12, paddingVertical: 8 }}>
          <Icon icon="Lightbulb" size={12} color={k.color} />
          <Label text={k.help} size={11} color={k.color} bold style={{ flex: 1 }} />
        </View>
        {dict != null && all.length > 3 && <Input value={query} onChange={(v) => setQuery(v.slice(0, 40))} placeholder={`🔍 ${k.short}を探す`} />}
      </View>

      <View style={{ flex: 1 }}>{body}</View>

      {/* 右下: 追加ボタン */}
      {dict != null && !adding && (
        <View style={{ position: "absolute", right: 20, bottom: insets.bottom + 20 }}>
          <Pressed
            onPress={() => {
              setAddSerial((n) => n + 1);
              setAdding(true);
            }}
            pressScale={0.95}
            style={[boxStyle(k.color, 26, 6), { height: 52, flexDirection: "row", alignItems: "center", gap: 8, paddingHorizontal: 20 }]}
          >
            <Icon icon="Plus" size={14} color="#fff" />
            <Label text={`${k.short}を追加`} size={14} color="#fff" bold />
          </Pressed>
        </View>
      )}

      {/* お知らせ */}
      {toast.length > 0 && (
        <View pointerEvents="none" style={{ position: "absolute", alignSelf: "center", bottom: insets.bottom + 86, backgroundColor: "rgba(51,65,85,0.92)", borderRadius: 16, paddingHorizontal: 14, paddingVertical: 8 }}>
          <Label text={toast} size={12} color="#fff" bold />
        </View>
      )}

      {/* 追加のシート */}
      <AddSheet
        key={`${k.kind}-${addSerial}`}
        visible={adding}
        k={k}
        busy={busy}
        bottomInset={insets.bottom}
        onCancel={() => setAdding(false)}
        onAdd={async (a, b, setErr) => {
          setBusy(true);
          const r = await AidyDeviceLink.addDictEntry(k.kind, a, b);
          setBusy(false);
          if (r.ok && r.dict) {
            setDict(r.dict);
            setAdding(false);
            setToast(`「${a}」を登録しました`);
          } else setErr(r.error ?? "追加できませんでした");
        }}
      />
    </View>
  );
}

/** 1 件。質問は吹き出し 2 つ、聞き間違い・読みは「左 → 右」のチップ。ゴミ箱で確認してから消す。 */
function DictRow({
  k,
  item,
  confirming,
  busy,
  onAskDelete,
  onDelete,
}: {
  k: DictKind;
  item: DictItem;
  confirming: boolean;
  busy: boolean;
  onAskDelete: () => void;
  onDelete: () => void;
}) {
  return (
    <View style={[boxStyle("#fff", 16, 1, Theme.Slate100), { padding: 12, gap: 8, marginBottom: 8 }]}>
      <View style={{ flexDirection: "row", alignItems: "flex-start", gap: 8 }}>
        {k.kind === "faq" ? (
          <View style={{ flex: 1, gap: 6 }}>
            <Bubble tag="Q" text={item.a} bg={Theme.Slate100} fg={Theme.TextMain} tagColor={k.color} filled={false} />
            <Bubble tag="A" text={item.b} bg={k.bg} fg={Theme.Slate700} tagColor={k.color} filled />
          </View>
        ) : (
          <View style={{ flex: 1, flexDirection: "row", alignItems: "center", gap: 8 }}>
            <Chip text={item.a} bg={Theme.Slate100} fg={Theme.TextMain} />
            <Icon icon="ChevronRight" size={10} color={k.color} />
            <Chip text={item.b} bg={k.bg} fg={k.color} />
          </View>
        )}
        <Pressed
          onPress={onAskDelete}
          pressScale={0.9}
          style={{ width: 32, height: 32, borderRadius: 16, alignItems: "center", justifyContent: "center", backgroundColor: confirming ? Theme.hex(0xfee2e2) : "transparent" }}
        >
          <Icon icon="XMark" size={12} color={confirming ? Theme.hex(0xef4444) : Theme.Slate400} />
        </Pressed>
      </View>
      {confirming && (
        <View style={{ flexDirection: "row", alignItems: "center", gap: 6, backgroundColor: Theme.hex(0xfef2f2), borderRadius: 10, paddingLeft: 10, paddingRight: 6, paddingVertical: 6 }}>
          <Label text="本体の辞書から削除しますか？" size={11} color={Theme.hex(0xb91c1c)} bold style={{ flex: 1 }} />
          <SmallButton text="やめる" bg="#fff" fg={Theme.Slate600} onPress={onAskDelete} />
          <SmallButton text={busy ? "削除中…" : "削除"} bg={Theme.hex(0xef4444)} fg="#fff" onPress={() => !busy && onDelete()} />
        </View>
      )}
    </View>
  );
}

function Bubble({ tag, text, bg, fg, tagColor, filled }: { tag: string; text: string; bg: string; fg: string; tagColor: string; filled: boolean }) {
  return (
    <View style={{ flexDirection: "row", alignItems: "flex-start", gap: 6 }}>
      <View style={{ width: 22, height: 22, borderRadius: 11, borderWidth: 1.5, borderColor: tagColor, backgroundColor: filled ? tagColor : "#fff", alignItems: "center", justifyContent: "center" }}>
        <Label text={tag} size={11} color={filled ? "#fff" : tagColor} bold />
      </View>
      <View style={{ flexShrink: 1, backgroundColor: bg, borderTopLeftRadius: 4, borderTopRightRadius: 12, borderBottomLeftRadius: 12, borderBottomRightRadius: 12, paddingHorizontal: 10, paddingVertical: 6 }}>
        <Label text={text} size={12} color={fg} bold={tag === "Q"} />
      </View>
    </View>
  );
}

function Chip({ text, bg, fg }: { text: string; bg: string; fg: string }) {
  return (
    <View style={{ flexShrink: 1, backgroundColor: bg, borderRadius: 10, paddingHorizontal: 10, paddingVertical: 7 }}>
      <Label text={text} size={13} color={fg} bold maxLines={2} />
    </View>
  );
}

/** 一覧の代わりに真ん中に出す案内 (読み込み中・つながらない・空)。 */
function CenterNote({ face, text, button, onButton }: { face: AidyFace; text: string; button?: string; onButton?: () => void }) {
  return (
    <View style={{ flex: 1, padding: 24, alignItems: "center", justifyContent: "center" }}>
      <AidyMascot size={72} face={face} />
      <View style={{ height: 12 }} />
      <Label text={text} size={13} color={Theme.TextSub} bold align="center" style={{ maxWidth: 280 }} />
      {button && onButton && (
        <View style={{ marginTop: 14 }}>
          <SmallButton text={button} bg={Theme.Primary} fg="#fff" onPress={onButton} />
        </View>
      )}
    </View>
  );
}

/** 下から出る追加のシート。プレビューで登録後の見え方を出す。 */
function AddSheet({
  visible,
  k,
  busy,
  bottomInset,
  onCancel,
  onAdd,
}: {
  visible: boolean;
  k: DictKind;
  busy: boolean;
  bottomInset: number;
  onCancel: () => void;
  onAdd: (a: string, b: string, setError: (e: string) => void) => void;
}) {
  const [a, setA] = useState("");
  const [b, setB] = useState("");
  const [err, setErr] = useState("");
  const t = useRef(new Animated.Value(0)).current;
  const [mounted, setMounted] = useState(visible);
  if (visible && !mounted) setMounted(true);

  useEffect(() => {
    Animated.timing(t, { toValue: visible ? 1 : 0, duration: 220, useNativeDriver: true }).start(() => {
      if (!visible) setMounted(false);
    });
  }, [visible, t]);

  if (!mounted) return null;
  const maxB = k.kind === "faq" ? 400 : 100;
  const ta = a.trim();
  const tb = b.trim();
  const preview =
    k.kind === "faq" ? `「${ta}」と聞かれたら →「${tb}」と答える` : k.kind === "asr" ? `「${ta}」と聞こえたら →「${tb}」として考える` : `「${ta}」は →「${tb}」と読む`;
  const translateY = t.interpolate({ inputRange: [0, 1], outputRange: [500, 0] });

  return (
    <View style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0 }}>
      <Animated.View style={{ position: "absolute", left: 0, right: 0, top: 0, bottom: 0, backgroundColor: "rgba(0,0,0,0.35)", opacity: t }}>
        <Pressable style={{ flex: 1 }} onPress={onCancel} />
      </Animated.View>
      <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={{ position: "absolute", left: 0, right: 0, bottom: 0 }}>
        <Animated.View style={{ transform: [{ translateY }], backgroundColor: "#fff", borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 20, paddingBottom: 20 + bottomInset, gap: 8 }}>
          <View style={{ alignSelf: "center", width: 40, height: 4, borderRadius: 2, backgroundColor: Theme.Slate300 }} />
          <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
            <View style={{ width: 28, height: 28, borderRadius: 14, backgroundColor: k.bg, alignItems: "center", justifyContent: "center" }}>
              <Icon icon="Plus" size={12} color={k.color} />
            </View>
            <Label text={`${k.title}を追加`} size={16} color={Theme.TextMain} bold />
          </View>
          <Label text={k.labelA} size={11} color={Theme.TextSub} bold />
          <Input value={a} onChange={(v) => { setA(v.slice(0, 100)); setErr(""); }} placeholder={k.hintA} />
          <Label text={k.labelB + (k.kind === "faq" ? `  ${b.length}/${maxB}` : "")} size={11} color={Theme.TextSub} bold />
          <Input value={b} onChange={(v) => { setB(v.slice(0, maxB)); setErr(""); }} placeholder={k.hintB} multiline={k.kind === "faq"} />
          {ta.length > 0 && tb.length > 0 && (
            <>
              <Label text="こうなります" size={10} color={Theme.Slate400} bold />
              <Label text={preview} size={12} color={k.color} bold />
            </>
          )}
          {err.length > 0 && <Label text={err} size={12} color={Theme.hex(0xef4444)} bold />}
          <View style={{ flexDirection: "row", gap: 8, paddingTop: 4 }}>
            <View style={{ flex: 1 }}>
              <Pressed onPress={onCancel} pressScale={0.97} style={{ height: 46, borderRadius: 23, backgroundColor: Theme.Slate100, alignItems: "center", justifyContent: "center" }}>
                <Label text="キャンセル" size={13} color={Theme.Slate700} bold />
              </Pressed>
            </View>
            <View style={{ flex: 1 }}>
              <Pressed
                onPress={() => {
                  if (busy) return;
                  if (ta.length === 0 || tb.length === 0) setErr("両方を入れてください");
                  else onAdd(ta, tb, setErr);
                }}
                pressScale={0.97}
                style={{ height: 46, borderRadius: 23, backgroundColor: k.color, alignItems: "center", justifyContent: "center" }}
              >
                <Label text={busy ? "送信中…" : "本体に登録"} size={13} color="#fff" bold />
              </Pressed>
            </View>
          </View>
        </Animated.View>
      </KeyboardAvoidingView>
    </View>
  );
}
