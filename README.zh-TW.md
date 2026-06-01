<p align="center">
  <img src="assets/tray-icon.png" width="128" alt="Clawd">
</p>
<h1 align="center">Clawd on Watch</h1>
<p align="center">
  <strong>桌面寵物 + Wear OS 手錶伴侶</strong><br>
  <sub>Fork 自 <a href="https://github.com/rullerzhou-afk/clawd-on-desk">clawd-on-desk</a>（<a href="https://github.com/rullerzhou-afk">@rullerzhou-afk</a>）</sub>
</p>
<p align="center">
  <a href="README.md">English</a>
  ·
  <a href="README.zh-CN.md">简体中文</a>
  ·
  <a href="README.ko-KR.md">한국어</a>
  ·
  <a href="README.ja-JP.md">日本語</a>
</p>
<p align="center">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux%20%7C%20Wear%20OS-lightgrey" alt="Platform">
</p>

<p align="center">
  <img src="assets/hero.gif" width="360" alt="Clawd 桌寵動畫示範：像素螃蟹即時回應 AI 程式設計助理狀態">
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/watch/watch-working.png" width="160" alt="Clawd 手錶伴侶：Wear OS 上顯示螃蟹打字動畫，Connected 狀態，working 狀態指示">
</p>

## 這是什麼？

**Clawd on Watch** 在 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) 桌面寵物的基礎上，增加了 **Wear OS 智慧手錶伴侶**。像素螃蟹同時住在你的桌面和手腕上——當 AI 程式設計助理開始思考、打字、或並行子代理時，手錶透過藍牙低功耗（BLE）即時同步狀態。

離開工位後，抬腕一看就知道 Agent 是還在幹活、等審批、還是已經完成了。當 Agent 需要執行高風險命令時，手錶震動提醒，你可以**甩腕批准或搖臂拒絕**——不用跑回電腦前。

