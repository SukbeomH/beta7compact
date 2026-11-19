# 카메라 기종 확대 조사

> **과거 연구 기록:** 0.8.12에서 BLE 위치 연동과 PC Remote 실험은 사용자 실기 피드백에 따라 종료·제거했습니다. 펌웨어 파일 분석도 제거했으며 라이브뷰 추가 계획은 없습니다. 아래 내용은 당시의 이력입니다. 현재 범위는 [인계 문서](HANDOFF.md)를 따릅니다.

조사일: 2026-11-18. 기준: 앱 0.8.9 소스 및 제조사의 공개 설명서. 이 문서는 **호환성 검증 후보**를 기록한다. 아래 기종을 앱의 지원 완료 목록으로 표시하지 않는다. 기종별 Android 연결, 전송 및 원격 제어는 이번 조사에서 실행하지 않았다.

## 결론과 현재 구현

- 사진 가져오기는 Sony USB VID `0x054c`인 장치를 한 대 찾고 USB 인터페이스 클래스 6과 권한을 확인한다. Android `MtpDevice`를 사용하며 실패 시 제한된 PTP 읽기 경로를 사용한다. 현재 가져오기 경로에 특정 모델 이름의 허용 목록은 없다. 탐색 대상이라는 사실은 실제 가져오기 성공을 뜻하지 않는다. [구현](../app/src/main/java/dev/sukbeomh/beta7compact/usb/UsbCamera.kt) · [USB 필터](../app/src/main/res/xml/usb_devices.xml)
- 0.8.9의 PC Remote 세션은 제조사가 Sony인 다른 기종도 시도할 수 있다. 진단과 촬영 화면은 다른 기종의 호환성이 미검증임을 안내하고 실행 확인을 받는다. 필수 명령 확인과 촬영 조건 검사는 유지한다. 비 Sony 기종은 제외된다. 기존 배포된 0.8.8은 `ILCE-7C`만 허용한다. [원격 세션](../app/src/main/java/dev/sukbeomh/beta7compact/remote/SonyRemoteSession.kt) · [촬영 세션](../app/src/main/java/dev/sukbeomh/beta7compact/remote/SonyCaptureSession.kt)
- 기존 기록에는 α7C의 USB 가져오기와 레시피 편집을 Galaxy Z Fold8에서 확인한 이력이 있다. PC Remote·AF·단발 촬영의 α7C 실기 성공은 확인되지 않았다. [기존 검증 상태](../README.md#검증-상태)
- 타 제조사 카메라는 현재 Sony VID 필터에서 제외된다. SD 카드 리더나 USB Mass Storage 경로는 이 카메라 가져오기 경로의 근거가 아니다.

## Sony 후보 기종

표의 MTP는 카메라 설명서가 해당 USB 모드를 명시한다는 뜻이다. Command와 SDK는 Sony의 공개 [Camera Remote Command 기종 목록](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)과 [Camera Remote SDK 기종 목록](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)의 등재 여부다. 이 표의 어느 항목도 **이 Android 앱의 호환성 인증이나 실기 성공을 뜻하지 않는다**. 물리 연결별·명령별 지원은 Sony 배포 자료와 실기 확인이 필요하다.

| 우선순위 | 카메라 | MTP 공식 근거 | Command | SDK | 확인할 차이 |
| --- | --- | --- | --- | --- | --- |
| 기준 | α7C `ILCE-7C` | [USB 연결](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000115335.html) | 등재 | 등재 | 기존 가져오기 이력. 원격 실기 미검증 |
| 1 | α7C II `ILCE-7CM2` | [USB 연결 모드](https://helpguide.sony.net/ilc/2360/v1/en/contents/0601_usb_connection.html) | 등재 | 등재 | 원형 α7C와 별도 모델. MTP와 PC Remote를 별도로 선택 |
| 1 | α7CR `ILCE-7CR` | [USB 연결 모드](https://helpguide.sony.net/ilc/2370/v1/en/contents/0601_usb_connection.html) | 등재 | 등재 | 7C II와 같은 세대의 후보. 파일 크기와 전송 실패 복구 확인 |
| 2 | α7 IV `ILCE-7M4` | [USB 연결 모드](https://helpguide.sony.net/ilc/2110/v1/en/contents/TP1000616544.html) | 등재 | 등재 | 공식 설명서의 MTP 대상은 메모리 카드 슬롯 1 |
| 2 | α6700 `ILCE-6700` | [USB 연결 모드](https://helpguide.sony.net/ilc/2320/v1/en/contents/0601_usb_connection.html) | 등재 | 등재 | APS-C 모델. 원격 기능과 전송을 별도 검사 |
| 2 | α7R V `ILCE-7RM5` | [USB 연결 모드](https://helpguide.sony.net/ilc/2230/v1/en/contents/TP0002887823.html) | 등재 | 등재 | 공식 설명서의 MTP 대상은 메모리 카드 슬롯 1 |
| 3 | α7 III `ILCE-7M3` | [USB 연결](https://helpguide.sony.net/ilc/1720/v1/en/contents/TP0001629775.html) | 등재 | 공개 목록 미등재 | 스마트폰 제어를 끄고 MTP 선택. 공식 MTP 대상은 슬롯 1 |
| 3 | α6400 `ILCE-6400` | [USB 연결](https://helpguide.sony.net/ilc/1810/v1/en/contents/TP0002241316.html) | 등재 | 공개 목록 미등재 | 스마트폰 제어를 끄고 MTP 선택. `ILCE-6400A`와 구분 |
| 3 | ZV-E10 `ZV-E10` | [USB 연결](https://helpguide.sony.net/ilc/2070/v1/en/contents/TP0001117083.html) | 등재 | 공개 목록 미등재 | MTP 사용 전 스마트폰 연결과 PC Remote를 끔 |
| 3 | ZV-E10 II `ZV-E10M2` | [USB 연결 모드](https://helpguide.sony.net/ilc/2430/v1/en/contents/0601_usb_connection.html) | 등재 | 등재 | ZV-E10과 별도 모델. MTP와 PC Remote를 별도로 선택 |

우선순위는 α7C와의 제품군 관계와 공개 근거를 기준으로 한 연구 순서다. 모델별 실제 사용 빈도, 성공 가능성, 지원 여부를 측정한 순위는 아니다. Sony 공개 문서는 MTP로 사진과 동영상을 옮길 수 있다고 설명하지만, 앱이 해당 기종의 모든 파일 형식과 전송 경로를 처리한다는 뜻은 아니다.

## Sony 외 제조사 후보

아래 모델은 각 제조사의 **Android USB 사진 가져오기 안내**를 조사한 후보이다. 현재 앱은 Sony VID만 찾으므로 이 모델들에 연결할 수 없다. 제조사 전용 앱이나 Android의 파일 앱에 대한 설명은 이 앱의 호환성을 뜻하지 않는다. 원격 촬영의 지원 여부도 별개다.

| 제조사·모델 | 공식 문서에서 확인한 연결 | 근거 수준과 남은 확인 |
| --- | --- | --- |
| Fujifilm X-T5 | USB CARD READER. Android 안내에 USB PTP와 Camera Importer를 명시 | [공식 안내](https://fujifilm-dsc.com/en/manual/x-t5/connections/usage_usb/index.html). 이 앱의 명령·파일 전송은 미검증 |
| Fujifilm X-T50 | USB CARD READER와 Android USB PTP 안내 | [공식 안내](https://app.fujifilm-dsc.com/en/manual/x-t50/connections/usage_usb/). 기기별 USB 전원 설정 확인 |
| Fujifilm X-M5 | USB CARD READER와 Android USB PTP 안내 | [공식 안내](https://fujifilm-dsc.com/en/manual/x-m5/connections/usage_usb/). 일부 파일 형식은 가져오지 못할 수 있음 |
| Fujifilm X100VI | USB CARD READER와 Android USB PTP 안내 | [공식 안내](https://app.fujifilm-dsc.com/en-int/manual/x100vi/connections/usage_usb/). 기기별 USB 전원 설정 확인 |
| Nikon Z6III | Android USB 연결에 MTP/PTP 선택 | [공식 안내](https://onlinemanual.nikonimglib.com/z6III/en/nwm_usb_data_connection_365.html). 이 앱의 가져오기 성공은 미검증 |
| Nikon Z f | Android USB 연결에 MTP/PTP 선택 | [공식 안내](https://onlinemanual.nikonimglib.com/zf/en/nwm_usb_331.html). 이 앱의 가져오기 성공은 미검증 |
| Nikon Z8 | Android USB 연결에 MTP/PTP 선택 | [공식 안내](https://onlinemanual.nikonimglib.com/z8/en/nwm_usb_%20data_connection_357.html). USB 데이터 단자와 카드 슬롯 확인 |
| Nikon Z6II·Z7II | Android USB 연결에 MTP/PTP 선택 | [펌웨어 1.50 변경 안내](https://onlinemanual.nikonimglib.com/z7II_z6II/en/17_changes_made_via_firmware_updates_04.html). 펌웨어·메뉴 차이 확인 |
| Canon EOS R6 Mark II | Android USB에서 Photo Import/Remote Control을 선택하고 Camera Connect 또는 Android Files로 사진을 가져오는 방법을 안내 | [공식 안내](https://cam.start.canon/en/C012/manual/html/UG-10_Reference_0030.html). 확인한 페이지에는 PTP/MTP 프로토콜명이 없어 인터페이스를 별도 확인해야 함 |

Fujifilm의 USB CARD READER는 이름만으로 USB Mass Storage라고 단정하지 않는다. 위 X-T5 등의 스마트폰 안내는 USB PTP를 명시한다. 반대로 다른 기종이나 다른 연결 모드에도 같은 프로토콜이 적용된다고 추정하지 않는다.

### 구현 전 확인할 사항

- `raw PTP`는 USB 전송 경로를 뜻한다. 카메라의 RAW 사진 파일을 현상한다는 의미가 아니다. 현재 가져오기 파일 필터는 JPEG, Sony ARW, HIF/HEIF만 허용하므로 `.RAF`, `.NEF`, `.CR3`는 현재 제외된다. [파일 필터](../app/src/main/java/dev/sukbeomh/beta7compact/core/Import.kt)
- USB 장치 탐색과 권한 요청은 Sony VID 및 단일 장치에 묶여 있다. Sony 외 기종에 대한 명시적 장치 선택과 인터페이스 검사가 필요하다. 필터만 제거해 연결된 모든 USB 장치에 Sony 전용 원격 명령을 보내지 않는다.
- MTP 우선과 읽기 전용 PTP 대체 경로의 목록·섬네일·원본 다운로드, 지원 명령, 세션 종료·취소·동시 접근을 제조사별 모의 통신과 실기로 확인한다. 쓰기·삭제·촬영·설정 변경은 사진 가져오기 범위에 포함하지 않는다.
- 다른 카메라와 같은 파일명·날짜·크기를 가진 사진의 중복 판정, 카드 슬롯, 날짜 형식과 캐시 범위를 검토한다. 동일 사진 오인이나 잘못된 가져오기 건너뛰기가 없어야 한다.
- JPEG 가져오기와 RAW 원본 보관, RAW 미리보기 또는 현상 지원은 별도의 기능이다. 각 형식의 전송과 표시를 따로 검증한다.

## 원격 제어 근거의 경계

Sony SDK의 지원 목록은 SDK의 지원 기종 목록이다. 현재 앱의 Android PTP 구현과 동일한 API가 아니다. Sony가 SDK와 Command 양쪽에 모델을 기재해도 이 앱에서 특정 속성, AF, 촬영, 라이브뷰가 작동한다고 추론할 수 없다. 개별 모델의 USB·Wi-Fi 호환성과 명령은 배포된 모델별 자료를 확인해야 한다.

Sony는 Command 공개 페이지에서 최신 펌웨어 조건과 모델별 인터페이스 자료를 안내한다. 또 일부 모델의 Camera Control PTP 2 명령은 2027년부터 사용할 수 없게 될 수 있다고 알린다. 적용 모델과 명령은 제공 README 확인이 필요하다. 이 공지를 MTP 사진 가져오기 중단으로 해석하지 않는다. Sony의 2.00.00 변경 이력에는 ZV-E10을 포함한 25개 모델에 대해 이전 명령 호환성 표를 수정한 사실도 있으므로 모델 이름만으로 명령을 일반화하지 않는다. [Sony Command 공지](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)

## 기종별 검증과 지원 범위 확대 순서

1. 현재 α7C에서 MTP 가져오기와 PC Remote 실험을 각각 확인한다. Android 실기 결과와 모의 통신 결과를 구분한다.
2. 새 기종에서는 카메라 설명서에 맞춰 MTP를 선택한다. 연결·USB 권한·모델 식별·목록·섬네일·JPEG 다운로드·원본 보존·중복 및 재연결을 확인한다. 지원 파일 형식은 실제 샘플마다 확인한다.
3. 슬롯이 둘인 모델은 슬롯별 표시와 전송을 검사한다. 설명서가 MTP를 슬롯 1로 제한하는 경우 슬롯 2 지원을 약속하지 않는다.
4. 실기 모델, 펌웨어, 폰/Android 버전, USB 모드, 결과와 재현 절차를 기록한다. 공유 로그는 기존 개인정보 제외 정책을 따른다. 성공한 기능만 모델·버전 조건과 함께 지원 목록에 올린다.
5. 다른 Sony 기종의 PC Remote 실험은 미검증 경고와 사용자 실행 확인 후에만 시도한다. 모델별 공식 자료와 모의 통신·실기 검증을 진행한다. 비 Sony 장치에는 Sony 전용 명령을 보내지 않는다.
6. 다른 제조사나 USB Mass Storage·카드 리더는 별도 설계 과제로 둔다. 제조사 필터 해제만으로 파일 포맷과 전송 안전성을 보장할 수 없다.

Sony 외 제조사에 관한 이번 조사에서는 인터넷 공개 문서와 소스를 읽었다. 해당 제조사의 기종별 실기 연결, 카메라 명령 전송 및 사진 가져오기 구현은 수행하지 않았다.
