# M2 레시피 엔진 결과

2026-11-15 UTC. 작업 브랜치 `feat/m2-recipe-engine`.
기준 제품 `a41be3f`, 작업 지시 커밋 `c8c2481` 이후 M2만 구현했습니다.
앱 버전 **versionCode 4 / versionName 0.2.0**입니다.

## 구현

- GLES 3.0 엔진과 Kotlin CPU 참조: 선택적 입력 변환 → 인코딩 sRGB 삼선형 LUT/강도 →
  선형 WB/EV → 휘도 의존 고정 시드 그레인 → 비네트 → sRGB JPEG.
  내장 LUT 27종을 manifest 순서/분류와 SHA256으로 로딩합니다.
- SAF `.cube` 가져오기: 크기 2~65, TITLE, 채널별 DOMAIN_MIN/MAX, 주석, 유한수/길이/행 수 검증.
  잘못된 파일은 원문 없이 한 줄 사유로 거부합니다. 앱 내부 B7LT-v1과 DOMAIN/TITLE sidecar로 저장합니다.
  원래 격자와 DOMAIN을 유지해 좁은 DOMAIN의 재격자화 오차를 피했습니다.
- S-Log3/S-Gamut3.Cine JPEG 옵션 기본 꺼짐. Sony 공개 역수식과 원색에서 도출한 행렬,
  sRGB 출력 전달함수를 사용하며 Sony LUT 파일은 사용하지 않았습니다. 출처와 수식은 `docs/SLOG3.md`.
- 레시피 JSON v1에 lutId/strength/inputTransform/wb/ev/grain/vignette/version을 담습니다.
  Room v4에서 레시피 보관·목록·즐겨찾기, 원본 키와 결과 URI·레시피 스냅샷·실제 생성 시각을 저장합니다.
  원본 `photos.recipe`를 결과로 덮어쓰지 않습니다.
- 라이브러리 사진 탭 → 축소 GPU 편집 미리보기, 분류별 LUT 썸네일 가로 목록, 슬라이더,
  누르고 있는 동안 전/후 비교. 여러 장 선택 → 같은 레시피로 WorkManager 일괄 저장,
  진행률·중단·작업 재개를 제공합니다.
- 저장은 원해상도 타일 처리 후 새 JPEG, 품질 95,
  `DCIM/beta7compact/Recipes/`. 플랫폼에서 읽고 쓸 수 있는 EXIF를 복사하며
  방향 1~8을 픽셀에 반영하고 orientation=1, 크기/sRGB/Software 태그를 갱신합니다.
- 기본 레시피 지정과 가져오기 후 자동 적용 설정(기본 꺼짐).
  원본 완료와 자동 job 삽입은 같은 Room 트랜잭션이므로 일부 가져오기가 성공한 뒤 중단되어도 job이 남습니다.
- ARW는 가장 큰 지원 내장 JPEG를 추출해 처리합니다. UI에 내장 JPEG 사용을 표시합니다.
  센서 RAW 현상·원래 센서 크기로 업스케일은 하지 않습니다.
- 512² 타일, 전체 목적 bitmap 한 장, 재사용 readback을 사용합니다.
  메모리 추정치가 heap 상한의 60%를 넘거나 OOM이면 다운샘플 대신 한 줄 사유로 실패합니다.
  자세한 메모리/색 처리/복구 계약은 `docs/M2-ENGINE.md`.

## 실행 검증

```sh
ANDROID_HOME=/home/agent/camera-recipe-app/.android-sdk \
ANDROID_SDK_ROOT=/home/agent/camera-recipe-app/.android-sdk \
./gradlew assembleDebug test lint assembleDebugAndroidTest --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
git diff --check
```

| 검사 | 실제 결과 |
| --- | --- |
| 최종 전체 게이트 | 종료 0, BUILD SUCCESSFUL, 1분 46초, 111 tasks |
| Debug / Release 단위 테스트 | 각각 117건, failures/errors/skipped 모두 0 |
| lint | 오류 0, 경고 13: 기존 12 + 플랫폼 ExifInterface 권장 경고 1 |
| 마이그레이션 | 기존 M1 / 새 M2 모두 통과, 원본 행·fresh schema·FK·integrity 보존 |
| APK | aapt versionCode 4 / 0.2.0, 27 LUT bytes/SHA256 및 NOTICE 일치 |
| diff | 공백 검사 통과, BLE/USB/core/원본 저장소/로그/내장 LUT 불변 |

- `.cube` 정상/거부, 모든 지원 크기의 경계, 좁은/채널별 DOMAIN,
  B7LT 헤더/길이/순서, 보관된 공개 `.cube.gz` 5개를 검사했습니다.
