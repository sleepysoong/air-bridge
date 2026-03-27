# Android 아키텍처

이 문서는 Android 앱의 설계와 구현 구조를 설명해요.

## 현재 구현 상태

Android 앱은 주요 기능이 이미 구현되어 있어요:
- Jetpack Compose 기반 UI
- QR 스캔 페어링 플로우
- X25519 키 합의 + HKDF + AES-256-GCM 암호화
- Relay HTTP/WebSocket 클라이언트
- Foreground Service 기반 브리지 런타임
- NotificationListenerService 알림 수집
- 전경 클립보드 모니터링 (1.5초 폴링)
- Mac→Android 클립보드 자동 적용
- 클립보드 루프 방지
- 서버 입력 제한 클라이언트 검증
- QR 파싱, 암호화, Relay 매핑, 클립보드 동기화 단위 테스트

## Android 앱이 맡는 책임

- 사용자가 Mac과 Android를 페어링할 수 있어야 해요
- Android 알림을 수집해서 relay 서버로 보내야 해요
- Android에서 읽을 수 있는 범위 안에서 클립보드를 수집해야 해요
- Mac에서 온 클립보드 payload를 Android 클립보드에 적용해야 해요
- 암호화 키, relay token, 기기 식별자를 안전하게 저장해야 해요

## 플랫폼 제약

- Android 10 이상에서는 일반 앱이 백그라운드에서 클립보드를 읽을 수 없어요
- 그래서 `Android -> Mac` 자동 동기화는 앱이 전경에 있을 때만 허용해야 해요
- 백그라운드 상황에서는 사용자가 수동으로 "지금 클립보드 보내기"를 눌러야 해요
- Android 12 이상에서는 클립보드 접근 시 시스템 토스트가 보일 수 있어요. 이건 운영체제 정책으로 받아들여야 해요

## 실제 패키지 구조

```text
android/
├── README.md
├── architecture.md
├── settings.gradle.kts
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/airbridge/app/
            ├── app/
            │   ├── AirBridgeApplication.kt
            │   └── AppContainer.kt
            ├── feature/
            │   ├── pairing/
            │   │   ├── PairingScreen.kt
            │   │   ├── PairingViewModel.kt
            │   │   ├── PairingRepository.kt
            │   │   └── PairingQrParser.kt
            │   ├── clipboard/
            │   │   ├── ClipboardSyncCoordinator.kt
            │   │   ├── ClipboardReadGateway.kt (Android implementation)
            │   │   ├── ClipboardApplyGateway.kt (Android implementation)
            │   │   └── ClipboardFormats.kt
            │   ├── notification/
            │   │   ├── AirBridgeNotificationListenerService.kt
            │   │   ├── NotificationForwarder.kt
            │   │   ├── NotificationPayloadNormalizer.kt
            │   │   └── NotificationNoiseFilter.kt
            │   ├── service/
            │   │   ├── AirBridgeRelayForegroundService.kt
            │   │   ├── BridgeRuntime.kt
            │   │   └── BridgeForegroundNotificationFactory.kt
            │   └── common/
            │       ├── BridgeFeatureRegistry.kt
            │       └── [공통 계약 인터페이스들]
            ├── data/
            │   ├── crypto/
            │   │   ├── SessionKeyStore.kt
            │   │   ├── EnvelopeCipher.kt
            │   │   └── Base64Utils.kt
            │   ├── relay/
            │   │   ├── RelayHttpClient.kt
            │   │   ├── RelayWebSocketClient.kt
            │   │   ├── RelayMessageMapper.kt
            │   │   └── RelayServerLimits.kt
            │   └── storage/
            │       ├── SecurePreferencesStore.kt
            │       ├── DeviceIdentityStore.kt
            │       └── RelayCredentialStore.kt
            ├── domain/
            │   ├── ClipboardPayload.kt
            │   ├── NotificationPayload.kt
            │   ├── PairingModels.kt
            │   ├── BridgeEnvelope.kt
            │   └── BridgeChannel.kt
            ├── ui/theme/
            │   ├── Color.kt
            │   └── Theme.kt
            └── MainActivity.kt
```

## 계층별 역할

### `app/`

- `AirBridgeApplication`: 앱 부트스트랩, AppContainer 초기화
- `AppContainer`: 수동 의존성 주입 컨테이너, 모든 주요 컴포넌트 조립
- `MainActivity`: Compose UI 진입점, QR 스캔, 권한 요청 플로우

