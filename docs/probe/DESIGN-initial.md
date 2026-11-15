# 카메라 레시피 앱 (A7C용, 폰 처리형) — 설계 초안

작성 2026-11-14. 사용자 결정: "1" = A7C는 PMCA(카메라 내 앱) 미지원이므로, 카메라 사진을 폰으로 받아 폰에서 레시피(LUT·그레인 등)를 적용하는 앱.

## 전제 사실 (확인됨)
- A7C(ILCE-7C, 2020)는 PlayMemories Camera Apps 안드로이드 서브시스템이 없다. OpenMemories 호환 목록 최종 모델 = A6500/RX100M5(2016-10).
- 소니 공식 Camera Remote Command(PTP/PTP-IP 확장)와 Camera Remote SDK가 ILCE-7C를 지원 목록에 포함. PTP-IP는 TCP 15740, Wi-Fi Direct/ST 모드. 모델별 인터페이스 호환은 README 확인 필요(미확인). 상업적 이용은 사용 허락 신청 필요, 개인 사용 범위로 시작.
- 기본 경로: 카메라 [스마트폰으로 보내기] → Imaging Edge Mobile/Creators' App이 폰 폴더에 저장(RAW는 JPEG로 변환되어 전송).
- JPEGCAM·JPEG Cookbook의 앱/레시피는 타인 저작물 → 복제·스크래핑하지 않음. LUT(.cube)는 사용자가 가진 파일을 불러오는 방식.

## 목표 기능
1. 가져오기
   - M1: 폴더 감시 — Creators' App/Imaging Edge 저장 폴더(또는 사용자가 고른 폴더)에 새 사진이 생기면 자동 감지. 프로토콜 위험 0.
   - M2: USB-C 직결 — Android `android.mtp.MtpDevice`로 카메라(PTP/MTP) 사진 직접 가져오기.
   - M3(검증 후): Wi-Fi PTP-IP 직결 — 카메라 Wi-Fi Direct에 붙어 새 사진 수신. A7C 지원 여부를 실기로 먼저 확인.
2. 레시피 엔진(GPU, 24MP JPEG 기준 1~2초 목표)
   - 3D LUT(.cube 17/33/65) 삼선형/사면체 보간
   - 노출·화이트밸런스 이동·톤 커브·채도/색상별 조정
   - 그레인(자체 생성 노이즈 텍스처, 크기·강도·톤별), 비네트, 할레이션
   - 크롭 프리셋(XPAN 65:24 등)
3. 레시피 관리: 자체 JSON 형식, 이름·태그·즐겨찾기, 미리보기 썸네일, QR/파일 공유
4. 자동 모드: 선택 레시피를 새로 들어온 사진에 자동 적용, 원본 보존, 결과는 별도 앨범, EXIF 유지 + 레시피 이름 기록
5. (선택) ARW RAW 현상: LibRaw 기반 — 무거움, 후순위

## 기술 선택(제안)
- Android 네이티브(Kotlin + Jetpack Compose), 처리 OpenGL ES 3 셰이더(또는 Vulkan) — Galaxy Z Fold8 대상
- 배포: 개인 사이드로드(APK). Play 배포는 후순위
- iOS 제외

## 단계
- S0 검증 스파이크: (a) 폴더 감시 + LUT 1개 적용 E2E, (b) USB-C로 A7C 사진 목록·다운로드, (c) A7C PTP-IP Wi-Fi 연결 가능 여부
- S1 MVP: 폴더 감시 + 레시피 엔진(LUT·그레인·WB·톤) + 자동 적용 + 갤러리 저장
- S2: USB-C 직결
- S3: Wi-Fi 직결(S0c 통과 시)
- S4: 레시피 편집기·공유, RAW

## 위험·미확인
- A7C PTP-IP Wi-Fi 지원 여부(README 인터페이스 표 미확인)
- Android의 MTP 호스트로 소니 카메라 연결 시 USB 모드(PC Remote vs MTP) 설정 필요
- 셰이더 결과와 데스크톱 LUT 적용 결과 일치 검증 필요(색 정확도)
