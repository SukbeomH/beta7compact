# 0.7.1 실험 진단 안내 검증

2026-11-17. versionName 0.7.1 / versionCode 12.

## 변경

- `ui/PcRemoteDiagnosticsSetting.kt`: 4개 접이식 안내와 결과별 해석 추가. 기존 모드 변경 경고·실행 확인란은 접이식 영역 밖에 유지한다.
- `app/build.gradle.kts`: 버전 증가. 기존 서명 설정 유지.
- README·CHANGELOG·실험 진단 사용법 갱신.
- USB/세션/진단 보고서·공유 로그 생성 코드는 변경하지 않았다.

## 검증 범위

`GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon`

[빌드 로그](release-0.7.1-build.log). JDK21 / Gradle8.9 / SDK35 / Build Tools35.0.0.

안내 UI 변경이므로 문구를 그대로 비교하는 새 단위 테스트는 추가하지 않았다. 기존 회귀 테스트를 재실행하고 Compose 컴파일·lint로 검증한다. 독립 에이전트가 결과 해석과 확인란 보존을 코드 검토했다.

실제 폰의 화면 배치·펼치기 조작·공유 메뉴·설치, α7C 연결 테스트는 수행하지 않았다. 사용자가 추후 실기 확인한다.

## 결과

- assembleDebug/test/lint 성공, 1분31초, 86 tasks.
- Debug/Release 각각281개 통과, 실패·오류·skip0. [집계](tests-0.7.1.txt).
- lint 오류0, 기존 경고17.
- versionName0.7.1/versionCode12 확인. [서명 검사](signing-0.7.1.log)에서 기존 인증서와 일치.
- 배포 파일 beta7compact-0.7.1.apk. [SHA-256](apk-0.7.1-sha256.txt).
- git diff --check 통과. master 변경 없이 작업 브랜치 소스 커밋으로 릴리즈한다.
