# Sony 별도 원격 세션 — 2단계

2026-11-17. [개발 순서](REMOTE_CONTROL_SCOPE.md)의 연결 협상·속성 snapshot·이벤트 관찰·종료를 구현했다. 실제 카메라 연결은 수행하지 않았고 바이트/명령 수준의 모의 통신으로 검증한다.

후속: 0.7.0에서 [PC Remote 실험 진단](PC_REMOTE_DIAGNOSTICS.md)에 Android USB 어댑터·실행 확인·결과 공유를 연결했다. 아래 어댑터/UI 미제공 서술은 2단계 완료 시점의 기록이다. 실기 미확인과 촬영·설정 쓰기 제외는 유지한다.

## 현재 가능한 범위

`UsbAccess.useRemote(openTransport) { session -> … }`가 기존 가져오기·진단과 **같은 Mutex**를 사용한다. transport 생성부터 연결·호출자 작업·종료까지 소유권을 유지한다. 기다리는 도중 취소하면 transport를 만들지 않는다. 오류·취소 시에도 정리 후 소유권을 반환한다.

이 진입점은 개발용 API다. 앱 화면에서 호출하지 않으며 실제 Android USB 어댑터는 아직 없다. `SonyRemoteWire`에 모의 바이트 전송을 연결하고 `SonySessionWireTransport`를 통해 실제 PTP 컨테이너 인코딩·재조립·응답 검증 경로를 실행한다. 기존 `PtpUsbClient`의 읽기 허용 목록과 가져오기·진단의 동작은 바꾸지 않았다.

**OBSERVING은 원격 촬영 준비 완료가 아니다.** SDIO 연결 자체는 카메라 모드를 변경할 수 있어 읽기 전용 진단에 자동으로 붙이지 않는다. PriorityMode, ISO·조리개 등의 설정 쓰기, 반셔터·셔터·동영상 명령은 이번 단계에 없다.

## 연결 순서와 명령 경계

| 순서 | 고정 명령 | 검증/처리 |
| --- | --- | --- |
| 1 | GetDeviceInfo `0x1001` | 모델 `ILCE-7C`, 제조사 `Sony`/`Sony Corporation`, `0x9201`·`0x9202` 광고 여부 확인 |
| 2 | OpenSession `0x1002(1)` | 성공한 세션만 소유. SessionAlreadyOpen을 성공으로 취급하지 않음 |
| 3 | SDIO Connect `0x9201(1,0,0)` | DATA+OK 확인 |
| 4 | SDIO Connect `0x9201(2,0,0)` | DATA+OK 확인 |
| 5 | GetExtDeviceInfo `0x9202(0x012c,1)` | 1단계 파서로 응답 버전·목록 해석. 미지 버전 거부 |
| 6 | capability 확인 | 초기 operations와 확장정보 두 배열의 합집합에 `0x9209`가 있는지 확인. 없으면 phase3 전에 종료 |
| 7 | SDIO Connect `0x9201(3,0,0)` | DATA+OK 확인 |
| 8 | GetAllExtDevicePropInfo `0x9209` | snapshot 전체 파싱 성공 후에만 OBSERVING으로 전환 |
| 종료 | CloseSession `0x1003` | 소유한 세션에만 시도. 프레이밍이 깨진 transport는 전송을 거부하고 자원만 해제 |

DeviceInfo의 초기 목록에서 일부 Sony 명령이 숨겨질 수 있어 확장정보 두 배열도 확인한다. 배열 이름이 property/control이어도 공개 구현은 포함된 코드를 operation/event/property로 재분류한다. 이 세션은 모르는 코드를 실행하지 않고 snapshot 명령의 존재 확인에만 사용한다.

명령은 `SonySessionOperation` enum에 고정되어 있다. 파라미터 목록도 변경 불가로 제공하며 임의 opcode·파라미터나 outbound DATA를 넘기는 API는 없다. SDK의 모델 지원 여부나 광고 목록만으로 쓰기 권한을 추정하지 않는다. 엄격한 모델·제조사·초기 명령 확인은 이 단계의 지원 정책이며 실제 모든 α7C 응답을 수용한다는 보장은 아니다.