### `feature/pairing`

- `PairingScreen`: Compose UI, 페어링 상태 표시
- `PairingViewModel`: 페어링 플로우 상태 관리
- `PairingRepository`: Relay HTTP API 호출 (join, lookup)
- `PairingQrParser`: QR JSON/URI 파싱

### `feature/clipboard`

- `ClipboardSyncCoordinator`: 전경 자동 모니터링 + 수동 전송 조율, 루프 방지 (`lastAppliedFingerprint`, `lastSentFingerprint`)
- `AndroidClipboardReadGateway`: Android 클립보드 읽기, MIME 타입 정규화
- `AndroidClipboardApplyGateway`: Mac→Android 클립보드 적용, ContentProvider 기반 바이너리 캐시
- `ClipboardFormats`: 지원 MIME 타입 상수

### `feature/notification`

- `AirBridgeNotificationListenerService`: Android NotificationListenerService 구현
- `NotificationForwarder`: posted/updated/removed 이벤트 전달
- `NotificationPayloadNormalizer`: 알림 정규화
- `NotificationNoiseFilter`: self/service 알림 필터링

### `feature/service`

- `AirBridgeRelayForegroundService`: Foreground Service 앵커 (START_STICKY)
- `BridgeRuntime`: WebSocket 연결 루프, 송수신, 암복호화, ack 처리
- `BridgeForegroundNotificationFactory`: Foreground notification 생성

### `data/crypto`

- `SessionKeyStore`: X25519 키 생성, ECDH 공유 비밀, HKDF-SHA256
- `EnvelopeCipher`: AES-256-GCM 암복호화, 서버 입력 제한 검증

### `data/relay`

- `RelayHttpClient`: Pairing HTTP API (join, lookup, complete)
- `RelayWebSocketClient`: WebSocket 연결, 메시지 송수신
- `RelayMessageMapper`: Wire ↔ Domain 변환
- `RelayServerLimits`: 서버 입력 제한 상수 (device_name 128자, ciphertext 20MiB+16bytes 등)

### `data/storage`

- `SecurePreferencesStore`: Android Keystore 기반 AES-GCM 암호화 SharedPreferences
- `DeviceIdentityStore`: 로컬 기기 ID + X25519 비밀키 저장
- `RelayCredentialStore`: Pairing session ID, device ID, relay token, peer public key 저장

### `domain/`

- `ClipboardPayload`: 정규화된 클립보드 payload (text, html, rtf, image/png, image/jpeg, uri-list)
- `NotificationPayload`: 정규화된 알림 payload (package, app, title, text, timestamp, ongoing)
- `PairingModels`: 페어링 세션, 기기 신원, relay 인증 정보 모델
- `BridgeEnvelope`: 암호화 envelope (channel, content_type, nonce, header_aad, ciphertext)
- `BridgeChannel`: CLIPBOARD, NOTIFICATION enum

## 데이터 플로우

### 페어링 플로우 (Android Joiner)

1. Mac이 QR 생성 → Android가 `PairingQrParser`로 파싱
2. `PairingRepository.joinPairing()` → Relay `POST /join` 호출
   - device_name 128자 검증
   - pairing_secret 128자 검증
3. X25519 키 생성, join 직후 credentials 저장 (DeviceIdentityStore, RelayCredentialStore)
4. relay 브리지를 바로 시작해요

### 클립보드 플로우 (Android → Mac)

1. `MainActivity.onStart()` → `ClipboardSyncCoordinator.startForegroundMonitoring()`
2. 1.5초마다 `ClipboardReadGateway.readCurrentClipboard()`
3. Fingerprint 계산, `lastSentFingerprint`/`lastAppliedFingerprint`와 비교
4. 새 클립보드면 `BridgeRuntime.publishClipboard()`
5. `EnvelopeCipher.encrypt()` → content_type/nonce/header_aad/ciphertext 검증
6. `RelayWebSocketClient.sendEnvelope()` → Relay 전송

### 클립보드 플로우 (Mac → Android)

