# M1 FIX4 결과

2026-11-15 UTC. `/home/agent/beta7compact-private/REVIEW_M1_R4.md`를 끝까지 읽고
차단 **R4-N1만** 수정했습니다. 기준은 `feat/m1-usb-location-r`의
`deaebeb4688f624b8c59c3b3439a725484e33f72`입니다. 제품·테스트·이 문서를 하나의 원자 커밋에 포함합니다.

## 비교와 중단 정책

| 결과 | 조건 | 동작 |
| --- | --- | --- |
| SAME | 존재와 게시 상태를 확인하고 저장된/계산한 SHA-256이 수신 바이트와 같음 | 기존 URI 연결 |
| DIFFERENT | 존재와 게시 상태를 확인하고 SHA-256이 다름 | 다른 비교 대상도 모두 확인한 뒤 새 게시 허용 |
| UNVERIFIABLE | 조회 예외·null 커서·스트림 열기/읽기 예외·null 해시·게시 상태/크기 미확인·저널 URI 없음 | 새 게시·완료 전에 중단 |
| ABSENT | pending도 포함한 특정 URI 조회에서 행 자체가 없음을 확인 | 사용자가 갤러리에서 지운 경우로 취급해 새 게시 허용 |

- 비교 대상은 기존 정책대로 수신 원본과 같은 크기의 모든 완료 행입니다. 먼저 SAME을 찾거나
  staged 게시 URI를 복구했어도 나머지 비교를 생략하지 않습니다. 하나라도 UNVERIFIABLE이면
  MediaStore 생성·새 게시·대기 행 완료 전에 중단합니다.
- 중단 시 수신 임시 파일을 `finally`에서 정리하고 대기 행·기존 URI·게시 원본을 보존합니다.
  Worker는 **“기존 완료 원본을 비교할 수 없습니다. 갤러리 상태를 확인하고 다시 시도하세요”**라는
  고정 한 줄 사유를 표시하며 기존 재시도 정책을 사용합니다. 예외 원문·URI·해시는 표시하거나 로그로 남기지 않습니다.
- 성공적으로 지연 계산한 완료 행의 contentHash는 SAME/DIFFERENT 모두 저장합니다.
  다른 행이 UNVERIFIABLE이어도 성공한 해시는 유지합니다. 해시 저장 실패 역시 게시 전에 전파됩니다.
- 저장된 완료 해시는 재해시하지 않되 URI 존재·게시 상태는 확인합니다. 삭제된 URI의 오래된 해시만으로
  SAME 처리하지 않습니다. ABSENT에서도 이전 완료 행의 URI·완료 시각·recipe를 변경하거나 삭제하지 않고
  새 대기 행을 새 URI로 완료합니다. 단순 조회 실패나 pending 행은 ABSENT로 처리하지 않습니다.
- API 29는 includePending URI, API 30 이상은 MATCH_INCLUDE 조회를 사용합니다.
  OriginalStore의 기본 구현은 recover=false만으로 부재를 주장하지 않고 UNVERIFIABLE을 반환합니다.

## 반례 이식과 회귀

`/tmp/beta7-m1-review-tests/r4/FocusChecks.kt`와 `IntegratedChecks.kt`의 R4-N1 반례를
`app/src/test/java/dev/sukbeomh/beta7compact/ImportReadFailureContractTest.kt`에 이식했습니다.
실제 CameraObject·ImportPlan·PhotoDao.enqueue·RoomJournal·ImportPipeline과 MediaOriginalStore를 실행하고,
DAO 저장 경계·카메라 바이트·Android ContentResolver만 합성합니다.

