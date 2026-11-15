# M1 FIX2 결과

2026-11-15 UTC. 비공개 `TASK_M1_FIX2.md`와 `REVIEW_M1_R2.md`를 끝까지 읽고 R2-N1·R2-N2만 수정했습니다.
기준 SHA는 `afb3f2150021390e081a70a45cee4c10e48469a3`, 제품·회귀 테스트 검증 SHA는
`7961ab8b19d94d7cecc29a7e5fa773530700479f`입니다. 이 보고서는 후속 문서 커밋입니다.

## R2-N1: 선택적 raw 보강과 기본 MTP 복구

- `OpenSession`의 `0x201E(SessionAlreadyOpen)`를 기존 세션 인계로 수락합니다. 정상 응답과 동일하게
  OpenSession의 transaction 0 이후 첫 세션 transaction은 1입니다. 다른 오류 응답은 수락하지 않습니다.
- native를 닫고 raw를 사용하는 구간은 성공·실패 모두 raw 해제 후 `finally`에서 native를 다시 엽니다.
  raw 초기화·GET_INTERFACE stall·메타데이터 읽기/종료 오류는 native 재개에 성공하면 보강 없이 진행합니다.
  native 자체를 다시 열지 못한 경우에는 연결 실패로 중단합니다. 두 소유자가 동시에 인터페이스를 점유하지 않습니다.
- 실패 사유는 `Log.i` 한 줄의 고정 문구로만 남깁니다. 장치 식별자·파일명·촬영시각·transport dump를 포함하지 않습니다.
  목록·썸네일·원본 수신은 재개한 `MtpDevice`를 사용합니다. 수신 직전 파일명·크기·storage·parent와
  시각 식별자를 다시 대조하며, raw 정보가 있으면 native 정보와도 대조합니다.
- **우선안 대신 선택적 보강을 유지한 근거:** Java `MtpObjectInfo`의 시간 단위는 밀리초이나,
  AOSP 파서는 원문 소수초·접미사를 잃고 `Z` 여부에 따라 UTC/로컬 해석을 달리합니다.
  epoch만으로 원문 전체를 복원한 시간대 독립 키를 보장할 수 없으므로, 보강 성공 시 기존 원문 키를 유지합니다.
  실패 시 MTP의 `dateCreated` 또는 `dateModified`를 사용하고 `cameraTime=null`로 구별합니다.
  원문을 역으로 지어내거나 fallback epoch 키 자체가 시간대 독립이라고 주장하지 않습니다.
- 원문 없는 MTP 항목은 v1과 같은 보수적 시간대 후보 대조를 적용합니다. 원문 기록의 MTP 호환 별칭도
  함께 제공하여 보강 가능 여부·시간대 변경으로 같은 원본을 다시 계획하지 않도록 합니다.
  정확한 원문이 있는 v2 기록끼리는 다른 촬영시각을 별칭으로 합치지 않습니다.
  현재 목록에서 원본 후보가 여러 개이면 아래 사전 검사에서 게시 전에 중단합니다.

