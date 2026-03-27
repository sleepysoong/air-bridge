# Android 앱 안내

이 폴더는 Android 클라이언트 구현을 담고 있어요.

## 현재 구현 상태

Android 앱은 이미 주요 기능이 구현되어 있어요:
- ✅ Jetpack Compose UI
- ✅ QR 기반 즉시 페어링
- ✅ X25519/HKDF-SHA256/AES-256-GCM 종단간 암호화
- ✅ 안전한 키 저장소 (Android Keystore 기반 암호화)
- ✅ Relay HTTP/WebSocket 클라이언트
- ✅ NotificationListenerService를 통한 알림 수집
- ✅ Foreground Service 기반 브리지 런타임
- ✅ 전경 클립보드 모니터링 (1.5초 폴링)
- ✅ Mac→Android 클립보드 자동 적용
- ✅ 클립보드 루프 방지 로직
- ✅ 서버 입력 제한 클라이언트 검증

## 이 영역의 역할

Android 앱은 아래 책임을 맡아요:
- Mac과의 페어링을 QR 스캔으로 참여하고 완료해요
- Android 알림을 수집해서 암호화한 뒤 relay 서버로 보내요
- Android 클립보드를 전경에서만 안전하게 수집해요
- Mac에서 온 클립보드 payload를 Android 클립보드에 반영해요
- 키와 relay 인증 정보를 안전한 저장소에 보관해요

## 먼저 읽어야 할 문서

1. `../project.md`
2. `../TODO.md`
3. `./architecture.md`

## Android에서 꼭 기억해야 할 결정

- 백그라운드 상시 클립보드 감시는 v1 범위에서 불가능해요
- 따라서 `Android -> Mac` 방향은 전경 감시(1.5초 폴링) 또는 수동 전송이어요
- Android 알림은 `NotificationListenerService`로 수집해요
- 지원 클립보드 포맷은 공통 교집합 포맷만 다뤄요
- payload는 기기에서 암호화한 뒤 relay로 보내요

## 프로젝트 구조

```
android/
├── README.md
├── architecture.md
├── settings.gradle.kts
└── app/
    ├── build.gradle.kts
    ├── src/main/
    │   ├── AndroidManifest.xml
    │   └── java/com/airbridge/app/
    │       ├── app/                  # 앱 부트스트랩, DI
    │       ├── feature/              # UI 및 기능 모듈
    │       │   ├── pairing/          # 페어링 플로우
    │       │   ├── clipboard/        # 클립보드 동기화
    │       │   ├── notification/     # 알림 수집
    │       │   ├── service/          # 포어그라운드 서비스
    │       │   └── common/           # 공통 계약
    │       ├── data/                 # 데이터 계층
    │       │   ├── crypto/           # 암호화 구현
    │       │   ├── relay/            # Relay HTTP/WebSocket
    │       │   └── storage/          # 안전한 저장소
    │       ├── domain/               # 도메인 모델
    │       └── ui/                   # UI 테마
    └── src/test/                     # 핵심 로직 단위 테스트
```

## 다음 작업

- Gradle/디바이스 환경에서 실제 빌드와 테스트 검증
- heartbeat/재시도 정책 구현
- 부팅 후 자동 복구 구현
- NotificationListener 연결 해제 처리
