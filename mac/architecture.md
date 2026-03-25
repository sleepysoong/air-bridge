# macOS 아키텍처

이 문서는 macOS 메뉴바 앱 구현을 위한 기준 문서예요.

현재 macOS 코드는 아직 없지만, 나중에 Android와 서버 구현이 진행된 뒤 바로 붙일 수 있도록 구조와 책임을 미리 고정해둘게요.

## macOS 앱이 맡아야 할 책임

- 메뉴바 앱으로 상시 실행되어야 해요.
- Android와 페어링을 시작하고 완료해야 해요.
- Keychain에 relay token과 암호화 관련 정보를 저장해야 해요.
- `NSPasteboard`를 감시해서 지원 포맷의 클립보드를 canonical payload로 만들고 relay로 보내야 해요.
- Android 알림 이벤트를 받아서 macOS 로컬 알림으로 보여줘야 해요.
- Android에서 온 클립보드 payload를 macOS pasteboard에 안전하게 적용해야 해요.

## 권장 구조

아직 생성되진 않았지만 아래 형태로 가야 해요.

```text
mac/
├── README.md
├── architecture.md
└── AirBridgeMac/
    ├── App/
    │   ├── AirBridgeApp.swift
    │   ├── AppContainer.swift
    │   └── AppState.swift
    ├── Feature/Pairing/
    │   ├── PairingView.swift
    │   ├── PairingViewModel.swift
    │   └── PairingCoordinator.swift
    ├── Feature/Clipboard/
    │   ├── PasteboardMonitor.swift
    │   ├── ClipboardSyncCoordinator.swift
    │   └── ClipboardPayloadMapper.swift
    ├── Feature/Notifications/
    │   ├── NotificationMirrorCoordinator.swift
    │   └── LocalNotificationGateway.swift
    ├── Data/Relay/
    │   ├── RelayHTTPClient.swift
    │   ├── RelayWebSocketClient.swift
    │   └── RelayMessageMapper.swift
    ├── Data/Security/
    │   ├── KeychainStore.swift
    │   ├── SessionKeyStore.swift
    │   └── EnvelopeCipher.swift
    └── Domain/
        ├── ClipboardPayload.swift
        ├── NotificationPayload.swift
        └── PairingModels.swift
```

## 계층별 역할

### App 계층

- 앱 수명 주기와 메뉴바 상태를 관리해야 해요.
- 연결 상태, 마지막 동기화 시각, 권한 상태를 한곳에서 보여줘야 해요.

### Pairing 계층

- Mac에서 pairing session을 생성해야 해요.
- QR에 필요한 세션 정보와 공개키를 표시해야 해요.
- Android가 참여한 뒤 SAS 확인 흐름을 이어서 완료해야 해요.

### Clipboard 계층

- `NSPasteboard`의 change count를 감시해야 해요.
- 지원 포맷을 canonical payload로 정규화해야 해요.
- 원격에서 들어온 내용을 pasteboard에 쓸 때는 synthetic write guard를 사용해서 반사 루프를 막아야 해요.

### Notifications 계층

- Android에서 들어온 `posted`, `updated`, `removed` 이벤트를 받아야 해요.
- `UNUserNotificationCenter`에 로컬 알림으로 등록하거나 제거해야 해요.
- Android의 `remoteKey`와 macOS 알림 identifier 사이 매핑을 유지해야 해요.

### Security 계층

- Keychain을 기본 저장소로 사용해야 해요.
- 종단 간 payload 암호화는 `AES-256-GCM`이어야 해요.
- relay token과 session key를 한 곳에서 분리 관리해야 해요.

## 서버 API 사용 방법

### 1. 페어링 세션 생성

```http
POST /api/v1/pairing/sessions
Content-Type: application/json

{
  "device_name": "sleepysoong-macbook-air",
  "platform": "macos",
  "public_key": "<base64>"
}
```

응답으로 아래 값을 받아야 해요.
- `pairing_session_id`
- `pairing_secret`
- `initiator_device_id`
- `initiator_relay_token`
- `expires_at`

이 정보를 QR로 표시해야 해요.

### 2. 페어링 상태 조회

```http
POST /api/v1/pairing/sessions/{sessionID}/lookup
Content-Type: application/json

{
  "pairing_secret": "prs_xxx"
}
```

이 응답으로 Android가 들어왔는지, 상대 공개키가 도착했는지 확인해야 해요.

### 3. 페어링 완료

```http
POST /api/v1/pairing/sessions/{sessionID}/complete
Content-Type: application/json

{
  "pairing_secret": "prs_xxx"
}
```

Mac은 SAS가 일치한다고 사용자가 확인한 뒤에만 이 완료 호출을 보내야 해요.

### 4. WebSocket 연결

```text
GET /api/v1/ws?device_id=dev_xxx&relay_token=rt_xxx
```

연결 후에는 아래를 처리해야 해요.
- 수신 `envelope`를 채널별로 분기해야 해요.
- 처리 완료 후 `ack_envelope`를 보내야 해요.
- 연결이 끊기면 재연결해야 해요.

## 서버 입력 제한 메모

macOS 네트워크 계층은 아래 상한을 미리 알고 있어야 해요.

- pairing 관련 HTTP JSON 본문은 최대 `16 KiB`예요.
- `device_name`과 `pairing_secret`는 서버 길이 제한을 넘기면 안 돼요.
- `content_type`은 최대 `255`바이트예요.
- `nonce`는 최대 `64`바이트예요.
- `header_aad`는 최대 `16 KiB`예요.
- `ciphertext`는 최대 `20 MiB + 16 bytes`예요.
- WebSocket 클라이언트 메시지는 최대 `28 MiB`예요.

이 상한을 넘는 값은 relay로 보내기 전에 macOS 쪽에서 먼저 막아야 해요.

## macOS 구현 시 주의할 점

- 메뉴바 앱은 눈에 덜 띄어도 되지만 연결 상태는 명확해야 해요.
- 로컬 pasteboard 쓰기와 원격 수신을 구분하지 않으면 무한 반사 루프가 생겨요.
- macOS 알림과 Android 알림의 생명주기를 맞추기 위해 remote identifier를 일관되게 유지해야 해요.
- UI보다 먼저 송수신 안정성과 상태 관리부터 단단하게 만들어야 해요.