형식 근거는 libgphoto2 커밋 `31a2f4089f19aec6caca7920b03e3eb7a251c5b6`의 [연결 순서](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/library.c#L729), [SDIO의 DATA 수신과 모드 변경](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.c#L4307), [확장정보](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.c#L4390)다. 그 구현의 PriorityMode 쓰기나 자동 재시도는 포함하지 않았다.

## 상태·실패·이벤트

- 상태는 NEW → CONNECTING → OBSERVING → CLOSING → CLOSED이며, 연결/파싱/통신 오류는 FAILED로 끝난다. 한 인스턴스를 재연결하지 않는다.
- snapshot은 변경 불가 목록을 사용한다. 초기 파싱이 실패하면 부분 결과를 공개하지 않는다. 실패·종료 후 기존 값은 stale로 표시한다.
- `pollEvent(timeoutMillis)`는 별도 이벤트 채널을 한 번 읽는다. 정상적으로 이벤트가 없으면 null이다. background loop는 없으며 `refreshProperties()`도 호출자가 명시적으로 실행한다.
- 속성 변경 이벤트는 propertiesStale, 객체 추가/삭제는 objectsNeedRefresh를 설정한다. 중복은 같은 플래그를 유지한다. 미지 이벤트는 보존하고 Captured 이벤트로 촬영 성공을 판정하지 않는다.
- 이벤트 읽기·속성 재조회·종료는 세션 내 Mutex로 직렬화한다. 실제 데이터와 오래된 이벤트를 촬영 요청에 대응시키는 기능은 아직 없다.
- `ptpCloseStatus`는 NOT_OPENED/UNCONFIRMED/CONFIRMED로 구분한다. 응답을 잃었거나 프레이밍이 깨진 경우 물리 자원 해제만으로 카메라 세션 종료를 확정하지 않는다. CONFIRMED도 PTP CloseSession의 OK를 뜻하며 카메라 메뉴·모드 원복의 보증이 아니다.
- 정리는 취소되지 않는 구간에서 수행하고 transport는 한 번만 닫는다. 최초 오류를 유지하며 정리 중 오류는 suppressed로 보존한다.

## wire 전송의 제한

기존 `PtpContainer` codec을 재사용한다. 부분 수신과 DATA/응답의 병합 수신을 처리하고 transaction ID, 코드, DATA 유무·중복, 길이·정렬·trailing bytes를 검증한다. 데이터 payload는 최대 1 MiB다. transaction deadline, I/O timeout, 반복 zero-byte 읽기 한도로 무한 대기를 막는다. 실제 어댑터는 전달된 timeout과 coroutine 취소를 준수해야 한다.

프레이밍·통신·취소 오류 이후에는 같은 wire에서 명령을 재시도하지 않는다. 정상 경계에서 수신한 비정상 PTP 응답은 상위에 전달하며, 정리용 CloseSession은 가능하다. 이벤트 채널의 정상 무이벤트 결과(null)와 timeout 계약 위반/예외는 구분한다. 후자는 실패로 처리한다.

## 남은 사항

1. 실제 snapshot에 문자열·배열·새로운 enum 형식이 있으면 1단계의 엄격한 파서가 거부한다. 이 경우 세션 전체가 실패하며 부분 목록으로 제어를 시작하지 않는다.
2. 실제 Android endpoint/interrupt 수신 어댑터, 권한·모드 선택 UI, 실기 확인은 추후 단계다. 현재 폰에서 원격 세션을 시작할 수는 없다.
3. PriorityMode 선택/복원, 촬영 버튼 해제 복구, 설정 허용값·쓰기 정책은 다음 기능 단계에서 별도 설계한다.
4. 기존 MTP로 돌아갈 때의 카메라 메뉴·물리 연결 상태는 아직 실기로 확인하지 않았다. 모의 테스트는 소프트웨어 자원과 잠금 반환만 입증한다.

수정 파일·실행 결과·빌드 로그는 [2단계 검증 기록](verification/2026-11-17/sony-remote-session.md)에 기록한다.