> 桌面端保留上游 clawd-on-desk 的所有功能：14 種動畫狀態、權限氣泡、工作階段 Dashboard、自訂主題、多螢幕支援，以及 **Claude Code**、**Codex CLI**、**Copilot CLI**、**Gemini CLI**、**Cursor Agent** 等[十餘種 Agent 整合](#桌面端功能)。

---

## 手錶伴侶

### 工作原理

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
  │  CWD1: 狀態 + 主題幀           Desktop → Watch      │
  │  CWD2: 審批請求                Desktop → Watch      │
  │  CWD3: 審批回應                Watch → Desktop      │
  │  CWD4: 中繼資料 + 主題指紋     Watch → Desktop      │
  └─────────────────────────────────────────────────────┘
```

桌面端啟動 Python sidecar（`watch_buddy_bridge.py`）作為 BLE Central。手錶執行為 BLE Peripheral（GATT Server）。狀態快照、主題資料和權限請求透過自訂 GATT 特徵值傳輸。

### 手錶功能

#### 即時狀態同步
手錶透過 BLE 鏡像桌面寵物的狀態，支援 14 種動畫：待機、思考、打字、建造、耳機律動（1 個子代理）、三球雜耍（2+ 子代理）、報錯、開心、通知、掃地、搬運、睡覺等。底部彩色藥丸標籤顯示目前狀態，每次狀態切換伴隨短震動回饋。

#### BLE 主題同步
自訂主題自動從桌面推送到手錶。SVG 動畫檔案經分塊後透過 BLE 傳輸，在手錶端透過隱藏 WebView 逐幀錄製 CSS 動畫，快取為 WebP 序列幀。手錶使用 SHA-256 指紋偵測過期主題，僅在需要時同步。

#### 手腕審批權限
當 AI Agent 請求執行工具或命令時，手錶按風險等級震動提醒：
- **高風險**：三連震，手勢禁用——只能用按鈕
- **中風險**：雙連震
- **低風險**：單次短震

三種審批方式：
1. **按鈕**：拒絕 / 允許 / 始終允許
2. **手腕手勢**：甩腕批准，搖臂拒絕（高風險時禁用）
3. **逾時**：滅螢幕或逾時自動拒絕

#### 自動重連與電源管理
BLE 連線斷開後自動漸進重試恢復。30 秒看門狗偵測 Central 靜默斷連。息螢幕時暫停動畫和手勢偵測，節省電量。

### 手錶效果展示

<p align="center">
  <img src="assets/watch/watch-working.png" width="200" alt="手錶顯示 Connected 狀態，螃蟹正在打字——working 狀態">
  <br>
  <sub>Clawd 在 Wear OS 手錶上——「Connected」連線成功，螃蟹正在寫程式</sub>
</p>

### 使用指南

#### 環境需求

| 元件 | 需求 |
|------|------|
| 手錶 | 支援 BLE 的 Wear OS 裝置 |
| 桌面 | macOS（已測試）、Windows/Linux（實驗性） |
| Python | Python 3.8+，安裝 `bleak` 函式庫 |
| Android SDK | compileSdk 34，JDK 17 |

#### 1. 桌面端：啟用手錶模式

1. 啟動 Clawd，開啟 **設定**（右鍵選單或系統匣選單）
2. 進入 **遠端審批** 標籤頁（側邊欄飛機圖示）
3. 展開 **Watch** 卡片
4. 開啟 **"Enable"** 開關——後台會啟動 BLE 橋接 sidecar
5. 點擊 **"Scan"**——附近的 Wear OS 裝置會以列表顯示
6. 點擊你的手錶——桌面端自動連線
7. 連線成功後狀態顯示 **"Connected: \<裝置名\>"**
8. 可選：開啟 **"Approval on Watch"** 開關，將工具權限請求轉發到手錶審批

> 如果橋接提示 `missing_bleak`，點擊錯誤提示中的 **"Install bleak"** 按鈕即可自動安裝。

**老用戶快捷方式**：如果之前連線過，裝置位址已儲存。啟用後直接點 **"Reconnect"** 即可跳過掃描。

<details>
<summary>進階：環境變數覆蓋（用於開發/CI）</summary>

```bash
export CLAWD_WATCH_ENABLED=1                      # 強制啟用（覆蓋設定）
export CLAWD_WATCH_ADDRESS="<BLE位址>"              # BLE 位址（macOS 為 UUID 格式）
export CLAWD_WATCH_NAME_PREFIX="Clawd"             # 掃描名稱前綴
export CLAWD_WATCH_PYTHON="python3"                # Python 可執行檔
```

</details>

#### 2. 手錶端：編譯安裝

```bash
cd watch-app/android

# 設定環境
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=/path/to/jdk17   # 需要 JDK 17（Gradle 8.2）

# 編譯
./gradlew assembleDebug

# 安裝到手錶（透過 ADB 連線）
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

#### 3. 配對連線

1. 啟動手錶應用程式——進入配對模式，開始 BLE 廣播
2. 在桌面端設定 `CLAWD_WATCH_ENABLED=1` 後啟動
3. 桌面端掃描附近以「Clawd」為前綴的 BLE 外設
4. 連線成功後手錶顯示「Connected」，寵物開始同步狀態
5. 如果桌面有自訂主題，首次連線時自動同步到手錶

#### 4. 驗證

```bash
# 檢視手錶日誌
adb logcat -s PetView FrameRecorder ThemeConfig BleService

# 預期看到：
# BleService: Central connected
# ThemeReceiver: assembled: clawd (8399a0)
# PetView: setState → WORKING
```

---

## 桌面端功能

> 包含上游 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) 的所有功能，以下為概要。

### Agent 整合

| Agent | 整合方式 | 權限氣泡 |
|-------|---------|---------|
| Claude Code | Command hook + HTTP 權限 hook | 支援 |
| Codex CLI | Official hooks + JSONL 備援 | 支援 |
| Copilot CLI | Command hooks | 不支援 |
| Gemini CLI | Command hooks（自動註冊） | 不支援 |
| Cursor Agent | IDE hooks（自動註冊） | 不支援 |
| Antigravity CLI | Command hooks（僅狀態） | 不支援 |
| CodeBuddy | Command hooks + HTTP 權限 hook | 支援 |
| Kiro CLI | 自訂 agent 設定 | 不支援 |
| Kimi Code CLI | TOML command hooks | 不支援 |
| Qwen Code | Command hooks + 權限請求 | 支援 |
| opencode | Plugin 整合 | 支援 |
| Pi | 全域 extension（僅狀態） | 不支援 |
| OpenClaw | Plugin 整合（僅狀態） | 不支援 |
| Hermes Agent | Plugin 整合 | 不支援 |

### 動畫一覽

