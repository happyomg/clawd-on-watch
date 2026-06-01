AGENTS.md

# Clawd on Watch

Electron 桌面宠物 + Wear OS 手表伴侣。桌面端监听 AI 编码会话状态，手表端通过 BLE 同步显示宠物动画 + 审批权限。

## 架构

```
Desktop (Electron)                Watch (Wear OS Kotlin)
  src/main.js                       watch-app/android/
  src/watch-adapter.js              service/BleService.kt
  src/watch-controller.js           renderer/PetView.kt
  src/watch-sidecar-client.js       renderer/FrameRecorder.kt
       │                            domain/ThemeConfig.kt
       │ stdio JSON                 domain/ThemeReceiver.kt
       ▼
  scripts/watch_buddy_bridge.py (BLE Central, Python bleak)
       │
       │ GATT over BLE (全部走 CWD1)
       ▼
  CWD1: State {s,n,th} + Theme frames {t:...}   Desktop → Watch
  CWD2: Approval Request    Desktop → Watch
  CWD3: Approval Response   Watch → Desktop (notify)
  CWD4: Meta + themeHash    Watch → Desktop (read)
```

> **注意**: 主题数据通过 CWD1（和状态推送同一特征值）传输，Watch 端用 JSON 的 `t` 字段区分。CWD5 因 macOS CoreBluetooth GATT 缓存问题不可用。

## 常用命令

### Desktop (Electron)

```bash
npm start                    # 启动桌面应用
npm test                     # 运行全部 JS 测试 (node:test)
node --test test/watch-*.js  # 只跑 watch 相关测试
```

### Watch (Android)

```bash
cd watch-app/android
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=/path/to/jdk17   # JDK 17，Gradle 8.2 不支持 JDK 25

./gradlew assembleDebug            # 编译 debug APK
./gradlew assembleRelease          # 编译 release APK

# 部署到手表
adb install -r -d app/build/outputs/apk/debug/app-debug.apk

# 查看日志
adb logcat -s PetView FrameRecorder ThemeConfig BleService
```

### Python BLE Bridge

```bash
pip install bleak            # 依赖
python3 scripts/watch_buddy_bridge.py --name-prefix Clawd
```

## 同步上游 (clawd-on-desk) 注意事项

本项目 fork 自 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk)。upstream remote 应指向原始项目：

```bash
# 确认 upstream 指向正确
git remote -v | grep upstream
# 期望: https://github.com/rullerzhou-afk/clawd-on-desk.git

# 同步流程
git fetch upstream
git checkout main && git merge upstream/main && git push origin main
# feature 分支 rebase: git rebase main
```

### Rebase 后必须检查的事项

我们只修改了 15 个上游文件，其中 `src/main.js` 是最高风险的冲突点。Rebase/merge 后必须逐项验证：

| 检查项 | 位置 | 验证方法 |
|--------|------|---------|
| **权限过滤器完整性** | `src/main.js` hardwareBuddy 和 watch 的 `getPendingPermissions` | 必须包含 `!p.isElicitation`、`p.toolName !== "ExitPlanMode"`、`p.toolName !== "AskUserQuestion"` 三个条件。丢失会导致 ExitPlanMode/AskUserQuestion 被推到手表，手表息屏 auto-deny 导致 Claude Code 报 "permission denied by hook" |
| **watchAdapter 状态钩子** | `src/main.js` `_stateCtx.onStateChanged` | `if (watchAdapter) watchAdapter.notifyStateChanged()` 必须存在，通常在 `hardwareBuddyAdapter.notifyStateChanged()` 旁边 |
| **watchAdapter 权限钩子** | `src/main.js` `_permCtx.onPermissionsChanged` | `if (watchAdapter) watchAdapter.notifyPermissionsChanged()` 必须存在 |
| **watch settings commands** | `src/main.js` `_settingsController` | `restartWatch`、`scanWatch`、`connectWatch`、`disconnectWatch`、`reconnectWatch` 五个 action 必须存在 |
| **settings.html script 引用** | `src/settings.html` | `<script src="settings-watch-panel.js">` 必须存在 |
| **watch panel 挂载** | `src/settings-tab-telegram-approval.js` | `buildWatchChannelCard()` 调用必须存在 |

