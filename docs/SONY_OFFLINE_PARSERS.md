# Sony 오프라인 파서 — 1단계

2026-11-17. [원격 제어 개발 순서](REMOTE_CONTROL_SCOPE.md)의 1단계 구현이다. 카메라 연결·협상·명령 전송·설정 쓰기·UI 연결 없이 입력 바이트만 해석한다. 기존 가져오기와 읽기 전용 진단의 USB 경로는 변경하지 않았다.

## 구현 범위

| 파일 (`app/src/main/java/dev/sukbeomh/beta7compact/remote/`) | 입력과 결과 |
| --- | --- |
| `SonyExtendedInfoParser.kt` | USB 컨테이너를 제외한 확장정보 payload → 광고 버전, 속성 코드 목록, 제어 코드 목록 |
| `SonyPropertyParser.kt` | 개별 속성 descriptor 또는 전체 snapshot payload → 정수 값, raw 접근/활성 상태, range/enum |
| `SonyEventParser.kt` | 완전한 USB PTP event 컨테이너 → 코드, unsigned transaction ID와 파라미터. 별도 순수 상태 모델로 재조회 필요 여부 관찰 |

확장정보의 `0x00c8`/`0x012c`는 **읽을 수 있는 데이터 형식**이다. 카메라의 호환성이나 협상 완료를 뜻하지 않는다. 모르는 버전은 버전 값을 가진 예외로 거부하며, 빈 응답에서 mode2를 추측하지 않는다. 속성 코드와 제어 코드는 합치지 않고 원래 순서·중복·미지 코드를 유지한다.

속성 파서는 signed/unsigned 8·16·32·64비트를 손실 없이 `BigInteger`로 보존한다. `GetSet`·`IsEnabled`는 원본 숫자만 보관한다. libgphoto2의 모드별 쓰기 가능 추정이나 예외 모델 처리를 이식하지 않았다. 현재값/기본값이 enum 밖이라는 이유로 거부하지 않는다. 이들은 시점이나 모드에 따라 달라질 수 있다.

이벤트 파서는 12바이트 헤더와 0~3개의 uint32 파라미터를 허용한다. 모르는 코드는 보존한다. 관찰 상태의 generation과 시간은 호출자가 주는 **로컬 값**이며, wire transaction ID나 촬영 요청의 상관관계가 아니다. 중복 이벤트는 재조회 플래그를 반복 설정할 뿐이며 이전 generation·마감 이후 이벤트는 플래그를 바꾸지 않는다. `Captured`라는 이름의 이벤트도 촬영 성공으로 판정하지 않는다. 같은 generation에서 늦게 도착한 이벤트를 카메라 발생 시간순으로 정렬하는 기능은 없다.

## 엄격한 지원 범위와 제한

- 확장정보와 속성 입력은 각각 최대 1 MiB. snapshot은 최대 4,096개 속성이다. 이 값은 앱의 방어적 자원 한도이며 Sony의 공식 제한이 아니다.
- 속성 코드는 현재 `0x5xxx`/`0xDxxx`, 타입은 PTP 정수 1~8, form은 none/range/enum만 허용한다.
- 문자열·배열·128비트·생략된 form·새 기종의 secondary enum은 아직 지원하지 않는다. 미지 타입의 바이트 길이를 추측해 다음 descriptor로 넘어가지 않는다.
- snapshot의 count와 reserved zero, 속성 코드 중복, 남은 바이트를 엄격히 검사한다. 공개 구현보다 보수적인 정책이며 실기 데이터가 다르면 근거를 확보한 뒤 확장한다.
- 역전된 range와 0 이하 step을 거부한다. 이는 이 단계의 지원 프로파일이며 카메라 전체 형식의 보편적 규칙으로 주장하지 않는다.
- 이벤트 입력은 완성된 USB 컨테이너 하나다. USB 스트림 재조립, PTP/IP 패킷, 실제 이벤트 수신·타이머·재조회 실행은 포함하지 않는다.
- 예외 메시지에 원본 payload나 카메라 문자열을 넣지 않는다.

## 형식 근거와 fixture 출처

libgphoto2의 커밋 `31a2f4089f19aec6caca7920b03e3eb7a251c5b6`을 고정해 필드 순서·크기·상수를 확인했다. Kotlin 파서는 해당 형식을 별도로 구성한 것이다. 카메라별 우회 처리, 전송·쓰기 구현은 가져오지 않았다.

| 형식 | 1차 소스 |
| --- | --- |
| 확장정보 버전 + 두 개의 u32-count/u16 배열 | [ptp.c:4397](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.c#L4397), [ptp-pack.c:242](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp-pack.c#L242) |
| Sony descriptor 헤더·값·form, snapshot 헤더 | [ptp-pack.c:788](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp-pack.c#L788), [ptp.c:4490](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.c#L4490) |
| USB event 컨테이너, Sony 이벤트 이름 | [ptp.h:147](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.h#L147), [ptp.h:1369](https://github.com/gphoto/libgphoto2/blob/31a2f4089f19aec6caca7920b03e3eb7a251c5b6/camlibs/ptp2/ptp.h#L1369) |

fixture는 각 `Sony*ParserTest.kt`에 직접 작성한 합성 hex/바이트다. 실제 ILCE-7C 캡처나 공식 Sony 시험 벡터가 아니다. 스칼라 값과 오류 입력도 경계 검증용이며 특정 ISO/셔터 값이 실제 카메라에서 허용된다는 뜻이 아니다. 신규 production encoder로 fixture를 만들지 않아 decoder와 encoder가 같은 실수를 공유하는 경우를 줄였다.

## 검증과 다음 단계

정상 payload, 모든 잘린 prefix, 과도한 count, 미지 형식, trailing bytes, 정수 경계 및 이벤트 중복·지연·timeout을 JVM 테스트로 검증한다. 실행 결과와 빌드 로그는 [1단계 검증 기록](verification/2026-11-17/sony-offline-parsers.md)에 남긴다.

실기 확인은 추후 진행한다. 이번 테스트 통과로 α7C 지원을 표시하거나 USB 읽기 허용 목록을 넓히지 않는다. 다음 단계는 별도 제어 세션 설계이며, 이번에는 연결 협상이나 버튼 누름/해제 명령을 구현하지 않았다.
