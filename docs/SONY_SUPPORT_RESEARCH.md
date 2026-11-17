# Sony α7C 공식 지원 조사

확인일: 2026-11-17. Aside 브라우저로 Sony 공식 페이지를 읽었다. 기기는 연결하지 않았다. ILCE-7C는 ILCE-7CM2와 별도 모델이다.

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
