# M1 구현 결과

2026-11-15 UTC. 브랜치 `feat/m1-usb-location`, 시작 커밋 `5d0f3b6`. `TASK_M1.md`, `ARCHITECTURE.md` 5b, S0 결과와 읽기 전용 프로브 USB/PTP 소스를 확인하고 M1 앱을 새로 구성했습니다. **USB·라이브러리·BLE 위치 전송 구현 및 서버 검증 완료, 실제 A7C/Fold8 확인은 미실행**입니다. BLE는 공개 패킷 규격과 예시를 확보해 구현했으며 근거 부족 BLOCKED 상태는 아닙니다.

## 구현

- 앱: `dev.sukbeomh.beta7compact`, Kotlin/Compose, 버전 0.1.0/code 1, minSdk 29, compileSdk/targetSdk 35. 홈·가져오기·라이브러리·설정과 한국어 원인 안내를 제공합니다. 사진 그리드는 가용 폭에 맞춰 열 수를 바꿉니다.
- USB: Sony VID attach 필터와 OS 앱 열기, USB 권한 요청, MTP 모드 안내. `android.mtp.MtpDevice`가 기본이며 초기 세션/장치정보 실패 시 raw PTP를 한 번 시도합니다. 설정에서 raw를 직접 고를 수도 있습니다. 목록·GetThumb·원본 GetObject 외에 카메라 설정/셔터/삭제 쓰기는 없습니다.
- 가져오기: JPEG·ARW·HIF를 날짜별로 보여주며 선택/새 사진 일괄 받기, 가져옴 표시, RAW+JPEG 쌍을 제공합니다. Room 대기열과 고유 WorkManager 포그라운드 작업, 파일 수 진행률·중단 알림을 사용합니다. 목록/썸네일/수신은 USB 소유권을 직렬화합니다.
- 재개·중복: 파일명+바이트 크기+촬영시각의 해시로 중복 제거합니다. 재시작 시 목록에서 현재 handle을 재확인하고 다운로드 직전 메타데이터를 다시 대조합니다. 완료 항목을 건너뛰고 중단 파일은 처음부터 다시 받습니다. 대기 중인 이전 항목도 같은 대기열에 유지됩니다. 자동 재시도는 최대 3회이며 이후 사용자 재개가 가능합니다.
- 원본 보존: 앱 전용 `.part` 수신 → 광고 크기 검증 → MediaStore pending 항목에 복사 → 저장 크기 검증 → 게시 → Room 완료 기록 순서입니다. 원본 바이트/EXIF를 변환하지 않습니다. pending URI를 먼저 기록해 게시 직후 종료를 복구하고, 기록 직전 종료로 생긴 앱 소유의 고아 pending만 정리합니다. 게시된 원본은 정리 대상으로 삭제하지 않습니다. 저장 경로는 `DCIM/beta7compact/`, JPEG `image/jpeg`, ARW `image/x-sony-arw`입니다.
- Room: 원본 URI·파일명·크기·촬영시각·handle/storage/parent·쌍·가져온 시각을 저장하며 레시피 컬럼은 예약만 했습니다. v1 스키마를 포함했습니다.
- BLE: Sony 제조사 ID+카메라 유형 검색 → 사용자 선택 → OS 본딩/앱 비공개 대상 저장. DD21 설정을 읽고 DD01 활성 상태를 확인한 뒤 DD11 위치 패킷을 응답형으로 전송합니다. 플랫폼 LocationManager, 위치 타입 포그라운드 서비스, 토글, 5–60초 간격, 마지막 성공 시각·실패 사유를 제공합니다. 좌표는 메모리에만 존재하며 화면·DB·로그에 기록하지 않습니다. [패킷 근거와 제한](BLE-LOCATION.md)에 공개 자료의 고정 revision URL을 기록했습니다.
- 경계: Wi-Fi 수신·리모컨·레시피 기능은 추가하지 않았습니다. Google Play services location·소니 SDK 바이너리·타 앱 코드/자산을 포함하지 않았습니다. `.env`·자격증명 파일을 읽지 않았고 실기 로그를 복사하거나 커밋하지 않았습니다. 생성 서명키·Gradle 캐시·로컬 SDK 경로는 저장소 안 무시 경로입니다. 앱 데이터의 클라우드/기기 간 백업도 제외했습니다.

## 실행 검증

