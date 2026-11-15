# 0.1.2 실기 피드백 반영 결과

2026-11-15 UTC. 기준 `origin/master` = `d26c884b58b2affd847e570c16c8df6207a1e18a`.
브랜치 `fix/device-feedback-012`에서 제품·회귀 테스트·문서를 하나의 원자 커밋으로 묶습니다.
버전은 **versionCode 3 / versionName 0.1.2**입니다.

## 입력과 원인 판단

사용자 실기: Fold8 Android 17 / A7C fw 2.03에서 raw 스위치를 켠 USB 가져오기는 성공,
기본 MTP는 실패, BLE 검색은 0대, 나머지는 정상입니다. BLE 검색 당시 카메라 페어링 화면은
열려 있었고 다른 폰/IEM 연동 이력은 없었습니다. 이 조건을 페어링 설정 오류로 설명하지 않습니다.
검사앱 `BleProbe.kt`의 company 0x012D + 빈 data 필터와 카메라 유형 03 00 광고를 확인했습니다.
기존 데이터 바이트 필터의 삼성 하드웨어 오프로드 미매칭과 8초 검색을 우선 가설로 처리했습니다.
서버 테스트만으로 실제 드라이버 원인을 확정하지 않습니다.

## 반영 내용

- USB: A7C용 raw PTP 기본값을 켭니다. 이미 저장된 명시적 스위치 값은 유지합니다.
  MTP 세션/장치 정보·목록/사진 정보·미리보기·원본 수신 실패 시 Native 소유권을 먼저 해제하고
  같은 카메라 읽기에서 raw PTP로 한 번 대체합니다. 전체 ImportPipeline·저널·게시를 재실행하지 않습니다.
  raw도 실패하면 그대로 중단합니다. 읽기에 성공한 경로만 raw 설정에 저장하고 스위치에 반영합니다.
  대기 중인 USB 요청도 소유권을 얻은 시점의 최신 설정을 읽습니다.
- MTP 실패는 세션·목록·미리보기·수신 중 어느 단계가 실패했는지 고정 한 줄로 홈/가져오기에 표시합니다.
  예외 원문·장치 정보·파일 메타데이터를 표시하지 않습니다. 원본 식별 불일치·권한·취소와
  MTP 호출 바깥의 파일/저장소 오류는 자동 대체로 우회하지 않습니다.
- BLE: 하드웨어 필터는 `setManufacturerData(0x012D, byteArrayOf())`입니다.
  단일/배치 콜백 모두 제조사 존재와 03 00을 검사합니다. 필터 검색 15초 후 카메라 광고 0건이면
  무필터 검색 15초를 정확히 한 번 수행합니다. 무필터에서도 같은 콜백 검증을 적용합니다.
  종료한 검색의 늦은 콜백은 집계와 결과를 바꾸지 못합니다.
- 본딩 기기는 `adapter.bondedDevices`에서 이름 ILCE-/DSC-/ZV-/ILME- 또는 저장된 카메라를
  골라 검색 전부터 표시합니다. 이미 본딩됐으면 재본딩 없이 선택합니다. 본딩 항목만 있어도
  카메라 광고가 0건이면 무필터 대체를 수행합니다. 선택/위치 연동 시작 시 진행 중 검색을 취소합니다.
- 다시 검색 버튼과 0건 안내를 추가했습니다. MENU→네트워크→Bluetooth 설정→페어링 화면을 안내하고,
  다른 폰/IEM과 이미 페어링한 경우에만 카메라 쪽 해제를 점검하도록 설명합니다.
  화면 진단 한 줄은 **필터 스캔 Sony 광고 수 / 무필터 스캔 Sony 광고 수 / 합산 카메라 유형 불일치 수**입니다.
  광고 수는 중복 수신을 포함한 콜백 건수이며, 카메라 목록은 주소를 메모리 내부 키로 사용해 중복 제거합니다.
- 설정에 진단 로그 공유를 추가했습니다. 정해진 enum 이벤트만 최대 200건 메모리에 보관하며
  연속 동일 이벤트는 합칩니다. 공유 본문은 고정 버전 헤더와 고정 이벤트 문구뿐입니다.
  MAC 전체/일부(마지막 2바이트 포함)·좌표·파일명·해시·광고 raw·예외·광고 집계치를 넣지 않습니다.
  로그 파일·DB·logcat을 수집하지 않습니다. 프로세스 종료 시 사라지며 사용자가 Android 공유 대상을 선택합니다.

