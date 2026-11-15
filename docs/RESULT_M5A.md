# M5a 편집기·레시피 공유 결과

2026-11-15 UTC. 브랜치 `feat/m5a-editor-share`.
제품 기준 `f9681a5`, 작업 지시/작업 시작 `734847f`, 구현 커밋 `5b83c993f29f9a162168f722fd1239caebfcae57`.
versionCode **5**, versionName **0.3.0**. 이 문서는 구현 커밋 후 결과 기록입니다.

## 구현 범위

- 레시피 JSON v2: RGB 공통/R/G/B 톤커브 각 2~16점, 단조 cubic Hermite 보간,
  8색 HSL(각 ±100), 하이라이트/섀도/대비/채도/바이브런스/스플릿 톤을 추가했습니다.
  곡선 추가·드래그 이동·선택 삭제/초기화를 제공합니다. GPU는 CPU와 동일한 점/접선을 사용합니다.
- v2는 입력 변환 → 선형 WB/노출 → 톤·곡선·HSL/채도/스플릿 톤 → LUT 강도 → 그레인/비네트입니다.
  v1 읽기와 기존 LUT→WB/노출 순서, 기존 작업/결과 스냅샷 의미는 유지합니다.
  v1에서 추가 색 편집을 시작하면 v2로 전환합니다. 수식/근거는 `docs/M2-ENGINE.md`.
- 현재 색 보정을 33³ red-fastest `.cube`로 굽고 SAF 새 문서로 내보냅니다. 그레인/비네트는 제외합니다.
  **M2 LUT 입력 계약인 인코딩 sRGB를 유지하므로 입력 변환은 LUT 앞에 별도로 유지합니다.**
  S-Log3 입력이면 inputTransform은 유지하고, 구워진 WB/노출/톤 등은 중립으로 둔 Recipe에
  구운 LUT를 적용합니다. UI의 ‘색 보정 초기화 · 입력 변환 유지’와 `.cube` 주석으로 안내합니다.
  입력 변환까지 통째로 굽는 방식의 정밀도 문제를 피하고 기존 LUT 입력 계약을 보존했습니다.
- `b7c1:` + base64url(zlib UTF-8 JSON) + SHA256 앞 8바이트 체크섬 코드,
  붙여넣기/공유 인텐트 수신, 텍스트 공유와 QR 표시를 구현했습니다.
  코드 24,000자/해제 JSON 16,384바이트/객체 깊이/이름 길이/모든 편집 범위를 검사합니다.
  변조·압축 폭탄·잘못된 필드는 원문 없는 한 줄 사유로 거부합니다.
- 사용자 LUT는 데이터 없이 id/표시 이름만 공유하고 ‘LUT 필요(이름)’을 표시합니다.
  수신 기기에 LUT가 없으면 보관만 하고 적용은 보류합니다. 필요한 LUT를 가져와 선택한 후
  보관 항목에서 명시적으로 현재 LUT에 연결할 수 있습니다. 내장 LUT는 id로 참조합니다.
- QR은 직접 작성한 Model 2 byte/L/mask0 인코더(v1/v20/v40)와 4모듈 여백입니다.
  외부 구현 코드를 복사하거나 의존성으로 추가하지 않았습니다.
  **M5a는 QR 표시만 구현했습니다. 스캔은 카메라 앱으로 읽은 텍스트 붙여넣기로 대체합니다.**
  2,953자 초과 코드는 QR 용량 사유와 텍스트 공유를 제공합니다. 벡터 출처/형식은 `docs/M5A-SHARING.md`.
- 편집 기본/톤커브/HSL/효과 탭, 슬라이더/이름 더블탭 초기화, 100상태 실행 취소·다시 실행,
  보관·이름 변경·복제·삭제·즐겨찾기·공유를 연결했습니다.
  이름 충돌은 Room 트랜잭션 안에서 (2), (3)…으로 처리합니다. 폭 700dp 이상에서
  미리보기/패널을 2분할합니다. 원본/결과 파일을 레시피 삭제와 함께 지우지 않습니다.

## 실행 검증

```sh
ANDROID_HOME=/home/agent/camera-recipe-app/.android-sdk \
ANDROID_SDK_ROOT=/home/agent/camera-recipe-app/.android-sdk \
./gradlew assembleDebug test lint assembleDebugAndroidTest --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/check_m5a_contract.py
git diff --check 734847f
```

| 검사 | 실제 결과 |
| --- | --- |
| 최종 전체 게이트 | 종료 0, BUILD SUCCESSFUL, 2분 3초, 111 tasks |
| Debug/Release JVM | 각각 137건(기존 125 + M5a 12), 실패/오류/건너뜀 0 |
| lint | 오류 0, 기존 경고 13개 유지 |
| androidTest | 확장 GPU/CPU 일치·기존 원본 보존/EXIF 계측 APK 빌드 통과 |
| M1/M2 migration | 모두 통과, Room v4 schema/원본 필드/FK/integrity 보존 |
| M5a 경계 검사 | BLE/USB/가져오기/원본 저장/로그/LUT 자산/DB/의존성 변경 없음 |
| 공백 | staged 및 작업 시작 `734847f` 대비 통과 |
| APK | aapt version 5/0.3.0, 내장 LUT 27개와 전체 LUT 자산/manifest/NOTICE bytes 일치 |

