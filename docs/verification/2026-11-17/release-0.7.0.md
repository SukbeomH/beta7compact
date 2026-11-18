# 0.7.0 PC Remote 실험 진단 배포 검증

2026-11-17. versionName 0.7.0, versionCode 11. 사용자 요청으로 실험 진단을 앱에 연결하며 실기 확인은 추후로 유지한다.

## 변경 파일

- `remote/AndroidSonyRemoteWire.kt`: Android bulk/interrupt 전송, 활성 USB 구성·alternate 선택, 요청 회수·자원 정리.
- `remote/RemoteDiagnostics.kt`, `RemoteDiagnosticReport.kt`: 공유 가능한 구조화된 실행·오류·종료 보고서.
- `remote/SonyPropertyParser.kt`: 미지원 속성 code/type을 가진 예외. 기존 엄격한 파싱 범위 유지.
- `remote/SonySessionWireTransport.kt`: 내부 deadline을 사용자 취소와 구분해 보고 가능하게 함.
- `usb/UsbCamera.kt`: 같은 USB 잠금을 사용하며 연결 전 세션 상태 관찰 콜백 추가. 기존 가져오기/진단 로직 유지.
- `ui/PcRemoteDiagnosticsSetting.kt`, `ui/SettingsScreen.kt`: 별도 확인·실행·미리보기·공유 화면.
- `app/build.gradle.kts`: 0.7.0/11. 서명 설정은 유지.
- Android USB/보고서/wire 테스트와 테스트 doubles. 이전 단계의 파서·세션·모의 통신 구현도 함께 포함한다.
- README·CHANGELOG·실험 진단 사용법·검증 기록.

Android 어댑터, 보고서, UI를 서브에이전트로 병렬 구현했다. UI 담당 에이전트가 통합 코드를 별도로 검토했고 주 작업자가 deadline 처리, 회귀 테스트, 릴리즈를 담당했다.

## 검증 명령

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/verify_release_apk.py app/build/outputs/apk/debug/app-debug.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
```

JDK 21, Gradle Wrapper 8.9, Android SDK 35 / Build Tools 35.0.0. [빌드 로그](release-0.7.0-build.log).

## 결과

- `assembleDebug test lint --continue --no-daemon`: 성공, 1분 34초, 86 tasks.
- Debug/Release 각각 281개 통과, 실패·오류·skip 0. 같은 테스트를 두 변형에서 실행한 결과다. [집계](tests-0.7.0.txt).
- lint 오류 0, 경고 17. 신규 remote/UI 파일 지적 없음. Room migration 검사 2개 통과.
- APK 메타데이터: `dev.sukbeomh.beta7compact`, versionCode 11, versionName 0.7.0.
- [서명 검증](signing-0.7.0.log): 기존 설치본 인증서 SHA-256 `eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8`와 일치.
- 배포 파일 `beta7compact-0.7.0.apk`, [SHA-256](apk-0.7.0-sha256.txt). 키·APK는 Git에 추가하지 않는다.
- `git diff --check` 통과. 릴리즈 대상은 현재 작업 브랜치의 소스 커밋이며 master는 변경하지 않는다.

## 미실행

실제 폰 설치·UI 조작·공유 앱으로의 전송·α7C 연결·원격 프로토콜 실기·카메라 모드 복귀는 수행하지 않았다. 테스트 데이터는 합성이며 실제 α7C 캡처가 아니다. Android UsbRequest cancel/drain의 실제 드라이버 동작은 사용자 실기 확인이 필요하다.

모드 전환 SDIO 연결 외의 촬영·설정 쓰기·PriorityMode·펌웨어 명령은 추가하지 않았다. 실험 기능은 기본 자동 실행되지 않으며 매번 사용자 확인이 필요하다. 보고서는 기기 문자열·원시 응답·속성값·사진·예외 메시지를 포함하지 않는다.
