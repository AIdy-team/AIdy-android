# Aidy アプリ (Kotlin 版)

ペットロボット **Aidy** のスマホアプリを、Unity 版 (`AIdy-android/` 直下) から Kotlin + Jetpack Compose に書き直した Android アプリです。
3D 表示は SceneView (Filament)、AR は ARCore と ArUco マーカーを使います。

- アプリ ID: `com.aidy.petrobot.kotlin` (Unity 版 `com.aidy.petrobot` とは別アプリとして同じ端末に入る)
- バージョン: 0.1.0 (versionCode 1)

---

## 1. 実行環境

| もの | 内容 |
|---|---|
| 端末 | Android 7.0 (API 24) 以上、OpenGL ES 3.0 対応。ABI は arm64-v8a / x86_64 (エミュレータ) |
| AR | ARCore 対応機種なら ARCore、非対応ならカメラ映像 + ArUco マーカーで動く (ARCore は任意) |
| 確認した端末 | Pixel 7a (実機)、Pixel 8 API 36 エミュレータ |
| 画面 | 縦固定、リフレッシュレートは 60Hz に制限 (発熱対策) |
| 権限 | カメラ (AR・QR 読み取り)、インターネット (本体との通信、`http://` の平文通信を許可)、Android 9 以下のみストレージ書き込み (マーカー画像の保存) |

### 開発環境

| もの | バージョン |
|---|---|
| Android Studio | 同梱の JDK (jbr) を使う。JDK 17 以上 |
| Gradle | 8.11.1 (wrapper) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (Compose コンパイラプラグイン) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |
| Compose BOM | 2024.12.01 (Material3 を使用) |
| 主なライブラリ | SceneView / ARSceneView 2.3.0、CameraX、ML Kit バーコード (QR)、desugar_jdk_libs (java.time) |
| Android SDK | `kotlin/local.properties` の `sdk.dir` (例: `C:\Users\<ユーザー>\AppData\Local\Android\Sdk`) |

---

## 2. 起動方法

### Android Studio (おすすめ)

1. Android Studio の「Open」で `AIdy-android/kotlin` を開く
2. Gradle の同期が終わるのを待つ
3. 上部の端末選択で USB 接続の実機かエミュレータを選ぶ
4. ▶ (Run) を押す

### コマンドライン (Git Bash)

```bash
cd /c/AIdy/AIdy-android/kotlin && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

APK: `kotlin/app/build/outputs/apk/debug/app-debug.apk`

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" shell am start -n com.aidy.petrobot.kotlin/com.aidy.petrobot.MainActivity
```

端末が複数つながっているときは `adb -s <シリアル>` で選ぶ (`adb devices` で確認)。
署名が違う古いビルドが入っていると `INSTALL_FAILED_UPDATE_INCOMPATIBLE` になるので、そのときは一度アンインストールする (保存データは消える)。

### ビルド時のモデルの取り込み

`app/build.gradle.kts` の `syncAidyAssets` タスクが、ビルドのたびに Unity 側のファイルを `build/generated/aidyAssets` にコピーして APK に入れます。元のファイルを差し替えれば次のビルドで反映されます。

| 取り込み元 | APK 内 | 内容 |
|---|---|---|
| `Assets/Resources/AIDY/本体v2_モデル.obj` ・ `本体v2_顔あり.obj` | `models/body.obj` ・ `body_face.obj` | 本体 (顔なし / 顔あり) |
| `Assets/Resources/AIDY/body_v2_layout.json` | `models/body_v2_layout.json` | 取り付け口 13 本 + 顔モニターの位置 |
| `Modules/<名前>.stl / .obj / .3mf` | `models/<ID>.<拡張子>` | モジュール (stl → obj → 3mf の順に最初の 1 つ) |
| `RoomAssets/kenney_furniture/*.obj + .mtl` | `room/` | ホームの部屋の家具 |
| `Assets/Resources/AIDY/aidy_logo_*.png` | ロゴ | ヘッダー・起動画面 |

モジュールを足すときは、`Modules/` に `.obj` + `.stl` を置き、`build.gradle.kts` の `moduleModels`、`data/Modules.kt` (種類・名前・アイコン・付けられる面・モデルのパス・向き)、`three/Rig.kt` の `ModuleShape.build` に追加します。

### 初回起動の時間

