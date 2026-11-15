# S0 프로브 0.1.2 구현 결과

작성일: 2026-11-14 UTC. 기준은 0.1.1 `abb9a86`입니다. `docs/TASK_S0_PROBE.md`의 **0.1.2 수정·추가**와 실기 로그 2·3을 읽고 Wi-Fi/VPN 처리와 UPnP 사진 전송을 구현했습니다. USB raw·MTP 비교 경로와 기존 원격 셔터·BLE 경계를 유지했습니다. **0.1.2 APK의 실제 A7C 연결·사진 수신은 아직 미확인**입니다.

## 실기 근거

- 로그 2(0.1.0): USB=MTP 지정 후 VID `0x054C` PID `0x0D2A`, 단일 Still Image 인터페이스에서 `ILCE-7C v2.0`, 객체 214개, JPEG 5,618,643 bytes 저장과 `importFile` 251ms 성공을 확인했습니다. 초기 자동/대용량 모드의 실패를 MTP 모드에서도 실패한다고 일반화하지 않습니다.
- 같은 로그에서 VPN은 `WIFI|VPN` 속성을 가진 `tun0`, 카메라는 `wlan0 192.168.0.2/16`, 게이트웨이 `192.168.122.1`입니다. 기존 후보 선택은 VPN을 포함했고, `bindProcessToNetwork` 호출 직후 검사·다운로드·원격 검사가 중단됐습니다. 기존 `step OK`는 예외가 없었다는 로그이며 반환 true나 실제 바인딩 상태를 증명하지 않습니다. 로그에는 실제 `getBoundNetworkForProcess` 값이 없어 OS 내부 원인은 확정하지 않았습니다.
- 로그 3(0.1.0, VPN 꺼짐): 바인딩·SSDP·LOCATION XML·ContentDirectory 루트 Browse가 성공했습니다. SonyImagingDevice MediaServer의 `dd.xml`은 포트 64321, 루트 자식은 `PhotoRoot` 컨테이너입니다. XPushList·DigitalImaging 서비스도 광고됐고 TCP 15740은 `ECONNREFUSED`였습니다. 하위 사진·res·SCPD 액션 목록·UPnP JPEG 수신은 이 로그에 없습니다.

## 구현

