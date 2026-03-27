# air-bridge 프로젝트 기준서

이 파일은 현재 프로젝트의 가장 중요한 기준 문서예요.

다음 세션이 압축된 상태로 시작되면 반드시 이 파일을 먼저 읽고, 그 다음에 `TODO.md`와 각 영역 문서를 읽어야 해요.

## 프로젝트 개요

`air-bridge`는 개인 사용자를 위한 Mac <-> Android 브리지예요.

v1에서 만들려는 핵심 기능은 아래와 같아요.
- Mac과 Android 사이에서 지원 포맷의 클립보드를 동기화해야 해요.
- Android의 사용자 가시 알림을 수집해서 Mac으로 전달해야 해요.
- 모든 민감 데이터는 중계 서버를 거치더라도 종단 간 암호화되어야 해요.

## 확정된 제품 범위

- 사용자 모델은 `1명 사용자 + 1대 Mac + 1대 Android`예요.
- 중계 서버는 개인용 클라우드 환경에 올리는 형태로 가야 해요.
- 암호화 경계는 `서버 블라인드 E2E`로 유지해야 해요.
- macOS는 메뉴바 앱 형태로 만들고, Android 알림은 macOS 시스템 알림으로 미러링해야 해요.
- Android는 네이티브 앱으로 만들고, 페어링, 알림 수집, 클립보드 송수신을 맡아야 해요.

## 클립보드 범위

v1 공식 지원 포맷은 아래와 같아요.
- `text/plain`
- `text/uri-list`
- `text/html`
- `text/rtf`
- `image/png`
- `image/jpeg`

v1에서 제외하는 범위는 아래와 같아요.
- 파일 참조
- 플랫폼 전용 opaque UTI/MIME blob
- 임의의 서드파티 앱 네이티브 포맷의 완전 복원

클립보드 처리 원칙은 아래와 같아요.
- 정규화된 payload 최대 크기는 `20 MB`여야 해요.
- 지원하지 않는 포맷은 기기에서 명시적으로 실패 처리해야 해요.
- 사용자가 모르게 품질이 떨어지는 숨은 변환은 하지 말아야 해요.

Android 제약도 이미 확정되어 있어요.
- Android 10 이상에서는 일반 앱이 백그라운드에서 클립보드를 읽을 수 없어요.
- 그래서 `Android -> Mac` 방향은 앱이 전경에 있을 때 자동 감시하거나, 수동 전송 버튼으로 보내야 해요.
- `Mac -> Android` 방향은 자동 반영을 유지할게요.

참고 문서는 아래예요.
- [Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes)
- [Android copy and paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)

## 알림 범위

- Android는 `posted`, `updated`, `removed` 이벤트를 Mac으로 보내야 해요.
- Mac은 이를 macOS 로컬 알림으로 미러링해야 해요.
- `air-bridge` 자체 알림과 foreground service 성격의 잡음 알림은 제외해야 해요.
- 알림 액션, 답장, 검색 가능한 히스토리는 v1 범위에서 제외할게요.

## 보안 모델

- 페어링은 QR 기반으로 시작하고, Android가 참여하면 즉시 완료되어야 해요.
- 키 합의는 `X25519`를 사용해야 해요.
- 각 기기의 공개키는 `32 byte X25519 public key`여야 해요.
- 키 파생은 `HKDF-SHA256`을 사용해야 해요.
- payload 암호화는 `AES-256-GCM`을 사용해야 해요.
- 전송 경로는 `HTTPS + WebSocket`으로 구성해야 해요.
- 중계 서버는 암호문과 최소 전달 메타데이터만 저장해야 해요.
- 로그에는 평문 payload나 raw ciphertext dump를 남기면 안 돼요.

## 현재 서버 계약

중계 서버는 아래 역할을 맡아야 해요.
- 페어링 세션을 만들고 조회해야 해요.
- 페어링된 기기와 릴레이 인증 토큰을 관리해야 해요.
- 온라인 상태의 기기에 WebSocket으로 실시간 전달해야 해요.
- 오프라인 기기를 위해 암호화된 envelope를 큐에 저장해야 해요.
- 전달 확인과 만료 정리를 처리해야 해요.
- WebSocket 인증은 페어링 완료 전에는 허용하면 안 돼요.

현재 기준 라우트는 아래와 같아요.
- `GET /healthz`
- `POST /api/v1/pairing/sessions`
- `POST /api/v1/pairing/sessions/{sessionID}/lookup`
- `POST /api/v1/pairing/sessions/{sessionID}/join`
- `GET /api/v1/ws`

현재 서버는 아래 입력 제한도 함께 적용해야 해요.
- `device_name`, `pairing_secret`는 길이 제한을 둬야 해요.
- pairing 관련 HTTP JSON 본문은 서버 상한 안에서만 받아야 해요.
- `content_type`, `nonce`, `header_aad`, `ciphertext`는 서버 측 최대 크기를 넘기면 안 돼요.
- WebSocket 클라이언트 메시지도 서버 최대 크기를 넘기면 연결을 끊어야 해요.

## 저장소 구조

- `android/`: Android 앱 문서와 이후 구현물
- `mac/`: macOS 앱 문서와 이후 구현물
- `server/`: 릴레이 서버 코드와 문서

## 현재 작업 우선순위

1. 릴레이 서버를 먼저 개발해야 해요.
2. Android 앱을 그 다음에 개발해야 해요.
3. macOS 앱은 마지막으로 붙여야 해요.

## 네이밍 원칙

이 저장소에서 만드는 모든 이름은 실제 프로덕션 코드라고 생각하고 정해야 해요.

지켜야 할 원칙은 아래와 같아요.
- 의미가 분명하고 오래 버틸 이름을 사용해야 해요.
- 임시 표현보다 도메인 용어를 우선해야 해요.
- 공개 JSON 키, 라우트 이름, 설정 키는 쉽게 바꾸지 않을 안정적인 이름이어야 해요.
- 업계 표준이 아닌 애매한 축약어는 피해야 해요.
