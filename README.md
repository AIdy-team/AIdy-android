# AIdy-android

AIdy（ペットロボット）のスマホアプリ。Unity で作っていて、Android / iOS / Windows（確認用）に書き出せる。

- 本体の 3D モデルを見る・なでる（ホーム）
- 会話ログから日記を作る・予定を入れる（日記 / カレンダー）
- モジュール（耳・尻尾・足・手…）の付け替えと、AR でテーブルや床に置いて見る（AR）

---

## 1. 必要なもの

| もの | 内容 |
|---|---|
| Unity | **2022.3.62f1**（Unity Hub から） |
| Unity のモジュール | **Android Build Support**（OpenJDK / Android SDK & NDK Tools 込み）、**iOS Build Support** |
| Android 実機 | Android 7.0（API 24）以上・ARM64。AR を使うなら ARCore 対応機種 |
| iPhone 実機 | iOS 12 以上。ビルドには **Mac + Xcode** が必要 |
| Windows で iPhone を使う場合 | iTunes（Apple Mobile Device Support）※Unity Remote 5 用 |

Unity Hub の「インストール → 歯車 → モジュールを加える」で Android / iOS Build Support を追加する。

## 2. 初回セットアップ

1. Unity Hub からこのフォルダ（`AIdy-android`）を開く
2. メニュー **AIDY → 1. プロジェクト設定 + AIDYシーン生成** を実行
   - アプリ名・向き（縦固定）・色空間（Gamma）・Android の設定・ARCore・シーン `Assets/Scenes/AIDY.unity` をまとめて設定する
3. `Modules/` に置いたモジュールの 3D モデルは、ビルド時に自動で `Assets/Resources/AIDY/*_stl.bytes` などに取り込まれる

---

## 3. Android ビルド

### 3-1. APK を作る
- メニュー **AIDY → 2. Android APK をビルド**
  - ビルド先が Android でなければ自動で切り替わる（初回は数分かかる）
  - 出力: `Builds/Android/AIDY.apk`
- バッチモード（エディタを開かずに）:
  ```
  Unity.exe -batchmode -quit -projectPath <このフォルダ> -executeMethod Aidy.EditorTools.AidyBuild.BatchBuildAndroid -logFile build.log
  ```

### 3-2. 実機に入れる
1. スマホ: 設定 → デバイス情報 →「ビルド番号」を 7 回タップ → 開発者向けオプション →「USB デバッグ」をオン
2. USB でつなぎ、「USB デバッグを許可」で許可
3. PC で:
   ```
   "C:\Program Files\Unity\Hub\Editor\2022.3.62f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe" install -r Builds\Android\AIDY.apk
   ```
   （APK をドライブ等でスマホに送って直接インストールしてもよい。その場合は「提供元不明のアプリ」を許可）

### 3-3. Android の設定（`AidyBuild.ConfigurePlayer` で自動設定）
| 項目 | 値 |
|---|---|
| パッケージ名 | `com.aidy.petrobot` |
| 最小 / ターゲット API | 24 / 自動（最新） |
| スクリプト / CPU | IL2CPP / ARM64 |
| グラフィックス | OpenGL ES3（ARCore の要件） |
| ARCore | **任意**（非対応端末でもインストールでき、その場合はカメラ映像モード） |
| 通信 | 平文 HTTP 許可（本体 `http://aidy.local` から会話ログを取るため） |

---

## 4. iOS ビルド

iOS アプリは **Windows だけでは作れない**。Unity で Xcode プロジェクトを書き出し、Mac の Xcode でビルド・署名する。

### 4-1. 手順
1. File → Build Settings → **iOS** → Switch Platform
2. Player Settings（iOS）で次を設定
   - **Bundle Identifier**（例: `com.aidy.petrobot`）
   - **Camera Usage Description**（例:「AR でAidyを表示するためにカメラを使います」）
     ※空のままだと AR 画面でカメラを開いた瞬間にアプリが落ちる
   - Target minimum iOS Version: 12.0 以上
3. Build → 出力フォルダ（例: `Builds/iOS`）を選ぶ → Xcode プロジェクトができる
4. そのフォルダを Mac にコピーし、`Unity-iPhone.xcodeproj` を Xcode で開く
5. Signing & Capabilities で Apple ID（Team）を選ぶ → iPhone をつないで ▶ Run
   - 無料の Apple ID でも自分の iPhone には入れられる（7 日で期限切れ）
   - 初回は iPhone の 設定 → 一般 → VPN とデバイス管理 で開発元を信頼する

