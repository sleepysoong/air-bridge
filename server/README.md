# Relay Server 안내

이 폴더는 `air-bridge`의 중계 서버를 담고 있어요.

relay 서버는 사용자 데이터를 해석하는 서버가 아니고, 암호화된 envelope를 전달하고 보관하는 서버여야 해요. 즉, 사용자 경험에서는 핵심이지만 보안 경계에서는 가능한 한 멍청해야 해요.

## 현재 구현 범위

현재 들어간 기능은 아래와 같아요.
- SQLite 기반 저장소
- 페어링 세션 생성, 조회, 참여, 완료 API
- 기기 인증 기반 WebSocket 진입점
- 암호화 envelope 큐 저장
- 수신 측 acknowledgment 처리
- 만료된 세션과 envelope 정리 루프
- 페어링 완료 전 기기의 WebSocket 접근 차단

## 디렉터리 구조

```text
server/
├── README.md
├── architecture.md
├── cmd/
│   └── airbridge-relay/
│       └── main.go
├── go.mod
└── internal/
    ├── config/
    │   └── config.go
    ├── domain/
    │   └── models.go
    ├── persistence/
    │   └── sqlite/
    │       └── store.go
    ├── security/
    │   └── token.go
    ├── service/
    │   ├── errors.go
    │   ├── pairing.go
    │   └── relay.go
    └── transport/
        └── httpapi/
            └── server.go
```

## 로컬 실행 방법

Go 1.26 이상이 필요해요.

```bash
cd /Users/sleepysoong/Desktop/air-bridge/server
go mod tidy
go run ./cmd/airbridge-relay
```

기본 포트는 `:8080`이에요.

## 환경 변수

- `AIR_BRIDGE_HTTP_ADDRESS`
- `AIR_BRIDGE_DATABASE_PATH`
- `AIR_BRIDGE_PAIRING_TTL`
- `AIR_BRIDGE_MESSAGE_TTL`
- `AIR_BRIDGE_CLEANUP_INTERVAL`
- `AIR_BRIDGE_WEBSOCKET_WRITE_TIMEOUT`
- `AIR_BRIDGE_SHUTDOWN_TIMEOUT`

예시는 아래와 같아요.

```bash
AIR_BRIDGE_HTTP_ADDRESS=:8080 \
AIR_BRIDGE_DATABASE_PATH=./data/airbridge-relay.db \
go run ./cmd/airbridge-relay
```

## Docker 실행 방법

이미지 빌드는 아래처럼 하면 돼요.

```bash
cd /Users/sleepysoong/Desktop/air-bridge/server
docker build -t airbridge-relay .
```

실행 예시는 아래예요.

```bash
docker run --rm \
  -p 8080:8080 \
  -v "$(pwd)/data:/app/data" \
  --env-file .env.example \
  airbridge-relay
```

## 빠른 API 확인 방법

### health check

```bash
curl http://localhost:8080/healthz
```

### pairing session 생성

```bash
curl -X POST http://localhost:8080/api/v1/pairing/sessions \
  -H 'Content-Type: application/json' \
  -d '{
    "device_name": "sleepysoong-macbook-air",
    "platform": "macos",
    "public_key": "bXlfcHVibGljX2tleQ"
  }'
```

### pairing session 조회

```bash
curl "http://localhost:8080/api/v1/pairing/sessions/ps_xxx?pairing_secret=prs_xxx"
```

## 이 서버가 지켜야 할 원칙

- 평문 payload를 저장하면 안 돼요.
- relay token은 평문이 아니라 hash로 저장해야 해요.
- relay token만 맞아도 접속되는 구조가 되면 안 되고, 페어링 완료 상태까지 같이 검증해야 해요.
- JSON 필드명과 라우트 이름은 초기에 안정적으로 고정해야 해요.
- 기기 간 전달은 online push와 offline queue 모두를 고려해야 해요.

## 다음 작업

- 자동화 테스트를 추가해야 해요.
- WebSocket 프로토콜을 더 엄격하게 검증해야 해요.
- 운영 로그 정책과 rate limiting을 보강해야 해요.
- 배포 자산을 추가해야 해요.
