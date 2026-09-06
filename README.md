# AIR CONTROL

Androidのインカメで手を追跡し、画面に触れずにTikTokなどを操作する個人用アプリです。

## v0.1.1 additions

- 「アクセスを拒否されました」用の制限付き設定解除ガイドをアプリ内に追加
- AIR CONTROLのアプリ情報へ直接移動するボタンを追加
- カメラ映像の上にMediaPipeの21点ランドマークと手の骨格線を重ねる動作テスト画面を追加
- テスト画面で認識ジェスチャー名、検出手数、信頼度をリアルタイム表示
- 動作テスト中は通常のAIR CONTROLカメラサービスを一時停止してカメラ競合を防止

## Features

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

### 「アプリはアクセスを拒否されました」と表示される場合

GitHubなどPlayストア以外からインストールしたAPKでは、Android 13以降の「制限付き設定」によってAccessibilityServiceがブロックされることがあります。

AIR CONTROLの「アクセス拒否された場合 / 制限付き設定を解除」を押して次の手順を実行してください。

1. 「アプリ情報を開く」
2. AIR CONTROLのアプリ情報で右上の`︙`を開く
3. 「制限付き設定を許可」を選ぶ
4. PIN・指紋などで承認
5. AIR CONTROLへ戻って「ユーザー補助設定」をもう一度開く
6. AIR CONTROLをONにする

この保護はAndroid側の機能なので、アプリ自身から自動的に解除することはできません。

Android 14以降ではカメラForeground Serviceをアプリが画面に出ている間に開始する必要があります。このアプリはSTARTボタンから開始する設計です。

## Hand tracking test

メイン画面の「手追跡をテスト（カメラ＋ボーン表示）」を開くと、インカメの上にMediaPipeが検出した手を可視化します。

- 緑: 手の骨格線
- 黄: 21点ランドマーク
- 赤: 手首
- HAND: 検出状態と手数
- GESTURE: MediaPipeの認識ジェスチャー
- CONF: 認識信頼度

骨格が手に追従していれば「カメラ → MediaPipe → 手ランドマーク取得」は正常です。骨格表示が正常なのにTikTokが動かない場合は、Accessibility設定側を確認してください。

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

GitHub ActionsのBuild workflowはDebug APKをArtifactとして出力します。Release workflowはバージョン番号を指定して実行すると、同一署名のRelease APKを作成してGitHub Releaseへ添付します。

> **Signing note:** 個人利用を優先し、更新互換性のため固定のテスト署名鍵をリポジトリにbase64で含めています。公開配布・Play Store・第三者配布には使用しないでください。公開鍵同然の扱いです。

## Update

アプリの「アップデートを確認」は`IKEGAMI-99/AIR-CONTROL`の最新GitHub Releaseを確認し、現在の`versionName`より新しければAPKをダウンロードしてAndroidのパッケージインストーラを開きます。

初回だけ「不明なアプリのインストール」をAIR CONTROLに許可する必要があります。

## Log export

内部ログは`files/aircontrol.log`へ追記し、各書き込みでflushします。書き出し時はStorage Access Frameworkを使い、コピー後にflush/closeします。内部ログが空でも診断情報を書き出すため0Bにはなりません。
