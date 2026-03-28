# macOS 앱 안내

이 폴더는 macOS 클라이언트 구현 기준을 담는 곳이에요.

이제 `mac/` 아래에는 실제로 빌드 가능한 메뉴바 앱 코드가 있어요. 현재는 Android와 실사용 플로우를 맞추는 구현까지 포함해서 메뉴바 런타임, 알림 미러링, 클립보드 송수신이 들어가 있어요.

## 이 영역의 역할

macOS 앱은 아래 책임을 맡아야 해요.
- 메뉴바 앱 형태로 상시 동작해야 해요.
- Android와 페어링을 진행하고, 키와 relay 인증 정보를 Keychain에 보관해야 해요.
- `NSPasteboard`를 감시해서 지원 포맷을 canonical payload로 만들어 전송해야 해요.
- Android에서 전달된 알림 이벤트를 macOS 로컬 알림으로 보여줘야 해요.
- Android에서 온 클립보드 payload를 macOS pasteboard에 반영해야 해요.

## 현재 상태

- 구현 상태: Swift Package 기반 macOS 메뉴바 앱이 있어요.
- 앱 형태: `MenuBarExtra` + Settings 화면 구조예요.
- 앱 아트워크: 파란 배경과 흰 구름 기반 새 앱 이미지를 리소스로 같이 관리해요.
- 저장소: Keychain에 pairing session과 relay 관련 비밀값을 저장해요.
- 네트워크: relay HTTP + WebSocket 클라이언트가 실제 서버 계약에 맞게 구현돼 있어요.
- 기능 범위: pairing, clipboard 송수신, Android 알림 미러링, reconnect, local validation이 포함돼요.
- reconnect와 앱 재실행 뒤에도 아직 못 보낸 암호화 clipboard envelope를 다시 보낼 수 있게 local queue를 유지해요.
- Android 알림 미러링은 텍스트뿐 아니라 best-effort 이미지 첨부도 받아서 로컬 알림에 붙여요.
- 다만 macOS 시스템 제약 때문에 원본 Android 앱의 실제 시스템 알림 아이콘으로 위장해서 보여줄 수는 없고, Air Bridge 알림 안에서 앱 이름과 첨부 이미지로 최대한 비슷하게 보여줘요.
- 남은 범위: 실제 E2E 검증, 배포 서명 마감, 알림 첨부 표시 품질 다듬기예요.

## 디렉터리 구조

```text
mac/
├── README.md
├── architecture.md
├── Package.swift
├── AirBridgeMac/
│   ├── App/
│   ├── Data/
│   ├── Domain/
│   └── Feature/
└── Tests/
    └── AirBridgeMacTests/
```

## 로컬 빌드 방법

```bash
cd /Users/sleepysoong/Desktop/air-bridge
swift build --package-path mac
```

실행은 아래처럼 하면 돼요.

```bash
cd /Users/sleepysoong/Desktop/air-bridge
swift run --package-path mac
```

테스트 코드는 `mac/Tests/AirBridgeMacTests/` 아래에 있지만, 현재 이 환경처럼 full Xcode가 아닌 Command Line Tools만 잡힌 경우에는 `XCTest`가 빠져 있어서 `swift test`를 바로 실행할 수 없을 수 있어요.

## 먼저 읽어야 할 문서

1. `../project.md`
2. `../TODO.md`
3. `./architecture.md`

## macOS에서 꼭 기억해야 할 결정

- macOS는 메뉴바 앱 중심으로 가야 해요.
- Android 알림은 `UNUserNotificationCenter`로 미러링해야 해요.
- 클립보드 반사 루프를 막기 위해 origin tag와 synthetic write guard가 필요해요.
- v1에서는 히스토리 검색 UI보다 안정적인 송수신이 우선이에요.
- relay 코드 기준 pairing lookup 경로는 `POST /api/v1/pairing/sessions/{sessionID}/lookup`이에요.
- 페어링은 QR 스캔 뒤 즉시 완료되는 흐름을 유지해야 해요.
