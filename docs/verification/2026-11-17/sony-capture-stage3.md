# 3단계 AF·단발 촬영 모의 검증

2026-11-17. [설계·소스·제한](../../SONY_CAPTURE_STAGE3.md).

## 수정 파일

- `remote/SonyCaptureTransport.kt`: 고정된 반셔터·셔터 누름/해제만 허용하는 별도 계약.
- `remote/SonySessionWireTransport.kt`: 기본 비활성 capture control, outbound DATA, 기존 framing 재사용.
- `remote/SonyCaptureSession.kt`: 단일 AF/촬영 시도, 모드·타입·광고 확인, 초점 전이, 해제·취소·오류 처리.
- `remote/SonyRemoteSession.kt`: 초기 광고 operations를 불변 snapshot에 보존. 기존 진단 동작/로그 형식은 유지.
- `usb/UsbCamera.kt`: 동일 Mutex를 사용하는 별도 `useCapture` scope. 기존 use/inspect/collectDiagnostics/useRemote 본문은 변경하지 않음.
- `SonyCaptureWireTest.kt`, `SonyCaptureSessionTest.kt`, `SonyCaptureIntegrationTest.kt`: 모의 바이트/상태/소유권 검증.
- 개발 순서 문서와 3단계 설명·본 검증 기록·빌드 로그.

wire, 컨트롤러, 독립 근거·코드 검토를 서브에이전트로 병렬 진행했다. 주 작업자가 계약·USB 소유권·실제 codec을 통과하는 통합 모의 테스트를 구성했다.

## 실행

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew assembleDebug test lint --continue --no-daemon
```

[빌드 로그](sony-capture-stage3-build.log). JDK21 / Gradle8.9 / Android SDK35 / Build Tools35.0.0.

최초 실행에서는 종료 함수의 추론 반환형으로 인해 controller 테스트 클래스가 JUnit4 초기화 오류를 냈다(13개 테스트 미실행). `close(): Unit` 계약으로 수정했다. 또한 lint가 `BigInteger.longValueExact()`의 API31 요구를 발견해 Android10(API29)에서도 가능한 부호·비트 길이 검사 후 `toLong()` 변환으로 바꿨다. 수정 후 동일 명령을 재실행하며 [최종 로그](sony-capture-stage3-final.log)에 기록한다.

[두 번째 실행](sony-capture-stage3-retry.log)은 304개 중 오류 보존 테스트 1개가 실패했다. coroutine stack recovery가 IOException 복사본을 만들 수 있어 객체 identity 비교가 부적절했다. 원래 오류가 cause chain에 유지되고 해제 오류가 suppressed로 보존되는지를 검사하도록 수정했다. 이 수정은 production 동작을 변경하지 않는다.

## 최종 검증 결과

- 수정 후 assembleDebug/test/lint 성공: 52초, 86 tasks.
- Debug/Release 각각 304개 통과, 실패·오류·skip 0. 기존 281개에 신규 23개(wire 8, controller 13, 통합/소유권 2)를 추가했다. 두 변형의 동일 테스트를 608개 고유 테스트로 계산하지 않는다. [집계](sony-capture-stage3-tests.txt).
- lint 오류 0, 기존 경고 17. 신규 capture 파일 lint 지적 없음.
- `git diff --check` 통과. production 검색에서 capture 메서드와 useCapture 정의 외의 호출 및 allowCaptureControls=true 설정이 없음을 확인했다.
- Debug APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성했다. 실제 배포된 0.7.1 릴리즈는 변경하지 않았다.

## 미실행·후속

실제 카메라·폰 연결, 반셔터/셔터 전송, 저장 파일 확인·다운로드, 실제 취소·연결 유실 복구는 수행하지 않았다. 모든 fixture는 합성이다. 신형/미지원 snapshot 자료형도 여전히 지원하지 않는다.

AF·촬영 UI와 기존 실험 진단에는 이번 control API를 연결하지 않았다. 버전은 0.7.1/12이며 APK는 로컬 빌드 검증 산출물이다. 설치·서명 재검증·릴리즈 게시는 이번 단계에서 수행하지 않는다.

결과 SUBMITTED_UNCONFIRMED는 저장 성공이 아니다. PriorityMode와 카드 저장 대상 조건을 자동 설정하지 않으므로 실제 기기에서 조건 불충족으로 거부될 수 있다. 고장이나 미지원 확정으로 해석하지 않는다. 취소 시 transport가 깨졌다면 물리 해제를 확인할 수 없어 UNCONFIRMED로 남는다.