### 4-2. iOS の現状
- **ARKit は未導入**（XR は Android の ARCore のみ設定）。そのため iPhone の AR は
  「カメラ映像 + マーカー認識」「ジャイロで傾きを追う机置き」で動き、平面認識はしない
- 平面認識まで使うなら: Package Manager で `com.unity.xr.arkit`（AR Foundation と同じ 5.1.5）を追加し、
  Project Settings → XR Plug-in Management → iOS で ARKit にチェック

### 4-3. Mac なしで iPhone で確認する（Unity Remote 5）
エディタの Play を iPhone に映して、タッチ・カメラ・ジャイロを試せる（アプリは入らない）。

1. iPhone に App Store の **Unity Remote 5** を入れる。PC には iTunes
2. File → Build Settings で **iOS** に Switch Platform（Windows / Android のままだとつながらない）
3. Edit → Project Settings → Editor → Unity Remote → **Device: Any iOS Device**
   （メニュー **AIDY → Unity Remote 5 (iPhone) を設定** でも Game ビューの縦画面などをまとめて設定できるが、Device は手で確認する）
4. iPhone を USB でつなぎ「このコンピュータを信頼」→ Unity Remote 5 を開いたまま → Unity で **Play**
   - Console に `Unity Remote connected to input!` が出れば接続 OK
   - つながらなければ Play を止め、Unity Remote 5 を開き直してから Play
5. Game ビューのサイズは **Remote (1170x2532)** などの縦長にする

Unity Remote の制限:
- **スマホのキーボードは出ない** → 入力欄をタップしたら PC のキーボードで打つ（実機アプリでは普通に出る）
- 映像は PC 経由なので遅れる。ARKit / ARCore は使えない（カメラ映像モードで動く）
- iPhone のカメラ映像は反転補正しない（Remote では反転済みで届くため。`AidyARView.RemoteCamera`）

---

## 5. Windows プレビューと検証用コマンド

- メニュー **AIDY → Windows プレビューをビルド** → `Builds/Windows/AIDY.exe`（412×892 の縦長ウィンドウ）
- 検証用の起動オプション（スクリーンショットを撮って自動終了）:

| オプション | 内容 |
|---|---|
| `-aidyShots <出力先>` | ホーム・日記・リモコン・設定・AR(画面上) の各画面 |
| `-aidyMarkerTest <画像> <出力先>` | 静止画をカメラ映像にしてマーカーの上のモデルを撮る |
| `-aidyMarkerSheet <出力先>` | 着せ替えマーカーの作成 → 保存 → 読み取り |
| `-aidyARPlacement <出力先>` | 表示方法の選択 → マーカーなしで机に置く → マーカー待ち |

例: `AIDY.exe -aidyShots shots -screen-width 412 -screen-height 892`

---

## 6. アプリの仕様

### 6-1. 画面
左右スワイプ（または下のタブ）でページを切り替える。

| ページ | 内容 |
|---|---|
| ホーム | 本体の 3D（全画面）。タップでなでなで、ドラッグで回転、ピンチでズーム。左上に状態と充電残量、下にいまのモジュールとお散歩スイッチ |
| 日記 | カレンダー、会話ログから作った日記、日ごとの予定（追加・編集・削除）。本体から Wi-Fi で会話ログを同期 |
| リモコン | 家電・拡張モジュール |
| AR | 「画面上」= 3D で面をタップしてモジュールを付け替え / 「AR」= カメラで机や床に表示 |
| 設定 | 右上の歯車から |

### 6-2. モジュール
- 種類: 耳 / 尻尾 / 尻尾2 / 足 / 顔（モニター）/ 手 / ライト手
- 3D モデルは **`.obj` と `.stl`** で `Modules/` に置く（ビルド時に Resources へ取り込み）。読み込み順は `_stl` → `_obj` → `_3mf`
- 付け替え画面の下のモジュール欄は丸いプレビューのカルーセル。左右スワイプで送り、ドラッグで 3D の面へ付ける
- 構成は端末に保存され、ホーム・付け替え・AR の全モデルで共通
- 本体の寸法は `docs/本体モデルv1解析.txt` を正とする

