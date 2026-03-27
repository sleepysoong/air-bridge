# air-bridge

`air-bridge`는 Mac과 Android 사이를 이어주는 개인용 브리지 프로젝트예요.

이 프로젝트는 아래 두 가지를 가장 먼저 해결해야 해요.
- Mac <-> Android 클립보드 동기화
- Android 알림을 수집해서 Mac으로 전달

모든 데이터는 relay 서버를 거치더라도 종단 간 암호화되어야 해요. relay 서버는 라우팅과 큐잉만 맡고, 사용자 데이터의 의미를 알 수 없어야 해요.

## 지금 바로 작업을 시작할 때 읽는 순서

1. `project.md`
2. `TODO.md`
3. `server/README.md`
4. `server/architecture.md`
5. 이후 작업 대상 영역의 `README.md`와 `architecture.md`

## 현재 개발 우선순위

1. relay 서버를 먼저 완성해야 해요.
2. Android 앱을 붙여야 해요.
3. macOS 앱을 붙여야 해요.

## 저장소 구조

```text
air-bridge/
├── AGENTS.md
├── README.md
├── TODO.md
├── project.md
├── android/
│   ├── README.md
│   └── architecture.md
├── mac/
│   ├── README.md
│   └── architecture.md
└── server/
    ├── README.md
    ├── architecture.md
    ├── cmd/
    ├── go.mod
    └── internal/
```

## 각 폴더의 역할

- `android/`는 Android 클라이언트 코드와 운영 문서를 같이 담고 있어요.
- `mac/`는 macOS 메뉴바 앱 코드와 운영 문서를 같이 담고 있어요.
- `server/`는 relay 서버 코드와 운영 문서를 담고 있어요.

## 협업 원칙

- 이름은 모두 실제 프로덕션 코드라고 생각하고 정해야 해요.
- 공개 API, JSON 필드, 환경 변수 이름은 쉽게 흔들리지 않게 잡아야 해요.
- 문서를 먼저 고정하고 구현을 맞춰가야 해요.
- 각 영역의 상세 구조와 역할은 해당 `architecture.md`를 기준으로 맞춰야 해요.

## 현재 진행 상황

- 최상위 구조를 만들었어요.
- 프로젝트 기준 문서와 TODO를 만들었어요.
- relay 서버의 기본 API, 큐 저장, WebSocket 중계를 구현했어요.
- Android 앱은 QR 전용 페어링, Shizuku fallback 클립보드 읽기, 알림 수집, 클립보드 송수신을 구현했어요.
- macOS 앱은 메뉴바 런타임, 클립보드 송수신, Android 알림 미러링, 로컬 알림 표시를 구현했어요.

## 현재 제품 메모

- Android 페어링은 QR 또는 deeplink로만 진행해요.
- Android 알림은 텍스트뿐 아니라 앱 정보와 best-effort 이미지 자산도 암호화해서 Mac으로 보내요.
- macOS는 이를 로컬 알림으로 보여주고, 가능하면 첨부 이미지도 함께 붙여요.
- 다만 macOS 시스템 제약 때문에 원본 Android 앱의 실제 시스템 알림 아이콘으로 위장해서 보여줄 수는 없어요.
- relay 서버는 여전히 평문을 해석하지 않고 암호화된 envelope만 전달해야 해요.

## 현재 서버 계약 메모

- 페어링 상태 조회는 `POST /api/v1/pairing/sessions/{sessionID}/lookup`로 처리해야 해요.
- `pairing_secret`는 query string으로 보내면 안 되고 JSON 본문으로만 보내야 해요.
- 서버는 `device_name`, `pairing_secret`, `content_type`, `nonce`, `header_aad`, `ciphertext`, HTTP body, WebSocket 메시지 크기에 상한을 적용해야 해요.