起動するとロゴのアニメーションが出て、3D モデル以外の準備ができたら画面が使えるようになります。3D モデルは裏で読み込み、終わるまで各ページにくるくるが出ます。
解析したモデルは `cacheDir/meshes` に保存するので、2 回目以降は速くなります。アプリを更新した直後の初回は作り直すため、実機で 25〜30 秒ほどかかります。

---

## 3. 画面と機能

ページは横スワイプ (3D 画面では本体以外の場所を速く横にスワイプ) と下のタブで移動します。ページ: ホーム / 日記 / リモコン / AR / 設定 (右上の歯車)。

### ホーム

- 家具のある部屋 (Kenney Furniture Kit) に 3D の Aidy がいて、お散歩 (足があるとき)・きょろきょろ・居眠りなどを自分でする
- 本体をタップでなでる、ドラッグで回す (回しすぎると目を回す)、ピンチ / ＋− ボタンでズーム
- 床をタップすると真ん中に戻ってきて「ただいま！」、しばらく真ん中にいる (お散歩オフでも戻る)
- 右上の「色」: 本体とパーツの色を変える
  - 対象を「本体 / パーツ全部 / 付いているパーツ (取り付け口ごと)」から選び、彩度・明るさの四角と色相のバーで色を作る
  - パターン (本体 + パーツの色の組み合わせ) をプリセットとして保存・呼び出し。用意したパターン 10 種 + 保存したもの (最大 12、長押しで削除)
- 下のカード: いまのモジュール、お散歩のオン・オフ、付け替えへ

### 日記

- 月 / 週カレンダー、日記のある日にミニ Aidy の印
- 本体と同期して会話ログから日記を作る、今週の予定 (本体から読むだけ)、予定の追加・編集・削除 (端末内)

### リモコン

- **Aidyの声**: 音量 (0〜100%) と話す速さ (×0.5〜×1.5、1.0 がふつう) のスライダー。ページを開くと本体から読み込み、スライダーを離すと本体へ送る。本体とつながっていなくても端末に覚えておく
- **辞書**: よくある質問・聞き間違い・読みの一覧・追加・削除 (本体の辞書を直接編集)
- 家電・拡張モジュールのカード (見た目だけ、本体側の口はまだない)

### AR

- **画面上**: モジュールの付け替え
  - 本体の面をタップして選ぶ → 下のトレイからモジュールをドラッグして取り付け
  - 取り付け済みのモジュールは、選んだ面のものだけつかんで動かす・外す (トレイか本体の外に落とすと外れる)。面を選択中はほかの面のパーツは反応しない
  - 面を選んでいないときのドラッグは本体の回転
- **AR (カメラ)**: 印刷したマーカーの上に Aidy を出す
  - ID0 = いまの着せ替え・いまの色、ID1 = 名刺用 (標準構成・グレー)、ID2〜 = アプリで作った着せ替えマーカー (構成と色のパターンを端末に保存)
  - 「マーカー・色」からマーカーの作成・色の変更・画像の保存 (300dpi の PNG。黒い四角が ID0〜: 80mm、ID1: 30mm で印刷)
  - ARCore 非対応機種はカメラ映像 + マーカー検出で表示

### 設定

- 日記のカレンダー表示 (週表示)
- Aidy 本体と接続: アドレス (IP:ポート) と合言葉を QR 読み取りか手入力で設定し、接続を確認
- 今後の開発予定 (本体側の口ができたら実装するもの)

---

## 4. モジュール

| 種類 | 名前 | 付けられる場所 | モデル |
|---|---|---|---|
| Ear | 耳 | 上面・左面・右面 | `Modules/mimi2` |
| Tail45 | 尻尾45 | 背面・上面 | `Modules/尻尾_45` |
| TailCurl | 巻き尻尾 | 背面・上面 | `Modules/尻尾_巻き2` |
| Hat | シルクハット | 上面の真ん中 (top_2) | `Modules/シルクハット` |
| Tv | ブラウン管テレビ | 上面の真ん中 (top_2) | `Modules/ブラウン管テレビ` |
| Legs | 足 | 底面 | `Modules/足v1` |
| Hand | 手 | 左面・右面 | `Modules/手_短い` |
| HandLight | ライト手 | 左面・右面 | `Modules/手_短い_懐中電灯` |
| Face | 顔 | 顔モニター開口 | (本体の顔ありモデルに切り替え) |

本体の寸法は `docs/本体モデルv1解析.txt` (外形 150mm 角) を基準にしています。

---

## 5. 本体との通信

