# 0.8.6 수동 국소 보정 제거 배포 검증

2026-11-18. versionName0.8.6 / versionCode19. 사용자의 얼굴·배경 품질 피드백으로 실험을 종료한다.

## 수정 파일과 보존 범위

- `ui/SettingsScreen.kt`: 사진 보정 실험 제목·진입 제거. 기존 진단·PC Remote/AF·단발 촬영 진입 유지.
- `perspective/LocalWarp.kt`, `LocalWarpExperiment.kt`, `LocalWarpInput.kt`, `LocalWarpOutput.kt`, `LocalWarpPhotoIO.kt`: 실험 전용 코드5개 삭제.
- `perspective/LocalWarpTest.kt`, `LocalWarpInputTest.kt`, `LocalWarpOutputTest.kt`: 삭제한 기능의 전용 테스트3개/18건 삭제.
- `app/build.gradle.kts`: 버전0.8.6(19). applicationId·minSdk·targetSdk·서명 설정 유지.
- README·CHANGELOG: 기능 종료와 검증 상태 갱신.
- [피드백·Lightroom 조사](../../LENS_CORRECTION_RESEARCH.md) 추가. [과거 실험 기록](../../LOCAL_WARP_EXPERIMENT.md)과 [최초 조사](../../PERSPECTIVE_FEASIBILITY_REVIEW.md)에 종료 결정 표시.
- 이 문서와 빌드 로그·검사 결과 추가.

기존 원본·실험 JPEG를 삭제하지 않는다. 레시피·DB 스키마·권한·의존성을 변경하지 않았다. 이전 전용 입력 캐시는 새 버전에서 사용하지 않으며 시스템/사용자의 캐시 정리 대상이다. 전체 `app/src/main`, `app/src/test`, `app/schemas`를 실험 추가 전 커밋93d42c5와 비교한 결과 차이가 없다. 이는 코드 보존 확인이며 실제 폰 동작 전체를 시험했다는 뜻은 아니다.

## 실행 명령과 결과

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew clean assembleDebug test lint --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
python3 tools/verify_release_apk.py dist/beta7compact-0.8.6.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
.tools/android-sdk/build-tools/35.0.0/aapt dump badging dist/beta7compact-0.8.6.apk
shasum -a 256 dist/beta7compact-0.8.6.apk
git diff --check
```

[전체 빌드 로그](release-0.8.6-build.log) · [검사 결과](release-0.8.6-checks.txt).

- 클린 빌드/test/lint 성공,2분15초, 87개 작업 실행.
- Debug/Release 각각41개 suite·335개 테스트, 실패·오류·skip0. 삭제 전353개에서 실험 전용18개를 뺀 수와 일치한다.
- lint Fatal/Error0, Warning17, Information1. 기존 컴파일 경고도 빌드 로그에 보존했다.
- Room v1→v2→v3 및 v1/v2/v3→v4의 실제 생성 SQL 검사 통과. 스키마/행/인덱스/외래키 무결성 확인.
- Kotlin 소스의 실험 참조0. APK DEX에서도 `LocalWarp`와 `local-warp-inputs` 문자열 없음.
- APK 패키지 `dev.sukbeomh.beta7compact`, 버전0.8.6(19), minSdk29/targetSdk35 확인.
- APK 서명 유효성 및 기존 설치본 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8` 일치.
- APK SHA-256 `15c3a02831822e66a71101658e39e2a2b89827d3ededadd969691f015f311ffd`.

마이그레이션 검사를 빌드 중 먼저 호출했을 때 clean으로 생성 SQL이 아직 없어2개 모두 FileNotFoundError로 실행되지 않았다. 테스트 완료 후 SQL이 생성된 상태에서 다시 실행해 모두 통과했다. 최초 웹 재조회는 로컬 Python 인증서 검증 오류로 실패했으며, 웹 검색 도구로 Sony 공식 페이지를 재조회해 내용을 확인했다. 이들은 앱 빌드/테스트 실패가 아니다.

## 미실행 검증과 남은 문제

이번에는 폰에 업데이트 설치·설정 화면 확인·사진 가져오기/편집/공유·캐시 재연결·테마 유지·카메라 USB/PC Remote/AF·촬영을 실기로 실행하지 않았다. 동일 서명·상향 versionCode 확인은 실제 설치 성공의 대체 검증이 아니다. 사용자 피드백 사진의 정량 품질 평가나 Lightroom 동일 사진 비교도 실행하지 않았다.

기존 α7C 원격 실험의 실기 미검증 상태는 유지한다. 이번 릴리즈는 얼굴 보정을 다른 알고리즘으로 대체하지 않으며 렌즈 프로파일 보정도 추가하지 않는다. 과거0.8.5는 공개 이력으로 남고 최신 배포본은0.8.6이다.

## 배포 대상

기존 서명키로 서명된 `assembleDebug` APK를 `beta7compact-0.8.6.apk`로 게시한다. Release variant의 단위 테스트도 실행했지만 배포 파일을 production release variant로 전환한 것은 아니다. 서명키 파일·비밀번호는 게시하지 않는다.

[GitHub v0.8.6 릴리즈](https://github.com/SukbeomH/beta7compact/releases/tag/v0.8.6) · [APK](https://github.com/SukbeomH/beta7compact/releases/download/v0.8.6/beta7compact-0.8.6.apk).
