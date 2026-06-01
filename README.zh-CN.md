<p align="center">
  <img src="assets/tray-icon.png" width="128" alt="Clawd">
</p>
<h1 align="center">Clawd on Watch</h1>
<p align="center">
  <strong>桌面宠物 + Wear OS 手表伴侣</strong><br>
  <sub>Fork 自 <a href="https://github.com/rullerzhou-afk/clawd-on-desk">clawd-on-desk</a>（<a href="https://github.com/rullerzhou-afk">@rullerzhou-afk</a>）</sub>
</p>
<p align="center">
  <a href="README.md">English</a>
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
  <img src="assets/hero.gif" width="360" alt="Clawd 桌宠动画演示：像素螃蟹实时响应 AI 编程助手状态">
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/watch/watch-working.png" width="160" alt="Clawd 手表伴侣：Wear OS 上显示螃蟹打字动画，Connected 状态，working 状态指示">
</p>

## 这是什么？

**Clawd on Watch** 在 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) 桌面宠物的基础上，增加了 **Wear OS 智能手表伴侣**。像素螃蟹同时住在你的桌面和手腕上——当 AI 编程助手开始思考、打字、或并发子代理时，手表通过蓝牙低功耗（BLE）实时同步状态。

离开工位后，抬腕一看就知道 Agent 是还在干活、等审批、还是已经完成了。当 Agent 需要运行高风险命令时，手表震动提醒，你可以**甩腕批准或摇臂拒绝**——不用跑回电脑前。

> 桌面端保留上游 clawd-on-desk 的所有功能：14 种动画状态、权限气泡、会话 Dashboard、自定义主题、多显示器支持，以及 **Claude Code**、**Codex CLI**、**Copilot CLI**、**Gemini CLI**、**Cursor Agent** 等[十余种 Agent 集成](#桌面端功能)。

---

## 手表伴侣

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
  │  CWD1: 状态 + 主题帧           Desktop → Watch      │
  │  CWD2: 审批请求                Desktop → Watch      │
  │  CWD3: 审批响应                Watch → Desktop      │
  │  CWD4: 元数据 + 主题指纹       Watch → Desktop      │
  └─────────────────────────────────────────────────────┘
```

桌面端启动 Python sidecar（`watch_buddy_bridge.py`）作为 BLE Central。手表运行为 BLE Peripheral（GATT Server）。状态快照、主题数据和权限请求通过自定义 GATT 特征值传输。

### 手表功能

#### 实时状态同步
手表通过 BLE 镜像桌面宠物的状态，支持 14 种动画：待机、思考、打字、建造、耳机律动（1 个子代理）、三球杂耍（2+ 子代理）、报错、开心、通知、扫地、搬运、睡觉等。底部彩色药丸标签显示当前状态，每次状态切换伴随短震动反馈。

#### BLE 主题同步
自定义主题自动从桌面推送到手表。SVG 动画文件经分块后通过 BLE 传输，在手表端通过隐藏 WebView 逐帧录制 CSS 动画，缓存为 WebP 序列帧。手表使用 SHA-256 指纹检测过期主题，仅在需要时同步。

#### 手腕审批权限
当 AI Agent 请求运行工具或命令时，手表按风险等级震动提醒：
- **高风险**：三连震，手势禁用——只能用按钮
- **中风险**：双连震
- **低风险**：单次短震

三种审批方式：
1. **按钮**：拒绝 / 允许 / 始终允许
2. **手腕手势**：甩腕批准，摇臂拒绝（高风险时禁用）
3. **超时**：灭屏或超时自动拒绝

#### 自动重连与电源管理
BLE 连接断开后自动渐进重试恢复。30 秒看门狗检测 Central 静默断连。息屏时暂停动画和手势检测，节省电量。

### 手表效果演示

<p align="center">
  <img src="assets/watch/watch-working.png" width="200" alt="手表显示 Connected 状态，螃蟹正在打字——working 状态">
  <br>
  <sub>Clawd 在 Wear OS 手表上——"Connected" 连接成功，螃蟹正在码代码</sub>
</p>

### 使用指南

#### 环境要求

| 组件 | 要求 |
|------|------|
| 手表 | 支持 BLE 的 Wear OS 设备 |
| 桌面 | macOS（已测试）、Windows/Linux（实验性） |
| Python | Python 3.8+，安装 `bleak` 库 |
| Android SDK | compileSdk 34，JDK 17 |

#### 1. 桌面端：启用手表模式

1. 启动 Clawd，打开 **设置**（右键菜单或托盘菜单）
2. 进入 **远程审批** 标签页（侧边栏飞机图标）
3. 展开 **Watch** 卡片
4. 打开 **"Enable"** 开关——后台会启动 BLE 桥接 sidecar
5. 点击 **"Scan"**——附近的 Wear OS 设备会以列表形式出现
6. 点击你的手表——桌面端自动连接
7. 连接成功后状态显示 **"Connected: \<设备名\>"**
8. 可选：打开 **"Approval on Watch"** 开关，将工具权限请求转发到手表审批

> 如果桥接提示 `missing_bleak`，点击错误提示中的 **"Install bleak"** 按钮即可自动安装。

**老用户快捷方式**：如果之前连接过，设备地址已保存。开启后直接点 **"Reconnect"** 即可跳过扫描。

<details>
<summary>高级：环境变量覆盖（用于开发/CI）</summary>

```bash
export CLAWD_WATCH_ENABLED=1                      # 强制启用（覆盖设置）
export CLAWD_WATCH_ADDRESS="<BLE地址>"              # BLE 地址（macOS 为 UUID 格式）
export CLAWD_WATCH_NAME_PREFIX="Clawd"             # 扫描名称前缀
export CLAWD_WATCH_PYTHON="python3"                # Python 可执行文件
```

</details>

#### 2. 手表端：编译安装

```bash
cd watch-app/android

# 设置环境
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=/path/to/jdk17   # 需要 JDK 17（Gradle 8.2）

# 编译
./gradlew assembleDebug

# 安装到手表（通过 ADB 连接）
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

#### 3. 配对连接

1. 启动手表应用——进入配对模式，开始 BLE 广播
2. 在桌面端设置 `CLAWD_WATCH_ENABLED=1` 后启动
3. 桌面端扫描附近以 "Clawd" 为前缀的 BLE 外设
4. 连接成功后手表显示 "Connected"，宠物开始同步状态
5. 如果桌面有自定义主题，首次连接时自动同步到手表

#### 4. 验证

```bash
# 查看手表日志
adb logcat -s PetView FrameRecorder ThemeConfig BleService

# 期望看到：
# BleService: Central connected
# ThemeReceiver: assembled: clawd (8399a0)
# PetView: setState → WORKING
```

---

## 桌面端功能

> 包含上游 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) 的所有功能，以下为概要。

