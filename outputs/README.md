# client build outputs

이 폴더는 GitHub Actions가 커밋 단위로 생성한 테스트용 클라이언트 산출물을 담아요.

- `outputs/android/air-bridge_v{app_version}.apk`
- `outputs/mac/air-bridge_v{app_version}.zip`

규칙은 아래와 같아요.

- `android/` 변경이 감지되면 installable debug APK를 만들어요.
- `mac/` 변경이 감지되면 `AirBridgeMac.app`을 zip으로 묶어서 만들어요.
- 한 커밋에 둘 다 바뀌면 둘 다 빌드하고 한 번에 커밋해요.
- `outputs/`만 바뀐 후속 커밋은 다시 빌드를 트리거하지 않아요.
