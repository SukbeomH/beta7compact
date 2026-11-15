# M2 엔진·저장 계약

## 색 처리와 재현성

GLES 3.0 EGL pbuffer에서 RGBA8 입력/출력을 사용합니다. 자동 sRGB 텍스처 디코딩은 없습니다.
B7LT uint16을 float로 복원하여 RGBA32F 3D 텍스처에 올리고 `texelFetch`로 8개 격자를 읽어
명시적 **삼선형 보간**을 수행합니다. float 텍스처 선형 필터 확장에는 의존하지 않습니다.
입력 픽셀 → 선택적 S-Log3/Cine 변환 → 인코딩 sRGB에서 LUT/강도 보간 →
선형 sRGB에서 WB gain → `2^EV` 노출 → sRGB 인코딩 → 휘도 의존 그레인 → 비네트 → clamp 순서입니다.
WB는 temperature/tint 각각 -1~1, gains=`2^(temperature/2), 2^(-tint/2), 2^(-temperature/2)`입니다.
물리적인 켈빈 색순응을 주장하지 않습니다. 알파는 unpremultiply 후 처리하고 재 premultiply합니다.

그레인은 원본 좌표 / 크기를 floor하고 정수 해시(x,y,seed)의 하위 16bit를 사용합니다.
휘도 `L=.2126R+.7152G+.0722B`, 진폭 `amount*.2*(.25+.75*sqrt(L*(1-L)))`입니다.
같은 레시피/좌표/해상도는 같은 패턴을 만들며 타일 경계에서도 이어집니다.
비네트는 정규화 중심 거리 제곱으로 주변부를 줄입니다. 미리보기 좌표를 원본 크기로 환산합니다.
미리보기는 최대 변 1000px를 목표로 power-of-two 디코드하고, 저장에는 다운샘플을 사용하지 않습니다.
GL 업로드와 readback이 모두 첫 행을 같은 순서로 해석하므로 별도 Y flip은 하지 않습니다.
EXIF orientation 1~8은 최종 Canvas 좌표에 반영합니다.

## 메모리와 성능 목표

저장 디코더는 `BitmapRegionDecoder`, 타일 512×512, 작업은 직렬 WorkManager chain입니다.
원본의 가로×세로를 유지하고 방향에 따라 두 축만 교환합니다. 원본 전체 디코드/복제 회전은 없습니다.
전체 목적 ARGB bitmap 한 장(24MP 약 96MB) + 타일 bitmap 두 장(각 최대 1MiB) +
재사용 GPU readback(최대 1MiB) + GPU 입출력 타일 + 3D LUT가 필요합니다.
65³ LUT는 RGB CPU 약 3.2MiB, GPU RGBA32F 약 4.2MiB이며 로딩 시 일시 사본이 있습니다.
원본과 JPEG 임시 파일은 앱 cache에 두고 정상/실패 경로에서 제거합니다.
JPEG 플랫폼 encoder와 드라이버 내부 버퍼 크기는 별도이므로 총 RSS의 절대 상한을 주장하지 않습니다.
목적 bitmap 추정치가 앱 heap 상한의 60%를 넘으면 다운샘플 없이 한 줄 사유로 거부합니다.
할당/인코딩 OOM도 한 줄 고정 문구로 표시합니다. GL native 메모리도 실기에서 측정해야 합니다.

Fold8 24MP 수 초 내 저장은 목표이며 서버에서 측정한 달성값이 아닙니다.
기기에서 6000×4000 JPEG의 내장 LUT/사용자65³ LUT를 각각 3회 저장하고,
첫/후속 저장 시간·RSS/Java/native heap·발열·결과 6000×4000 보존을 기록해야 합니다.

## 결과·중단 복구

원본 URI에는 읽기만 수행합니다. 새 `MediaStore.Images` pending 행을
`DCIM/beta7compact/Recipes/recipe-<작업 UUID>.jpg`로 만들고 JPEG 품질 95로 저장합니다.
플랫폼이 읽고 쓸 수 있는 EXIF 태그를 복사하되 구조적 offset/thumbnail/색공간/크기/방향은
새 이미지에 맞춥니다. orientation=1, Software=`beta7compact 0.2.0`, ColorSpace=1입니다.
불투명한 MakerNote의 제조사별 의미나 플랫폼이 지원하지 않는 태그의 바이트 보존은 보증하지 않습니다.
결과 URI·불변 레시피 JSON·생성 시각은 별도 테이블에 저장하며 `photos.recipe`와 원본 행을 덮어쓰지 않습니다.

Room 작업에 결과 URI와 인코딩 완료 체크포인트를 기록합니다. 인코딩 전 중단은 그 결과 pending URI만
제거하고 재생성합니다. 인코딩 후 중단은 같은 URI를 게시하고 링크를 복구합니다.
완료 job/result ID는 같아서 배치 재개 시 완료본을 다시 만들지 않습니다.
취소/실패/스케줄 등록 전 중단 작업은 라이브러리의 ‘작업 재개’로 복구합니다.
MediaStore insert와 Room URI 기록 사이의 프로세스 강제 종료는 pending 고아 행을 남길 수 있습니다.
이는 원본이나 게시된 결과를 삭제하는 복구로 해결하지 않습니다.

자동 적용은 기본 꺼짐이며 편집 화면에서 기본 레시피를 먼저 지정해야 켤 수 있습니다.
원본 완료와 자동 적용 job 삽입을 같은 Room 트랜잭션으로 처리하여 부분 수신 성공도 대기열에 남깁니다.
WorkManager 스케줄 실패가 원본 완료를 되돌리지는 않으며 저장된 job은 수동 재개할 수 있습니다.
USB 동일성/해시 비교·카메라 명령·BLE·진단 로그 내용은 변경하지 않습니다.

ARW는 TIFF IFD/하위 IFD의 JPEG offset/length 또는 단일 JPEG strip에서 가장 큰 내장 JPEG를 추출합니다.
없는 형식은 한 줄로 거부합니다. RAW 센서 해상도로 업스케일하거나 full RAW 현상을 하지 않습니다.
UI에 내장 JPEG임을 표시하며 출력 크기는 그 JPEG의 크기입니다.

## 검증 실행

```sh
ANDROID_HOME=/path/to/sdk ANDROID_SDK_ROOT=/path/to/sdk ./gradlew assembleDebug test lint assembleDebugAndroidTest --continue --no-daemon
python3 tools/check_m1_migration.py
python3 tools/check_m2_migration.py
# 실제 GLES 3.0 Android 기기를 연결한 뒤:
ANDROID_HOME=/path/to/sdk ./gradlew connectedDebugAndroidTest
```

`GpuParityTest`: 내장 27 LUT × 입력변환 두 모드 × 256 RGB 패치에서 CPU와 각 채널 2/255 이내,
그레인/WB/EV/비네트/원본 좌표, 타일 이음새, EXIF 방향 경로를 검사합니다.
`ExportContractTest`: 합성 JPEG 원본 SHA 불변, 원해상도/방향, EXIF/Software, Room 스냅샷,
게시 후 체크포인트 재개 시 URI 재사용을 기기에서 검사합니다. 생성한 합성 자료만 정리합니다.
서버 JVM ΔE76 비교는 float 보간과 독립 double 보간의 산술 일치이며 실제 GPU/카메라 색차가 아닙니다.
