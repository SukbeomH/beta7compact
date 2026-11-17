# 0.8.0 AF·단발 촬영 실험 배포 검증

2026-11-17. versionName 0.8.0 / versionCode 13.

## 변경 파일

- `remote/SonyCaptureTransport.kt`, `SonyCaptureSession.kt`: 고정 반셔터/셔터 제어, 엄격한 조건 검사, 단일 시도·해제·취소 처리. typed 조건 거부 사유 추가.
- `remote/SonySessionWireTransport.kt`: 명시적으로 활성화한 capture transport만 outbound DATA 지원. 기존 진단 기본값은 비활성.
- `remote/SonyRemoteSession.kt`: 광고 명령을 snapshot에 보존.
- `remote/CaptureExperiment.kt`: Android 연결·실행·정리·공유용 보고서. 원시값과 객체 핸들 제외.
- `usb/UsbCamera.kt`: 같은 USB 잠금의 useCapture와 상태 관찰·테스트 factory. 기존 가져오기/진단 본문 유지.
- `ui/CaptureExperimentSetting.kt`, `SettingsScreen.kt`: 작업 선택, 매 실행 확인, 단발 추가 확인, 결과 해석·미리보기·공유.
- capture 관련 wire/controller/integration/runner 테스트와 문서. 버전 증가, 기존 서명 설정 유지.

이전 3단계 모의 구현도 이 릴리즈에 포함한다. UI, runner/보고서, 조건 사유와 검토를 서브에이전트로 나눠 진행했다.

## 검증 방법

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
python3 tools/verify_release_apk.py app/build/outputs/apk/debug/app-debug.apk \
  --apksigner .tools/android-sdk/build-tools/35.0.0/apksigner
```

JDK21 / Gradle8.9 / SDK35 / Build Tools35.0.0. [빌드 로그](release-0.8.0-build.log).

실제 폰 설치·UI 조작·AF·촬영·카드 저장・공유 발송·모드 복귀는 수행하지 않았다. 모든 테스트 입력은 합성이다. 실제 저장 결과는 앱이 확정하지 않으며 사용자가 카메라에서 확인한다. 우선순위/저장 조건은 앱이 자동 설정하지 않아 실제 장치에서 거부될 수 있다.

## 결과

- assembleDebug/test/lint 성공, 1분36초, 86 tasks.
- Debug/Release 각각318개 통과, 실패·오류·skip0. [집계](tests-0.8.0.txt). 두 변형의 동일 테스트를 별개로 합산하지 않는다.
- lint 오류0, 기존 경고17. 신규 capture 관련 파일 지적 없음. Room migration 검사2개 통과.
- APK 메타데이터 versionName0.8.0/versionCode13, 패키지 dev.sukbeomh.beta7compact 확인.
- [서명 검사](signing-0.8.0.log): 기존 설치본 인증서 SHA-256 eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8과 일치.
- 배포 파일 beta7compact-0.8.0.apk. [SHA-256](apk-0.8.0-sha256.txt). 키와 APK는 Git에 추가하지 않는다.
- git diff --check 통과. master는 변경하지 않고 작업 브랜치 소스 커밋에 릴리즈 태그를 연결한다.
