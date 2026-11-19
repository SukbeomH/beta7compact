# 0.8.12 검증 기록

2026-11-19 · 카메라 화면과 설정의 실험 기능 정리

## 실행한 검증

- `GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew clean assembleDebug test lint --continue --no-daemon --offline`: 성공. Debug와 Release 단위 테스트 각각 220개, 실패·오류·건너뜀 0개. 제거된 기능의 테스트도 함께 제거했으므로 이전 버전과 테스트 개수는 직접 비교할 수 없다.
- Android lint 오류 0개, 경고 6개. `tools/check_m1_migration.py`, `tools/check_m2_migration.py`: Room 마이그레이션과 무결성 검사 통과. 최초 실행은 클린 빌드가 테스트 산출물을 만들기 전이라 실패했으며, 빌드 완료 후 재실행해 통과했다.
- 패키지 `dev.sukbeomh.beta7compact`, versionCode 25, versionName 0.8.12, 최소 API 29. APK 서명 검증과 기존 설치본 인증서 일치 확인: SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8`.
- Samsung SM-F971N의 0.8.11을 `adb install -r`로 업데이트. 설치된 APK와 배포 APK의 SHA-256 일치: `5cec5afd8f777c5f08a66ad998653edeac669aa15f9ed8bea7d2f3aad6169009`.
- 폰에서 카메라 탭, Wi-Fi 사진 가져오기 대화상자와 설정의 버전·진단 영역을 확인했다. 설정 하단 카드와 업데이트 카드의 폭이 같은 것을 화면에서 확인했다. 카메라 탭의 실험·예정 영역과 설정의 중단 기능은 표시되지 않았다.
- 웹 시안 로컬 HTTP 200, JavaScript 문법, 카메라·Wi-Fi 데모 목록·설정 화면 확인. 당시 소유자 전용 Sites에 게시했다. 이후 전체 공개로 변경했으며 [공개 접속 검증](screen-studio-public.md)을 별도로 기록했다.

[클린 빌드 로그](release-0.8.12-build.log)는 후행 공백만 제거했다. 배포 파일은 기존 서명키로 서명된 `assembleDebug` APK다. Release 단위 테스트는 실행했지만 release variant APK는 생성하지 않았다.

## 실행하지 않은 검증

실제 α7C에서 Wi-Fi 사진 목록·다운로드, USB 사진 가져오기와 카메라 연결 진단은 실행하지 않았다. 다른 카메라 기종의 호환성, 사진 편집 결과의 실기 품질과 전체 UI 회귀 검증도 이번에 실행하지 않았다. 폰 화면 표시 확인은 카메라와의 통신 성공을 뜻하지 않는다.
