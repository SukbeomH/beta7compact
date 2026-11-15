# M1 FIX 결과

2026-11-15 UTC. 지정된 비공개 `TASK_M1_FIX.md`·`REVIEW_M1.md`의 R1~R4만 수행했습니다.
제품·회귀 검증 소스 SHA: `b8c032f04e2aa0d74545d30903581a625fe4fabf`. 이 보고서는 후속 문서 커밋입니다.

## 변경

- **R1**: 중복 키와 RAW/JPEG 쌍 키는 표시용 epoch와 분리한 `cameraTime` 원문을 사용합니다. raw는 PTP DateTime의 소수초·접미사까지 그대로 보존합니다. MTP도 손실된 epoch를 역변환하지 않고, 별도의 읽기 전용 raw 세션에서 같은 원문을 얻습니다. MTP·raw는 USB를 동시에 점유하지 않습니다. MTP 목록과 파일명·크기·storage·parent를 대조하고, 수신 직전에 원문 키를 재검사합니다. 원본 수신과 썸네일은 MtpDevice를 유지합니다.
- **v1→v2**: nullable `cameraTime` 열만 추가하고 App에 명시적 Migration을 등록했습니다. v1의 키·URI·완료 상태를 보존하며, 당시 시간대가 기록되지 않은 행은 `legacy:` 병행 식별자로 처리합니다. 카메라 원문을 현재 기기의 전체 ZoneId 후보로 해석한 예전 키와 비교하며 DST gap도 포함합니다. 완료 표시는 재계획을 제외하고, enqueue는 완료·대기 기록 모두와 대조합니다. 재개 시 staged/완료 기록은 반드시 기존 대기열 키에 씁니다. v1 대기 항목이 여러 원본에 대응하면 게시 전에 중단합니다.
- **R2**: `5d0f3b643ef1ffbce074ef40c76d857165a8fef6`에서 `feat/m1-usb-location-r`를 만들고 `e07bb006`의 소스만 복원했습니다. APK·dist 검증 산출물은 처음부터 새 이력에 넣지 않았습니다. `.gitignore`에서 `dist/`, APK, AAB를 제외하고 README·기존 RESULT의 배포 링크를 로컬 빌드/비공개 배포 안내로 정리했습니다.
- **R3**: API 30+는 `QUERY_ARG_MATCH_PENDING=MATCH_INCLUDE`와 SQL selection Bundle, API 29는 `MediaStore.setIncludePending()`을 사용합니다. 소유 패키지·경로·pending 필터를 유지하며 known URI와 게시 원본은 보존합니다.
- **R4**: raw의 `setConfiguration`·`setInterface` 호출과 변경 helper를 제거했습니다. GET_CONFIGURATION/GET_INTERFACE로 현재 활성 구성·alternate를 확인하고 bulk IN/OUT Still Image 인터페이스만 claim합니다. 사용할 수 없으면 “카메라 설정 → USB 연결 → MTP” 안내로 중단합니다.

## 실행 검증

명령:

```sh
ANDROID_HOME=/home/agent/camera-recipe-app/.android-sdk \
ANDROID_SDK_ROOT=/home/agent/camera-recipe-app/.android-sdk \
./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
```

| 항목 | 결과 |
| --- | --- |
| 최종 전체 게이트 | 종료 0, BUILD SUCCESSFUL, 1분 14초, 84 tasks |
| debug / release JVM | 각각 50 tests, failures/errors/skipped 모두 0 |
| lint | 오류 0, 기존 경고 12 (업데이트 10, UnusedAttribute 1, KaptUsageInsteadOfKsp 1) |
| 시간대 반례 이식 | Asia/Seoul→UTC 후 두 번째 계획 0, 추가 다운로드·게시 0, 게시 원본 총 1 |
| 공통 촬영 식별자 | Seoul/UTC/New York/Kathmandu, 소수초·offset·Z·DST gap 원문의 키/쌍 불변 |
| v1 호환 | 완료 재계획 제외, 대기·staged URI/기존 키 보존, 중복 enqueue 방지, 모호한 대기열 게시 전 중단 |
| 실제 Migration SQL + SQLite | JVM에서 실제 Migration이 실행한 SQL을 SQLite에 적용. 새 v2 스키마와 일치, 완료·staged 2행의 모든 기존 열/URI/키 보존, integrity_check=ok |
| MediaStore 계약 스텁 | API 29/30/35에서 고아 pending만 삭제. known·다른 소유자·다른 경로·게시 원본 보존 및 게시 원본 discard 거절 |
| USB 계약 | 비활성 구성/alternate·bulk 누락 시 중단. raw/native 세션 소유권 겹침 없음 및 오류 시 raw 해제 |
| diff | `git diff --check 5d0f3b6 HEAD` 통과 |

