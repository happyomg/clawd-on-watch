<p align="center">
  <img src="assets/tray-icon.png" width="128" alt="Clawd">
</p>
<h1 align="center">Clawd on Watch</h1>
<p align="center">
  <strong>데스크톱 펫 + Wear OS 워치 컴패니언</strong><br>
  <sub><a href="https://github.com/rullerzhou-afk/clawd-on-desk">clawd-on-desk</a>에서 포크 — <a href="https://github.com/rullerzhou-afk">@rullerzhou-afk</a></sub>
</p>
<p align="center">
  <a href="README.md">English</a>
  ·
  <a href="README.zh-CN.md">中文版</a>
  ·
  <a href="README.zh-TW.md">繁體中文</a>
  ·
  <a href="README.ja-JP.md">日本語</a>
</p>
<p align="center">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux%20%7C%20Wear%20OS-lightgrey" alt="Platform">
</p>

<p align="center">
  <img src="assets/hero.gif" width="360" alt="Clawd on Desk — AI 코딩 에이전트에 실시간으로 반응하는 픽셀 데스크톱 펫">
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/watch/watch-working.png" width="160" alt="Clawd on Watch — Connected 상태와 working 상태 칩이 표시된 Wear OS 컴패니언에서 크랩이 타이핑하는 모습">
</p>

## Clawd on Watch란?

**Clawd on Watch**는 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) 데스크톱 펫을 **Wear OS 스마트워치 컴패니언**으로 확장합니다. 픽셀 크랩이 데스크톱과 손목 위 양쪽에서 살아갑니다 — AI 코딩 에이전트가 생각하거나, 타이핑하거나, 서브에이전트를 돌리기 시작하면 워치가 Bluetooth Low Energy(BLE)를 통해 실시간으로 상태를 미러링합니다.

책상에서 잠시 떠나 손목을 흘끗 보는 것만으로 에이전트가 아직 작업 중인지, 권한을 기다리고 있는지, 아니면 완료됐는지 즉시 알 수 있습니다. 에이전트가 위험한 명령어를 실행해야 할 때 워치가 진동하며 **손목 제스처로 승인 또는 거부**할 수 있습니다 — 터미널로 급히 돌아갈 필요가 없습니다.