### 6-3. AR
AR を開くと、まず表示方法を選ぶ（選ぶまでモデルは出さない）。下の「表示方法」でいつでも選び直せる。

| 表示方法 | Android（ARCore 対応） | iPhone / Remote / ARCore 非対応 |
|---|---|---|
| マーカーで表示 | マーカーの上に立つ。位置はワールドに固定され、マーカーが外れても残る | マーカーが写っている間だけ表示 |
| マーカーなしで置く | ARCore が見つけた平面（青い面）をタップして置く | ジャイロで傾きを追い、カメラの 35cm 下の水平面とみなして置く（中央に置き場所の輪） |

- 表示サイズ: 一辺 **75mm**（本体 150mm の 1/2。`AidyARView.ARModelSize`）
- 「カメラ切替」で使うカメラを順に切り替え（Remote の iPhone カメラ ⇔ PC の Web カメラなど）

### 6-4. ArUco マーカー
- 辞書: **DICT_4X4_50**（OpenCV と同じ）。検出は C# の自作（`AidyAruco.cs`、OpenCV 不要）

| ID | 名前 | 印刷サイズ（黒い四角） | 出るモデル |
|---|---|---|---|
| 0 | あなたのAidy | 80mm | いまの着せ替え（付け替えると変わる） |
| 1 | 名刺のAidy | 30mm | 標準構成（名刺などで配布） |
| 2〜49 | 着せ替えAidy #n | 80mm | 作ったときの着せ替え（この端末だけに保存） |

- 印刷用画像: `../docs/markers/`（ID0 / ID1）
- アプリ内の作成: 付け替え画面のモジュール欄「**マーカー作成**」→「いまの着せ替えで作成」→「画像を保存」
  - 保存先: Android はギャラリー（Pictures/AIDY）、エディタはプロジェクトの `Markers/`
  - 300dpi の PNG。黒い四角が上の表のサイズになるよう等倍で印刷する

### 6-5. 日記・本体との通信
- 本体の API: `GET http://aidy.local/api/logs?date=yyyy-MM-dd`（詳しくは `../docs/会話ログ取得.md`）
- 本体と同じ Wi-Fi につないで「Aidyから会話ログを受け取る」

---

## 7. 開発メモ

- **文字（フォント）**: 日本語は起動時にアトラスへ登録している。画面に新しい文言を足したら `AidyUI.cs` の `UI.Preload` にも追加する（足さないと文字が出ない場合がある）
- **UI**: シーンには `AIDY App` だけを置き、画面はすべてコード（`AidyApp.cs` ほか）で作っている
- **ロゴ**: `Assets/Resources/AIDY/aidy_logo_dark.png`（黒文字・白背景用、ヘッダーで使用）/ `aidy_logo_light.png`（白文字・暗い背景用）。元画像はプロジェクト直下の `Aidy_logo-1.png` / `Aidy_logo-2.png`

### 主なスクリプト（`Assets/Scripts/AIDY/`）
| ファイル | 役割 |
|---|---|
| `AidyApp.cs` | 画面全体の構築・ページ・ヘッダー・検証用オプション |
| `AidyHomeRoom.cs` | ホーム / 付け替えの 3D ルーム（なでなで・回転・面の選択） |
| `AidyModules.cs` / `AidyRig.cs` | モジュールの種類・スロット・モデル読み込み・組み立て |
| `AidyModuleTray.cs` / `AidyModulePreview.cs` | モジュール欄のカルーセルと丸いプレビュー |
| `AidyARView.cs` | AR 画面（ARCore / カメラ映像・表示方法・マーカー・机置き） |
| `AidyAruco.cs` / `AidyMarkerAR.cs` | ArUco の検出・姿勢推定 / マーカーの ID・保存・画像生成 |
| `AidyMarkerSheet.cs` | 着せ替えマーカー作成シート |
| `AidyDiary.cs` / `AidyDiaryPage.cs` | 日記・予定のデータと日記ページ（カレンダー） |
| `AidyUI.cs` | UI 部品・テーマ色・フォント |
| `Assets/Editor/AidyBuild.cs` | プロジェクト設定・ビルド（メニュー AIDY） |
| `Assets/Editor/AidyUnityRemote.cs` | Unity Remote 5 の設定メニュー |
