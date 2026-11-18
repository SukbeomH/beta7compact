# 전체 사진 수동 왜곡 조절 검증

2026-11-18 개발 당시 기록. 공개0.8.6(4d296da)을 기준으로 검증한 개발 APK였으며 이 단계에서는 릴리즈를 게시하지 않았다. 이후 사용자 요청에 따른 [0.8.7 배포 검증](release-0.8.7.md)은 별도로 기록한다. 렌즈 프로파일 자동 보정과 과거 수동 국소 보정 화면은 추가하지 않았다.

## 수정 범위

- `recipe/ManualDistortion.kt`: 원본 좌표의 전체 사진 역매핑·가장자리 처리·입력 영역 계산·bilinear 샘플링·행 단위 취소.
- `recipe/DistortionRenderer.kt`: Android 미리보기와 BitmapRegionDecoder 타일 연결.
- `recipe/PhotoRenderer.kt`, `ui/RecipeScreen.kt`: 기존 색 처리 앞에 기하 변환 연결.0에서는 기존 경로를 유지하고 원본을 덮어쓰지 않는다.
- `recipe/Recipe.kt`: 기본값0과 v3 직렬화. `colorVersion`으로 기존 v1/v2의 색 처리 순서를 보존한다.
- `recipe/CpuEngine.kt`, `GpuEngine.kt`: 직렬화 버전과 별도로 색 처리 순서를 선택한다.
- `ui/ColorEditor.kt`: 별도 왜곡 탭의 슬라이더·0 초기화·크롭/레시피 적용 안내. `UiPolicy.kt`: v3의 수정 여부 판정에서 버전 정규화 예외 방지.
- `recipe/RecipeShare.kt`: `.cube`에서 공간 효과가 제외됨을 주석에 명시. 기존 레시피 공유·히스토리·대기열 경로 재사용.
- `ManualDistortionTest.kt`: 아래11개 신규 테스트. `RecipeEngineTest.kt`: 미지원 버전 거부 대상을4로 변경. v3의 필수 필드/구버전 혼합 거부는 새 테스트로 검증.
- README·[기능 사용법/한계](../../MANUAL_DISTORTION.md)·[디자인 규칙 검토](../../MANUAL_DISTORTION_DESIGN_REVIEW.md)·이 문서·로그/검사 결과.

새 카메라 명령·펌웨어 접근·프로파일 파일·외부 의존성·DB 스키마 변경은 없다. 연구 문서의 기존 미커밋 변경도 유지했다.

## 신규 테스트의 관찰 대상

1. 0에서 합성 픽셀 전체 항등.
2. 미리보기와 원해상도 좌표 대응, 가로/세로/단일 축 처리.
3. 배럴/핀쿠션의 반대 방향과 고정 중심.
4. 대칭·축 방향 단조성·유효 입력 영역.
5. 타일 입력 범위가 보간 이웃을 포함.
6. 전체 렌더와 여러 타일 렌더의 픽셀 일치로 이음새 확인.
7. bilinear 혼합값과 행 단위 취소 중단.
8. 기존 레시피0 기본값, v3 직렬화·공유·undo/redo.
9. 비정상 수치·구버전에 넣은 왜곡 값·필수 값 누락 거부.
10. 구형 색 처리 순서 보존과 v3 수정 상태 판정.
11. v3의 기존 색 처리 및 공간 변형의 `.cube` 제외.

위 테스트는 Android Bitmap 디코더나 실제 GPU를 실행한 테스트가 아니다. `DistortionRenderer`가 호출하는 순수 Kotlin 매핑/샘플링을 합성 데이터로 검증한다. 수학적 대응이 실제 JPEG의 인물 자연스러움이나 Android 렌더링 성공을 보증하지 않는다.

## 실행 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-manual-distortion-debug.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
```

최초 연결 후 빌드와 기존 테스트는 성공했다. 이후10개 테스트를 추가하고 성공했으며, 최종 연결 검토에서 `isAdjusted`의 v3 정규화와 v1 색 처리 순서를 보완해11개로 늘렸다. 별도 왜곡 탭 이동 중 분기를 잘못된 위치에 넣어 컴파일 오류1회가 발생했고, 올바른 when 분기 위치로 수정했다. [탭 이동 시 실패 로그](manual-distortion-tab-initial-build.log)와 [최종 빌드 로그](manual-distortion-build.log)를 구분해 보존한다.

## 미실행 항목과 남은 문제

- 사용자 실제 사진의 품질·선호·배경/얼굴 자연스러움 평가는 후속 진행이다.
- 폰 화면·큰 글꼴/가로창·탭 배치, Android EXIF8방향 디코드/표시, 실제 GPU 셰이더 실행과 MediaStore 저장, 공유 앱/일괄 대기열의 실기 동작은 이번에 실행하지 않았다.
- CPU 기하 변환의 실제 폰 처리 속도/최대 메모리는 측정하지 않았다. 네이티브 디코딩 호출의 즉시 취소도 확인하지 않았다.
- 미리보기는 축소본을 보간하므로 저장 파일의 세부 선명도와 다를 수 있다. 효과를 켜면 일부 화각을 잃고 주변부 인물/배경이 부자연스러워질 수 있다.
- 왜곡값을 담은 v3 레시피는 구버전 앱이 읽을 수 없다. `.cube`에는 포함되지 않는다. 레시피·기본 레시피·일괄 적용에서는 수동 왜곡값도 재사용되므로 사진별 확인이 필요하다.
- 배포 버전 번호는 올리지 않았다. 개발 APK는0.8.6(19)이며 기존 앱의 자동 업데이트용 새 릴리즈가 아니다. 공개 릴리즈·폰 설치·카메라 실기는 진행하지 않았다.

## 최종 결과

- 디자인 규칙 검토 후 가로 스크롤 왜곡 탭·방향 이름·레시피/LUT 안내·슬라이더 semantics를 반영한 상태에서 assembleDebug/test/lint 성공,1분35초.
- Debug/Release 각각42개 suite·346개 테스트, 실패·오류·skip0. 기존335개와 신규11개.
- Room 마이그레이션 검사2개, APK 서명 유효성/기존 인증서 일치, diff check 통과.
- lint Fatal/Error0, Warning17, Information1.
- APK `dist/beta7compact-manual-distortion-debug.apk`, 버전0.8.6(19).
- APK SHA-256 `050e354914e00b39b4adda8f8f30392f98e27072a1ec7a0be0787712d5942fa3`.
- [검사 결과](manual-distortion-checks.txt). 공개 릴리즈·설치·실기 품질 평가 미실행.