필터 API와 데이터 매칭 경계는 [Android ScanFilter.Builder 공식 문서](https://developer.android.com/reference/android/bluetooth/le/ScanFilter.Builder)와
[AOSP ScanFilter 구현](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/ScanFilter.java)을 참고했습니다.
빈 data의 삼성 실기 호환성은 사용자 검사앱 결과를 근거로 하며 이번 수정 앱 재실기는 남아 있습니다.

## 실행 검증

```sh
ANDROID_HOME=/home/agent/camera-recipe-app/.android-sdk \
ANDROID_SDK_ROOT=/home/agent/camera-recipe-app/.android-sdk \
./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
git diff --check
```

| 검사 | 실제 결과 |
| --- | --- |
| 최종 assembleDebug + test + lint | 종료 0, BUILD SUCCESSFUL, 1분 29초, 84 tasks |
| Debug / Release 단위 테스트 | 각각 102건, failures/errors/skipped 모두 0 |
| lint | 오류 0, 기존 경고 12: GradleDependency 10, UnusedAttribute 1, KaptUsageInsteadOfKsp 1 |
| migration | v1→v2→v3 / v2→v3 fresh v3 일치, 키·URI·recipe·시각 보존, 신규 hash NULL, integrity_check=ok |
| APK 메타데이터 | aapt로 패키지·versionCode 3·versionName 0.1.2 확인 |
| diff | 공백 검사 통과. 위치 패킷·LocationLink/Service·PTP 클라이언트·ImportPipeline·원본 저장소·Room/스키마·manifest와 의존성 목록은 기준과 동일 |

새 USB 계약 7건은 실제 Native/Raw 어댑터의 세션·장치 정보·카드/handle/사진 정보 실패,
미리보기 실패, 부분 수신 뒤 raw 재수신/파일 절단, 양쪽 실패 시 루프 금지, raw 직접 사용,
권한/취소/동일성/일반 IO 오류의 비대체, 대기 요청의 최신 성공 경로 적용을 확인합니다.
USB 소유권 중첩은 합성 전송기가 거절하며 SET 호출은 0회입니다. 기존 0x201E 세션 인계,
선택적 raw 보강 실패 뒤 MTP 유지, 원본 비교 불가 시 새 게시 0, 중복·중단 복구 회귀도 통과했습니다.

새 BLE/공유 계약 7건은 빈 data 필터와 콜백 검증, 15초 대기 요청, 무필터 한 번 대체,
경로별 집계/중복 제거, 본딩 목록 선공개, 2회 모두 0건, 취소 시 stopScan,
필터 실패 뒤 무필터 복구, 종료 콜백 배제, 공유 문구 허용 목록/200건 상한을 확인합니다.
BLE 테스트는 실제 Scanner 코드를 실행하며 Android API와 시간만 합성합니다. 실제 15초 전파 수신을
측정한 결과가 아닙니다. 기존 테스트 88건을 삭제·제외하거나 실패 기대를 완화하지 않았습니다.

첫 전체 게이트도 통과했습니다(1분 35초, 각 100건). 이후 대기 USB 경로·종료 BLE 콜백 보호와
각 회귀를 더하고 위 최종 게이트를 통과했습니다. 실패한 게이트나 제외한 제품 테스트는 없습니다.
로그: `/tmp/beta7-fb012-gate-first.log`, `/tmp/beta7-fb012-gate-final.log`,
`/tmp/beta7-fb012-migration.log`.

## 산출물과 경계

- 로컬 배포 APK: `dist/beta7compact-0.1.2-debug.apk`. Gradle 중간 산출물은 기존 무시 build 경로입니다.
  dist/APK/AAB는 Git에 추가하지 않습니다. 원격 전송 대상은 지정 브랜치 하나이며 force를 사용하지 않습니다.
- USB SET 0·메타데이터 세션 인계·원본 바이트/동일성·해시 비교·중복 게시 방지 규칙을 유지했습니다.
  위치 패킷이나 GATT 쓰기 범위, 카메라 명령 허용 목록을 바꾸지 않았습니다. 외부 의존성 추가가 없습니다.
- 이번 검증은 서버 JVM/Android 계약 스텁·SQLite·APK 메타데이터입니다. Fold8 Android 17/A7C의
  수정 APK 재실기, 삼성 필터 오프로드, 실제 USB/MediaStore·공유 화면·본딩 UI는 미검증입니다.
  `docs/M1-DEVICE-CHECK.md`의 0.1.2 체크 항목을 갱신했고 실기 완료로 체크하지 않았습니다.
- 새 SHA의 독립 리뷰는 수행하지 않았습니다.
