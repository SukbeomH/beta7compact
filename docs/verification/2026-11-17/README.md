# 검증 기록

## 실행 환경

macOS arm64, Oracle JDK 21.0.12.1, Gradle Wrapper 8.9. SDK 35 r02와 Build Tools 35.0.0은 Google 공식 저장소에서 내려받아 프로젝트의 무시 경로 `.tools/android-sdk`에 설치했다. 저장소의 Wrapper는 이미 존재하므로 추가하지 않았다.

최초 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew assembleDebug test lint --continue --no-daemon`은 Gradle 배포본 다운로드 중 PKIX 오류로 실패했다(`baseline.log`). 로컬 시스템 인증서를 프로젝트 전용 truststore에 추가해 재시도했다. TLS 검증을 비활성화하거나 시스템 JDK 인증서를 수정하지 않았다.

재시도 환경: `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts`. `local.properties`는 `.tools/android-sdk`를 가리킨다. 둘 다 커밋하지 않는다. `baseline-retry.log`는 병렬 구현과 겹친 통합 작업 로그이므로 변경 전 기준선 성공 증거가 아니다. 변경 전 소스는 `git archive HEAD`로 별도 무시 디렉터리에 보관해 검증한다.

다운로드 체크섬:
- Gradle 8.9 SHA-256: d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab (Wrapper 설정과 동일)
- SDK 35 r02 SHA-1: 0bb560a90a7a2cbd0dd8348224d518b638fe7949
- Build Tools 35.0.0 SHA-1: 93ab8ce91230e067b5add4bfa79919c52b27f072

SDK 체크섬은 Google repository2-1.xml과 대조했다.

## 실기 미검증

카메라 지원 정보 조회·USB 분리·가져오기 재진입, SAF 파일 선택·취소·화면 이동, 실제 런처 모양·소형 가독성은 실기 미실행이다. 파일 공급자의 블로킹 read/open 또는 USB bulk 호출은 반환 전 즉시 취소를 보장하지 않는다. 새 아이콘은 monochrome 레이어가 없으며 옅은 잔여 알파 픽셀이 안전 영역 밖에 있다(아이콘 보고서 참고).

## 기존 범위 검사

`tools/check_m5a_contract.py`와 `check_d2_contract.py`는 과거 특정 작업에서 USB 등 보호 파일을 바꾸지 않았는지 확인하는 고정 기준 커밋/브랜치 검사다. 이번 승인 범위는 USB 정보 파서 확장이므로 그 조건에 실패했다. 이를 제품 동작 회귀로 단정하거나 검사를 약화하지 않았다. 최초 migration 검사 두 개는 JVM 테스트가 SQL 산출물을 만들기 전에 실행되어 파일 없음으로 실패했다(`contracts.log`). 테스트 이후 재실행 결과를 별도로 남긴다.

ADB에서 승인된 Android 폰 한 대를 발견했지만 새 APK 설치·런처·SAF·카메라 실기 테스트는 아직 하지 않았다. 식별자는 기록에서 제외했다.

## 변경 후 통합 검증

`assembleDebug test lint --continue --no-daemon`: 성공 (`baseline-retry.log`, 86 tasks). Debug APK 생성. debug/release 단위 테스트 각각 192개, 실패·오류·skip 0. 같은 테스트를 두 변형에서 실행한 것이므로 서로 다른 384개 테스트로 계산하지 않는다. Room migration 검사 두 개 재실행 성공(`migrations-final.log`).

Lint 오류 0, 경고 17. 새 아이콘 관련 경고는 minSdk29에서 v26 폴더 불필요 1개와 monochrome 없음 2개다. 나머지는 기존 API/의존성/kapt 관련 경고다. monochrome은 이번 리소스에 제공하지 않았다.

APK: `app/build/outputs/apk/debug/app-debug.apk`. SHA-256은 `apk-sha256.txt`. APK는 Git에 추가하지 않는다.

## 수정 파일

- README.md, docs/PLAN_A7C_EXTENSIONS.md, docs/SONY_SUPPORT_RESEARCH.md: 범위·사용법·공식 근거.
- AndroidManifest.xml 및 새 launcher 리소스: β7C adaptive icon.
- usb/Ptp.kt, usb/UsbCamera.kt: 지원 속성 목록 및 기존 USB 소유권 경유 진단.
- ui/CameraDiagnosticsSetting.kt, ui/SettingsScreen.kt: 진단·파일 분석 진입점.
- research/FirmwareInspection.kt, FirmwareInspectionCard.kt: 로컬 읽기 전용 분석.
- DeviceInfoDiagnosticsTest.kt, FirmwareInspectionTest.kt, 테스트 UsbContracts.kt: 새 동작 검증.
- docs/design/ICON-IMPLEMENTATION.md, icon-source: 아이콘 출처·원본·제약.

공유 진단 로그, 원본 보존 정책, 의존성 선언은 변경하지 않았다. 실제 기기 실행은 빌드 성공과 별도로 남아 있다.

## 변경 전 기준선

기준 커밋 `1973c2ff88ff4d37ec58dab1763a2691202a4f47`을 `git archive`로 `.tools/baseline`에 분리했다. 같은 JDK/SDK/Gradle 캐시를 사용했다. debug/release 각각 183개 테스트 통과(실패·오류·skip 0). 이번 변경의 새 테스트는 9개다.

별도 사본의 첫 빌드는 사용자 지정 debug keystore 경로에 키가 없어 패키징에 실패했다(`baseline-clean.log`). 실제 프로젝트의 로컬 debug 키를 무시 경로인 기준선 사본에 복사해 재실행했다. 서명키는 저장소에 추가하지 않았다. 최종 결과는 `baseline-clean-final.log`다.

기준선 최종 assembleDebug/test/lint 성공. 변경 후에도 동일 명령 성공.

## 0.5.0 릴리즈 빌드

사용자 요청으로 versionName 0.5.0, versionCode 7로 올렸다. 같은 assembleDebug/test/lint 재실행 성공(`release-build.log`). APK 메타데이터와 apksigner verify 검증 성공(`apk-signature.log`). `apk-sha256.txt`는 최종 0.5.0 APK 기준으로 갱신했다. 기존 설치본과의 서명 일치는 별도 미검증이다. 릴리즈는 codex/a7c-readonly-research의 소스 커밋에 v0.5.0 태그를 지정하며 master를 변경하지 않는다.

## 0.5.1 서명 수정

설치된 0.4.0(versionCode 6)과 0.5.0(versionCode 7)의 버전 순서는 정상이나 인증서가 달랐다. 사용자 홈의 기존 debug 키가 설치본 인증서와 일치함을 확인했다. 키를 공개하거나 변경하지 않고 같은 키로 0.5.1(versionCode 8)을 빌드했다. assembleDebug/test/lint 성공(`release-0.5.1.log`), 인증서 일치 검사 및 잘못 서명된 APK 거부, `adb install -r` 성공과 설치 버전 확인(`signing-fix.log`). 삭제·데이터 초기화는 수행하지 않았다. 앱 데이터 내용은 별도 열람하지 않았다. 키 관리 절차는 `docs/SIGNING.md`.

## 0.5.2 아이콘 수정

외곽 프레임 제거, 카메라·칩 확대, inset 4dp. 버전 0.5.2/9. assembleDebug/lint 성공(`release-0.5.2.log`). APK 인증서가 기존 설치본과 일치함을 게시 전 확인(`signing-0.5.2.log`). 기능 코드 변경이 없는 리소스·버전 수정이므로 단위 테스트는 재실행하지 않았다. 실제 런처 확인·설치는 미실행. 이미지 검증 수치는 아이콘 문서에 기록했다.
