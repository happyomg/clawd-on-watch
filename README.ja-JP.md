<p align="center">
  <img src="assets/tray-icon.png" width="128" alt="Clawd">
</p>
<h1 align="center">Clawd on Watch</h1>
<p align="center">
  <strong>デスクトップペット + Wear OS ウォッチコンパニオン</strong><br>
  <sub><a href="https://github.com/rullerzhou-afk/clawd-on-desk">clawd-on-desk</a> からフォーク (<a href="https://github.com/rullerzhou-afk">@rullerzhou-afk</a>)</sub>
</p>
<p align="center">
  <a href="README.md">English</a>
  ·
  <a href="README.zh-CN.md">中文版</a>
  ·
  <a href="README.zh-TW.md">繁體中文</a>
  ·
  <a href="README.ko-KR.md">한국어</a>
  ·
  <a href="README.ja-JP.md">日本語</a>
</p>
<p align="center">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux%20%7C%20Wear%20OS-lightgrey" alt="Platform">
</p>

<p align="center">
  <img src="assets/hero.gif" width="360" alt="Clawd on Desk — AI コーディングエージェントにリアルタイムで反応するピクセルデスクトップペット">
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/watch/watch-working.png" width="160" alt="Clawd on Watch — Connected ステータスと working 状態チップを表示する Wear OS コンパニオン">
</p>

## Clawd on Watch とは？

**Clawd on Watch** は [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) デスクトップペットを **Wear OS スマートウォッチコンパニオン** で拡張したプロジェクトです。ピクセルのカニはデスクトップと手首の両方に住んでいます。AI コーディングエージェントが思考、タイピング、サブエージェントの切り替えを始めると、ウォッチは Bluetooth Low Energy (BLE) を介してリアルタイムに状態を反映します。

デスクを離れても手首をちらっと見るだけで、エージェントがまだ作業中なのか、権限待ちなのか、完了したのかが即座にわかります。エージェントがリスクのあるコマンドを実行しようとすると、ウォッチが振動し、**手首のフリックで承認・拒否** が可能です。ターミナルに急いで戻る必要はありません。

