# AF·단발 촬영 — 3단계 모의 구현

2026-11-17. 카메라에 실제 명령을 보내지 않고 mock transport로 구현·검증한다. 기존 가져오기·읽기 전용 진단·PC Remote 실험 진단의 실행 경로는 유지한다. 촬영 UI나 새 APK 릴리즈는 이번 단계에 추가하지 않는다.

후속: 0.8.0에서 [별도 AF·단발 촬영 실험 화면](CAPTURE_EXPERIMENT.md)에 명시적 실행과 결과 공유를 연결했다. 아래 UI 미연결 서술은 모의 구현 완료 시점의 기록이다. 실기 미확인·엄격한 실행 조건·저장 성공 미확인은 유지한다.

## 실행 경계

- `SonyCaptureSession`은 private `SonyRemoteSession`을 구성하고 연결/작업/종료를 직렬화한다. 한 연결에서 **AF만 한 번 또는 AF를 포함한 단발 촬영 한 번**만 허용한다. 실패 뒤 같은 인스턴스를 재사용하지 않는다.
- READY 상태는 관찰 세션 연결 완료를 뜻한다. 촬영 조건 검사는 실제 AF/촬영 요청 직전에 수행하며 READY만으로 카메라 제어 가능을 확정하지 않는다.
- `UsbAccess.useCapture`는 기존 가져오기·진단과 같은 USB 잠금을 transport 생성부터 정리까지 유지한다. 앱 UI에서 이 scope를 호출하지 않는다.
- `SonySessionWireTransport`의 `allowCaptureControls` 기본값은 false다. 기존 진단은 계속 이 기본값을 사용하며 촬영 요청은 wire 전송 전에 거부된다.
- 별도 `SonyCaptureTransport.control(SonyCaptureCommand)`만 네 종류의 명령을 허용한다. 임의 속성 코드·값·바이트를 보내는 public API는 없다.

| 제어 | opcode / property | outbound DATA |
| --- | --- | --- |
| 반셔터 누름 | `0x9207 / 0xD2C1` | UINT16 little-endian 2 |
| 전체 셔터 누름 | `0x9207 / 0xD2C2` | UINT16 little-endian 2 |
| 전체 셔터 해제 | `0x9207 / 0xD2C2` | UINT16 little-endian 1 |
| 반셔터 해제 | `0x9207 / 0xD2C1` | UINT16 little-endian 1 |

command와 DATA는 같은 transaction ID를 사용한다. DATA 부분 전송·응답 불일치·취소·timeout으로 경계가 불확실해지면 해당 wire를 재사용하지 않는다. 기존 session 명령은 같은 framing 경로를 재사용하지만 outbound DATA를 추가하지 않는다.

## 보수적인 실행 조건

정확한 모델·protocol 형식 확인은 2단계 세션을 따른다. 촬영 전에 새 snapshot을 조회하고 아래 조건을 확인한다. 이들은 **앱의 제한된 실험 프로파일**이며 Sony가 모든 모델에서 요구하는 조건이나 실제 α7C 호환성 보장이 아니다.

| 대상 | 요구 조건 |
| --- | --- |
| 광고된 코드 | `0x9207`, `0xD2C1`, `0xD2C2` 존재 |
| 버튼 descriptor | UINT16, rawGetSet `0x81`, rawEnabled 1, 시작 시 해제값 1 |
| Drive `0x5013` | Single Shot 값 1. mode2 UINT16, mode3 UINT32 |
| Focus mode `0x500A` | UINT16 값 2 (공개 구현의 Automatic). AF-S로 단정하지 않음 |
| Exposure program `0x500E` | 해당 protocol의 M/P/A/S 값만 허용. 영상 모드 제외 |
| Priority `0xD25A` | UINT8 값 1 Application을 이미 관찰해야 함 |
| 저장 대상 `0xD222` | UINT16 값 `0x10` card-only |
| 메모리 객체 `0xD215` | UINT16 값 0. 남은 객체를 다운로드/폐기해 비우지 않음 |
| 셔터속도 `0xD20D` | UINT32 분수의 분자·분모 모두 0이 아닌 값. Bulb 제외 |