### Agent 集成

| Agent | 集成方式 | 权限气泡 |
|-------|---------|---------|
| Claude Code | Command hook + HTTP 权限 hook | 支持 |
| Codex CLI | Official hooks + JSONL 兜底 | 支持 |
| Copilot CLI | Command hooks | 不支持 |
| Gemini CLI | Command hooks（自动注册） | 不支持 |
| Cursor Agent | IDE hooks（自动注册） | 不支持 |
| Antigravity CLI | Command hooks（仅状态） | 不支持 |
| CodeBuddy | Command hook + HTTP 权限 hook | 支持 |
| Kiro CLI | 自定义 agent 配置 | 不支持 |
| Kimi Code CLI | TOML command hooks | 不支持 |
| Qwen Code | Command hooks + 权限请求 | 支持 |
| opencode | Plugin 集成 | 支持 |
| Pi | 全局 extension（仅状态） | 不支持 |
| OpenClaw | Plugin 集成（仅状态） | 不支持 |
| Hermes Agent | Plugin 集成 | 不支持 |

### 动画一览

<table>
  <tr>
    <td align="center"><img src="assets/gif/clawd-idle.gif" width="100"><br><sub>待机</sub></td>
    <td align="center"><img src="assets/gif/clawd-thinking.gif" width="100"><br><sub>思考</sub></td>
    <td align="center"><img src="assets/gif/clawd-typing.gif" width="100"><br><sub>打字</sub></td>
    <td align="center"><img src="assets/gif/clawd-building.gif" width="100"><br><sub>建造</sub></td>
    <td align="center"><img src="assets/gif/clawd-headphones-groove.gif" width="100"><br><sub>耳机律动</sub></td>
    <td align="center"><img src="assets/gif/clawd-juggling.gif" width="100"><br><sub>三球杂耍</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/calico-idle.gif" width="80"><br><sub>三花待机</sub></td>
    <td align="center"><img src="assets/gif/calico-thinking.gif" width="80"><br><sub>三花思考</sub></td>
    <td align="center"><img src="assets/gif/calico-typing.gif" width="80"><br><sub>三花打字</sub></td>
    <td align="center"><img src="assets/gif/calico-building.gif" width="80"><br><sub>三花建造</sub></td>
    <td align="center"><img src="assets/gif/calico-juggling.gif" width="80"><br><sub>三花杂耍</sub></td>
    <td align="center"><img src="assets/gif/calico-conducting.gif" width="80"><br><sub>三花指挥</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/cloudling-idle.gif" width="120"><br><sub>云宝待机</sub></td>
    <td align="center"><img src="assets/gif/cloudling-thinking.gif" width="120"><br><sub>云宝思考</sub></td>
    <td align="center"><img src="assets/gif/cloudling-typing.gif" width="120"><br><sub>云宝打字</sub></td>
    <td align="center"><img src="assets/gif/cloudling-building.gif" width="120"><br><sub>云宝建造</sub></td>
    <td align="center"><img src="assets/gif/cloudling-juggling.gif" width="120"><br><sub>云宝杂耍</sub></td>
    <td align="center"><img src="assets/gif/cloudling-conducting.gif" width="120"><br><sub>云宝指挥</sub></td>
  </tr>
</table>

### 其他桌面功能

