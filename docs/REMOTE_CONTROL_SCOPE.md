# α7C 원격 촬영·설정 제어 적용 범위

조사일: 2026-11-17. 대상은 **원형 α7C(ILCE-7C)**다. α7C II(ILCE-7CM2), α7CR(ILCE-7CR)의 기능을 원형에 합산하지 않는다. 이번 작업은 공개 문서·소스 조사이며 앱 구현, 카메라 통신, 빌드, APK 배포는 수행하지 않았다.

## 결론

USB PC Remote를 통한 **단발 촬영, 반셔터 AF, ISO·조리개·셔터속도·노출보정, 라이브뷰**는 우선 개발 후보로 삼을 근거가 있다. 현재 앱에서 동작한다는 뜻은 아니다. Sony 제어 세션과 속성·이벤트 처리가 필요하므로 기존 MTP 읽기 명령을 몇 개 추가하는 방식으로 구현하지 않는다.

Wi-Fi 원격 촬영은 Sony 공식 앱에서 ILCE-7C 지원 근거가 있다. 그러나 SDK의 Wi-Fi 지원, Android 직접 PTP/IP 구현, 옛 HTTP Camera Remote API 지원은 각각 다른 문제다. Android 직접 Wi-Fi 제어는 후속 검증 대상으로 둔다.

## 근거 수준과 모델 구분

| 자료 | 확인한 내용 | 적용 한계 |
| --- | --- | --- |
| Sony 공식 SDK·Command 페이지 | ILCE-7C를 지원 모델로 명시. SDK 지원 OS에 Android 없음 | 전체 목록은 개별 명령·전송 계층 지원표가 아님 |
| Sony 공식 Imaging Edge 모델표 | ILCE-7C의 USB/Wi-Fi 원격 촬영, 라이브뷰, AF/MF 전환, 사용자 정의 WB 지원 표시 | Sony PC 앱의 지원이며 Android 구현 결과가 아님 |
| libgphoto2 모델 등록·소스 | ILCE-7C(Control), USB `054c:0d2b`, capture/preview 지원 플래그. ILCE-7CM2는 별도 등록 | 공개 구현 근거이며 현재 카메라·앱의 시험 성적이 아님. PID를 모든 모드의 고정값으로 사용하지 않음 |
| Sony SDK API Reference 2.01.00의 비공식 호스팅 사본 | 모델 열별 API·속성·제어 지원 표를 추가 확인 | Sony에서 정식 확보한 최신 배포본이 아님. 보조 자료로만 사용 |
| Wi-Fi 공개 구현 | Alpha-Fairy와 Android Sony Multiple Monitor의 PTP/IP 구조 | 원형 α7C 실증 근거를 확보하지 못함 |