근거: [MtpObjectInfo 시간 계약](https://developer.android.com/reference/android/mtp/MtpObjectInfo#getDateCreated()),
[AOSP Android 14 MtpDevice](https://android.googlesource.com/platform/frameworks/av/+/android-14.0.0_r1/media/mtp/MtpDevice.cpp),
[AOSP Android 14 DateTime 파서](https://android.googlesource.com/platform/frameworks/av/+/android-14.0.0_r1/media/mtp/MtpUtils.cpp).
웹 뷰어에서 AOSP 두 파일 접근이 실패하여 공식 `?format=TEXT` 응답을 직접 받아 확인했습니다.
SHA-256은 각각 `4178b732fb1485497bf209edb7764382375c0596eea827309bf9d29896a959ed`,
`926c20fb69d51dfacc5363a0ba0a29abc38194f745da3903497d21392a447337`이며 R2 근거와 같습니다.

## R2-N2: legacy 다중 행을 원본 단위로 처리

- `RoomJournal`에서 완료 행도 읽어 전체 대기열과 함께 현재 목록의 원본에 연결합니다.
  전체 대기열과 관련 완료 행의 원본 매칭을 다운로드·게시 전에 검사합니다.
  서로 다른 handle을 같은 키라는 이유로 지우지 않으며, 원본이 없거나 여러 후보에 대응하면 중단합니다.
- 완료 행이 있으면 기존 URI가 게시 원본인지 확인하고 나머지 대기 행을 그 URI로 완료합니다.
  완료 행 자체의 기존 키·URI·완료 시각·recipe는 유지합니다. 다운로드·새 게시를 하지 않습니다.
  완료 원본을 확인할 수 없는 경우 새 원본을 게시하지 않고 갤러리 확인 사유로 중단합니다.
- 대기 행만 여러 개이면 이미 게시된 staged URI부터 그룹 전체에서 찾습니다. 없으면 원본을 한 번만
  다운로드하고 모든 대기 행에 같은 staged URI를 기록한 뒤 한 번만 게시합니다.
  일부 행의 완료 기록 전에 중단돼도 재개 시 기존 완료/staged URI를 찾아 추가 게시하지 않습니다.
- 숨겨진 임시 항목만 정리합니다. 이미 게시된 URI가 여러 개 존재하면 각각 보존하며,
  기존 게시 원본 삭제로 중복 상태를 해결하지 않습니다. 일반적인 대기 두 행은 같은 URI로 완료됩니다.
- 모호한 원본·확인할 수 없는 완료 원본의 사유를 Worker의 고정 사용자 안내에도 연결했습니다.
  DB 스키마·Migration SQL은 변경하지 않았습니다.

## 검증 결과

검증 SHA `7961ab8b`에서 실행한 최종 명령:

```sh
ANDROID_HOME=/home/agent/camera-recipe-app/.android-sdk \
ANDROID_SDK_ROOT=/home/agent/camera-recipe-app/.android-sdk \
./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
git diff --check afb3f215 HEAD
```

| 검증 | 실행 결과 |
| --- | --- |
| 최종 전체 게이트 | 종료 0, BUILD SUCCESSFUL, 1분 17초, 84 tasks |
| debug / release JVM | 각각 65 tests, failures/errors/skipped 모두 0 |
| lint | 오류 0, 기존 경고 12: GradleDependency 10, UnusedAttribute 1, KaptUsageInsteadOfKsp 1 |
| R2 UsbChecks 이식 | 실제 UsbAccess·NativeCamera·RawCamera·PtpUsbClient 실행. 세션 유지/해제 모두 목록·썸네일·MTP 수신 성공 |
| GET_INTERFACE stall / raw 세션 0x2019 | 목록·썸네일·MTP 수신 모두 성공, 고정 fallback 사유 기록, USB SET 0회 |
| 카드 변경 보호 | native 정보가 그대로여도 raw parent가 바뀌면 수신 전 중단 |
| R2 IdentityChecks 이식: A 완료+B 대기 | 기존 게시 총 1, 추가 다운로드·게시 0, 기존 키·완료 URI·recipe 보존 |
| A 대기+B 대기 | 다운로드·게시 각각 총 1, 두 기존 키 모두 같은 URI로 완료, 재실행 추가 게시 0 |
| 그룹 복구 | 마지막 대기 행의 게시 URI 복구, 일부 행 완료 중 중단 후 재개, 복사 중단 후 그룹 단위 재개 통과 |
| 모호성 | 중복 키의 다른 handle 및 관련 완료 행의 다중 후보를 전체 게시 전에 차단 |
| 원본 보존 | 이미 게시된 서로 다른 URI 보존. 완료 원본 확인 불가 시 다운로드·게시 0 |
| fallback 시각 호환 | 시간대 변경 및 raw/fallback 상호 대조 통과. 원문이 다른 v2 기록의 구별도 유지 |
| 실제 Migration SQL + SQLite | 새 v2 스키마 일치, staged/완료 2행의 기존 키·URI·메타데이터 보존, integrity_check=ok |
| 범위 | BLE·manifest·백업 XML·Room 스키마·Gradle 의존성은 기준 SHA와 diff 없음 |
| 이력 | 기준 SHA가 조상, 이후 merge commit 0. 전체 도달 가능한 blob의 APK 0, APK/AAB/dist 경로 0 |

이식한 회귀는 `UsbCameraContractTest.kt`, `LegacyQueueContractTest.kt`입니다.
Android USB/MTP·DB I/O 경계만 합성 계약으로 대체하며 제품 어댑터·DAO enqueue·RoomJournal·파이프라인은 실제 코드를 실행합니다.
기존 프로토콜·BLE 벡터·MediaStore 보호 회귀도 전체 게이트에 포함했습니다.

검증 중 실패도 수정 후 다시 실행했습니다. 첫 USB 게이트는 nullable 시각 처리의 컴파일 오류로 종료 1이었고,
수정 후 debug 55개가 통과했습니다. 첫 전체 게이트는 새 모호성 테스트의 합성 완료 행을 Seoul 시각으로 만들어
실제로는 단일 후보였기 때문에 debug/release에서 각각 1개씩 실패했습니다. 해당 반례를 UTC legacy 행으로
바로잡은 뒤 전체 65개씩 통과했고, 마지막으로 위 제품 SHA에서 전체 게이트를 다시 통과했습니다.
실패 테스트 제외·검사 비활성화·의존성 추가는 하지 않았습니다.

최종 로그: `dist/validation/beta7-m1-fix2-final-gate.log`.
앞선 실패/수정 검증 로그도 `dist/validation/beta7-m1-fix2-*.log`에 보존했습니다.
JVM XML은 `app/build/test-results/`, lint XML은 `app/build/reports/lint-results-debug.xml`입니다.

## APK·이력·push

- 로컬 APK: `dist/beta7compact-0.1.0-debug.apk`, **24,176,126 bytes**.
- SHA-256: **`29359ebab7351d89ba2a4b9603fe86ad325cefc07bc4aefd91cfe0ccdbe0f095`**.
- 검증 SHA에서 빌드한 `app/build/outputs/apk/debug/app-debug.apk`와 바이트 일치를 확인했습니다.
  APK와 검증 로그는 무시 경로에만 두며 커밋하지 않습니다.
- `git rev-list --objects HEAD` 전체에서 확장자·dist 경로뿐 아니라 ZIP 내부 AndroidManifest.xml도 검사했습니다.
  wrapper JAR 이외 APK는 없고, 과거 APK blob `460d947ba3ad826f113740e99d883250dc58ef48`도 도달 불가입니다.
  이 보고서 커밋 뒤 최종 HEAD를 다시 검사하며 상세 결과는 `dist/validation/m1-fix2-history.json`에 기록합니다.
- push 직전 원격 지정 브랜치는 기준 `afb3f215`와 일치했습니다. 대상은
  `HEAD:refs/heads/feat/m1-usb-location-r` 하나이며 일반 fast-forward push만 사용합니다.
  다른 브랜치·tag·원격 ref 삭제·force push는 사용하지 않습니다.
  push 후 원격 지정 ref와 로컬 최종 HEAD의 일치를 확인합니다.

## 남은 확인

서버 JVM·계약 스텁·SQLite 검증이며 A7C/Fold8/Android 17의 실제 USB/MTP·BLE·Room 업그레이드는 미실행입니다.
실제 연결의 세션 인계·재개 지연·GET_INTERFACE 응답, 분리·절전·강제 종료·저장 공간 부족은 실기 확인이 필요합니다.
원문 없는 MTP/v1 기록은 시각·접미사 정보가 소실되어 후보 대조가 보수적입니다. 서로 다른 원본을 후보에서
확정할 수 없는 경우 자동 게시를 중단하며, epoch로 원문 촬영시각을 완전히 복구했다는 보장은 하지 않습니다.
