# A7C 컴패니언 앱 — 전체 구성 설계 (초안 v0.1, 2026-11-14)

목표: Imaging Edge Mobile(IEM) 대체 + 폰에서 필름 레시피 적용. 대상 Sony A7C(ILCE-7C fw 2.03) ↔ Galaxy Z Fold8(Android 17). 개인 사용(사이드로드)부터.

## 1. 실기 근거 (A7C Probe 0.1.0~0.1.2 로그)
| 경로 | 상태 | 근거 |
|---|---|---|
| USB-C (카메라 USB=MTP) | ✅ 확인 | ILCE-7C v2.0, 214 객체, JPEG 5.6MB 0.25s. 소니 확장 op 0x9280~0x9285 광고 |
| Wi-Fi '스마트폰으로 보내기' | ◐ 입구 확인 | UPnP MediaServer :64321, ContentDirectory(PhotoRoot), Sony XPushList/DigitalImaging. 원본 다운로드는 0.1.2로 확인 예정 |
| Wi-Fi PC 원격(PTP-IP 15740) | ? 미확인 | 보내기 모드에선 거부(정상). PC 원격 모드 시험 필요 |
| BLE | ✅ 서비스 확인 | 8000FF00(리모컨), 8000DD00(위치), 8000CC00(연결/상태), 8000EE00 |

## 2. 기능 구성 (모듈)
```
app
├─ connect/        카메라 연결 관리 (상태머신 1개: 끊김→발견→연결→세션)
│   ├─ ble/        상시 저전력 링크: 발견·깨우기·셔터·위치 전송·Wi-Fi 켜기 트리거
│   ├─ wifi/       카메라 AP 연결(WifiNetworkSpecifier) + Network 바인딩(VPN 우회)
│   ├─ upnp/       '보내기' 모드 전송: SSDP·ContentDirectory·HTTP GET
│   ├─ ptpip/      PC 원격 모드: 세션·SDIO·라이브뷰·셔터·설정 읽기/쓰기
│   └─ usb/        MTP/raw PTP 대량 가져오기(가장 빠름)
├─ import/         가져오기 파이프라인: 큐·중복 제거(파일명+크기+촬영시각)·재개·원본 보존
├─ library/        로컬 사진 DB(Room): 원본/결과 링크, 레시피 이력, EXIF
├─ recipe/         레시피 엔진(GPU): LUT(.cube)·WB·톤커브·HSL·그레인·비네트·할레이션·크롭
│   └─ store/      레시피 JSON(자체 스키마), 태그·즐겨찾기, QR/파일 공유
├─ remote/         원격 촬영 UI: 라이브뷰, 셔터, 노출 표시/변경
├─ location/       폰 GPS → BLE로 카메라 시각·위치 동기화(포그라운드 서비스)
└─ ui/             Compose: 홈(연결 상태) · 가져오기 · 갤러리 · 레시피 · 원격 · 설정
```

## 3. 핵심 흐름
1. **자동 가져오기**: BLE로 카메라 감지 → (사용자가 카메라에서 '보내기' 또는 앱이 BLE로 Wi-Fi 요청) → 카메라 AP 자동 연결 → UPnP로 새 사진만 수신 → 선택 레시피 자동 적용 → 갤러리 저장 → Wi-Fi 해제
2. **대량 가져오기**: USB-C 연결 감지 → MTP로 날짜 범위/새 항목 일괄 수신(RAW 포함 가능)
3. **원격 촬영**: PC 원격(Wi-Fi Direct) 연결 → 라이브뷰 + 셔터 + 노출 표시. 빠른 셔터만 필요하면 BLE 리모컨
4. **위치 동기화**: 백그라운드로 BLE 위치 전송(IEM '위치정보 연동' 대체)
5. **레시피**: 미리보기 실시간(GPU, 축소본) → 저장 시 원본 해상도 처리, 원본 불변

## 4. 기술 스택
Kotlin, Jetpack Compose, Coroutines/Flow, Room, WorkManager(가져오기 재개), 포그라운드 서비스(위치·전송), OpenGL ES 3.2 셰이더(레시피), ExifInterface. 외부 라이브러리는 AndroidX 위주. RAW(ARW) 현상은 후순위(LibRaw NDK).

## 5. 원칙
- 원본은 절대 수정하지 않음, 결과는 별도 앨범.
- 카메라 설정 쓰기·셔터는 사용자 동작으로만. 자동 촬영 없음.
- 연결 실패는 원인(모드·VPN·권한)을 화면에 한 줄로.
- 프로토콜 코드는 공개 자료(소니 Camera Remote 문서, 커뮤니티 공개 분석)만 사용. 상업 배포 시 소니 라이선스 신청.

## 5b. 사용자 결정 (2026-11-14)
- "파일전송은 usb 전송을 주로 사용할거 같음" → **USB(MTP/raw PTP)가 기본 가져오기 경로**. RAW(ARW) 포함, 연결 시 새 사진 자동 감지·일괄 수신을 M1 핵심으로.
- Wi-Fi 수신(UPnP 보내기, FTP)은 보조/후순위. FTP는 RAW 무선 수신 대안으로 기록만(미구현).
- 위치 동기화를 앞당김 → M1에 포함(BLE 위치 전송 + 시각 동기화).
- 저장소 `github.com/SukbeomH/beta7compact`(public, master, README만 있음), 앱 이름 **beta7compact**. 프로브(camera-recipe-app)는 참고용으로 분리 유지.
- 레시피 입력 방식(LUT 우선 vs 편집기 우선)은 사용자 판단 대기.

## 6. 단계(마일스톤)
- M0 (진행 중) 프로브: UPnP 다운로드, PTP-IP 원격 확인
- M1 가져오기 MVP: **USB 중심** — 케이블 연결 감지 → 새 항목(JPEG·ARW) 목록 → 선택/전체 일괄 수신(진행률·재개·중복 제거) → 라이브러리·갤러리 저장. Wi-Fi 수신은 M1 범위 밖
- M2 레시피 엔진 + 자동 적용
- M3 BLE: 카메라 감지, 리모컨 셔터, 위치 동기화
- M4 원격 촬영(PTP-IP 라이브뷰·노출)
- M5 RAW 현상, 레시피 공유/QR, 폴더블 2분할 UI

## 7. 결정 필요
1. M1에 RAW(ARW) 수신 포함 여부(USB만 가능, Wi-Fi 보내기는 JPEG 변환)
2. 위치 동기화 우선순위(M3 → M1로 당길지)
3. 레시피 입력: LUT 파일만 vs 앱 내 편집기 우선
4. 앱 이름·패키지(현 프로브 dev.a7c.probe와 분리)
