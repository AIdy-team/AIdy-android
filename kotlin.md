Kotlin版 Aidy の進捗と使い方
進捗

Unity版の全機能を kotlin/ フォルダ（Android Studio プロジェクト）に Kotlin で書き直しました。ビルドは成功し、Pixel 8 エミュレータで起動を確認しています。まだコミットしていません。

エミュレータで動作を確認したもの
画面	内容
ホーム	3D の Aidy（耳・尻尾・足・顔モニター）、自動で歩く・寝る動き、状態の吹き出し、電池表示、ズームボタン、モジュールのまとめカード
日記	月カレンダー、日記がある日のミニ Aidy、空の日のカード、同期ボタン
設定	すべてのカードとスイッチ
AR タブ（画面上）	[画面上 / AR] の切り替え、面タップの案内、モジュールトレイ（回転するカルーセル）
画面移動	タブ・ギアアイコンでの移動（落ちない）
見つけて直した不具合
ページを移動するとアプリが落ちていました。横スワイプのページ（HorizontalPager）が 3D 表示の画面を使い回すときに、すでに解放した 3D の部品を使っていたのが原因です。
5ページとも常に読み込んだままにして解決しました。AR カメラは AR ページが見えている間だけ動くようにしています。
まだ確認できていないこと
AR カメラモード：ARCore での平面配置、ARCore がない端末向けのカメラ映像モード、ArUco マーカー認識、ジャイロ。カメラ付きの実機での確認が必要です。
手で操作していない機能：モジュールのドラッグ＆ドロップでの付け外し、予定の追加・編集・削除、マーカー作成シート。
起動に約40秒（エミュレータ）：モデルの読み込みを UI スレッドで行っているためと考えています。バックグラウンドに移せば大きく短くなる見込みです。
見た目の調整：ライトの強さ・色合いや、Unity から変換した数値（ホームのカメラの上下のずらし方など）は、Unity版と並べて見比べる必要があります。
使い方
前提
Android Studio がインストール済み（同梱の Java jbr を使います）
Android SDK が C:\Users\mtmas\AppData\Local\Android\Sdk にある（kotlin/local.properties に設定済み）
ビルド時に、モデル・ロゴを Unity 側のフォルダ（本体の obj、body_v2_layout.json、Modules/ のモジュールモデル）から自動でコピーします。Unity 側のファイルを更新すれば、次のビルドで反映されます。
方法1: Android Studio で開く（おすすめ）
Android Studio で「Open」から C:\AIdy\AIdy-android\kotlin を開く
Gradle の同期が終わるのを待つ
上部の端末選択でエミュレータ（Pixel_8_API_36 など）か USB 接続の実機を選ぶ
▶（Run）を押す
方法2: コマンドラインでビルド
cd /c/AIdy/AIdy-android/kotlin && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug

APK の出力先：kotlin/app/build/outputs/apk/debug/app-debug.apk

端末へのインストール：

"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r /c/AIdy/AIdy-android/kotlin/app/build/outputs/apk/debug/app-debug.apk

起動：

"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" shell am start -n com.aidy.petrobot.kotlin/com.aidy.petrobot.MainActivity
Unity版との見比べ方
Kotlin版のアプリIDは com.aidy.petrobot.kotlin で、Unity版とは別アプリとして入ります。同じ端末に両方入れて、交互に開いて比べられます。
保存データ（着せ替え・日記・予定・マーカー）はアプリごとに別なので、片方の変更はもう片方に反映されません。
アプリの起動直後はスプラッシュ画面のまま数十秒待つことがあります（上記の起動の遅さ）。
ソースの場所

kotlin/app/src/main/java/com/aidy/petrobot/ の下：

フォルダ	内容	主なファイル
ui/	画面	AidyApp.kt（全体の枠）、Pages.kt（ホーム・リモコン・設定・AR）、DiaryPage.kt、ModuleTray.kt、MarkerSheet.kt
three/	3D	3D の部屋、モジュールの組み立て、モデルの読み込み
ar/	AR	ARView.kt（ARCore／カメラ映像）、MarkerTracker.kt、Aruco.kt
data/	データ	着せ替え・日記・予定・マーカーの保存