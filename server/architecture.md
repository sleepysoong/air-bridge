# Relay Server 아키텍처

이 문서는 relay 서버 구현의 상세 기준 문서예요.

서버 코드를 건드리기 전에 이 문서를 읽으면 어떤 계층이 무엇을 책임지고, 어떤 API를 어떻게 써야 하는지 빠르게 이해할 수 있어야 해요.

## 서버의 역할

relay 서버는 아래 책임만 가져가야 해요.
- 페어링 세션 생성과 상태 조회
- 기기 인증 정보 관리
- 암호화된 envelope 큐 저장
- 온라인 기기로의 즉시 전달
- 처리 완료 acknowledgment 반영
- 만료 데이터 정리

relay 서버가 하지 말아야 할 일도 분명해요.
- 평문 clipboard payload를 읽으면 안 돼요.
- 평문 notification payload를 읽으면 안 돼요.
- 클립보드 포맷을 해석하거나 변환하면 안 돼요.
- 앱별 도메인 로직을 서버 쪽에 두면 안 돼요.

## 현재 패키지 구조와 책임

```text
server/
├── cmd/airbridge-relay/main.go
├── internal/config/config.go
├── internal/domain/models.go
├── internal/persistence/sqlite/store.go
├── internal/security/token.go
├── internal/service/errors.go
├── internal/service/pairing.go
├── internal/service/relay.go
└── internal/transport/httpapi/server.go
```

### `cmd/airbridge-relay/main.go`

- 환경 변수를 읽고 서버를 띄우는 진입점이에요.
- SQLite 저장소를 열고 서비스 객체를 조립해야 해요.
- 종료 시그널과 cleanup 루프를 관리해야 해요.

### `internal/config/config.go`

- relay 서버 환경 변수와 기본값을 관리해요.
- 설정 키 이름이 외부 계약 역할을 하기 때문에 안정적으로 유지해야 해요.

### `internal/domain/models.go`

- 플랫폼, 채널, 기기, 페어링 세션, envelope 같은 핵심 도메인 모델을 정의해요.
- transport 계층과 저장소 계층이 공유해야 하는 중심 타입이에요.

### `internal/persistence/sqlite/store.go`

- SQLite 연결, 마이그레이션, CRUD, cleanup을 담당해요.
- 현재는 단일 파일에 모여 있지만, 테이블이 늘어나면 도메인별 파일로 나눠도 돼요.
- 시간은 Unix millisecond 기준으로 저장하고 있어요.

### `internal/security/token.go`

- 기기 ID, session ID, relay token, pairing secret 같은 식별자와 비밀값을 생성해요.
- relay token은 SHA-256 hash로 저장하도록 도와줘요.

### `internal/service/pairing.go`

- 페어링 세션 생성, 조회, 참여, 완료 흐름을 담당해요.
- 입력 검증, 만료 확인, 비밀값 비교 같은 애플리케이션 규칙이 여기에 있어야 해요.

### `internal/service/relay.go`

- WebSocket 인증, envelope 저장, pending envelope 조회, ack 처리를 담당해요.
- sender와 recipient의 pairing 관계를 검증하는 것도 여기에 있어야 해요.

### `internal/transport/httpapi/server.go`

- HTTP API와 WebSocket 프로토콜의 실제 진입점이에요.
- JSON 요청과 도메인 모델 사이 매핑을 담당해요.
- connection hub도 현재 여기 들어 있어요.

## 데이터 흐름

### 1. Mac이 페어링 세션을 만들 때

1. macOS 앱이 `POST /api/v1/pairing/sessions`를 호출해야 해요.
2. 서버는 initiator device, pairing session, relay token, pairing secret을 만들어야 해요.
3. 서버는 initiator relay token hash와 session secret hash만 저장해야 해요.
4. macOS 앱은 응답 값을 QR로 보여줘야 해요.

### 2. Android가 페어링에 참여할 때

1. Android 앱이 QR에서 `sessionID`, `pairingSecret`, relay URL, Mac public key를 읽어야 해요.
2. Android 앱이 `POST /api/v1/pairing/sessions/{sessionID}/join`을 호출해야 해요.
3. 서버는 joiner device와 relay token을 만들고 session 상태를 `ready`로 바꿔야 해요.
4. macOS 앱은 `POST /api/v1/pairing/sessions/{sessionID}/lookup`으로 상태를 확인해야 해요.
5. 이 시점의 relay token은 발급되더라도 WebSocket 인증에는 아직 사용할 수 없어요.

### 3. 양쪽이 SAS를 확인한 뒤

1. 사용자가 SAS가 같다고 확인해야 해요.
2. initiator가 `POST /api/v1/pairing/sessions/{sessionID}/complete`를 호출해야 해요.
3. 서버는 두 기기의 pairing confirmation 상태를 저장해야 해요.
4. 이후 두 기기만 relay token으로 WebSocket에 연결할 수 있어야 해요.

### 4. 실시간 envelope 전달

1. 클라이언트는 `GET /api/v1/ws?device_id=...&relay_token=...`로 연결해야 해요.
2. 서버는 자격 증명과 pairing confirmation 상태를 함께 검증하고 `connected` 메시지를 보내야 해요.
3. 송신 측 클라이언트는 `send_envelope` 메시지로 암호문을 보내야 해요.
4. 서버는 envelope를 SQLite에 저장한 뒤, 수신 측이 연결돼 있으면 즉시 push해야 해요.
5. 수신 측 클라이언트는 처리 완료 뒤 `ack_envelope`를 보내야 해요.
6. 서버는 ack를 받으면 해당 envelope를 delivered 상태로 바꿔야 해요.

## HTTP API 상세

### `GET /healthz`

서버 생존 확인용이에요.