| 검사 | 결과 |
| --- | --- |
| Focus: null 해시·해시 읽기 예외·URI 조회 예외 각각 단일 주입 | 새 URI 생성·게시 0, 대기·기존 행/URI/바이트 보존, 임시 파일 삭제. 오류 해제 후 기존 URI 연결·게시 총 1개 |
| Integrated: v1/v2형 원문 없는 같은 경로 완료 행 / v2형 raw 다른 parent 완료 행 | API 29/30/35 × 두 행 형태 × 다섯 장애 30조건 통과. 열기 예외·읽기 예외·null 해시·조회 예외·null 커서 모두 미완료 유지, 새 게시 0. 해제 후 기존 URI로 완료·게시 총 1개 |
| SAME과 UNVERIFIABLE이 함께 있는 복수 완료 행 | 두 조회 순서 모두 게시·완료 중단. SAME/DIFFERENT 행의 성공한 지연 해시 저장, 복구 후 정상 완료 |
| staged 게시 URI 복구 + 다른 완료 원본 미확인 | 추가 수신 없이도 완료를 중단하고 staged URI·대기 행 보존. 오류 해제 후 추가 게시 없이 완료 |
| 갤러리 삭제 / pending URI | API 29/30/35에서 실제 빈 행 조회만 ABSENT로 새 게시 허용. pending은 UNVERIFIABLE로 중단, 기존 완료 기록 보존 |
| 기존 unreadableLegacyUriNeverConfirmsContentEquality | 새 게시 1회 기대를 중단·0회로 수정. 오류 해제 후 기존 URI 연결과 해시 저장 확인 |
| LegacyQueueContractTest의 unreadableCompletedOriginalCannotConfirmEqualityAndKeepsTheOldRecord | 단일 null 해시 실패에서 기존 원본·별명 대기 행 보존, 재개 후 새 게시 0 확인 |
| 기존 동일성·USB·MediaStore·중단 복구·마이그레이션 회귀 | 전체 단위 테스트 통과 |

## 실행 증거

최종 제품·테스트 소스에 대해 다음 게이트를 실행했습니다. 이후 변경은 이 결과 문서뿐입니다.

```sh
ANDROID_HOME=/home/agent/camera-recipe-app/.android-sdk \
ANDROID_SDK_ROOT=/home/agent/camera-recipe-app/.android-sdk \
./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
git diff --check
```

| 게이트 | 결과 |
| --- | --- |
| assembleDebug + test + lint | 종료 0, BUILD SUCCESSFUL, 55초, 84 tasks 중 11 실행·73 up-to-date |
| debug / release 단위 테스트 | 각각 88 tests, failures/errors/skipped 모두 0 |
| lint | 오류 0, 기존 경고 12: GradleDependency 10, UnusedAttribute 1, KaptUsageInsteadOfKsp 1 |
| migration 검사 | 종료 0. v1→v2→v3 및 v2→v3 fresh v3 일치, 대기·완료 키/URI/recipe/시각 보존, hash NULL, integrity_check=ok |
| 범위와 diff | diff --check 통과. USB/BLE·Room/스키마·Gradle 의존성·manifest 변경 없음 |

최종 게이트 로그: `/tmp/beta7-m1-fix4-gradle-final.log`.
마이그레이션 로그: `/tmp/beta7-m1-fix4-migration.log`.
최초 게이트는 LegacyQueueContractTest의 기존 새 게시 기대 때문에 debug/release 각각 1건 실패했습니다
(`/tmp/beta7-m1-fix4-gradle.log`). 해당 기대를 위 중단·재개 계약으로 수정한 뒤 전체 게이트를 통과했습니다.
실패 테스트를 제외하거나 건너뛰지 않았습니다.

## 경계와 남은 점

- 대상 브랜치는 `feat/m1-usb-location-r` 하나이며 force push를 사용하지 않습니다.
  APK/AAB는 Git에 추가하지 않고 로컬 무시 빌드 경로에만 생성합니다. 외부 의존성 추가가 없습니다.
- 게시 원본 삭제·카메라 쓰기·EXIF 변환을 추가하지 않았습니다. USB/BLE 구현은 기준 SHA와 동일합니다.
- 검증은 서버 JVM·Android 계약 스텁·SQLite 범위입니다. A7C/Fold8/Android 17의 실제 ContentResolver·Room
  디스크 업그레이드·USB 분리·강제 종료·BLE 실기는 미실행입니다. UI 문구 경로는 코드로 확인했으며
  Compose 실물 화면 렌더링은 검증하지 않았습니다. 이 결과를 실기 인증으로 확대하지 않습니다.
- 수정 SHA에 대한 독립 재리뷰는 미실행입니다.