> 데스크톱 쪽은 업스트림 clawd-on-desk의 모든 기능을 유지합니다: 14개 애니메이션 상태, 권한 말풍선, 세션 대시보드, 커스텀 테마, 멀티 디스플레이 지원, 그리고 **Claude Code**, **Codex CLI**, **Copilot CLI**, **Gemini CLI**, **Cursor Agent** 및 [기타 에이전트](#데스크톱-기능) 연동.

---

## 워치 컴패니언

### 동작 원리

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

데스크톱은 BLE Central 역할을 하는 Python 사이드카(`watch_buddy_bridge.py`)를 생성합니다. 워치는 BLE Peripheral(GATT Server)로 동작합니다. 상태 스냅샷, 테마 데이터, 권한 요청은 커스텀 GATT 특성값(Characteristic)을 통해 전송됩니다.

### 워치 기능

#### 실시간 상태 동기화
워치는 BLE를 통해 데스크톱 펫의 상태를 미러링하며, 14개의 고유한 애니메이션을 지원합니다: 대기, 생각, 타이핑, 건설, 헤드폰 그루브(서브에이전트 1개), 저글링(서브에이전트 2개 이상), 에러, 기쁨, 알림, 청소, 운반, 수면 등. 하단의 색상 코드 칩이 현재 상태를 표시합니다. 상태가 전환될 때마다 짧은 햅틱 진동이 발생합니다.

#### BLE를 통한 테마 동기화
커스텀 테마가 데스크톱에서 워치로 자동 전송됩니다. SVG 애니메이션 파일이 청크로 분할되어 BLE를 통해 전송되고, 기기 내의 보이지 않는 WebView가 CSS 애니메이션을 프레임별로 캡처하여 캐시된 WebP 시퀀스로 렌더링합니다. 워치는 SHA-256 지문(fingerprint)을 사용하여 오래된 테마를 감지하고 필요할 때만 동기화합니다.

#### 손목에서 권한 승인
AI 에이전트가 도구나 명령어 실행 권한을 요청하면, 워치가 위험도에 따른 차별화된 햅틱 패턴으로 진동합니다:
- **고위험**: 삼중 펄스, 제스처 비활성화 — 버튼만 사용 가능
- **중위험**: 이중 펄스
- **저위험**: 단일 짧은 펄스

세 가지 입력 방법:
1. **버튼**: 거부 / 허용 / 항상 허용
2. **손목 제스처**: 뒤집어서 승인, 흔들어서 거부 (고위험에서는 비활성화)
3. **시간 초과**: 화면이 꺼지거나 타이머가 만료되면 자동 거부

#### 자동 재연결 및 전원 관리
BLE 연결이 끊겨도 점진적 재시도 지연으로 자동 복구됩니다. 30초 워치독(watchdog)이 Central의 무응답 상태를 감지합니다. 워치는 화면이 꺼지면 애니메이션과 제스처 감지를 일시 정지하여 배터리를 절약합니다.

### 워치 데모

<p align="center">
  <img src="assets/watch/watch-working.png" width="200" alt="워치에 Connected 상태와 크랩이 타이핑하는 working 상태 표시">
  <br>
  <sub>Wear OS 워치 위의 Clawd — "Connected" 상태에서 에이전트가 작업하는 동안 크랩이 타이핑</sub>
</p>

### 설정 가이드

#### 사전 요구 사항

| 구성 요소 | 요구 사항 |
|-----------|----------|
| 워치 | BLE를 지원하는 Wear OS 기기 |
| 데스크톱 | macOS (테스트 완료), Windows/Linux (실험적) |
| Python | Python 3.8+ 및 `bleak` 라이브러리 |
| Android SDK | compileSdk 34, JDK 17 |

#### 1. 데스크톱: 워치 모드 활성화

1. Clawd를 실행하고 **설정**을 엽니다 (우클릭 메뉴 또는 트레이 메뉴)
2. **원격 승인** 탭으로 이동합니다 (사이드바 비행기 아이콘)
3. **Watch** 카드를 펼칩니다
4. **"Enable"** 토글을 켭니다 — 백그라운드에서 BLE 브릿지 사이드카가 시작됩니다
5. **"Scan"**을 클릭합니다 — 근처 Wear OS 기기가 목록에 표시됩니다
6. 워치를 클릭합니다 — 데스크톱이 자동으로 연결됩니다
7. 연결되면 상태가 **"Connected: \<기기명\>"**으로 표시됩니다
8. 선택 사항: **"Approval on Watch"** 토글을 켜서 도구 권한 요청을 워치로 전달합니다

> 브릿지에서 `missing_bleak` 오류가 표시되면 오류 힌트의 **"Install bleak"** 버튼을 클릭하면 자동 설치됩니다.

**재연결 단축키**: 이전에 연결한 적이 있다면 기기 주소가 저장되어 있습니다. 활성화 후 **"Reconnect"**를 클릭하면 스캔 없이 바로 연결됩니다.

<details>
<summary>고급: 환경 변수 오버라이드 (개발/CI용)</summary>

```bash
export CLAWD_WATCH_ENABLED=1                      # 강제 활성화 (설정 덮어쓰기)
export CLAWD_WATCH_ADDRESS="<BLE-주소>"             # BLE 주소 (macOS UUID 형식)
export CLAWD_WATCH_NAME_PREFIX="Clawd"             # 스캔 이름 접두사
export CLAWD_WATCH_PYTHON="python3"                # Python 실행 파일
```

</details>

#### 2. 워치: 빌드 및 설치

```bash
cd watch-app/android

# 환경 설정
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=/path/to/jdk17   # JDK 17 필수 (Gradle 8.2)

# 빌드
./gradlew assembleDebug

# 워치에 설치 (ADB로 연결)
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

#### 3. 페어링 및 연결

1. 워치 앱을 실행합니다 — 페어링 모드에 진입하고 BLE 광고(Advertising)를 시작합니다
2. 데스크톱 앱을 `CLAWD_WATCH_ENABLED=1`로 시작합니다
3. 데스크톱이 "Clawd" 접두사를 가진 주변 BLE 기기를 스캔합니다
4. 연결되면 워치에 "Connected"가 표시되고 펫 상태가 동기화되기 시작합니다
5. 데스크톱에 커스텀 테마가 있으면 첫 연결 시 자동으로 워치에 동기화됩니다

#### 4. 확인

```bash
# 워치 로그
adb logcat -s PetView FrameRecorder ThemeConfig BleService

# 다음을 확인:
# BleService: Central connected
# ThemeReceiver: assembled: clawd (8399a0)
# PetView: setState → WORKING
```

---

## 데스크톱 기능

> 업스트림 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk)의 모든 기능이 포함되어 있습니다. 아래는 요약입니다.

### 에이전트 연동

| 에이전트 | 연동 방식 | 권한 말풍선 |
|---------|----------|------------|
| Claude Code | Command hook + HTTP permission hook | 지원 |
| Codex CLI | Official hooks + JSONL 폴백 | 지원 |
| Copilot CLI | Command hooks | 미지원 |
| Gemini CLI | Command hooks (자동 등록) | 미지원 |
| Cursor Agent | IDE hooks (자동 등록) | 미지원 |
| Antigravity CLI | Command hooks (상태만) | 미지원 |
| CodeBuddy | Command hooks + HTTP permission hooks | 지원 |
| Kiro CLI | 커스텀 agent 설정 | 미지원 |
| Kimi Code CLI | TOML을 통한 Command hooks | 미지원 |
| Qwen Code | Command hooks + 권한 요청 | 지원 |
| opencode | 플러그인 연동 | 지원 |
| Pi | 전역 extension (상태만) | 미지원 |
| OpenClaw | 플러그인 연동 (상태만) | 미지원 |
| Hermes Agent | 플러그인 연동 | 미지원 |

### 애니메이션

<table>
  <tr>
    <td align="center"><img src="assets/gif/clawd-idle.gif" width="100"><br><sub>대기</sub></td>
    <td align="center"><img src="assets/gif/clawd-thinking.gif" width="100"><br><sub>생각</sub></td>
    <td align="center"><img src="assets/gif/clawd-typing.gif" width="100"><br><sub>타이핑</sub></td>
    <td align="center"><img src="assets/gif/clawd-building.gif" width="100"><br><sub>건설</sub></td>
    <td align="center"><img src="assets/gif/clawd-headphones-groove.gif" width="100"><br><sub>1개 서브에이전트</sub></td>
    <td align="center"><img src="assets/gif/clawd-juggling.gif" width="100"><br><sub>2+ 서브에이전트</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/calico-idle.gif" width="80"><br><sub>Calico 대기</sub></td>
    <td align="center"><img src="assets/gif/calico-thinking.gif" width="80"><br><sub>Calico 생각</sub></td>
    <td align="center"><img src="assets/gif/calico-typing.gif" width="80"><br><sub>Calico 타이핑</sub></td>
    <td align="center"><img src="assets/gif/calico-building.gif" width="80"><br><sub>Calico 건설</sub></td>
    <td align="center"><img src="assets/gif/calico-juggling.gif" width="80"><br><sub>Calico 저글링</sub></td>
    <td align="center"><img src="assets/gif/calico-conducting.gif" width="80"><br><sub>Calico 지휘</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/gif/cloudling-idle.gif" width="120"><br><sub>Cloudling 대기</sub></td>
    <td align="center"><img src="assets/gif/cloudling-thinking.gif" width="120"><br><sub>Cloudling 생각</sub></td>
    <td align="center"><img src="assets/gif/cloudling-typing.gif" width="120"><br><sub>Cloudling 타이핑</sub></td>
    <td align="center"><img src="assets/gif/cloudling-building.gif" width="120"><br><sub>Cloudling 건설</sub></td>
    <td align="center"><img src="assets/gif/cloudling-juggling.gif" width="120"><br><sub>Cloudling 저글링</sub></td>
    <td align="center"><img src="assets/gif/cloudling-conducting.gif" width="120"><br><sub>Cloudling 지휘</sub></td>
  </tr>
</table>

### 기타 데스크톱 기능

- **권한 말풍선** — 앱 내 권한 검토용 떠다니는 카드로 허용 / 거부 / 항상 허용 및 전역 단축키(`Ctrl+Shift+Y` / `Ctrl+Shift+N`) 지원
- **세션 대시보드 + HUD** — 라이브 세션, 최근 이벤트를 확인하고 터미널로 이동 가능
- **커스텀 테마** — SVG/GIF/APNG 에셋으로 나만의 캐릭터를 만들거나 Codex Pet zip 패키지를 가져오기
- **멀티 디스플레이** — 비례 크기 조정, 세로 모니터 보정, 디스플레이 간 드래그
- **미니 모드** — 화면 가장자리에 숨기고 마우스를 올리면 나타남
- **시선 추적** — 대기 상태에서 Clawd가 커서를 따라봄
- **다국어 지원** — 영어, 중국어 간체, 중국어 번체, 한국어, 일본어
- **자동 업데이트** — GitHub 릴리스에서 새 버전 확인

전체 기능 문서: **[docs/guides/state-mapping.md](docs/guides/state-mapping.md)** | **[docs/guides/setup-guide.md](docs/guides/setup-guide.md)** | **[docs/guides/known-limitations.md](docs/guides/known-limitations.md)**

### 빠른 시작 (데스크톱만)

```bash
git clone https://github.com/happyomg/clawd-on-watch.git
cd clawd-on-watch
npm install
npm start
```

---

## 포크 히스토리

이 프로젝트는 [@rullerzhou-afk](https://github.com/rullerzhou-afk)(鹿鹿)의 [**clawd-on-desk**](https://github.com/rullerzhou-afk/clawd-on-desk)에서 포크되었습니다. 업스트림 프로젝트는 AI 코딩 에이전트에 실시간으로 반응하는 커뮤니티 주도 Electron 데스크톱 펫입니다.

**추가된 기능:**
- Wear OS 컴패니언 앱 (`watch-app/android/`) — BLE Peripheral, SVG 애니메이션 렌더링, 손목 제스처 인식을 포함하는 Kotlin 전체 구현
- Python BLE 브릿지 (`scripts/watch_buddy_bridge.py`) — macOS/Windows/Linux Central 연결을 위한 asyncio + bleak
- 데스크톱 워치 어댑터 (`src/watch-adapter.js`, `src/watch-controller.js`, `src/watch-sidecar-client.js`) — 사이드카 라이프사이클, 상태 푸시, 테마 동기화, 권한 릴레이를 관리
- 테마 전송 프로토콜 — 증분 동기화를 위한 SHA-256 지문이 적용된 청크 SVG-over-BLE

### 업스트림 동기화

`upstream` 리모트가 이미 설정되어 있습니다. 원본 clawd-on-desk의 새 기능이나 수정 사항을 가져오려면:

```bash
git fetch upstream
git checkout main
git merge upstream/main
# 충돌이 있으면 해결한 다음:
git push origin main
```

**충돌 발생 가능 파일**: `README*.md`, `package.json`, `src/main.js` (워치 어댑터 초기화 코드 위치). 워치 전용 파일 (`watch-app/`, `scripts/watch_buddy_bridge.py`, `src/watch-*.js`)은 업스트림에 존재하지 않으므로 충돌하지 않습니다.

## 기여하기

버그 리포트, 기능 아이디어, 풀 리퀘스트 모두 환영합니다 — [이슈](https://github.com/happyomg/clawd-on-watch/issues)를 열거나 PR을 직접 보내 주세요.

### 업스트림 메인테이너

<table>
  <tr>
    <td align="center" valign="top" width="140"><a href="https://github.com/rullerzhou-afk"><img src="https://github.com/rullerzhou-afk.png" width="72" style="border-radius:50%" /><br /><sub><b>@rullerzhou-afk</b><br />鹿鹿 · 제작자</sub></a></td>
    <td align="center" valign="top" width="140"><a href="https://github.com/YOIMIYA66"><img src="https://github.com/YOIMIYA66.png" width="72" style="border-radius:50%" /><br /><sub><b>@YOIMIYA66</b><br />메인테이너</sub></a></td>
  </tr>
</table>

### 기여자

Clawd를 더 좋게 만드는 데 도움을 준 모든 분들께 감사합니다:

<details>
<summary>기여자 50명 모두 보기</summary>

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

## 감사의 말

- 업스트림 프로젝트 [clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) — [@rullerzhou-afk](https://github.com/rullerzhou-afk) (鹿鹿)
- Clawd 픽셀 아트 참고: [clawd-tank](https://github.com/marciogranzotto/clawd-tank) — [@marciogranzotto](https://github.com/marciogranzotto)
- [LINUX DO](https://linux.do/) 커뮤니티에서 공유됨

## 라이선스

소스 코드는 [GNU Affero General Public License v3.0](LICENSE)(AGPL-3.0)로 배포됩니다.

**아트워크와 번들 테마 에셋(`assets/` 및 `themes/*/assets/` 포함)은 AGPL-3.0 라이선스 대상이 아닙니다.** 각 저작권자의 권리가 유지되며 자세한 내용은 [assets/LICENSE](assets/LICENSE)와 아래 고지를 참고하세요.

- **Clawd** 캐릭터는 [Anthropic](https://www.anthropic.com)의 자산입니다. 이 프로젝트는 비공식 팬 프로젝트이며 Anthropic과 제휴하거나 승인받지 않았습니다.
- **Calico cat (삼색 고양이)** 아트워크는 鹿鹿([@rullerzhou-afk](https://github.com/rullerzhou-afk))의 작품이며, 모든 권리를 보유합니다.
- **Cloudling (云宝)** 아트워크는 鹿鹿([@rullerzhou-afk](https://github.com/rullerzhou-afk))의 작품이며, 모든 권리를 보유합니다. Cloudling의 시각 방향에는 OpenAI Codex 로고에 대한 오마주가 포함되어 있습니다. Codex/OpenAI 관련 표장은 OpenAI의 자산이며, 이 프로젝트는 OpenAI와 제휴하거나 승인받지 않았습니다.
- **서드파티 기여물**: 저작권은 각 아티스트에게 유지됩니다.

**암호화폐 없음.** 이 프로젝트에는 토큰, 코인, NFT, 에어드롭이 없으며 어떤 암호화폐 프로젝트와도 관련이 없습니다.
