# AIDY (React Native / Expo 版)

ペットロボット「Aidy」アプリの Expo/React Native 移植版。Unity/Kotlin 版からの移植作業中。

## 必要なもの

- Node.js(推奨: 20 以上)・npm
- スマホ実機(iOS / Android)に [Expo Go](https://expo.dev/go) アプリをインストール
  - 3D ビュー・ジェスチャー等は実機でないと正しく確認できません(シミュレータ非推奨)

## セットアップ

```bash
npm install
```

## 起動

```bash
npx expo start
```

表示される QR コードを、スマホの Expo Go アプリ(または端末のカメラ)で読み取って開きます。

- ターミナルで `s` キーを押すと Expo Go 用の接続リンクに切り替えられます(カスタム開発ビルド用のリンクのままだと Expo Go では正しく開けません)。
- コードを変更しても動作がおかしい場合は、一度キャッシュをクリアして再起動してください。

  ```bash
  npx expo start -c
  ```

  あわせてスマホ側のアプリも完全に終了してから開き直してください。

## その他のコマンド

```bash
npx tsc --noEmit     # 型チェック
npm run lint         # ESLint (expo lint)
npm run android      # Android 用ネイティブビルドを作成して実行 (要 Android Studio / SDK)
npm run ios          # iOS 用ネイティブビルドを作成して実行 (要 macOS / Xcode)
```

## 3D モデル(パーツ)の追加・差し替えについて

`src/assets/models/` 以下(サブフォルダ含む)に置いた `.obj` / `.stl` / `.3mf` ファイルを、`scripts/genManifest.js` が読み取って `src/assets/generated/manifest.ts`(Metro の `require()` は静的パスが必要なので、見つけたファイルぶん `require()` 呼び出しを並べたファイル)を自動生成します。`npm start` は `prestart` フックでこのスクリプトを毎回自動実行するので、ファイルを置いて `npm start` し直すだけで反映されます。手動で生成したいときは:

```bash
npm run gen-manifest
```

このスクリプトは **読み取るだけで、何かを削除したり外部(Unity側)から探しにいったりはしません**。

```
src/assets/models/
├── body.obj              固定 (本体)
├── body_face.obj         固定 (本体・顔あり)
├── body_v2_layout.json   固定 (取り付け口レイアウト)
├── ear/
│   └── ear.stl
├── tail/
│   ├── tail.stl
│   └── tail2.stl
├── hand/
│   ├── hand.3mf
│   └── hand_light.3mf
└── legs/
    └── legs.3mf
```

- 本体 (`body.obj` / `body_face.obj` / `body_v2_layout.json`) は `models/` 直下の固定ファイル名で扱われます。
- パーツは種別ごとのフォルダ(`ear/` `tail/` `hand/` `legs/`)の下に置きます。**フォルダ名自体は読み取っておらず**、置き場所の整理用です。実際にどのパーツとして読み込まれるかは、拡張子を除いたファイル名だけで決まります(例: `tail/tail2.stl` → `tail2`)。

**既存の種類のパーツを追加・差し替えたいだけなら**、対応するファイル名で該当フォルダに置くだけで完了します。現在使われている名前は `ear` / `tail` / `tail2` / `hand` / `hand_light` / `legs`(`src/data/modules.ts` の `ModuleCatalog.modelKey()` を参照)。同じ名前で拡張子違いのファイルが複数あると `.stl` → `.obj` → `.3mf` の優先順位で1つだけ採用され、警告が出ます。

**まったく新しい種類のパーツ**(今までの6種類にない部位)を追加する場合は、ファイルを置くだけでなく以下の登録も必要です(モデルファイルだけでは部位の名前・アイコン・取り付けルールまでは決められないため):

- `src/data/modules.ts`: `ModuleKind` 型に追加、`ModuleCatalog` の `name()` / `icon()` / `tint()` / `fits()` / `modelKey()` / `axis()` に分岐を追加
- `src/three/rig.ts`: `buildModuleShape()` に、そのモデルをどう組み立てて動かすかの分岐を追加

## 実機での開発ビルドが必要なケース

このアプリは Expo Go で起動できますが、以下の機能は Expo Go に含まれないネイティブコードが必要なため、将来的にカスタム開発ビルド([EAS Build](https://docs.expo.dev/develop/development-builds/introduction/))が必要になります。

- `@shopify/react-native-skia`(現状は未使用。将来のアイコン/マスコット描画の高度化で使用予定)
- カメラを使った本格的な AR 表示(未実装、将来実装予定)

```bash
npx eas-cli@latest build --platform ios --profile development
npx eas-cli@latest build --platform android --profile development
```

iOS 実機向けビルドには Apple Developer Program(年間登録)への加入が必要です。
