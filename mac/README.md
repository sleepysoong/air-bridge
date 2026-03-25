# macOS 앱 안내

이 폴더는 macOS 클라이언트 구현 기준을 담는 곳이에요.

현재는 relay 서버를 먼저 만드는 단계라서 아직 앱 코드는 없어요. 대신 macOS 쪽 책임과 구조를 먼저 문서로 고정해서 이후 구현 때 흔들리지 않게 할게요.

## 이 영역의 역할

macOS 앱은 아래 책임을 맡아야 해요.
- 메뉴바 앱 형태로 상시 동작해야 해요.
- Android와 페어링을 진행하고, 키와 relay 인증 정보를 Keychain에 보관해야 해요.
- `NSPasteboard`를 감시해서 지원 포맷을 canonical payload로 만들어 전송해야 해요.
- Android에서 전달된 알림 이벤트를 macOS 로컬 알림으로 보여줘야 해요.
- Android에서 온 클립보드 payload를 macOS pasteboard에 반영해야 해요.

## 현재 상태

- 구현 상태: 아직 프로젝트 생성 전이에요.
- 우선순위: Android 이후에 붙일 예정이에요.
- UI 방향: 메뉴바 중심, 시스템 알림 미러링 중심으로 가야 해요.

## 먼저 읽어야 할 문서

1. `../project.md`
2. `../TODO.md`
3. `./architecture.md`

## macOS에서 꼭 기억해야 할 결정

- macOS는 메뉴바 앱 중심으로 가야 해요.
- Android 알림은 `UNUserNotificationCenter`로 미러링해야 해요.
- 클립보드 반사 루프를 막기 위해 origin tag와 synthetic write guard가 필요해요.
- v1에서는 히스토리 검색 UI보다 안정적인 송수신이 우선이에요.