응답 예시는 아래와 같아요.

```json
{
  "status": "ok"
}
```

### `POST /api/v1/pairing/sessions`

initiator device와 pairing session을 만들어요.

요청 예시는 아래와 같아요.

```json
{
  "device_name": "sleepysoong-macbook-air",
  "platform": "macos",
  "public_key": "<32-byte-x25519-public-key-base64>"
}
```

응답 예시는 아래와 같아요.

```json
{
  "pairing_session_id": "ps_xxx",
  "pairing_secret": "prs_xxx",
  "initiator_device_id": "dev_xxx",
  "initiator_relay_token": "rt_xxx",
  "expires_at": "2026-03-26T00:00:00Z"
}
```

### `POST /api/v1/pairing/sessions/{sessionID}/lookup`

페어링 상태를 조회해요.

요청 예시는 아래와 같아요.

```json
{
  "pairing_secret": "prs_xxx"
}
```

- `pending`: 아직 joiner가 없어요.
- `ready`: joiner가 들어왔어요.
- `completed`: 사용자가 SAS를 확인하고 완료했어요.

### `POST /api/v1/pairing/sessions/{sessionID}/join`

joiner device를 추가해요.

요청 예시는 아래와 같아요.

```json
{
  "pairing_secret": "prs_xxx",
  "device_name": "pixel-android",
  "platform": "android",
  "public_key": "<32-byte-x25519-public-key-base64>"
}
```

응답 예시는 아래와 같아요.

```json
{
  "pairing_session_id": "ps_xxx",
  "joiner_device_id": "dev_android",
  "joiner_relay_token": "rt_android",
  "initiator_device_id": "dev_macos",
  "initiator_public_key": "bXlfaW5pdGlhdG9yX3B1YmxpY19rZXk",
  "expires_at": "2026-03-26T00:00:00Z"
}
```

### `POST /api/v1/pairing/sessions/{sessionID}/complete`

사용자가 SAS를 확인한 뒤 완료 처리해요.

요청 예시는 아래와 같아요.

```json
{
  "pairing_secret": "prs_xxx"
}
```

## 입력 크기 제한

서버는 아래 상한을 강제로 적용해야 해요.

- pairing 관련 HTTP JSON 본문은 최대 `16 KiB`만 받아야 해요.
- `device_name`은 최대 `128`자만 받아야 해요.
- `pairing_secret`는 최대 `128`자만 받아야 해요.
- `content_type`은 최대 `255`바이트만 받아야 해요.
- `nonce`는 최대 `64`바이트만 받아야 해요.
- `header_aad`는 최대 `16 KiB`만 받아야 해요.
- `ciphertext`는 최대 `20 MiB + 16 bytes`만 받아야 해요.
- WebSocket 클라이언트 메시지는 최대 `28 MiB`만 받아야 해요.

이 제한은 서버 메모리 사용량과 SQLite 저장소 오염을 막기 위한 최소 방어선이에요. 클라이언트도 같은 상한을 미리 알고 로컬에서 빠르게 실패 처리해야 해요.

## WebSocket 프로토콜

### 연결 방법

```text
GET /api/v1/ws?device_id=dev_xxx&relay_token=rt_xxx
```

이 연결은 페어링이 `completed` 상태가 된 뒤에만 성공해야 해요.

### 서버가 보내는 메시지

#### `connected`

```json
{
  "type": "connected",
  "device_id": "dev_xxx",
  "peer_device_id": "dev_peer"
}
```

#### `envelope`

```json
{
  "type": "envelope",
  "envelope_id": "env_xxx",
  "sender_device_id": "dev_sender",
  "channel": "clipboard",
  "content_type": "application/json",
  "nonce": "<base64>",
  "header_aad": "<base64>",
  "ciphertext": "<base64>",
  "created_at": "2026-03-26T00:00:00Z",
  "expires_at": "2026-03-27T00:00:00Z"
}
```

#### `error`

```json
{
  "type": "error",
  "code": "send_failed",
  "message": "..."
}
```

### 클라이언트가 보내는 메시지

#### `ping`

연결 유지 확인용이에요.

#### `send_envelope`

```json
{
  "type": "send_envelope",
  "recipient_device_id": "dev_peer",
  "channel": "notification",
  "content_type": "application/json",
  "nonce": "<base64>",
  "header_aad": "<base64>",
  "ciphertext": "<base64>"
}
```

#### `ack_envelope`

```json
{
  "type": "ack_envelope",
  "envelope_id": "env_xxx"
}
```

## SQLite 저장 데이터

현재 저장하는 핵심 데이터는 아래예요.

- `devices`
  - 기기 ID
  - 기기 이름
  - 플랫폼
  - peer device ID
  - relay token hash
  - pairing confirmed 시각
  - 생성 시각
  - 마지막 접속 시각

- `pairing_sessions`
  - session ID
  - initiator 정보
  - joiner 정보
  - public key
  - pairing secret hash
  - 상태
  - 만료 시각

- `envelopes`
  - envelope ID
  - sender / recipient
  - channel
  - content type
  - nonce
  - AAD
  - ciphertext
  - 생성 시각
  - 만료 시각
  - delivered 시각

## 운영과 협업에서 주의할 점

- transport 계층에서 도메인 규칙을 너무 많이 가지면 안 돼요.
- SQLite는 지금 시작점으로 적절하지만, 동시성 요구가 커지면 저장소 계층 분리가 더 필요해요.
- 서버는 payload 의미를 몰라야 하므로 content validation은 envelope 외곽 메타데이터까지만 해야 해요.
- Android와 macOS가 공통으로 쓰는 envelope 계약이 바뀌면 반드시 이 문서와 `project.md`를 같이 수정해야 해요.
