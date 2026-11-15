# TASK M1: beta7compact — USB 가져오기 + 위치 동기화 + 라이브러리

먼저 읽기: docs/ARCHITECTURE.md(특히 5b 사용자 결정), docs/probe/RESULT_S0.md, 프로브 소스 /home/agent/camera-recipe-app (읽기 전용 참고, 필요한 프로토콜 코드는 이 저장소로 옮겨 재작성·테스트). 실기 로그는 /home/agent/beta7compact-private/probe-logs/ (읽기만, **공개 저장소에 커밋 금지** — MAC·IP 포함).

## 앱
- 이름 beta7compact, 패키지 `dev.sukbeomh.beta7compact`, Kotlin + Compose, minSdk 29, targetSdk 35(실기 Android 17/SDK 37에서 동작해야 함). 대상: Sony A7C(ILCE-7C fw 2.03) ↔ Galaxy Z Fold8.
- 의존성: AndroidX(Compose, Room, WorkManager, Lifecycle), Kotlin Coroutines, Google Play services location은 쓰지 말고 플랫폼 LocationManager 사용. 그 외 외부 라이브러리 추가 금지.
- SDK: /home/agent/camera-recipe-app/.android-sdk (local.properties sdk.dir, git 제외). Gradle wrapper, 캐시는 저장소 안 무시 경로.

## M1 범위
1. USB 가져오기 (기본 경로)
   - USB_DEVICE_ATTACHED(Sony VID 0x054C) 감지 → 앱 열기/알림. 카메라가 MTP가 아닐 때 "카메라 설정 → USB 연결 → MTP" 한 줄 안내.
   - 연결 경로: 프로브에서 실증된 android.mtp.MtpDevice 경로를 기본, raw PTP(USB bulk)는 대체 경로(설정에서 선택 또는 MtpDevice 실패 시 1회 시도).
   - 카드의 객체 목록(JPEG, ARW, 필요 시 HIF) → 날짜별 그룹, 썸네일(GetThumb) 그리드, 이미 가져온 항목 표시.
   - [새 사진 모두 받기] / 선택 받기. WorkManager 포그라운드 작업 + 진행률 알림, 중단 시 재개, 중복 제거(파일명+크기+촬영시각), RAW+JPEG 쌍 묶기.
   - 저장: MediaStore DCIM/beta7compact/ (JPEG image/jpeg, ARW image/x-sony-arw). 원본 바이트 불변, 임시 파일→완료 후 게시(부분 파일 노출 금지), 크기 검증.
2. 위치 동기화 (BLE)
   - 카메라 BLE 발견(Sony Company ID 0x012D), 사용자가 고른 카메라를 페어링(본딩) 대상으로 저장.
   - 공개 커뮤니티 분석 자료만 근거로 위치 서비스(8000DD00: DD01 notify, DD11 write, DD21 read 관측됨)에 폰 GPS 위치·시각·시간대를 전송. 근거 문서/저장소 URL을 docs/BLE-LOCATION.md에 기록하고 패킷 형식을 JVM 테스트로 고정. 근거가 불충분하면 쓰기 구현을 멈추고 BLOCKED로 보고(추측 패킷 전송 금지).
   - 포그라운드 서비스(위치 타입), 켜기/끄기 토글, 전송 간격·마지막 전송 시각·실패 사유 표시. 카메라 쪽 필요 설정(Bluetooth 켬, 위치정보 연동 켬, 페어링 절차) 안내.
   - 리모컨(8000FF00)·Wi-Fi 서비스 쓰기는 M1 범위 아님.
3. 라이브러리
   - Room: 가져온 항목(원본 URI, 파일명, 크기, 촬영시각, 카메라 handle/storage, RAW/JPEG 쌍, 가져온 시각). 레시피 컬럼 자리만(M2).
   - 화면: 홈(USB·BLE·위치 상태 카드) / 가져오기 / 라이브러리 그리드 / 설정.
4. 공통: 폴더블(펼침/접힘) 레이아웃 깨지지 않게, 한국어 UI, 오류는 원인 한 줄.

## 검증
- ./gradlew assembleDebug test lint, 단위 테스트: PTP/MTP 목록→가져오기 계획(중복·쌍·재개), 저장 경로·MIME, BLE 위치 패킷 인코딩(근거 문서 예시값과 일치), 상태머신.
- 가짜 카메라 어댑터(인터페이스 분리)로 가져오기 흐름 통합 테스트.
- dist/beta7compact-0.1.0-debug.apk + sha256.
- 실기 확인 체크리스트 docs/M1-DEVICE-CHECK.md(사용자용 한국어).

## 경계
- 브랜치 `feat/m1-usb-location`에서 원자 커밋, 이 브랜치만 origin push 허용. master 직접 push·PR·merge·force push·--no-verify·git add -A 금지.
- 비밀·.env 금지, 실기 로그·MAC·개인 위치를 저장소에 넣지 말 것. JPEGCAM 등 타 앱 코드·자산 금지. 소니 SDK 바이너리 포함 금지.
- 완료 시 docs/RESULT_M1.md 작성 후 `M1-READY @<sha8>`로 멈춤. BLE 위치가 근거 부족이면 나머지를 완료하고 `M1-READY-PARTIAL @<sha8> (BLE BLOCKED)`.