mode2에서 `0xD229`가 있으면 그 값도 비-Bulb인지 검사한다. mode3의 같은 코드는 다른 설정일 수 있어 셔터속도로 해석하지 않는다. 문자열·배열 등 현재 파서의 미지원 형식은 계속 실패 처리한다.

현재 관찰 세션은 PriorityMode나 저장 대상을 설정하지 않는다. 따라서 실제 카메라가 위 조건을 광고하지 않으면 실행을 거부할 수 있다. 권한·저장 대상 설정과 복원은 별도 후속 설계가 필요하다. 이번에는 이를 맞추기 위한 숨은 쓰기 명령을 추가하지 않았다.

## 초점과 촬영 결과

초기 연결 뒤 공개 구현의 시작 대기 근거를 따라 3초 경과를 기다리고, 제한된 횟수로 이전 이벤트를 비운다. 큐가 조용해지지 않으면 실행을 거부한다. 사진 객체를 읽어 소비하거나 삭제하지 않는다.

반셔터 뒤 **새로 조회한** FocusFound `0xD213`의 1 → 2 전이를 관찰해야 한다. 누르기 전부터 남아 있는 2, 단순 property-change 이벤트, 값 3을 AF 성공으로 사용하지 않는다. 값 3은 일반 Sony 표에서 저대비 초점 실패다. 제한된 관찰 시간/횟수 안에 전이를 보지 못하면 전체 셔터를 누르지 않는다.

단발 촬영은 반셔터를 유지한 채 조건을 다시 확인하고 전체 셔터를 한 번 누른다. 응답 후 전체 셔터 → 반셔터 순서로 해제하고 짧게 이벤트를 관찰한다. 결과는 **SUBMITTED_UNCONFIRMED**이며 저장 성공을 뜻하지 않는다. 객체 핸들은 관찰 증거로만 모으고 중복은 제거한다. RAW+JPEG의 여러 객체를 사진 여러 장의 증명으로 해석하지 않는다. 파일 다운로드·기존 사진 소비·삭제는 없다.

이벤트는 늦게 도착하거나 외부 조작으로 발생할 수 있다. 사전 큐 정리 이후의 ObjectAdded/Captured도 이번 요청과 확실히 대응한다는 보장이 없다. 카드 저장 완료·파일 정합성 검증은 후속 단계다.

## 오류·취소·해제

누름 요청을 await하기 **전에** 시도 여부를 기록한다. 누름 응답이 없어도 해제가 필요할 수 있기 때문이다. finally의 취소되지 않는 구간에서 시도한 버튼의 해제를 각각 한 번 시도한다. 전체 셔터 해제가 실패해도 반셔터 해제를 시도하고 최초 오류와 정리 오류를 보존한다.

프레이밍이 깨지면 wire가 해제 명령도 거부할 수 있다. 이때 해제 상태는 UNCONFIRMED로 남으며 성공으로 표시하지 않는다. CONFIRMED는 해제 명령 OK 응답을 받았다는 뜻으로 물리 버튼/카메라 상태의 실기 검증은 아니다. 모호한 촬영 요청은 자동 재시도하지 않는다.

관찰 시간은 단조 시계와 suspend pause를 주입해 테스트한다. 실제 Android 동기 USB 호출의 종료는 전달된 timeout과 드라이버에 의존하므로 카메라 실기에서 해제 지연과 복구를 별도로 확인해야 한다.

AF는 1초 coroutine deadline과 snapshot 수신 뒤 시간 재검사를 적용한다. 이미 진행 중인 네이티브 I/O는 자체 timeout까지 기다릴 수 있으므로 물리 반셔터가 정확히 1초 안에 해제된다고 보장하지 않는다.

## 1차 소스와 검증

모든 참조는 libgphoto2 `31a2f4089f19aec6caca7920b03e3eb7a251c5b6`에 고정한다.

- [ControlDevice의 DATA 전송](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.c#L4616)
- [α7C 시작 대기·기존 RAM 객체 주의·누름/해제 순서](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/library.c#L5055)
- [초점 상태 표](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/config.c#L8323), [Single Shot 표](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/config.c#L2347), [저장 대상 표](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/config.c#L11919)

테스트 fixture는 합성이고 카메라 캡처가 아니다. 실행 결과·수정 파일·미실행 항목은 [3단계 검증 기록](verification/2026-11-17/sony-capture-stage3.md)에 남긴다.