> デスクトップ側はアップストリーム clawd-on-desk のすべての機能を保持しています: 14 種類のアニメーション状態、権限バブル、セッションダッシュボード、カスタムテーマ、マルチディスプレイ対応、**Claude Code**、**Codex CLI**、**Copilot CLI**、**Gemini CLI**、**Cursor Agent** [その他多数](#デスクトップ機能) との連携。

---

## ウォッチコンパニオン

### 仕組み

```
Desktop (Electron)                Watch (Wear OS / Kotlin)
  src/main.js                       watch-app/android/
  src/watch-adapter.js              service/BleService.kt
  src/watch-controller.js           renderer/PetView.kt
       │                            domain/ThemeReceiver.kt
       │ stdio JSON
       ▼
  scripts/watch_buddy_bridge.py     (BLE Central, Python bleak)
       │
       │ GATT over BLE
       ▼
  ┌─────────────────────────────────────────────────────┐
  │  CWD1: State + Theme frames     Desktop → Watch     │
  │  CWD2: Approval Request         Desktop → Watch     │
  │  CWD3: Approval Response        Watch → Desktop     │
  │  CWD4: Meta + themeHash         Watch → Desktop     │
  └─────────────────────────────────────────────────────┘
```

デスクトップは Python サイドカー (`watch_buddy_bridge.py`) を起動し、BLE Central として動作します。ウォッチは BLE Peripheral (GATT Server) として動作します。状態スナップショット、テーマデータ、権限リクエストはカスタム GATT 特性を介してやり取りされます。

### ウォッチ機能

#### リアルタイム状態同期
ウォッチは BLE を介してデスクトップペットの状態をミラーリングし、14 種類のアニメーションを表示します: idle、thinking、typing、building、headphones groove (サブエージェント 1 個)、juggling (2 個以上)、error、happy、notification、sweeping、carrying、sleeping など。画面下部のカラーコードチップが現在の状態を示します。状態が切り替わるたびに短い触覚振動が発生します。

#### BLE 経由のテーマ同期
カスタムテーマはデスクトップからウォッチへ自動的にプッシュされます。SVG アニメーションファイルはチャンク分割されて BLE 経由で転送され、デバイス上で非表示の WebView が CSS アニメーションをフレームごとにキャプチャし、キャッシュされた WebP シーケンスとしてレンダリングします。ウォッチは SHA-256 フィンガープリントで古いテーマを検出し、必要な場合のみ同期を行います。

#### 手首からの権限承認
AI エージェントがツールやコマンドの実行権限をリクエストすると、ウォッチはリスクレベルに応じた触覚パターンで振動します:
- **高リスク**: トリプルパルス、ジェスチャー無効 — ボタン操作のみ
- **中リスク**: ダブルパルス
- **低リスク**: ショートパルス

3 つの入力方法:
1. **ボタン**: Deny / Allow / Always Allow
2. **手首フリック**: フリックで承認、シェイクで拒否 (高リスク時は無効)
3. **タイムアウト**: 画面オフまたはタイマー切れで自動拒否

#### 自動再接続と電力管理
BLE 接続は切断から自動的に復旧し、プログレッシブなリトライ遅延で再接続します。30 秒のウォッチドッグが Central の無音消失を検出します。画面オフ時にはアニメーションとジェスチャー検出を一時停止してバッテリーを節約します。

### ウォッチデモ

<p align="center">
  <img src="assets/watch/watch-working.png" width="200" alt="Connected ステータスでカニがタイピング中 — working 状態のウォッチ">
  <br>
  <sub>Wear OS ウォッチ上の Clawd — エージェント作業中にカニがタイピングする「Connected」状態</sub>
</p>

### セットアップガイド

#### 前提条件

| コンポーネント | 要件 |
|-------------|------|
| ウォッチ | BLE 対応の Wear OS デバイス |
| デスクトップ | macOS (検証済み)、Windows/Linux (実験的) |
| Python | Python 3.8+ と `bleak` ライブラリ |
| Android SDK | compileSdk 34, JDK 17 |

#### 1. デスクトップ: ウォッチモードの有効化

1. Clawd を起動し、**設定** を開きます（右クリックメニューまたはトレイメニュー）
2. **リモート承認** タブに移動します（サイドバーの飛行機アイコン）
3. **Watch** カードを展開します
4. **"Enable"** トグルをオンにします — バックグラウンドで BLE ブリッジサイドカーが起動します
5. **"Scan"** をクリックします — 近くの Wear OS デバイスがリストに表示されます
6. ウォッチをクリックします — デスクトップが自動的に接続します
7. 接続が完了すると、ステータスが **"Connected: \<デバイス名\>"** と表示されます
8. オプション: **"Approval on Watch"** トグルをオンにして、ツール権限リクエストをウォッチに転送します

> ブリッジが `missing_bleak` エラーを報告した場合、エラーヒントの **"Install bleak"** ボタンをクリックすると自動インストールされます。

**リピーターの方へ**: 以前接続したことがある場合、デバイスアドレスは保存されています。有効化後 **"Reconnect"** をクリックするだけでスキャンなしで接続できます。

<details>
<summary>上級者向け: 環境変数オーバーライド（開発/CI 用）</summary>

```bash
export CLAWD_WATCH_ENABLED=1                      # 強制有効化（設定を上書き）
export CLAWD_WATCH_ADDRESS="<BLEアドレス>"           # BLE アドレス (macOS UUID 形式)
export CLAWD_WATCH_NAME_PREFIX="Clawd"             # スキャン名プレフィックス
export CLAWD_WATCH_PYTHON="python3"                # Python 実行ファイル
```

</details>

#### 2. ウォッチ: ビルドとインストール

```bash
cd watch-app/android

# 環境の設定
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=/path/to/jdk17   # JDK 17 が必要 (Gradle 8.2)

# ビルド
./gradlew assembleDebug

# ウォッチにインストール (ADB で接続)
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

#### 3. ペアリングと接続

1. ウォッチアプリを起動します — ペアリングモードに入り、BLE アドバタイジングが開始されます
2. デスクトップアプリを `CLAWD_WATCH_ENABLED=1` で起動します
3. デスクトップが "Clawd" プレフィックスを持つ近くの BLE ペリフェラルをスキャンします
4. 接続が確立されると、ウォッチに "Connected" が表示され、ペットの状態同期が始まります
5. デスクトップにカスタムテーマがある場合、初回接続時に自動的にウォッチへ同期されます

#### 4. 動作確認

```bash
# ウォッチログ
adb logcat -s PetView FrameRecorder ThemeConfig BleService

# 確認するログ:
# BleService: Central connected
# ThemeReceiver: assembled: clawd (8399a0)
# PetView: setState → WORKING
```

---

## デスクトップ機能

> アップストリーム [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) のすべての機能を含んでいます。以下はその概要です。

### エージェント連携

| エージェント | 連携方式 | 権限バブル |
|------------|---------|-----------|
| Claude Code | Command hook + HTTP permission hook | あり |
| Codex CLI | Official hooks + JSONL フォールバック | あり |
| Copilot CLI | Command hook | なし |
| Gemini CLI | Command hook (自動登録) | なし |
| Cursor Agent | IDE hooks (自動登録) | なし |
| Antigravity CLI | Command hook (状態のみ) | なし |
| CodeBuddy | Command hook + HTTP permission hook | あり |
| Kiro CLI | カスタムエージェント設定 | なし |
| Kimi Code CLI | Command hook (TOML 経由) | なし |
| Qwen Code | Command hook + 権限リクエスト | あり |
| opencode | プラグイン連携 | あり |
| Pi | グローバルエクステンション (状態のみ) | なし |
| OpenClaw | プラグイン連携 (状態のみ) | なし |
| Hermes Agent | プラグイン連携 | なし |

### アニメーション

<table>
  <tr>
    <td align="center"><img src="assets/gif/clawd-idle.gif" width="100"><br><sub>Idle</sub></td>
    <td align="center"><img src="assets/gif/clawd-thinking.gif" width="100"><br><sub>Thinking</sub></td>
    <td align="center"><img src="assets/gif/clawd-typing.gif" width="100"><br><sub>Typing</sub></td>
    <td align="center"><img src="assets/gif/clawd-building.gif" width="100"><br><sub>Building</sub></td>
    <td align="center"><img src="assets/gif/clawd-headphones-groove.gif" width="100"><br><sub>1 Subagent</sub></td>
    <td align="center"><img src="assets/gif/clawd-juggling.gif" width="100"><br><sub>2+ Subagents</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/calico-idle.gif" width="80"><br><sub>Calico Idle</sub></td>
    <td align="center"><img src="assets/gif/calico-thinking.gif" width="80"><br><sub>Calico Thinking</sub></td>
    <td align="center"><img src="assets/gif/calico-typing.gif" width="80"><br><sub>Calico Typing</sub></td>
    <td align="center"><img src="assets/gif/calico-building.gif" width="80"><br><sub>Calico Building</sub></td>
    <td align="center"><img src="assets/gif/calico-juggling.gif" width="80"><br><sub>Calico Juggling</sub></td>
    <td align="center"><img src="assets/gif/calico-conducting.gif" width="80"><br><sub>Calico Conducting</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/cloudling-idle.gif" width="120"><br><sub>Cloudling Idle</sub></td>
    <td align="center"><img src="assets/gif/cloudling-thinking.gif" width="120"><br><sub>Cloudling Thinking</sub></td>
    <td align="center"><img src="assets/gif/cloudling-typing.gif" width="120"><br><sub>Cloudling Typing</sub></td>
    <td align="center"><img src="assets/gif/cloudling-building.gif" width="120"><br><sub>Cloudling Building</sub></td>
    <td align="center"><img src="assets/gif/cloudling-juggling.gif" width="120"><br><sub>Cloudling Juggling</sub></td>
    <td align="center"><img src="assets/gif/cloudling-conducting.gif" width="120"><br><sub>Cloudling Conducting</sub></td>
  </tr>
</table>

### その他のデスクトップ機能

- **権限バブル** — Allow / Deny / Always Allow のフローティングカードによるアプリ内権限レビュー、グローバルホットキー (`Ctrl+Shift+Y` / `Ctrl+Shift+N`) 対応
- **セッションダッシュボード + HUD** — ライブセッション、最近のイベントの確認とターミナルへのジャンプ
- **カスタムテーマ** — SVG/GIF/APNG アセットで独自キャラクターを作成、または Codex Pet zip パッケージをインポート
- **マルチディスプレイ** — 比例サイズ調整、縦長モニターブースト、ディスプレイ間ドラッグ
- **Mini モード** — 画面端に隠れ、ホバーで顔を出す
- **視線追従** — idle 状態で Clawd がカーソルを追跡
- **多言語対応** — English、簡体中文、繁体中文、Korean、Japanese
- **自動更新** — GitHub Releases で新バージョンを確認

詳細ドキュメント: **[docs/guides/state-mapping.md](docs/guides/state-mapping.md)** | **[docs/guides/setup-guide.md](docs/guides/setup-guide.md)** | **[docs/guides/known-limitations.md](docs/guides/known-limitations.md)**

### クイックスタート (デスクトップのみ)

```bash
git clone https://github.com/happyomg/clawd-on-watch.git
cd clawd-on-watch
npm install
npm start
```

---

## フォーク履歴

このプロジェクトは [@rullerzhou-afk](https://github.com/rullerzhou-afk) (鹿鹿) による [**clawd-on-desk**](https://github.com/rullerzhou-afk/clawd-on-desk) からフォークしたものです。アップストリームプロジェクトは、AI コーディングエージェントにリアルタイムで反応するコミュニティ主導の Electron デスクトップペットです。

**追加した内容:**
- Wear OS コンパニオンアプリ (`watch-app/android/`) — BLE ペリフェラル、SVG アニメーションレンダリング、手首ジェスチャー認識を備えた Kotlin 実装
- Python BLE ブリッジ (`scripts/watch_buddy_bridge.py`) — asyncio + bleak による macOS/Windows/Linux Central 接続
- デスクトップウォッチアダプター (`src/watch-adapter.js`, `src/watch-controller.js`, `src/watch-sidecar-client.js`) — サイドカーライフサイクル、状態プッシュ、テーマ同期、権限リレーのオーケストレーション
- テーマ転送プロトコル — SHA-256 フィンガープリントによる増分同期を備えた、チャンク分割 SVG-over-BLE

### アップストリームとの同期

`upstream` リモートはすでに設定されています。オリジナルの clawd-on-desk から新機能や修正を取り込むには:

```bash
git fetch upstream
git checkout main
git merge upstream/main
# コンフリクトがあれば解決してから:
git push origin main
```

**コンフリクト発生箇所**: `README*.md`、`package.json`、`src/main.js`（ウォッチアダプターの初期化コードがここにあります）。ウォッチ専用ファイル（`watch-app/`、`scripts/watch_buddy_bridge.py`、`src/watch-*.js`）はアップストリームに存在しないため、コンフリクトしません。

## コントリビュート

バグ報告、機能アイデア、Pull Request を歓迎します。[issue](https://github.com/happyomg/clawd-on-watch/issues) を開くか、直接 PR を送ってください。

### アップストリームメンテナー

<table>
  <tr>
    <td align="center" valign="top" width="140"><a href="https://github.com/rullerzhou-afk"><img src="https://github.com/rullerzhou-afk.png" width="72" style="border-radius:50%" /><br /><sub><b>@rullerzhou-afk</b><br />鹿鹿 · creator</sub></a></td>
    <td align="center" valign="top" width="140"><a href="https://github.com/YOIMIYA66"><img src="https://github.com/YOIMIYA66.png" width="72" style="border-radius:50%" /><br /><sub><b>@YOIMIYA66</b><br />maintainer</sub></a></td>
  </tr>
</table>

### コントリビューター

Clawd をより良くしてくれたすべての方に感謝します:

<details>
<summary>コントリビューター 50 人をすべて表示</summary>

<table>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/PixelCookie-zyf"><img src="https://github.com/PixelCookie-zyf.png" width="50" style="border-radius:50%" /><br /><sub>PixelCookie-zyf</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/yujiachen-y"><img src="https://github.com/yujiachen-y.png" width="50" style="border-radius:50%" /><br /><sub>yujiachen-y</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/AooooooZzzz"><img src="https://github.com/AooooooZzzz.png" width="50" style="border-radius:50%" /><br /><sub>AooooooZzzz</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/purefkh"><img src="https://github.com/purefkh.png" width="50" style="border-radius:50%" /><br /><sub>purefkh</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/Tobeabellwether"><img src="https://github.com/Tobeabellwether.png" width="50" style="border-radius:50%" /><br /><sub>Tobeabellwether</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/Jasonhonghh"><img src="https://github.com/Jasonhonghh.png" width="50" style="border-radius:50%" /><br /><sub>Jasonhonghh</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/crashchen"><img src="https://github.com/crashchen.png" width="50" style="border-radius:50%" /><br /><sub>crashchen</sub></a></td>
  </tr>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/hongbigtou"><img src="https://github.com/hongbigtou.png" width="50" style="border-radius:50%" /><br /><sub>hongbigtou</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/InTimmyDate"><img src="https://github.com/InTimmyDate.png" width="50" style="border-radius:50%" /><br /><sub>InTimmyDate</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/NeizhiTouhu"><img src="https://github.com/NeizhiTouhu.png" width="50" style="border-radius:50%" /><br /><sub>NeizhiTouhu</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/xu3stones-cmd"><img src="https://github.com/xu3stones-cmd.png" width="50" style="border-radius:50%" /><br /><sub>xu3stones-cmd</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/androidZzT"><img src="https://github.com/androidZzT.png" width="50" style="border-radius:50%" /><br /><sub>androidZzT</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/Ye-0413"><img src="https://github.com/Ye-0413.png" width="50" style="border-radius:50%" /><br /><sub>Ye-0413</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/WanfengzzZ"><img src="https://github.com/WanfengzzZ.png" width="50" style="border-radius:50%" /><br /><sub>WanfengzzZ</sub></a></td>
  </tr>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/TaoXieSZ"><img src="https://github.com/TaoXieSZ.png" width="50" style="border-radius:50%" /><br /><sub>TaoXieSZ</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/ssly"><img src="https://github.com/ssly.png" width="50" style="border-radius:50%" /><br /><sub>ssly</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/stickycandy"><img src="https://github.com/stickycandy.png" width="50" style="border-radius:50%" /><br /><sub>stickycandy</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/Rladmsrl"><img src="https://github.com/Rladmsrl.png" width="50" style="border-radius:50%" /><br /><sub>Rladmsrl</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/YOIMIYA66"><img src="https://github.com/YOIMIYA66.png" width="50" style="border-radius:50%" /><br /><sub>YOIMIYA66</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/Kevin7Qi"><img src="https://github.com/Kevin7Qi.png" width="50" style="border-radius:50%" /><br /><sub>Kevin7Qi</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/sefuzhou770801-hub"><img src="https://github.com/sefuzhou770801-hub.png" width="50" style="border-radius:50%" /><br /><sub>sefuzhou770801-hub</sub></a></td>
  </tr>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/Tonic-Jin"><img src="https://github.com/Tonic-Jin.png" width="50" style="border-radius:50%" /><br /><sub>Tonic-Jin</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/seoki180"><img src="https://github.com/seoki180.png" width="50" style="border-radius:50%" /><br /><sub>seoki180</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/sophie-haynes"><img src="https://github.com/sophie-haynes.png" width="50" style="border-radius:50%" /><br /><sub>sophie-haynes</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/PeterShanxin"><img src="https://github.com/PeterShanxin.png" width="50" style="border-radius:50%" /><br /><sub>PeterShanxin</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/CHIANGANGSTER"><img src="https://github.com/CHIANGANGSTER.png" width="50" style="border-radius:50%" /><br /><sub>CHIANGANGSTER</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/JaeHyeon-KAIST"><img src="https://github.com/JaeHyeon-KAIST.png" width="50" style="border-radius:50%" /><br /><sub>JaeHyeon-KAIST</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/hhhzxyhhh"><img src="https://github.com/hhhzxyhhh.png" width="50" style="border-radius:50%" /><br /><sub>hhhzxyhhh</sub></a></td>
  </tr>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/TVpoet"><img src="https://github.com/TVpoet.png" width="50" style="border-radius:50%" /><br /><sub>TVpoet</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/zeus6768"><img src="https://github.com/zeus6768.png" width="50" style="border-radius:50%" /><br /><sub>zeus6768</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/anhtrinh919"><img src="https://github.com/anhtrinh919.png" width="50" style="border-radius:50%" /><br /><sub>anhtrinh919</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/tomaioo"><img src="https://github.com/tomaioo.png" width="50" style="border-radius:50%" /><br /><sub>tomaioo</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/v-avuso"><img src="https://github.com/v-avuso.png" width="50" style="border-radius:50%" /><br /><sub>v-avuso</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/livlign"><img src="https://github.com/livlign.png" width="50" style="border-radius:50%" /><br /><sub>livlign</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/tongguang2"><img src="https://github.com/tongguang2.png" width="50" style="border-radius:50%" /><br /><sub>tongguang2</sub></a></td>
  </tr>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/Ziy1-Tan"><img src="https://github.com/Ziy1-Tan.png" width="50" style="border-radius:50%" /><br /><sub>Ziy1-Tan</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/tatsuyanakanogaroinc"><img src="https://github.com/tatsuyanakanogaroinc.png" width="50" style="border-radius:50%" /><br /><sub>tatsuyanakanogaroinc</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/yeonhub"><img src="https://github.com/yeonhub.png" width="50" style="border-radius:50%" /><br /><sub>yeonhub</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/joshua-wu"><img src="https://github.com/joshua-wu.png" width="50" style="border-radius:50%" /><br /><sub>joshua-wu</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/nmsn"><img src="https://github.com/nmsn.png" width="50" style="border-radius:50%" /><br /><sub>nmsn</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/sunnysonx"><img src="https://github.com/sunnysonx.png" width="50" style="border-radius:50%" /><br /><sub>sunnysonx</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/YuChenYunn"><img src="https://github.com/YuChenYunn.png" width="50" style="border-radius:50%" /><br /><sub>YuChenYunn</sub></a></td>
  </tr>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/jhseo-b"><img src="https://github.com/jhseo-b.png" width="50" style="border-radius:50%" /><br /><sub>jhseo-b</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/Hwasowl"><img src="https://github.com/Hwasowl.png" width="50" style="border-radius:50%" /><br /><sub>Hwasowl</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/XiangZheng2002"><img src="https://github.com/XiangZheng2002.png" width="50" style="border-radius:50%" /><br /><sub>XiangZheng2002</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/keiyo118"><img src="https://github.com/keiyo118.png" width="50" style="border-radius:50%" /><br /><sub>keiyo118</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/pan93412"><img src="https://github.com/pan93412.png" width="50" style="border-radius:50%" /><br /><sub>pan93412</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/taehwanis"><img src="https://github.com/taehwanis.png" width="50" style="border-radius:50%" /><br /><sub>taehwanis</sub></a></td>
    <td align="center" valign="top" width="110"><a href="https://github.com/linnin233"><img src="https://github.com/linnin233.png" width="50" style="border-radius:50%" /><br /><sub>linnin233</sub></a></td>
  </tr>
  <tr>
    <td align="center" valign="top" width="110"><a href="https://github.com/xiyouMc"><img src="https://github.com/xiyouMc.png" width="50" style="border-radius:50%" /><br /><sub>xiyouMc</sub></a></td>
  </tr>
</table>

</details>

## 謝辞

- アップストリームプロジェクト [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) — [@rullerzhou-afk](https://github.com/rullerzhou-afk) (鹿鹿)
- Clawd のピクセルアートは [@marciogranzotto](https://github.com/marciogranzotto) による [clawd-tank](https://github.com/marciogranzotto/clawd-tank) を参考にしています
- [LINUX DO](https://linux.do/) コミュニティで共有されました

## ライセンス

ソースコードは [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) のもとでライセンスされています。

**Artwork および同梱テーマアセット (`assets/` と `themes/*/assets/` を含む) は AGPL-3.0 の対象外です。** すべての権利は各著作権者に帰属します。詳細は [assets/LICENSE](assets/LICENSE) と以下の注記を参照してください。

- **Clawd** キャラクターは [Anthropic](https://www.anthropic.com) の所有物です。このプロジェクトは非公式のファンプロジェクトであり、Anthropic との提携または承認を受けたものではありません。
- **Calico cat (三毛猫)** のアートワークは 鹿鹿 ([@rullerzhou-afk](https://github.com/rullerzhou-afk)) によるものです。All rights reserved.
- **Cloudling (云宝)** のアートワークは 鹿鹿 ([@rullerzhou-afk](https://github.com/rullerzhou-afk)) によるものです。All rights reserved. Cloudling のビジュアル方針には OpenAI Codex ロゴへのオマージュが含まれています。Codex/OpenAI の標章は OpenAI に帰属し、このプロジェクトは OpenAI との提携または承認を受けたものではありません。
- **サードパーティのコントリビューション**: 著作権は各アーティストに帰属します。

**暗号資産との関係はありません。** このプロジェクトにはトークン、コイン、NFT、エアドロップはなく、いかなる暗号資産プロジェクトとも関係ありません。
