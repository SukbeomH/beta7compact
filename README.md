# beta7compact

Sony A7C와 Android 휴대폰을 위한 USB 원본 가져오기·BLE 위치 동기화 앱입니다. M1은 Kotlin + Compose, minSdk 29, targetSdk 35, 패키지 `dev.sukbeomh.beta7compact`를 사용합니다. 실기 호환 확인은 아직 남아 있습니다.

- USB A7C 기본 raw PTP, 설정에서 MTP 선택 가능. MTP 읽기 실패 시 같은 동작에서 raw PTP 1회 대체·성공 경로 기억. JPEG·ARW·HIF 목록, 선택/전체 받기, 파일 단위 재개, 중복 제거.
- Room 라이브러리, MediaStore `DCIM/beta7compact/`, 원본 바이트 보존 및 크기 검증 후 게시.
- Sony BLE 제조사 빈 data 필터 15초 + 카메라 광고 0건이면 무필터 15초 1회, 기존 본딩 카메라 즉시 선택·다시 검색·경로별 광고 진단. 플랫폼 GPS와 위치 타입 포그라운드 서비스. 전송 시각·간격·실패 원인 표시.
- 설정의 진단 로그 공유: 이번 실행의 고정 이벤트만 최대 200건, MAC 전체·일부/좌표/파일명/해시 미포함.
- 레시피·리모컨·Wi-Fi 수신은 M1에 포함하지 않습니다.

## 빌드

JDK 17 이상과 Android SDK 35/Build Tools 35.0.0이 필요합니다. `local.properties`에 로컬 `sdk.dir`을 지정한 뒤 실행합니다. 이 파일은 커밋하지 않습니다.

```sh
./gradlew assembleDebug test lint --continue --no-daemon
```

Gradle wrapper·캐시·생성된 debug 서명키는 프로젝트 안의 무시 경로를 사용합니다. 외부 의존성은 AndroidX·Kotlin Coroutines와 테스트 도구입니다. Google Play services location은 사용하지 않습니다.

APK는 로컬에서 빌드하거나 비공개 배포로 받습니다. `dist/` 산출물은 커밋하지 않습니다.

MTP 원본 수신에서도 촬영 식별용 DateTime 원문은 별도 읽기 전용 PTP 세션으로 확인합니다. 두 세션은 번갈아 USB를 사용하며, 현재 활성 구성·alternate에 읽기 인터페이스가 없으면 MTP 설정 안내와 함께 중단합니다.

v1→v2→v3 및 v2→v3 마이그레이션 SQL·기록 보존은 위 테스트 실행 후 `python3 tools/check_m1_migration.py`로 SQLite에서도 확인할 수 있습니다.

[0.1.2 결과](docs/RESULT_012.md) · [구현 결과](docs/RESULT_M1.md) · [실기 확인](docs/M1-DEVICE-CHECK.md) · [BLE 근거](docs/BLE-LOCATION.md) · [설계](docs/ARCHITECTURE.md)

USB 권한을 허용하고 카메라 USB 설정을 MTP로 지정하세요. 중단 후 같은 카드를 연결하고 가져오기 화면의 대기열 재개를 누르세요. 현재 파일은 처음부터 재수신하고 완료 파일은 건너뜁니다. BLE는 카메라의 Bluetooth·페어링·위치정보 연동을 켠 뒤 앱에서 대상을 선택하고 동기화를 켭니다.

사진·DB·선택한 카메라 정보는 폰의 앱 전용 영역과 사용자 갤러리에만 저장됩니다. GPS 좌표를 DB·로그에 기록하지 않습니다. 실기 로그·MAC·개인 위치·원본 사진을 이 공개 저장소에 추가하지 마세요.
