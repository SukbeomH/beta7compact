# M1 FIX3 결과

2026-11-15 UTC. `TASK_M1_FIX3.md`와 `REVIEW_M1_R3.md`를 끝까지 읽고 지정 설계로 수정했습니다.
기준 SHA는 `7b24d1e528b82a77ccd6f426cffd749b1b1f5a61`, 제품·회귀 검증 SHA는
`55d7223a23e09e69c0aecca02d94fcc7e4f3c9e1`입니다. 이 보고서는 후속 문서 커밋입니다.
대상 브랜치는 `feat/m1-usb-location-r`이며 다른 브랜치·태그는 push하지 않습니다.

## 구현

- 계획에서 제외하는 known 조건은 **비어 있지 않은 동일 원문 촬영 문자열 + 동일 storage/parent/이름/크기**입니다.
  handle 변경과 전화 시간대 변경은 이 조건을 바꾸지 않습니다. 원문이 빈 문자열이면 fallback으로 처리합니다.
- 시각 별명·fallback·다른 폴더/카드의 메타데이터 일치는 확인 필요 힌트입니다.
  UI에 ‘확인 필요’로 표시하고 선택을 허용하며 ‘새 사진 모두 받기’에도 포함합니다.
  별명으로 DAO enqueue를 생략하지 않습니다.
- 메타데이터 행 키와 바이트 해시를 분리했습니다. 새 행 키에는 storage/parent를 포함합니다.
  기존 v1/v2 키는 `recordKey`로 그대로 유지하며 실제 DB 조회·갱신에도 그 키를 사용합니다.
  fallback이 이미 완료된 동일 메타데이터 키와 겹쳐도 별도 대기 행으로 수신합니다.
  동일한 대기 항목을 반복 enqueue해 행을 늘리지는 않습니다.
- 수신 임시 파일을 64 KiB 버퍼로 읽어 SHA-256을 계산하고 게시 전에 Room에 저장합니다.
  기존 완료 원본과 해시가 같으면 기존 URI에 연결하고 임시 파일을 폐기합니다.
  해시가 다르면 새 원본을 게시합니다. 다른 이름·폴더·카드도 내용 비교 대상입니다.
- 기존 완료 행의 해시가 null이면 같은 크기의 수신 파일을 비교할 때 게시 URI에서 지연 계산해 저장합니다.
  읽기 실패는 동일성 확정에 쓰지 않으며 해시는 null로 유지합니다. 이 경우 기존 기록·원본을 보존하고
  수신 파일은 별도로 게시합니다. 읽을 수 없는 기존 원본까지 중복 게시 0회를 보장하지는 않습니다.
- 전체 대기열의 후보를 다운로드·게시 전에 검사합니다. 누락·복수 후보이면 전체를 중단합니다.
  원문 대기 행은 fallback 현재 목록으로 자동 완료하지 않습니다. fallback끼리도 다른 epoch의
  시간대 별명 하나만으로 재개하지 않습니다. 서로 다른 원문 행을 한 그룹으로 완료하지 않습니다.
  Worker는 같은 카드 연결·목록 새로고침 등 고정 사유를 표시합니다.
- 같은 경로의 유일한 현재 후보에 대응하는 v1 잔여 행들은 한 번 수신한 바이트와 기존 URI의 해시를
  비교하여 한 번만 게시합니다. 해시를 게시 전에 저널링하므로 게시 뒤 완료 기록 중단도 복구합니다.
  기존에 게시된 URI는 삭제하지 않고, 바이트가 같은 기존 개별 URI 링크도 유지합니다.
- Room v3와 `MIGRATION_2_3`를 추가했습니다. v1→v2→v3 및 v2→v3 모두 파괴적 재생성을 사용하지 않습니다.
  기존 열·키·URI·완료 시각·recipe·원문을 보존하고 nullable `contentHash`만 추가합니다.
- 구조 문서와 실기 체크리스트도 새 동일성 규칙에 맞췄습니다. 의존성 추가는 없습니다.

## 반례와 회귀 결과