| 검증 | 결과 | 증거 |
| --- | --- | --- |
| `./gradlew assembleDebug test lint --continue --no-daemon` | BUILD SUCCESSFUL, 종료 0 | [전체 빌드 기록](../dist/validation/gradle.txt) |
| debug JVM | 38/38 통과, 실패/오류/skip 0 | [테스트별 결과](../dist/validation/tests.txt) |
| release JVM | 38/38 통과, 실패/오류/skip 0 | 같은 결과 파일 |
| lint | 오류 0, 경고 12 | [전체 보고서](../dist/validation/lint.txt) |
| APK 서명 | v2 검증 성공, 서명자 1 | [서명 검증](../dist/validation/signature.txt) |
| APK 버전·SDK·동일성 | 0.1.0/code 1, SDK 29/35, 빌드 APK와 배포 APK 바이트 일치 | [메타데이터](../dist/validation/artifact.txt) |

가짜 카메라가 실제 앱의 `Camera` 인터페이스와 `ImportPipeline`을 통해 원본 수신·게시·완료 기록을 수행합니다. 테스트는 중복/선택/RAW 쌍, 경로·MIME, 짧은 원본 거부, pending 복사 중단, 게시 직후 완료 기록 실패와 중복 없는 복구, 중단 후 다음 파일 재개, handle 변경/카드 누락, 상태머신을 검증합니다. PTP는 조각난 USB 패킷·ZLP·응답/transaction/크기 오류·금지 명령·설정 검증을 포함합니다. PTP 데이터셋과 동등한 합성 MTP 메타데이터의 가져오기 식별자도 비교했습니다. BLE는 공개 문서의 전체 95바이트 벡터 일치, 시간대 미포함 길이, 음수 좌표/시간대와 DST 분리, 잘못된 좌표·설정·상태를 확인했습니다. 테스트 데이터는 합성 또는 공개 프로토콜 예시입니다.

개발 중 첫 빌드는 FlowRow 실험 API 선언 누락으로, 두 번째는 테스트 hex 함수의 접근 범위와 lint 오류 3개로 실패했습니다. 이를 수정한 세 번째 실행은 기존 35개 테스트와 lint가 통과했습니다. 목록 변환 테스트 3개를 추가하고 최종 전체 실행에서 38개씩 통과했습니다. 초기 실패의 전체 기록은 커밋하지 않는 `.validation/gradle-initial.txt`, `.validation/gradle-second.txt`에 유지했습니다.

lint 경고는 의존성 업데이트 안내 10개·API 31 전용 스캔 속성 안내·kapt 대신 KSP 권고입니다. lint baseline이나 검사 비활성화는 추가하지 않았습니다. 비동기 BLE 권한 호출에는 실제 진입점의 권한 확인을 전제로 한 국소 MissingPermission 주석이 있습니다. 컴파일에는 구형 Android 호환 BLE API와 kapt 언어 버전 경고가 남습니다.

## 산출물

- [beta7compact-0.1.0-debug.apk](../dist/beta7compact-0.1.0-debug.apk): 24,176,126 bytes, debug 서명.
- SHA-256: `d0d0f57f733d174469ad2690ec40a365ff59fb4c8584bf6ad3dd49f59eee41a5` ([검증 파일](../dist/beta7compact-0.1.0-debug.apk.sha256)).
- [사용법](../README.md), [실기 체크리스트](M1-DEVICE-CHECK.md), [BLE 근거](BLE-LOCATION.md), Room v1 스키마 및 wrapper.

## 남은 확인

- JVM 검증은 Android USB/MTP, MediaStore, Room 디스크 트랜잭션, 실제 GATT/본딩, 위치 포그라운드 서비스 정책을 실행한 결과가 아닙니다. Fold8 접힘/펼침 화면과 Android 17/SDK 37 실행은 실기 체크리스트에서 확인해야 합니다.
- DD01 최초 활성 알림이 오지 않으면 전송하지 않고 시간 초과를 표시합니다. 카메라 위치정보 연동을 껐다 켜고 재시도해야 할 수 있습니다. DD30/DD31을 요구하는 새 프로토콜은 지원하지 않습니다. EE·리모컨·Wi-Fi 쓰기는 없습니다.
- 시간대는 휴대폰 시스템 시간대이며 좌표 기반 자동 시간대 조회를 하지 않습니다. 폰 시간/시간대 설정이 올바른지 확인해야 합니다.
- 파일 단위 재개이므로 현재 파일의 바이트 오프셋을 이어받지 않습니다. 큰 파일은 수신 임시 파일과 pending 복사본을 위한 여유 공간이 필요합니다. MtpDevice importFile은 플랫폼의 블로킹 API여서 중단 처리는 호출 반환 시점에 반영됩니다. USB를 다시 연결했다면 앱을 열고 권한을 확인해 재개하세요.
- RAW/HIF 썸네일·갤러리 뷰어 지원, 실제 사진의 바이트 일치, 화면을 끈 상태의 위치 전송, OS 작업 할당량/절전 동작은 미확인입니다. 공개 저장소에 실기 로그·MAC·개인 좌표·원본 사진을 추가하지 않습니다.