첫 게이트는 당시 44개씩 통과했습니다. MTP 식별자 보강 뒤 두 번째 게이트에서 IntArray의 `mapNotNull` 사용으로 debug/release 컴파일이 실패했습니다. `toList()` 변환으로 고치고 추가 회귀까지 포함한 세 번째 전체 실행이 위 결과로 통과했습니다. 실패 테스트를 제외하거나 검사를 비활성화하지 않았습니다. 준비 명령의 `python` 별칭 미설치와 로그 확인용 sed 범위 오기도 각각 `python3`·정상 범위로 수정했습니다.

빌드 1~3 로그와 최종 이력 검사 목록은 무시 경로 `dist/validation/`에만 있습니다. JVM XML은 `app/build/test-results/`, lint는 `app/build/reports/lint-results-debug.xml`에 있습니다.

## APK 및 공개 이력

- 로컬 산출물: `dist/beta7compact-0.1.0-debug.apk` (24,176,126 bytes). 빌드 APK와 바이트 일치. 커밋 대상이 아닙니다.
- SHA-256: `2acecaa1f887e34dc06185a3e8c3b7671917e2af4ca2b2e09030aa584ca91edc`.
- `git rev-list --objects HEAD` 전체에서 APK/AAB·dist 경로 0, 도달 가능한 모든 blob의 ZIP 내부 AndroidManifest.xml 포함 APK 0을 확인했습니다. 기존 APK blob `460d947ba3ad826f113740e99d883250dc58ef48`도 새 브랜치에서 도달 불가입니다. 이 보고서 커밋 뒤 같은 검사를 다시 수행합니다.
- 기준 SHA가 merge-base와 일치하고 기준 이후 merge commit은 0개입니다. 원 브랜치를 조상으로 삼지 않았습니다. 원 브랜치·master·tag에 push하거나 원격 ref를 삭제하지 않습니다. force push는 사용하지 않습니다.
- push 대상은 새 `feat/m1-usb-location-r` 하나입니다. 최종 원격 SHA는 push 후 `git ls-remote --heads origin refs/heads/feat/m1-usb-location-r`와 로컬 HEAD를 비교합니다.

## 한계 및 남은 확인

- 서버 JVM/계약 스텁/SQLite 검증입니다. 실제 Android ContentResolver·Room 업그레이드, 카메라 USB/MTP/BLE, A7C/Fold8/Android 17 실기는 미실행입니다. 새 raw↔native 세션 전환의 실제 기기 호환성과 지연도 실기 확인이 필요합니다. MTP 원본 수신도 촬영 원문을 읽을 수 있는 현재 활성 raw 인터페이스가 필요합니다.
- v1은 원문·당시 시간대가 소실되어 같은 파일명/크기의 다른 촬영을 완전히 구분할 수 없습니다. 재게시 방지를 우선해 가능한 예전 키와 일치하는 완료 후보를 보수적으로 제외합니다. 모호한 대기 항목은 임의 수신하지 않습니다. v2 신규 기록끼리는 원문 전체를 정확 비교합니다.
- 기존 원 브랜치/다른 ref의 APK 공개 이력 처리 여부는 별도 운영 판단입니다. 이 작업은 새 브랜치의 도달 가능한 이력만 보장하며 기존 ref를 변경하지 않았습니다.

API 근거: [MediaStore pending 공식 계약](https://developer.android.com/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING), [AOSP Android 14 MTP DateTime 변환](https://android.googlesource.com/platform/frameworks/av/+/android-14.0.0_r1/media/mtp/MtpUtils.cpp).