`/tmp/beta7-m1-review-tests/r3/ExtraChecks.kt`의 반례를 저장소
`ImportIdentityContractTest.kt`로 이식했습니다. 결함을 재현하던 기대값은 수정 후 동작으로 바꿨습니다.
실제 `CameraObject`, `ImportPlan`, `PhotoDao.enqueue`, `RoomJournal`, `ImportPipeline`을 실행하며
카메라 바이트·DAO 저장 경계·MediaStore만 합성입니다.

| 검사 | 최종 관찰 결과 |
| --- | --- |
| R3-N1 A: raw→fallback, fallback→raw, fallback→fallback | 각 방향을 같은 폴더/다른 폴더에서 검사. UTC 고정, 다른 바이트를 1회 수신·1회 새 게시. 기존 원본 보존, 기존·신규 행 분리 |
| R3-N1 B: 원문 대기 A/B, 현재 fallback A 하나 | 수신 0·게시 0, 원문 확인 사유로 중단. 어느 행도 잘못 완료되지 않음 |
| fallback 대기와 다른 epoch의 현재 fallback | 별명 힌트만으로 재개하지 않고 수신·게시 전에 중단 |
| R3-N2: 동일 이름·크기·원문, parent 또는 storage 변경, 다른 바이트 | 각각 계획·선택·enqueue 포함, 수신 1·새 게시 1, 기존 바이트·URI·recipe 보존 |
| 같은 바이트, 다른 parent/storage | 각각 수신 1·새 게시 0, 기존 URI 연결 및 임시 파일 폐기 |
| 한 목록 안의 같은 원문·다른 폴더/카드 | 계획·DAO 모두 3개 별도 항목 보존 |
| fallback 동일 메타데이터 키 재사용 / raw 시각 공백 | 선택과 enqueue를 생략하지 않고 다른 바이트를 새 게시. 반복 enqueue는 대기 행 1개 유지 |
| R1: 원문 고정, 전화 시간대·handle 변경 | 네 시간대에서 known 유지·새 계획 0. 원문 소수초·접미사·DST 관련 회귀 통과 |
| R1: 같은 원본 raw/fallback 교대 | 두 시작 조건 × 5회 연결·시간대 변경. 확인을 위한 재수신은 발생하지만 새 게시 총 1회·URI 1개 |
| R2-N1: 0x201E 세션 인계·선택적 보강 | 기존 실제 USB 어댑터 계약 테스트 통과. 세션 유지/해제·raw 거절/stall 뒤 native 목록·썸네일·수신, USB SET 0회 |
| R2-N2: A 완료+B 대기 | 원래 키·URI·완료 시각·recipe 보존, 해시 지연 계산, 수신 1·새 게시 0. 새 원문 기준 행도 같은 URI로 완료 |
| R2-N2: A/B 대기 | 기존 두 행을 보존하고 수신·게시 1회. 새 원문 기준 행이 추가되어도 같은 URI로 완료 |
| 중단·재개 | 게시 후 완료 기록 중단, 별명별 완료 중단, 부분 복사, 수신 후 취소, 짧은 수신 모두 회귀 통과 |
| 해시 저널 저장 실패 | 게시·MediaStore 생성 0, 임시 파일 폐기, 미완료 유지. 재시도 시 1회 정상 게시 |
| 읽기 불가 / 저장된 해시 | 읽기 불가 null 해시를 동일성 증거로 쓰지 않음. 이미 저장된 완료 해시는 원본을 재해시하지 않고 비교 |
| 연속 촬영의 다른 파일명 2개 | 같은 촬영 초라도 계획·대기 2개, 다른 바이트 각각 게시 |
| 게시 원본 보호 | API 29/30/35 MediaOriginalStore 계약에서 고아 pending만 정리, 게시 원본 discard 거절, 게시 URI 해시 읽기 통과 |

