# 0.8.7 전체 사진 수동 왜곡·디자인 개선 배포 검증

2026-11-18. versionName0.8.7 / versionCode20. 왜곡 탭·디자인 규칙 검토 결과와 렌즈 프로파일 조사 문서를 포함한다.

## 수정 파일과 기능 범위

- `recipe/ManualDistortion.kt`, `DistortionRenderer.kt`: 원본 좌표의 전체 사진 역매핑, 가장자리 처리, 타일 입력 영역·bilinear 보간·행 단위 취소, Android 미리보기/내보내기 연결.
- `recipe/PhotoRenderer.kt`, `ui/RecipeScreen.kt`: 값0의 기존 경로 유지. 기하 변형 후 기존 색 처리·EXIF 방향 정립, JPEG 저장 연결. 원본을 덮어쓰지 않는다.
- `recipe/Recipe.kt`, `CpuEngine.kt`, `GpuEngine.kt`, `RecipeShare.kt`: 왜곡값과 색 처리 버전을 v3으로 저장·공유. 기존 v1/v2 색 처리 유지. `.cube`는 색만 담도록 유지하고 제외 항목 안내.
- `ui/ColorEditor.kt`, `RecipeScreen.kt`, `UiPolicy.kt`: 별도 왜곡 탭·가로 스크롤 탭·방향/값·0 초기화·공유 범위 안내·슬라이더 semantics·v3 수정 상태 판정. 기존 배경과 고정 저장 작업 유지.
- `ManualDistortionTest.kt`: 신규11개. `RecipeEngineTest.kt`: 지원 버전 증가에 맞춰 미지원 버전 거부 대상 갱신.
- `app/build.gradle.kts`, README, CHANGELOG: 버전/사용법/호환성/검증 갱신.
- [기능 문서](../../MANUAL_DISTORTION.md), [디자인 검토](../../MANUAL_DISTORTION_DESIGN_REVIEW.md), [개발 검증](manual-distortion.md).
- [렌즈 프로파일 조사](../../LENS_PROFILE_FEASIBILITY.md), [Viltrox 촬영 준비](../../VILTROX26_VALIDATION.md), [권리 조건 추가 조사](../../VILTROX26_PROFILE_RIGHTS.md)와 기존 조사 후속 링크. 제조사 LCP/ZIP/PDF 파일은 포함하지 않았다.

왜곡값은 레시피·기본 레시피·일괄 적용에 재사용된다. v3 레시피는 구버전 앱이 읽을 수 없으며, `.cube`에는 왜곡이 포함되지 않는다. 자동 렌즈 보정·얼굴 복원·새 카메라 명령은 추가하지 않았다. DB 스키마·앱 ID·SDK 수준·서명 설정은 유지했다.

## 실행 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew clean assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.7.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
.tools/android-sdk/build-tools/35.0.0/aapt dump badging dist/beta7compact-0.8.7.apk
shasum -a 256 dist/beta7compact-0.8.7.apk
git diff --check
```

[전체 빌드 로그](release-0.8.7-build.log) · [검사 결과](release-0.8.7-checks.txt).

## 미실행 항목과 남은 문제

사용자의 실제 사진 품질 평가는 후속 진행이다. 이번 APK의 폰 설치·실화면·4테마/큰 글꼴·TalkBack·실제 Android 디코드/GPU/MediaStore 저장·카메라 USB/PC Remote/AF·단발 촬영은 실행하지 않았다. 연결 기기를 과거 개발 검토에서 확인했으나 최신 APK의 화면 검증으로 계산하지 않는다.

합성 픽셀 테스트는 좌표/타일/레시피/취소 검증이며 실제 인물의 자연스러움이나 JPEG 저장 성공을 입증하지 않는다. 효과를 켜면 일부 화각이 잘리고, 축소 미리보기와 저장 결과의 세부 선명도가 다를 수 있다. 폰 처리 시간/최대 메모리는 미측정이다. 기존 원격 실험의 α7C 실기 미검증 상태도 유지한다.

디자인 검토는 코드와 프로젝트 규칙을 대조한 결과다. 최신 APK의 스크린샷 기반 감사나 전체 접근성 통과로 표현하지 않는다. 초기 개발 중 탭 이동 컴파일 오류와 수정은 [개발 검증 기록](manual-distortion.md)에 보존한다.

## 배포 형식

기존 인증서로 서명한 `assembleDebug` APK를 게시한다. Release variant 단위 테스트도 실행하지만 배포 파일을 production release variant로 전환한 것은 아니다. 개인키/비밀번호·사용자 사진·제조사 프로파일은 릴리즈에 포함하지 않는다.

[GitHub v0.8.7 릴리즈](https://github.com/SukbeomH/beta7compact/releases/tag/v0.8.7) · [APK](https://github.com/SukbeomH/beta7compact/releases/download/v0.8.7/beta7compact-0.8.7.apk).

## 최종 결과

- clean assembleDebug/test/lint 성공,2분16초.87개 작업 실행.

- Debug/Release 각각42개 suite·346개 테스트, 실패·오류·skip0. 기존335개와 신규11개.
- Room 마이그레이션 검사2개 및 diff check 통과.
- lint Fatal/Error0, Warning17, Information1.
- 패키지 dev.sukbeomh.beta7compact, 버전0.8.7(20), minSdk29/targetSdk35 확인.
- 기존 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8` 일치 및 APK 서명 검증 통과.
- APK SHA-256 `52474134897118f0e2c9e0f10aa3dc4143791719023a72008b2f34a34a53bbca`.