<table>
  <tr>
    <td align="center"><img src="assets/gif/clawd-idle.gif" width="100"><br><sub>待機</sub></td>
    <td align="center"><img src="assets/gif/clawd-thinking.gif" width="100"><br><sub>思考</sub></td>
    <td align="center"><img src="assets/gif/clawd-typing.gif" width="100"><br><sub>打字</sub></td>
    <td align="center"><img src="assets/gif/clawd-building.gif" width="100"><br><sub>建造</sub></td>
    <td align="center"><img src="assets/gif/clawd-headphones-groove.gif" width="100"><br><sub>耳機律動</sub></td>
    <td align="center"><img src="assets/gif/clawd-juggling.gif" width="100"><br><sub>三球雜耍</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/calico-idle.gif" width="80"><br><sub>三花待機</sub></td>
    <td align="center"><img src="assets/gif/calico-thinking.gif" width="80"><br><sub>三花思考</sub></td>
    <td align="center"><img src="assets/gif/calico-typing.gif" width="80"><br><sub>三花打字</sub></td>
    <td align="center"><img src="assets/gif/calico-building.gif" width="80"><br><sub>三花建造</sub></td>
    <td align="center"><img src="assets/gif/calico-juggling.gif" width="80"><br><sub>三花雜耍</sub></td>
    <td align="center"><img src="assets/gif/calico-conducting.gif" width="80"><br><sub>三花指揮</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/cloudling-idle.gif" width="120"><br><sub>雲寶待機</sub></td>
    <td align="center"><img src="assets/gif/cloudling-thinking.gif" width="120"><br><sub>雲寶思考</sub></td>
    <td align="center"><img src="assets/gif/cloudling-typing.gif" width="120"><br><sub>雲寶打字</sub></td>
    <td align="center"><img src="assets/gif/cloudling-building.gif" width="120"><br><sub>雲寶建造</sub></td>
    <td align="center"><img src="assets/gif/cloudling-juggling.gif" width="120"><br><sub>雲寶雜耍</sub></td>
    <td align="center"><img src="assets/gif/cloudling-conducting.gif" width="120"><br><sub>雲寶指揮</sub></td>
  </tr>
</table>

### 其他桌面功能

- **權限審批氣泡** — 浮動卡片審批權限，支援 Allow / Deny / Always Allow 和全域快速鍵（`Ctrl+Shift+Y` / `Ctrl+Shift+N`）
- **工作階段 Dashboard + HUD** — 檢視進行中的工作階段、最近事件，可跳轉終端機
- **自訂主題** — 用 SVG/GIF/APNG 素材建立你自己的角色，或匯入 Codex Pet zip 套件
- **多螢幕** — 等比縮放、直立螢幕加成、跨螢幕拖動
- **極簡模式** — 藏到螢幕邊緣，懸停探頭
- **眼球追蹤** — 待機時 Clawd 跟隨滑鼠
- **國際化** — 英文、簡體中文、繁體中文、韓文、日文
- **自動更新** — 檢查 GitHub release 取得新版本

詳細文件見：**[docs/guides/state-mapping.zh-CN.md](docs/guides/state-mapping.zh-CN.md)** | **[docs/guides/setup-guide.zh-CN.md](docs/guides/setup-guide.zh-CN.md)** | **[docs/guides/known-limitations.zh-CN.md](docs/guides/known-limitations.zh-CN.md)**

### 快速開始（僅桌面端）

```bash
git clone https://github.com/happyomg/clawd-on-watch.git
cd clawd-on-watch
npm install
npm start
```

---

## Fork 歷史