Wi-Fi で本体の `http://<アドレス>` に HTTP で話します。すべてのリクエストにヘッダー `X-Aidy-Token: <合言葉>` を付けます (401・403 は「合言葉が違います」)。タイムアウト 4 秒。実装は `data/Diary.kt` の `AidyDeviceLink`。

| 口 | 使うところ |
|---|---|
| `GET /api/ping` | 設定の「保存して接続を試す」 |
| `GET /api/logs?date=yyyy-MM-dd` | 日記の同期 (会話ログ) |
| `GET /api/days` | 会話がある日の一覧 (カレンダーの印) |
| `GET /api/week` | 今週の予定・やること (読むだけ) |
| `GET /api/dict` ・ `POST /api/add` ・ `POST /api/del` | リモコンの辞書 (kind = `faq` / `asr` / `yomi`) |
| `GET /api/settings` ・ `POST /api/settings` | リモコンの音量・話す速さ (**仮の形**、下記) |

### /api/settings (仮の形)

本体側の仕様書 (AIdy_アプリ連携_仕様.md の「段階1」) の正式な形がこのリポジトリに無いため、次の形で実装しています。本体側が決まったら `AidyDeviceLink.parseSettings` / `saveSettings` を合わせてください。

```
GET  /api/settings          → {"ok": true, "settings": {"volume": 60, "speed": 1.0}}
POST /api/settings          ← {"volume": 60, "speed": 1.0}
                            → {"ok": true, "settings": {...}}   (settings が無ければ送った値を使う)
```

- `volume`: 0〜100 (%)、`speed`: 話す速さの倍率 (1.0 = ふつう)
- 読むときは `settings` の中でも直下でも、`speed` の代わりに `rate` でも受け付ける
- 404 のときは「本体がまだ音量・速さの設定に対応していません」と出す

まだ本体に口が無い機能 (本体のようす・予定の書き込み・文字で話させる・スマホのマイク) は、設定の「今後の開発予定」に載せています。

---

## 6. 端末に保存するデータ

SharedPreferences (`aidy`) に保存します。アプリを消すと消えます。Unity 版とは共有しません。

| キー | 内容 |
|---|---|
| `aidy.modules.v1` | いまの着せ替え (取り付け口 → モジュール) |
| `aidy.color.pattern` ・ `aidy.color.patternPresets` | いまの色のパターン・保存したパターン (形式 `#本体,*#パーツ,top_1#色`) |
| `aidy.markers.v1` | 作ったマーカー (`2=top_1:Ear,...|#色のパターン;3=...`) |
| `aidy.device.host` ・ `aidy.device.token` | 本体のアドレス・合言葉 |
| `aidy.voice.*` | 最後に知っている音量・話す速さ |
| `aidy.calendar.weekView` ほか | 日記・予定・同期の状態 |

---

## 7. ソースの構成

`app/src/main/java/com/aidy/petrobot/`

| フォルダ | 主なファイル |
|---|---|
| (直下) | `MainActivity.kt` (起動画面・60Hz)、`AidyApplication.kt` |
| `ui/` | `AidyApp.kt` (ページ・タブ・スワイプ)、`Pages.kt` (ホーム・リモコン・設定・AR)、`View3D.kt` (3D の操作)、`ModuleTray.kt` (付け替えトレイ)、`ColorPicker.kt` (色のパターン)、`RemoteVoice.kt` (音量・話す速さ)、`DictManager.kt` (辞書)、`DiaryPage.kt`、`MarkerSheet.kt`、`Loading.kt`、`Widgets.kt` ほか |
| `three/` | `HomeRoom.kt` (3D の部屋・Aidy の動き・当たり判定)、`RoomDecor.kt` (家具)、`Rig.kt` (本体 + モジュールの組み立て)、`ModelLoader.kt` (obj / stl / 3mf の読み込み・キャッシュ)、`Gfx.kt`、`Camera.kt`、`Math.kt` |
| `ar/` | `ARView.kt` (ARCore / カメラ映像)、`Aruco.kt` (マーカー検出)、`MarkerTracker.kt` |
| `data/` | `Modules.kt` (モジュール・取り付け口・着せ替え)、`BodyColor.kt` (色のパターン)、`Markers.kt`、`Diary.kt` (日記・予定・本体との通信)、`Prefs.kt` |

---

## 8. ライセンス (素材)

- ホームの家具: [Kenney Furniture Kit](https://kenney.nl/assets/furniture-kit) — CC0 (クレジット不要)。`RoomAssets/kenney_furniture/License.txt`