- **权限审批气泡** — 浮动卡片审批权限，支持 Allow / Deny / Always Allow 和全局快捷键（`Ctrl+Shift+Y` / `Ctrl+Shift+N`）
- **会话 Dashboard + HUD** — 查看活跃会话、最近事件，可跳转终端
- **自定义主题** — 用 SVG/GIF/APNG 素材创建你自己的角色，或导入 Codex Pet zip 包
- **多显示器** — 等比缩放、竖屏加成、跨屏拖动
- **极简模式** — 藏到屏幕边缘，悬停探头
- **眼球追踪** — 待机时 Clawd 跟随鼠标
- **国际化** — 英文、简体中文、繁体中文、韩文、日文
- **自动更新** — 检查 GitHub release 获取新版本

详细文档见：**[docs/guides/state-mapping.zh-CN.md](docs/guides/state-mapping.zh-CN.md)** | **[docs/guides/setup-guide.zh-CN.md](docs/guides/setup-guide.zh-CN.md)** | **[docs/guides/known-limitations.zh-CN.md](docs/guides/known-limitations.zh-CN.md)**

### 快速开始（仅桌面端）

```bash
git clone https://github.com/happyomg/clawd-on-watch.git
cd clawd-on-watch
npm install
npm start
```

---

## Fork 历史

本项目 Fork 自 [**clawd-on-desk**](https://github.com/rullerzhou-afk/clawd-on-desk)（[@rullerzhou-afk](https://github.com/rullerzhou-afk) / 鹿鹿）。上游项目是一个社区驱动的 Electron 桌面宠物，能实时响应 AI 编程助手。

**我们新增的功能：**
- Wear OS 手表伴侣应用（`watch-app/android/`）——完整的 Kotlin 实现，包含 BLE Peripheral、SVG 动画渲染、手腕手势识别
- Python BLE 桥接（`scripts/watch_buddy_bridge.py`）——asyncio + bleak，支持 macOS/Windows/Linux Central 连接
- 桌面端手表适配器（`src/watch-adapter.js`、`src/watch-controller.js`、`src/watch-sidecar-client.js`）——管理 sidecar 生命周期、状态推送、主题同步、权限转发
- 主题传输协议——分块 SVG-over-BLE，使用 SHA-256 指纹做增量同步

### 同步上游更新

`upstream` remote 已配置好。拉取上游 clawd-on-desk 的新功能或修复：

```bash
git fetch upstream
git checkout main
git merge upstream/main
# 有冲突则解决，然后：
git push origin main
```

**冲突热点**：`README*.md`、`package.json`、`src/main.js`（手表适配器初始化在此文件中）。我们独有的文件（`watch-app/`、`scripts/watch_buddy_bridge.py`、`src/watch-*.js`）不会冲突，因为上游不存在这些文件。

## 参与贡献

欢迎提 Bug、提需求、提 PR——在 [Issues](https://github.com/happyomg/clawd-on-watch/issues) 里聊或直接提交 PR。

### 上游维护者

<table>
  <tr>
    <td align="center" valign="top" width="140"><a href="https://github.com/rullerzhou-afk"><img src="https://github.com/rullerzhou-afk.png" width="72" style="border-radius:50%" /><br /><sub><b>@rullerzhou-afk</b><br />鹿鹿 · 创建者</sub></a></td>
    <td align="center" valign="top" width="140"><a href="https://github.com/YOIMIYA66"><img src="https://github.com/YOIMIYA66.png" width="72" style="border-radius:50%" /><br /><sub><b>@YOIMIYA66</b><br />维护者</sub></a></td>
  </tr>
</table>

### 贡献者

感谢每一位让 Clawd 变得更好的贡献者：

<details>
<summary>展开全部 50 位贡献者</summary>

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

## 致谢

- 上游项目 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk)（[@rullerzhou-afk](https://github.com/rullerzhou-afk) / 鹿鹿）
- Clawd 像素画参考自 [clawd-tank](https://github.com/marciogranzotto/clawd-tank)（[@marciogranzotto](https://github.com/marciogranzotto)）
- 本项目在 [LINUX DO](https://linux.do/) 社区推广

## 许可证

源代码基于 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）开源。

**美术素材和内置主题素材（包括 `assets/` 与 `themes/*/assets/`）不适用 AGPL-3.0 许可。** 所有权利归各自版权持有人所有，详见 [assets/LICENSE](assets/LICENSE) 及下列说明。

- **Clawd** 角色设计归属 [Anthropic](https://www.anthropic.com)。本项目为非官方粉丝作品，与 Anthropic 无官方关联。
- **三花猫** 素材由 鹿鹿 ([@rullerzhou-afk](https://github.com/rullerzhou-afk)) 创作，保留所有权利。
- **Cloudling（云宝）** 素材由 鹿鹿 ([@rullerzhou-afk](https://github.com/rullerzhou-afk)) 创作，保留所有权利。云宝的视觉方向包含对 OpenAI Codex logo 的致敬；Codex / OpenAI 相关标识仍归 OpenAI 所有，本项目与 OpenAI 无官方关联，也未获 OpenAI 背书。
- **第三方画师作品**：版权归各自作者所有。

**无加密货币。** 本项目没有代币、硬币、NFT 或空投，与任何加密货币项目无关。