- Wi-Fi이면서 VPN/tun이 아닌 후보 중 게이트웨이가 `192.168.122.1`이거나 입력 IPv4가 로컬 서브넷에 포함되는 네트워크를 고릅니다. 무관한 Wi-Fi를 임의 fallback으로 고르지 않으며, 후보 0개·복수는 진단 후 중단합니다. VPN 감지는 **“VPN 끄고 재시도 권장”** 경고만 남깁니다.
- 성공 판정은 `getBoundNetworkForProcess == selected`입니다. bind 반환값·실제 바인딩·선택값·ms를 남기며 반환 false여도 실제 바인딩이 일치하면 계속합니다. 불일치면 카메라 소켓을 열기 전에 중단합니다. 바인딩 요청 자체의 예외·검증 실패·통신 실패에도 이전 바인딩을 복원하고 재검증합니다. 복원 실패는 실패로 남기며 원래 오류를 보존합니다.
- PTP-IP 명령·이벤트 TCP는 선택 Network의 socketFactory, SSDP UDP는 Network.bindSocket, LOCATION·SCPD·Browse·JPEG HTTP는 Network.openConnection을 사용합니다. HTTP는 프록시를 명시적으로 사용하지 않습니다. 연결 생성·바인딩·실제 응답 단계의 예외를 기록합니다. 선택 네트워크 단절 시 셀룰러/기본 VPN으로 자동 재시도하지 않습니다.
- ContentDirectory는 `BrowseDirectChildren`, RequestedCount 50으로 루트 0부터 재귀 조회합니다. 깊이 6, 컨테이너 포함 총 2,000개 상한을 적용하며 페이지의 실제 NumberReturned만큼 StartingIndex를 전진합니다. 순환·중복 컨테이너·반복 페이지·비정상 카운터·SOAP Fault를 검사합니다. 트리·항목 수·완료 여부와 상위 res의 protocolInfo·size·resolution·URL 원문을 기록합니다. 제한 도달은 `complete=false`이며 전체 목록을 조사했다고 주장하지 않습니다.
- **Wi-Fi 검사**는 PTP-IP가 실패해도 UPnP 조회 성공 시 검사 성공으로 처리합니다. **Wi-Fi 사진 1장 받기**는 PTP-IP 실패 시에만 UPnP JPEG HTTP GET으로 전환합니다. PTP-IP 수신 성공 뒤 두 번째 사진을 받지 않으며, 검사 버튼은 사진을 받지 않습니다. 두 경로가 모두 실패하면 원래 원인과 보조 오류를 보존합니다.
- HTTP `image/jpeg` res 중 픽셀 수, 바이트 크기 순으로 최대 리소스 한 개를 고릅니다. 파일명·URL 동률 순서는 결정적으로 정합니다. 카메라에서 선택한 사진과 스마트폰 선택용 하위 폴더를 같은 탐색으로 처리합니다. 이 동작은 가짜 트리로 검증했으며 실제 두 카메라 모드에서의 성공은 미확인입니다.
- GET은 스트리밍하며 최대 128MiB, 연결/읽기 각각 8초, 수신 전체 120초를 적용합니다. 광고 크기·Content-Length가 있으면 실제 바이트 수와 대조하고 JPEG SOI를 검사합니다. 리다이렉트·부분 응답·HTTP 오류·크기 불일치·잘림은 실패로 처리합니다. 기존 PhotoStore가 pending 상태로 저장하고 검증 후 `Pictures/A7CProbe/`에 공개하며 실패 시 항목을 정리합니다.
- XPushList·DigitalImaging은 SCPDURL을 GET하여 액션 이름만 로그에 남깁니다. 해당 서비스 액션은 호출하지 않습니다. XML은 Android/JVM 표준 SAX로 파싱하고 DTD·외부 엔티티·과도한 크기/깊이를 거부합니다. 추가 라이브러리는 없습니다.
- PTP-IP refused에는 **“이 모드는 UPnP 전송, PTP-IP는 PC 원격 모드”** 안내를 남깁니다. USB raw/MTP·SonyRemote·BLE 구현 파일은 `abb9a86` 대비 변경하지 않았습니다.

## 산출물

- [0.1.2 APK](dist/a7c-probe-0.1.2.apk): debug 서명, 6,705,327 bytes.
- SHA-256: `dbdb7d1fed5a254e393bf233b191278e13285b6c986968206e6b6098b0159524`. [해시 파일](dist/a7c-probe-0.1.2.apk.sha256), [APK 메타데이터·동일성 기록](dist/validation/artifact-0.1.2.txt).
- 패키지 `dev.a7c.probe`, versionName `0.1.2`, versionCode `3`, minSdk `29`, compileSdk/targetSdk `35`. 화면·시작 로그도 0.1.2입니다.
- [한국어 설치·사용법](README_PROBE.md)을 갱신하고 0.1.0/0.1.1 배포물은 보존했습니다. 사용자가 수정한 `docs/TASK_S0_PROBE.md`는 직접 수정하거나 이번 구현 커밋에 포함하지 않았습니다.

## 서버 검증

| 검증 | 실행 결과 | 증거 |
| --- | --- | --- |
| `./gradlew assembleDebug test lint --continue --no-daemon` | BUILD SUCCESSFUL, 종료 0 | [전체 실행 기록](dist/validation/gradle-0.1.2.txt) |
| JVM 단위·소켓 테스트 | debug 73/73, release 73/73; 실패·오류·skip 0 | [테스트별 기록](dist/validation/tests-0.1.2.txt) |
| lint | 오류 0, 경고 7; 검사 억제·baseline 추가 없음 | [전체 lint 기록](dist/validation/lint-0.1.2.txt) |
| APK 서명 | Verifies, v2, 서명자 1 | [서명 검증](dist/validation/apk-signature-0.1.2.txt) |
| APK 버전·SDK·해시·빌드 산출물 동일성 | 0.1.2/code 3, SDK 29/35; 해시 일치, APK 바이트 일치 | [배포 검증](dist/validation/artifact-0.1.2.txt) |

