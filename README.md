# AIR CONTROL

Androidのインカメで手を追跡し、画面に触れずにTikTokなどを操作する個人用アプリです。

## v0.1 features

- MediaPipe Gesture Recognizer + CameraX（前面カメラ）
- 手のひらを上下に振る: 次/前の動画
- ピンチ: 画面中央タップ（再生/一時停止）
- 親指を立ててホールド: 画面中央ダブルタップ（TikTokのいいね）
- グーをホールド: 操作ロック/解除
- AccessibilityServiceでタップ/スワイプを実行
- カメラForeground Serviceで他アプリ表示中も追跡
- GitHub Releasesからアプリ内アップデート
- ログ書き出し。空ログでも診断ヘッダを書き、0Bファイルを作らない

## Setup

1. APKをインストールしてAIR CONTROLを起動
2. カメラ権限を許可
3. 「ユーザー補助設定」を開き、AIR CONTROLを有効化
4. 「START AIR CONTROL」を押す
5. TikTokを開く

Android 14以降ではカメラForeground Serviceをアプリが画面に出ている間に開始する必要があります。このアプリはSTARTボタンから開始する設計です。

## Gesture defaults

| Gesture | Action |
| --- | --- |
| Open Palm + swipe up | Swipe up |
| Open Palm + swipe down | Swipe down |
| Pinch | Center tap |
| Thumb Up 500ms | Center double tap |
| Closed Fist 900ms | Lock / unlock |

誤操作防止のため、スワイプには移動量・速度・クールダウンを設定しています。

## Build

MediaPipeの`gesture_recognizer.task`はビルド前にGoogleの公式モデル配布先から自動取得します。

GitHub ActionsのBuild workflowはDebug APKをArtifactとして出力します。Release workflowは手動実行でバージョン番号を指定すると、同一署名のRelease APKを作成してGitHub Releaseへ添付します。

> **Signing note:** 個人利用を優先し、更新互換性のため固定のテスト署名鍵をリポジトリにbase64で含めています。公開配布・Play Store・第三者配布には使用しないでください。公開鍵同然の扱いです。

## Update

アプリの「アップデートを確認」は`IKEGAMI-99/AIR-CONTROL`の最新GitHub Releaseを確認し、現在の`versionName`より新しければAPKをダウンロードしてAndroidのパッケージインストーラを開きます。

初回だけ「不明なアプリのインストール」をAIR CONTROLに許可する必要があります。

## Log export

内部ログは`files/aircontrol.log`へ追記し、各書き込みでflushします。書き出し時はStorage Access Frameworkを使い、コピー後にflush/closeします。内部ログが空でも診断情報を書き出すため0Bにはなりません。