기존 R2의 ‘별명만 맞으면 다운로드 0회’ 기대값은 요청한 해시 비교에 맞게 변경했습니다.
중복 게시 방지와 기존 원본 보존 결과는 유지합니다. 완료 기록의 메타데이터 후보가 여러 개여도
그 기록은 힌트일 뿐이며 수신 바이트 해시로 비교합니다. 대기 원본 후보의 모호함은 계속 중단합니다.

## 실행 증거

최종 소스와 커밋된 소스가 동일함을 확인했습니다. 다음 명령을 실행했습니다.

```sh
ANDROID_HOME=/home/agent/camera-recipe-app/.android-sdk \
ANDROID_SDK_ROOT=/home/agent/camera-recipe-app/.android-sdk \
./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
git diff --check 7b24d1e5 HEAD
```

| 게이트 | 결과 |
| --- | --- |
| assembleDebug + test + lint | 종료 0, BUILD SUCCESSFUL, 1분 22초, 84 tasks |
| debug / release 단위 테스트 | 각각 83 tests, failures/errors/skipped 모두 0 |
| lint | 오류 0, 기존 경고 12: GradleDependency 10, UnusedAttribute 1, KaptUsageInsteadOfKsp 1 |
| 실제 Migration SQL + SQLite | v1→v2 중간 스키마, v1→v2→v3, v2→v3가 fresh schema와 일치. 대기/완료 2행의 키·URI·recipe·원문 보존, 신규 hash NULL, integrity_check=ok |
| 코드 범위·diff | diff --check 통과. USB/BLE·manifest·백업 XML·Gradle 의존성은 기준 SHA와 동일 |
| 검증 SHA의 전체 도달 가능 이력 | object 275개·blob 106개. ZIP 1개는 기존 wrapper JAR, APK blob 0·APK/AAB 경로 0·민감 파일 경로 0 |

최종 로그는 `/tmp/beta7-m1-fix3-gate-final2.log`, 직전 전체 게이트 로그는
`/tmp/beta7-m1-fix3-gate-final.log`에 있습니다. 이력 검사 스크립트는
`/tmp/beta7-m1-fix3-history.py`이며 출력은 집계치뿐입니다.

중간 실패도 보존합니다. 최초 debug 실행은 65건 중 기존 별명 확정·키 형식 등에 대한 기대값 15건이
실패했습니다(`/tmp/beta7-m1-fix3-first.log`). 새 설계의 계획·enqueue·해시 비교 기대값으로 갱신한 뒤
추가 반례까지 통과했습니다. 이후 전체 게이트도 통과했으며, 해시 저널 실패·빈 원문 회귀와 DAO 후보
집합 재사용을 추가한 최종 상태에서 위 게이트를 다시 실행했습니다.
보조 EOF 검사는 Room 생성 schema의 기본 무개행 형식 때문에 한 번 실패했습니다.
기존 v2도 같은 생성 형식임을 확인하고 생성 파일은 유지했으며, 수동 작성 텍스트의 EOF 검사는 통과했습니다.
실패한 제품 테스트를 건너뛰거나 제외하지 않았습니다.

## 안전 경계와 남은 점

- 원본 변환·EXIF 재작성·게시 원본 삭제·카메라 쓰기를 추가하지 않았습니다.
  해시·파일명 로그를 추가하지 않았으며 USB/BLE 기존 허용 범위를 유지했습니다.
- APK는 로컬 무시 빌드 경로에만 생성했습니다. APK/AAB를 Git에 추가하지 않았고 force push도 사용하지 않습니다.
- 검증은 서버 JVM·Android 계약 스텁·SQLite입니다. A7C/Fold8/Android 17 실물 연결, 실제 Room 업그레이드,
  USB 분리·절전·공간 부족·강제 종료·실제 BLE는 미실행입니다. 이 결과를 실기 인증으로 확대하지 않습니다.
- 기존 null 해시 원본을 읽지 못하면 동일성은 미확정이며 새 게시가 생길 수 있습니다.
  원문이 있는 대기열에서 raw 보강에 실패하면 재개가 중단되므로 같은 카드에서 목록을 다시 확인해야 합니다.
- 새 SHA에 대한 독립 재리뷰는 아직 수행하지 않았습니다.