기존 33개 테스트를 유지하고 40개를 추가했습니다. 추가 검증은 VPN/Wi-Fi 동시 연결·사용자 IP/서브넷·후보 모호성, 반환값과 실제 바인딩 불일치·복원, PTP 두 TCP 채널의 주입 socketFactory 사용, UPnP fallback 1회와 중복 저장 방지, PhotoRoot/앨범 탐색·50개 페이지·깊이 6·2,000개 상한·순환·반복/오류 페이지, 원본 후보 선택·XML 이스케이프/DTD 거부, HTTP 크기·응답·SOI 검증을 포함합니다. 로컬 가짜 HTTP 서버는 실제 앱의 SOAP/XML/HTTP 코어로 `POST /control` 두 번 후 최대 해상도 `GET /full.jpg` 한 번을 왕복했습니다.

개발 중 발생한 실패도 보존했습니다. [초기 Wi-Fi 검증](dist/validation/gradle-initial-0.1.2.txt)의 테스트 nullable 목록 타입 추론 오류를 고친 뒤 [Wi-Fi 검증](dist/validation/gradle-wifi-0.1.2.txt)은 통과했습니다. UPnP 추가 시 [소요 시간 함수 참조 오류](dist/validation/gradle-upnp-initial-0.1.2.txt)를 수정했고, [테스트 컴파일 기록](dist/validation/gradle-upnp-test-compile-0.1.2.txt)의 JDK HTTP 서버 모듈 미제공은 표준 ServerSocket 가짜 서버로 대체했습니다. 실패 테스트를 건너뛰지 않았으며 최종 전체 검증은 모두 통과했습니다.

lint 경고 7개는 기존 manifest/API·업데이트/backup·아이콘·문자열 리소스화 안내입니다. `allNetworks` deprecated 컴파일 경고도 남습니다.

## 경계와 남은 확인

- 작업 산출물·프로젝트 캐시·APK·검증 기록은 저장소 내부에 뒀습니다. 비밀·`.env`·자격증명 파일을 읽지 않았고, 추가 의존성·타 앱 코드/자산·원격 push는 없습니다.
- USB 기본 raw와 MTP 비교 버튼·독립 로그를 유지했습니다. 설정 쓰기·자동 촬영·BLE 쓰기/강제 페어링/알림 구독을 추가하지 않았습니다. Sony 서비스는 SCPD GET뿐이며 SOAP POST는 ContentDirectory Browse만 사용합니다.
- Linux/JVM 테스트는 Android Network/VPN 정책, Fold8 화면·권한, USB API, MediaStore의 실제 실행을 검증하지 않습니다. VPN이 직접 바인딩을 금지하는 OS 정책까지 우회 성공을 보장하지 않습니다.
- 실기 로그 3에는 사진 res가 없으므로 테스트 res·SCPD·하위 트리는 합성 데이터입니다. 원본 JPEG가 실제로 광고되는지, 선택해서 보내기/스마트폰에서 선택 양쪽의 트리·페이지·파일 크기·갤러리 저장은 0.1.2 설치 후 확인해야 합니다. 최대 해상도 리소스가 카메라 원본이라는 보장은 없으며, 탐색 상한에 닿으면 선택 범위도 제한됩니다.
- 0.1.0의 MTP 실기 성공을 0.1.2 raw USB 성공으로 간주하지 않습니다. 원격 셔터·라이브뷰·BLE 실기 호환과 Imaging Edge Mobile 전체 대체 가능성은 계속 미확인입니다.

## API 확인 자료

- [ConnectivityManager.getBoundNetworkForProcess](https://developer.android.com/reference/android/net/ConnectivityManager#getBoundNetworkForProcess()): 명시적 프로세스 바인딩 조회.
- [Android Network](https://developer.android.com/reference/android/net/Network): socketFactory·bindSocket·openConnection으로 선택 네트워크 사용.
- [SAXParserFactory](https://developer.android.com/reference/javax/xml/parsers/SAXParserFactory), [DefaultHandler2](https://developer.android.com/reference/org/xml/sax/ext/DefaultHandler2): 표준 XML 파서와 DTD 이벤트 처리. Android 런타임 실제 파싱은 후속 실기 대상입니다.