### 快速识别冲突归属

冲突 hunk 中搜索 `watch` 或 `Watch` 关键词——包含这些的是我们的代码，上游不存在任何 watch 相关代码。我们的插入模式：
- 独立代码块以 `// ── Watch adapter` 标记开始
- 在 `hardwareBuddyAdapter` 调用旁添加等价的 `watchAdapter` 单行调用
- `README*.md` 冲突直接 `git checkout --ours`

### 历史教训

- **2026-06-01 rebase 回退**：upstream 独立修改了 `getPendingPermissions`（只加了 `isCodexNotify`/`isKimiNotify`/`isHardwareBuddyTest`），与我们更严格的 filter（含 `isElicitation`/`ExitPlanMode`/`AskUserQuestion`）冲突，rebase 自动解析时选择了上游的简化版本，导致我们的三个关键过滤条件丢失。此类"静默回退"不会产生冲突标记，只能通过 rebase 后人工检查发现。

## E2E 测试规范

### ⚠️ 测试前必须清理（防进程冲突）

**核心原则**：手表同一时间只能有一个 Central 连接。如果有残留进程占着连接，新启动的 Desktop 无法连上。

```bash
# 第 0 步：彻底杀掉所有旧进程（最重要！）
pkill -9 -f "watch_buddy_bridge"
pkill -9 -f "Electron.*clawd"    # 注意：可能匹配不到，用下面的方式
# 找到真正的 Electron PID：
ps aux | grep "Electron \." | grep -v grep | grep -v Helper | awk '{print $2}' | xargs kill -9
# 清掉 SingletonLock（否则新实例启动即退出）：
rm -f ~/Library/Application\ Support/clawd-on-desk/SingletonLock
rm -f ~/Library/Application\ Support/clawd-on-desk/SingletonSocket
# 确认全部清干净：
ps aux | grep -iE "watch_buddy_bridge|Electron" | grep -v grep | grep -v Helper | grep -v Qoder
# 应该无输出
```

### 踩坑经验（必读）

| 坑 | 现象 | 原因 | 解法 |
|---|------|------|------|
| 手表连上旧 bridge | Watch 显示 Connected 但 Desktop 日志无连接 | 旧 bridge 进程是孤儿，占着 BLE 连接 | `pkill -9 -f watch_buddy_bridge` |
| 新 Electron 启动即退出 | `node launch.js` 无输出直接结束 | SingletonLock 被旧实例持有 | 删 lock 文件 |
| Desktop 日志只有 `started` | bridge 连上了但没有 status 输出 | stdout redirect 问题或 CWD4 读超时卡死（已修） | 直接用 Electron 可执行文件跑 |
| theme sync 0 frames | 触发了同步但帧没传过去 | Python StreamReader 64KB limit（已修）或断连 | 检查 `theme sync confirmed` 日志 |
| 帧内容空白/有 UI chrome | View.draw + SOFTWARE layer 空白 或 PixelCopy 截全屏 | 设备 WebView 实现差异 | enableSlowWholeDocumentDraw + alpha=0 WebView |
| macOS GATT 缓存 | 新加的 CWD5 特征值写入超时 | CoreBluetooth 缓存旧的服务列表，永远不更新 | 不用新 UUID，复用 CWD1 |

### 完整 E2E 测试流程

