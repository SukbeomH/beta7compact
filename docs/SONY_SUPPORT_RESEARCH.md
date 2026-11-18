# Sony α7C 공식 지원 조사

확인일: 2026-11-17. Aside 브라우저로 Sony 공식 페이지를 읽었다. 기기는 연결하지 않았다. ILCE-7C는 ILCE-7CM2와 별도 모델이다.

후속 조사: [원격 촬영·설정 제어 적용 범위](REMOTE_CONTROL_SCOPE.md)에 기능별 근거, 공개 구현의 프로토콜 차이, Wi-Fi 경계와 개발 순서를 정리했다. 아래의 상세 배포 자료 미확보는 **Sony 정식 배포본** 기준이다. 후속 조사에서 비공식 호스팅 SDK 2.01.00 사본을 보조 자료로 확인했으나 최신 공식 표나 실기 검증을 대체하지 않는다.

| 항목 | 확인 결과 | 앱에 대한 의미 |
|---|---|---|
| Camera Remote SDK | 공식 지원 모델에 ILCE-7C 포함. 지원 OS 목록에 Android 없음 | Android용 SDK 바이너리 지원으로 해석하지 않는다 |
| Camera Remote Command | ILCE-7C 포함. PTP의 Sony 확장. 제공 대상은 기업 고객 | 공개 목록만으로 개별 명령을 구현하지 않는다. 제공 조건 확인 필요 |
| Command의 Android | Sony Japan은 OS/언어에 의존하지 않으며 iOS/Android 활용을 예시로 듦 | 프로토콜을 Android에서 구현할 가능성과 현재 앱의 호환성은 별개 |
| PC Remote | ILCE-7C 설명서가 USB, Wi-Fi Direct, Wi-Fi Access Point 명시 | Imaging Edge 기능의 근거이며 SDK/Command의 모델별 Wi-Fi 보장이 아님 |
| 모델별 인터페이스·명령 | SDK는 배포 API Reference, Command는 제공 README를 확인하도록 안내 | 해당 자료 미확보. 구체 opcode·라이브뷰·속성 쓰기 지원은 미확정 |

## 공식 출처