1. Relay → `BridgeRuntime` WebSocket 수신
2. `EnvelopeCipher.decrypt()`
3. `ClipboardSyncCoordinator.applyRemoteClipboard()` (루프 방지)
4. `ClipboardApplyGateway.apply()` → Android 클립보드 적용
5. `lastAppliedFingerprint` 업데이트 → 재전송 방지

### 알림 플로우 (Android → Mac)

1. `AirBridgeNotificationListenerService.onNotificationPosted()`
2. `NotificationNoiseFilter` → self/service 필터링
3. `NotificationPayloadNormalizer` → 정규화
4. `NotificationForwarder.publishNotification()`
5. `BridgeRuntime` → 암호화 → Relay 전송

## 서버 API 사용 방법

Android는 아래 흐름을 따라야 해요.
### 1. 페어링 참여 (현재 구현됨)

QR 스캔으로 얻은 정보:
- `pairing_session_id`
- `pairing_secret`
- relay base URL
- initiator public key

`RelayHttpClient.joinPairingSession()` 호출:

```http
POST /api/v1/pairing/sessions/{sessionID}/join
Content-Type: application/json

{
  "pairing_secret": "prs_xxx",
  "device_name": "pixel-android",
  "platform": "android",
  "public_key": "<base64>"
}
```

응답:
- `joiner_device_id`
- `joiner_relay_token`
- `initiator_device_id`
- `initiator_public_key`

### 2. WebSocket 연결 (현재 구현됨)

`RelayWebSocketClient.connect()`:

```text
GET /api/v1/ws?device_id=dev_xxx&relay_token=rt_xxx
```

수신 메시지:
- `connected`: 연결 성공
- `envelope`: 암호화된 payload (clipboard 또는 notification)
- `pong`: ping 응답
- `error`: 서버 오류

송신 메시지:
- `ping`: 연결 유지
- `send_envelope`: 암호화 envelope 전송
- `ack_envelope`: 수신 확인

## 서버 입력 제한 (클라이언트 검증 구현됨)

`RelayServerLimits.kt`에 정의된 제한값:

- `device_name`: 최대 128자
- `pairing_secret`: 최대 128자
- `content_type`: 최대 255바이트
- `nonce`: 최대 64바이트
- `header_aad`: 최대 16 KiB
- `ciphertext`: 최대 20 MiB + 16 bytes (GCM tag)
- WebSocket 메시지: 최대 28 MiB

`RelayHttpClient`와 `EnvelopeCipher`에서 서버 전송 전 검증해요.

## Payload 방향

### 알림 전달 (구현됨)

- `NotificationPayloadNormalizer` → `NotificationPayload` 정규화
- JSON 직렬화 + 암호화 → `channel=notification`
- `BridgeRuntime` → Relay 전송

### 클립보드 전달 (구현됨)

- 지원 포맷: text/plain, text/html, text/rtf, text/uri-list, image/png, image/jpeg
- `ClipboardReadGateway` → `ClipboardPayload` 변환
- JSON 직렬화 + 암호화 → `channel=clipboard`
- `BridgeRuntime` → Relay 전송

## 보안 구현

- **키 저장**: `SecurePreferencesStore` (Android Keystore AES-GCM 암호화)
- **키 합의**: X25519 ECDH
- **키 파생**: HKDF-SHA256
- **Payload 암호화**: AES-256-GCM (12-byte nonce, 128-bit tag)
- **전송 보안**: HTTPS + WebSocket (서버 설정에 따라 WSS)

## 알려진 제한사항 및 다음 작업

### 현재 미구현:
- 실제 Gradle/디바이스 환경 빌드 검증
- instrumentation 테스트
- heartbeat/ping 루프 (코드 있지만 미사용)
- ack 재시도 정책
- 송신 큐 영속화 (연결 끊김 시 메시지 유실 가능)
- 부팅 후 자동 복구 (BOOT_COMPLETED receiver 없음)
- NotificationListener 연결 해제 처리

### 버그 수정 완료:
- ✅ 클립보드 루프 방지 (Mac→Android 적용 후 재전송 방지)
- ✅ 서버 입력 제한 클라이언트 검증

## 협업 포인트

- 서버 계약은 `server/architecture.md` 기준을 따라요
- macOS가 기대하는 canonical payload 구조와 Android가 만드는 구조는 완전히 같아야 해요
- Android는 운영체제 제약이 가장 큰 영역이기 때문에, 구현 전마다 제약 검증을 먼저 해야 해요
