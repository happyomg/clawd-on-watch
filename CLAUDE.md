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
       │ GATT over BLE
       ▼
  CWD1: State {s,n,th}      Desktop → Watch
  CWD2: Approval Request    Desktop → Watch
  CWD3: Approval Response   Watch → Desktop (notify)
  CWD4: Meta + themeHash    Watch → Desktop (read)
  CWD5: Theme Data          Desktop → Watch (chunks)
```

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

## 测试规范

### 必须走真实流程测试，禁止模拟

**严禁**用 `adb shell input tap` 或手动 `adb install` 来代替端到端 BLE 流程。测试主题同步的正确流程：

1. **启动 Desktop**：`CLAWD_WATCH_ENABLED=1 npm start`
2. **确认 Watch 广播中**：手表 app 启动后 BleService 开始广播
3. **Desktop 自动连接**：bridge 扫描到手表 → 连接 → 读 CWD4 (含 themeHash)
4. **对比 hash**：Desktop 活跃主题 hash vs Watch 上报 hash
5. **不一致时触发同步**：Desktop 通过 CWD5 推送 SVG 文件
6. **Watch 接收 + 预录制**：ThemeReceiver 组装 → ThemeConfig 激活 → PetView.preRecordAll 录制全部状态帧
7. **验证**：所有 16 个状态有 meta.json + webp 帧、截图显示动画

### 验证清单

```bash
# Watch 帧缓存
adb shell "run-as com.clawd.watch find files/themes -name 'meta.json' | wc -l"
# 应该 = 16 (Clawd 主题有 16 个 SVG)

# 无崩溃
adb logcat -d | grep -i "FATAL\|AndroidRuntime" | grep clawd

# 截图
adb shell screencap -p /sdcard/test.png && adb pull /sdcard/test.png
```

### JS 测试

使用 `node:test` + `node:assert`。测试文件放在 `test/` 目录，命名 `*.test.js`。

预期基线：10 个**既有无关失败**（Claude 版本探测、Kiro Windows 路径、updater），不影响 watch 功能。

## 环境变量

| 变量 | 用途 |
|------|------|
| `CLAWD_WATCH_ENABLED=1` | 启用手表连接 |
| `CLAWD_WATCH_ADDRESS` | 指定手表 BLE 地址 |
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
Clawd 内置主题指纹 = `79c952`。跨平台一致性已通过 JS + JVM 交叉验证。

## 提交规范

使用 conventional commits：`feat(watch):`, `fix(watch):`, `refactor(watch):`

Co-Authored-By 行必须包含在提交信息末尾。