- [Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html): 지원 모델·OS·인터페이스 확인. 모델별 인터페이스는 API Reference 확인 필요.
- [Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html): 지원 모델·기업 고객 제공·최신 펌웨어 조건. 모델별 인터페이스는 배포 README 확인 필요.
- [Sony Japan Command](https://www.sony.jp/camera-biz/command/): OS/언어 독립 및 Android 활용 예시.
- [ILCE-7C PC Remote 설명서](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156203.html): USB/Wi-Fi 연결과 스마트폰 제어 끄기 조건.
- [Imaging Edge 연결 안내](https://support.d-imaging.sony.co.jp/app/imagingedge/en/instruction/4_1_connection.php): ILCE-9M2/7RM4A/7RM4/7C 묶음에서 유선 LAN은 9M2만 해당. 7CM2는 별도 절차.

## 남은 실기 검증

현재 가져오기 MTP 모드와 PC Remote 모드를 구분해 VID/PID·인터페이스·권한·읽기 응답을 확보해야 한다. USB 지원 정보 조회를 실제 α7C에서 확인하고 가져오기 재진입도 검사한다. 설정 쓰기·촬영·펌웨어 변경은 이번 범위 밖이다. 최신 펌웨어 요구는 공식 문서의 조건을 기록한 것이며 이번 작업에서 업데이트하지 않았다.

비공식 API 문서 미러와 타 기종 연구는 호환성 판정 근거로 사용하지 않았다. SDK/Command 지원 모델이라는 사실만으로 generic PTP 속성 명령이나 Android 앱의 실기 성공을 주장하지 않는다.

## 추가 확인: 기능 목록과 자료 접근 경계

같은 날 Sony 공식 페이지의 변경 이력·라이선스 페이지·다운로드 안내를 추가 확인했다. 라이선스 동의나 신청서 제출은 하지 않았다.

### ILCE-7C에 명시된 기능

SDK의 2021-12-07 API Reference 1.05.00 변경 이력은 ILCE-7C를 정지 사진 촬영 완료 알림과 Contents Transfer mode 지원 모델로 명시한다. 이는 특정 기능의 공식 근거다. Android 앱의 구현이나 generic PTP 명령만으로 같은 기능을 이용할 수 있다는 근거는 아니다. 모델별 인자·이벤트·초기화 절차는 배포 API Reference가 필요하다. [Sony SDK 변경 이력](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)

SDK의 Wi-Fi 지원 추가 이력과 전체 지원 모델 목록은 구분해야 한다. 전체 목록에 ILCE-7C가 있다고 해서 이 기기의 SDK Wi-Fi가 지원된다고 결론 내리지 않는다. 현재 조사에서 공식 공개 페이지로 ILCE-7C의 SDK/Command 물리 계층별 상세 표를 확보하지 못했다. [Sony SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)

### 명령 계열의 변경 안내

Camera Remote Command 공개 페이지는 Camera Control PTP 2 명령이 일부 모델에서 2027년부터 사용 불가능해질 수 있으며 사용을 권장하지 않는다고 안내한다. 상세 대상은 제공 README 확인을 요구한다. 공개 문구만으로 ILCE-7C가 해당 대상인지, 기존 MTP 사진 읽기가 영향을 받는지 판단할 수 없다. 이 경고를 모든 PTP 기능의 중단으로 확대 해석하지 않는다. [Sony Command 안내](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)

### 확보하지 않은 자료

| 자료 | 공개 확인 결과 | 남은 조건 |
| --- | --- | --- |
| Command 모델별 인터페이스·명령 README | 공개 제품 페이지는 제공 자료의 README를 안내. 신청·다운로드 페이지 확인 | 적절한 제공 절차로 원문 확보 후 ILCE-7C 행 확인 |
| SDK 모델별 API Reference | 공식 소개·변경 이력은 확인. 상세 모델별 표는 확보하지 못함 | 정식 배포 자료의 물리 계층·기능표 확인 |
| Command 라이선스 | 동의가 필요한 배포 자료의 사용 조건 페이지 확인 | 동의·다운로드·사용은 수행하지 않음 |
| Command 제공 대상 | FAQ에서 기업 고객 대상임을 명시 | 개인용 앱에 제공 가능한지 별도 확인 필요 |

[Command 라이선스 페이지](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/licenseagreement/en.html)는 배포 자료를 내려받거나 사용하는 행위에 대한 조건을 제시한다. [Sony Pro 다운로드 안내](https://pro.sony/ue_US/digital-imaging/camera-remote-command-download)는 신청 양식을 안내한다. 이번 조사는 공개 페이지 읽기에서 종료했으며, 이 문서는 라이선스 사용 허가를 의미하지 않는다.

## 진단 앱에 반영할 결론

현재 구현 가능한 다음 단계는 모드별 USB 구조와 기존 읽기 요청의 성공 여부를 비교하는 것이다. 사용자가 선택한 모드와 실제 descriptor를 함께 기록하되, 모드를 자동 확정하지 않는다. 알려지지 않은 인터페이스나 광고된 Sony 전용 opcode를 발견해도 실행하지 않는다. 명령 목록만으로 속성 값 읽기·쓰기 또는 원격 촬영 UI를 활성화하지 않는다.

현재 진단 범위는 기존 `GET_CONFIGURATION`, `GET_INTERFACE`, `GetDeviceInfo`를 넘지 않는다. 진단의 성공은 이 읽기 경로의 성공이며, 원격 촬영·라이브뷰·SDK 전체 호환성 판정이 아니다. 모드별 절차와 재연결·기존 가져오기 확인은 [실기 진단 체크리스트](CAMERA_DIAGNOSTICS.md)를 따른다.

## ILCE-7C 메뉴에서 구분할 세 경로

| 경로 | 공식 설명서의 조건 | 진단 해석 |
| --- | --- | --- |
| USB Connection → MTP | 스마트폰 제어 Off. PC Remote 연결 방법이 USB라면 PC Remote Off. USB 연결 메뉴는 Auto·Mass Storage·MTP | 기존 사진 가져오기 기준선. Auto는 연결 대상에 따라 모드가 달라져 비교 시 MTP를 명시적으로 선택 |
| Network → PC Remote Function | 스마트폰 제어 Off. PC Remote On, 연결 방법 USB·Wi-Fi Direct·Wi-Fi Access Point 중 선택 | 별도 원격 제어 경로. MTP 성공을 그대로 PC Remote 성공으로 간주하지 않음 |
| USB Streaming | 스트리밍 중 PC Remote 및 스마트폰 제어 사용 불가 | 영상 스트리밍을 PTP 라이브뷰 호환성으로 오인하지 않음 |

출처: [USB Connection](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000115335.html), [PC Remote Function](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156203.html), [USB Streaming](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000553122.html).

[USB LUN Setting](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000115336.html)은 보통 Multi를 사용하고 연결할 수 없을 때만 Single을 선택하도록 안내한다. 앱에서 자동 변경하지 않으며, 비교 실험 중 사용자가 바꿨다면 그 조건을 별도로 기록해야 한다.