새 JVM 검증은 임의 곡선 150개(증가·감소·평탄·반전)의 구간별 오버슈트 0,
HSL 가중 합=1/모든 경계·빨강 랩 연속성, v1 읽기/순서와 v2 왕복,
범위·NaN·길이·깊이·압축 상한·변조 거부, 사용자 LUT/한글 이름과 최대 제어점 공유 왕복,
이름 충돌/undo/redo 이력 상한을 검사합니다.
QR은 독립 시스템 oracle로 생성한 v1/v20/v40의 모든 모듈과 비트가 일치했습니다.
새 라이브러리 설치는 하지 않았습니다.

### 구운 LUT 재가져오기 ΔE76

`.cube` 작성 → CubeParser 재가져오기 → 실제 저장과 같은 B7LT uint16 양자화/재로딩 →
기본 Recipe(원래 입력 변환 유지)에 적용한 결과를 원래 Recipe CPU 직접 색 처리와 비교했습니다.
D65 Lab ΔE76, 시드 1729, 각 2,500패치(총 15,000), 공간 효과 제외입니다.
S-Log3 구성은 유효 sRGB 장면 패치를 역 gamut 행렬/Sony 수식으로 인코딩해 사용했습니다.

| 구성 | p95 ΔE76 | 최대 ΔE76 |
| --- | ---: | ---: |
| 중립 | 0.001809 | 0.002467 |
| 톤커브·8색 HSL·톤·스플릿 | 0.445334 | 1.599885 |
| 위 편집 + WB/EV | 0.501011 | 1.446810 |
| 위 편집 + S-Log3 입력 해석 유지 | 0.415020 | 1.510950 |
| 위 편집 + 필름 LUT 강도 .6 | 0.306377 | 1.205591 |
| v1 순서 + 필름 LUT/EV | 0.000475 | 0.277877 |

모든 구성에서 **p95 < 1**입니다. 최대값 전체 <1이나 모든 가능한 극단 파라미터/좁은 DOMAIN의
근사 오차를 보증하는 값은 아닙니다. 그레인/비네트를 바꿔도 구운 격자가 동일함을 검사했습니다.
실측 원본 로그는 `app/build/reports/m5a-bake.txt`입니다.

## 발견·수정 및 로그

- 첫 게이트는 공유 파서의 split 구문 오류로 컴파일에 실패해 수정했습니다.
- 두 번째는 부동소수점 출력의 1 미세 초과와 곡선 검사 구간 끝점 합성 오차를 검출했습니다.
  CPU/GPU 색 출력 범위를 clamp하고 테스트 입력 좌표를 실제 해당 구간에 제한했습니다.
- S-Log3 입력 해석까지 하나의 33³ 격자에 포함한 초기 실험은 p95 ΔE76 6.123955,
  유효 장면 패치에서도 2.362392로 실패했습니다. 입력 해석을 기존 M2 sRGB LUT 계약 앞에
  유지하도록 굽기/재적용 경로를 수정했습니다. 임계값이나 ΔE 정의를 완화하지 않았습니다.
- `f9681a5` 전체 대비 공백 검사는 작업 전 `734847f`가 추가한 `docs/TASK_M5A.md:14`의
  기존 끝 공백 1건으로 실패합니다. 작업 지시문은 그대로 보존하고, 이번 구현 차이는
  `734847f` 대비와 staged diff로 통과했습니다. 검증 우회 옵션은 사용하지 않았습니다.
- 최종 제품 테스트에 남은 실패/건너뜀은 없습니다. 기기 실행은 아래와 같이 별도 미검증입니다.

초기/중간: `/tmp/beta7-m5a-gate-1.log`, `/tmp/beta7-m5a-gate-2.log`,
`/tmp/beta7-m5a-targeted.log`, `/tmp/beta7-m5a-targeted-2.log`.
수정 후 전체 통과: `/tmp/beta7-m5a-gate-final.log`(136건),
최종: `/tmp/beta7-m5a-gate-closeout.log`(137건, B7LT 양자화 포함).

## 산출물과 남은 실기 확인

- 로컬 APK: `dist/beta7compact-0.3.0-debug.apk`. Gradle 출력과 bytes 동일.
  SHA256 `b4febcaf10fca57b9b33824edee51da2bc3c480be70729cb0a72f38f3d0f8da8`.
  dist/APK/AAB는 Git 제외 상태이며 커밋하지 않았습니다.
- `adb devices` 연결 0대이므로 connectedDebugAndroidTest/실제 GLSL 실행/실제 GPU 색차,
  Fold8 펼침 UI·더블탭·터치 곡선·SAF·공유 인텐트·QR 카메라 인식은 실기 미검증입니다.
  계측 APK 빌드는 GPU 런타임 검증을 대신하지 않습니다.
- M2의 24MP 저장 성능/메모리/ARW·EXIF/프로세스 중단 후 pending 고아 한계는 유지합니다.
  원본·사진 결과·기존 작업/DB migration·BLE/USB/PTP/가져오기 코드를 바꾸지 않았습니다.
- QR 직접 스캔, RAW 현상, 새 카메라 제어는 구현하지 않았습니다. 외부 의존성 변경도 없습니다.
- push 대상은 `feat/m5a-editor-share` 하나입니다. force/--no-verify/git add -A/APK 커밋을 사용하지 않습니다.