```bash
# === 1. 清理环境 ===
pkill -9 -f "watch_buddy_bridge"
ps aux | grep "Electron \." | grep -v grep | grep -v Helper | awk '{print $2}' | xargs kill -9 2>/dev/null
rm -f ~/Library/Application\ Support/clawd-on-desk/SingletonLock
rm -f ~/Library/Application\ Support/clawd-on-desk/SingletonSocket
sleep 3

# === 2. 编译部署 Watch ===
SDK=~/Library/Android/sdk
JBR=<你的 JDK17 路径>
cd watch-app/android
ANDROID_HOME=$SDK JAVA_HOME=$JBR ./gradlew assembleDebug --offline
adb install -r -d app/build/outputs/apk/debug/app-debug.apk

# === 3. 清 Watch 缓存 ===
adb shell "run-as com.clawd.watch rm -rf files/themes"
adb shell "run-as com.clawd.watch rm shared_prefs/clawd_state.xml"

# === 4. 启动 Watch ===
adb shell am force-stop com.clawd.watch
adb logcat -c
adb shell am start -n com.clawd.watch/.MainActivity
sleep 3

# === 5. 启动 Desktop（前台，看日志）===
cd /path/to/clawd-on-watch
caffeinate -d -t 300 &   # 防 macOS 睡眠杀 Electron
CLAWD_WATCH_ENABLED=1 CLAWD_WATCH_ADDRESS="<手表BLE地址>" \
  ./node_modules/electron/dist/Electron.app/Contents/MacOS/Electron . \
  > /tmp/clawd_e2e.log 2>&1 &
echo "Desktop PID: $!"

# === 6. 验证（90s 后）===
sleep 90
# Desktop 日志：
grep -iE "theme|synced|error" /tmp/clawd_e2e.log | tail -10
# 期望: "theme sync confirmed: 8399a0 (453 frames)"

# Watch 日志：
adb logcat -d | grep -iE "handleThemeWrite\|ThemeReceiver\|preRecordAll\|assembled"
# 期望: "ThemeReceiver: assembled: clawd (8399a0)" + "preRecordAll: X/14"

# Watch 帧缓存：
adb shell "run-as com.clawd.watch find files/themes -name 'meta.json' | wc -l"
# 最终应该 = 14

# 截图：
adb shell screencap -p /sdcard/test.png && adb pull /sdcard/test.png

# === 7. 断连重连验证 ===
# Watch 端 force-stop（模拟断连）：
adb shell am force-stop com.clawd.watch
sleep 5
adb shell am start -n com.clawd.watch/.MainActivity
# Bridge 应该自动重连（2-30s 延迟），Desktop 日志出现新的 connected
sleep 30
grep "connected" /tmp/clawd_e2e.log | tail -3
```

### 验证清单

| 阶段 | 验证项 | 命令 / 期望 |
|------|--------|------------|
| 连接 | Watch 显示 "Connected" | 截图 |
| 状态同步 | chip 显示 "working" | 截图 |
| 主题传输 | Desktop: `theme sync confirmed: 8399a0 (453 frames)` | grep log |
| SVG 落盘 | 14 个 SVG | `find files/themes -name '*.svg' \| wc -l` = 14 |
| 帧录制 | 14 个 meta.json | `find files/themes -name 'meta.json' \| wc -l` = 14 |
| 帧内容 | 无 UI chrome | 拉一帧 `cat frames/.../010.webp` 看内容 |
| 断连重连 | Bridge 自动重连 | Watch force-stop → 重启 → Desktop log 出现新 connected |
| 缓存命中 | 重启后直接播放，无录制 | Watch force-stop → 重启 → 截图立即显示动画 |

### JS 测试

使用 `node:test` + `node:assert`。测试文件放在 `test/` 目录，命名 `*.test.js`。

预期基线：10 个**既有无关失败**（Claude 版本探测、Kiro Windows 路径、updater），不影响 watch 功能。

## 环境变量

| 变量 | 用途 |
|------|------|
| `CLAWD_WATCH_ENABLED=1` | 启用手表连接 |
| `CLAWD_WATCH_ADDRESS` | 指定手表 BLE 地址（macOS 为 UUID 格式）|
| `CLAWD_WATCH_NAME_PREFIX` | BLE 扫描名称前缀（默认 Clawd）|
| `CLAWD_WATCH_PYTHON` | Python 可执行文件路径 |

## Android 环境要求

- compileSdk 34, minSdk 26, targetSdk 30
- JDK 17（Gradle 8.2 兼容，JDK 25 不行）
- Kotlin 1.9.22, AGP 8.2.0
- 需要 `local.properties` 指定 `sdk.dir`

## 主题指纹

Desktop 和 Watch 用相同算法计算主题指纹：
```
sha256(themeId + ":" + sortedUniqueFiles.join(","))[0..5]
```
Clawd 内置主题指纹 = `8399a0`。跨平台一致性已通过 JS + JVM 交叉验证。

## 提交规范

使用 conventional commits：`feat(watch):`, `fix(watch):`, `refactor(watch):`

Co-Authored-By 行必须包含在提交信息末尾。
