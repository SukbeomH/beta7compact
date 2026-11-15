# beta7compact

Sony A7C와 Android 휴대폰을 위한 USB 원본 가져오기·BLE 위치 동기화 앱입니다. M1은 Kotlin + Compose, minSdk 29, targetSdk 35, 패키지 `dev.sukbeomh.beta7compact`를 사용합니다. 실기 호환 확인은 아직 남아 있습니다.

- USB MtpDevice 기본, raw PTP 대체. JPEG·ARW·HIF 목록, 선택/전체 받기, 파일 단위 재개, 중복 제거.
- Room 라이브러리, MediaStore `DCIM/beta7compact/`, 원본 바이트 보존 및 크기 검증 후 게시.
- Sony BLE 카메라 선택/본딩, 플랫폼 GPS와 위치 타입 포그라운드 서비스. 전송 시각·간격·실패 원인 표시.
- 레시피·리모컨·Wi-Fi 수신은 M1에 포함하지 않습니다.

## 빌드

JDK 17 이상과 Android SDK 35/Build Tools 35.0.0이 필요합니다. `local.properties`에 로컬 `sdk.dir`을 지정한 뒤 실행합니다. 이 파일은 커밋하지 않습니다.

```sh
./gradlew assembleDebug test lint --continue --no-daemon
```

Gradle wrapper·캐시·생성된 debug 서명키는 프로젝트 안의 무시 경로를 사용합니다. 외부 의존성은 AndroidX·Kotlin Coroutines와 테스트 도구입니다. Google Play services location은 사용하지 않습니다.

[APK](dist/beta7compact-0.1.0-debug.apk) · [SHA-256](dist/beta7compact-0.1.0-debug.apk.sha256) · [구현 결과](docs/RESULT_M1.md) · [실기 확인](docs/M1-DEVICE-CHECK.md) · [BLE 근거](docs/BLE-LOCATION.md) · [설계](docs/ARCHITECTURE.md)

USB 권한을 허용하고 카메라 USB 설정을 MTP로 지정하세요. 중단 후 같은 카드를 연결하고 가져오기 화면의 대기열 재개를 누르세요. 현재 파일은 처음부터 재수신하고 완료 파일은 건너뜁니다. BLE는 카메라의 Bluetooth·페어링·위치정보 연동을 켠 뒤 앱에서 대상을 선택하고 동기화를 켭니다.

사진·DB·선택한 카메라 정보는 폰의 앱 전용 영역과 사용자 갤러리에만 저장됩니다. GPS 좌표를 DB·로그에 기록하지 않습니다. 실기 로그·MAC·개인 위치·원본 사진을 이 공개 저장소에 추가하지 마세요.
