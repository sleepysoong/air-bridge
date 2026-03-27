# macOS build outputs

이 폴더의 설치용 zip은 GitHub Actions의 `build-mac-app` 워크플로가 macOS runner에서 빌드한 뒤 갱신해요.

- 파일명: `AirBridgeMac-macos.zip`
- 내부 구성: `AirBridgeMac.app`
- 서명 방식: ad-hoc signing
- 용도: 테스트 설치용

Linux 환경에서는 SwiftUI/AppKit 기반 macOS 앱을 직접 빌드할 수 없어서, 이 저장소는 workflow로 산출물을 만들어요.