本專案 Fork 自 [**clawd-on-desk**](https://github.com/rullerzhou-afk/clawd-on-desk)（[@rullerzhou-afk](https://github.com/rullerzhou-afk) / 鹿鹿）。上游專案是一個社群驅動的 Electron 桌面寵物，能即時回應 AI 程式設計助理。

**我們新增的功能：**
- Wear OS 手錶伴侶應用程式（`watch-app/android/`）——完整的 Kotlin 實作，包含 BLE Peripheral、SVG 動畫渲染、手腕手勢辨識
- Python BLE 橋接（`scripts/watch_buddy_bridge.py`）——asyncio + bleak，支援 macOS/Windows/Linux Central 連線
- 桌面端手錶適配器（`src/watch-adapter.js`、`src/watch-controller.js`、`src/watch-sidecar-client.js`）——管理 sidecar 生命週期、狀態推送、主題同步、權限轉發
- 主題傳輸協定——分塊 SVG-over-BLE，使用 SHA-256 指紋做增量同步

### 同步上游更新

首先確認 upstream remote 指向原始專案：

```bash
git remote -v | grep upstream
# 如果沒有或不對：
git remote remove upstream 2>/dev/null
git remote add upstream https://github.com/rullerzhou-afk/clawd-on-desk.git
```

拉取上游變更：

```bash
git fetch upstream
git checkout main
git merge upstream/main
# 解決衝突，然後：
git push origin main
```

#### 衝突中如何識別「我們的」還是「上游的」

我們 fork 只改了 **15 個上游檔案**。其餘 73 個檔案（`watch-app/`、`src/watch-*.js`、`scripts/watch_buddy_bridge.py`）是我們獨有的，永遠不會衝突。遇到合併衝突時參考：

| 檔案 | 我們的改動 | 合併策略 |
|------|-----------|---------|
| `src/main.js` | Watch adapter 初始化、鉤子、權限過濾 | **手動合併** — 保留 `// ── Watch adapter` 段落和 `watchAdapter` 單行呼叫 |
| `README*.md` | 為手錶品牌全部重寫 | **保留我們的** (`git checkout --ours`) |
| `package.json` | `name` 和 `description` | **name/description 保留我們的**，**版本號取上游** |
| 其他 `src/settings-*`、`src/prefs.js` | 末尾追加 watch 程式碼 | **兩邊都保留** |

**快速識別規則**：衝突 hunk 中搜尋 `watch` 或 `Watch`。包含這些關鍵字的就是我們的程式碼。

## 參與貢獻

歡迎提 Bug、提需求、提 PR——在 [Issues](https://github.com/happyomg/clawd-on-watch/issues) 裡聊或直接提交 PR。

### 上游維護者

<table>
  <tr>
    <td align="center" valign="top" width="140"><a href="https://github.com/rullerzhou-afk"><img src="https://github.com/rullerzhou-afk.png" width="72" style="border-radius:50%" /><br /><sub><b>@rullerzhou-afk</b><br />鹿鹿 · 建立者</sub></a></td>
    <td align="center" valign="top" width="140"><a href="https://github.com/YOIMIYA66"><img src="https://github.com/YOIMIYA66.png" width="72" style="border-radius:50%" /><br /><sub><b>@YOIMIYA66</b><br />維護者</sub></a></td>
  </tr>
</table>

### 貢獻者

感謝每一位讓 Clawd 變得更好的貢獻者：

<details>
<summary>展開全部 50 位貢獻者</summary>

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

## 致謝

- 上游專案 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk)（[@rullerzhou-afk](https://github.com/rullerzhou-afk) / 鹿鹿）
- Clawd 像素畫參考自 [clawd-tank](https://github.com/marciogranzotto/clawd-tank)（[@marciogranzotto](https://github.com/marciogranzotto)）
- 本專案在 [LINUX DO](https://linux.do/) 社群推廣

## 授權

原始碼以 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）授權釋出。

**美術素材和內建主題素材（包括 `assets/` 與 `themes/*/assets/`）不適用 AGPL-3.0 授權。** 所有權利歸各自著作權人所有，詳見 [assets/LICENSE](assets/LICENSE) 及下列說明。

- **Clawd** 角色設計屬於 [Anthropic](https://www.anthropic.com)。本專案為非官方粉絲作品，與 Anthropic 沒有官方關聯。
- **三花貓** 素材由 鹿鹿 ([@rullerzhou-afk](https://github.com/rullerzhou-afk)) 創作，保留所有權利。
- **Cloudling（雲寶）** 素材由 鹿鹿 ([@rullerzhou-afk](https://github.com/rullerzhou-afk)) 創作，保留所有權利。雲寶的視覺方向包含對 OpenAI Codex logo 的致敬；Codex 與 OpenAI 相關標誌仍歸 OpenAI 所有，本專案與 OpenAI 沒有官方關聯，也未獲 OpenAI 背書。
- **第三方畫師作品**：著作權歸各自作者所有。

**無加密貨幣。** 本專案沒有代幣、硬幣、NFT 或空投，與任何加密貨幣專案無關。
