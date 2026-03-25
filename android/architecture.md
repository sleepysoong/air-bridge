# Android 아키텍처

이 문서는 Android 앱 구현을 시작할 때 기준이 되는 설계 문서예요.

현재 Android 코드는 아직 없지만, 나중에 여러 개발자가 동시에 들어와도 흔들리지 않게 역할, 제약, 파일 구조, 서버 연동 방법을 먼저 고정해둘게요.

## Android 앱이 맡아야 할 책임

- 사용자가 Mac과 Android를 페어링할 수 있어야 해요.
- Android 알림을 수집해서 relay 서버로 보내야 해요.
- Android에서 읽을 수 있는 범위 안에서 클립보드를 수집해야 해요.
- Mac에서 온 클립보드 payload를 Android 클립보드에 적용해야 해요.
- 암호화 키, relay token, 기기 식별자를 안전하게 저장해야 해요.

## 플랫폼 제약

- Android 10 이상에서는 일반 앱이 백그라운드에서 클립보드를 읽을 수 없어요.
- 그래서 `Android -> Mac` 자동 동기화는 앱이 전경에 있을 때만 허용해야 해요.
- 백그라운드 상황에서는 사용자가 수동으로 "지금 클립보드 보내기"를 눌러야 해요.
- Android 12 이상에서는 클립보드 접근 시 시스템 토스트가 보일 수 있어요. 이건 운영체제 정책으로 받아들여야 해요.

## 권장 패키지 구조

아직 생성되진 않았지만 아래 구조로 가야 해요.

```text
android/
├── README.md
├── architecture.md
└── app/
    └── src/main/java/com/airbridge/app/
        ├── app/
        │   ├── AirBridgeApplication.kt
        │   └── AppContainer.kt
        ├── feature/pairing/
        │   ├── PairingActivity.kt
        │   ├── PairingViewModel.kt
        │   └── PairingRepository.kt
        ├── feature/clipboard/
        │   ├── ClipboardSyncCoordinator.kt
        │   ├── ClipboardReadGateway.kt
        │   └── ClipboardApplyGateway.kt
        ├── feature/notification/
        │   ├── AirBridgeNotificationListenerService.kt
        │   └── NotificationForwarder.kt
        ├── data/crypto/
        │   ├── SessionKeyStore.kt
        │   └── EnvelopeCipher.kt
        ├── data/relay/
        │   ├── RelayHttpClient.kt
        │   ├── RelayWebSocketClient.kt
        │   └── RelayMessageMapper.kt
        ├── data/storage/
        │   ├── DeviceIdentityStore.kt
        │   └── RelayCredentialStore.kt
        └── domain/
            ├── ClipboardPayload.kt
            ├── NotificationPayload.kt
            └── PairingModels.kt
```

## 계층별 역할

### `feature/pairing`

- QR 스캔과 세션 참여를 처리해야 해요.
- relay 서버의 pairing API를 호출해야 해요.
- X25519 공개키 교환과 SAS 확인에 필요한 UI 상태를 관리해야 해요.

### `feature/clipboard`

- Android 전경 상태에서만 클립보드 자동 감시를 켜야 해요.
- 수동 전송 버튼도 같은 coordinator를 통해 동작해야 해요.
- 수신 payload를 Android 클립보드에 쓸 때는 로컬 반사 루프를 막을 장치가 있어야 해요.

### `feature/notification`

- `NotificationListenerService`를 사용해서 `posted`, `updated`, `removed` 이벤트를 잡아야 해요.
- 패키지명, 앱 이름, 제목, 본문, timestamp, ongoing 여부를 정규화해서 보내야 해요.
- `air-bridge` 자체 알림과 foreground service 잡음은 여기서 필터링해야 해요.

### `data/crypto`

- Android Keystore와 encrypted storage를 사용해서 relay token과 세션 키를 저장해야 해요.
- 실제 payload 암호화는 `AES-256-GCM`이어야 해요.
- relay로 나가는 JSON이나 WebSocket 메시지에는 평문 payload를 넣지 말아야 해요.

### `data/relay`

- pairing용 HTTP 호출과 실시간 전달용 WebSocket 연결을 분리해야 해요.
- 서버 API 필드명과 Android 내부 모델을 분리해서 mapper를 둬야 해요.
- 네트워크 오류, reconnect, ack 재시도 정책을 여기서 다뤄야 해요.

## 서버 API 사용 방법

Android는 아래 흐름을 따라야 해요.

### 1. 페어링 참여

Mac이 만든 QR에서 아래 정보를 얻어야 해요.
- `pairing_session_id`
- `pairing_secret`
- relay base URL
- Mac public key

그 다음 아래 API를 호출해야 해요.

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

응답으로 아래 값을 받아야 해요.
- `joiner_device_id`
- `joiner_relay_token`
- `initiator_device_id`
- `initiator_public_key`

### 2. 페어링 상태 확인

```http
POST /api/v1/pairing/sessions/{sessionID}/lookup
Content-Type: application/json

{
  "pairing_secret": "prs_xxx"
}
```

이 응답을 바탕으로 SAS 확인에 필요한 상대 기기 정보를 보여줘야 해요.

### 3. WebSocket 연결

```text
GET /api/v1/ws?device_id=dev_xxx&relay_token=rt_xxx
```

연결 후 받을 수 있는 메시지는 아래와 같아요.
- `connected`
- `envelope`
- `pong`
- `error`

Android가 보낼 수 있는 메시지는 아래와 같아요.
- `ping`
- `send_envelope`
- `ack_envelope`

## 서버 입력 제한 메모

Android 네트워크 계층은 아래 상한을 클라이언트에서도 알고 있어야 해요.

- pairing 관련 HTTP JSON 본문은 최대 `16 KiB`예요.
- `device_name`과 `pairing_secret`는 서버 길이 제한을 넘기면 안 돼요.
- `content_type`은 최대 `255`바이트예요.
- `nonce`는 최대 `64`바이트예요.
- `header_aad`는 최대 `16 KiB`예요.
- `ciphertext`는 최대 `20 MiB + 16 bytes`예요.
- WebSocket 클라이언트 메시지는 최대 `28 MiB`예요.

이 상한을 넘는 값은 relay로 보내기 전에 Android 쪽에서 먼저 거부해야 해요.

## Android가 서버에 보내는 payload 방향

### 알림 전달

- Android 알림을 앱 내부 `NotificationPayload`로 정규화해야 해요.
- 그 payload를 직렬화하고 암호화한 뒤 `channel=notification`으로 보내야 해요.

### 클립보드 전달

- 지원 포맷만 canonical `ClipboardPayload`로 변환해야 해요.
- 변환 실패나 미지원 포맷은 사용자에게 분명하게 보여줘야 해요.
- 암호화 후 `channel=clipboard`로 보내야 해요.

## Android 구현 시 협업 포인트

- 서버 계약은 `server/architecture.md` 기준으로 맞춰야 해요.
- macOS가 기대하는 canonical payload 구조와 Android가 만드는 구조는 완전히 같아야 해요.
- Android는 운영체제 제약이 가장 큰 영역이기 때문에, 구현 전마다 제약 검증을 먼저 해야 해요.
