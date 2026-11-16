# beta7compact

Sony A7C(ILCE-7C) 사진을 Galaxy Z Fold 계열 Android 폰으로 가져와 필름 레시피(LUT·톤커브·HSL·그레인 등)를 적용하는 개인용 동반 앱입니다. 원본은 절대 수정하지 않고, 결과는 새 JPEG로 저장합니다.

- 현재 버전: **0.4.0** (Kotlin + Jetpack Compose, minSdk 29, targetSdk 35, 패키지 `dev.sukbeomh.beta7compact`)
- 실기 확인: Galaxy Z Fold8(Android 17) + A7C fw 2.03에서 USB 가져오기·레시피 편집 동작 확인
- 버전별 변경: [CHANGELOG](CHANGELOG.md)

## 기능

| 영역 | 내용 | 상태 |
| --- | --- | --- |
| USB 가져오기 | A7C 기본 raw PTP(MTP 실패 시 자동 1회 대체·성공 경로 기억). JPEG·ARW·HIF 목록, 선택/전체 받기, 파일 단위 재개, RAW+JPEG 쌍. 내용 해시(SHA-256) 기준 중복 판정, 원본 바이트 보존 후 `DCIM/beta7compact/`에 게시 | ✅ 실기 확인 |
| 라이브러리 | Room DB, 필터 칩, 다중 선택·일괄 적용, 원본↔결과 연결 | ✅ |
| 레시피 엔진 | GPU 처리 + CPU 참조 구현. 내장 LUT 27종(MIT/CC0 출처, 33³), 사용자 `.cube` 가져오기, 강도, 화이트밸런스, 노출, 그레인, 비네트, S-Log3 JPEG 입력 변환(기본 꺼짐) | ✅ |
| 편집기 | 톤커브(RGB·채널별), HSL 8색, 하이라이트/섀도, 대비, 채도·바이브런스, 스플릿 톤, 실행 취소/다시 실행, 전/후 비교 | ✅ |
| 레시피 공유 | `b7c1:` 텍스트 코드, QR 표시(자체 인코더), 현재 색 설정을 `.cube`로 내보내기 | ✅ |
| 화면 구성 | 카메라·사진·레시피·설정 4탭, 항상 다크, 사진 영역 3:2 고정. 접힘 하단 탭 / 펼침 좌측 레일, 세로 펼침은 위 사진·아래 편집, 가로 펼침 좌우 분할, 테이블탑 상하 분할. 미저장 편집 이탈 확인 | ✅ |
| 진단 로그 공유 | 설정에서 고정 이벤트만 최대 200건 공유(MAC·좌표·파일명·해시 미포함) | ✅ |
| BLE 위치 동기화 | 공개 분석 자료 기반 위치 패킷(구현·테스트 완료, [근거](docs/BLE-LOCATION.md)). 실기에서 카메라 광고 미검출 | ⏸ 보류 |
| RAW(ARW) 직접 현상 | 계획 [TASK_M5B](docs/TASK_M5B.md). 현재는 ARW 내장 미리보기 JPEG에 레시피 적용 | ⏸ 보류 |
| 원격 촬영(Wi-Fi) | 검사 앱으로 실기 확인 후 진행 | 예정 |

## 원칙

- 원본 사진은 수정·덮어쓰기·삭제하지 않습니다. 같은 사진인지는 메타데이터가 아니라 내용 해시로 확정합니다.
- 카메라에 대한 쓰기(삭제·설정 변경·셔터)와 USB 구성 변경을 하지 않습니다.
- 로그·진단 공유에 MAC·좌표·파일명·해시를 남기지 않습니다. GPS 좌표는 DB에 저장하지 않습니다.
- 외부 라이브러리는 AndroidX·Kotlin Coroutines·테스트 도구만 사용합니다. Google Play services는 쓰지 않습니다. 타사 레시피·앱 코드는 가져오지 않습니다.

## 빌드

JDK 17 이상과 Android SDK 35 / Build Tools 35.0.0이 필요합니다. `local.properties`에 `sdk.dir`을 지정합니다(커밋 금지). Gradle 캐시·debug 서명키는 프로젝트 안의 무시 경로를 씁니다.

```sh
./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/check_m5a_contract.py
python3 tools/check_d2_contract.py
```

APK는 로컬에서 빌드합니다. `dist/` 산출물과 APK는 커밋하지 않습니다. 내장 LUT는 `tools/luts/`로 재생성할 수 있습니다([출처](docs/LUT-SOURCES.md), [형식](docs/LUT-FORMAT.md), 고지 `assets/luts/NOTICE`).

## 사용

1. 카메라와 폰을 USB로 연결하고 권한을 허용합니다. 카메라 탭의 **새 사진 N장 가져오기**를 누릅니다. 중단되면 다시 연결해 재개하면 완료된 파일은 건너뜁니다.
2. 사진 탭에서 사진을 열고 **편집**에서 LUT를 고른 뒤 강도와 세부 항목을 조절합니다. 길게 누르면 원본과 비교합니다.
3. **저장**하면 `DCIM/beta7compact/Recipes/`에 새 JPEG가 생깁니다. 마음에 드는 설정은 **레시피로 보관**하고 레시피 탭에서 공유합니다.
4. 여러 장은 사진 탭 선택 모드에서 같은 레시피를 일괄 적용합니다.

## 문서

- 설계: [ARCHITECTURE](docs/ARCHITECTURE.md) · 디자인 [RESEARCH](docs/design/RESEARCH.md) · [IA](docs/design/IA.md) · [DESIGN-SYSTEM](docs/design/DESIGN-SYSTEM.md) · [PLAN](docs/design/PLAN.md) · 와이어프레임 `docs/design/wireframes-32/`
- 엔진: [M2-ENGINE](docs/M2-ENGINE.md) · [SLOG3](docs/SLOG3.md) · [M5A-SHARING](docs/M5A-SHARING.md)
- 결과 보고: [M1](docs/RESULT_M1.md) · [0.1.2](docs/RESULT_012.md) · [M2](docs/RESULT_M2.md) · [M5a](docs/RESULT_M5A.md) · [D2](docs/RESULT_D2.md) · [LUT](docs/RESULT_LUTS.md) · 실기 체크 [M1-DEVICE-CHECK](docs/M1-DEVICE-CHECK.md)

사진·DB는 폰의 앱 전용 영역과 사용자 갤러리에만 저장됩니다. 실기 로그·MAC·개인 위치·원본 사진을 이 공개 저장소에 추가하지 마십시오.