- 내장 27 LUT × 100 합성 패치를 독립 double 삼선형 참조와 비교했습니다.
  최대 **ΔE76 = 0.00003367640230509084**. 이 수치는 Kotlin float 보간 산술의 차이이며 GPU/실사진 색차가 아닙니다.
- 레시피 JSON 왕복/버전·범위 거부, Sony black/grey/white/toe와 비중립 gamut 기준값,
  그레인 시드/휘도 의존, 강도 0/EV, TIFF 내장 JPEG 선택/잘못된 offset을 검사했습니다.
- Room 실제 migration에서 내보낸 SQL로 1→2→3과 2→3 기존 검사를 유지했습니다.
  새 검사는 1/2/3→4의 fresh schema·index·FK 일치, 원본 키/URI/recipe/raw clock/hash 보존,
  결과 링크·레시피·작업 저장과 integrity/FK check를 검사합니다.
- 기존 M1 테스트를 삭제하거나 제외하지 않았습니다. 원본 완료 hook의 1회 호출/원본 필드 보존 회귀도 포함합니다.
- `androidTest`에는 실제 EGL/GLES 내장 27 LUT × 입력모드 2 × 256 RGB 패치의 CPU/GPU 일치(채널별 ≤2/255),
  비기본 DOMAIN, 타일 이음새, 8방향 픽셀 매핑 테스트를 넣었습니다.
  합성 JPEG 내보내기의 원본 SHA 불변/EXIF/크기/스냅샷/pending 체크포인트 재개 테스트도 포함합니다.
  **기기 테스트 APK 빌드만 수행했으며 이 계측 테스트는 실행하지 않았습니다.** `adb devices`는 연결 0대였습니다.

로그는 `/tmp/beta7-m2-gate-1.log`, `/tmp/beta7-m2-gate-final.log`,
`/tmp/beta7-m2-gate-final-2.log`, 최종 `/tmp/beta7-m2-gate-closeout.log`입니다.
앞선 전체 게이트들도 통과했으며 보강 후 동일 전체 게이트를 다시 실행했습니다.

## 산출물·보존·남은 실기 확인

- 로컬 배포 APK: `dist/beta7compact-0.2.0-debug.apk`. APK/AAB/dist/build는 커밋 대상에서 제외합니다.
  앱 APK에 내장 27 LUT bytes/SHA와 NOTICE가 그대로 포함되는지 별도 검사했습니다.
- BLE, USB/PTP, core ImportPipeline, MediaOriginalStore, 진단 로그, 내장 LUT bytes와 기존 마이그레이션 검사기는
  기준과 동일합니다. Room v4 `photos` 엔티티는 v3와 동일합니다. 카메라 쓰기·로그 개인정보 경로를 추가하지 않았습니다.
  기존 완료 hook에 자동 적용 트랜잭션만 연결했으며 내용 해시/중복/원본 게시 규칙은 유지합니다.
- 의존성 목록은 변경하지 않았습니다. 기기 테스트는 플랫폼 legacy test runner를 사용하며
  optional platform library만 빌드에 지정했습니다. force/검증 우회/APK 커밋은 사용하지 않습니다.
- Fold8의 24MP 수 초 내 저장, 실제 GPU 색차/드라이버·메모리, SAF/MediaStore/EXIF/ARW 실사진,
  편집 UI와 WorkManager 중단/재개·자동 적용의 실기는 미검증입니다. 측정 절차는 `docs/M2-ENGINE.md`.
- 플랫폼 미지원 EXIF와 제조사 MakerNote의 의미까지 보존하는 full RAW 처리는 범위 밖입니다.
  `.cube` 출력 음수/1 초과(HDR), 1D/혼합 LUT는 M2에서 명시적으로 거부합니다.
- 프로세스가 MediaStore insert 직후 Room URI 기록 전에 종료되면 숨겨진 pending 고아가 남을 수 있습니다.
  원본/게시본 삭제를 복구 수단으로 사용하지 않습니다. 기록된 job은 ‘작업 재개’로 다시 처리합니다.

자료 확인 과정에서 Sony pro 도메인 직접 PDF 다운로드는 HTTP 403, 로컬 PDF 추출은
도구 부재/압축 스트림 추출 실패가 있었습니다. 공식 웹 문서와 원색 기반 행렬 도출 및 수식 테스트로 검증했습니다.
최종 전체 게이트·마이그레이션·산출물 검사에 실패하거나 제외한 제품 테스트는 없습니다.