공식 출처: [SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html), [Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html), [Imaging Edge 모델표](https://support.d-imaging.sony.co.jp/app/imagingedge/en/devices/index.php). 공개 구현: [libgphoto2 모델 등록](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/library.c#L1388-L1389).

비공식 사본의 [물리 계층 표](https://amarburg.github.io/sony_remote_camera_sdk/other/compatibility.html)는 ILCE-7C에 USB R/C, ILCE-7CM2에 USB 및 무선 R/C/T를 표시한다. R은 remote control, C는 contents transfer, T는 remote transfer다. 이 표의 ILCE-7C 무선 미지원 표시는 **해당 SDK 버전의 범위**이며 카메라의 Wi-Fi 원격 촬영 자체가 불가능하다는 뜻이 아니다. T 부재도 촬영 사진의 모든 자동 전송이 불가능하다는 뜻이 아니다. ILCE-7CR의 세부 표는 이번 문서에서 확정하지 않았다.

## 기능별 적용 판단

| 기능 | 원형 ILCE-7C 근거 | 앱 적용 범위·조건 |
| --- | --- | --- |
| 단발 촬영 | libgphoto2에 Sony 촬영 흐름과 ILCE-7C 대기 처리 존재 | 최우선 후보. 누름/해제, 촬영 완료 이벤트, 결과 객체 수신을 별도 상태로 처리 |
| 반셔터 AF | 공개 구현의 Sony 반셔터·초점 상태 처리 | 후보. AF 실패·취소·연결 끊김 시 해제 처리 필요 |
| ISO·조리개·셔터속도·노출보정 | 공개 구현에 Sony 전용 setter와 프로토콜별 분기 | 후보. 현재 허용값·타입·쓰기 가능 여부를 읽고 설정 후 재조회 |
| 라이브뷰 | ILCE-7C preview 등록, Sony 공통 객체 기반 프레임 수신 구현 및 공식 Imaging Edge 지원 | 후보. USB Streaming/UVC와 별도. 해상도·FPS·동시 촬영 성능 미확정 |
| 촬영 완료·사진 전송 | SDK 1.05.00 공식 이력에 ILCE-7C 촬영 완료 알림·Contents Transfer 지원 명시 | 촬영 결과 전송과 기존 카드 파일 탐색을 구분. RAW+JPEG 복수 객체 고려 |
| 동영상 시작/정지 | 보조 SDK 표에 MovieRecButton·RecordingState 표시 | 2차 후보. 실제 녹화 상태 재조회가 필요하며 toggle 변형 지원으로 확대하지 않음 |
| AF 위치·수동 초점 이동 | 보조 표에 AF_Area_Position·NearFar 표시 | 2차 후보. 절대 초점 위치·거리 지정 지원과 다름. 렌즈/AF 모드별 조건 미확정 |
| AF/MF·화이트밸런스 | 공식 Imaging Edge 표에 AF/MF 전환·사용자 정의 WB 표시 | 2차 후보. 각 속성의 허용값 및 촬영 모드 제약 확인 후 활성화 |
| Wi-Fi 촬영·설정 | 공식 PC Remote 설명서와 Imaging Edge 지원 | 별도 연구. 현재 Android 구현에 대한 α7C 검증 없음 |
| 연사·Bulb·고급 초점 제어 | 이번 자료만으로 α7C의 끝단 동작·복구 조건을 확정하지 못함 | 기본 촬영 실기 확인 이후로 보류 |
| 포맷·삭제·전원·펌웨어·서비스 명령 | 일부 자료에 관련 API가 있어도 앱 목적과 별도 | 이번 원격 제어 범위에서 제외 |

공식 기능 이력: [Sony SDK 변경 이력](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html). 보조 표: [API](https://amarburg.github.io/sony_remote_camera_sdk/function_list/api_list.html), [속성](https://amarburg.github.io/sony_remote_camera_sdk/function_list/device_property_list.html), [제어](https://amarburg.github.io/sony_remote_camera_sdk/function_list/control_code_list.html). 표의 지원 기호는 모든 모드에서의 쓰기 가능성을 보장하지 않는다. SDK enum/제어 번호만으로 USB 패킷·인자를 구성해서는 안 된다.

보조 표에는 ILCE-7C의 절대 초점 위치 설정, ISO Auto 상·하한, 별도 영상 프레임레이트 설정, 최신 MovieRecButtonToggle 변형 등이 미지원으로 표시된다. 기본 기능과 최신 모델 확장 기능을 분리해야 한다. 이 목록은 2.01.00 사본 기준이며 최신 정식 문서로 재확인해야 한다.

## 상세 프로토콜에서 확인한 구현 경계

기준 소스는 libgphoto2 커밋 `31a2f4089f19aec6caca7920b03e3eb7a251c5b6`이다. 아래 값은 구현 검토용 근거이며 지금 앱에서 실행한 명령이 아니다.

1. **초기화부터 상태 변경이다.** Sony SDIO Connect 단계와 확장정보 조회 후 제어 우선순위를 설정한다. 읽기 전용 진단의 연장으로 자동 실행하면 안 된다. [초기화 소스](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/library.c#L729-L781)
2. **모델명으로 프로토콜을 고정하지 않는다.** 현재 소스는 확장정보 응답 버전에 따라 mode2/mode3 경로를 선택한다. α7C firmware 2.00에서 2.5.31 초기화 변경으로 속성 조회가 깨진 보고와 수정이 있다. 이는 구현 회귀 근거이며 α7C 하드웨어의 protocol3 미지원 증명은 아니다. libgphoto2 내부 mode2/3 명칭과 Sony Command의 “Camera Control PTP 2” 제품 문구도 같다고 단정하지 않는다. [협상 코드](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.c#L4350-L4402), [이슈 937](https://github.com/gphoto/libgphoto2/issues/937), [수정 938](https://github.com/gphoto/libgphoto2/pull/938)
3. **셔터는 누름/해제 쌍이다.** 공개 구현은 반셔터 `0xD2C1`, 셔터 `0xD2C2`의 상태와 초점·객체 이벤트를 처리한다. 일반 `InitiateCapture` 한 번으로 대체할 근거가 없다. 취소·타임아웃 때 가능한 경우 해제를 시도하고, 연결 유실 시 복구가 확인되지 않았음을 표시해야 한다. [촬영 코드](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/library.c#L5055-L5225)
4. **표준 속성 번호도 Sony 경로를 쓸 수 있다.** FNumber `0x5007`이라는 사실만으로 generic SetDevicePropValue를 적용하지 않는다. ISO의 AUTO/상위 비트, 셔터속도의 분수 표현, protocol별 타입과 setter가 다르다. 허용값 목록을 UI의 선택지로 사용하고 모드/렌즈 변화 시 갱신한다. [ISO](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/config.c#L4209-L4348), [조리개](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/config.c#L4650-L4672), [셔터속도](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/config.c#L6769-L6805)
5. **라이브뷰는 별도 수신 흐름이다.** Sony 공통 구현은 특수 객체 `0xffffc002`를 읽고 JPEG를 추출한다. 이것은 α7C의 성능 보증 자료가 아니다. 프레임 길이 제한, 잘린 프레임 폐기, 취소 및 촬영 명령과의 전송 조정이 필요하다. [프리뷰 코드](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/library.c#L3994-L4045)

## Wi-Fi와 Android 경로

| 경로 | 판단 |
| --- | --- |
| 공식 SDK를 Android 앱에 직접 포함 | 공식 지원 OS 근거 없음. 즉시 채택하지 않음 |
| PC/Linux에서 SDK 실행 후 Android가 연결 | 구조상 대안이나 별도 호스트·통신 계층이 필요. 폰 단독 USB 앱의 우선 경로는 아님 |
| Android USB에서 Sony 프로토콜 직접 구현 | 공개 구현 근거를 검토할 수 있는 우선 연구 경로. 모델/펌웨어 capability와 라이선스 검토 필요 |
| Android Wi-Fi PTP/IP | 별도 연결·명령·이벤트 채널과 모델별 초기화 검증 필요 |
| 과거 Smart Remote HTTP JSON-RPC | PTP/IP와 별개. 옛 메서드가 존재한다는 이유로 ILCE-7C에 적용하지 않음 |

[Alpha-Fairy README](https://github.com/frank26080115/alpha-fairy/blob/fd1f2989f952a77d01cdb23b788058c39f091c89/README.md)는 A7C를 미시험·이론적 PTP 지원 후보로 분류한다. SDK 모델 목록에서 유추한 부분을 독립적인 실증으로 인용하지 않는다. [역공학 노트](https://github.com/frank26080115/alpha-fairy/blob/fd1f2989f952a77d01cdb23b788058c39f091c89/doc/Camera-Reverse-Engineering.md)는 속성 형식 차이·설정 지연·전송 및 복구 문제의 참고 자료다.

[Sony Multiple Monitor README](https://github.com/lehotanhiep/Sony-Multiple-Monitor-App/blob/56f0f243ea61918343a43c0f333477b891944e44/README.md)의 시험 모델은 ZV-1/ZV-E10M2이며 ILCE-7C가 아니다. Android 구현의 존재와 α7C 호환성을 구분한다.

코드 재사용 시 libgphoto2의 [LGPL-2.0-or-later 헤더](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/config.c#L1-L19), Alpha-Fairy의 [MIT 라이선스](https://github.com/frank26080115/alpha-fairy/blob/fd1f2989f952a77d01cdb23b788058c39f091c89/LICENSE)를 각각 확인해야 한다. Sony Multiple Monitor는 이번 조사에서 라이선스를 확인하지 못했으므로 코드 복사 대상으로 삼지 않는다. 프로토콜 관찰과 소스 이식은 구분한다.

## 기존 앱에 맞춘 개발 순서

아래는 후속 계획이다. 최초 조사에서는 구현하지 않았으며, 이후 승인된 [1단계 오프라인 파서](SONY_OFFLINE_PARSERS.md)와 [2단계 별도 원격 세션](SONY_REMOTE_SESSION.md)의 구현·제약을 따로 기록한다. 2단계는 모의 통신까지이며 실제 USB 어댑터·UI 연결, 3단계 이후 기능과 실기 확인은 아직 진행하지 않았다.

1. **오프라인 형식·상태 모델:** Sony 확장정보, 속성 descriptor, 이벤트의 출처가 명확한 fixture와 parser 테스트. 길이 오류·미지 타입·중복/지연 이벤트·타임아웃을 검사한다. 합성 fixture 통과를 실기 호환성으로 표시하지 않는다.
2. **별도 원격 제어 세션:** 기존 MTP 가져오기와 읽기 전용 진단의 허용 목록을 유지한다. 기존 USB 배타적 소유권을 재사용하고, 사용자가 진입하는 별도 PC Remote 세션에 연결 협상·속성 snapshot·이벤트 수신·종료 처리를 둔다. 임의 opcode 입력 기능은 만들지 않는다.
3. **AF·단발 촬영:** 누름/해제 및 완료/실패 상태부터 구현한다. RAW+JPEG 객체 중복·복수 수신과 연결 유실을 다룬다.
4. **기본 노출 제어:** ISO → 조리개 → 셔터속도 → 노출보정 순으로 허용값·쓰기 가능 상태·설정 후 read-back을 검증한다. 지원하지 않거나 읽기 전용인 속성은 UI에서 비활성화한다.
5. **라이브뷰·후속 제어:** 대역폭·취소·동시 촬영 확인 후 동영상, AF 위치, WB 등을 확장한다. Wi-Fi는 USB 기준선이 확인된 뒤 별도 단계로 진행한다.

실기 확인은 사용자 요청대로 추후 진행한다. 그때 모델·펌웨어·렌즈·사진/영상 모드별로 연결/협상, 허용값 조회, 촬영 완료 이벤트, 설정 read-back, 종료/재연결 및 MTP 가져오기 복귀를 검증한다. 실제 값이 확보되기 전에는 후보 기능을 “α7C 지원 완료”로 표시하지 않는다.

## 수행 내역과 남은 문제

- 수행: Sony 공식 공개 페이지, 비공식 호스팅 SDK 문서의 출처·버전 구분, 커밋을 고정한 공개 소스 및 α7C 회귀 보고 조사. USB와 Wi-Fi 조사를 병렬로 수행했다.
- 미수행: 카메라/폰 연결, 패킷 캡처, 원격 명령 전송, 앱 코드 수정, 단위 테스트, Gradle 빌드, APK 배포. 문서 변경만 있어 빌드 로그를 생성하지 않았다.
- 미확보: 최신 정식 SDK 모델별 원문, Camera Remote Command의 ILCE-7C별 명령·인터페이스 표와 2027년 변경 적용 여부. 신청서 제출·라이선스 동의는 하지 않았다.
- 미확정: 현재 사용자의 카메라 펌웨어에서의 협상 결과, 속성별 writable/허용값, 라이브뷰 성능, Android Wi-Fi 직접 제어, 복구 동작.

기존 공식 조사와 진단 범위는 [Sony 지원 조사](SONY_SUPPORT_RESEARCH.md), 후속 실기 절차는 [카메라 진단](CAMERA_DIAGNOSTICS.md)을 참고한다.
