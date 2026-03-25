# TODO

새 세션이 열리면 `project.md`를 먼저 읽어야 해요.

## Relay Server

- [x] `server/` 기본 구조를 만들었어요.
- [x] 프로덕션 기준 네이밍, 설정 키, 라우트 골격을 잡았어요.
- [x] SQLite 기반 저장소를 추가해서 기기, 페어링 세션, 암호화 envelope를 저장할 수 있게 했어요.
- [x] 페어링 세션 생성, 조회, 참여, 완료 HTTP API를 만들었어요.
- [x] 인증된 WebSocket 진입점을 추가했어요.
- [x] 암호화 envelope 큐와 acknowledgment 흐름을 넣었어요.
- [x] 페어링, 인증, 큐 전달, 만료 정리에 대한 자동화 테스트를 추가했어요.
- [ ] SAS 확인 단계를 클라이언트와 함께 맞출 수 있도록 문서와 인터페이스를 더 구체화해야 해요.
- [ ] rate limiting, metrics, 운영 로그 정책을 보강해야 해요.
- [ ] relay token 회전과 기기 revoke 흐름을 추가해야 해요.
- [x] 개인용 클라우드 배포용 최소 배포 자산을 추가했어요.

## Android App

- [ ] `android/` 아래에 네이티브 Android 프로젝트를 만들어야 해요.
- [ ] 페어링 화면과 안전한 키 저장소를 붙여야 해요.
- [ ] `NotificationListenerService`를 구현해야 해요.
- [ ] 전경 감시 기반 클립보드 수집과 수동 전송 버튼을 구현해야 해요.
- [ ] 수신한 클립보드 payload를 Android 클립보드에 적용해야 해요.

## macOS App

- [x] `mac/` 아래에 네이티브 macOS 메뉴바 프로젝트를 만들었어요.
- [x] 페어링 화면과 Keychain 저장을 붙였어요.
- [x] `NSPasteboard` 감시와 canonical clipboard payload 송신을 구현했어요.
- [x] Android 알림을 `UNUserNotificationCenter`로 미러링하게 만들었어요.
- [x] 수신한 클립보드 payload를 macOS pasteboard에 적용하게 만들었어요.
- [ ] Android와 SAS/payload 계약을 최종 확정하고 실제 E2E 검증을 해야 해요.
